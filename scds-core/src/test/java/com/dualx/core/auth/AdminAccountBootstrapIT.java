package com.dualx.core.auth;

import com.dualx.core.domain.Account;
import com.dualx.core.domain.Role;
import com.dualx.core.repository.AccountRepository;
import com.dualx.core.repository.AccountRoleRepository;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 初始管理员建号的行为约束。
 *
 * <p>重点守的是「不覆盖既有账号」——这条一旦回归，会把「建号后可从 .env 删掉口令」
 * 变成假的：删掉之后下次启动会把口令重置成空。</p>
 */
@SpringBootTest
@Transactional
class AdminAccountBootstrapIT {

    @Autowired
    private AccountRepository accounts;
    @Autowired
    private AccountRoleRepository accountRoles;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void createsAccountWithAdminRole_whenConfigured() {
        bootstrap("it_admin_new", "s3cret-pw", "总指挥").run(null);

        Account created = accounts.findByUsername("it_admin_new").orElseThrow();
        assertThat(created.enabled()).isTrue();
        assertThat(created.displayName()).isEqualTo("总指挥");
        // 落库的必须是散列，且能校验回原文
        assertThat(created.passwordHash()).isNotEqualTo("s3cret-pw");
        assertThat(passwordEncoder.matches("s3cret-pw", created.passwordHash())).isTrue();
        assertThat(accountRoles.findByAccountId(created.id())).containsExactly(Role.ADMIN);
    }

    @Test
    void displayNameFallsBackToUsername_whenBlank() {
        bootstrap("it_admin_noname", "pw", "  ").run(null);

        assertThat(accounts.findByUsername("it_admin_noname").orElseThrow().displayName())
                .isEqualTo("it_admin_noname");
    }

    @Test
    void skipsSilently_whenBothBlank() {
        bootstrap("", "", "").run(null);

        assertThat(accounts.findByUsername("")).isEmpty();
    }

    @Test
    void skipsAndWarns_whenOnlyUsernameConfigured() {
        bootstrap("it_admin_half_configured", "", "").run(null);

        assertThat(accounts.findByUsername("it_admin_half_configured")).isEmpty();
    }

    @Test
    void doesNotOverwriteExistingAccount() {
        var id = UuidV7.generate();
        accounts.insert(new Account(id, "it_admin_existing", "原展示名",
                passwordEncoder.encode("original-pw"), true, null, null, null));

        bootstrap("it_admin_existing", "brand-new-pw", "新展示名").run(null);

        Account after = accounts.findById(id).orElseThrow();
        assertThat(after.displayName()).isEqualTo("原展示名");
        assertThat(passwordEncoder.matches("original-pw", after.passwordHash())).isTrue();
        // 既有账号也不该被补授角色：建号只发生在「不存在」时
        assertThat(accountRoles.findByAccountId(id)).isEmpty();
    }

    private AdminAccountBootstrap bootstrap(String username, String password, String displayName) {
        return new AdminAccountBootstrap(new AdminProperties(username, password, displayName),
                accounts, accountRoles, passwordEncoder);
    }
}
