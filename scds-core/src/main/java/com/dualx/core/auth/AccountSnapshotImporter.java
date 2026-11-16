package com.dualx.core.auth;

import com.dualx.core.domain.Account;
import com.dualx.core.domain.Role;
import com.dualx.core.repository.AccountRepository;
import com.dualx.core.repository.AccountRoleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 边缘账号身份同步导入器（设计 03c §7.3）。
 *
 * <p>边缘只验签云端令牌，但 {@code GET /api/v1/auth/me} 需要本地 {@code t_account} ＋
 * {@code t_account_role}。本工具读取运维在云端离线导出的账号快照，按 UUID
 * {@code upsert} 出最小身份（**不复制云端口令散列**，写不可登录的占位散列）。</p>
 *
 * <p><b>何时执行。</b>仅当 {@code scds.identity.import-file} 非空白时本 bean 才存在；
 * 该属性存在时每次启动都会跑一次。运维可用
 * {@code --scds.identity.import-file=<path>} 作一次性触发，跑完从配置中去掉即不再重放。</p>
 *
 * <p><b>全量或全无。</b>结构错、条目非法或冲突一律**整体拒绝**（逐条报告，抛出 ⇒ 启动失败），
 * 写入在单个事务内完成，任一条失败整体回滚（见设计 03c §4 C4 / §7.3 语义 3、4、6）。</p>
 *
 * <p><b>不做的事</b>：不删除快照外的本地行；不改既有账号的 {@code username} / {@code password_hash}
 * / {@code last_login_at}；不做新鲜度校验或回源（快照落后时 {@code me} 返回本地库现态，设计 03c §4 C1）。</p>
 */
