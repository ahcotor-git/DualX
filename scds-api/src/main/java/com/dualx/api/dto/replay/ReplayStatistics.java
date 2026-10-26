package com.dualx.api.dto.replay;

import com.dualx.core.push.TeamSummary;
import com.dualx.protocol.HitPart;

import java.util.List;
import java.util.Map;

/**
 * 复盘统计（契约 domain.yaml → {@code ReplayStatistics}），
 * {@code GET /api/v1/replays/{replayId}/statistics} 的响应体。
 *
 * <p>契约里 topShooters / hitPartDistribution / timeline 的元素是匿名内联对象，
 * 故以嵌套 record 承载，不额外建文件。</p>
 *
 * @param replayId            复盘 ID
 * @param teams               队伍战损汇总，复用 situation 域共享 DTO {@code TeamSummary}
 * @param topShooters         命中排行
 * @param hitPartDistribution 命中部位分布
 * @param timeline            时间轴上的存活人数曲线
 */
public record ReplayStatistics(String replayId, List<TeamSummary> teams, List<TopShooter> topShooters,
                               List<HitPartCount> hitPartDistribution, List<TimelineBucket> timeline) {

    /**
     * 命中排行条目。
     *
     * @param vestNum   马甲编号
     * @param name      受击者姓名，未登记为 null
     * @param hitsDealt 命中他方次数
     * @param kills     击杀数
     */
    public record TopShooter(Integer vestNum, String name, Integer hitsDealt, Integer kills) {
    }

    /**
     * 命中部位分布条目。
     *
     * @param hitPart 击中部位
     * @param count   命中次数
     */
    public record HitPartCount(HitPart hitPart, Integer count) {
    }

    /**
     * 存活人数曲线采样段。
     *
     * @param t          相对开演的毫秒偏移
     * @param liveByTeam 各队存活人数，键为队伍名
     */
    public record TimelineBucket(Long t, Map<String, Integer> liveByTeam) {
    }
}
