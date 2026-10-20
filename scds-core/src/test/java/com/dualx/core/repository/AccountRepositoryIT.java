package com.dualx.core.repository;

import com.dualx.core.domain.Account;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class AccountRepositoryIT {

    @Autowired
    private AccountRepository accounts;

    @Test
    void insertThenFindById_roundTripsEveryColumn() {
        UUID id = UuidV7.generate();
        Instant lastLogin = Instant.parse("2026-10-20T12:34:56.789Z");
        Account drafted = new Account(id, "it_user_a", "甲", "$2a$10$abcdefghijklmnopqrstuv",
                true, lastLogin, null, null);

        assertThat(accounts.insert(drafted)).isEqualTo(1);

        Optional<Account> loaded = accounts.findById(id);
        assertThat(loaded).isPresent();
        Account account = loaded.get();
        assertThat(account.username()).isEqualTo("it_user_a");
        assertThat(account.displayName()).isEqualTo("甲");
        assertThat(account.passwordHash()).isEqualTo("$2a$10$abcdefghijklmnopqrstuv");
        assertThat(account.enabled()).isTrue();
        // datetime(3) 只到毫秒，用毫秒精度比较
        assertThat(account.lastLoginAt()).isEqualTo(lastLogin);
        // created_at / updated_at 由库默认值填充，应当非空
        assertThat(account.createdAt()).isNotNull();
        assertThat(account.updatedAt()).isNotNull();
    }

    @Test
    void lastLoginAt_isNullBeforeFirstLogin() {
        UUID id = UuidV7.generate();
        accounts.insert(new Account(id, "it_user_b", null, "hash", true, null, null, null));

        assertThat(accounts.findById(id).orElseThrow().lastLoginAt()).isNull();
    }

    @Test
    void findByUsername_returnsEmptyWhenAbsent() {
        assertThat(accounts.findByUsername("it_no_such_user")).isEmpty();
    }

    @Test
    void existsByUsername_reflectsWhatWasInserted() {
        accounts.insert(new Account(UuidV7.generate(), "it_user_c", null, "hash", true, null, null, null));

        assertThat(accounts.existsByUsername("it_user_c")).isTrue();
        assertThat(accounts.existsByUsername("it_user_zzz")).isFalse();
    }

    @Test
    void duplicateUsername_isRejectedByUniqueIndex() {
        accounts.insert(new Account(UuidV7.generate(), "it_user_dup", null, "hash", true, null, null, null));

        assertThatThrownBy(() -> accounts
                .insert(new Account(UuidV7.generate(), "it_user_dup", null, "hash", true, null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void update_changesMutableColumnsOnly() {
        UUID id = UuidV7.generate();
        accounts.insert(new Account(id, "it_user_d", "旧名", "old_hash", true, null, null, null));

        Instant login = Instant.parse("2026-10-20T01:02:03.004Z");
        assertThat(accounts.update(new Account(id, "it_user_d", "新名", "new_hash", false, login, null, null)))
                .isEqualTo(1);

        Account updated = accounts.findById(id).orElseThrow();
        assertThat(updated.username()).isEqualTo("it_user_d");
        assertThat(updated.displayName()).isEqualTo("新名");
        assertThat(updated.passwordHash()).isEqualTo("new_hash");
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.lastLoginAt()).isEqualTo(login);
    }

    @Test
    void deleteById_removesRow() {
        UUID id = UuidV7.generate();
        accounts.insert(new Account(id, "it_user_e", null, "hash", true, null, null, null));

        assertThat(accounts.deleteById(id)).isEqualTo(1);
        assertThat(accounts.findById(id)).isEmpty();
        assertThat(accounts.deleteById(id)).isZero();
    }

    @Test
    void findAll_paginatesInUsernameOrder() {
        long baseline = accounts.count();
        for (String username : List.of("it_pg_1", "it_pg_2", "it_pg_3")) {
            accounts.insert(new Account(UuidV7.generate(), username, null, "hash", true, null, null, null));
        }
        assertThat(accounts.count()).isEqualTo(baseline + 3);

        List<Account> page1 = accounts.findAll(PageRequest.of(1, 2));
        List<Account> page2 = accounts.findAll(PageRequest.of(2, 2));

        assertThat(page1).hasSize(2);
        assertThat(page1).extracting(Account::username).isSorted();
        assertThat(page2).extracting(Account::username).isSorted();
        // offset 生效：两页不重叠
        assertThat(page1).extracting(Account::id)
                .doesNotContainAnyElementsOf(page2.stream().map(Account::id).toList());
    }
}