@Component
@EnableConfigurationProperties(IdentitySyncProperties.class)
@ConditionalOnProperty(prefix = "scds.identity", name = "import-file")
public class AccountSnapshotImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountSnapshotImporter.class);

    /** 当前唯一受支持的快照结构版本（设计 03c §3.2）。 */
    private static final int SCHEMA_VERSION = 1;

    /** 与 {@code t_account.username} / {@code display_name} 的列宽一致（V1 迁移）。 */
    private static final int MAX_USERNAME = 64;
    private static final int MAX_DISPLAY_NAME = 64;

    /**
     * 快照解析用的映射器。**自备一份而非注入**：{@code scds-core} 无 {@code spring-web}，
     * 上下文本就不装配 {@code ObjectMapper} bean（Jackson 自动配置需 web 构件），
     * 本工具也不该为此反过来依赖 web 栈。快照里只有 UUID / 字符串 / 枚举 / 列表，默认配置足够。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final IdentitySyncProperties properties;
    private final AccountRepository accounts;
    private final AccountRoleRepository accountRoles;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;

    public AccountSnapshotImporter(IdentitySyncProperties properties,
                                   AccountRepository accounts,
                                   AccountRoleRepository accountRoles,
                                   PasswordEncoder passwordEncoder,
                                   PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.accounts = accounts;
        this.accountRoles = accountRoles;
        this.passwordEncoder = passwordEncoder;
        // 刻意用 TransactionTemplate 而非 @Transactional 注解：本类的集成测试会**手动构造**
        // 本类（沿用 AdminAccountBootstrapIT 口径），注解代理在那条路径上不生效，而
        // 「全或无」必须对被测件本身成立，不能只对容器装配的代理成立。
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        String path = properties.importFile();
        if (path == null) {
            // 属性存在但为空白：bean 被条件创建了，但不该做任何事。
            log.info("账号快照导入文件未设置（scds.identity.import-file 为空白），跳过同步。");
            return;
        }

        AccountSnapshot snapshot = read(path);
        int count = transaction.execute(status -> {
            List<AccountSnapshot.Entry> entries = validate(snapshot);
            apply(entries);
            return entries.size();
        });
        log.info("账号快照导入完成：{} 条（来自 {}）。", count, path);
    }

    /** 读取并反序列化快照文件；文件缺失或 JSON 非法一律明确失败。 */
    private AccountSnapshot read(String path) {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("账号快照文件不存在或不是普通文件：" + path);
        }
        try (InputStream in = Files.newInputStream(file)) {
            return OBJECT_MAPPER.readValue(in, AccountSnapshot.class);
        } catch (IOException e) {
            throw new IllegalStateException("账号快照文件解析失败：" + path, e);
        }
    }

    /**
     * 逐条校验（设计 03c §7.3 语义 3）。**收集全部问题后再抛**，让运维一次看到所有冲突；
     * 任一不通过即整体拒绝，调用方在写入前就被拦住。
     *
     * @return 校验通过的条目（原序）
     */
    private List<AccountSnapshot.Entry> validate(AccountSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalStateException("账号快照为空。");
        }
        if (snapshot.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalStateException("账号快照 schemaVersion=" + snapshot.schemaVersion()
                    + " 不受支持（仅接受 " + SCHEMA_VERSION + "）。");
        }

        List<AccountSnapshot.Entry> entries = snapshot.accounts() == null ? List.of() : snapshot.accounts();
        List<String> problems = new ArrayList<>();
        Set<UUID> seenIds = new HashSet<>();
        Set<String> seenUsernames = new HashSet<>();

        for (int i = 0; i < entries.size(); i++) {
            AccountSnapshot.Entry entry = entries.get(i);
            String at = "第 " + (i + 1) + " 条";
            if (entry == null) {
                problems.add(at + "：条目为空。");
                continue;
            }

            UUID id = entry.id();
            if (id == null) {
                problems.add(at + "：id 缺失。");
            } else if (id.version() != 7) {
                // 边缘 t_account.id 必须逐字等于云端 sub，且库约定主键为 UUIDv7（设计 03c §2.1）。
                problems.add(at + "（id=" + id + "）：id 版本位不是 7。");
            } else if (!seenIds.add(id)) {
                problems.add(at + "（id=" + id + "）：快照内 id 重复。");
            }

            String username = entry.username();
            if (username == null || username.isBlank()) {
                problems.add(at + "：username 为空。");
            } else if (username.length() > MAX_USERNAME) {
                problems.add(at + "（username=" + username + "）：username 超长（>" + MAX_USERNAME + "）。");
            } else if (!seenUsernames.add(username.toLowerCase(Locale.ROOT))) {
                // t_account.username 的排序规则不区分大小写，快照内重复也按同一口径判。
                problems.add(at + "（username=" + username + "）：快照内 username 重复。");
            }

            String displayName = entry.displayName();
            if (displayName != null && displayName.length() > MAX_DISPLAY_NAME) {
                problems.add(at + "（username=" + username + "）：displayName 超长（>" + MAX_DISPLAY_NAME + "）。");
            }

            List<Role> roles = entry.roles();
            if (roles == null || roles.isEmpty()) {
                problems.add(at + "（username=" + username + "）：roles 为空。");
            } else if (roles.contains(null)) {
                problems.add(at + "（username=" + username + "）：roles 含空元素。");
            }

            if (id != null && username != null && !username.isBlank()) {
                Account byId = accounts.findById(id).orElse(null);
                if (byId != null && !byId.username().equals(username)) {
                    problems.add(at + "（id=" + id + "）：本地同 id 账号的 username 为 " + byId.username()
                            + "，与快照 " + username + " 不一致。");
                }
                Account byName = accounts.findByUsername(username).orElse(null);
                if (byName != null && !byName.id().equals(id)) {
                    problems.add(at + "（username=" + username + "）：该用户名已被另一个 id（"
                            + byName.id() + "）占用。");
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("账号快照校验未通过，整体拒绝（共 " + problems.size() + " 处）：\n  - "
                    + String.join("\n  - ", problems));
        }
        return entries;
    }

    /** 按 UUID upsert（设计 03c §7.3 语义 4），已在校验后被调用，故此处只做写入。 */
    private void apply(List<AccountSnapshot.Entry> entries) {
        for (AccountSnapshot.Entry entry : entries) {
            UUID id = entry.id();
            String username = entry.username();
            boolean enabled = entry.enabled();
            List<Role> roles = List.copyOf(entry.roles());

            Account local = accounts.findById(id).orElse(null);
            if (local == null) {
                accounts.insert(new Account(id, username, entry.displayName(), placeholderHash(), enabled,
                        null, null, null));
                accountRoles.replaceAll(id, roles);
                log.info("账号快照：新建账号 username={} id={} roles={} enabled={}", username, id, roles, enabled);
            } else {
                // 复用 AccountRepository.update：password_hash / last_login_at 传读到的现值 ⇒
                // 不改既有口令、不动最后登录时间（username 本就与快照一致，取库内值最稳）。
                accounts.update(new Account(local.id(), local.username(), entry.displayName(),
                        local.passwordHash(), enabled, local.lastLoginAt(), local.createdAt(), local.updatedAt()));
                accountRoles.replaceAll(id, roles);
                log.info("账号快照：更新账号 username={} id={} roles={} enabled={}", username, id, roles, enabled);
            }
        }
    }

    /**
     * 占位口令散列：每次导入现取一次性随机明文再散列，明文用后即弃（设计 03c §7.1）。
     *
     * <p>因此必然**无人知道明文**（不可登录），且**每账号各不相同**（不泄露"这是云端账号"的线索）。
     * 与 {@code AuthService.DUMMY_PASSWORD_HASH} 无关联——那是登录失败路径的常量时间陪跑，两者互不复用。</p>
     */
    private String placeholderHash() {
        return passwordEncoder.encode(UUID.randomUUID().toString());
    }
}
