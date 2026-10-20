package com.dualx.core.repository;

import com.dualx.core.domain.Role;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 账号角色仓储（t_account_role）。一人可多角色，故用 (account_id, role_code) 组合主键。
 *
 * <p>刻意不做成单列逗号串：逗号串既建不了索引，也约束不了取值。
 */
@Repository
public class AccountRoleRepository {

    private final JdbcClient jdbc;

    public AccountRoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Role> findByAccountId(UUID accountId) {
        return jdbc.sql("SELECT role_code FROM t_account_role WHERE account_id = :accountId ORDER BY role_code")
                .param("accountId", accountId.toString())
                .query(String.class)
                .list()
                .stream()
                .map(Role::valueOf)
                .toList();
    }

    /**
     * 整体替换某账号的角色集合（先删后插），与「编成整体替换」同一策略：
     * 逐行 diff 会让调用方承担状态比对，而角色集合很小，全量重写更简单可靠。
     */
    @Transactional
    public int replaceAll(UUID accountId, Collection<Role> roles) {
        deleteByAccountId(accountId);
        int inserted = 0;
        for (Role role : roles) {
            inserted += jdbc.sql("INSERT INTO t_account_role (account_id, role_code) VALUES (:accountId, :roleCode)")
                    .param("accountId", accountId.toString())
                    .param("roleCode", role.name())
                    .update();
        }
        return inserted;
    }

    public int deleteByAccountId(UUID accountId) {
        return jdbc.sql("DELETE FROM t_account_role WHERE account_id = :accountId")
                .param("accountId", accountId.toString())
                .update();
    }
}
