package com.dualx.solver.algorithm;

import com.dualx.solver.config.ScdsUwbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Per-tag measurement state. Reading a snapshot never advances the estimator. */
public class UwbFilter implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(UwbFilter.class);
    public static final String STATUS_HELD="HELD", STATUS_OK="OK", STATUS_LOST="LOST",
            STATUS_RECOVER="RECOVER", STATUS_DEGRADED="DEGRADED", STATUS_UNINITIALIZED="UNINITIALIZED";
    private final ScdsUwbProperties props;
    private final int vestNum;
    private final Deque<Event> events = new ArrayDeque<>();
    private long lastFrameId=-1, lastMeasurementTs, lastValidTs, lastRxTs, candidateTs;
    private double rawX=Double.NaN, rawY=Double.NaN, smoothX=Double.NaN, smoothY=Double.NaN;
    private double length, width, candidateX, candidateY;
    private int frameCount, acceptedCount, candidateCount, usedCount;
    private long rejectedCount;
    private String status=STATUS_UNINITIALIZED, mapKey="";
    private Double residualM;
    private BufferedWriter csv;
    private int unflushed;
    private long lastFlushTs;
    private boolean csvFailed;

    public static final class Event {
        public final String type, message; public final Long ts;
        public Event(String type, Long ts, String message) { this.type=type;this.ts=ts;this.message=message; }
    }
    public static final class PushResult {
        public final boolean accepted; public final double xPct,yPct; public final String status,rejectReason;
        PushResult(boolean accepted,double x,double y,String status,String reason) {
            this.accepted=accepted;xPct=x;yPct=y;this.status=status;rejectReason=reason;
        }
    }
    public static final class State {
        public final double xPct,yPct; public final String status;
        public final long ts; public final double realLengthM,realWidthM;
        public final String mapKey;
        public State(double x,double y,String status) { this(x,y,status,0,0,0,""); }
        State(double x,double y,String status,long ts,double l,double w,String mapKey) {
            xPct=x;yPct=y;this.status=status;this.ts=ts;realLengthM=l;realWidthM=w;this.mapKey=mapKey;
        }
    }
    public UwbFilter(ScdsUwbProperties props,int vestNum) { this.props=props;this.vestNum=vestNum; }
    public synchronized void setMapKey(String key) { mapKey=key; }

    public synchronized PushResult push(long frameId,long ts,UwbSolver.Solve2DResult sol) {
        return update(frameId,ts,sol.xPct,sol.yPct,sol.residualM,sol.usedCount,sol.realLengthM,sol.realWidthM,
                sol.valid,sol.status);
    }
    /** Compatibility entry point: callers must provide true RMS metres and a new timestamp. */
    public synchronized PushResult push(double x,double y,Double rms,int used,long ts,
                                        double l,double w,Double priorX,Double priorY) {
        return update(lastFrameId+1,ts,x,y,rms,used,l,w,used>=2,"COMPAT");
    }
    private PushResult update(long id,long ts,double x,double y,Double rms,int used,double l,double w,
                              boolean solutionValid,String solverStatus) {
        if(id<=lastFrameId || ts<lastMeasurementTs)
            return result(false,"DUPLICATE_OR_OUT_OF_ORDER",ts);
        lastFrameId=id;lastMeasurementTs=ts;lastRxTs=ts;frameCount++;usedCount=used;residualM=rms;
        boolean quality=solutionValid && Double.isFinite(x) && Double.isFinite(y) && x>=0 && x<=1 && y>=0 && y<=1
                && Double.isFinite(l) && Double.isFinite(w) && l>0 && w>0 && rms!=null
                && Double.isFinite(rms) && rms>=0 && rms<=props.getResidualMaxMeters();
        if(!quality || (lastValidTs==0 && used<Math.max(2,props.getUsedMinCountStatus()))) {
            candidateCount=0;
            status=lastValidTs==0?STATUS_UNINITIALIZED:STATUS_HELD;
            event("MEASUREMENT_REJECT",ts,solverStatus);
            return result(false,solverStatus,ts);
        }
        length=l;width=w;
        if(lastValidTs==0) {
            accept(x,y,ts,used,true);
            event("FIRST_VALID",ts,"fresh="+used);
            return result(true,"FIRST_VALID",ts);
        }
        double displacement=Math.hypot((x-rawX)*l,(y-rawY)*w);
        boolean expired=props.getLostTimeoutMs()>0 && ts-lastValidTs>props.getLostTimeoutMs();
        double gateM=Math.max(0,props.getEmaJumpRejectDistCm())/100;
        if(expired || (props.isEmaJumpRejectEnforced() && gateM>0 && displacement>gateM)) {
            rejectedCount++;
            boolean fullQuality=used>=Math.max(2,props.getUsedMinCountStatus())
                    && rms<=props.getReacquireResidualMaxM();
            double dt=(ts-candidateTs)/1000d;
            boolean consistent=candidateCount>0 && dt>=0 && ts-candidateTs<=props.getRangeWindowMaxAgeMs()
                    && Math.hypot((x-candidateX)*l,(y-candidateY)*w)<=Math.max(.3,props.getJumpMaxMps()*dt);
            candidateCount=fullQuality?(consistent?candidateCount+1:1):0;
            candidateX=x;candidateY=y;candidateTs=ts;
            if(candidateCount>=Math.max(2,props.getReacquireMinFrames())) {
                accept(x,y,ts,used,true);candidateCount=0;
                event("REACQUIRED",ts,"new consistent frames after entry rejection");
                return result(true,"REACQUIRED",ts);
            }
            status=STATUS_RECOVER;
            event("ENTRY_REJECT",ts,"distanceM="+displacement+" candidates="+candidateCount);
            return result(false,"ENTRY_REJECT",ts);
        }
        candidateCount=0;
        accept(x,y,ts,used,false);
        return result(true,used<Math.max(2,props.getUsedMinCountStatus())?"DEGRADED_ACCEPT":"ACCEPT",ts);
    }
    private void accept(double x,double y,long ts,int used,boolean reset) {
        double dt=lastValidTs>0?Math.max(0,(ts-lastValidTs)/1000d):.05;
        if(reset || !props.isEmaEnabled()) {smoothX=x;smoothY=y;}
        else {
            // Preserve the configured alpha at 50ms while making the response independent of update rate.
            double alpha50=props.getEffectiveEmaAlpha();
            double alpha=1-Math.pow(1-alpha50,dt/.05);
            double nx=smoothX+alpha*(x-smoothX),ny=smoothY+alpha*(y-smoothY);
            double step=Math.hypot((nx-smoothX)*length,(ny-smoothY)*width);
            double limit=props.getEmaStepLimitCm()/100 * dt/.05;
            if(limit>0 && step>limit) {
                nx=smoothX+(nx-smoothX)*limit/step;ny=smoothY+(ny-smoothY)*limit/step;
                event("STEP_CLAMP_TIME",ts,"distanceM="+step+" limitM="+limit);
            }
            smoothX=nx;smoothY=ny;
        }
        rawX=x;rawY=y;lastValidTs=ts;acceptedCount++;
        status=used<Math.max(2,props.getUsedMinCountStatus())?STATUS_DEGRADED:STATUS_OK;
    }
    private PushResult result(boolean accepted,String reason,long now) {
        State s=getState(now);return new PushResult(accepted,s.xPct,s.yPct,s.status,reason);
    }
    public synchronized State getState(long now) {
        String st=status;
        if(lastValidTs>0 && props.getLostTimeoutMs()>0 && now-lastValidTs>props.getLostTimeoutMs()) st=STATUS_LOST;
        return new State(smoothX,smoothY,st,lastValidTs,length,width,mapKey);
    }
    public synchronized void resetForFloorChange() {
        close();lastFrameId=-1;lastMeasurementTs=0;lastValidTs=0;lastRxTs=0;candidateTs=0;
        rawX=rawY=smoothX=smoothY=Double.NaN;length=width=0;candidateCount=frameCount=acceptedCount=usedCount=0;
        rejectedCount=0;residualM=null;status=STATUS_UNINITIALIZED;events.clear();csvFailed=false;lastFlushTs=0;
    }
    private void event(String type,long ts,String message) {
        if(events.size()>=256)events.removeFirst();
        events.addLast(new Event(type,ts,message));
    }
    public synchronized List<Event> snapshotEvents(int tail) {
        List<Event> list=new ArrayList<>(events);
        return new ArrayList<>(list.subList(Math.max(0,list.size()-Math.max(0,tail)),list.size()));
    }
    public void setManualOverride(boolean ignored) { /* No manual lock in the measurement estimator. */ }
    public synchronized double getHoldXPct(){return smoothX;}
    public synchronized double getHoldYPct(){return smoothY;}
    public synchronized int getEmaFrameCount(){return acceptedCount;}
    public synchronized int getFrameCountSnapshot(){return frameCount;}
    public synchronized int getLastUsedCountSnapshot(){return usedCount;}
    public long getJumpDtClampCountSnapshot(){return 0;}
    public long getUsed3DeadlockSkipCountSnapshot(){return 0;}
    public long getJumpBypassCountSnapshot(){return 0;}
    public synchronized long getLastRxTs(){return lastRxTs;}
    public synchronized long getLastFrameId(){return lastFrameId;}
    public synchronized String getMapKey(){return mapKey;}
    public synchronized void discardThrough(long frameId){lastFrameId=Math.max(lastFrameId,frameId);}

    /** Called exactly once per consumed frame, including failed solves and entry rejections. */
    public synchronized void recordDiagnostics(long frameId,long rxTs,long solveTs,UwbSolver.Solve2DResult sol,
                                               PushResult result,State display) {
        String line=String.format(Locale.ROOT,
                "%d,%d,%d,%d,%s,%s,%s,%s,%s,%s,%d,%d,%s,%.8f,%s,%s,%s,%s,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%s,%d,%d",
                frameId,rxTs,solveTs,vestNum,mapKey,vector(sol.rawMm),vector(sol.filteredMm),vector(sol.ageMs),
                vector(sol.sigmaM),String.join(";",sol.usedBsIds),sol.validMask,sol.usedCount,sol.status,sol.cost,
                sol.residualM,sol.gdop,result.accepted,result.rejectReason,sol.xPct,sol.yPct,rawX,rawY,
                smoothX,smoothY,display.xPct,display.yPct,display.status,lastValidTs,candidateCount);
        int sample=props.getLocationLogSampleN();
        if(!result.accepted || (sample>0 && frameCount%sample==1) || sample==1)
            log.info("[UWB-FRAME] build={} {}",props.getBuildVersion(),line);
        if(!props.isDiagnosticCsvEnabled() || csvFailed)return;
        try {
            if(csv==null) {
                Path dir=Paths.get("logs");Files.createDirectories(dir);
                Path file=dir.resolve("uwb_frames_"+vestNum+"_"+UUID.randomUUID()+".csv");
                csv=Files.newBufferedWriter(file,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                csv.write("frame_id,rx_ts,solve_ts,vest,map_key,raw_mm,median_mm,age_ms,sigma_m,used_ids,valid_mask,used_count,solver_status,huber_cost,rms_m,gdop,push_accepted,reason,sol_x,sol_y,hold_x,hold_y,smooth_x,smooth_y,display_x,display_y,state_status,valid_ts,reacquire_count");
                csv.newLine();
            }
            csv.write(line);csv.newLine();unflushed++;
            if(unflushed>=20 || solveTs-lastFlushTs>=1000){csv.flush();unflushed=0;lastFlushTs=solveTs;}
        } catch(IOException ex) {csvFailed=true;log.warn("[UWB-CSV] write failed",ex);close();}
    }
    private static String vector(int[] v){return Arrays.toString(v).replace(", ",";");}
    private static String vector(long[] v){return Arrays.toString(v).replace(", ",";");}
    private static String vector(double[] v){return Arrays.toString(v).replace(", ",";");}
    @Override public synchronized void close() {
        if(csv!=null)try{csv.close();}catch(IOException ex){log.warn("[UWB-CSV] close failed",ex);}
        csv=null;unflushed=0;
    }
}
