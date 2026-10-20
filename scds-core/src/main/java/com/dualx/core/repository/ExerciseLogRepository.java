package com.dualx.core.repository;

import com.dualx.core.domain.ExerciseLogEntry;
import com.dualx.core.support.SqlTime;
import com.dualx.protocol.LogType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 演习日志流水仓储（{@code t_exercise_log}）。只增不改不删，故只提供追加与读取。
 *
 * <p>{@code seq} 是全局自增（而非按演习分段），这样客户端拿着它做 {@code sinceSeq} 增量拉取
 * 时不必关心演习边界——换了场次 seq 依然单调，游标不会回退。
 *
 * <p>约定 {@code seq} 由库生成：{@link #append} 忽略入参里的 seq，因此不存在「插入后取回
 * 自增值」这个在连接池下并不可靠的步骤；调用方若要新 seq，从读取路径取即可。
 */
@Repository
public class ExerciseLogRepository {

    private static final String COLUMNS =
            "seq, exercise_id, at, log_type, vest_num, operator_name, detail, downlink_hex";

    private final JdbcClient jdbc;

    public ExerciseLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条。{@code entry.seq()} 被忽略。 */
    public int append(ExerciseLogEntry entry) {
        return jdbc.sql("""
                        INSERT INTO t_exercise_log
                            (exercise_id, at, log_type, vest_num, operator_name, detail, downlink_hex)
                        VALUES (:exerciseId, :at, :logType, :vestNum, :operatorName, :detail, :downlinkHex)
                        """)
                .param("exerciseId", entry.exerciseId().toString())
                .param("at", SqlTime.toDb(entry.at()))
                .param("logType", entry.logType().name())
                .param("vestNum", entry.vestNum())
                .param("operatorName", entry.operatorName())
                .param("detail", entry.detail())
                .param("downlinkHex", entry.downlinkHex())
                .update();
    }

    /**
     * 增量拉取：{@code seq > sinceSeq} 的条目，按 seq 升序（即时间顺序），至多 {@code limit} 条。
     * 客户端保存最后一条的 seq 作为下次的 sinceSeq，游标因此不会漏读。
     *
     * @param sinceSeq null 表示从头拉取
     */
    public List<ExerciseLogEntry> findByExerciseIdSince(UUID exerciseId, Long sinceSeq, int limit) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM t_exercise_log WHERE exercise_id = :exerciseId AND seq > :sinceSeq"
                        + " ORDER BY seq LIMIT :limit")
                .param("exerciseId", exerciseId.toString())
                .param("sinceSeq", sinceSeq == null ? 0L : sinceSeq)
                .param("limit", limit)
                .query(ExerciseLogRepository::map)
                .list();
    }

    /**
     * 最新一页：契约约定「不传 sinceSeq 时返回最新一页」。取 seq 最大的 limit 条，
     * 再按 seq 升序返回——滚动日志始终是从旧到新读，倒序展示会让前端每次都要反转。
     */
    public List<ExerciseLogEntry> findLatest(UUID exerciseId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM (SELECT " + COLUMNS
                        + " FROM t_exercise_log WHERE exercise_id = :exerciseId"
                        + " ORDER BY seq DESC LIMIT :limit) AS recent ORDER BY seq")
                .param("exerciseId", exerciseId.toString())
                .param("limit", limit)
                .query(ExerciseLogRepository::map)
                .list();
    }

    public long countByExerciseId(UUID exerciseId) {
        return jdbc.sql("SELECT COUNT(*) FROM t_exercise_log WHERE exercise_id = :exerciseId")
                .param("exerciseId", exerciseId.toString())
                .query(Long.class)
                .single();
    }

    private static ExerciseLogEntry map(ResultSet rs, int rowNum) throws SQLException {
        return new ExerciseLogEntry(
                rs.getLong("seq"),
                UUID.fromString(rs.getString("exercise_id")),
                SqlTime.fromDb(rs.getObject("at", LocalDateTime.class)),
                LogType.valueOf(rs.getString("log_type")),
                rs.getObject("vest_num", Integer.class),
                rs.getString("operator_name"),
                rs.getString("detail"),
                rs.getString("downlink_hex"));
    }
}
