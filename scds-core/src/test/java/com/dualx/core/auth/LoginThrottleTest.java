package com.dualx.core.auth;

import com.dualx.core.auth.LoginThrottle.Scope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录限流的边界行为。纯单测（不碰数据库），用可控时钟推进时间。
 */
class LoginThrottleTest {

    private static final String IP = "10.0.0.9";

    @Test
    void accountLockedOnFifthFailure_notBefore() {
        var throttle = new LoginThrottle(new MutableClock());

        for (int i = 1; i <= 4; i++) {
            throttle.recordFailure("u1", IP);
            assertThat(throttle.lockout("u1", IP)).as("第 %d 次失败后仍不该锁", i).isEmpty();
        }

        throttle.recordFailure("u1", IP);
        assertThat(throttle.lockout("u1", IP)).isPresent()
                .get()
                .extracting(LoginThrottle.Lockout::scope)
                .isEqualTo(Scope.ACCOUNT);
    }

    @Test
    void lockLastsFullFiveMinutes_evenWhenFailuresAreBunchedAtTheEnd() {
        var clock = new MutableClock();
        var throttle = new LoginThrottle(clock);

        // 5 次失败全在第 4 分钟涌入：若用"窗口内计数≥阈值"判定，
        // 最早的失败 1 分钟后滑出窗口就解锁了，锁定只剩 1 分钟。
        clock.advance(Duration.ofMinutes(4));
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("u2", IP);
        }

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertThat(throttle.lockout("u2", IP)).isPresent();
        clock.advance(Duration.ofMinutes(3).plusSeconds(58));
        assertThat(throttle.lockout("u2", IP)).isPresent();

