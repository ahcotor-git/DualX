package com.dualx.core.repository;

import com.dualx.core.domain.ExerciseCommand;
import com.dualx.core.domain.ExerciseCommandState;
import com.dualx.core.support.SqlTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 下行指令队列仓储（{@code t_exercise_command}）。一次入队 + 若干状态流转 + 清理。
 *
 * <p>每个状态流转的 WHERE 都带上「当前态」作为守卫（如 SENT→ACKED 限定
 * {@code state='SENT'}），返回 0 即表示该指令已被别的路径推进过。不这样写，重连重试与
 * 超时扫描并发时就会把 ACKED 覆写成 EXPIRED。
 *
 * <p>队列是瞬态的：终态（ACKED / FAILED / EXPIRED）可被 {@link #deleteFinished()} 清掉，
 * 「发过什么字节」由 {@code t_exercise_log} 永久留存。
 */
@Repository
public class ExerciseCommandRepository {

    private static final String COLUMNS =
            "id, exercise_id, vest_num, command, state, payload_json, downlink_hex, operator_name,"
                    + " queued_at, sent_at, finished_at, fail_reason";

    private final JdbcClient jdbc;

    public ExerciseCommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ExerciseCommand> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_exercise_command WHERE id = :id")
                .param("id", id)
                .query(ExerciseCommandRepository::map)
                .optional();
    }

    /** 入队（PENDING）。{@code command.id()} 被忽略，由库自增。 */
    public int enqueue(ExerciseCommand command) {
        return jdbc.sql("""
                        INSERT INTO t_exercise_command
                            (exercise_id, vest_num, command, state, payload_json, downlink_hex,
                             operator_name, queued_at)
                        VALUES (:exerciseId, :vestNum, :command, :state, :payloadJson, :downlinkHex,
                                :operatorName, :queuedAt)
                        """)
                .param("exerciseId", command.exerciseId().toString())
                .param("vestNum", command.vestNum())
                .param("command", command.command())
                .param("state", command.state().name())
                .param("payloadJson", command.payloadJson())
                .param("downlinkHex", command.downlinkHex())
                .param("operatorName", command.operatorName())
                .param("queuedAt", SqlTime.toDb(command.queuedAt()))
                .update();
    }

    /**
     * 待发指令，按入队时间升序（FIFO）。用 {@code ix_exercise_command_pending (state, queued_at)}
     * 直接命中，故不加 exercise_id 条件——同一时刻至多一场演习，多一层过滤只会让索引白建。
     */
    public List<ExerciseCommand> findPending(int limit) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM t_exercise_command WHERE state = :state ORDER BY queued_at LIMIT :limit")
                .param("state", ExerciseCommandState.PENDING.name())
                .param("limit", limit)
                .query(ExerciseCommandRepository::map)
                .list();
    }

    /**
     * 下发：PENDING → SENT，同时落定下行原文（组帧在此之前完成）。
     *
     * @return 影响行数。0 表示该指令不是 PENDING（已被别的路径处理）
     */
    public int markSent(long id, Instant sentAt, String downlinkHex) {
        return jdbc.sql("""
                        UPDATE t_exercise_command
                           SET state = :sent, sent_at = :sentAt, downlink_hex = :downlinkHex
                         WHERE id = :id AND state = :pending
                        """)
                .param("sent", ExerciseCommandState.SENT.name())
                .param("sentAt", SqlTime.toDb(sentAt))
                .param("downlinkHex", downlinkHex)
                .param("id", id)
                .param("pending", ExerciseCommandState.PENDING.name())
                .update();
    }

    /**
     * 马甲回执：SENT → ACKED。
     *
     * @return 影响行数。0 表示该指令不在 SENT（如回执重复到达）
     */
    public int markAcked(long id, Instant finishedAt) {
        return jdbc.sql("""
                        UPDATE t_exercise_command
                           SET state = :acked, finished_at = :finishedAt
                         WHERE id = :id AND state = :sent
                        """)
                .param("acked", ExerciseCommandState.ACKED.name())
                .param("finishedAt", SqlTime.toDb(finishedAt))
                .param("id", id)
                .param("sent", ExerciseCommandState.SENT.name())
                .update();
    }

    /**
     * 终结为失败或超时作废（FAILED / EXPIRED），仅允许从活跃态（PENDING / SENT）进入。
     *
     * @return 影响行数。0 表示该指令已处于终态
     */
    public int markFinished(long id, ExerciseCommandState state, Instant finishedAt, String failReason) {
        if (state != ExerciseCommandState.FAILED && state != ExerciseCommandState.EXPIRED) {
            throw new IllegalArgumentException("markFinished accepts FAILED or EXPIRED, got " + state);
        }
        return jdbc.sql("""
                        UPDATE t_exercise_command
                           SET state = :state, finished_at = :finishedAt, fail_reason = :failReason
                         WHERE id = :id AND state IN (:pending, :sent)
                        """)
                .param("state", state.name())
                .param("finishedAt", SqlTime.toDb(finishedAt))
                .param("failReason", failReason)
                .param("id", id)
                .param("pending", ExerciseCommandState.PENDING.name())
                .param("sent", ExerciseCommandState.SENT.name())
                .update();
    }

    /**
     * 清理终态指令，返回清理条数。未终结的（PENDING / SENT）一律保留——它们还在等重连或回执。
     *
     * <p>不做「保留最近 N 天」的时间窗：队列本就只承载当前这场演习的在途指令，
     * 演习结束时的清理天然把上场的残留一并带走。
     */
    public int deleteFinished() {
        return jdbc.sql("DELETE FROM t_exercise_command WHERE state IN (:acked, :failed, :expired)")
                .param("acked", ExerciseCommandState.ACKED.name())
                .param("failed", ExerciseCommandState.FAILED.name())
                .param("expired", ExerciseCommandState.EXPIRED.name())
                .update();
    }

    public long countByState(ExerciseCommandState state) {
        return jdbc.sql("SELECT COUNT(*) FROM t_exercise_command WHERE state = :state")
                .param("state", state.name())
                .query(Long.class)
                .single();
    }

    private static ExerciseCommand map(ResultSet rs, int rowNum) throws SQLException {
        return new ExerciseCommand(
                rs.getLong("id"),
                UUID.fromString(rs.getString("exercise_id")),
                rs.getInt("vest_num"),
                rs.getString("command"),
                ExerciseCommandState.valueOf(rs.getString("state")),
                rs.getString("payload_json"),
                rs.getString("downlink_hex"),
                rs.getString("operator_name"),
                SqlTime.fromDb(rs.getObject("queued_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("sent_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("finished_at", LocalDateTime.class)),
                rs.getString("fail_reason"));
    }
}
