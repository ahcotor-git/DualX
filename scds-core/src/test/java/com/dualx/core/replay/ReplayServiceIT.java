package com.dualx.core.replay;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.UuidV7;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 复盘读侧的验收（契约 {@code docs/api/paths/replay.yaml} 的 {@code listReplays} / {@code getReplay}）。
 *
 * <p>真库真事务：复盘与演习共用 {@code t_exercise} 一行，判据「进行中的场次不是复盘」要靠
 * {@code ended_at IS NULL} 这一列去证——用替身替掉仓储会把「同一行两用」这件事本身验掉。
 *
 * <p>{@code @Transactional} 让每个用例的演习行回滚：{@code t_exercise} 有「至多一场进行中」的
 * 唯一索引，不隔离的话一条用例造的进行中场次就会挡住其它用例。
 */
@SpringBootTest
@Transactional
class ReplayServiceIT {

    @Autowired
    private ReplayService replays;

    @Autowired
    private ExerciseRepository exercises;

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

    // ── 铸造 ────────────────────────────────────────────────────────────────

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
