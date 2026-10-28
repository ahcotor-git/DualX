package com.dualx.core.repository;

import com.dualx.core.domain.AsyncTaskState;
import com.dualx.core.domain.ExportFormat;
import com.dualx.core.domain.ExportTask;
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
 * 复盘导出任务仓储（{@code t_export_task}）。
 *
 * <p>状态流转与指令队列同一范式：每个 UPDATE 的 WHERE 都带「当前态」守卫，
 * 返回 0 即表示该任务已被别的路径推进（如超时扫描把 RUNNING 判失败时，任务恰好完成）。
 *
 * <p>「同一复盘同一格式的并发导出合并为一个任务」由 {@link #findActive} 支持：
 * 服务层先查活跃任务，命中即复用，不新建。
 */
@Repository
public class ExportTaskRepository {

    private static final String COLUMNS =
            "id, replay_id, format, state, progress_pct, file_name, file_path, error_message,"
                    + " expire_at, created_at, updated_at";

    /** 活跃态：尚未终结、可被合并复用的任务。永久内插进 SQL，故带引号。 */
    private static final String ACTIVE_STATES =
            "('" + AsyncTaskState.PENDING.name() + "', '" + AsyncTaskState.RUNNING.name() + "')";

    private final JdbcClient jdbc;

    public ExportTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建任务（PENDING，进度 0）。{@code task.id()} 由调用方生成 UUIDv7。 */
    public int insert(ExportTask task) {
        return jdbc.sql("""
                        INSERT INTO t_export_task (id, replay_id, format, state, progress_pct)
                        VALUES (:id, :replayId, :format, :state, :progressPct)
                        """)
                .param("id", task.id().toString())
                .param("replayId", task.replayId().toString())
                .param("format", task.format().name())
                .param("state", AsyncTaskState.PENDING.name())
                .param("progressPct", 0)
                .update();
    }

    public Optional<ExportTask> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_export_task WHERE id = :id")
                .param("id", id.toString())
                .query(ExportTaskRepository::map)
                .optional();
    }

    /**
     * 某复盘某格式的活跃任务（用于并发导出合并）。同一时刻至多一个——服务层先查后建，
     * 且这里天然只取最新的那条。
     */
    public Optional<ExportTask> findActive(UUID replayId, ExportFormat format) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_export_task"
                        + " WHERE replay_id = :replayId AND format = :format AND state IN " + ACTIVE_STATES
                        + " ORDER BY created_at DESC LIMIT 1")
                .param("replayId", replayId.toString())
                .param("format", format.name())
                .query(ExportTaskRepository::map)
                .optional();
    }

    /**
     * 待执行任务（{@code PENDING}），按创建序取一批。
     *
     * <p><b>为什么取旧不取新</b>：导出耗时主要在一场演习的编成规模上，先受理的先出结果；
     * 若按新到旧取，一批批新任务会不断插到队首，把最早那条饿死——而它正是客户端等得最久的。
     * 末位按 {@code id} 兜底：同一毫秒受理的两条（UUIDv7 时间有序）否则会在两拍之间换位，
     * 导致某一条被反复跳过。</p>
     */
    public List<ExportTask> findPending(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_export_task WHERE state = :pending"
                        + " ORDER BY created_at, id LIMIT :limit")
                .param("pending", AsyncTaskState.PENDING.name())
                .param("limit", limit)
                .query(ExportTaskRepository::map)
                .list();
    }

    /**
     * 开始执行：PENDING → RUNNING。
     *
     * @return 影响行数。0 表示该任务不是 PENDING（已被处理）
     */
    public int markRunning(UUID id) {
        return jdbc.sql("UPDATE t_export_task SET state = :running"
                        + " WHERE id = :id AND state = :pending")
                .param("running", AsyncTaskState.RUNNING.name())
                .param("id", id.toString())
                .param("pending", AsyncTaskState.PENDING.name())
                .update();
    }

    /**
     * 推进进度。
     *
     * @return 影响行数。0 表示任务已终结（无需再报进度）
     */
    public int updateProgress(UUID id, int progressPct) {
        return jdbc.sql("UPDATE t_export_task SET progress_pct = :progressPct"
                        + " WHERE id = :id AND state IN " + ACTIVE_STATES)
                .param("progressPct", progressPct)
                .param("id", id.toString())
                .update();
    }

    /**
     * 完成：写入产物位置，进度置满。
     *
     * @return 影响行数。0 表示任务已终结
     */
    public int markSucceeded(UUID id, String fileName, String filePath, Instant expireAt) {
        return jdbc.sql("""
                        UPDATE t_export_task
                           SET state = :succeeded, progress_pct = 100,
                               file_name = :fileName, file_path = :filePath, expire_at = :expireAt
                         WHERE id = :id AND state IN %s
                        """.formatted(ACTIVE_STATES))
                .param("succeeded", AsyncTaskState.SUCCEEDED.name())
                .param("fileName", fileName)
                .param("filePath", filePath)
                .param("expireAt", SqlTime.toDb(expireAt))
                .param("id", id.toString())
                .update();
    }

    /**
     * 判定失败：仅允许从活跃态进入。
     *
     * @return 影响行数。0 表示任务已终结
     */
    public int markFailed(UUID id, String errorMessage) {
        return jdbc.sql("UPDATE t_export_task SET state = :failed, error_message = :errorMessage"
                        + " WHERE id = :id AND state IN " + ACTIVE_STATES)
                .param("failed", AsyncTaskState.FAILED.name())
                .param("errorMessage", errorMessage)
                .param("id", id.toString())
                .update();
    }

    private static ExportTask map(ResultSet rs, int rowNum) throws SQLException {
        return new ExportTask(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("replay_id")),
                ExportFormat.valueOf(rs.getString("format")),
                AsyncTaskState.valueOf(rs.getString("state")),
                rs.getInt("progress_pct"),
                rs.getString("file_name"),
                rs.getString("file_path"),
                rs.getString("error_message"),
                SqlTime.fromDb(rs.getObject("expire_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("updated_at", LocalDateTime.class)));
    }
}
