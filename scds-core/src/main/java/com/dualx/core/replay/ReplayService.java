package com.dualx.core.replay;

import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseUnit;
import com.dualx.core.domain.TrackSample;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.push.TeamSummary;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.repository.ExerciseUnitRepository;
import com.dualx.core.repository.HitEventRepository;
import com.dualx.core.repository.TrackSampleRepository;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import com.dualx.protocol.HitPart;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 复盘读侧编排（契约 {@code docs/api/paths/replay.yaml} 的 {@code listReplays} / {@code getReplay} /
 * {@code getReplayTimeline} / {@code getReplayStatistics}）。
 *
 * <p><b>为什么没有独立 replay 仓储</b>：复盘与演习是同一张表的同一行——{@code t_exercise}
 * 写入 {@code ended_at} 后即成复盘记录，{@code replayId} 就是 {@code exerciseId}。
 * 因此本服务只读 {@link ExerciseRepository}，没有「转存 / 复制」这一步，也就不存在两份主键
 * 谁先谁后的一致性问题（见 V4 与 {@code ExerciseRepository} 类注释）。轨迹另读
 * {@link TrackSampleRepository}（{@code t_track_sample} 按 {@code exercise_id} 归属，同 ID 可分片）；
 * 统计另读 {@link ExerciseUnitRepository}（停演冻结的终局台账）与 {@link HitEventRepository}（命中部位分布）。</p>
 *
 * <p><b>只读</b>：复盘是既成事实，本域不产生任何写动作——导出（{@code exportReplay} 等）
 * 会落盘并写 {@code t_export_task}，故不在此类，按 M5 的 R4 分段另行落地。</p>
 */
@Service
public class ReplayService {

    private final ExerciseRepository exercises;
    private final TrackSampleRepository tracks;
    private final ExerciseUnitRepository units;
    private final HitEventRepository hits;

    public ReplayService(ExerciseRepository exercises, TrackSampleRepository tracks,
                         ExerciseUnitRepository units, HitEventRepository hits) {
        this.exercises = exercises;
        this.tracks = tracks;
        this.units = units;
        this.hits = hits;
    }

    /**
     * 轨迹分片（契约 {@code getReplayTimeline}）。
     *
     * <p>游标是 {@code t_track_sample.id}，不是 {@code t_ms}：{@code id} 是自增代理键且插入序即采样序，
     * 拿它分片不重不漏；{@code t_ms} 只保证时间语义，同毫秒多点时无法唯一定出下一片的起点
     * （见 {@link TrackSampleRepository#findChunk}）。</p>
     *
     * <p>{@code nextCursor} 只在「本片正好拉满」时给值：没拉满就说明库里的数据已经见底。
     * 反过来，拉满不保证一定还有下一片（可能刚好整除），此时客户端会多拉一次空片并拿到
     * {@code nextCursor=null}——用一次空请求换「不必多查一次 COUNT」。</p>
     *
     * @param cursor 客户端回传的上一片 {@code nextCursor}；首次为 null
     */
    @Transactional(readOnly = true)
    public Chunk timeline(UUID replayId, String cursor, int limit, List<Integer> vestNums, int stride) {
        requireReplay(replayId);
        List<TrackSample> samples = tracks.findChunk(replayId, afterId(cursor), vestNums, stride, limit);
        return new Chunk(samples, samples.size() < limit ? null : Long.toString(samples.getLast().id()));
    }

    /**
     * 复盘列表（契约 {@code listReplays}）：按结束时间倒序分页。
     *
     * <p>列表与总数共用 {@link ExerciseRepository} 里同一份过滤条件（{@code ended_at IS NOT NULL}
     * + 想定名模糊 + 结束时间区间），两者口径不会各自漂移——翻页时「第 N 页还有没有数据」
     * 与 {@code total} 必须说同一件事。</p>
     *
     * <p>排序末位按 {@code id} 兜底：同毫秒结束的两场若不这样排，翻页时会在页边界换位，
     * 让客户端看到重复或漏项。</p>
     */
    public Page<Exercise> list(String keyword, Instant from, Instant to, PageRequest request) {
        List<Exercise> items = exercises.findEnded(keyword, from, to, request);
        long total = exercises.countEnded(keyword, from, to);
        return Page.of(items, total, request);
    }

