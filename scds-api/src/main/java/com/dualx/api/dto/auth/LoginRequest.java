package com.dualx.api.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 账号登录请求（契约 domain.yaml → {@code LoginRequest}）。
 *
 * @param username 登录账号
 * @param password 明文密码，仅存在于请求体，服务端立即做摘要比对
 */
public record LoginRequest(
        @NotBlank @Size(min = 1, max = 64) String username,
        @NotBlank String password) {
}
