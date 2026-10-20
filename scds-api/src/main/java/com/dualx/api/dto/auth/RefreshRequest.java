package com.dualx.api.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新令牌请求（契约 domain.yaml → {@code RefreshRequest}）。
 *
 * @param refreshToken 用于换取新 accessToken 的刷新令牌
 */
public record RefreshRequest(@NotBlank String refreshToken) {
}
