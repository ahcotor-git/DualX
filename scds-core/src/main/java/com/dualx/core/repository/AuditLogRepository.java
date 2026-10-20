package com.dualx.core.repository;

import com.dualx.core.domain.AuditLog;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.SqlTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 审计日志仓储（t_audit_log）。只增不改不删，写入量远小于轨迹表，故用自增主键。
 */
@Repository
public class AuditLogRepository {

    private static final String COLUMNS =
            "id, account_id, action, target_type, target_id, detail_json, client_ip, at";

    private final JdbcClient jdbc;

    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AuditLog> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_audit_log WHERE id = :id")
                .param("id", id)
                .query(AuditLogRepository::map)
                .optional();
    }

    public List<AuditLog> findByAccountId(UUID accountId, PageRequest request) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM t_audit_log WHERE account_id = :accountId ORDER BY id DESC LIMIT :limit OFFSET :offset")
                .param("accountId", accountId.toString())
                .param("limit", request.size())
                .param("offset", request.offset())
                .query(AuditLogRepository::map)
                .list();
    }

    public long countByAccountId(UUID accountId) {
        return jdbc.sql("SELECT COUNT(*) FROM t_audit_log WHERE account_id = :accountId")
                .param("accountId", accountId.toString())
                .query(Long.class)
                .single();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM t_audit_log").query(Long.class).single();
    }

    public int insert(AuditLog log) {
        return jdbc.sql("""
                        INSERT INTO t_audit_log (account_id, action, target_type, target_id, detail_json, client_ip, at)
                        VALUES (:accountId, :action, :targetType, :targetId, :detailJson, :clientIp, :at)
                        """)
                .param("accountId", log.accountId() == null ? null : log.accountId().toString())
                .param("action", log.action())
                .param("targetType", log.targetType())
                .param("targetId", log.targetId())
                .param("detailJson", log.detailJson())
                .param("clientIp", log.clientIp())
                .param("at", SqlTime.toDb(log.at()))
                .update();
    }

    private static AuditLog map(ResultSet rs, int rowNum) throws SQLException {
        String accountId = rs.getString("account_id");
        return new AuditLog(
                rs.getLong("id"),
                accountId == null ? null : UUID.fromString(accountId),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("detail_json"),
                rs.getString("client_ip"),
                SqlTime.fromDb(rs.getObject("at", LocalDateTime.class)));
    }
}
