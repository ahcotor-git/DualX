package com.dualx.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 指挥台账号（对应 t_account）。
 *
 * <p>{@code passwordHash} 存 BCrypt 摘要，绝不落明文；本记录只是数据载体，
 * 校验与加密由服务层负责。
 *
 * @param enabled     停用后不可登录，但不删除账号（审计日志要保引用）
 * @param lastLoginAt 首次登录前为 null
 */
public record Account(
        UUID id,
        String username,
        String displayName,
        String passwordHash,
        boolean enabled,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt) {
}