    /**
     * 复盘概览（契约 {@code getReplay}）。
     *
     * <p><b>404 判据</b>：{@code ended_at IS NULL} 的行虽在 {@code t_exercise} 里存在，但它
     * 描述的是「进行中的那场演习」而不是一条复盘——「还没结束」与「不存在」对复盘入口是同一件事，
     * 都返回 {@link ApiErrorCode#REPLAY_NOT_FOUND}，不区分 404 的两种成因（否则客户端还要
     * 去 current 端点反查，把一次读取变成两次）。</p>
     */
    @Transactional(readOnly = true)
    public Exercise get(UUID replayId) {
        return requireReplay(replayId);
    }

    /**
     * 复盘统计（契约 {@code getReplayStatistics}）：队伍战损、命中排行、命中部位分布、存活人数曲线。
     *
     * <p><b>数据源分两处</b>：战损与排行取 {@code t_exercise_unit} 的**停演冻结列**
     * （{@code final_status} / {@code dead_at} / {@code kills} / {@code hits_dealt}）——复盘是既成事实，
     * 终局必须来自冻结台账而不是从命中流水反推（否则崩溃自愈未冻结时会给出两套数）；
     * 命中部位分布才去 {@code t_hit_event} 分组，因为那是这一维度唯一的来源。</p>
     *
     * <p><b>排行口径</b>：只列「打出过命中或击杀」的马甲（{@code hitsDealt > 0 || kills > 0}），
     * 按击杀降序、命中降序、编号升序；契约未给 {@code topShooters} 的条数上限，
     * 故不设人为截断，返回全部有战绩者。名单里的姓名取编成快照的 {@code name}（未命名为 null）。</p>
     *
     * <p><b>存活曲线口径</b>：把 {@code [startedAt, endedAt]} 等分成 {@code timelineBuckets} 段，
     * 第 {@code i} 段的 {@code t = durationMs * i / buckets}（相对开演的毫秒偏移）；
     * 该时刻「存活」= {@code final_status != DIE}（{@code OFFLINE} 是掉线不是阵亡，计入存活），
     * 阵亡者从 {@code dead_at} 起不再计入。每个采样段都把所有出现过的队伍填成 0 起算，
     * 使各段的 {@code liveByTeam} 键集一致、客户端画多系列时不必补键。</p>
     *
     * @param timelineBuckets 存活曲线采样段数（契约 10..500，默认 120）
     */
    @Transactional(readOnly = true)
    public Statistics statistics(UUID replayId, int timelineBuckets) {
        Exercise replay = requireReplay(replayId);
        List<ExerciseUnit> roster = units.findByExerciseId(replayId);
        return new Statistics(teamsOf(roster), topShootersOf(roster),
                hitPartDistributionOf(replayId),
                timelineOf(roster, replay.startedAt(), replay.endedAt(), timelineBuckets));
    }

    /**
     * 队伍战损：按 {@link Team} 声明序、只含本场编成里出现过的队伍，口径与态势读侧的
     * {@code SituationQuery.teamSummaryOf} 一致（{@code hitsDealt} 是**打出**数而非被命中数，
     * 与 {@code t_exercise_unit.hits_dealt} 同一来源）。
     */
    private static List<TeamSummary> teamsOf(List<ExerciseUnit> roster) {
        Map<Team, int[]> acc = new EnumMap<>(Team.class);
        for (ExerciseUnit unit : roster) {
            // [total, live, dead, offline, hitsDealt]
            int[] c = acc.computeIfAbsent(unit.team(), k -> new int[5]);
            c[0]++;
            VestStatus status = unit.finalStatus();
            if (status == VestStatus.LIVE) {
                c[1]++;
            } else if (status == VestStatus.DIE) {
                c[2]++;
            } else if (status == VestStatus.OFFLINE) {
                c[3]++;
            }
            c[4] += unit.hitsDealt();
        }
        List<TeamSummary> out = new ArrayList<>(acc.size());
        acc.forEach((team, c) -> out.add(new TeamSummary(team, c[0], c[1], c[2], c[3], c[4])));
        return out;
    }

    /** 命中排行：击杀降序、命中降序、编号升序；只列有战绩者（见 {@link #statistics}）。 */
    private static List<ShooterStat> topShootersOf(List<ExerciseUnit> roster) {
        return roster.stream()
                .filter(unit -> unit.hitsDealt() > 0 || unit.kills() > 0)
                .sorted(Comparator.comparingInt(ExerciseUnit::kills).reversed()
                        .thenComparing(Comparator.comparingInt(ExerciseUnit::hitsDealt).reversed())
                        .thenComparingInt(ExerciseUnit::vestNum))
                .map(unit -> new ShooterStat(unit.vestNum(), unit.name(),
                        unit.hitsDealt(), unit.kills()))
                .toList();
    }

