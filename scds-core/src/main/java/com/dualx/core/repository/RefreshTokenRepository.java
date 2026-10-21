package com.dualx.core.repository;

import com.dualx.core.auth.RefreshToken;
import com.dualx.core.support.SqlTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 账号刷新令牌仓储（{@code t_refresh_token}，设计 03 §4.8）。
 *
 * <p>契约要求 refreshToken「可撤销」，而可撤销与无状态互斥，因此它必须落库：
 * 库行才是权威，JWT 只是指向这行的凭据。</p>
 *
 * <p><b>撤销是置 revoked_at 而非删行。</b>已撤销的行本身就是重放检测的依据——
 * 收到一个「已被换出过」的令牌，说明它被盗用或两端同时在刷新，此时按 RFC 6819
 * 撤销该账号整条链（{@link #revokeAllByAccountId}）。删行则会让这种重放变得
 * 完全不可见。</p>
 *
 * <p>表上不建外键、也没有 account 行存在性校验（V1 全局约定）：令牌行的存活期
 * 远短于账号，且账号停用后令牌本就该由服务层拒绝，靠外键拦属于把业务规则
 * 写进 schema。</p>
 */
@Repository
public class RefreshTokenRepository {

    private static final String COLUMNS =
            "id, account_id, token_hash, issued_at, expires_at, revoked_at, replaced_by";

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 影响行数，正常为 1 */
    public int insert(RefreshToken token) {
        return jdbc.sql("""
                        INSERT INTO t_refresh_token (id, account_id, token_hash, issued_at, expires_at,
                                                     revoked_at, replaced_by)
                        VALUES (:id, :accountId, :tokenHash, :issuedAt, :expiresAt, :revokedAt, :replacedBy)
                        """)
                .param("id", token.id().toString())
                .param("accountId", token.accountId().toString())
                .param("tokenHash", token.tokenHash())
                .param("issuedAt", SqlTime.toDb(token.issuedAt()))
                .param("expiresAt", SqlTime.toDb(token.expiresAt()))
                .param("revokedAt", SqlTime.toDb(token.revokedAt()))
                .param("replacedBy", uuid(token.replacedBy()))
                .update();
    }

    public Optional<RefreshToken> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_refresh_token WHERE id = :id")
                .param("id", id.toString())
                .query(RefreshTokenRepository::map)
                .optional();
    }

    /**
     * 按令牌原文的 SHA-256 十六进制定位（唯一索引 {@code uk_refresh_token_hash}）。
     *
     * <p><b>换发令牌靠本方法定位，不解析 JWT。</b>库行才是权威：令牌原文本身就是凭据，
     * 拿不到原文就构造不出能命中唯一索引的字符串，因此无需再验一次签名；而解析 JWT
     * 需要在核心侧引入解码器（{@code scds-core} 刻意只依赖 {@code spring-security-crypto}，
     * 不含 Web 过滤链），代价远大于收益。</p>
     *
     * <p>调用方负责先把原文转成十六进制摘要——哈希留在核心侧而不是让仓储接收原文，
     * 是为了让「库里绝不出现令牌原文」这件事只由一条路径负责。</p>
     */
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_refresh_token WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .query(RefreshTokenRepository::map)
                .optional();
    }

    /** 先锁账号再锁令牌；避免 REPEATABLE READ 快照漏掉等待期间发生的撤销。 */
    public Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_refresh_token WHERE token_hash = :tokenHash FOR UPDATE")
                .param("tokenHash", tokenHash)
                .query(RefreshTokenRepository::map)
                .optional();
    }

    /**
     * 滚动刷新：作废旧令牌并指向换发出来的新令牌。
     *
     * <p>{@code revoked_at IS NULL} 是刻意的并发闸门：两端同时刷新时只有一个能拿到
     * 返回 1，另一个拿到 0，服务层据此判定重放并处置整条链——否则两边都会「成功」，
     * 库里留下两条并行链，谁也说不清哪条是窃取来的。</p>
     *
     * @param replacedBy 换发出的新令牌 id；退出登录时为 null
     * @return 影响行数；0 表示这条令牌已被换出过
     */
    public int revoke(UUID id, UUID replacedBy, Instant revokedAt) {
        return jdbc.sql("""
                        UPDATE t_refresh_token
                           SET revoked_at = :revokedAt,
                               replaced_by = :replacedBy
                         WHERE id = :id
                           AND revoked_at IS NULL
                        """)
                .param("id", id.toString())
                .param("revokedAt", SqlTime.toDb(revokedAt))
                .param("replacedBy", uuid(replacedBy))
                .update();
    }

    /**
     * 撤销某账号全部仍有效的令牌（重放判定后的处置）。
     *
     * <p>只影响未撤销的行：已撤销行的 {@code revoked_at} 与 {@code replaced_by} 是链路的
     * 历史证据，不能被这次处置覆盖。</p>
     *
     * @return 影响行数，含本次触发重放的那一条
     */
    public int revokeAllByAccountId(UUID accountId, Instant revokedAt) {
        return jdbc.sql("""
                        UPDATE t_refresh_token
                           SET revoked_at = :revokedAt
                         WHERE account_id = :accountId
                           AND revoked_at IS NULL
                        """)
                .param("accountId", accountId.toString())
                .param("revokedAt", SqlTime.toDb(revokedAt))
                .update();
    }

    private static String uuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private static RefreshToken map(ResultSet rs, int rowNum) throws SQLException {
        String replacedBy = rs.getString("replaced_by");
        return new RefreshToken(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("account_id")),
                rs.getString("token_hash"),
                SqlTime.fromDb(rs.getObject("issued_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("expires_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("revoked_at", LocalDateTime.class)),
                replacedBy == null ? null : UUID.fromString(replacedBy));
    }
}
