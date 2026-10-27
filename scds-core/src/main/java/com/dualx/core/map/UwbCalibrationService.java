package com.dualx.core.map;

import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.solver.config.ScdsUwbProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UWB 中心基准标定（契约 {@code startUwbCalibration} / {@code getUwbCalibration} /
 * {@code clearUwbCalibration}，设计 06 §3 非目标、实施计划 M3 CALIBRATION）。
 *
 * <p><b>它做什么</b>：把被测单元摆到场地正中心，采 {@code calibSampleFrames} 帧 41B 测距，
 * 校验这段窗口内测距是「静止」的，然后把现场卷尺实测的长宽<b>覆盖写回</b>
 * {@code t_map.uwb_meta} 的 {@code realLengthCm} / {@code realWidthCm}——
 * 解算链路只读那一列，所以现场调优的沉淀点就是它。</p>
 *
 * <p><b>为什么不自己反推几何</b>（本段口径，2026-10-27 裁定）：实测长宽以**卷尺值为准**，
 * 采样只承担「这段时间单元没动」的一致性校验。反推一套几何意味着在 core 里再写一份必然与
 * 冻结的 {@code scds-solver.UwbSolver.Geometry} 漂移的实现，而解算仍然只认 {@code UwbSolver}
 * ——两份实现只会互相打脸。因此本类<b>不触碰任何解算算法</b>，{@code scds-solver} 一字未改。</p>
 *
 * <p><b>线程模型</b>：{@code start}/{@code status}/{@code clear} 由 HTTP 线程调用，
 * {@link #onFrame(int, int[])} 由态势状态机的<b>唯一写手线程</b>调用（见 {@code SituationStateService}）。
 * 会话表用 {@link ConcurrentHashMap}，单个会话的字段只在持有该会话锁时改写——校验与写回都在
 * 锁内完成，故最高频的采样路径无全局锁。</p>
 *
 * <p><b>不抛异常上抛到写手线程</b>：{@link #onFrame(int, int[])} 一旦抛出，写手循环会跳过这条
 * UWB 事件，连带丢掉那一帧的坐标更新。因此完成阶段的任何失败都折叠成 {@code FAILED} 状态，
 * 只记日志、只在 {@code message} 里留原因。</p>
 *
 * <p><b>状态是进程内的</b>：{@code RUNNING} 的进度与 {@code IDLE/FAILED} 都只活在内存里（重启即丢），
 * 这与设计 01 §4.1「链路句柄与 UWB 标定上下文都只在内存里」一致；{@code SUCCEEDED} 的
 * {@code appliedAt} 落库由后续分段（V11 校准列）补上。</p>
 */
public final class UwbCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(UwbCalibrationService.class);

    /** 与 {@code MapLedgerService} 同一取向：库内冻结 JSON 的进出用同一个 ObjectMapper。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 参与静止校验与长宽基准的只有四角；bs5~bs8 是可选项，标定不依赖它们。 */
    private static final int CORNERS = 4;

    /** 校准状态机（契约 {@code UwbCalibrationStatus.state}，取值与 api 侧枚举逐字同名）。 */
    public enum State {
        IDLE,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    /**
     * 一次标定的状态快照（契约 {@code UwbCalibrationStatus}）。
     *
     * <p>字段顺序与契约一致，接口层只需一层同名映射，不再重新编排。</p>
     *
     * @param state         当前状态
     * @param progressPct   进度百分比；非 {@code RUNNING} 时为 {@code null}
     * @param sampledFrames 已采样帧数
     * @param appliedAt     结果生效时刻；未成功写回时为 {@code null}
     * @param message       状态说明，可空
     */
    public record Status(State state, Integer progressPct, int sampledFrames, Instant appliedAt, String message) {

        /** 从未开始过（或已被清除）时的回答。 */
        static Status idle() {
            return new Status(State.IDLE, null, 0, null, null);
        }
    }

    private final MapLedgerService ledger;
    private final ScdsUwbProperties uwb;
    private final Clock clock;

    /** 每张底图一份会话；键是底图 id，不是演习 id——标定发生在演习之前。 */
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /**
     * @param ledger 读写 {@code uwb_meta} 的入口。标定<b>不改</b>四角几何，只覆盖两个实测长宽，
     *               因此直接复用它的「结构性与边界性校验」，不在本类重写一遍校验规则
     * @param uwb    {@code scds.uwb.*}：采样帧数与静止阈值的来源（solver 刻意不带 Spring 注解，
     *               绑定由装配层恢复）
     * @param clock  UTC 时钟；{@code appliedAt} 与态势时间戳同源
     */
    public UwbCalibrationService(MapLedgerService ledger, ScdsUwbProperties uwb, Clock clock) {
        this.ledger = ledger;
        this.uwb = uwb;
        this.clock = clock;
    }

    /**
     * 触发一次标定（契约 {@code startUwbCalibration}）。
     *
     * <p>前置条件是**这张底图已经标定过四角**（{@link MapLedgerService#uwbMeta(UUID)} 会为此抛
     * {@code MAP_NOT_CALIBRATED}）：没有四角就没有可写入的骨架，中心基准校准补的是两个长宽值，
     * 不是整份 {@code uwb_meta}。</p>
     *
     * @param sampleFrames 采样帧数；{@code null} 或 ≤0 时取 {@code scds.uwb.calib-sample-frames}
     * @param bs1ToBs2Cm   现场卷尺实测长度（厘米），{@code null} 表示不覆盖该维
     * @param bs1ToBs3Cm   现场卷尺实测宽度（厘米），{@code null} 表示不覆盖该维。
     *                     <b>字段名与语义错位留档</b>：契约该字段名写作 {@code bs1ToBs3Cm}、描述却是
     *                     「实测宽度」，而已定的四角口径是 {@code bs1↔bs4} 为宽度基准。本段按
     *                     **契约描述**解释为宽度，写回 {@code realWidthCm}，并保持字段名不动
     *                     （见 decision-register「CAL-2 口径」）
     * @return 受理后的状态（{@code RUNNING}）
     * @throws BusinessException {@code MAP_NOT_CALIBRATED} 底图还没标四角；
     *                           {@code MAP_UWB_META_INCOMPLETE} 两个实测值都没给；
     *                           {@code MAP_CALIBRATION_IN_PROGRESS} 该底图已有标定在跑；
     *                           {@code VALIDATION_FAILED} 采样帧数 ≤ 0 或实测值不是正整数厘米
     */
    public Status start(UUID mapId, Integer sampleFrames, Double bs1ToBs2Cm, Double bs1ToBs3Cm) {
        // 先要四角骨架：读不出来就是 404 MAP_NOT_CALIBRATED，与 getUwbMeta 同一口径。
        String baseMeta = ledger.uwbMeta(mapId);
        int target = sampleFrames == null ? uwb.getCalibSampleFrames() : sampleFrames;
        if (target <= 0) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED,
                    "采样帧数必须是正整数，实际 " + target);
        }
        if (bs1ToBs2Cm == null && bs1ToBs3Cm == null) {
            throw new BusinessException(ApiErrorCode.MAP_UWB_META_INCOMPLETE,
                    "未提供现场实测长宽：中心基准校准至少要有一个卷尺实测值才能写回 uwb_meta");
        }
        requirePositiveWholeCm(bs1ToBs2Cm, "bs1ToBs2Cm");
        requirePositiveWholeCm(bs1ToBs3Cm, "bs1ToBs3Cm");

        Session fresh = new Session(mapId, baseMeta, target, bs1ToBs2Cm, bs1ToBs3Cm,
                uwb.getCalibStaticMaxDiffCm() * 10.0d);
        // 同键并发启动必须在一次原子操作里判「是否已有在跑」，否则两个请求会各自覆盖会话。
        sessions.compute(mapId, (key, current) -> {
            if (current != null && current.state == State.RUNNING) {
                throw new BusinessException(ApiErrorCode.MAP_CALIBRATION_IN_PROGRESS,
                        "该底图已有标定正在进行：" + mapId);
            }
            return fresh;
        });
        log.info("UWB 标定开始：mapId={}，目标帧数={}，静止阈值={}mm", mapId, target, fresh.staticMaxDiffMm);
        return fresh.snapshot();
    }

    /** 查询状态（契约 {@code getUwbCalibration}）；这张图没标过就回答 {@code IDLE}。 */
    public Status status(UUID mapId) {
        Session s = sessions.get(mapId);
        return s == null ? Status.idle() : s.snapshot();
    }

    /**
     * 清除标定结果（契约 {@code clearUwbCalibration}）。
     *
     * <p>也承担「取消」：正在跑的那次会被一并丢弃，下一次 {@link #start} 不再撞
     * {@code MAP_CALIBRATION_IN_PROGRESS}。本段只清进程内状态——被写回 {@code uwb_meta} 的
     * 实测长宽是原创数据，清除标定不等于把它们改回去（要改回去就再标一次）。</p>
     */
    public void clear(UUID mapId) {
        if (sessions.remove(mapId) != null) {
            log.info("UWB 标定状态已清除：mapId={}", mapId);
        }
    }

    /**
     * 采样入口：由态势状态机的唯一写手线程逐帧调用。
     *
     * <p>只认<b>首个</b>出现过的马甲编号（中心基准校准只针对摆在中心的那一只），其它马甲的帧
     * 直接忽略——不然同一段窗口里混入别人的测距，静止校验与长宽基准都会被污染。</p>
     *
     * <p>本方法<b>绝不抛出</b>（理由见类注释）：完成阶段的失败一律折叠成 {@code FAILED}。</p>
     */
    public void onFrame(int vestNum, int[] rangesMm) {
        if (rangesMm == null || rangesMm.length < CORNERS) {
            return;
        }
        for (Session s : sessions.values()) {
            if (s.state != State.RUNNING) {
                continue;
            }
            synchronized (s) {
                if (s.state != State.RUNNING) {
                    continue;
                }
                if (s.vestNum == null) {
                    s.vestNum = vestNum;
                } else if (s.vestNum != vestNum) {
                    continue;
                }
                s.samples[s.sampledFrames++] = Arrays.copyOf(rangesMm, CORNERS);
                if (s.sampledFrames >= s.target) {
                    finish(s);
                } else {
                    s.progressPct = s.sampledFrames * 100 / s.target;
                }
            }
        }
    }

    // ------------------------------------------------------------------ 完成阶段

    /** 校验静止性 → 覆盖写回实测长宽 → 置终态；任何失败都折叠成 {@code FAILED}，不上抛。 */
    private void finish(Session s) {
        try {
            String violation = staticViolation(s);
            if (violation != null) {
                fail(s, violation);
                return;
            }
            String updated = withTapeMeasures(s);
            ledger.saveUwbMeta(s.mapId, updated);
            s.state = State.SUCCEEDED;
            s.appliedAt = clock.instant();
            s.progressPct = 100;
            s.message = "中心基准校准完成：已写回现场实测长宽";
            log.info("UWB 标定完成：mapId={}，采样帧数={}", s.mapId, s.sampledFrames);
        } catch (RuntimeException e) {
            fail(s, "写回 uwb_meta 失败：" + e.getMessage());
            log.warn("UWB 标定写回失败：mapId={}", s.mapId, e);
        }
    }

    /**
     * 静止判定：逐角取采样窗口内的<b>中位数</b>，任一帧偏离中位数超过阈值即判「人动了」。
     *
     * <p>判据用的是<b>原始测距</b>而非解算坐标：标定发生在演习之前，本场底图几何尚未装载到
     * {@code IndoorPositionResolver}，拿不到坐标。阈值取 {@code scds.uwb.calib-static-max-diff-cm}
     * （冻结配置键），含义由「EMA 坐标变化」落到「测距偏离」，本段落档于此。</p>
     *
     * @return 违反原因；静止时返回 {@code null}
     */
    private static String staticViolation(Session s) {
        for (int corner = 0; corner < CORNERS; corner++) {
            int[] column = new int[s.sampledFrames];
            int max = Integer.MIN_VALUE;
            int min = Integer.MAX_VALUE;
            for (int i = 0; i < s.sampledFrames; i++) {
                int v = s.samples[i][corner];
                column[i] = v;
                max = Math.max(max, v);
                min = Math.min(min, v);
            }
            int median = medianOf(column);
            int deviation = Math.max(Math.abs(max - median), Math.abs(min - median));
            if (deviation > s.staticMaxDiffMm) {
                return "被测单元在采样期间移动：bs" + (corner + 1) + " 测距偏离中位数 " + deviation
                        + "mm，超过阈值 " + s.staticMaxDiffMm + "mm";
            }
        }
        return null;
    }

    /** 在既有 {@code uwb_meta} 上覆盖两个实测长宽；未提供的维保持原值。 */
    private static String withTapeMeasures(Session s) {
        JsonNode parsed;
        try {
            parsed = JSON.readTree(s.baseMetaJson);
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException("底图冻结 JSON 读不出来", broken);
        }
        if (!(parsed instanceof ObjectNode root)) {
            throw new IllegalStateException("底图 uwb_meta 顶层不是 JSON 对象");
        }
        if (s.tapeLengthCm != null) {
            root.put("realLengthCm", s.tapeLengthCm);
        }
        if (s.tapeWidthCm != null) {
            root.put("realWidthCm", s.tapeWidthCm);
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException("底图冻结 JSON 写不出去", broken);
        }
    }

    private void fail(Session s, String reason) {
        s.state = State.FAILED;
        s.progressPct = null;
        s.message = reason;
        log.warn("UWB 标定失败：mapId={}，原因={}", s.mapId, reason);
    }

    private static int medianOf(int[] values) {
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static void requirePositiveWholeCm(Double cm, String field) {
        if (cm == null) {
            return;
        }
        if (cm <= 0 || cm != Math.rint(cm)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED,
                    field + " 必须是正整数厘米（> 0），当前为 " + cm);
        }
    }

    /** 一次标定的全部可变状态；字段只在持有本对象锁时改写。 */
    private static final class Session {

        private final UUID mapId;
        /** 起始时刻的 {@code uwb_meta} 原文：覆盖写回时以它为骨架，避免丢四角或他人字段。 */
        private final String baseMetaJson;
        private final int target;
        private final Double tapeLengthCm;
        private final Double tapeWidthCm;
        private final double staticMaxDiffMm;
        private final int[][] samples;

        private State state = State.RUNNING;
        private int sampledFrames;
        private Integer progressPct = 0;
        private Instant appliedAt;
        private String message;
        private Integer vestNum;

        Session(UUID mapId, String baseMetaJson, int target, Double tapeLengthCm, Double tapeWidthCm,
                double staticMaxDiffMm) {
            this.mapId = mapId;
            this.baseMetaJson = baseMetaJson;
            this.target = target;
            this.tapeLengthCm = tapeLengthCm;
            this.tapeWidthCm = tapeWidthCm;
            this.staticMaxDiffMm = staticMaxDiffMm;
            this.samples = new int[target][CORNERS];
        }

        synchronized Status snapshot() {
            return new Status(state, progressPct, sampledFrames, appliedAt, message);
        }
    }
}
