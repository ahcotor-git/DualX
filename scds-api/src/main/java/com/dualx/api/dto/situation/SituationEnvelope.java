package com.dualx.api.dto.situation;

import com.dualx.core.push.UnitState;

import java.time.Instant;
import java.util.List;

/**
 * 态势信封（契约 situation.yaml → {@code SituationEnvelope}）。
 *
 * <p><b>HTTP Poll 响应体与 WebSocket 帧体是同一个本类</b>，这是双通道「同构载荷」的落地方式：
 * 客户端一套解析逻辑，WS 只把延迟从秒级降到毫秒级，Poll 负责兜底与恢复。</p>
 *
 * @param revision        全局单调递增的态势版本号，客户端必须保存并用于 {@code sinceRevision} 续传
 * @param full            true=全量快照（units 有效）；false=增量（changes / removedVestNums 有效）
 * @param running         当前是否有演习进行中
 * @param exerciseId      进行中的演习 ID，无则为 null
 * @param serverTime      服务端时间（UTC）
 * @param units           全量快照单元，仅 full=true 时出现
 * @param changes         增量变化单元，仅 full=false 时出现
 * @param removedVestNums 本增量中退场（从编成中移除）的马甲编号
 * @param hits            本区间内新增的命中事件
 * @param teamSummary     队伍汇总
 */
public record SituationEnvelope(
        long revision,
        boolean full,
        boolean running,
        String exerciseId,
        Instant serverTime,
        List<UnitState> units,
        List<UnitState> changes,
        List<Integer> removedVestNums,
        List<HitEvent> hits,
        List<TeamSummary> teamSummary) {
}
