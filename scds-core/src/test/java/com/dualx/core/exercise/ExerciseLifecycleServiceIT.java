package com.dualx.core.exercise;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseCommand;
import com.dualx.core.domain.ExerciseCommandState;
import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.domain.ExerciseLogEntry;
import com.dualx.core.domain.ExerciseLogType;
import com.dualx.core.domain.ExerciseUnit;
import com.dualx.core.domain.HitEvent;
import com.dualx.core.repository.ExerciseCommandRepository;
import com.dualx.core.repository.ExerciseLogRepository;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.repository.ExerciseUnitRepository;
import com.dualx.core.repository.HitEventRepository;
import com.dualx.core.support.UuidV7;
import com.dualx.protocol.HitPart;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ExerciseLifecycleServiceIT {

    @Autowired
    private ExerciseLifecycleService lifecycle;

    @Autowired
    private ExerciseRepository exercises;

    @Autowired
    private ExerciseUnitRepository units;

    @Autowired
    private HitEventRepository hits;

    @Autowired
    private ExerciseCommandRepository commands;

    @Autowired
    private ExerciseLogRepository logs;

    @Autowired
    private JdbcClient jdbc;

    private static final Instant START = Instant.parse("2026-10-20T08:00:00.000Z");
    private static final Instant STOP_AT = Instant.parse("2026-10-20T09:00:00.000Z");

    private UUID insertRunning(String planName) {
        UUID id = UuidV7.generate();
        exercises.insert(new Exercise(id, UuidV7.generate(), planName, null, BattleMode.TRAIN,
                START, null, 3, 0, 0, null, null));
        return id;
    }

    private static ExerciseUnit unit(UUID exerciseId, int vestNum, String name, Team team) {
        return new ExerciseUnit(0L, exerciseId, vestNum, name, team, null, null, 0, 0, null, null);
    }

    private static HitEvent hit(UUID exerciseId, int hitVestNum, int shooterNum, boolean lethal) {
        return new HitEvent(0L, exerciseId, START, 1000, hitVestNum, shooterNum, true,
                null, HitPart.CHEST, lethal, null);
    }

    private static ExerciseCommand queued(UUID exerciseId, int vestNum, String command) {
        return new ExerciseCommand(0L, exerciseId, vestNum, command, ExerciseCommandState.PENDING,
                null, null, "指挥员", START, null, null, null);
    }

    /** 直接读列：{@code Exercise} 领域记录尚未映射 {@code end_reason}。 */
    private String endReasonOf(UUID exerciseId) {
        return jdbc.sql("SELECT end_reason FROM t_exercise WHERE id = :id")
                .param("id", exerciseId.toString())
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /**
     * 按马甲编号取队列主键。不用 {@code findPending} 的下标：那按 {@code queued_at} 排序，
     * 而同一测试里几条指令的入队时刻相同，下标顺序在 SQL 层无保证。
     */
    private long idOfVest(UUID exerciseId, int vestNum) {
        return jdbc.sql("SELECT id FROM t_exercise_command WHERE exercise_id = :exerciseId AND vest_num = :vestNum")
                .param("exerciseId", exerciseId.toString())
                .param("vestNum", vestNum)
                .query(Long.class)
                .single();
    }

    @Test
    void freezeAndClose_freezesAllThreeCountsAndTheReason() {
        UUID id = insertRunning("it_life_城市攻防");
        units.insert(unit(id, 1001, "甲", Team.RED));
        units.insert(unit(id, 1002, "乙", Team.RED));
        units.insert(unit(id, 1003, "丙", Team.BLUE));
        hits.insert(hit(id, 1001, 2001, true));
        hits.insert(hit(id, 1002, 2001, false));
        hits.insert(hit(id, 1002, 2002, true));
        // 态势状态机本该在停演时冻结每行终局；这里只模拟「已冻结一人阵亡」
        units.freezeOutcome(id, 1001, VestStatus.DIE, STOP_AT, 1, 2);

        ExerciseLifecycleService.CloseOutcome outcome = lifecycle
                .freezeAndClose(id, ExerciseEndReason.CRASH_RECOVERED, "INSTANCE_RESTART", STOP_AT)
                .orElseThrow();

        assertThat(outcome.rosterCount()).isEqualTo(3);
        assertThat(outcome.hitCount()).isEqualTo(3);
        assertThat(outcome.deadCount()).isEqualTo(1);
        assertThat(outcome.residualCommands()).isZero();

        Exercise ended = exercises.findById(id).orElseThrow();
        assertThat(ended.running()).isFalse();
        assertThat(ended.endedAt()).isEqualTo(STOP_AT);
        assertThat(ended.rosterCount()).isEqualTo(3);
        assertThat(ended.hitCount()).isEqualTo(3);
        assertThat(ended.deadCount()).isEqualTo(1);
        assertThat(endReasonOf(id)).isEqualTo("CRASH_RECOVERED");
    }

    @Test
    void freezeAndClose_withoutStateFrozenOutcome_recordsZeroDeathsRatherThanGuessing() {
        UUID id = insertRunning("it_life_未冻结终局");
        units.insert(unit(id, 1001, "甲", Team.RED));
        units.insert(unit(id, 1002, "乙", Team.RED));
        // 有致命命中，但没有任何一行终局被冻结——崩溃就是这个样子
        hits.insert(hit(id, 1001, 2001, true));

        ExerciseLifecycleService.CloseOutcome outcome = lifecycle
                .freezeAndClose(id, ExerciseEndReason.CRASH_RECOVERED, "INSTANCE_RESTART", STOP_AT)
                .orElseThrow();

        assertThat(outcome.hitCount()).isEqualTo(1);
        assertThat(outcome.deadCount()).isZero();
    }

    @Test
    void freezeAndClose_expiresResidualCommandsAndWritesSystemEvidence() {
        UUID id = insertRunning("it_life_残留指令");
        commands.enqueue(queued(id, 1001, "DIE"));
        commands.enqueue(queued(id, 1002, "RELOAD"));
        commands.enqueue(queued(id, 1003, "CONFIG"));
        long sentId = idOfVest(id, 1002);
        long ackedId = idOfVest(id, 1003);
        commands.markSent(sentId, STOP_AT, "0009ff03e9a1affa");
        commands.markSent(ackedId, STOP_AT, "0009ff03e9a2affa");
        commands.markAcked(ackedId, STOP_AT);

        ExerciseLifecycleService.CloseOutcome outcome = lifecycle
                .freezeAndClose(id, ExerciseEndReason.CRASH_RECOVERED, "INSTANCE_RESTART", STOP_AT)
                .orElseThrow();

        // 在途的两条被作废并存证；已拿到回执的那条不改写、但随场次一并清出队列
        assertThat(outcome.residualCommands()).isEqualTo(2);
        assertThat(outcome.deletedCommands()).isEqualTo(3);
        assertThat(commands.findResidualByExerciseId(id)).isEmpty();
        assertThat(commands.findById(ackedId)).isEmpty();

        List<ExerciseLogEntry> evidence = logs.findLatest(id, 10);
        assertThat(evidence).hasSize(2);
        assertThat(evidence).allSatisfy(entry -> assertThat(entry.logType()).isEqualTo(ExerciseLogType.SYSTEM));
        assertThat(evidence).extracting(ExerciseLogEntry::vestNum).containsExactly(1001, 1002);
        assertThat(evidence).extracting(ExerciseLogEntry::downlinkHex).containsExactly(null, "0009ff03e9a1affa");
        assertThat(evidence.get(1).detail())
                .contains("id=" + sentId)
                .contains("RELOAD")
                .contains("原状态SENT")
                .contains("INSTANCE_RESTART");
    }

    @Test
    void freezeAndClose_whenAlreadyEnded_isNoOp() {
        UUID id = insertRunning("it_life_重复收尾");
        commands.enqueue(queued(id, 1001, "DIE"));
        lifecycle.freezeAndClose(id, ExerciseEndReason.NORMAL, "EXERCISE_STOPPED", STOP_AT).orElseThrow();

        // 收尾之后又冒出一条在途指令（模拟收尾后才入队的竞态）
        commands.enqueue(queued(id, 1002, "RELOAD"));
        long lateId = commands.findResidualByExerciseId(id).getFirst().id();

        assertThat(lifecycle.freezeAndClose(id, ExerciseEndReason.CRASH_RECOVERED, "INSTANCE_RESTART", STOP_AT))
                .isEmpty();

        // 这不是本场的收尾窗口了：原因不被改写，残留指令不被误清
        assertThat(endReasonOf(id)).isEqualTo("NORMAL");
        assertThat(commands.findById(lateId)).isPresent();
        assertThat(logs.countByExerciseId(id)).isEqualTo(1);
    }

    @Test
    void freezeAndClose_unknownExercise_isNoOp() {
        assertThat(lifecycle.freezeAndClose(UuidV7.generate(), ExerciseEndReason.NORMAL,
                "EXERCISE_STOPPED", STOP_AT)).isEmpty();
    }
}
