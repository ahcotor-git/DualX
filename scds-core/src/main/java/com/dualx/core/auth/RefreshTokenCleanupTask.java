package com.dualx.core.auth;

import com.dualx.core.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * refreshToken 历史行清理（review R27）。
 *
 * <p><b>为什么需要它</b>：{@code t_refresh_token} 只增不减——每次登录与每次滚动刷新都插一行，
 * 退出与重放处置只把行置成「已撤销」而不删（撤销行本身就是重放检测的证据，见
 * {@link RefreshTokenRepository}）。没有这一步，行数会随使用时长无界增长，长期运行缓慢吃盘。</p>
 *
 * <p><b>删除线取「过期满保留期」。</b>判据用 {@code expires_at}：令牌一旦过期，行就失去作用
 * （即便被重放，也会在 {@code refresh} 里先撞上过期判定），此时再多留
 * {@code scds.auth.refresh.retention} 一段作为证据窗口即可回收。**未过期的撤销行照旧保留**——
 * 那是仍在有效期内的重放证据，提前删掉就看不见「同一令牌被两端换发」这类异常。</p>
 *
 * <p><b>何时触发</b>：{@code @EnableScheduling} 只在唯一入口 {@code ScdsNextApplication} 上开启，
 * 故本任务在应用内自动运行、在 {@code scds-core} 的测试上下文里不会自动跑（测试直接调
 * {@link #tick()}），与 {@code ExerciseCommandExpiryTask} 同一约定。</p>
 *
 * <p><b>第一拍晚一个周期</b>（{@code initialDelay}）：本任务与那两个任务不同，它的每一拍都会
 * <b>删除</b>行，积压时还是多批删除。若随启动立即开跑，就与「启动过程不改动库内数据」这条
 * 既有不变式（{@code ApplicationStartupIT} 以启动前后行数相等来守）直接冲突——现场重启不该
 * 顺带触发一轮删除。清理本身不紧急（要删的行早已过期），推迟一个周期没有代价。</p>
 */
@Component
@EnableConfigurationProperties(RefreshTokenCleanupTask.RefreshRetentionProperties.class)
public class RefreshTokenCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupTask.class);

    /** 扫描周期。清理是后台维护，慢一点无妨，也不必与登录路径争锁。 */
    private static final long PERIOD_MS = 3_600_000L;

    /** 单批删除上限，避免一次删太多长时间持锁。 */
    private static final int BATCH_LIMIT = 500;

    /** 单拍最多删多少批：首次启用清理存量时不至于把这一拍拖太久，其余留给下一拍。 */
    private static final int MAX_BATCHES_PER_TICK = 10;

    /** 库内统一存 UTC 挂钟值（V1 迁移文件头第 2 条）。 */
    private static final Clock CLOCK = Clock.systemUTC();

    private final RefreshTokenRepository tokens;
    private final RefreshRetentionProperties properties;

    public RefreshTokenCleanupTask(RefreshTokenRepository tokens, RefreshRetentionProperties properties) {
        this.tokens = tokens;
        this.properties = properties;
    }

    /** 删除过期已超过保留期的行。分批做，直到清空或达到单拍上限。 */
    @Scheduled(fixedDelay = PERIOD_MS, initialDelay = PERIOD_MS)
    @Transactional
    public void tick() {
        Instant cutoff = CLOCK.instant().minus(properties.retention());
        int deleted = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
            int rows = tokens.deleteExpiredBefore(cutoff, BATCH_LIMIT);
            deleted += rows;
            if (rows < BATCH_LIMIT) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("refreshToken 清理：删除 {} 条过期超过 {} 的历史行。", deleted, properties.retention());
        }
    }

    /**
     * {@code scds.auth.refresh.*} 的绑定与校验。
     *
     * <p>显式配成 0 或负数让**启动失败**：保留期本身就是「令牌过期后再留一段证据窗口」，
     * 0 会让撤销行刚过期就被抹掉，而现场从配置上看不出异常（与 {@code scds.command.sent-ttl}
     * 同一取向）。漏配（null）走默认值。</p>
     */
    @ConfigurationProperties("scds.auth.refresh")
    public record RefreshRetentionProperties(@DefaultValue("7d") Duration retention) {

        public RefreshRetentionProperties {
            if (retention == null || retention.isZero() || retention.isNegative()) {
                throw new IllegalArgumentException("scds.auth.refresh.retention 必须为正的时长，实际 " + retention);
            }
        }
    }
}
