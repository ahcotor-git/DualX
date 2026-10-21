package com.dualx.core.auth;

import com.dualx.core.domain.Vest;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.DeviceTokenClaims;
import com.dualx.core.port.TokenSigner;
import com.dualx.core.repository.VestDeviceSessionRepository;
import com.dualx.core.repository.VestRepository;
import com.dualx.protocol.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备登录的真实数据库行为（决策 04 D03）。
 *
 * <p>无测试外层事务：要验证的是真提交——{@code openSession} 同一语句里落 sid 与 {@code bound}，
 * 以及失败路径一个字节都不写库。只清理本用例创建的马甲行，不触碰本地已有台账。</p>
 */
@SpringBootTest
class DeviceAuthServiceIT {

    private static final String SECRET = "device-integration-secret";
    private static final String IP = "127.0.0.3";

    /** 与 VestDeviceSessionRepositoryIT 的 94xx 段错开，避免并行/历史残留互相踩。 */
    private static final int FIRST_VEST_NUM = 62001;

    @Autowired private VestRepository vests;
    @Autowired private VestDeviceSessionRepository sessions;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JdbcClient jdbc;

    private final List<Integer> createdVests = new ArrayList<>();
    private TokenSigner signer;
    private DeviceAuthService deviceAuth;
    private int nextVestNum = FIRST_VEST_NUM;

    @BeforeEach
    void setUp() {
        signer = mock(TokenSigner.class);
        when(signer.enabled()).thenReturn(true);
        when(signer.deviceTokenTtl()).thenReturn(Duration.ofHours(12));
        when(signer.signDeviceToken(any())).thenAnswer(call ->
                "device-" + call.getArgument(0, DeviceTokenClaims.class).sessionId());
        deviceAuth = new DeviceAuthService(vests, sessions, new LoginThrottle(), passwords, signer);
    }

    @AfterEach
    void cleanUp() {
        for (int vestNum : createdVests) {
            jdbc.sql("DELETE FROM t_vest WHERE vest_num = :vestNum").param("vestNum", vestNum).update();
        }
    }

    @Test
    void boundSecretIssuesToken_andStampsSessionAndBoundInOneGo() {
        int vestNum = seedBoundVest();

        DeviceAuthService.IssuedDeviceToken issued = deviceAuth.login(vestNum, SECRET, IP);

        String sid = sessions.findSessionSid(vestNum).orElseThrow();
        assertThat(issued.deviceToken()).isEqualTo("device-" + sid);
        assertThat(issued.expiresIn()).isEqualTo(43200);
        // bound 与 sid 由同一条 UPDATE 落库：登录成功即「已被认领」（D03-3）
        assertThat(vests.findByVestNum(vestNum).orElseThrow().bound()).isTrue();
    }

    @Test
    void secondLoginReplacesSid_soTheEarlierTokenIsKnockedOut() {
        int vestNum = seedBoundVest();

        deviceAuth.login(vestNum, SECRET, IP);
        String first = sessions.findSessionSid(vestNum).orElseThrow();
        deviceAuth.login(vestNum, SECRET, IP);

        // 「单端在线」的全部实现：旧令牌的 sid 与库中不再相等，下一次请求即被拒
        assertThat(sessions.findSessionSid(vestNum)).isPresent().get().isNotEqualTo(first);
    }

    @Test
    void missingDisabledUnboundAndWrongSecretShareTheSameFailure() {
        assertCode(() -> deviceAuth.login(61999, SECRET, IP), ApiErrorCode.AUTH_DEVICE_NOT_BOUND);

        int disabled = seedVest(false, passwords.encode(SECRET));
        assertCode(() -> deviceAuth.login(disabled, SECRET, IP), ApiErrorCode.AUTH_DEVICE_NOT_BOUND);

        int unbound = seedVest(true, null);
        assertCode(() -> deviceAuth.login(unbound, SECRET, IP), ApiErrorCode.AUTH_DEVICE_NOT_BOUND);

        int blankHash = seedVest(true, "");
        assertCode(() -> deviceAuth.login(blankHash, SECRET, IP), ApiErrorCode.AUTH_DEVICE_NOT_BOUND);

        int bound = seedBoundVest();
        assertCode(() -> deviceAuth.login(bound, "wrong-secret", IP), ApiErrorCode.AUTH_DEVICE_NOT_BOUND);

        assertThat(sessions.findSessionSid(disabled)).isEmpty();
        assertThat(sessions.findSessionSid(unbound)).isEmpty();
        assertThat(sessions.findSessionSid(blankHash)).isEmpty();
        assertThat(sessions.findSessionSid(bound)).isEmpty();
        // 失败路径不改 bound：认领只发生在密钥校验通过之后
        assertThat(vests.findByVestNum(bound).orElseThrow().bound()).isFalse();
        verify(signer, never()).signDeviceToken(any());
    }

    @Test
    void sixthAttemptStaysLockedEvenWithTheCorrectSecret() {
        int vestNum = seedBoundVest();
        for (int i = 0; i < 5; i++) {
            assertCode(() -> deviceAuth.login(vestNum, "wrong-secret", IP), ApiErrorCode.AUTH_DEVICE_NOT_BOUND);
        }

        assertThatThrownBy(() -> deviceAuth.login(vestNum, SECRET, IP))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ApiErrorCode.RATE_LIMITED);
                    assertThat(failure.retryAfter()).isPositive();
                });
        assertThat(sessions.findSessionSid(vestNum)).isEmpty();
        verify(signer, never()).signDeviceToken(any());
    }

    @Test
    void successfulLoginClearsTheDeviceFailureCounter() {
        int vestNum = seedBoundVest();
        for (int i = 0; i < 4; i++) {
            assertCode(() -> deviceAuth.login(vestNum, "wrong-secret", IP), ApiErrorCode.AUTH_DEVICE_NOT_BOUND);
        }

        deviceAuth.login(vestNum, SECRET, IP);

        // 计数已清零：再攒 4 次失败也不会锁
        for (int i = 0; i < 4; i++) {
            assertCode(() -> deviceAuth.login(vestNum, "wrong-secret", IP), ApiErrorCode.AUTH_DEVICE_NOT_BOUND);
        }
        assertThat(deviceAuth.login(vestNum, SECRET, IP).deviceToken()).isNotBlank();
    }

    @Test
    void signingDisabledFailsBeforeAnyMutation() {
        int vestNum = seedBoundVest();
        when(signer.enabled()).thenReturn(false);

        assertThatThrownBy(() -> deviceAuth.login(vestNum, SECRET, IP))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sessions.findSessionSid(vestNum)).isEmpty();
        assertThat(vests.findByVestNum(vestNum).orElseThrow().bound()).isFalse();
    }

    /** {@code bindSecretHash} 传明文（调用方决定是否已散列），便于显式构造 null / 空串等边界。 */
    private int seedVest(boolean enabled, String bindSecretHash) {
        int vestNum = nextVestNum++;
        createdVests.add(vestNum);
        vests.insert(new Vest(vestNum, "设备 IT " + vestNum, Team.RED, null, null, null,
                bindSecretHash, false, enabled, null, null, null, null));
        return vestNum;
    }

    private int seedBoundVest() {
        return seedVest(true, passwords.encode(SECRET));
    }

    private static void assertCode(Runnable action, ApiErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
