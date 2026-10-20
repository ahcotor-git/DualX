package com.dualx.api.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单兵终端（马甲 App）登录请求（契约 domain.yaml → {@code DeviceLoginRequest}）。
 *
 * @param vestNum 马甲编号，绑定关系由指挥台在 device 域预先建立
 * @param secret  设备绑定密钥（或 SIM 号，由部署配置二选一）
 */
public record DeviceLoginRequest(@NotNull Integer vestNum, @NotBlank String secret) {
}
