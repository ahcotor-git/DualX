package com.dualx.core.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 进程实例标识（设计 01 §4.3），键 {@code scds.instance-id}。
 *
 * <p>默认取 {@link UuidV7}：时间有序，日志与库里按它排序时大致跟随进程启动顺序，
 * 排查「哪一次部署留下的行」比随机 UUID 直观。
 *
 * <p>可配置覆盖的用途是现场诊断——把同一台机器上的同一次部署视为同一实例，
 * 便于把 {@code owner_instance} 与运维手里的部署记录对上。它<b>不是</b>用来让重启后的进程
 * 「认领回」上一场的：自愈规则是启动时一律收尾（§4.1），换了标识也不会改变动作。
 *
 * <p>每个进程只解析一次（本 Bean 是单例）。重启即换新标识，这正是自愈判定所需——
 * 崩溃前的 {@code owner_instance} 必然不等于重启后的本实例。
 *
 * @param instanceId 留空或未配置时自动生成；配置值前后空白会被裁掉
 */
@ConfigurationProperties(prefix = "scds")
public record InstanceProperties(String instanceId) {

    public InstanceProperties {
        instanceId = (instanceId == null || instanceId.isBlank())
                ? UuidV7.generate().toString()
                : instanceId.trim();
    }
}
