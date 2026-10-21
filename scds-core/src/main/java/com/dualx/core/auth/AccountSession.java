package com.dualx.core.auth;

import com.dualx.core.domain.Account;
import com.dualx.core.domain.Role;

import java.util.List;

/**
 * 一个账号的身份快照：{@code GET /api/v1/auth/me} 的载荷，登录与刷新也复用它。
 *
 * <p>放在 {@code scds-core} 而不是直接返回 {@code scds-api} 的 DTO：接口层的
 * {@code User} 属于 api，核心侧不能反向依赖。这里只描述「服务端算出了什么」，
 * 字段命名与拼装成契约形状是接口层的事。</p>
 *
 * <p>{@code permissions} 由核心侧用 {@link RolePermissions} 算好一并带出，
 * 而不是把 {@code roles} 交给接口层再去查表——权限点映射是业务规则，
 * 让接口层各算一遍迟早会算出两个版本。</p>
 *
 * @param account     账号行本身，供接口层取 username / displayName / lastLoginAt
 * @param roles       角色编码，按 {@code role_code} 升序
 * @param permissions 权限点并集（{@link RolePermissions#of(java.util.Collection)}）
 */
public record AccountSession(Account account, List<Role> roles, List<String> permissions) {
}
