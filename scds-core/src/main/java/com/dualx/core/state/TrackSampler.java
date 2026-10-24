package com.dualx.core.state;

import com.dualx.core.domain.CoordinateSystem;
import com.dualx.core.domain.GeoPoint;
import com.dualx.core.domain.MotionState;
import com.dualx.core.domain.SampleReason;
import com.dualx.core.domain.TrackSample;
import com.dualx.core.push.UnitState;
import com.dualx.protocol.VestStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 轨迹采样的<b>降采样判定</b>（设计 06 §4.4）。输入是单元的实时视图与相对开演的毫秒偏移，
 * 输出「是否该记一点、以什么理由记」，用于把 200ms 的逐帧上报压缩成可复盘的轨迹。
 *
 * <p><b>只由态势唯一写手线程调用</b>（{@code SituationStateService} 的状态变化点），因此内部
 * 的「每马甲上次采样点」用普通 {@link HashMap} 即可，不需要加锁。</p>
 *
 * <p><b>理由优先级</b>（设计 06 §4.4 的表，自上而下短路）：</p>
 * <ol>
 *   <li>{@link #onEvent} 显式指定的 {@link SampleReason#EVENT}——命中/阵亡/判活/判伤/充弹等离散点，
 *       必记，且不受「状态没变」抑制；</li>
 *   <li>{@link SampleReason#STATE_CHANGE}——{@code status} 或 {@code motion} 与上次采样不同
 *       （含离线扫描判出的 {@code LIVE→OFFLINE}）；</li>
 *   <li>{@link SampleReason#DISPLACEMENT}——坐标相对上次采样位移越过阈值；</li>
 *   <li>{@link SampleReason#TIMER}——距上次采样已达定时周期。</li>
 * </ol>
 *
 * <p><b>首次观察</b>记为 {@code STATE_CHANGE}：一个单元第一次进入采样视野时（例如 {@code OFFLINE→LIVE}
 * 的首个心跳）没有任何「上次采样」可比，把它算作状态确立而非位移/定时，语义最贴近事实。</p>
 *
 * <p><b>阈值单位</b>：室内的 {@code xPct/yPct} 是归一化 0~1（见 {@link GeoPoint}），故室内阈值就是
 * 归一化平面距离；室外用 WGS-84 上的大地距离（米）。设计 06 §4.7 表里「室内像素 / 室外米」指的是
 * 同一对参数，列名以 {@link GeoPoint} 为准。</p>
 *
 * <p><b>配置未闭合声明（D10）</b>：构造参数就是 {@code scds.state.sample-interval-ms} /
 * {@code scds.state.sample-displacement-indoor} / {@code scds.state.sample-displacement-outdoor} 的落点，
 * 取值属容量候选，现场数据出来前不得据其宣称达标（由装配层绑定，本类不设默认值）。</p>
 */
public final class TrackSampler {

    /** WGS-84 平均地球半径（米），用于室外位移的大地距离。 */
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private final long intervalMs;
    private final double indoorDisplacement;
    private final double outdoorDisplacementMeters;

    /** 每马甲的上次采样点；只有写手线程读写。 */
    private final Map<Integer, LastSample> lastByVest = new HashMap<>();

    /**
     * @param intervalMs               定时采样周期（{@code scds.state.sample-interval-ms}），必须 &gt; 0
     * @param indoorDisplacement       室内位移阈值，归一化平面距离（{@code scds.state.sample-displacement-indoor}），
     *                                 必须 &ge; 0
     * @param outdoorDisplacementMeters 室外位移阈值，米（{@code scds.state.sample-displacement-outdoor}），
     *                                 必须 &ge; 0
     */
    public TrackSampler(long intervalMs, double indoorDisplacement, double outdoorDisplacementMeters) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("采样周期必须 > 0：" + intervalMs);
        }
        if (indoorDisplacement < 0 || outdoorDisplacementMeters < 0) {
            throw new IllegalArgumentException(
                    "位移阈值不得为负：indoor=" + indoorDisplacement + ", outdoor=" + outdoorDisplacementMeters);
        }
        this.intervalMs = intervalMs;
        this.indoorDisplacement = indoorDisplacement;
        this.outdoorDisplacementMeters = outdoorDisplacementMeters;
    }

    /**
     * 装载新演习时调用：清空「上次采样点」，避免与上一场的采样点作差（{@code tMs} 已归零，
     * 隔着演习比较会得出错误的位移/定时结论）。幂等。
     */
    public void reset() {
        lastByVest.clear();
    }

    /**
     * 离散事件点：必记一行，{@link SampleReason#EVENT}。命中、阵亡、判活、判伤、充弹等由调用方
     * 在状态变更处显式调用；这一行同时刷新「上次采样点」，使紧随其后的同值观察不再重复记点。
     *
     * @param tMs 相对开演的毫秒偏移，与 {@code t_hit_event.t_ms} 同原点
     */
    public TrackSample onEvent(UUID exerciseId, UnitState view, int tMs) {
        return emit(exerciseId, view, tMs, SampleReason.EVENT);
    }

    /**
     * 常规观察点：按 {@code STATE_CHANGE > DISPLACEMENT > TIMER} 判定，无一条命中时不记点。
     *
     * @param tMs 相对开演的毫秒偏移
     * @return 该记的采样点；本轮不该记时为空
     */
    public Optional<TrackSample> onObservation(UUID exerciseId, UnitState view, int tMs) {
        Objects.requireNonNull(view, "view");
        LastSample last = lastByVest.get(view.vestNum());
        SampleReason reason = null;
        if (last == null) {
            reason = SampleReason.STATE_CHANGE;
        } else if (last.status != view.status() || last.motion != view.motion()) {
            reason = SampleReason.STATE_CHANGE;
        } else if (displaced(last.position(), view.position())) {
            reason = SampleReason.DISPLACEMENT;
        } else if ((long) tMs - last.tMs >= intervalMs) {
            reason = SampleReason.TIMER;
        }
        return reason == null ? Optional.empty() : Optional.of(emit(exerciseId, view, tMs, reason));
    }

    /** 该马甲是否已有采样基准（诊断用）。 */
    public boolean tracked(int vestNum) {
        return lastByVest.containsKey(vestNum);
    }

    private TrackSample emit(UUID exerciseId, UnitState view, int tMs, SampleReason reason) {
        Objects.requireNonNull(view, "view");
        lastByVest.put(view.vestNum(), new LastSample(tMs, view.status(), view.motion(), view.position()));
        return new TrackSample(
                0L,
                exerciseId,
                view.vestNum(),
                tMs,
                view.status(),
                view.hp(),
                view.ammo1(),
                view.ammo2(),
                view.motion(),
                view.positionSource(),
                view.signalQuality(),
                view.position(),
                reason);
    }

    /** 位移是否越过阈值。两侧不可比（一侧无坐标、坐标系切换）时按「有位移」处理，宁可多记不可漏记。 */
    private boolean displaced(GeoPoint a, GeoPoint b) {
        if (a == null || b == null) {
            return a != b;
        }
        if (a.coordinateSystem() != b.coordinateSystem()) {
            return true;
        }
        if (a.coordinateSystem() == CoordinateSystem.INDOOR) {
            if (a.xPct() == null || a.yPct() == null || b.xPct() == null || b.yPct() == null) {
                return true;
            }
            return Math.hypot(a.xPct() - b.xPct(), a.yPct() - b.yPct()) >= indoorDisplacement;
        }
        if (a.wgs84Lng() == null || a.wgs84Lat() == null || b.wgs84Lng() == null || b.wgs84Lat() == null) {
            return true;
        }
        return haversineMeters(a.wgs84Lat(), a.wgs84Lng(), b.wgs84Lat(), b.wgs84Lng())
                >= outdoorDisplacementMeters;
    }

    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    private record LastSample(int tMs, VestStatus status, MotionState motion, GeoPoint position) {
    }
}
