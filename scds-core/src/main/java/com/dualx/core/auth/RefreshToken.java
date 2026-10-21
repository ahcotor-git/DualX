package com.dualx.core.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * 一条账号 refreshToken 记录（{@code t_refresh_token}，设计 03 §4.8）。
 *
 * <p>放在 scds-core 而非 scds-domain：这张表是边缘实例自己的会话簿记，与演习域无关，
 * 网关不需要、也不该看见它。</p>
 *
 * @param id         主键，同时是 JWT 的 {@code jti}
 * @param accountId  所属账号（{@code t_account.id}）
 * @param tokenHash  {@code SHA-256(令牌原文)} 的十六进制；库内绝不存原文
 * @param issuedAt   签发时刻
 * @param expiresAt  过期时刻
 * @param revokedAt  撤销时刻，null 表示仍有效
 * @param replacedBy 滚动刷新时指向新令牌 id，用于链路追溯
 */
public record RefreshToken(
        UUID id,
        UUID accountId,
        String tokenHash,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt,
        UUID replacedBy) {

    /** 新签发：尚未撤销、未被替换。 */
    public static RefreshToken issued(UUID id, UUID accountId, String tokenHash,
                                      Instant issuedAt, Instant expiresAt) {
        return new RefreshToken(id, accountId, tokenHash, issuedAt, expiresAt, null, null);
    }

    public boolean revoked() {
        return revokedAt != null;
    }

    /**
     * 是否可用于换发新令牌。
     *
     * <p>撤销与过期都要判：已撤销的行按设计还要区分「被盗重放」，故调用方先用
     * {@link #revoked()} 单独判一次，再落到本方法。</p>
     */
    public boolean usableAt(Instant now) {
        return !revoked() && expiresAt.isAfter(now);
    }
}