    /** 命中部位分布：库按部位分组计数，键序即 {@link HitPart} 声明序。 */
    private List<HitPartStat> hitPartDistributionOf(UUID replayId) {
        return hits.countByHitPart(replayId).entrySet().stream()
                .map(entry -> new HitPartStat(entry.getKey(), Math.toIntExact(entry.getValue())))
                .toList();
    }

    private static List<LiveBucket> timelineOf(List<ExerciseUnit> roster, Instant startedAt,
                                               Instant endedAt, int buckets) {
        long durationMs = Duration.between(startedAt, endedAt).toMillis();
        List<Team> teamsPresent = roster.stream().map(ExerciseUnit::team).distinct().sorted().toList();
        List<LiveBucket> out = new ArrayList<>(buckets);
        for (int i = 0; i < buckets; i++) {
            long t = durationMs * i / buckets;
            Instant at = startedAt.plusMillis(t);
            Map<String, Integer> liveByTeam = new LinkedHashMap<>();
            teamsPresent.forEach(team -> liveByTeam.put(team.name(), 0));
            for (ExerciseUnit unit : roster) {
                if (aliveAt(unit, at)) {
                    liveByTeam.merge(unit.team().name(), 1, Integer::sum);
                }
            }
            out.add(new LiveBucket(t, liveByTeam));
        }
        return out;
    }

    /**
     * 某时刻是否存活：非阵亡恒活跃；阵亡者自 {@code deadAt} 起不再计入。
     * 终局为 {@code DIE} 而 {@code deadAt} 为 null（未冻结的异常态）按「全程阵亡」处理，
     * 宁可不显示也不虚增存活数。
     */
    private static boolean aliveAt(ExerciseUnit unit, Instant at) {
        if (unit.finalStatus() != VestStatus.DIE) {
            return true;
        }
        Instant deadAt = unit.deadAt();
        return deadAt != null && deadAt.isAfter(at);
    }

    /**
     * 复盘入口的共用 404 判据：{@code t_exercise} 里有这一行、且它已经结束。
     *
     * <p>概览与轨迹分片共用它——两条路由都以 {@code replayId} 定位一条复盘，若各自判一套，
     * 「有没有这条复盘」会在两个端点给出不同答案。</p>
     */
    private Exercise requireReplay(UUID replayId) {
        return exercises.findById(replayId)
                .filter(ended -> !ended.running())
                .orElseThrow(() -> new BusinessException(
                        ApiErrorCode.REPLAY_NOT_FOUND, "复盘不存在：" + replayId));
    }

    /**
     * 游标解析：契约把 {@code cursor} 描述成「上一片返回的 nextCursor」，对客户端是不透明串。
     *
     * <p>解析不出来按 400 {@link ApiErrorCode#VALIDATION_FAILED} 响亮失败，不静默退回 0：
     * 从 0 重新开始会让客户端每次都拿到第一片，形成「永远在拉第一页」的死循环——
     * 一个坏游标若能被容忍，故障就变成无声的性能问题而不是一次可见的报错。</p>
     */
    private static long afterId(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        try {
            long value = Long.parseLong(cursor.trim());
            if (value < 0) {
                throw new NumberFormatException("negative");
            }
            return value;
        } catch (NumberFormatException malformed) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED, "游标不合法：" + cursor);
        }
    }

    /**
     * 轨迹分片结果（契约 {@code TimelineChunk} 在 core 侧的对应物）。
     *
     * @param samples    本片采样点，按 {@code id} 升序（即采样序）
     * @param nextCursor 下一片游标；null 表示已到末尾
     */
    public record Chunk(List<TrackSample> samples, String nextCursor) {
    }

    /**
     * 复盘统计结果（契约 {@code ReplayStatistics} 在 core 侧的对应物；
     * {@code teams} 直接复用中立线载荷 {@link TeamSummary}，其余三项由 api 层再映射成契约形状）。
     */
    public record Statistics(List<TeamSummary> teams, List<ShooterStat> topShooters,
                             List<HitPartStat> hitPartDistribution, List<LiveBucket> timeline) {
    }

    /** 命中排行条目。 */
    public record ShooterStat(int vestNum, String name, int hitsDealt, int kills) {
    }

    /** 命中部位分布条目。 */
    public record HitPartStat(HitPart hitPart, int count) {
    }

    /** 存活曲线采样段；{@code t} 为相对开演的毫秒偏移。 */
    public record LiveBucket(long t, Map<String, Integer> liveByTeam) {
    }
}
