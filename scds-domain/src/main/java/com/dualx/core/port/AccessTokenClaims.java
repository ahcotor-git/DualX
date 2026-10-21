package com.dualx.core.port;

import com.dualx.core.domain.Role;

import java.util.List;
import java.util.UUID;

/**
 * 账号 accessToken 的载荷输入（{@link TokenSigner#signAccessToken}）。
 *
 * <p>{@code roles} 是角色编码而非权限点：权限点会随版本变化，冻进 30 分钟的令牌里
 * 就要等它过期才生效；角色变化极少，且变了本来就该重登（设计 03 §4.5）。</p>
 *
 * <p>刻意不含 {@code tenantId}：本产品是「每客户独立实例」，没有多租户维度，
 * 凭据里塞一个恒定字段只会误导前端去实现并不存在的隔离逻辑。</p>
 *
 * @param accountId 账号主键（{@code t_account.id}），即 JWT 的 {@code sub}
 * @param roles     角色编码列表，写进 {@code roles} claim
 */
public record AccessTokenClaims(UUID accountId, List<Role> roles) {
}
