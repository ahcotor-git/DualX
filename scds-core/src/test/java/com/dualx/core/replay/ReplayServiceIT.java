package com.dualx.core.replay;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.CoordinateSystem;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.domain.ExerciseUnit;
import com.dualx.core.domain.GeoPoint;
import com.dualx.core.domain.HitEvent;
import com.dualx.core.domain.MotionState;
import com.dualx.core.domain.PositionSource;
import com.dualx.core.domain.SampleReason;
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
import com.dualx.core.support.UuidV7;
import com.dualx.protocol.HitPart;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import com.dualx.protocol.WeaponKind;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 复盘读侧的验收（契约 {@code docs/api/paths/replay.yaml} 的 {@code listReplays} / {@code getReplay} /
 * {@code getReplayTimeline} / {@code getReplayStatistics}）。
 *
 * <p>真库真事务：复盘与演习共用 {@code t_exercise} 一行，判据「进行中的场次不是复盘」要靠
 * {@code ended_at IS NULL} 这一列去证；轨迹分片的「不重不漏」要靠 {@code t_track_sample.id} 的
 * 真实自增值去证——用替身替掉任一侧都会把要验的那件事本身验掉。
 *
 * <p>{@code @Transactional} 让每个用例的演习行与采样点一起回滚：{@code t_exercise} 有
 * 「至多一场进行中」的唯一索引，不隔离的话一条用例造的进行中场次就会挡住其它用例。
 */
@SpringBootTest
@Transactional
class ReplayServiceIT {

    @Autowired
    private ReplayService replays;

    @Autowired
    private ExerciseRepository exercises;

    @Autowired
    private TrackSampleRepository tracks;

    @Autowired
    private ExerciseUnitRepository units;

    @Autowired
    private HitEventRepository hits;

    private static final Instant START = Instant.parse("2026-10-20T08:00:00.000Z");

    // ── listReplays ─────────────────────────────────────────────────────────

    @Test
    void list_returnsOnlyEnded_newestFirst() {
        UUID newer = seedEnded("it_rp_列表_新", Instant.parse("2026-10-20T10:00:00Z"));
        UUID older = seedEnded("it_rp_列表_旧", Instant.parse("2026-10-20T09:00:00Z"));
        // 进行中的那行同前缀，却必须不出现在复盘列表里——这正是「一行两用」的判据所在。
        seedRunning("it_rp_列表_进行中");

        Page<Exercise> page = replays.list("it_rp_列表", null, null, PageRequest.of(1, 20));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(Exercise::id).containsExactly(newer, older);
        assertThat(page.items()).allMatch(ended -> !ended.running());
    }

    @Test
    void list_appliesKeywordAndEndedWindow() {
        seedEnded("it_rp_检索_甲", Instant.parse("2026-10-20T09:00:00Z"));
        UUID inWindow = seedEnded("it_rp_检索_乙", Instant.parse("2026-10-20T11:00:00Z"));

        Page<Exercise> page = replays.list("it_rp_检索",
                Instant.parse("2026-10-20T10:00:00Z"), Instant.parse("2026-10-20T12:00:00Z"),
                PageRequest.of(1, 20));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(Exercise::id).containsExactly(inWindow);
    }

    @Test
    void list_treatsPercentAsLiteralNotWildcard() {
        seedEnded("it_rp_通配_甲", Instant.parse("2026-10-20T09:00:00Z"));

        // 未转义时 '%' 会命中全表；这里必须一条都不中。
        Page<Exercise> page = replays.list("%", null, null, PageRequest.of(1, 20));

        assertThat(page.total()).isZero();
        assertThat(page.items()).isEmpty();
    }

    @Test
    void list_pagesWithStableTotalAndNoOverlap() {
        UUID newest = seedEnded("it_rp_分页_一", Instant.parse("2026-10-20T12:00:00Z"));
        UUID older = seedEnded("it_rp_分页_二", Instant.parse("2026-10-20T10:00:00Z"));

        Page<Exercise> first = replays.list("it_rp_分页", null, null, PageRequest.of(1, 1));
        Page<Exercise> second = replays.list("it_rp_分页", null, null, PageRequest.of(2, 1));

        assertThat(first.total()).isEqualTo(2);
        assertThat(first.items()).extracting(Exercise::id).containsExactly(newest);
        assertThat(second.total()).isEqualTo(2);
        assertThat(second.items()).extracting(Exercise::id).containsExactly(older);
    }

    // ── getReplay ───────────────────────────────────────────────────────────

