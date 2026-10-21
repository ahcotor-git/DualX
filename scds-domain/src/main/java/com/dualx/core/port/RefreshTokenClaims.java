package com.dualx.core.port;

import java.util.UUID;

/**
 * 账号 refreshToken 的载荷输入（{@link TokenSigner#signRefreshToken}）。
 *
 * <p>{@code tokenId} 由调用方生成而非实现生成：它同时是 {@code t_refresh_token.id}
 * 与 JWT 的 {@code jti}，必须先在库中留下痕迹，令牌才允许离开本进程。</p>
 *
 * @param tokenId   本令牌的主键（{@code t_refresh_token.id}），即 JWT 的 {@code jti}
 * @param accountId 账号主键，即 JWT 的 {@code sub}
 */
public record RefreshTokenClaims(UUID tokenId, UUID accountId) {
}
