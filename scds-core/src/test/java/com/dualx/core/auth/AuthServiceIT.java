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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 无测试外层事务：验证真实提交、独立事务回滚和数据库锁。
 * 只清理本用例创建的 UUID 账号及其令牌，不触碰本地已有数据。
 */
@SpringBootTest
class AuthServiceIT {

    private static final String PASSWORD = "integration-secret";
    private static final String IP = "127.0.0.2";

    @Autowired private AccountRepository accounts;
    @Autowired private AccountRoleRepository roles;
    @Autowired private RefreshTokenRepository tokens;
    @Autowired private PasswordEncoder passwords;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcClient jdbc;

    private final List<UUID> createdAccounts = new ArrayList<>();
    private TokenSigner signer;
    private LoginThrottle throttle;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        signer = mock(TokenSigner.class);
        when(signer.enabled()).thenReturn(true);
        when(signer.accessTokenTtl()).thenReturn(Duration.ofMinutes(30));
        when(signer.refreshTokenTtl()).thenReturn(Duration.ofDays(14));
        when(signer.signAccessToken(any())).thenAnswer(call -> "access-" + UuidV7.generate());
        when(signer.signRefreshToken(any())).thenAnswer(call ->
                "refresh-" + call.getArgument(0, RefreshTokenClaims.class).tokenId());
        throttle = new LoginThrottle();
        auth = service(tokens);
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : createdAccounts) {
            jdbc.sql("DELETE FROM t_refresh_token WHERE account_id = :id")
                    .param("id", id.toString()).update();
            roles.deleteByAccountId(id);
            accounts.deleteById(id);
        }
    }

    @Test
    void loginCommitsOnlyHashAndReturnsPreviousLoginAndCurrentPermissions() throws Exception {
        Instant previous = Instant.parse("2026-01-01T00:00:00Z");
        Account account = account(true, previous);
        roles.replaceAll(account.id(), List.of(Role.DIRECTOR, Role.VIEWER));

        IssuedTokens issued = login(account);
        RefreshToken stored = row(issued.refreshToken());

        assertThat(stored.accountId()).isEqualTo(account.id());
        assertThat(stored.tokenHash()).isEqualTo(hash(issued.refreshToken()));
        assertThat(stored.tokenHash()).hasSize(64).isNotEqualTo(issued.refreshToken());
        assertThat(stored.expiresAt()).isEqualTo(stored.issuedAt().plus(Duration.ofDays(14)));
        assertThat(stored.revoked()).isFalse();
        assertThat(issued.expiresIn()).isEqualTo(1800);
        assertThat(issued.session().account().lastLoginAt()).isEqualTo(previous);
        assertThat(issued.session().roles()).containsExactly(Role.DIRECTOR, Role.VIEWER);
        assertThat(issued.session().permissions()).isEqualTo(RolePermissions.of(List.of(Role.DIRECTOR, Role.VIEWER)));
        assertThat(accounts.findById(account.id()).orElseThrow().lastLoginAt()).isAfter(previous);
        verify(signer).signAccessToken(new AccessTokenClaims(account.id(), List.of(Role.DIRECTOR, Role.VIEWER)));
        verify(signer).signRefreshToken(new RefreshTokenClaims(stored.id(), account.id()));
    }

    @Test
    void missingDisabledAndWrongPasswordShareTheSameFailure() {
        assertCode(() -> auth.login("missing-" + UuidV7.generate(), PASSWORD, IP), ApiErrorCode.AUTH_BAD_CREDENTIALS);
        Account disabled = account(false, null);
        assertCode(() -> login(disabled), ApiErrorCode.AUTH_BAD_CREDENTIALS);
        Account enabled = account(true, null);
        assertCode(() -> auth.login(enabled.username(), "wrong", IP), ApiErrorCode.AUTH_BAD_CREDENTIALS);
        assertThat(countTokens(enabled.id(), false)).isZero();
        verify(signer, never()).signAccessToken(any());
    }

    @Test
    void sixthAttemptRemainsLockedEvenWithCorrectPassword() {
        Account account = account(true, null);
        for (int i = 0; i < 5; i++) {
            assertCode(() -> auth.login(account.username(), "wrong", IP), ApiErrorCode.AUTH_BAD_CREDENTIALS);
        }
        assertThatThrownBy(() -> login(account)).isInstanceOfSatisfying(BusinessException.class, failure -> {
            assertThat(failure.code()).isEqualTo(ApiErrorCode.RATE_LIMITED);
            assertThat(failure.retryAfter()).isPositive();
        });
        assertThat(countTokens(account.id(), false)).isZero();
    }

    @Test
    void successfulLoginClearsFailureCounters() {
        Account account = account(true, null);
        for (int i = 0; i < 4; i++) {
            assertCode(() -> auth.login(account.username(), "wrong", IP), ApiErrorCode.AUTH_BAD_CREDENTIALS);
        }
        login(account);
        assertCode(() -> auth.login(account.username(), "wrong", IP), ApiErrorCode.AUTH_BAD_CREDENTIALS);
        assertThat(throttle.lockout(account.username(), IP)).isEmpty();
    }

    @Test
    void refreshRotatesAndReloadsRolesWithoutUpdatingLastLogin() throws Exception {
        Account account = account(true, null);
        IssuedTokens first = login(account);
        Instant lastLogin = accounts.findById(account.id()).orElseThrow().lastLoginAt();
        roles.replaceAll(account.id(), List.of(Role.OPERATOR));

        IssuedTokens second = auth.refresh(first.refreshToken());
        RefreshToken old = row(first.refreshToken());
        RefreshToken replacement = row(second.refreshToken());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(old.revoked()).isTrue();
        assertThat(old.replacedBy()).isEqualTo(replacement.id());
        assertThat(replacement.revoked()).isFalse();
        assertThat(second.session().roles()).containsExactly(Role.OPERATOR);
        assertThat(second.session().account().lastLoginAt()).isEqualTo(lastLogin);
        assertThat(countTokens(account.id(), true)).isEqualTo(1);
    }

    @Test
    void replayRevokesAllVictimTokensButNotAnotherAccount() {
        Account victim = account(true, null);
        IssuedTokens first = login(victim);
        login(victim);
        Account other = account(true, null);
        login(other);
        auth.refresh(first.refreshToken());

        assertCode(() -> auth.refresh(first.refreshToken()), ApiErrorCode.AUTH_TOKEN_INVALID);
        assertThat(countTokens(victim.id(), true)).isZero();
        assertThat(countTokens(other.id(), true)).isEqualTo(1);
    }

    @Test
    void replayRevocationSurvivesCallerTransactionRollback() {
        Account account = account(true, null);
        IssuedTokens old = login(account);
        auth.refresh(old.refreshToken());
        TransactionTemplate outer = new TransactionTemplate(transactions);

        assertCode(() -> outer.execute(status -> auth.refresh(old.refreshToken())),
                ApiErrorCode.AUTH_TOKEN_INVALID);
        assertThat(countTokens(account.id(), true)).isZero();
    }

    @Test
    void expiredTokenIsDistinctFromUnknownOrModifiedToken() throws Exception {
        Account account = account(true, null);
        IssuedTokens issued = login(account);
        RefreshToken token = row(issued.refreshToken());
        jdbc.sql("""
                UPDATE t_refresh_token SET issued_at = '2020-01-01 00:00:00',
                                           expires_at = '2020-01-02 00:00:00' WHERE id = :id
                """).param("id", token.id().toString()).update();

        assertCode(() -> auth.refresh(issued.refreshToken()), ApiErrorCode.AUTH_TOKEN_EXPIRED);
        assertCode(() -> auth.refresh(issued.refreshToken() + "tampered"), ApiErrorCode.AUTH_TOKEN_INVALID);
        assertCode(() -> auth.refresh("unknown"), ApiErrorCode.AUTH_TOKEN_INVALID);
        assertCode(() -> auth.refresh(null), ApiErrorCode.AUTH_TOKEN_INVALID);
        assertCode(() -> auth.refresh(" "), ApiErrorCode.AUTH_TOKEN_INVALID);
    }

    @Test
    void disabledAccountCannotRefreshOrReadMe() {
        Account account = account(true, null);
        IssuedTokens issued = login(account);
        jdbc.sql("UPDATE t_account SET enabled = 0 WHERE id = :id")
                .param("id", account.id().toString()).update();
        assertCode(() -> auth.refresh(issued.refreshToken()), ApiErrorCode.AUTH_TOKEN_INVALID);
        assertCode(() -> auth.me(account.id()), ApiErrorCode.AUTH_TOKEN_INVALID);
    }

    @Test
    void deletedAccountCannotRefreshOrReadMe() {
        Account account = account(true, null);
        IssuedTokens issued = login(account);
        accounts.deleteById(account.id());
        assertCode(() -> auth.refresh(issued.refreshToken()), ApiErrorCode.AUTH_TOKEN_INVALID);
        assertCode(() -> auth.me(account.id()), ApiErrorCode.AUTH_TOKEN_INVALID);
    }

    @Test
    void logoutRevokesEverySessionAndIsIdempotent() {
        Account account = account(true, null);
        IssuedTokens first = login(account);
        login(account);
        Account other = account(true, null);
        login(other);
        auth.logout(account.id());
        auth.logout(account.id());
        assertThat(countTokens(account.id(), true)).isZero();
        assertThat(countTokens(other.id(), true)).isEqualTo(1);
        assertCode(() -> auth.refresh(first.refreshToken()), ApiErrorCode.AUTH_TOKEN_INVALID);
    }

    @Test
    void meReturnsCurrentRolesAndPermissionsWithoutSigning() {
        Account account = account(true, null);
        roles.replaceAll(account.id(), List.of(Role.ADMIN, Role.VIEWER));
        when(signer.enabled()).thenReturn(false);
        AccountSession session = auth.me(account.id());
        assertThat(session.account().id()).isEqualTo(account.id());
        assertThat(session.permissions()).containsExactly("*");
        verify(signer, never()).signAccessToken(any());
    }

    @Test
    void signingDisabledFailsBeforeAccountOrTokenMutation() {
        Account account = account(true, null);
        when(signer.enabled()).thenReturn(false);
        assertThatThrownBy(() -> login(account)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> auth.refresh("unknown")).isInstanceOf(IllegalStateException.class);
        assertThat(accounts.findById(account.id()).orElseThrow().lastLoginAt()).isNull();
        assertThat(countTokens(account.id(), false)).isZero();
    }

    @Test
    void signerFailureRollsBackLoginAndLeavesOldRefreshUsable() throws Exception {
        Account account = account(true, null);
        IssuedTokens first = login(account);
        Instant lastLogin = accounts.findById(account.id()).orElseThrow().lastLoginAt();
        doThrow(new IllegalStateException("signer unavailable")).when(signer).signRefreshToken(any());

        assertThatThrownBy(() -> login(account)).hasMessage("signer unavailable");
        assertThat(accounts.findById(account.id()).orElseThrow().lastLoginAt()).isEqualTo(lastLogin);
        assertThatThrownBy(() -> auth.refresh(first.refreshToken())).hasMessage("signer unavailable");
        assertThat(row(first.refreshToken()).revoked()).isFalse();
        assertThat(countTokens(account.id(), false)).isEqualTo(1);
    }

    @Test
    void failureAfterInsertRollsBackBothReplacementAndOldRevocation() throws Exception {
        Account account = account(true, null);
        IssuedTokens first = login(account);
        RefreshTokenRepository failing = spy(new RefreshTokenRepository(jdbc));
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("failure after insert");
        }).when(failing).insert(any());
        AuthService failingAuth = service(failing);

        assertThatThrownBy(() -> failingAuth.refresh(first.refreshToken())).hasMessage("failure after insert");
        assertThat(row(first.refreshToken()).revoked()).isFalse();
        assertThat(countTokens(account.id(), false)).isEqualTo(1);
    }

    @Test
    void concurrentRefreshHasOneWinnerAndReplayRevokesTheWinningReplacement() throws Exception {
        Account account = account(true, null);
        IssuedTokens first = login(account);
        CountDownLatch located = new CountDownLatch(2);
        RefreshTokenRepository concurrent = spy(new RefreshTokenRepository(jdbc));
        doAnswer(call -> {
            Object result = call.callRealMethod();
            located.countDown();
            await(located);
            return result;
        }).when(concurrent).findByTokenHash(anyString());
        AuthService concurrentAuth = service(concurrent);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> refreshOutcome(concurrentAuth, first.refreshToken()));
            var right = executor.submit(() -> refreshOutcome(concurrentAuth, first.refreshToken()));
            List<Object> results = List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(IssuedTokens.class::isInstance).count()).isEqualTo(1);
            assertThat(results.stream().filter(ApiErrorCode.AUTH_TOKEN_INVALID::equals).count()).isEqualTo(1);
        }
        assertThat(countTokens(account.id(), false)).isEqualTo(2);
        assertThat(countTokens(account.id(), true)).isZero();
    }

    @Test
    void logoutWaitingForRefreshRevokesItsReplacement() throws Exception {
        Account account = account(true, null);
        IssuedTokens first = login(account);
        CountDownLatch signing = new CountDownLatch(1);
        CountDownLatch logoutStarted = new CountDownLatch(1);
        doAnswer(call -> {
            signing.countDown();
            await(logoutStarted);
            return "refresh-" + call.getArgument(0, RefreshTokenClaims.class).tokenId();
        }).when(signer).signRefreshToken(any());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var refresh = executor.submit(() -> auth.refresh(first.refreshToken()));
            await(signing);
            var logout = executor.submit(() -> {
                logoutStarted.countDown();
                auth.logout(account.id());
            });
            IssuedTokens replacement = refresh.get(15, TimeUnit.SECONDS);
            logout.get(15, TimeUnit.SECONDS);
            assertThat(row(replacement.refreshToken()).revoked()).isTrue();
        }
        assertThat(countTokens(account.id(), true)).isZero();
    }

    private AuthService service(RefreshTokenRepository repository) {
        return new AuthService(accounts, roles, repository, throttle, passwords, signer, transactions);
    }

    private Account account(boolean enabled, Instant lastLogin) {
        UUID id = UuidV7.generate();
        createdAccounts.add(id);
        Account account = new Account(id, "auth_it_" + id, "Auth IT",
                passwords.encode(PASSWORD), enabled, lastLogin, null, null);
        accounts.insert(account);
        return account;
    }

    private IssuedTokens login(Account account) {
        return auth.login(account.username(), PASSWORD, IP);
    }

    private RefreshToken row(String raw) throws Exception {
        return tokens.findByTokenHash(hash(raw)).orElseThrow();
    }

    private long countTokens(UUID accountId, boolean liveOnly) {
        return jdbc.sql("SELECT COUNT(*) FROM t_refresh_token WHERE account_id = :id"
                        + (liveOnly ? " AND revoked_at IS NULL" : ""))
                .param("id", accountId.toString()).query(Long.class).single();
    }

    private static String hash(String raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertCode(Runnable action, ApiErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static Object refreshOutcome(AuthService service, String raw) {
        try {
            return service.refresh(raw);
        } catch (BusinessException failure) {
            return failure.code();
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent authentication");
        }
    }
}
