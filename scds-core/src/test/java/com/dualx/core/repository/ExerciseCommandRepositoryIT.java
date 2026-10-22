package com.dualx.core.repository;

import com.dualx.core.domain.ExerciseCommand;
import com.dualx.core.domain.ExerciseCommandState;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ExerciseCommandRepositoryIT {

    @Autowired
    private ExerciseCommandRepository commands;

    private static final Instant QUEUED_1 = Instant.parse("2026-10-20T08:00:01.000Z");
    private static final Instant QUEUED_2 = Instant.parse("2026-10-20T08:00:02.000Z");
    private static final Instant QUEUED_3 = Instant.parse("2026-10-20T08:00:03.000Z");
    private static final Instant QUEUED_4 = Instant.parse("2026-10-20T08:00:04.000Z");
    private static final Instant SENT_AT = Instant.parse("2026-10-20T08:00:05.000Z");
    private static final Instant DONE_AT = Instant.parse("2026-10-20T08:00:06.000Z");

    private static ExerciseCommand queued(UUID exerciseId, int vestNum, String command, Instant queuedAt) {
        return new ExerciseCommand(0L, exerciseId, vestNum, command, ExerciseCommandState.PENDING,
                null, null, "指挥员", queuedAt, null, null, null);
    }

    private long enqueueOne() {
        UUID exerciseId = UuidV7.generate();
        commands.enqueue(queued(exerciseId, 1001, "DIE", QUEUED_1));
        return commands.findPending(10).getFirst().id();
    }

    /** 某场次的 PENDING 行（按入队时间升序）。全局的 {@link #findPending(int)} 需先按场次筛出。 */
    private List<ExerciseCommand> pendingOf(UUID exerciseId) {
        return commands.findPending(10).stream()
                .filter(c -> c.exerciseId().equals(exerciseId))
                .toList();
    }

    @Test
    void enqueue_andFindById_roundTripsAllColumns() {
        UUID exerciseId = UuidV7.generate();
        assertThat(commands.enqueue(queued(exerciseId, 1001, "RELOAD", QUEUED_1))).isEqualTo(1);

        ExerciseCommand found = commands.findPending(10).getFirst();
        assertThat(found.id()).isPositive();
        assertThat(found.exerciseId()).isEqualTo(exerciseId);
        assertThat(found.vestNum()).isEqualTo(1001);
        assertThat(found.command()).isEqualTo("RELOAD");
        assertThat(found.state()).isEqualTo(ExerciseCommandState.PENDING);
        assertThat(found.payloadJson()).isNull();
        assertThat(found.downlinkHex()).isNull();
        assertThat(found.operatorName()).isEqualTo("指挥员");
        assertThat(found.queuedAt()).isEqualTo(QUEUED_1);
        assertThat(found.sentAt()).isNull();
        assertThat(found.finishedAt()).isNull();
        assertThat(found.failReason()).isNull();
        assertThat(commands.findById(found.id())).isPresent();
    }

    @Test
    void enqueue_payloadJson_isNormalizedByMySql() {
        UUID exerciseId = UuidV7.generate();
        commands.enqueue(new ExerciseCommand(0L, exerciseId, 1001, "CONFIG", ExerciseCommandState.PENDING,
                "{\"weapon\":\"PRIMARY\",\"ammo\":30}", null, null, QUEUED_1, null, null, null));

        // MySQL 的 JSON 列会把对象键按「键长优先、再按字节序」重排，并统一为 ": " / ", " 分隔
        assertThat(commands.findPending(10).getFirst().payloadJson())
                .isEqualTo("""
                        {"ammo": 30, "weapon": "PRIMARY"}""");
    }

    @Test
    void findById_unknown_returnsEmpty() {
        assertThat(commands.findById(987_654_321L)).isEmpty();
    }

    @Test
    void findPending_isFifoAndOnlyPending() {
        UUID exerciseId = UuidV7.generate();
        commands.enqueue(queued(exerciseId, 1003, "DIE", QUEUED_3));
        commands.enqueue(queued(exerciseId, 1001, "DIE", QUEUED_1));
        commands.enqueue(queued(exerciseId, 1002, "DIE", QUEUED_2));
        long sent = commands.findPending(10).getFirst().id();
        commands.markSent(sent, SENT_AT, "0009ff03e9a1affa");

        List<ExerciseCommand> pending = commands.findPending(10);

        assertThat(pending).extracting(ExerciseCommand::vestNum).containsExactly(1002, 1003);
        assertThat(commands.findPending(1)).hasSize(1);
    }

    @Test
    void markSent_movesPendingToSentAndStoresDownlink() {
        long id = enqueueOne();

        assertThat(commands.markSent(id, SENT_AT, "0009ff03e9a1affa")).isEqualTo(1);

        ExerciseCommand found = commands.findById(id).orElseThrow();
        assertThat(found.state()).isEqualTo(ExerciseCommandState.SENT);
        assertThat(found.sentAt()).isEqualTo(SENT_AT);
        assertThat(found.downlinkHex()).isEqualTo("0009ff03e9a1affa");
        assertThat(commands.countByState(ExerciseCommandState.PENDING)).isZero();
    }

    @Test
    void markSent_whenNotPending_affectsNoRow() {
        long id = enqueueOne();
        commands.markSent(id, SENT_AT, "00");

        // 守卫 state=PENDING：重复下发不会把 SENT/ACKED 覆盖回 SENT
        assertThat(commands.markSent(id, DONE_AT, "01")).isZero();
        assertThat(commands.findById(id).orElseThrow().sentAt()).isEqualTo(SENT_AT);
    }

    @Test
    void markAcked_movesSentToAcked() {
        long id = enqueueOne();
        commands.markSent(id, SENT_AT, "00");

        assertThat(commands.markAcked(id, DONE_AT)).isEqualTo(1);

        ExerciseCommand found = commands.findById(id).orElseThrow();
        assertThat(found.state()).isEqualTo(ExerciseCommandState.ACKED);
        assertThat(found.finishedAt()).isEqualTo(DONE_AT);
    }

    @Test
    void markAcked_whenNotSent_affectsNoRow() {
        long id = enqueueOne();

        // 尚未下发就收到回执：不接受
        assertThat(commands.markAcked(id, DONE_AT)).isZero();
        assertThat(commands.findById(id).orElseThrow().state()).isEqualTo(ExerciseCommandState.PENDING);
    }

    @Test
    void markFinished_recordsFailedWithReason() {
        long id = enqueueOne();

        assertThat(commands.markFinished(id, ExerciseCommandState.FAILED, DONE_AT, "串口未开启")).isEqualTo(1);

        ExerciseCommand found = commands.findById(id).orElseThrow();
        assertThat(found.state()).isEqualTo(ExerciseCommandState.FAILED);
        assertThat(found.finishedAt()).isEqualTo(DONE_AT);
        assertThat(found.failReason()).isEqualTo("串口未开启");
    }

    @Test
    void markFinished_recordsExpired() {
        long id = enqueueOne();

        assertThat(commands.markFinished(id, ExerciseCommandState.EXPIRED, DONE_AT, "等待回执超时")).isEqualTo(1);

        assertThat(commands.findById(id).orElseThrow().state()).isEqualTo(ExerciseCommandState.EXPIRED);
    }

    @Test
    void markFinished_whenAlreadyAcked_affectsNoRow() {
        long id = enqueueOne();
        commands.markSent(id, SENT_AT, "00");
        commands.markAcked(id, DONE_AT);

        // 回执已到，超时扫描不得把 ACKED 覆写为 EXPIRED
        assertThat(commands.markFinished(id, ExerciseCommandState.EXPIRED, DONE_AT, "迟到的超时")).isZero();
        assertThat(commands.findById(id).orElseThrow().state()).isEqualTo(ExerciseCommandState.ACKED);
    }

    @Test
    void markFinished_rejectsNonTerminalState() {
        long id = enqueueOne();

        assertThatThrownBy(() -> commands.markFinished(id, ExerciseCommandState.ACKED, DONE_AT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FAILED or EXPIRED");
    }

    @Test
    void deleteFinished_keepsActiveCommands() {
        UUID exerciseId = UuidV7.generate();
        commands.enqueue(queued(exerciseId, 1001, "DIE", QUEUED_1));
        commands.enqueue(queued(exerciseId, 1002, "DIE", QUEUED_2));
        commands.enqueue(queued(exerciseId, 1003, "DIE", QUEUED_3));
        commands.enqueue(queued(exerciseId, 1004, "DIE", QUEUED_4));
        List<ExerciseCommand> all = commands.findPending(10);
        commands.markSent(all.get(0).id(), SENT_AT, "00");
        commands.markAcked(all.get(0).id(), DONE_AT);
        commands.markFinished(all.get(1).id(), ExerciseCommandState.FAILED, DONE_AT, "链路异常");
        commands.markSent(all.get(2).id(), SENT_AT, "00");

        assertThat(commands.deleteFinished()).isEqualTo(2);

        // PENDING（等重连）与 SENT（等回执）都必须留存
        assertThat(commands.findPending(10)).extracting(ExerciseCommand::vestNum).containsExactly(1004);
        assertThat(commands.countByState(ExerciseCommandState.SENT)).isEqualTo(1);
    }

    @Test
    void findResidualByExerciseId_returnsOnlyInFlightOfThatExerciseInIdOrder() {
        UUID busy = UuidV7.generate();
        UUID other = UuidV7.generate();
        commands.enqueue(queued(busy, 1001, "DIE", QUEUED_1));
        commands.enqueue(queued(busy, 1002, "DIE", QUEUED_2));
        commands.enqueue(queued(busy, 1003, "DIE", QUEUED_3));
        commands.enqueue(queued(busy, 1004, "DIE", QUEUED_4));
        commands.enqueue(queued(other, 2001, "DIE", QUEUED_1));

        List<ExerciseCommand> rows = pendingOf(busy);
        long pending = rows.get(0).id();
        long sent = rows.get(1).id();
        long acked = rows.get(2).id();
        long failed = rows.get(3).id();
        commands.markSent(sent, SENT_AT, "00");
        commands.markSent(acked, SENT_AT, "00");
        commands.markAcked(acked, DONE_AT);
        commands.markFinished(failed, ExerciseCommandState.FAILED, DONE_AT, "链路异常");

        // 只回本场次的在途行；ACKED（已拿到回执）与 FAILED 都不算残留
        assertThat(commands.findResidualByExerciseId(busy))
                .extracting(ExerciseCommand::id).containsExactly(pending, sent);
        assertThat(commands.findResidualByExerciseId(other))
                .extracting(ExerciseCommand::vestNum).containsExactly(2001);
    }

    @Test
    void markExpiredByExerciseId_expiresInFlightButKeepsAcked() {
        UUID busy = UuidV7.generate();
        UUID other = UuidV7.generate();
        commands.enqueue(queued(busy, 1001, "DIE", QUEUED_1));
        commands.enqueue(queued(busy, 1002, "DIE", QUEUED_2));
        commands.enqueue(queued(busy, 1003, "DIE", QUEUED_3));
        commands.enqueue(queued(other, 2001, "DIE", QUEUED_1));

        List<ExerciseCommand> rows = pendingOf(busy);
        long pending = rows.get(0).id();
        long sent = rows.get(1).id();
        long acked = rows.get(2).id();
        commands.markSent(sent, SENT_AT, "00");
        commands.markSent(acked, SENT_AT, "00");
        commands.markAcked(acked, DONE_AT);
        long otherPending = pendingOf(other).getFirst().id();

        assertThat(commands.markExpiredByExerciseId(busy, DONE_AT, "INSTANCE_RESTART")).isEqualTo(2);

        assertThat(commands.findById(pending).orElseThrow().state()).isEqualTo(ExerciseCommandState.EXPIRED);
        assertThat(commands.findById(sent).orElseThrow().state()).isEqualTo(ExerciseCommandState.EXPIRED);
        assertThat(commands.findById(sent).orElseThrow().failReason()).isEqualTo("INSTANCE_RESTART");
        assertThat(commands.findById(sent).orElseThrow().finishedAt()).isEqualTo(DONE_AT);
        // 那次下发其实成功了，作废它会抹掉事实
        assertThat(commands.findById(acked).orElseThrow().state()).isEqualTo(ExerciseCommandState.ACKED);
        assertThat(commands.findById(otherPending).orElseThrow().state()).isEqualTo(ExerciseCommandState.PENDING);
    }

    @Test
    void deleteByExerciseId_removesInFlightRowsThatGlobalCleanupLeavesBehind() {
        UUID busy = UuidV7.generate();
        UUID other = UuidV7.generate();
        commands.enqueue(queued(busy, 1001, "DIE", QUEUED_1));
        commands.enqueue(queued(other, 2001, "DIE", QUEUED_2));

        // 全局清理只动终态，重启残留的 PENDING 正是它故意留下的
        assertThat(commands.deleteFinished()).isZero();
        assertThat(commands.deleteByExerciseId(busy)).isEqualTo(1);

        assertThat(commands.findResidualByExerciseId(busy)).isEmpty();
        assertThat(commands.findResidualByExerciseId(other)).hasSize(1);
    }
}
