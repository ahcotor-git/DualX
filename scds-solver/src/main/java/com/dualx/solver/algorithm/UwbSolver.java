package com.dualx.solver.algorithm;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dualx.solver.config.ScdsUwbProperties;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** All fresh TOA ranges share one standardized Huber objective. Units inside the solver are metres. */
public class UwbSolver {
    private final ScdsUwbProperties props;
    private final ConcurrentHashMap<Integer, RangeState> states = new ConcurrentHashMap<>();

    private static final class Sample {
        final double range;
        final long ts;
        Sample(double range, long ts) { this.range = range; this.ts = ts; }
    }
    private static final class RangeState {
        final List<Deque<Sample>> windows = new ArrayList<>();
        final long[] lastSeen = new long[8];
        String meta;
        long frameId = -1, ts;
        Solve2DResult result;
        RangeState() { for (int i = 0; i < 8; i++) windows.add(new ArrayDeque<>()); }
    }
    public static final class BsPoint {
        public final int id;
        public final double meterX, meterY, distM, sigmaM;
        BsPoint(int id, double x, double y, double range, double sigma) {
            this.id = id; meterX = x; meterY = y; distM = range; sigmaM = sigma;
        }
    }
    public static final class Solve2DResult {
        public final double xPct, yPct, cost, gdop, realLengthM, realWidthM;
        public final int usedCount, validMask;
        public final List<String> usedBsIds;
        /** Unstandardized RMS in metres; never a Huber cost. */
        public final Double residualM;
        public final String status;
        public final boolean valid;
        public final int[] rawMm, filteredMm;
        public final double[] sigmaM;
        public final long[] ageMs;
        Solve2DResult(double x, double y, List<BsPoint> bs, Double rms, double cost, double gdop,
                      String status, boolean valid, double length, double width,
                      int[] raw, int[] filtered, double[] sigma, long[] age) {
            xPct = x; yPct = y; usedCount = bs.size(); residualM = rms; this.cost = cost;
            this.gdop = gdop; this.status = status; this.valid = valid;
            realLengthM = length; realWidthM = width;
            List<String> ids = new ArrayList<>();
            int mask = 0;
            for (BsPoint b : bs) { ids.add("bs" + b.id); mask |= 1 << (b.id - 1); }
            usedBsIds = Collections.unmodifiableList(ids); validMask = mask;
            rawMm = raw.clone(); filteredMm = filtered.clone(); sigmaM = sigma.clone(); ageMs = age.clone();
        }
    }
    private static final class Geometry {
        final double[][] anchors = new double[8][];
        double length, width;
        Geometry(String json) {
            JSONObject meta = JSONUtil.parseObj(json);
            double l = meta.getDouble("realLengthCm", 0d) / 100, w = meta.getDouble("realWidthCm", 0d) / 100;
            if (!finitePositive(l) || !finitePositive(w)) throw new IllegalArgumentException("dimensions");
            double minX = Double.POSITIVE_INFINITY, maxX = -minX, minY = minX, maxY = -minX;
            for (int i = 0; i < 8; i++) {
                JSONObject b = meta.getJSONObject("bs" + (i + 1));
                if (b == null || b.getDouble("xPx") == null || b.getDouble("yPx") == null) {
                    if (i < 4) throw new IllegalArgumentException("missing anchor");
                    continue;
                }
                double x = b.getDouble("xPx"), y = b.getDouble("yPx");
                if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("anchor");
                anchors[i] = new double[]{x, y};
                if (i < 4) {
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
            double lenPx = distance(anchors[0], anchors[1]), widPx = distance(anchors[0], anchors[3]);
            if (!finitePositive(lenPx) || !finitePositive(widPx) || maxX <= minX || maxY <= minY)
                throw new IllegalArgumentException("scale");
            double ax = Math.pow(anchors[1][0]-anchors[0][0],2);
            double ay = Math.pow(anchors[1][1]-anchors[0][1],2);
            double bx = Math.pow(anchors[3][0]-anchors[0][0],2);
            double by = Math.pow(anchors[3][1]-anchors[0][1],2);
            double det = ax*by-ay*bx;
            double sx2, sy2;
            if (Math.abs(det) > 1e-10 * lenPx*lenPx*widPx*widPx) {
                sx2 = (l*l*by-w*w*ay)/det;
                sy2 = (w*w*ax-l*l*bx)/det;
            } else {
                if (Math.abs(l/lenPx-w/widPx) > .01*Math.max(l/lenPx,w/widPx))
                    throw new IllegalArgumentException("ambiguous anisotropic scale");
                sx2 = sy2 = Math.pow(l/lenPx,2);
            }
            if (!finitePositive(sx2) || !finitePositive(sy2)) throw new IllegalArgumentException("inconsistent scale");
            double sx = Math.sqrt(sx2), sy = Math.sqrt(sy2);
            length = (maxX - minX) * sx; width = (maxY - minY) * sy;
            for (double[] a : anchors) if (a != null) {
                a[0] = (a[0] - minX) * sx; a[1] = (a[1] - minY) * sy;
            }
        }
    }
    public UwbSolver(ScdsUwbProperties props) { this.props = props; }
    public void reset(int vest) { states.remove(vest); }
    public void clear() { states.clear(); }
    public Solve2DResult rejectedFrame(int[] ranges, String reason) {
        int[] raw = new int[8];
        if (ranges != null) System.arraycopy(ranges, 0, raw, 0, Math.min(8, ranges.length));
        return failure(null, null, Collections.emptyList(), reason, 0, 0, raw, new int[8], new double[8], new long[8]);
    }

    public Solve2DResult solve2D(int vest, String meta, int[] ranges, Double px, Double py) {
        RangeState state = states.computeIfAbsent(vest, key -> new RangeState());
        synchronized (state) {
            return solve2D(vest, meta, ranges, px, py, Math.max(System.currentTimeMillis(), state.ts + 1), state.frameId + 1);
        }
    }
    public Solve2DResult solve2D(String meta, int[] ranges, Double px, Double py) {
        return new UwbSolver(props).solve2D(0, meta, ranges, px, py);
    }

    public Solve2DResult solve2D(int vest, String meta, int[] ranges, Double px, Double py, long ts, long frameId) {
        return solve2D(vest, meta, ranges, px, py, ts, frameId, ts - 50);
    }

    public Solve2DResult solve2D(int vest, String meta, int[] ranges, Double px, Double py,
                                long ts, long frameId, long priorAcceptedTs) {
        RangeState state = states.computeIfAbsent(vest, key -> new RangeState());
        synchronized (state) {
            if (!Objects.equals(state.meta, meta)) {
                for (Deque<Sample> q : state.windows) q.clear();
                Arrays.fill(state.lastSeen, 0);
                state.meta = meta; state.frameId = -1; state.ts = 0; state.result = null;
            }
            if (frameId <= state.frameId && state.result != null) return state.result;
            int[] raw = new int[8], filtered = new int[8];
            if (ranges != null) System.arraycopy(ranges, 0, raw, 0, Math.min(8, ranges.length));
            double[] sigma = new double[8]; long[] age = new long[8];
            List<BsPoint> bs = new ArrayList<>();
            Geometry g;
            try { g = new Geometry(meta); }
            catch (Exception ex) {
                return failure(px, py, bs, "HELD_META_INVALID", 0, 0, raw, filtered, sigma, age);
            }
            if (ts < state.ts) return failure(px, py, bs, "HELD_OUT_OF_ORDER", g.length, g.width, raw, filtered, sigma, age);
            final int capacity = Math.max(1, Math.max(props.getMedianWindowFrames(), props.getHuberSigmaFrames()));
            final long expiry = Math.max(1, props.getRangeWindowMaxAgeMs());
            for (int i = 0; i < 8; i++) {
                Deque<Sample> q = state.windows.get(i);
                age[i] = state.lastSeen[i] > 0 ? ts - state.lastSeen[i] : -1;
                while (!q.isEmpty() && ts - q.peekFirst().ts > expiry) q.removeFirst();
                double r = raw[i] / 1000d;
                if (raw[i] <= 0 || r < props.getBsValidMinMeters() || r > props.getBsValidMaxMeters()
                        || g.anchors[i] == null) continue;
                q.addLast(new Sample(r, ts));
                while (q.size() > capacity) q.removeFirst();
                state.lastSeen[i] = ts; age[i] = 0;
                double med = median(tail(q, Math.max(1, props.getMedianWindowFrames())));
                sigma[i] = robustSigma(tail(q, Math.max(2, props.getHuberSigmaFrames())));
                filtered[i] = (int)Math.round(med * 1000);
                if (bs.size() < Math.max(2, props.getMaxBsCount()))
                    bs.add(new BsPoint(i + 1, g.anchors[i][0], g.anchors[i][1], med, sigma[i]));
            }
            boolean prior = px != null && py != null && Double.isFinite(px) && Double.isFinite(py);
            if (bs.size() < 2 || (bs.size() == 2 && !prior)) {
                state.result = failure(px, py, bs, "HELD_INSUFFICIENT_GEOMETRY", g.length, g.width, raw, filtered, sigma, age);
            } else {
                double[] guess = prior ? new double[]{px * g.length, py * g.width} : null;
                // Only underdetermined/mirror-ambiguous two-anchor tracking needs a position prior.
                double elapsed = Math.max(0, (ts - priorAcceptedTs) / 1000d);
                double priorSigma = props.getTwoAnchorPriorSigmaM() + Math.min(10, elapsed) * props.getJumpMaxMps();
                double priorWeight = bs.size() == 2 ? 1 / (priorSigma * priorSigma) : 0;
                List<double[]> seeds = new ArrayList<>();
                if (prior) seeds.add(guess);
                seeds.add(new double[]{g.length / 2, g.width / 2});
                seeds.add(linearSeed(bs, g));
                for (int ix = 1; ix <= 3; ix++) for (int iy = 1; iy <= 3; iy++)
                    seeds.add(new double[]{g.length * ix / 4, g.width * iy / 4});
                double[] best = null; double bestCost = Double.POSITIVE_INFINITY;
                for (double[] seed : seeds) {
                    double[] candidate = optimize(seed, bs, guess, priorWeight, g);
                    double c = cost(candidate, bs, guess, priorWeight);
                    if (c < bestCost - 1e-9 || (Math.abs(c - bestCost) <= 1e-9 && prior &&
                            (best == null || distance(candidate, guess) < distance(best, guess)))) {
                        best = candidate; bestCost = c;
                    }
                }
                double ss = 0;
                for (BsPoint b : bs) { double e = Math.hypot(best[0] - b.meterX, best[1] - b.meterY) - b.distM; ss += e * e; }
                double rms = Math.sqrt(ss / bs.size()), gdop = geometryDop(best, bs);
                boolean good = Double.isFinite(bestCost) && Double.isFinite(gdop) &&
                        gdop <= props.getSolverMaxGdop() && rms <= props.getResidualMaxMeters();
                String status = !good ? "HELD_QUALITY" : bs.size() < Math.max(3, props.getUsedMinCountStatus()) ?
                        "DEGRADED_HUBER" : "OK_HUBER_ALL";
                state.result = new Solve2DResult(best[0] / g.length, best[1] / g.width, bs, rms,
                        bestCost, gdop, status, good, g.length, g.width, raw, filtered, sigma, age);
            }
            state.frameId = frameId; state.ts = ts;
            return state.result;
        }
    }

    private Solve2DResult failure(Double px, Double py, List<BsPoint> bs, String status, double l, double w,
                                  int[] raw, int[] filtered, double[] sigma, long[] age) {
        return new Solve2DResult(px != null ? px : Double.NaN, py != null ? py : Double.NaN, bs, null,
                Double.NaN, Double.POSITIVE_INFINITY, status, false, l, w, raw, filtered, sigma, age);
    }
    private double robustSigma(double[] values) {
        double[] differences = new double[Math.max(0, values.length - 1)];
        for (int i = 0; i < differences.length; i++) differences[i] = values[i + 1] - values[i];
        double center = differences.length > 0 ? median(differences) : 0;
        for (int i = 0; i < differences.length; i++) differences[i] = Math.abs(differences[i] - center);
        double estimate = differences.length > 1 ? 1.4826 * median(differences) / Math.sqrt(2) : 0;
        return Math.max(props.getRangeSigmaMinM(), Math.min(props.getRangeSigmaMaxM(), estimate));
    }
    private static double[] tail(Deque<Sample> q, int n) {
        Sample[] all = q.toArray(new Sample[0]); int count = Math.min(all.length, n);
        double[] result = new double[count];
        for (int i = 0; i < count; i++) result[i] = all[all.length - count + i].range;
        return result;
    }
    private static double median(double[] values) {
        double[] copy = values.clone(); Arrays.sort(copy); int n = copy.length;
        return n == 0 ? 0 : n % 2 == 1 ? copy[n / 2] : (copy[n / 2 - 1] + copy[n / 2]) / 2;
    }
    private double cost(double[] p, List<BsPoint> bs, double[] prior, double priorWeight) {
        double result = 0, k = Math.max(.1, props.getHuberK());
        for (BsPoint b : bs) {
            double z = Math.abs((Math.hypot(p[0] - b.meterX, p[1] - b.meterY) - b.distM) / b.sigmaM);
            result += z <= k ? .5 * z * z : k * z - .5 * k * k;
        }
        if (prior != null && priorWeight > 0) result += .5 * priorWeight * Math.pow(distance(p, prior), 2);
        return result;
    }
    private double[] optimize(double[] seed, List<BsPoint> bs, double[] prior, double priorWeight, Geometry g) {
        double[] p = {clamp(seed[0], 0, g.length), clamp(seed[1], 0, g.width)};
        double damping = .001, k = Math.max(.1, props.getHuberK());
        for (int iter = 0; iter < Math.max(5, props.getSolverMaxIterations()); iter++) {
            double hxx = damping, hxy = 0, hyy = damping, gx = 0, gy = 0;
            for (BsPoint b : bs) {
                double dx = p[0] - b.meterX, dy = p[1] - b.meterY, d = Math.max(1e-8, Math.hypot(dx, dy));
                double e = d - b.distM, z = Math.abs(e / b.sigmaM);
                double weight = (z <= k ? 1 : k / z) / (b.sigmaM * b.sigmaM);
                double ux = dx / d, uy = dy / d;
                hxx += weight * ux * ux; hxy += weight * ux * uy; hyy += weight * uy * uy;
                gx += weight * ux * e; gy += weight * uy * e;
            }
            if (prior != null && priorWeight > 0) {
                hxx += priorWeight; hyy += priorWeight;
                gx += priorWeight * (p[0] - prior[0]); gy += priorWeight * (p[1] - prior[1]);
            }
            double det = hxx * hyy - hxy * hxy;
            if (det <= 1e-15) break;
            double sx = (-hyy * gx + hxy * gy) / det, sy = (hxy * gx - hxx * gy) / det;
            double[] next = {clamp(p[0] + sx, 0, g.length), clamp(p[1] + sy, 0, g.width)};
            if (cost(next, bs, prior, priorWeight) <= cost(p, bs, prior, priorWeight)) {
                double moved = distance(p, next); p = next; damping = Math.max(1e-8, damping / 3);
                if (moved < 1e-5) break;
            } else damping *= 10;
        }
        return p;
    }
    private static double[] linearSeed(List<BsPoint> bs, Geometry g) {
        BsPoint a = bs.get(0);
        double xx=0, xy=0, yy=0, bx=0, by=0;
        for (int i=1;i<bs.size();i++) {
            BsPoint b=bs.get(i); double x=2*(b.meterX-a.meterX), y=2*(b.meterY-a.meterY);
            double rhs=a.distM*a.distM-b.distM*b.distM+b.meterX*b.meterX+b.meterY*b.meterY-a.meterX*a.meterX-a.meterY*a.meterY;
            xx+=x*x;xy+=x*y;yy+=y*y;bx+=x*rhs;by+=y*rhs;
        }
        double det=xx*yy-xy*xy;
        return det > 1e-9 ? new double[]{(yy*bx-xy*by)/det,(xx*by-xy*bx)/det} : new double[]{g.length/2,g.width/2};
    }
    public static double geometryDop(double[] p, List<BsPoint> bs) {
        double xx=0,xy=0,yy=0;
        for (BsPoint b : bs) {
            double dx=p[0]-b.meterX,dy=p[1]-b.meterY,d=Math.hypot(dx,dy);
            if (d < 1e-8) continue;
            double x=dx/d,y=dy/d;xx+=x*x;xy+=x*y;yy+=y*y;
        }
        double det=xx*yy-xy*xy;
        return det > 1e-10 ? Math.sqrt((xx+yy)/det) : Double.POSITIVE_INFINITY;
    }
    private static double distance(double[] a,double[] b) { return Math.hypot(a[0]-b[0],a[1]-b[1]); }
    /** Convert the physical BS1-4 bounding-box percentages to the frontend's full-image percentages. */
    public static double[] toImagePct(String meta, double x, double y, double imageWidth, double imageHeight) {
        if (!finitePositive(imageWidth) || !finitePositive(imageHeight))
            throw new IllegalArgumentException("missing image dimensions");
        JSONObject json = JSONUtil.parseObj(meta);
        double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=-minX,maxY=-minX;
        for (int i=1;i<=4;i++) {
            JSONObject b=json.getJSONObject("bs"+i);
            double bx=b.getDouble("xPx"),by=b.getDouble("yPx");
            minX=Math.min(minX,bx);maxX=Math.max(maxX,bx);minY=Math.min(minY,by);maxY=Math.max(maxY,by);
        }
        return new double[]{clamp((minX+x*(maxX-minX))/imageWidth,0,1),
                clamp((minY+y*(maxY-minY))/imageHeight,0,1)};
    }
    private static double clamp(double x,double lo,double hi) { return Math.max(lo,Math.min(hi,x)); }
    private static boolean finitePositive(double x) { return Double.isFinite(x) && x > 0; }
}
