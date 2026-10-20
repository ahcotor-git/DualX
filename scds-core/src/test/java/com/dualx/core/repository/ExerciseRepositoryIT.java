package com.dualx.core.repository;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ExerciseRepositoryIT {

    @Autowired
    private ExerciseRepository exercises;

    private static final Instant START = Instant.parse("2026-10-20T08:00:00.000Z");
    private static final Instant END_1 = Instant.parse("2026-10-20T09:00:00.000Z");
    private static final Instant END_2 = Instant.parse("2026-10-20T10:00:00.000Z");
    private static final Instant END_3 = Instant.parse("2026-10-20T11:00:00.000Z");

    private static Exercise running(String planName, UUID mapId) {
        return new Exercise(UuidV7.generate(), UuidV7.generate(), planName, mapId, BattleMode.BATTLE,
                START, null, 6, 0, 0, null, null);
    }

    @Test
    void insert_runningExercise_roundTripsAllColumns() {
        UUID id = UuidV7.generate();
        UUID planId = UuidV7.generate();
        UUID mapId = UuidV7.generate();
        exercises.insert(new Exercise(id, planId, "it_ex_城市攻防", mapId, BattleMode.TRAIN,
                START, null, 12, 0, 0, null, null));

        Exercise found = exercises.findById(id).orElseThrow();
        assertThat(found.planId()).isEqualTo(planId);
        assertThat(found.planName()).isEqualTo("it_ex_城市攻防");
        assertThat(found.mapId()).isEqualTo(mapId);
        assertThat(found.battleMode()).isEqualTo(BattleMode.TRAIN);
        assertThat(found.startedAt()).isEqualTo(START);
        assertThat(found.endedAt()).isNull();
        assertThat(found.running()).isTrue();
        assertThat(found.rosterCount()).isEqualTo(12);
        assertThat(found.hitCount()).isZero();
        assertThat(found.deadCount()).isZero();
        assertThat(found.createdAt()).isNotNull();
        assertThat(found.updatedAt()).isNotNull();
    }

    @Test
    void insert_withoutMap_allowsNullMapId() {
        // 室外纯 GPS 想定不绑底图
        UUID id = UuidV7.generate();
        exercises.insert(new Exercise(id, UuidV7.generate(), "it_ex_野外", null, BattleMode.TRAIN,
                START, null, 5, 0, 0, null, null));

        assertThat(exercises.findById(id).orElseThrow().mapId()).isNull();
    }

    @Test
    void findById_unknown_returnsEmpty() {
        assertThat(exercises.findById(UuidV7.generate())).isEmpty();
    }

    @Test
    void insert_secondRunning_isRejectedByUniqueIndex() {
        exercises.insert(running("it_ex_第一场", null));

        assertThatThrownBy(() -> exercises.insert(running("it_ex_第二场", null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_exercise_running");
    }

    @Test
    void insert_afterPreviousEnded_isAllowed() {
        UUID first = UuidV7.generate();
        exercises.insert(new Exercise(first, UuidV7.generate(), "it_ex_上一场", null, BattleMode.TRAIN,
                START, null, 4, 0, 0, null, null));
        assertThat(exercises.markEnded(first, END_1, 0, 0)).isEqualTo(1);

        UUID second = UuidV7.generate();
        exercises.insert(new Exercise(second, UuidV7.generate(), "it_ex_下一场", null, BattleMode.TRAIN,
                END_1, null, 4, 0, 0, null, null));

        assertThat(exercises.findRunning().orElseThrow().id()).isEqualTo(second);
    }

    @Test
    void markEnded_freezesOutcomeAndTurnsRowIntoReplay() {
        UUID id = UuidV7.generate();
        exercises.insert(new Exercise(id, UuidV7.generate(), "it_ex_冻结", null, BattleMode.TRAIN,
                START, null, 6, 0, 0, null, null));

        assertThat(exercises.markEnded(id, END_2, 9, 3)).isEqualTo(1);

        Exercise ended = exercises.findById(id).orElseThrow();
        assertThat(ended.endedAt()).isEqualTo(END_2);
        assertThat(ended.running()).isFalse();
        assertThat(ended.hitCount()).isEqualTo(9);
        assertThat(ended.deadCount()).isEqualTo(3);
        assertThat(ended.rosterCount()).isEqualTo(6);
        assertThat(exercises.findRunning()).isEmpty();
    }

    @Test
    void markEnded_whenAlreadyEnded_affectsNoRow() {
        UUID id = UuidV7.generate();
        exercises.insert(new Exercise(id, UuidV7.generate(), "it_ex_重复停演", null, BattleMode.TRAIN,
                START, null, 2, 0, 0, null, null));
        assertThat(exercises.markEnded(id, END_1, 1, 1)).isEqualTo(1);

        // 守卫 ended_at IS NULL 使重复停演幂等
        assertThat(exercises.markEnded(id, END_2, 99, 99)).isZero();
        assertThat(exercises.findById(id).orElseThrow().endedAt()).isEqualTo(END_1);
        assertThat(exercises.findById(id).orElseThrow().hitCount()).isEqualTo(1);
    }

    @Test
    void markEnded_unknownId_affectsNoRow() {
        assertThat(exercises.markEnded(UuidV7.generate(), END_1, 0, 0)).isZero();
    }

    @Test
    void findEnded_ordersByEndedAtDescending_andExcludesRunning() {
        UUID done1 = ended("it_ex_早", START, END_1);
        UUID done2 = ended("it_ex_晚", START, END_3);
        exercises.insert(running("it_ex_进行中", null));

        List<Exercise> found = exercises.findEnded(null, null, null, PageRequest.of(1, 10));

        assertThat(found).extracting(Exercise::id).containsExactly(done2, done1);
        assertThat(exercises.countEnded(null, null, null)).isEqualTo(2);
    }

    @Test
    void findEnded_filtersByPlanNameKeyword() {
        ended("百分号一百", START, END_1);
        ended("it_ex_无关", START, END_2);

        assertThat(exercises.findEnded("百分号", null, null, PageRequest.of(1, 10))).hasSize(1);
        assertThat(exercises.findEnded("it_ex_", null, null, PageRequest.of(1, 10))).hasSize(1);
        assertThat(exercises.countEnded("百分号", null, null)).isEqualTo(1);
    }

    @Test
    void findEnded_treatsWildcardsAsLiteralText() {
        ended("百分号一百", START, END_1);

        // 未转义时 "%" 会命中全表
        assertThat(exercises.findEnded("%", null, null, PageRequest.of(1, 10))).isEmpty();
        assertThat(exercises.findEnded("_", null, null, PageRequest.of(1, 10))).isEmpty();
        assertThat(exercises.countEnded("%", null, null)).isZero();
    }

    @Test
    void findEnded_filtersByEndedTimeRange() {
        ended("it_ex_一粒", START, END_1);
        ended("it_ex_二粒", START, END_2);
        ended("it_ex_三粒", START, END_3);

        assertThat(exercises.findEnded(null, END_2, null, PageRequest.of(1, 10))).hasSize(2);
        assertThat(exercises.findEnded(null, null, END_2, PageRequest.of(1, 10))).hasSize(2);
        assertThat(exercises.findEnded(null, END_2, END_2, PageRequest.of(1, 10))).hasSize(1);
        assertThat(exercises.countEnded(null, END_3, null)).isEqualTo(1);
    }

    @Test
    void findEnded_respectsPaging() {
        ended("it_ex_甲", START, END_1);
        ended("it_ex_乙", START, END_2);
        ended("it_ex_丙", START, END_3);

        assertThat(exercises.findEnded(null, null, null, PageRequest.of(1, 2))).hasSize(2);
        assertThat(exercises.findEnded(null, null, null, PageRequest.of(2, 2))).hasSize(1);
        assertThat(exercises.findEnded(null, null, null, PageRequest.of(3, 2))).isEmpty();
    }

    /** 插入一场已结束的演习（插入即停演），返回其 id。 */
    private UUID ended(String planName, Instant startedAt, Instant endedAt) {
        UUID id = UuidV7.generate();
        exercises.insert(new Exercise(id, UuidV7.generate(), planName, null, BattleMode.TRAIN,
                startedAt, null, 3, 0, 0, null, null));
        exercises.markEnded(id, endedAt, 0, 0);
        return id;
    }
}
