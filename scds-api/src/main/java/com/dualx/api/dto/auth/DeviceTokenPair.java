package com.dualx.api.dto.auth;

/**
 * 单兵终端登录返回的设备令牌（契约 domain.yaml → {@code DeviceTokenPair}）。
 *
 * @param deviceToken 设备令牌
 * @param expiresIn   令牌剩余有效秒数
 */
public record DeviceTokenPair(String deviceToken, int expiresIn) {
}
