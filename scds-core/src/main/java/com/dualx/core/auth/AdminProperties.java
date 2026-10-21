package com.dualx.core.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 初始管理员配置（{@code scds.admin.*}），值来自部署侧的 {@code .env}。
 *
 * <p>为什么不在 V9 迁移里种账号：迁移文件会进版本库——写死明文口令等于所有客户共用
 * 同一把钥匙；写死散列则连口令都换不了（改口令就得改迁移文件并让 Flyway 校验和失配）。
 * 由部署侧提供明文、应用在运行期落成散列，两难皆消。</p>
 *
 * <p>三项全为空是**正常状态**（CLOUD 形态的账号由云管下发，边缘实例本就不该自建）；
 * 因此空配置只跳过建号，不报错。</p>
 *
 * @param username    登录名；为空则跳过建号
 * @param password    口令明文，仅启动建号时读取一次；建号后应从 .env 删除
 * @param displayName 展示名；为空时用登录名
 */
@ConfigurationProperties(prefix = "scds.admin")
public record AdminProperties(String username, String password, String displayName) {
}
