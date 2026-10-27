package com.dualx.core.command;

import com.dualx.core.domain.ExerciseCommand;
import com.dualx.core.domain.ExerciseCommandState;
import com.dualx.core.domain.ExerciseLogEntry;
import com.dualx.core.domain.ExerciseLogType;
import com.dualx.core.repository.ExerciseCommandRepository;
import com.dualx.core.repository.ExerciseLogRepository;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指令队列过期扫描的验收（设计 01 §4.4 第 2 步的运行期版本）。
 *
 * <p>测试上下文不带 {@code @EnableScheduling}，任务不会自己跑，因此这里手工调
 * {@link ExerciseCommandExpiryTask#tick()} 驱动一个周期（与 {@code ExerciseHeartbeatTaskIT} 同一约定）。
 * 「超时」靠回填 {@code sent_at} 制造，不依赖真实等待。</p>
 *
 * <p>本类全程跑在测试事务里，不写自己的清理钩子：断言一律限定到本用例新生成的 {@code exerciseId}
 * 上，全库性的 {@code deleteFinished()} 随事务回滚。</p>
 */
@SpringBootTest
@Transactional
class ExerciseCommandExpiryTaskIT {

    /** 8B 判死帧原文，只作下发原文的样本用（本测试不关心帧语义，只关心它有没有被存证）。 */
    private static final String DIE_HEX = "0009ff03e9a1affa";

    @Autowired
    private ExerciseCommandExpiryTask expiryTask;

    @Autowired
    private ExerciseCommandRepository commands;

    @Autowired
    private ExerciseLogRepository logs;

    @Autowired
    private ExerciseCommandExpiryTask.CommandProperties properties;

    @Test
    void defaultTtl_isThirtySeconds() {
        assertThat(properties.sentTtl()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void tick_expiresOverdueSentCommandAndLeavesSystemEvidence() {
        UUID exerciseId = UuidV7.generate();
        long id = sent(exerciseId, 1001, "DIE", DIE_HEX, Instant.now().minusSeconds(60));

        expiryTask.tick();

        // 队列行不保留历史（停演收尾也一样），可观测的结果是「队列里没有它 + 一条 SYSTEM 存证」
        assertThat(commands.findById(id)).isEmpty();
        List<ExerciseLogEntry> evidence = systemOf(exerciseId);
        assertThat(evidence).hasSize(1);
        ExerciseLogEntry row = evidence.getFirst();
        assertThat(row.logType()).isEqualTo(ExerciseLogType.SYSTEM);
        assertThat(row.vestNum()).isEqualTo(1001);
        assertThat(row.operatorName()).isNull();
        assertThat(row.downlinkHex()).isEqualTo(DIE_HEX);
        assertThat(row.detail())
                .contains("id=" + id)
                .contains("DIE")
                .contains("马甲1001")
                .contains("原状态SENT")
                .contains(ExerciseCommandExpiryTask.REASON_SENT_TIMEOUT);
    }

    @Test
    void tick_leavesFreshSentCommandInFlight() {
        UUID exerciseId = UuidV7.generate();
        long id = sent(exerciseId, 1001, "DIE", DIE_HEX, Instant.now().minusSeconds(5));

        expiryTask.tick();

        // 5s 下发、超时窗 30s：仍在等回执，不得被判死
        assertThat(commands.findById(id).orElseThrow().state()).isEqualTo(ExerciseCommandState.SENT);
        assertThat(systemOf(exerciseId)).isEmpty();
    }

    @Test
    void tick_leavesPendingCommandsAlone() {
        UUID exerciseId = UuidV7.generate();
        commands.enqueue(new ExerciseCommand(0L, exerciseId, 1001, "DIE", ExerciseCommandState.PENDING,
                null, null, null, Instant.now().minusSeconds(600), null, null, null));

        expiryTask.tick();

        // 只扫「已发未确认」：PENDING 等的是重连而不是回执，判它超时等于把派发的责任挪过来
        assertThat(commands.findResidualByExerciseId(exerciseId))
                .extracting(ExerciseCommand::state).containsExactly(ExerciseCommandState.PENDING);
        assertThat(systemOf(exerciseId)).isEmpty();
    }

    @Test
    void tick_onlyTouchesTheOverdueRowsAndKeepsTheRestOfTheQueue() {
        UUID overdueExercise = UuidV7.generate();
        UUID freshExercise = UuidV7.generate();
        long overdue = sent(overdueExercise, 1001, "DIE", DIE_HEX, Instant.now().minusSeconds(120));
        long recent = sent(freshExercise, 2001, "RELOAD", "0114ffffff1e02c9affa", Instant.now().minusSeconds(5));

        expiryTask.tick();

        assertThat(commands.findById(overdue)).isEmpty();
        assertThat(commands.findById(recent).orElseThrow().state()).isEqualTo(ExerciseCommandState.SENT);
        assertThat(systemOf(overdueExercise)).hasSize(1);
        assertThat(systemOf(freshExercise)).isEmpty();
    }

    @Test
    void tick_isIdempotentAcrossTicks() {
        UUID exerciseId = UuidV7.generate();
        sent(exerciseId, 1001, "DIE", DIE_HEX, Instant.now().minusSeconds(60));

        expiryTask.tick();
        expiryTask.tick();

        // 第一拍已把它作废并清出队列，第二拍扫不到它，因此不会重复存证
        assertThat(systemOf(exerciseId)).hasSize(1);
    }

    /** 造一条已下发（SENT）的指令，{@code sentAt} 由调用方回填以制造逾期，返回其队列主键。 */
    private long sent(UUID exerciseId, int vestNum, String command, String downlinkHex, Instant sentAt) {
        commands.enqueue(new ExerciseCommand(0L, exerciseId, vestNum, command, ExerciseCommandState.PENDING,
                null, null, null, sentAt, null, null, null));
        long id = commands.findResidualByExerciseId(exerciseId).getFirst().id();
        commands.markSent(id, sentAt, downlinkHex);
        return id;
    }

    /** 本场次的 SYSTEM 存证（按 seq 升序）。收尾路径也写 SYSTEM，故断言必须限定到本用例的场次。 */
    private List<ExerciseLogEntry> systemOf(UUID exerciseId) {
        return logs.findLatest(exerciseId, 20).stream()
                .filter(entry -> entry.logType() == ExerciseLogType.SYSTEM)
                .toList();
    }
}
