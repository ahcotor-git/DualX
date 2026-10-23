package com.dualx.core.exercise;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseCommand;
import com.dualx.core.domain.ExerciseCommandState;
import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.domain.ExerciseLogEntry;
import com.dualx.core.domain.ExerciseLogType;
import com.dualx.core.repository.ExerciseCommandRepository;
import com.dualx.core.repository.ExerciseLogRepository;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动自愈的验收（设计 01 §7 判据 1、4 的服务侧部分）。
 *
 * <p>不以「等应用启动」来验证：测试上下文只启动一次并被缓存，Runner 在那一刻就跑完了。
 * 这里注入 Runner 本体直接调用 {@code run}，考的正是它自己的行为。真实启动路径见 Docker 部署核查。
 *
 * <p>{@code @Transactional} 让用例内的插入与收尾一起回滚：自愈会「关掉进行中的场次」，
 * 不隔离的话一次用例就会污染同上下文的其它用例。
 */
@SpringBootTest
@Transactional
class ExerciseLivenessRecoverIT {

    @Autowired
    private ExerciseLivenessRecover recover;

    @Autowired
    private ExerciseRepository exercises;

    @Autowired
    private ExerciseCommandRepository commands;

    @Autowired
    private ExerciseLogRepository logs;

    @Autowired
    private JdbcClient jdbc;

    private static final Instant START = Instant.parse("2026-10-20T08:00:00.000Z");

    private static final String CRASHED_INSTANCE = "instance-doomed";

    private UUID insertRunning(String planName) {
        UUID id = UuidV7.generate();
        exercises.insert(new Exercise(id, UuidV7.generate(), planName, null, BattleMode.TRAIN,
                START, null, 3, 0, 0, null, null));
        return id;
    }

    private static ExerciseCommand queued(UUID exerciseId, int vestNum, String command) {
        return new ExerciseCommand(0L, exerciseId, vestNum, command, ExerciseCommandState.PENDING,
                null, null, "指挥员", START, null, null, null);
    }

    /** 直接读列：领域 {@code Exercise} 尚未映射 {@code end_reason}。 */
    private String endReasonOf(UUID exerciseId) {
        return jdbc.sql("SELECT end_reason FROM t_exercise WHERE id = :id")
                .param("id", exerciseId.toString())
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /**
     * 本场次在队列表里的残留行数。直接读表而非复用仓储：收尾会把本场次的行**统统清出**
     * （含已标记 EXPIRED 的那些），所以「作废标记」在收尾后是不可观测的中间态，
     * 永久证据只看 {@code t_exercise_log} 的 SYSTEM 记录。
     */
    private long commandsLeft(UUID exerciseId) {
        return jdbc.sql("SELECT COUNT(*) FROM t_exercise_command WHERE exercise_id = :exerciseId")
                .param("exerciseId", exerciseId.toString())
                .query(Long.class)
                .single();
    }

    @Test
    void run_closesTheRunningExerciseAsCrashRecoveredAndExpiresItsCommands() {
        UUID id = insertRunning("it_recover_断电残留");
        // 崩在开演不久：已占用运行权，留下一条还没发出去的下行指令
        exercises.claim(id, CRASHED_INSTANCE, START);
        commands.enqueue(queued(id, 1001, "DIE"));

        recover.run(null);

        Exercise recovered = exercises.findById(id).orElseThrow();
        assertThat(recovered.running()).isFalse();
        assertThat(recovered.endedAt()).isNotNull();
        assertThat(endReasonOf(id)).isEqualTo(ExerciseEndReason.CRASH_RECOVERED.name());
        // 参演人数是开演快照，自愈不该把它抹掉
        assertThat(recovered.rosterCount()).isEqualTo(3);

        // 唯一索引这一关通了：库里不再有「进行中」的行
        assertThat(exercises.findRunning()).isEmpty();

        // 残留指令作废后按场次清出队列（不保留队列行），原文与原因进 SYSTEM 日志留证（判据 4）
        assertThat(commandsLeft(id)).isZero();
        assertThat(commands.findResidualByExerciseId(id)).isEmpty();
        assertThat(commands.findPending(10)).noneMatch(c -> c.exerciseId().equals(id));

        List<ExerciseLogEntry> evidence = logs.findLatest(id, 10);
        assertThat(evidence).hasSize(1);
        assertThat(evidence.getFirst().logType()).isEqualTo(ExerciseLogType.SYSTEM);
        assertThat(evidence.getFirst().detail())
                .contains("DIE")
                .contains("马甲1001")
                .contains("INSTANCE_RESTART");
    }

    @Test
    void run_whenNothingIsRunning_touchesNoRow() {
        long endedBefore = exercises.countEnded(null, null, null);

        recover.run(null);

        // 起来时没有进行中的场次：既不该凭空造行，也不该把任何已结束的行改写成异常中断
        assertThat(exercises.countEnded(null, null, null)).isEqualTo(endedBefore);
        assertThat(exercises.findRunning()).isEmpty();
    }

    @Test
    void run_whenTheOnlyRunningRowIsAlreadyEnded_leavesItsReasonAlone() {
        // 极端情形：查询与收尾之间被正常停演抢先（freezeAndClose 的 ended_at 守卫会返回空）
        UUID id = insertRunning("it_recover_抢先停演");
        exercises.markEnded(id, START.plusSeconds(60), 0, 0, ExerciseEndReason.NORMAL);

        recover.run(null);

        assertThat(endReasonOf(id)).isEqualTo(ExerciseEndReason.NORMAL.name());
    }
}
