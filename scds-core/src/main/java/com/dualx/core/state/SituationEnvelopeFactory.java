package com.dualx.core.state;

import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.push.HitEvent;
import com.dualx.core.push.SituationEnvelope;
import com.dualx.core.push.TeamSummary;
import com.dualx.core.push.UnitState;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 态势<b>线载荷组装器</b>（M4 PUSH-MODEL）：把 {@link SituationQuery} 的读侧原语拼成契约的
 * {@link SituationEnvelope}，并在这里做「线载荷 ↔ 库实体」的形状转换。
 *
 * <p><b>为什么独立于 {@code SituationQuery}</b>：读侧只提供原语（快照 / 增量窗口 / 命中流水 /
 * 队伍汇总 / 单马甲），<b>不组装信封</b>（它的类注释已如此声明）。组装是「同构」的落点——
 * HTTP Poll 与 WebSocket 首帧/补齐必须产出<b>同一个</b> {@link SituationEnvelope}，若两处各拼一遍，
 * 同构就从类型级保证退化为人工纪律（设计 02 §3 目标 2）。故组装收在这一处，Poll 与 WS 共用。</p>
 *
 * <p><b>为什么在 core 而不是 api</b>：{@code SituationStateService} 在变化时要调
 * {@code SituationPushPort} 推送（设计 02 §5），那时它必须能构造信封，而 core 不能依赖 api
 * ——工厂落在 core 是唯一能让两侧共用同一份组装逻辑的位置。</p>
 *
 * <p><b>失败出口</b>：本类抛 {@link BusinessException} 并只携带 {@link ApiErrorCode}，
 * HTTP 状态码由 api 的异常出口映射（超窗 → 409，编成外马甲 → 404）。</p>
 */
public final class SituationEnvelopeFactory {

    private final SituationQuery query;
    private final Clock clock;

    public SituationEnvelopeFactory(SituationQuery query, Clock clock) {
        this.query = Objects.requireNonNull(query, "query");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------ 信封

    /**
     * 全量帧（{@code full=true}）：{@code units} 为当前全部参演单元，无演习时为空列表。
     *
     * <p>{@code hits} 取「从头起至多 {@code hitsLimit} 条」（与 {@code GET /situation/hits}
     * 同一游标口径，客户端凭返回的末条 {@code seq} 继续增量拉取）。之所以不从「最近 N 条」倒取：
     * 契约的命中分页是<b>前向游标</b>，倒取会让客户端无法判断自己漏了更早的哪一段。</p>
     */
    public SituationEnvelope full(int hitsLimit) {
        return new SituationEnvelope(
                query.revision(), true, query.running(), exerciseId(), clock.instant(),
                query.snapshot(), List.of(), List.of(), hits(null, hitsLimit), teamSummary());
    }

    /**
     * 增量帧（{@code full=false}）：{@code changes} 为 {@code sinceRevision} 之后的单元级变化，
     * 可能为空列表（表示已是最新）。
     *
     * <p><b>{@code hits} 恒为空</b>：{@code HitEvent.seq} 由库生成、与 {@code revision} <b>互不推导</b>
     * （设计 02 §4.4），且命中<b>不推</b> revision（只有心跳值变化、阵亡、判活才推），故仅凭
     * {@code sinceRevision} 无法定出该携带哪一段命中。命中补拉一律走契约已有的
     * {@code GET /situation/hits?sinceSeq=}——契约对该端点的定位正是「命中记录面板单独补拉」。</p>
     *
     * <p><b>{@code teamSummary} 也恒为空</b>：「增量帧只带变化」是<u>字段级</u>口径——客户端把
     * {@code changes} 合进本地单元表后自己就能算出汇总，权威汇总只在全量帧（{@code full=true}）里出现。
     * 若增量帧也带权威汇总，则 WS 推的增量帧（写侧不能依赖读侧聚合）与 Poll 的增量帧会是两种形状，
     * 「同构」就从类型级保证退化成了人工纪律。</p>
     *
     * <p>{@code removedVestNums} 目前恒为空：本场编成在开演时装载后固定，演习中不移除成员。</p>
     *
     * @throws BusinessException {@link ApiErrorCode#SITUATION_REVISION_TOO_OLD}
     *                           ——{@code sinceRevision} 已超出服务端保留窗口（含早于本场切换点），
     *                           客户端应丢弃本地缓存改为全量拉取
     */
    public SituationEnvelope delta(long sinceRevision) {
        Optional<List<UnitState>> window = query.changesSince(sinceRevision);
        if (window.isEmpty()) {
            throw new BusinessException(ApiErrorCode.SITUATION_REVISION_TOO_OLD,
                    "持有的 revision 已超出服务端保留窗口，请改为全量拉取：sinceRevision=" + sinceRevision
                            + "，当前 revision=" + query.revision());
        }
        return new SituationEnvelope(
                query.revision(), false, query.running(), exerciseId(), clock.instant(),
                List.of(), window.get(), List.of(), List.of(), List.of());
    }

    // ------------------------------------------------------------------ 单马甲 / 队伍汇总 / 命中

    /**
     * 单个马甲的实时状态。
     *
     * @throws BusinessException {@link ApiErrorCode#DEVICE_VEST_NOT_FOUND} 不在本场编成内（含无演习进行中）
     */
    public UnitState unit(int vestNum) {
        return query.unit(vestNum).orElseThrow(() -> new BusinessException(
                ApiErrorCode.DEVICE_VEST_NOT_FOUND, "本场编成内没有该马甲：" + vestNum));
    }

    /** 各队伍存活/阵亡汇总（线载荷）。 */
    public List<TeamSummary> teamSummary() {
        return query.teamSummary().stream().map(SituationEnvelopeFactory::toWire).toList();
    }

    /**
     * 命中流水（线载荷）：{@code seq > sinceSeq} 的命中，按 seq 升序，至多 {@code limit} 条。
     *
     * @param sinceSeq null 表示从头拉取
     */
    public List<HitEvent> hits(Long sinceSeq, int limit) {
        return query.hitsSince(sinceSeq, limit).stream().map(SituationEnvelopeFactory::toWire).toList();
    }

    /** 当前是否有演习进行中（{@code running=false} 时全量帧的 {@code units} 必为空）。 */
    public boolean running() {
        return query.running();
    }

    // ------------------------------------------------------------------ 线载荷转换

    /**
     * 库实体 → 线载荷。两者<b>有意分开</b>（设计 02 §4.3）：库实体多带 {@code exerciseId} 与
     * {@code tMs} 两个库列，且用基本类型；线载荷的字段形状由契约决定，用可空包装类型。
     */
    private static HitEvent toWire(com.dualx.core.domain.HitEvent event) {
        return new HitEvent(
                event.seq(), event.at(), event.hitVestNum(), event.shooterNum(), event.shooterIsSoldier(),
                event.shooterWeaponKind(), event.hitPart(), event.lethal(), event.position());
    }

    /**
     * 读侧计数载体 → 线载荷。
     *
     * <p>公开是给**写侧**用的：{@code SituationStateService} 推全量帧时要带权威汇总，它只能拿到
     * {@link SituationQuery#teamSummaryOf} 的结果，而「计数载体 → 线载荷」的换算只能有一处。</p>
     */
    public static TeamSummary toWire(SituationQuery.TeamCounts counts) {
        return new TeamSummary(counts.team(), counts.total(), counts.live(), counts.dead(),
                counts.offline(), counts.hitsDealt());
    }

    private String exerciseId() {
        UUID id = query.exerciseId();
        return id == null ? null : id.toString();
    }
}
