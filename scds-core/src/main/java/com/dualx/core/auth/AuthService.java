package com.dualx.core.auth;

import com.dualx.core.domain.Account;
import com.dualx.core.domain.Role;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.AccessTokenClaims;
import com.dualx.core.port.RefreshTokenClaims;
import com.dualx.core.port.TokenSigner;
import com.dualx.core.repository.AccountRepository;
import com.dualx.core.repository.AccountRoleRepository;
import com.dualx.core.repository.RefreshTokenRepository;
import com.dualx.core.support.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 账号认证：登录 / 滚动刷新 / 退出 / 取自身信息（设计 03 §4.2、§4.8、§4.10）。
 *
 * <p><b>只依赖 {@link TokenSigner} 端口，不引用 Spring Security Web。</b>
 * 令牌算法与密钥留在 {@code scds-api} 的适配器里；本类只回答「这个身份该不该发令牌」。</p>
 *
 * <p>账号行锁串行化登录、刷新与退出。独立事务保证换发原子性；重放路径先提交撤销，
 * 再在事务外抛业务异常，防止调用方的事务回滚撤销证据。</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 库内统一存 UTC 挂钟值（V1 迁移文件头第 2 条）。 */
    private static final Clock CLOCK = Clock.systemUTC();

    /**
     * 未登记用户名的限流键。所有查不到账号的用户名共用这一个桶，理由见 {@link #login}。
     * 取值刻意避开 UUID 文本形态，不会与任何 {@code account.id()} 撞键。
     */
    private static final String UNKNOWN_ACCOUNT_KEY = "unknown";

    private final AccountRepository accounts;
    private final AccountRoleRepository accountRoles;
    private final RefreshTokenRepository refreshTokens;
    private final LoginThrottle throttle;
    private final PasswordEncoder passwordEncoder;
    private final TokenSigner tokenSigner;
    private final TransactionTemplate transaction;

    public AuthService(AccountRepository accounts,
                       AccountRoleRepository accountRoles,
                       RefreshTokenRepository refreshTokens,
                       LoginThrottle throttle,
                       PasswordEncoder passwordEncoder,
                       TokenSigner tokenSigner,
                       PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.accountRoles = accountRoles;
        this.refreshTokens = refreshTokens;
        this.throttle = throttle;
        this.passwordEncoder = passwordEncoder;
        this.tokenSigner = tokenSigner;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 账号登录。
     *
     * @param clientIp 客户端 IP，仅用于限流口径（设计 03 §4.10）
     */
    public IssuedTokens login(String username, String password, String clientIp) {
        requireSigning();
        // D01 身份归一化：限流键必须取自库内身份，不能取自请求原文。t_account.username 的排序
        // 规则是 utf8mb4_0900_ai_ci（不区分大小写与重音），"Admin" 与 "admin" 在库里就是同一行；
        // 若按原文记失败，"Admin" 试 3 次 + "admin" 试 3 次就绕过了 5 次的单账号上限。
        // 代价是锁定期内仍会查这一库（设计 03 §4.10）。
        Account candidate = accounts.findByUsername(username).orElse(null);
        String accountKey = candidate == null
                // 未登记的用户名共用一个桶：既不让每个乱填的用户名都占一个键（review R27 的
                // 桶容量上限），也让「未命中账号」与「口令错误」同为 5 次锁定——否则
                // 「试不存在的用户名不会被锁」会把用户名是否存在从 429/401 的差异里泄漏出去。
                ? UNKNOWN_ACCOUNT_KEY
                : candidate.id().toString();

        // 锁定期判定必须在校验口令之前：锁定期内不因「这次口令正好对了」而放行，
        // 否则攻击者撞对一次即可解锁（设计 03 §4.10）
        throttle.lockout(accountKey, clientIp).ifPresent(lock -> {
            log.warn("登录被限流：username={} 口径={} 剩余={}s",
                    username, lock.scope(), lock.retryAfter().toSeconds());
            throw BusinessException.rateLimited(lock.retryAfter());
        });

        IssuedTokens issued = transaction.execute(status -> {
            // 事务外那次查找只用来定限流键，不作鉴权依据：持行锁后按 id 重读，
            // 账号是否被禁用、口令是否匹配一律以锁内状态为准。
            Account account = candidate == null
                    ? null
                    : accounts.findByIdForUpdate(candidate.id()).orElse(null);
            if (account == null || !account.enabled()
                    || !passwordEncoder.matches(password, account.passwordHash())) {
                throttle.recordFailure(accountKey, clientIp);
                throw new BusinessException(ApiErrorCode.AUTH_BAD_CREDENTIALS, "账号或密码错误");
            }
            Instant now = CLOCK.instant();
            accounts.update(withLastLoginAt(account, now));
            return issue(account, UuidV7.generate(), now);
        });
        throttle.reset(accountKey, clientIp);
        return issued;
    }

    /**
     * 滚动刷新：作废旧 refreshToken、换发一对新令牌。
     *
     * @param refreshToken 令牌原文；靠其 SHA-256 摘要命中库行（见
     *                     {@link RefreshTokenRepository#findByTokenHash(String)}）
     */
    public IssuedTokens refresh(String refreshToken) {
        requireSigning();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw invalidToken();
        }
        String hash = sha256Hex(refreshToken);
        IssuedTokens issued = transaction.execute(status -> refreshLocked(hash));
        // null 只表示重放：必须在撤销事务成功提交后才向调用方抛异常。
        if (issued == null) {
            throw invalidToken();
        }
        return issued;
    }

    private IssuedTokens refreshLocked(String hash) {
        RefreshToken located = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.AUTH_TOKEN_INVALID, "刷新令牌不成立"));
        Account account = accounts.findByIdForUpdate(located.accountId()).orElse(null);
        RefreshToken row = refreshTokens.findByTokenHashForUpdate(hash).orElseThrow(AuthService::invalidToken);
        Instant now = CLOCK.instant();

        if (row.revoked()) {
            int revoked = refreshTokens.revokeAllByAccountId(row.accountId(), now);
            log.warn("refreshToken 重放：account={} 撤销 {} 条", row.accountId(), revoked);
            return null;
        }
        if (!row.expiresAt().isAfter(now)) {
            throw new BusinessException(ApiErrorCode.AUTH_TOKEN_EXPIRED, "刷新令牌已过期，请重新登录");
        }

        if (account == null || !account.enabled()) {
            throw invalidToken();
        }
        UUID newId = UuidV7.generate();
        if (refreshTokens.revoke(row.id(), newId, now) == 0) {
            refreshTokens.revokeAllByAccountId(account.id(), now);
            return null;
        }
        return issue(account, newId, now);
    }

    /**
     * 退出登录。
     *
     * <p>契约的 {@code logout} 没有请求体，服务端手上只有 accessToken 里的 {@code sub}，
     * 因此撤销范围是**该账号全部未撤销的 refreshToken**（按「退出即全端退出」语义）。
     * accessToken 无状态不可撤销，由 30 分钟短有效期兜底。</p>
     */
    public void logout(UUID accountId) {
        int revoked = transaction.execute(status -> {
            accounts.findByIdForUpdate(accountId);
            return refreshTokens.revokeAllByAccountId(accountId, CLOCK.instant());
        });
        log.info("退出登录：account={} 撤销 {} 条 refreshToken", accountId, revoked);
    }

    /** 取账号身份快照（{@code GET /api/v1/auth/me}）。 */
    public AccountSession me(UUID accountId) {
        return session(accounts.findById(accountId)
                .filter(Account::enabled)
                .orElseThrow(AuthService::invalidToken));
    }

    /**
     * 签发一对令牌，并把 refreshToken 的库行落下。
     *
     * @param refreshTokenId 由调用方先定好：它同时是 {@code t_refresh_token.id} 与 JWT 的
     *                       {@code jti}。滚动刷新还要拿它写旧行的 {@code replaced_by}，
     *                       故不能藏在本方法内部生成
     */
    private IssuedTokens issue(Account account, UUID refreshTokenId, Instant now) {
        List<Role> roles = accountRoles.findByAccountId(account.id());
        String accessToken = tokenSigner.signAccessToken(new AccessTokenClaims(account.id(), roles));

        // 顺序是「先定 id → 签发 → 落库」。字面上的「先 insert 后签」做不到——token_hash
        // 非空，而摘要只能由签名结果算出；真正要守的约束是「令牌离开本进程（返回给客户端）
        // 之前，库里必须已有这一行」，下面这行 insert 在 return 之前
        String refreshToken = tokenSigner.signRefreshToken(new RefreshTokenClaims(refreshTokenId, account.id()));
        refreshTokens.insert(RefreshToken.issued(refreshTokenId, account.id(), sha256Hex(refreshToken),
                now, now.plus(tokenSigner.refreshTokenTtl())));

        return new IssuedTokens(accessToken, refreshToken,
                Math.toIntExact(tokenSigner.accessTokenTtl().toSeconds()), session(account, roles));
    }

    private void requireSigning() {
        if (!tokenSigner.enabled()) {
            throw new IllegalStateException("Token signing is disabled");
        }
    }

    private static BusinessException invalidToken() {
        return new BusinessException(ApiErrorCode.AUTH_TOKEN_INVALID, "刷新令牌已失效，请重新登录");
    }

    private AccountSession session(Account account) {
        return session(account, accountRoles.findByAccountId(account.id()));
    }

    private AccountSession session(Account account, List<Role> roles) {
        return new AccountSession(account, roles, RolePermissions.of(roles));
    }

    private static Account withLastLoginAt(Account account, Instant lastLoginAt) {
        return new Account(account.id(), account.username(), account.displayName(), account.passwordHash(),
                account.enabled(), lastLoginAt, account.createdAt(), account.updatedAt());
    }

    /**
     * 令牌原文 → SHA-256 十六进制摘要（库内 {@code token_hash} 列，V9）。
     *
     * <p>用 SHA-256 而非 BCrypt：令牌是高熵随机串、不是人设的口令，不需要抗暴力破解的慢哈希，
     * 而刷新路径会频繁走它，慢哈希只会白白拖慢（V9 迁移文件头已说明）。</p>
     */
    private static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走到这里说明运行环境本身不成立
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }
}