        clock.advance(Duration.ofSeconds(1));
        assertThat(throttle.lockout("u2", IP)).isEmpty();
    }

    @Test
    void retryAfterCountsDownToLockExpiry() {
        var clock = new MutableClock();
        var throttle = new LoginThrottle(clock);

        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("u3", IP);
        }

        assertThat(throttle.lockout("u3", IP)).get()
                .extracting(LoginThrottle.Lockout::retryAfter)
                .isEqualTo(Duration.ofMinutes(5));

        clock.advance(Duration.ofMinutes(2));
        assertThat(throttle.lockout("u3", IP)).get()
                .extracting(LoginThrottle.Lockout::retryAfter)
                .isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void clientIpLocksOnlyAtThirtyFailures_acrossDifferentUsernames() {
        var clock = new MutableClock();
        var throttle = new LoginThrottle(clock);

        // 每个用户名只试 2 次（远低于账号阈值），但都来自同一出口 IP
        for (int i = 0; i < 15; i++) {
            throttle.recordFailure("victim" + i, IP);
            throttle.recordFailure("victim" + i, IP);
        }

        assertThat(throttle.lockout("someone-else", IP)).get()
                .extracting(LoginThrottle.Lockout::scope)
                .isEqualTo(Scope.CLIENT_IP);
        // 另一个 IP 上的新账号不受影响：锁的是 IP 维度，不是全局
        assertThat(throttle.lockout("someone-else", "10.0.0.10")).isEmpty();
    }

    @Test
    void successResetsBothDimensions() {
        var clock = new MutableClock();
        var throttle = new LoginThrottle(clock);

        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("u4", IP);
        }
        throttle.reset("u4", IP);

        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("u4", IP);
            assertThat(throttle.lockout("u4", IP)).isEmpty();
        }
    }

    @Test
    void failuresOutsideTheWindowDoNotAccumulate() {
        var clock = new MutableClock();
        var throttle = new LoginThrottle(clock);

        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("u5", IP);
            clock.advance(Duration.ofMinutes(2));
        }
        // 4 次失败跨越 6 分钟，任意时刻窗口内都不足 5 次
        throttle.recordFailure("u5", IP);
        assertThat(throttle.lockout("u5", IP)).isEmpty();
    }

    @Test
    void staleBucketsAreEvicted_soLongRunningProcessDoesNotLeakKeys() {
        var clock = new MutableClock();
        var throttle = new LoginThrottle(clock);

        for (int i = 0; i < 50; i++) {
            throttle.recordFailure("ghost" + i, "10.1.1." + i);
        }
        assertThat(throttle.trackedKeyCount()).isEqualTo(100);

        // 清理挂在失败路径上：所有旧失败滑出窗口后，再记一次失败即可触发回收。
        // 这次失败自身会留下两个键（账号 + IP），故清理后不是 0 而是 2。
        clock.advance(LoginThrottle.FAILURE_WINDOW.plusSeconds(1));
        throttle.recordFailure("survivor", "10.2.2.2");

        assertThat(throttle.trackedKeyCount()).isEqualTo(2);
    }

    @Test
    void deviceLocksOnFifthFailure_underItsOwnKeySpace() {
        var throttle = new LoginThrottle(new MutableClock());

        for (int i = 1; i <= 4; i++) {
            throttle.recordDeviceFailure(7, IP);
            assertThat(throttle.deviceLockout(7, IP)).as("第 %d 次失败后仍不该锁", i).isEmpty();
        }

        throttle.recordDeviceFailure(7, IP);
        assertThat(throttle.deviceLockout(7, IP)).isPresent()
                .get()
                .extracting(LoginThrottle.Lockout::scope)
                .isEqualTo(Scope.DEVICE);
        // 键前缀不同（account: / vest:），同名 subject 也互不串扰：设备失败不该锁住同名账号
        assertThat(throttle.lockout("7", IP)).isEmpty();
        // 一次设备失败只写自己那条链路的两个键，不在对方命名空间里留空壳
        assertThat(throttle.trackedKeyCount()).isEqualTo(2);
    }

    @Test
    void deviceFailuresStillCountTowardTheSharedClientIpDimension() {
        var throttle = new LoginThrottle(new MutableClock());

        for (int i = 0; i < 30; i++) {
            throttle.recordDeviceFailure(100 + i, IP);
        }

        // 每个马甲只失败 1 次（远低于设备阈值），锁只能来自共用的 IP 维度
        assertThat(throttle.deviceLockout(999, IP)).isPresent()
                .get()
                .extracting(LoginThrottle.Lockout::scope)
                .isEqualTo(Scope.CLIENT_IP);
        assertThat(throttle.deviceLockout(999, "10.0.0.11")).isEmpty();
    }

    @Test
    void deviceSuccessResetsBothDeviceAndIpDimensions() {
        var throttle = new LoginThrottle(new MutableClock());

        for (int i = 0; i < 4; i++) {
            throttle.recordDeviceFailure(7, IP);
        }
        throttle.resetDevice(7, IP);

        for (int i = 0; i < 4; i++) {
            throttle.recordDeviceFailure(7, IP);
            assertThat(throttle.deviceLockout(7, IP)).isEmpty();
        }
    }

    @Test
    void concurrentFailuresAtTheThresholdStillLockTheAccount() throws Exception {
        // 并发预算：看点不是「锁得住」，而是「一次更新都没丢」。5 个线程掐着同一个起跑线
        // 各记一次失败，若桶的读取-累加-判锁不是原子的，计数会停在 4，账号就漏锁了——
        // 这正是多终端同时打错口令时最危险的失效方式。单轮几乎必然通过，故重复多轮。
        for (int round = 0; round < 50; round++) {
            var throttle = new LoginThrottle(new MutableClock());
            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(LoginThrottle.ACCOUNT_MAX_FAILURES)) {
                var attempts = new ArrayList<Future<?>>();
                for (int i = 0; i < LoginThrottle.ACCOUNT_MAX_FAILURES; i++) {
                    attempts.add(executor.submit(() -> {
                        await(start);
                        throttle.recordFailure("racer", IP);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> attempt : attempts) {
                    attempt.get(15, TimeUnit.SECONDS);
                }
            }
            assertThat(throttle.lockout("racer", IP))
                    .as("第 %d 轮：%d 次并发失败已到阈值，账号必须被锁", round, LoginThrottle.ACCOUNT_MAX_FAILURES)
                    .isPresent()
                    .get()
                    .extracting(LoginThrottle.Lockout::scope)
                    .isEqualTo(Scope.ACCOUNT);
        }
    }

    @Test
    void concurrentFailuresAcrossUsernamesStillExhaustTheClientIpBudget() throws Exception {
        // IP 维度是 30 次的大预算，并发下同样不能少计：每个用户名只失败 1 次（远低于账号阈值），
        // 锁只可能来自 IP 口径——否则说明有更新丢失，攻击者靠并发就能把 IP 预算刷穿。
        var throttle = new LoginThrottle(new MutableClock());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(10)) {
            var attempts = new ArrayList<Future<?>>();
            for (int i = 0; i < LoginThrottle.CLIENT_IP_MAX_FAILURES; i++) {
                String username = "victim" + i;
                attempts.add(executor.submit(() -> {
                    await(start);
                    throttle.recordFailure(username, IP);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(15, TimeUnit.SECONDS);
            }
        }

        assertThat(throttle.lockout("someone-else", IP)).isPresent()
                .get()
                .extracting(LoginThrottle.Lockout::scope)
                .isEqualTo(Scope.CLIENT_IP);
        assertThat(throttle.lockout("someone-else", "10.0.0.12")).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发起跑线时被中断", interrupted);
        }
    }

    /** 可手动推进的时钟：限流逻辑全靠它，用真实时钟就没法在毫秒内验证 5 分钟的行为。 */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-21T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
