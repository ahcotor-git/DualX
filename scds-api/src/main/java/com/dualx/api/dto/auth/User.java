package com.dualx.api.dto.auth;

import com.dualx.core.domain.Role;

import java.time.Instant;
import java.util.List;

/**
 * 当前登录用户（契约 domain.yaml → {@code User}）。
 *
 * @param userId      用户唯一标识
 * @param username    登录账号
 * @param displayName 展示名，可为 null
 * @param roles       角色编码，复用 {@link Role}
 * @param permissions 细粒度权限点，前端据此显隐按钮
 * @param lastLoginAt 上次登录时刻，首次登录前为 null
 */
public record User(String userId, String username, String displayName, List<Role> roles,
                   List<String> permissions, Instant lastLoginAt) {
}
