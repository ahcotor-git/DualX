package com.dualx.core.auth;

import com.dualx.core.domain.Account;
import com.dualx.core.domain.Role;
import com.dualx.core.error.BusinessException;
import com.dualx.core.repository.AccountRepository;
import com.dualx.core.repository.AccountRoleRepository;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账号快照导入器行为约束（设计 03c §7.3 测试计划）。
 *
 * <p><b>不用 {@code @Transactional}</b>：原子性用例要在**无外层事务**下验证「全或无」，
 * 若测试方法自带事务，一切都会随测试回滚，断言就失去了意义。因此本类自行登记/清理测试行。</p>
 *
 * <p>被测件**手动构造**（沿用 {@code AdminAccountBootstrapIT} 口径）：真实仓储 ＋ 真实
 * {@code PasswordEncoder} ＋ 真实 {@code TransactionTemplate}，只把属性换成每条用例指定的文件。</p>
 */
@SpringBootTest
class AccountSnapshotImporterIT {

    @TempDir
    Path tempDir;

    @Autowired
    private AccountRepository accounts;
    @Autowired
    private AccountRoleRepository accountRoles;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AuthService authService;

    /** 本类自己造出来的行（含被测件写入的），逐条清理，避免污染隔离库。 */
    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : created) {
            accountRoles.deleteByAccountId(id);
            accounts.deleteById(id);
        }
        created.clear();
    }

    @Test
    void insertsNewAccountWithRolesAndUnusablePassword() throws IOException {
        UUID id = track(UuidV7.generate());
        Path file = write(snapshot(entry(id, "edge_alice", "爱丽丝", true, "ADMIN", "VIEWER")));

        importer(file).run(null);

        Account created = accounts.findById(id).orElseThrow();
        assertThat(created.username()).isEqualTo("edge_alice");
        assertThat(created.displayName()).isEqualTo("爱丽丝");
        assertThat(created.enabled()).isTrue();
        assertThat(created.lastLoginAt()).isNull();
        // 占位散列：明文随机即弃，任何猜测都不匹配 ⇒ 不可登录
        assertThat(passwordEncoder.matches("", created.passwordHash())).isFalse();
        assertThat(passwordEncoder.matches("password", created.passwordHash())).isFalse();
        assertThat(accountRoles.findByAccountId(id)).containsExactly(Role.ADMIN, Role.VIEWER);
    }

    @Test
    void updatesExistingAccountWithoutTouchingPasswordHashOrLastLogin() throws IOException {
        UUID id = track(UuidV7.generate());
        String originalHash = passwordEncoder.encode("original-pw");
        accounts.insert(new Account(id, "edge_bob", "旧名", originalHash, true, null, null, null));
        accountRoles.replaceAll(id, List.of(Role.OPERATOR));

        Path file = write(snapshot(entry(id, "edge_bob", "新名", false, "VIEWER")));
        importer(file).run(null);

        Account after = accounts.findById(id).orElseThrow();
        assertThat(after.displayName()).isEqualTo("新名");
        assertThat(after.enabled()).isFalse();
        // 口令散列逐字不变、最后登录时间不被触碰
        assertThat(after.passwordHash()).isEqualTo(originalHash);
        assertThat(passwordEncoder.matches("original-pw", after.passwordHash())).isTrue();
        assertThat(after.lastLoginAt()).isNull();
        assertThat(accountRoles.findByAccountId(id)).containsExactly(Role.VIEWER);
    }

    @Test
    void rejectsWholeSnapshotWhenExistingIdHasDifferentUsername() throws IOException {
        UUID id = track(UuidV7.generate());
        accounts.insert(new Account(id, "edge_carol", "C", passwordEncoder.encode("pw"), true, null, null, null));

        Path file = write(snapshot(entry(id, "edge_renamed", null, true, "ADMIN")));

        assertThatThrownBy(() -> importer(file).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("整体拒绝");

        assertThat(accounts.findById(id).orElseThrow().username()).isEqualTo("edge_carol");
        assertThat(accounts.findByUsername("edge_renamed")).isEmpty();
    }

    @Test
    void rejectsWholeSnapshotWhenUsernameBelongsToAnotherId() throws IOException {
        UUID taken = track(UuidV7.generate());
        accounts.insert(new Account(taken, "edge_taken", "T", passwordEncoder.encode("pw"), true, null, null, null));

        UUID newId = track(UuidV7.generate());
        Path file = write(snapshot(entry(newId, "edge_taken", null, true, "ADMIN")));

        assertThatThrownBy(() -> importer(file).run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(accounts.findById(newId)).isEmpty();
    }

    @Test
    void rejectsDuplicateIdWithinSnapshot() throws IOException {
        UUID id = track(UuidV7.generate());
        Path file = write(snapshot(
                entry(id, "dup_one", null, true, "ADMIN"),
                entry(id, "dup_two", null, true, "ADMIN")));

        assertThatThrownBy(() -> importer(file).run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(accounts.findById(id)).isEmpty();
    }

    @Test
    void rejectsUnknownRole() throws IOException {
        UUID id = track(UuidV7.generate());
        Path file = write(snapshot(
                "{\"id\":\"" + id + "\",\"username\":\"role_user\",\"displayName\":null,"
                        + "\"roles\":[\"SUPERADMIN\"],\"enabled\":true}"));

        assertThatThrownBy(() -> importer(file).run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(accounts.findById(id)).isEmpty();
    }

    @Test
    void rejectsMalformedUuid() throws IOException {
        Path file = write(snapshot(
                "{\"id\":\"not-a-uuid\",\"username\":\"bad\",\"displayName\":null,"
                        + "\"roles\":[\"ADMIN\"],\"enabled\":true}"));

        assertThatThrownBy(() -> importer(file).run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonV7Uuid() throws IOException {
        UUID v4 = track(UUID.randomUUID());
        Path file = write(snapshot(entry(v4, "v4_user", null, true, "ADMIN")));

        assertThatThrownBy(() -> importer(file).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("版本位");
        assertThat(accounts.findById(v4)).isEmpty();
    }

    @Test
    void rollsBackWholeSnapshotWhenAnyEntryConflicts() throws IOException {
        UUID conflicting = track(UuidV7.generate());
        accounts.insert(new Account(conflicting, "atomic_old", "O", passwordEncoder.encode("pw"),
                true, null, null, null));

        UUID valid = track(UuidV7.generate());
        Path file = write(snapshot(
                entry(valid, "atomic_new", null, true, "OPERATOR"),
                entry(conflicting, "atomic_renamed", null, true, "ADMIN")));

        assertThatThrownBy(() -> importer(file).run(null)).isInstanceOf(IllegalStateException.class);

        // 无外层事务：合法那条也不落库
        assertThat(accounts.findById(valid)).isEmpty();
        assertThat(accountRoles.findByAccountId(valid)).isEmpty();
        assertThat(accounts.findById(conflicting).orElseThrow().username()).isEqualTo("atomic_old");
    }

    @Test
    void blankImportFileIsNoOp() {
        long before = accounts.count();

        // 属性「存在但为空白」：bean 会被条件创建，但导入器必须什么都不做
        new AccountSnapshotImporter(new IdentitySyncProperties("   "),
                accounts, accountRoles, passwordEncoder, transactionManager).run(null);

        assertThat(accounts.count()).isEqualTo(before);
    }

    @Test
    void missingFileFailsClearly() {
        assertThatThrownBy(() -> importer(tempDir.resolve("does-not-exist.json")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void malformedJsonFailsClearly() throws IOException {
        Path file = write("{ this is not json");

        assertThatThrownBy(() -> importer(file).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解析失败");
    }

    @Test
    void rejectsUnsupportedSchemaVersion() throws IOException {
        Path file = write("{\"schemaVersion\":2,\"source\":\"cloud\","
                + "\"generatedAt\":\"2026-11-16T00:00:00Z\",\"accounts\":[]}");

        assertThatThrownBy(() -> importer(file).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void importedAccountIsVisibleThroughMe() throws IOException {
        UUID id = track(UuidV7.generate());
        Path file = write(snapshot(entry(id, "edge_me", "我", true, "DIRECTOR")));

        importer(file).run(null);

        AccountSession session = authService.me(id);
        assertThat(session.account().id()).isEqualTo(id);
        assertThat(session.account().username()).isEqualTo("edge_me");
        assertThat(session.account().displayName()).isEqualTo("我");
        assertThat(session.roles()).containsExactly(Role.DIRECTOR);
        assertThat(session.permissions()).isNotEmpty();
    }

    @Test
    void disabledImportedAccountIsNotVisibleThroughMe() throws IOException {
        UUID id = track(UuidV7.generate());
        Path file = write(snapshot(entry(id, "edge_disabled", null, false, "VIEWER")));

        importer(file).run(null);

        // C2：本地 enabled=false ⇒ me 沿用现有语义（401），但不声称 accessToken 即时撤销
        assertThatThrownBy(() -> authService.me(id)).isInstanceOf(BusinessException.class);
    }

    private AccountSnapshotImporter importer(Path file) {
        return new AccountSnapshotImporter(new IdentitySyncProperties(file.toString()),
                accounts, accountRoles, passwordEncoder, transactionManager);
    }

    private Path write(String json) throws IOException {
        Path file = tempDir.resolve("snapshot-" + UUID.randomUUID() + ".json");
        Files.writeString(file, json);
        return file;
    }

    private UUID track(UUID id) {
        created.add(id);
        return id;
    }

    private static String snapshot(String... entries) {
        return "{\"schemaVersion\":1,\"source\":\"cloud\",\"generatedAt\":\"2026-11-16T00:00:00Z\","
                + "\"accounts\":[" + String.join(",", entries) + "]}";
    }

    private static String entry(UUID id, String username, String displayName, boolean enabled, String... roles) {
        String roleJson = Arrays.stream(roles).map(role -> "\"" + role + "\"").collect(Collectors.joining(","));
        String nameJson = displayName == null ? "null" : "\"" + displayName + "\"";
        return "{\"id\":\"" + id + "\",\"username\":\"" + username + "\",\"displayName\":" + nameJson
                + ",\"roles\":[" + roleJson + "],\"enabled\":" + enabled + "}";
    }
}
