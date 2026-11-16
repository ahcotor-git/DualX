package com.dualx.core.auth;

import com.dualx.core.domain.Role;

import java.util.List;
import java.util.UUID;

/**
 * 云端离线导出的「账号身份快照」模型（运维输入，**不是** REST 契约）。
 *
 * <p>边缘 {@code /auth/me} 直接用 JWT {@code sub} 当 {@code t_account.id} 查库，故快照里的
 * {@link Entry#id()} 必须**逐字等于**云端签发实例写入 {@code sub} 的账号 UUID（设计 03c §2.1）。</p>
 *
 * <p>本类**纯模型、无行为**：解析与逐条校验（版本位 / 重复项 / 冲突 / 角色取值）由导入器负责；
 * 导入器的写入语义见设计 03c §7.3。</p>
 *
 * @param schemaVersion 快照结构版本；当前仅接受 {@code 1}
 * @param source        快照来源标识，仅元数据
 * @param generatedAt   快照生成时刻，仅元数据
 * @param accounts      账号条目列表
 */
public record AccountSnapshot(int schemaVersion, String source, String generatedAt, List<Entry> accounts) {

    /**
     * 单个账号条目。
     *
     * <p>**不含任何口令或散列**：云端口令散列不进边缘（设计 03c §7 明令）；导入时写的是不可登录的
     * 占位散列。</p>
     *
     * @param id          账号 UUID（须与云端 {@code sub} 逐字相同）
     * @param username    登录名
     * @param displayName 展示名（可空）
     * @param roles       角色集合（非空）
     * @param enabled     是否启用；{@code false} 使本地 {@code /auth/me} 返回 401
     */
    public record Entry(UUID id, String username, String displayName, List<Role> roles, boolean enabled) {
    }
}
