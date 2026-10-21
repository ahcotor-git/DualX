package com.dualx.core.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 登录失败限流（设计 03 §4.10）。零新依赖的进程内实现，不引 Caffeine、不引 Redis。
 *
 * <p><b>模型：滑动窗口计数 + 固定时长锁。</b>
 * 失败时间戳按"窗口"修剪，窗口内失败数达到阈值即置一个「锁到某时刻」的标记。
 * 两者刻意分开：若只用"窗口内计数 ≥ 阈值"来判定锁定，5 次失败集中在第 4 分钟发生时，
 * 最早的失败 1 分钟后滑出窗口就解锁了，实际锁定只有 1 分钟——与设计的"锁 5 分钟"不符。</p>
 *
 * <p><b>锁定期内不再计入失败。</b>锁定期内的请求在口令校验之前就被拒了，把它算作
 * "口令失败"会让锁在持续攻击下无限续期、永不解除；解除靠的是锁定时刻自然到期。</p>
 *
 * <p><b>阈值</b>：账号 5 次 / 5 分钟，客户端 IP 30 次 / 5 分钟。IP 阈值宽松得多，
 * 因为现场常有多台终端在同一 NAT 出口后面，按账号阈值卡 IP 会把正常登录连坐。</p>
 *
 * <p><b>三个口径分属两条独立链路</b>：账号登录用 {@code ACCOUNT + CLIENT_IP}
 * （{@link #lockout(String, String)} 一族），设备登录用 {@code DEVICE + CLIENT_IP}
 * （{@link #deviceLockout(int, String)} 一族）。键前缀不同（{@code account:} / {@code vest:}），
 * 且一次失败只写本条链路的维度——否则设备登录会在账号命名空间里留下永不查询的键。</p>
 *
 * <p><b>作用域是单个进程、重启即清零。</b>本项目"每客户独立实例"，单机承载全部登录，
 * 故不需要跨实例共享计数；代价是重启会放掉正在锁定的账号——现场重启本就伴随人工
 * 在场，可接受，为此引入中间件则得不偿失。</p>
 *
 * <p>线程安全：登录流量极低，直接对整个实例加锁，换取实现上的零心智负担。</p>
 */
@Component
public class LoginThrottle {

    /** 账号维度：5 次失败即锁。 */
    public static final int ACCOUNT_MAX_FAILURES = 5;

    /** 客户端 IP 维度：30 次失败即锁。 */
    public static final int CLIENT_IP_MAX_FAILURES = 30;

    /** 失败计数的滑动窗口长度。 */
    public static final Duration FAILURE_WINDOW = Duration.ofMinutes(5);

    /** 达到阈值后的锁定时长。 */
    public static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    private static final String ACCOUNT_KEY_PREFIX = "account:";
    private static final String DEVICE_KEY_PREFIX = "vest:";
    private static final String IP_KEY_PREFIX = "ip:";

    /** 账号登录适用的口径：账号自身 + 客户端 IP。 */
    private static final List<Scope> ACCOUNT_DIMENSIONS = List.of(Scope.ACCOUNT, Scope.CLIENT_IP);

    /** 设备登录适用的口径：设备自身 + 客户端 IP（IP 维度与账号登录共用，D03-6）。 */
    private static final List<Scope> DEVICE_DIMENSIONS = List.of(Scope.DEVICE, Scope.CLIENT_IP);

    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();

    /** Spring 用无参构造（多构造器且未标注时，Spring 选无参那个）。 */
    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    /** 供测试注入可控时钟；生产路径不走这里。 */
    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /**
     * 当前是否处于锁定期。**必须在校验口令之前调用**——锁定期不因"这次口令正好对了"
     * 而放行，否则攻击者撞对一次即可解锁。
     *
     * @return 空表示未锁定；非空给出锁的口径与剩余时长（后者用于 429 的 {@code Retry-After}）
     */
    public Optional<Lockout> lockout(String username, String clientIp) {
        return lockout(ACCOUNT_DIMENSIONS, username, clientIp);
    }

    /** 设备口径的锁定判定，语义与 {@link #lockout} 相同，键为 {@code vest:<num>}。 */
    public Optional<Lockout> deviceLockout(int vestNum, String clientIp) {
        return lockout(DEVICE_DIMENSIONS, Integer.toString(vestNum), clientIp);
    }

    private Optional<Lockout> lockout(List<Scope> dimensions, String subject, String clientIp) {
        Instant now = clock.instant();
        synchronized (this) {
            Lockout found = null;
            for (Scope scope : dimensions) {
                Bucket bucket = buckets.get(scope.key(subject, clientIp));
                if (bucket == null) {
                    continue;
                }
                bucket.prune(now);
                if (found == null && bucket.isLocked(now)) {
                    found = new Lockout(scope, Duration.between(now, bucket.lockedUntil));
                }
            }
            return Optional.ofNullable(found);
        }
    }

    /** 记录一次**口令校验失败**。锁定期内的请求不应调用本方法（见类注释）。 */
    public void recordFailure(String username, String clientIp) {
        recordFailure(ACCOUNT_DIMENSIONS, username, clientIp);
    }

    /** 记录一次**设备绑定密钥校验失败**。口径见 {@link #deviceLockout}。 */
    public void recordDeviceFailure(int vestNum, String clientIp) {
        recordFailure(DEVICE_DIMENSIONS, Integer.toString(vestNum), clientIp);
    }

    private void recordFailure(List<Scope> dimensions, String subject, String clientIp) {
        Instant now = clock.instant();
        synchronized (this) {
            for (Scope scope : dimensions) {
                buckets.computeIfAbsent(scope.key(subject, clientIp), key -> new Bucket())
                        .recordFailure(now, scope.maxFailures());
            }
            evictStale(now);
        }
    }

    /**
     * 清理已无内容的键。否则每个被尝试过的用户名/IP 都会留下一个空壳，长期运行会缓慢吃内存。
     *
     * <p>放在失败路径上而非查询路径上：只有失败会新增键，于是"清理频率"天然被限流封顶
     * （单个 IP 每 5 分钟至多记 30 次失败，锁定期内更是一次都不记）。反过来说，
     * 若放在查询路径上，就等于给每次正常登录都加一遍全表扫描。</p>
     */
    private void evictStale(Instant now) {
        buckets.values().removeIf(bucket -> {
            bucket.prune(now);
            return bucket.isEmpty();
        });
    }

    /** 登录成功：两个口径一并清零（仅在未锁定状态下会走到这里）。 */
    public void reset(String username, String clientIp) {
        reset(ACCOUNT_DIMENSIONS, username, clientIp);
    }

    /** 设备登录成功：设备与 IP 两个口径一并清零。 */
    public void resetDevice(int vestNum, String clientIp) {
        reset(DEVICE_DIMENSIONS, Integer.toString(vestNum), clientIp);
    }

    private void reset(List<Scope> dimensions, String subject, String clientIp) {
        synchronized (this) {
            for (Scope scope : dimensions) {
                buckets.remove(scope.key(subject, clientIp));
            }
        }
    }

    /** 当前仍在跟踪的键数量。仅用于测试观察内存边界，生产代码不要调用。 */
    int trackedKeyCount() {
        synchronized (this) {
            return buckets.size();
        }
    }

    /** 锁的口径。 */
    public enum Scope {
        ACCOUNT,
        /** 设备（马甲终端）维度：键为 {@code vest:<vestNum>}，阈值与账号同（D03-6）。 */
        DEVICE,
        CLIENT_IP;

        private String key(String subject, String clientIp) {
            return switch (this) {
                case ACCOUNT -> ACCOUNT_KEY_PREFIX + subject;
                case DEVICE -> DEVICE_KEY_PREFIX + subject;
                case CLIENT_IP -> IP_KEY_PREFIX + clientIp;
            };
        }

        private int maxFailures() {
            return switch (this) {
                case ACCOUNT, DEVICE -> ACCOUNT_MAX_FAILURES;
                case CLIENT_IP -> CLIENT_IP_MAX_FAILURES;
            };
        }
    }

    /**
     * 一次锁定判定结果。
     *
     * @param scope      触发锁定的口径，用于日志（现场要能一眼看出是被账号锁还是被 IP 锁）
     * @param retryAfter 剩余锁定时间，写入 429 的 {@code Retry-After}
     */
    public record Lockout(Scope scope, Duration retryAfter) {
    }

    /** 单键的失败窗口与锁定时刻。仅在持有 {@link LoginThrottle} 实例锁时访问。 */
    private static final class Bucket {

        private final Deque<Instant> failures = new ArrayDeque<>();
        private Instant lockedUntil;

        /** 丢弃滑出窗口的失败记录，并清掉已到期的锁。 */
        void prune(Instant now) {
            Instant cutoff = now.minus(FAILURE_WINDOW);
            while (!failures.isEmpty() && !failures.peekFirst().isAfter(cutoff)) {
                failures.pollFirst();
            }
            if (lockedUntil != null && !lockedUntil.isAfter(now)) {
                lockedUntil = null;
            }
        }

        void recordFailure(Instant now, int maxFailures) {
            prune(now);
            failures.addLast(now);
            if (failures.size() >= maxFailures) {
                lockedUntil = now.plus(LOCK_DURATION);
            }
        }

        boolean isLocked(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }

        boolean isEmpty() {
            return failures.isEmpty() && lockedUntil == null;
        }
    }
}
