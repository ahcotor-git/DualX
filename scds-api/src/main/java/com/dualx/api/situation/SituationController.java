package com.dualx.api.situation;

import com.dualx.api.dto.situation.HitEventList;
import com.dualx.core.push.HitEvent;
import com.dualx.core.push.SituationEnvelope;
import com.dualx.core.push.TeamSummary;
import com.dualx.core.push.UnitState;
import com.dualx.core.state.SituationEnvelopeFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 实时态势接口（契约 paths/situation.yaml）。
 *
 * <p><b>双通道硬约束</b>：态势必须同时提供 WebSocket 与低频 HTTP Poll 两条通道，且返回
 * <b>同构载荷</b>（同一个 {@link SituationEnvelope}）。WebSocket 只把延迟从秒级降到毫秒级，
 * <b>不得替代 Poll</b>——客户端即使 WS 已连接，也必须保持低频（不超过 10s）的兜底轮询：
 * WS 可能静默半开，而 Poll 是唯一能独立把客户端从任意 revision 恢复到当前态的通道。</p>
 *
 * <p>WebSocket 端点 {@code /ws/v1/situation}（{@code watchSituation}）不在本类：其握手与保活
 * 已由 {@code com.dualx.api.ws.WebSocketConfig} 与 {@code SituationWebSocketHandler} 装配。</p>
 *
 * <p><b>本层不做业务判断</b>：信封组装、{@code full/增量} 判定、超窗与编成外马甲两类失败
 * 全在 core 的 {@link SituationEnvelopeFactory}；本层只做契约形状的搬运，失败以
 * {@code BusinessException} 交给全局异常出口映射（超窗 → 409 {@code SITUATION_REVISION_TOO_OLD}、
 * 编成外马甲 → 404 {@code DEVICE_VEST_NOT_FOUND}）。在控制器里再判一次「超没超窗」会让
 * REST 与 WS 两条入口各持一份口径。</p>
 */
@RestController
@Validated
public class SituationController {

    private final SituationEnvelopeFactory situations;

    public SituationController(SituationEnvelopeFactory situations) {
        this.situations = situations;
    }

    /**
     * 拉取态势（全量快照 或 增量）。
     *
     * <p>不带 {@code sinceRevision} → 全量快照（{@code full=true}）；带 {@code sinceRevision=N}
     * → revision 大于 N 的变化（{@code full=false}）；N 已超出服务端保留窗口（含早于本场切换点）
     * → 409 {@code SITUATION_REVISION_TOO_OLD}，客户端应丢弃本地缓存改为全量拉取。</p>
     *
     * <p><b>{@code includeSnapshotOnFirst} 目前不影响响应</b>：设计 06 §4.5 明确「无演习进行中时
     * 仍返回一帧空快照」，理由是这个代码块本身就是让客户端拿到 lastRevision 的唯一途径
     * ——不返回会让刚启动的客户端无从锚定 revision。参数按契约保留，行为不变。</p>
     *
     * <p>{@code hitsLimit} 只作用于全量帧的 {@code hits}（从头起至多条数）。增量帧的 {@code hits}
     * 恒为空：命中不推 revision，{@code seq} 与 {@code revision} 互不推导，命中补拉走
     * {@link #listHits}。</p>
     */
    @GetMapping("/api/v1/situation")
    public SituationEnvelope getSituation(
            @RequestParam(required = false) Long sinceRevision,
            @RequestParam(required = false, defaultValue = "true") boolean includeSnapshotOnFirst,
            @RequestParam(required = false, defaultValue = "200") @Min(0) @Max(500) int hitsLimit) {
        return sinceRevision == null ? situations.full(hitsLimit) : situations.delta(sinceRevision);
    }

    /**
     * 查询单个马甲的实时状态；不在本场编成内（含无演习进行中）时为 404。
     *
     * <p>{@code @PreAuthorize} 只收「谁能看哪一号」：指挥台令牌可见全部马甲，单兵设备令牌
     * 只见自身（D04）。它不改「本层不做业务判断」的取向——编成内/外的 404 仍由 core 判定，
     * 这里只是授权层的一次资源范围裁剪。</p>
     */
    @GetMapping("/api/v1/situation/vests/{vestNum}")
    @PreAuthorize("@deviceScope.allowSelf(#vestNum, authentication)")
    public UnitState getVestState(@PathVariable int vestNum) {
        return situations.unit(vestNum);
    }

    /** 各队伍存活/阵亡汇总，只含有编成的队伍。 */
    @GetMapping("/api/v1/situation/teams/summary")
    public List<TeamSummary> getTeamSummary() {
        return situations.teamSummary();
    }

    /**
     * 命中流水（增量）：{@code seq > sinceSeq} 的命中，按 seq 升序，至多 {@code limit} 条。
     *
     * <p>{@code latestSeq} 取本批末条（列表已按 seq 升序）——客户端把它作为下次的 {@code sinceSeq}。
     * 这是命中面板的<b>唯一</b>补拉入口，与态势增量帧无关（见 {@link #getSituation}）。</p>
     */
    @GetMapping("/api/v1/situation/hits")
    public HitEventList listHits(
            @RequestParam(required = false) Long sinceSeq,
            @RequestParam(required = false, defaultValue = "200") @Min(1) @Max(500) int limit) {
        List<HitEvent> items = situations.hits(sinceSeq, limit);
        Long latestSeq = items.isEmpty() ? null : items.get(items.size() - 1).seq();
        return new HitEventList(items, latestSeq);
    }
}
