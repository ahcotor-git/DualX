package com.dualx.api.situation;

import com.dualx.api.dto.situation.HitEventList;
import com.dualx.api.dto.situation.SituationEnvelope;
import com.dualx.api.dto.situation.TeamSummary;
import com.dualx.api.dto.situation.UnitState;
import com.dualx.api.error.NotImplementedException;
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
 * <p>骨架期只登记路由、参数与返回类型，方法体统一抛 {@link NotImplementedException}。</p>
 */
@RestController
public class SituationController {

    /** 拉取态势（全量快照 或 增量）。 */
    @GetMapping("/api/v1/situation")
    public SituationEnvelope getSituation(
            @RequestParam(required = false) Long sinceRevision,
            @RequestParam(required = false, defaultValue = "true") boolean includeSnapshotOnFirst,
            @RequestParam(required = false, defaultValue = "200") int hitsLimit) {
        throw new NotImplementedException("getSituation");
    }

    /** 查询单个马甲的实时状态。 */
    @GetMapping("/api/v1/situation/vests/{vestNum}")
    public UnitState getVestState(@PathVariable int vestNum) {
        throw new NotImplementedException("getVestState");
    }

    /** 各队伍存活/阵亡汇总。 */
    @GetMapping("/api/v1/situation/teams/summary")
    public List<TeamSummary> getTeamSummary() {
        throw new NotImplementedException("getTeamSummary");
    }

    /** 命中流水（增量）。 */
    @GetMapping("/api/v1/situation/hits")
    public HitEventList listHits(
            @RequestParam(required = false) Long sinceSeq,
            @RequestParam(required = false, defaultValue = "200") int limit) {
        throw new NotImplementedException("listHits");
    }
}
