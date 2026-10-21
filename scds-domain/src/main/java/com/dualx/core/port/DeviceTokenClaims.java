package com.dualx.core.port;

import java.util.UUID;

/**
 * 设备令牌的载荷输入（{@link TokenSigner#signDeviceToken}）。
 *
 * <p>{@code sessionId} 是「单端在线」的全部实现：每次设备登录都生成新的 sid 并写入
 * {@code t_vest.device_session_sid}，校验时比对令牌 claim 与库中该列是否相等。
 * 于是重复登录天然顶掉旧令牌，无需定时任务、无需额外的会话表（设计 03 §4.9）。</p>
 *
 * @param vestNum   马甲号，即 JWT 的 {@code sub}
 * @param sessionId 设备会话 ID（{@code t_vest.device_session_sid}），即 JWT 的 {@code sid}
 */
public record DeviceTokenClaims(int vestNum, UUID sessionId) {
}
