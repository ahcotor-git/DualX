package com.dualx.api.dto.auth;

/**
 * 登录 / 刷新返回的令牌对（契约 domain.yaml → {@code TokenPair}）。
 *
 * @param accessToken  JWT，云管签发、边缘网关用公钥离线验签
 * @param refreshToken 滚动刷新用的令牌，无状态场景下可为 null
 * @param expiresIn    accessToken 剩余有效秒数
 * @param user         当前用户，可为 null
 */
public record TokenPair(String accessToken, String refreshToken, int expiresIn, User user) {
}
