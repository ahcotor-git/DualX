package com.dualx.core.repository;

import com.dualx.core.domain.Account;
import com.dualx.core.domain.Role;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AccountRoleRepositoryIT {

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountRoleRepository accountRoles;

    private UUID newAccount(String username) {
        UUID id = UuidV7.generate();
        accounts.insert(new Account(id, username, null, "hash", true, null, null, null));
        return id;
    }

    @Test
    void replaceAll_writesWholeSetInEnumOrder() {
        UUID id = newAccount("it_role_a");

        assertThat(accountRoles.replaceAll(id, List.of(Role.VIEWER, Role.ADMIN))).isEqualTo(2);

        // 查询按 role_code 排序，与传入顺序无关
        assertThat(accountRoles.findByAccountId(id)).containsExactly(Role.ADMIN, Role.VIEWER);
    }

    @Test
    void replaceAll_isIdempotentNotAdditive() {
        UUID id = newAccount("it_role_b");
        accountRoles.replaceAll(id, List.of(Role.ADMIN, Role.OPERATOR));

        accountRoles.replaceAll(id, List.of(Role.DIRECTOR));

        assertThat(accountRoles.findByAccountId(id)).containsExactly(Role.DIRECTOR);
    }

    @Test
    void findByAccountId_returnsEmptyForAccountWithoutRoles() {
        UUID id = newAccount("it_role_c");

        assertThat(accountRoles.findByAccountId(id)).isEmpty();
    }

    @Test
    void deleteByAccountId_onlyTouchesTargetAccount() {
        UUID kept = newAccount("it_role_d");
        UUID removed = newAccount("it_role_e");
        accountRoles.replaceAll(kept, List.of(Role.ADMIN));
        accountRoles.replaceAll(removed, List.of(Role.ADMIN));

        assertThat(accountRoles.deleteByAccountId(removed)).isEqualTo(1);

        assertThat(accountRoles.findByAccountId(removed)).isEmpty();
        assertThat(accountRoles.findByAccountId(kept)).containsExactly(Role.ADMIN);
    }
}
