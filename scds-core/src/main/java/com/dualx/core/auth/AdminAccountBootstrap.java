package com.dualx.core.auth;

import com.dualx.core.domain.Account;
import com.dualx.core.domain.Role;
import com.dualx.core.repository.AccountRepository;
import com.dualx.core.repository.AccountRoleRepository;
import com.dualx.core.support.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 初始管理员建号（设计 03 Q5 的落地形态：口令走 {@code .env}，不种进迁移文件）。
 *
 * <p><b>只在账号不存在时建号。</b>刻意不覆盖已存在账号的口令——否则每次启动都会把
 * 口令重置成 .env 里那份，于是「建号后可从 .env 删掉口令」这件事变成假的，
 * 运维删掉之后反而会在下次启动时把口令重置为空。运维要改口令，应走账号管理接口。</p>
 *
 * <p>建号与授角色在同一事务内：只建了账号没授角色，会得到一个能通过口令校验却
 * 处处 403 的账号，那种状态只能人工捞库修，比直接失败更难处理。</p>
 */
@Component
@EnableConfigurationProperties(AdminProperties.class)
public class AdminAccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountBootstrap.class);

    private final AdminProperties properties;
    private final AccountRepository accounts;
    private final AccountRoleRepository accountRoles;
    private final PasswordEncoder passwordEncoder;

    public AdminAccountBootstrap(AdminProperties properties,
                                 AccountRepository accounts,
                                 AccountRoleRepository accountRoles,
                                 PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.accounts = accounts;
        this.accountRoles = accountRoles;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String username = trimToNull(properties.username());
        String password = properties.password();
        boolean passwordPresent = password != null && !password.isBlank();

        if (username == null || !passwordPresent) {
            // 两者都空是预期状态（CLOUD 形态由云管下发账号），不出声；
            // 只配了其中之一才是配置失误，值得一条警告。
            if (username != null || passwordPresent) {
                log.warn("初始管理员配置不完整，已跳过建号：username={}，password={}。"
                                + "两项需同时配置；CLOUD 形态下两者都留空即为预期状态。",
                        username != null ? "已配置" : "空",
                        passwordPresent ? "已配置" : "空");
            }
            return;
        }

        if (accounts.existsByUsername(username)) {
            log.info("初始管理员 {} 已存在，跳过建号（不覆盖既有口令）。", username);
            return;
        }

        UUID id = UuidV7.generate();
        String displayName = trimToNull(properties.displayName());
        accounts.insert(new Account(id, username, displayName == null ? username : displayName,
                passwordEncoder.encode(password), true, null, null, null));
        accountRoles.replaceAll(id, List.of(Role.ADMIN));

        // 用 WARN 而非 INFO：这条日志带一个必须由人执行的动作，混在启动日志里容易被略过。
        // 口令本身绝不进日志。
        log.warn("已创建初始管理员 {}（id={}，角色 ADMIN）。"
                        + "请确认该口令已妥善保管，并从 .env 中删除 SCDS_ADMIN_PASSWORD ——"
                        + "此后再启动不会再读取它，账号口令只以散列形式存在于库中。",
                username, id);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
