package com.dualx.core.auth;

import com.dualx.core.domain.Vest;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.DeviceTokenClaims;
import com.dualx.core.port.TokenSigner;
import com.dualx.core.repository.VestDeviceSessionRepository;
import com.dualx.core.repository.VestRepository;
import com.dualx.core.support.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * 设备（马甲终端 App）认证：绑定密钥校验 + SID 单端在线（设计 03 §4.9，决策 04 D03）。
 *
 * <p><b>不依赖数据库事务。</b>整条链路只落一条 UPDATE——{@link VestDeviceSessionRepository#openSession}
 * 同时写入 {@code device_session_sid} 与 {@code bound = 1}，单语句本身即原子。为一条语句
 * 套一层事务，只会引入「事务内抛异常要不要回滚」的无谓分支。</p>
 *
 * <p><b>不引入缓存</b>（D03-5）：{@code device_session_sid} 库即权威，读写都走它，
 * 于是「重复登录顶掉旧令牌」只是写入新 sid 的天然结果。</p>
 *
 * <p>失败一律收敛为 {@link ApiErrorCode#AUTH_DEVICE_NOT_BOUND}：马甲不存在、已停用、
 * 未设绑定密钥、密钥不符，对终端而言都是「这个编号 + 密钥不成立」。区分开只会给攻击者
 * 提供枚举价值（与账号登录「口令错和账号不存在同码」同一思路，设计 03 §4.10）。</p>
 */
@Service
public class DeviceAuthService {

    private static final Logger log = LoggerFactory.getLogger(DeviceAuthService.class);

    /** 库内统一存 UTC 挂钟值（V1 迁移文件头第 2 条）。 */
    private static final Clock CLOCK = Clock.systemUTC();

    private final VestRepository vests;
    private final VestDeviceSessionRepository sessions;
    private final LoginThrottle throttle;
    private final PasswordEncoder passwordEncoder;
    private final TokenSigner tokenSigner;

    public DeviceAuthService(VestRepository vests,
                             VestDeviceSessionRepository sessions,
                             LoginThrottle throttle,
                             PasswordEncoder passwordEncoder,
                             TokenSigner tokenSigner) {
        this.vests = vests;
        this.sessions = sessions;
        this.throttle = throttle;
        this.passwordEncoder = passwordEncoder;
        this.tokenSigner = tokenSigner;
    }

    /**
     * 单兵终端登录：马甲编号 + 绑定密钥换取设备令牌。
     *
     * @param clientIp 客户端 IP，仅用于限流口径（与账号登录共用 IP 维度，D03-6）
     */
    public IssuedDeviceToken login(int vestNum, String secret, String clientIp) {
        requireSigning();
        // 与账号登录同理：锁定期判定必须在密钥校验之前调用，否则「这次密钥正好对了」即可解锁
        throttle.deviceLockout(vestNum, clientIp).ifPresent(lock -> {
            log.warn("设备登录被限流：vestNum={} 口径={} 剩余={}s",
                    vestNum, lock.scope(), lock.retryAfter().toSeconds());
            throw BusinessException.rateLimited(lock.retryAfter());
        });

        Vest vest = vests.findByVestNum(vestNum).orElse(null);
        if (vest == null || !vest.enabled()
                || vest.bindSecretHash() == null || vest.bindSecretHash().isBlank()
                || !passwordEncoder.matches(secret, vest.bindSecretHash())) {
            throttle.recordDeviceFailure(vestNum, clientIp);
            throw notBound();
        }

        UUID sessionId = UuidV7.generate();
        if (sessions.openSession(vestNum, sessionId.toString(), CLOCK.instant()) == 0) {
            // 读与写之间台账行被删（或编号超出 CHECK 约束范围）：对外与「密钥不符」同样处理
            throttle.recordDeviceFailure(vestNum, clientIp);
            throw notBound();
        }
        throttle.resetDevice(vestNum, clientIp);

        log.info("设备登录成功：vestNum={}", vestNum);
        String deviceToken = tokenSigner.signDeviceToken(new DeviceTokenClaims(vestNum, sessionId));
        return new IssuedDeviceToken(deviceToken, Math.toIntExact(tokenSigner.deviceTokenTtl().toSeconds()));
    }

    /**
     * 校验这份设备身份仍是该马甲当前有效的那一个会话（SID 单端在线，设计 03 §4.9）。
     *
     * <p>验签只证明令牌是本站签发的，不证明它还属于当前登录的那台设备。每次设备登录都会写入
     * 新的 {@code device_session_sid}，换绑 / 退役会把该列置 NULL，于是「同一马甲换了设备或
     * 退了役，而手里的旧令牌在 12h 有效期内仍可用」这件事必须在这里挡掉。设计 03b §4 的
     * 口径是：单兵自身可见性与 SID 业务检查不因验签通过而省略。</p>
     *
     * <p>不等一律按 {@link ApiErrorCode#AUTH_TOKEN_INVALID}（401）拒绝，而不是 403：设备端应
     * 重新走一次 {@code device/login}，403 会把它引向「换令牌」的错误方向。</p>
     */
    public void requireActiveSession(DeviceTokenClaims claims) {
        String current = sessions.findSessionSid(claims.vestNum()).orElse(null);
        if (current == null || !current.equals(claims.sessionId().toString())) {
            log.debug("设备会话已失效：vestNum={}", claims.vestNum());
            throw new BusinessException(ApiErrorCode.AUTH_TOKEN_INVALID, "设备令牌已失效，请重新登录");
        }
    }

    private void requireSigning() {
        if (!tokenSigner.enabled()) {
            throw new IllegalStateException("Token signing is disabled");
        }
    }

    private static BusinessException notBound() {
        return new BusinessException(ApiErrorCode.AUTH_DEVICE_NOT_BOUND, "马甲编号或绑定密钥不正确");
    }

    /**
     * 一次成功的设备登录结果（契约 {@code domain.yaml → DeviceTokenPair}）。
     *
     * @param deviceToken 设备令牌（{@code aud = scds-device}），其 {@code sid} 此刻已落库
     * @param expiresIn   令牌剩余有效秒数
     */
    public record IssuedDeviceToken(String deviceToken, int expiresIn) {
    }
}
