package com.dualx.core.auth;

/**
 * 一次成功签发的令牌对（{@code login} / {@code refresh} 的产物）。
 *
 * <p>带着 {@code session} 一起返回，是为了让登录/刷新一次就把身份交付给客户端——
 * 否则客户端拿到令牌还得再打一次 {@code /auth/me}，而现场是几十台终端同时登录，
 * 这点往返省下来是实打实的。</p>
 *
 * @param accessToken  账号 accessToken（{@code aud = scds-edge}）
 * @param refreshToken 账号 refreshToken（{@code aud = scds-refresh}），返回时其库行已存在
 * @param expiresIn    accessToken 剩余有效秒数，契约 {@code TokenPair.expiresIn}
 * @param session      同一时刻的身份快照，接口层据此填 {@code TokenPair.user}
 */
public record IssuedTokens(String accessToken, String refreshToken, int expiresIn,
                           AccountSession session) {
}
