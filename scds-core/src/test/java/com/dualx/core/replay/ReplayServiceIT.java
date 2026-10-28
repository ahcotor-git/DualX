package com.dualx.core.replay;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.CoordinateSystem;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.domain.GeoPoint;
import com.dualx.core.domain.MotionState;
import com.dualx.core.domain.PositionSource;
import com.dualx.core.domain.SampleReason;
import com.dualx.core.domain.TrackSample;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.repository.TrackSampleRepository;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.UuidV7;
import com.dualx.protocol.VestStatus;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 复盘读侧的验收（契约 {@code docs/api/paths/replay.yaml} 的 {@code listReplays} / {@code getReplay} /
 * {@code getReplayTimeline}）。
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

    // ── 铸造 ────────────────────────────────────────────────────────────────

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
