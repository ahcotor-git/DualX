package com.dualx.core.support;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;

/**
 * 全局 ID 生成入口：业务实体主键统一 UUIDv7（CHAR(36)），由应用侧生成。
 *
 * <p>只暴露这一个方法，是为了让「用 v7」成为绕不过去的约定——uuid-creator 还有
 * 随机 v4 等工厂方法，直接调用会破坏 V1 迁移文件头声明的 ID 策略，而库层拦不住。
 *
 * <p>选 v7 而非 v4：v7 带毫秒时间前缀，作为 InnoDB 聚簇主键时索引页分裂远小于
 * 纯随机值；同时保留不撞号特性，满足云管/边缘双形态下的双向汇入对账。
 */
public final class UuidV7 {

    private UuidV7() {
    }

    public static UUID generate() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
