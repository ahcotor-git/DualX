package com.dualx.core.port;

import java.time.Duration;

/**
 * 设备令牌签发端口（设计 03b §3 / §10.2）。核心侧只描述「要签什么」，算法与密钥细节留在适配器。
 *
 * <p>与 {@link TokenSigner}（账号用途）<b>刻意分开</b>：账号与设备使用独立 issuer、独立公私钥、
 * 独立开关，设备业务只依赖本端口，不复用账号签发能力——缺设备私钥不得借账号私钥签发设备令牌
 * （03b §3）。实现落在 {@code scds-api}（{@code DeviceRsaTokenSigner}，
 * 见 03b §10.2 工作包 3a）。</p>
 *
 * <p><b>能力开关不是环境开关</b>：{@link #enabled()} 为 {@code false} 时，
 * {@code POST /api/v1/auth/device/login} 不注册（404），本类的 {@link #signDeviceToken} 不应被调用。
 * 是否具备设备签发能力与「这台机器是 CLOUD 还是 PRIVATE」正交，禁止用 {@code deployment-mode} 反推。</p>
 *
 * <p><b>为什么有效期也在端口上</b>：与 {@link TokenSigner} 同理——调用方要把它写进 API 响应的
 * {@code expiresIn} 与 {@code t_vest} 侧的会话过期计算。若让核心侧自己读配置，就得把
 * {@code scds-api} 的配置类下沉到 {@code scds-domain}，或把同一前缀绑定两次；取值必须同源，
 * 故随端口一起暴露。</p>
 */
public interface DeviceTokenSigner {

    /** 本进程是否具备签发设备令牌的能力（独立于账号签发能力）。 */
    boolean enabled();

    /** 设备令牌有效期（03b §2 定为 12 小时，不按日历午夜截断）。 */
    Duration deviceTokenTtl();

    /**
     * 签发设备令牌（{@code aud = scds-device}）。
     *
     * <p>{@code iss} / {@code iat} / {@code nbf} / {@code exp} / {@code jti} 由实现按自身时钟与本端口
     * 声明的有效期补齐；{@code sid} claim 取 {@link DeviceTokenClaims#sessionId()}，
     * 是「单端在线」的全部依据（与 {@code t_vest.device_session_sid} 比对，03b §6）。</p>
     */
    String signDeviceToken(DeviceTokenClaims claims);
}