    @Test
    void get_endedExercise_returnsFrozenOutcome() {
        Instant endedAt = Instant.parse("2026-10-20T10:00:00Z");
        UUID id = seedEnded("it_rp_概览", endedAt, 5, 2);

        Exercise replay = replays.get(id);

        assertThat(replay.id()).isEqualTo(id);
        assertThat(replay.startedAt()).isEqualTo(START);
        assertThat(replay.endedAt()).isEqualTo(endedAt);
        assertThat(replay.rosterCount()).isEqualTo(8);
        assertThat(replay.hitCount()).isEqualTo(5);
        assertThat(replay.deadCount()).isEqualTo(2);
        assertThat(replay.running()).isFalse();
    }

    @Test
    void get_runningExercise_isNotFound() {
        UUID id = seedRunning("it_rp_概览_进行中");

        assertCode(() -> replays.get(id), ApiErrorCode.REPLAY_NOT_FOUND);
    }

    @Test
    void get_unknownId_isNotFound() {
        assertCode(() -> replays.get(UuidV7.generate()), ApiErrorCode.REPLAY_NOT_FOUND);
    }

    // ── getReplayTimeline ───────────────────────────────────────────────────

    @Test
    void timeline_pagesByCursorWithoutOverlapOrGap() {
        UUID id = seedEnded("it_rp_轨迹_分页", Instant.parse("2026-10-20T10:00:00Z"));
        seedTicks(id, List.of(1001), 5);

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            ReplayService.Chunk chunk = replays.timeline(id, cursor, 2, null, 1);
            pages++;
            chunk.samples().forEach(s -> seen.add(s.id()));
            cursor = chunk.nextCursor();
            if (cursor == null) {
                break;
            }
        }

