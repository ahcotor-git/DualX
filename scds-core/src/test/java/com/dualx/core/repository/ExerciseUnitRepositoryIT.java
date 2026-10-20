package com.dualx.core.repository;

import com.dualx.core.domain.ExerciseUnit;
import com.dualx.core.support.UuidV7;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
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
class ExerciseUnitRepositoryIT {

    @Autowired
    private ExerciseUnitRepository units;

    private static final Instant DEAD_AT = Instant.parse("2026-10-20T09:30:00.000Z");

    /** 开演快照入参：终局列留空，代理键与时间戳由库填。 */
    private static ExerciseUnit unit(UUID exerciseId, int vestNum, String name, Team team) {
        return new ExerciseUnit(0L, exerciseId, vestNum, name, team, null, null, 0, 0, null, null);
    }

    @Test
    void insert_andFindByExerciseId_roundTripsAllColumns() {
        UUID exerciseId = UuidV7.generate();
        units.insert(unit(exerciseId, 1001, "张三", Team.RED));

        List<ExerciseUnit> found = units.findByExerciseId(exerciseId);
        assertThat(found).hasSize(1);
        ExerciseUnit one = found.getFirst();
        assertThat(one.id()).isPositive();
        assertThat(one.exerciseId()).isEqualTo(exerciseId);
        assertThat(one.vestNum()).isEqualTo(1001);
        assertThat(one.name()).isEqualTo("张三");
        assertThat(one.team()).isEqualTo(Team.RED);
        assertThat(one.finalStatus()).isNull();
        assertThat(one.deadAt()).isNull();
        assertThat(one.kills()).isZero();
        assertThat(one.hitsDealt()).isZero();
        assertThat(one.createdAt()).isNotNull();
        assertThat(one.updatedAt()).isNotNull();
    }

    @Test
    void insert_allowsNullName() {
        UUID exerciseId = UuidV7.generate();
        units.insert(unit(exerciseId, 1002, null, Team.BLUE));

        assertThat(units.findByExerciseId(exerciseId).getFirst().name()).isNull();
    }

    @Test
    void findByExerciseId_ordersByTeamThenVestNum() {
        UUID exerciseId = UuidV7.generate();
        units.insert(unit(exerciseId, 1003, "红三", Team.RED));
        units.insert(unit(exerciseId, 1001, "红一", Team.RED));
        units.insert(unit(exerciseId, 1002, "蓝二", Team.BLUE));

        assertThat(units.findByExerciseId(exerciseId))
                .extracting(ExerciseUnit::vestNum)
                .containsExactly(1002, 1001, 1003);
    }

    @Test
    void insert_duplicateVestNumInSameExercise_isRejected() {
        UUID exerciseId = UuidV7.generate();
        units.insert(unit(exerciseId, 1001, "甲", Team.RED));

        assertThatThrownBy(() -> units.insert(unit(exerciseId, 1001, "乙", Team.BLUE)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_exercise_unit");
    }

    @Test
    void insert_sameVestNumInAnotherExercise_isAllowed() {
        // 编成快照按场次隔离，跨场同号互不冲突
        units.insert(unit(UuidV7.generate(), 1001, "甲", Team.RED));
        units.insert(unit(UuidV7.generate(), 1001, "甲", Team.RED));

        assertThat(units.countByExerciseId(UuidV7.generate())).isZero();
    }

    @Test
    void replaceByExerciseId_swapsWholeRoster() {
        UUID exerciseId = UuidV7.generate();
        units.insert(unit(exerciseId, 1001, "甲", Team.RED));

        int written = units.replaceByExerciseId(exerciseId, List.of(
                unit(exerciseId, 2001, "乙", Team.BLUE),
                unit(exerciseId, 2002, "丙", Team.BLUE)));

        assertThat(written).isEqualTo(2);
        assertThat(units.findByExerciseId(exerciseId))
                .extracting(ExerciseUnit::vestNum)
                .containsExactly(2001, 2002);
    }

    @Test
    void replaceByExerciseId_withEmptyList_clearsRoster() {
        UUID exerciseId = UuidV7.generate();
        units.insert(unit(exerciseId, 1001, "甲", Team.RED));

        assertThat(units.replaceByExerciseId(exerciseId, List.of())).isZero();
        assertThat(units.findByExerciseId(exerciseId)).isEmpty();
    }

    @Test
    void replaceByExerciseId_leavesOtherExercisesUntouched() {
        UUID target = UuidV7.generate();
        UUID other = UuidV7.generate();
        units.insert(unit(target, 1001, "甲", Team.RED));
        units.insert(unit(other, 3001, "乙", Team.BLUE));

        units.replaceByExerciseId(target, List.of(unit(target, 4001, "丙", Team.YELLOW)));

        assertThat(units.findByExerciseId(other)).extracting(ExerciseUnit::vestNum).containsExactly(3001);
    }

    @Test
    void deleteByExerciseId_removesOnlyThatExercise() {
        UUID target = UuidV7.generate();
        UUID other = UuidV7.generate();
        units.insert(unit(target, 1001, "甲", Team.RED));
        units.insert(unit(other, 1002, "乙", Team.BLUE));

        assertThat(units.deleteByExerciseId(target)).isEqualTo(1);
        assertThat(units.findByExerciseId(target)).isEmpty();
        assertThat(units.countByExerciseId(other)).isEqualTo(1);
    }

    @Test
    void freezeOutcome_writesFinalStatusAndStatistics() {
        UUID exerciseId = UuidV7.generate();
        units.insert(unit(exerciseId, 1001, "甲", Team.RED));

        assertThat(units.freezeOutcome(exerciseId, 1001, VestStatus.DIE, DEAD_AT, 3, 5)).isEqualTo(1);

        ExerciseUnit frozen = units.findByExerciseId(exerciseId).getFirst();
        assertThat(frozen.finalStatus()).isEqualTo(VestStatus.DIE);
        assertThat(frozen.deadAt()).isEqualTo(DEAD_AT);
        assertThat(frozen.kills()).isEqualTo(3);
        assertThat(frozen.hitsDealt()).isEqualTo(5);
    }

    @Test
    void freezeOutcome_isIdempotentAndLastWriteWins() {
        UUID exerciseId = UuidV7.generate();
        units.insert(unit(exerciseId, 1001, "甲", Team.RED));
        units.freezeOutcome(exerciseId, 1001, VestStatus.DIE, DEAD_AT, 3, 5);

        // 人工改判存活：dead_at 清空、状态改写，不抛错
        assertThat(units.freezeOutcome(exerciseId, 1001, VestStatus.LIVE, null, 4, 6)).isEqualTo(1);

        ExerciseUnit frozen = units.findByExerciseId(exerciseId).getFirst();
        assertThat(frozen.finalStatus()).isEqualTo(VestStatus.LIVE);
        assertThat(frozen.deadAt()).isNull();
        assertThat(frozen.kills()).isEqualTo(4);
        assertThat(frozen.hitsDealt()).isEqualTo(6);
    }

    @Test
    void freezeOutcome_unknownVestNum_affectsNoRow() {
        UUID exerciseId = UuidV7.generate();
        units.insert(unit(exerciseId, 1001, "甲", Team.RED));

        assertThat(units.freezeOutcome(exerciseId, 9999, VestStatus.DIE, DEAD_AT, 1, 1)).isZero();
    }
}
