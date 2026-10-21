package com.dualx.api.dto.auth;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单兵终端（马甲 App）登录请求（契约 domain.yaml → {@code DeviceLoginRequest}）。
 *
 * @param vestNum 马甲编号，取值范围与协议 uint16 及库内 {@code ck_vest_num} 一致（1..65532）
 * @param secret  设备绑定密钥明文，服务端与 {@code t_vest.bind_secret_hash}（BCrypt）比对
 */
public record DeviceLoginRequest(
        @NotNull @Min(1) @Max(65532) Integer vestNum,
        @NotBlank String secret) {
}