        // 5 个点、每片 2 个 => 2 / 2 / 1 三片；末片没拉满，故直接收尾（不会再多一次空片）
        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(5).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void timeline_lastPageWhenExactlyDivisible_endsWithAnEmptyChunk() {
        UUID id = seedEnded("it_rp_轨迹_整除", Instant.parse("2026-10-20T10:00:00Z"));
        seedTicks(id, List.of(1001), 4);

        ReplayService.Chunk first = replays.timeline(id, null, 2, null, 1);
        ReplayService.Chunk second = replays.timeline(id, first.nextCursor(), 2, null, 1);
        ReplayService.Chunk third = replays.timeline(id, second.nextCursor(), 2, null, 1);

        // 拉满不等于还有下一片：服务端不为此多查一次 COUNT，客户端多拉一次空片即收尾
        assertThat(first.samples()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();
        assertThat(second.samples()).hasSize(2);
        assertThat(second.nextCursor()).isNotNull();
        assertThat(third.samples()).isEmpty();
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    void timeline_filtersByVestNumsAndThinsByStride() {
        UUID id = seedEnded("it_rp_轨迹_筛选", Instant.parse("2026-10-20T10:00:00Z"));
        seedTicks(id, List.of(1001, 1002), 4);

        ReplayService.Chunk chunk = replays.timeline(id, null, 100, List.of(1001), 2);

        // 全体 8 个点；只看 1001 得 4 个，再按步长 2 抽稀得 t=200 / 400
        assertThat(chunk.samples()).hasSize(2);
        assertThat(chunk.samples()).extracting(TrackSample::vestNum).containsOnly(1001);
        assertThat(chunk.samples()).extracting(TrackSample::tMs).containsExactly(200, 400);
        assertThat(chunk.nextCursor()).isNull();
    }

    @Test
    void timeline_replayWithoutSamples_returnsEmptyChunk() {
        UUID id = seedEnded("it_rp_轨迹_空", Instant.parse("2026-10-20T10:00:00Z"));

        ReplayService.Chunk chunk = replays.timeline(id, null, 5000, null, 1);

        assertThat(chunk.samples()).isEmpty();
        assertThat(chunk.nextCursor()).isNull();
    }

    @Test
    void timeline_isScopedToItsOwnReplay() {
        UUID mine = seedEnded("it_rp_轨迹_隔离_甲", Instant.parse("2026-10-20T10:00:00Z"));
        UUID other = seedEnded("it_rp_轨迹_隔离_乙", Instant.parse("2026-10-20T11:00:00Z"));
        seedTicks(mine, List.of(1001), 2);
        seedTicks(other, List.of(1001), 3);

        assertThat(replays.timeline(mine, null, 100, null, 1).samples()).hasSize(2);
        assertThat(replays.timeline(other, null, 100, null, 1).samples()).hasSize(3);
    }

    @Test
    void timeline_runningExercise_isNotFound() {
        UUID id = seedRunning("it_rp_轨迹_进行中");

        assertCode(() -> replays.timeline(id, null, 100, null, 1), ApiErrorCode.REPLAY_NOT_FOUND);
    }

    @Test
    void timeline_unknownId_isNotFound() {
        assertCode(() -> replays.timeline(UuidV7.generate(), null, 100, null, 1),
                ApiErrorCode.REPLAY_NOT_FOUND);
    }

    @Test
    void timeline_malformedCursor_isValidationFailed() {
        UUID id = seedEnded("it_rp_轨迹_坏游标", Instant.parse("2026-10-20T10:00:00Z"));

        // 不静默退回 0：那样客户端每次都会拿到第一片，变成「永远拉第一页」的死循环
        assertCode(() -> replays.timeline(id, "abc", 100, null, 1), ApiErrorCode.VALIDATION_FAILED);
        assertCode(() -> replays.timeline(id, "-1", 100, null, 1), ApiErrorCode.VALIDATION_FAILED);
    }

    // ── getReplayStatistics ─────────────────────────────────────────────────

    @Test
    void statistics_aggregatesTeamsFromFrozenRoster() {
        UUID id = seedEnded("it_rp_统计_队伍", Instant.parse("2026-10-20T08:01:00Z"));
        seedUnit(id, 1001, "甲", Team.RED, VestStatus.LIVE, null, 0, 3);
        seedUnit(id, 1002, "乙", Team.RED, VestStatus.DIE, Instant.parse("2026-10-20T08:00:20Z"), 1, 2);
        seedUnit(id, 1003, "丙", Team.BLUE, VestStatus.OFFLINE, null, 0, 0);

        ReplayService.Statistics stats = replays.statistics(id, 10);

        // 队伍按 Team 声明序、只含出现过的队伍
        assertThat(stats.teams()).extracting(TeamSummary::team).containsExactly(Team.RED, Team.BLUE);
        TeamSummary red = stats.teams().getFirst();
        assertThat(red.total()).isEqualTo(2);
        assertThat(red.live()).isEqualTo(1);
        assertThat(red.dead()).isEqualTo(1);
        assertThat(red.offline()).isZero();
        assertThat(red.hitsDealt()).isEqualTo(5);
        assertThat(stats.teams().get(1).offline()).isEqualTo(1);
    }

    @Test
    void statistics_ranksShootersByKillsThenHitsThenVest() {
        UUID id = seedEnded("it_rp_统计_排行", Instant.parse("2026-10-20T08:01:00Z"));
        seedUnit(id, 1001, "甲", Team.RED, VestStatus.LIVE, null, 0, 0);
        seedUnit(id, 1002, "乙", Team.RED, VestStatus.LIVE, null, 2, 5);
        seedUnit(id, 1003, null, Team.BLUE, VestStatus.LIVE, null, 2, 3);
        seedUnit(id, 1004, "丁", Team.BLUE, VestStatus.LIVE, null, 2, 5);

        ReplayService.Statistics stats = replays.statistics(id, 10);

        // 击杀降序 → 同为 2 杀再比命中降序 → 再同则编号升序；零战绩的 1001 被剔除
        assertThat(stats.topShooters()).extracting(ReplayService.ShooterStat::vestNum)
                .containsExactly(1002, 1004, 1003);
        assertThat(stats.topShooters().get(2).name()).isNull();
    }

    @Test
    void statistics_hitPartDistribution_groupsByPartInEnumOrder() {
        UUID id = seedEnded("it_rp_统计_部位", Instant.parse("2026-10-20T08:01:00Z"));
        seedHit(id, HitPart.CHEST, 1001, 2001);
        seedHit(id, HitPart.HEAD, 1001, 2001);
        seedHit(id, HitPart.CHEST, 1002, 2002);

        ReplayService.Statistics stats = replays.statistics(id, 10);

        // 键序 = HitPart 声明序：HEAD 在 CHEST 之前
        assertThat(stats.hitPartDistribution()).extracting(ReplayService.HitPartStat::hitPart)
                .containsExactly(HitPart.HEAD, HitPart.CHEST);
        assertThat(stats.hitPartDistribution()).extracting(ReplayService.HitPartStat::count)
                .containsExactly(1, 2);
    }

    @Test
    void statistics_timeline_splitsDurationAndDropsTheDead() {
        // 时长 10s，10 段 → t = 0,1000,...,9000
        UUID id = seedEnded("it_rp_统计_曲线", Instant.parse("2026-10-20T08:00:10Z"));
        seedUnit(id, 1001, "甲", Team.RED, VestStatus.DIE, Instant.parse("2026-10-20T08:00:03Z"), 0, 0);
        seedUnit(id, 1002, "乙", Team.RED, VestStatus.LIVE, null, 0, 0);
        seedUnit(id, 1003, "丙", Team.BLUE, VestStatus.DIE, Instant.parse("2026-10-20T08:00:07Z"), 0, 0);

        ReplayService.Statistics stats = replays.statistics(id, 10);

        assertThat(stats.timeline()).extracting(ReplayService.LiveBucket::t)
                .containsExactly(0L, 1000L, 2000L, 3000L, 4000L, 5000L, 6000L, 7000L, 8000L, 9000L);
        // t=0 三人全活
        assertThat(stats.timeline().getFirst().liveByTeam())
                .containsExactlyInAnyOrderEntriesOf(Map.of("RED", 2, "BLUE", 1));
        // t=3000：1001 的 deadAt 落在此刻 → 自这一秒起不再计入
        assertThat(stats.timeline().get(3).liveByTeam())
                .containsExactlyInAnyOrderEntriesOf(Map.of("RED", 1, "BLUE", 1));
        // t=7000：1003 阵亡 → 蓝队归零，但键仍在（各段键集一致）
        assertThat(stats.timeline().get(7).liveByTeam())
                .containsExactlyInAnyOrderEntriesOf(Map.of("RED", 1, "BLUE", 0));
    }

    @Test
    void statistics_replayWithoutRosterOrHits_returnsEmptySections() {
        UUID id = seedEnded("it_rp_统计_空", Instant.parse("2026-10-20T08:00:10Z"));

        ReplayService.Statistics stats = replays.statistics(id, 10);

        assertThat(stats.teams()).isEmpty();
        assertThat(stats.topShooters()).isEmpty();
        assertThat(stats.hitPartDistribution()).isEmpty();
        assertThat(stats.timeline()).hasSize(10);
        assertThat(stats.timeline()).allSatisfy(bucket -> assertThat(bucket.liveByTeam()).isEmpty());
    }

    @Test
    void statistics_runningExercise_isNotFound() {
        UUID id = seedRunning("it_rp_统计_进行中");

        assertCode(() -> replays.statistics(id, 10), ApiErrorCode.REPLAY_NOT_FOUND);
    }

    @Test
    void statistics_unknownId_isNotFound() {
        assertCode(() -> replays.statistics(UuidV7.generate(), 10), ApiErrorCode.REPLAY_NOT_FOUND);
    }

    // ── 铸造 ────────────────────────────────────────────────────────────────

    /** 造一个停演冻结后的编成单元。 */
    private void seedUnit(UUID exerciseId, int vestNum, String name, Team team,
                          VestStatus finalStatus, Instant deadAt, int kills, int hitsDealt) {
        units.insert(new ExerciseUnit(0L, exerciseId, vestNum, name, team, null, null, 0, 0,
                null, null));
        units.freezeOutcome(exerciseId, vestNum, finalStatus, deadAt, kills, hitsDealt);
    }

    /** 造一条命中（部位分布用），编号按单兵区间推导。 */
    private void seedHit(UUID exerciseId, HitPart part, int shooterNum, int hitVestNum) {
        hits.insert(new HitEvent(0L, exerciseId, START, 1000, hitVestNum, shooterNum,
                WeaponKind.isSoldier(shooterNum), WeaponKind.fromNum(shooterNum), part, false,
                new GeoPoint(CoordinateSystem.INDOOR, 0.1, 0.2, 1, 5, null, null, null, null)));
    }

    /** 按 tick 交错写入多人，还原真实上行帧的写入顺序；每 tick 的 {@code tMs} 为 {@code (tick+1)*100}。 */
    private void seedTicks(UUID exerciseId, List<Integer> vestNums, int ticks) {
        List<TrackSample> batch = new ArrayList<>();
        for (int tick = 0; tick < ticks; tick++) {
            for (int vestNum : vestNums) {
                batch.add(new TrackSample(0L, exerciseId, vestNum, (tick + 1) * 100, VestStatus.LIVE,
                        100, 30, 12, MotionState.MOVING, PositionSource.UWB, 4,
                        new GeoPoint(CoordinateSystem.INDOOR, 0.1, 0.2, 1, 5, null, null, null, null),
                        SampleReason.TIMER));
            }
        }
        tracks.insertAll(batch);
    }

    private UUID seedEnded(String planName, Instant endedAt) {
        return seedEnded(planName, endedAt, 0, 0);
    }

    private UUID seedEnded(String planName, Instant endedAt, int hitCount, int deadCount) {
        UUID id = UuidV7.generate();
        exercises.insert(new Exercise(id, UuidV7.generate(), planName, null, BattleMode.BATTLE,
                START, null, 8, 0, 0, null, null));
        exercises.markEnded(id, endedAt, hitCount, deadCount, ExerciseEndReason.NORMAL);
        return id;
    }

    private UUID seedRunning(String planName) {
        UUID id = UuidV7.generate();
        exercises.insert(new Exercise(id, UuidV7.generate(), planName, null, BattleMode.BATTLE,
                START, null, 8, 0, 0, null, null));
        return id;
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ApiErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
