package com.dualx.core.repository;

import com.dualx.core.domain.Account;
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
 * 账号仓储（t_account）。用 JdbcClient 直接写 SQL，不引 ORM。
 *
 * <p>created_at / updated_at 由库默认值维护，INSERT 不写这两列；
 * 需要最新值时重新查询，避免应用侧时间与库时间出现两个版本。
 */
@Repository
public class AccountRepository {

    private static final String COLUMNS =
            "id, username, display_name, password_hash, enabled, last_login_at, created_at, updated_at";

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_account WHERE id = :id")
                .param("id", id.toString())
                .query(AccountRepository::map)
                .optional();
    }

    public Optional<Account> findByUsername(String username) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_account WHERE username = :username")
                .param("username", username)
                .query(AccountRepository::map)
                .optional();
    }

    public List<Account> findAll(PageRequest request) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_account ORDER BY username LIMIT :limit OFFSET :offset")
                .param("limit", request.size())
                .param("offset", request.offset())
                .query(AccountRepository::map)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM t_account").query(Long.class).single();
    }

    public boolean existsByUsername(String username) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM t_account WHERE username = :username)")
                .param("username", username)
                .query(Boolean.class)
                .single();
    }

    /** @return 影响行数，正常为 1 */
    public int insert(Account account) {
        return jdbc.sql("""
                        INSERT INTO t_account (id, username, display_name, password_hash, enabled, last_login_at)
                        VALUES (:id, :username, :displayName, :passwordHash, :enabled, :lastLoginAt)
                        """)
                .param("id", account.id().toString())
                .param("username", account.username())
                .param("displayName", account.displayName())
                .param("passwordHash", account.passwordHash())
                .param("enabled", account.enabled() ? 1 : 0)
                .param("lastLoginAt", SqlTime.toDb(account.lastLoginAt()))
                .update();
    }

    /** 更新可变字段；id / username / created_at 不可改。 */
    public int update(Account account) {
        return jdbc.sql("""
                        UPDATE t_account
                           SET display_name = :displayName,
                               password_hash = :passwordHash,
                               enabled = :enabled,
                               last_login_at = :lastLoginAt
                         WHERE id = :id
                        """)
                .param("id", account.id().toString())
                .param("displayName", account.displayName())
                .param("passwordHash", account.passwordHash())
                .param("enabled", account.enabled() ? 1 : 0)
                .param("lastLoginAt", SqlTime.toDb(account.lastLoginAt()))
                .update();
    }

    public int deleteById(UUID id) {
        return jdbc.sql("DELETE FROM t_account WHERE id = :id")
                .param("id", id.toString())
                .update();
    }

    private static Account map(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                UUID.fromString(rs.getString("id")),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getBoolean("enabled"),
                SqlTime.fromDb(rs.getObject("last_login_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("updated_at", LocalDateTime.class)));
    }
}
