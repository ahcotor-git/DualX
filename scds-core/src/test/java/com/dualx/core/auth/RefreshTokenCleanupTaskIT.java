package com.dualx.core.auth;

import com.dualx.core.repository.RefreshTokenRepository;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * refreshToken 历史行清理的验收（review R27）。
 *
 * <p>测试上下文不带 {@code @EnableScheduling}，任务不会自己跑，故手工调
 * {@link RefreshTokenCleanupTask#tick()} 驱动一拍（与 {@code ExerciseCommandExpiryTaskIT} 同一约定）。
 * 「过期」靠回填 {@code expires_at} 制造，不依赖真实等待。</p>
 *
 * <p>本类全程跑在测试事务里：全库性的删除随事务回滚，不污染库；断言一律限定到本用例新造的行。</p>
 */
@SpringBootTest
@Transactional
class RefreshTokenCleanupTaskIT {

    @Autowired
    private RefreshTokenCleanupTask cleanupTask;

    @Autowired
    private RefreshTokenRepository tokens;

    @Autowired
    private RefreshTokenCleanupTask.RefreshRetentionProperties properties;

    @Test
    void defaultRetention_isSevenDays() {
        assertThat(properties.retention()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void tick_deletesRowsExpiredBeyondRetention_andKeepsTheRest() {
        Instant now = Instant.now();
        Duration retention = properties.retention();

        // 过期已超过保留期 → 应删
        UUID ancient = insert(now.minus(retention).minus(Duration.ofDays(30)));
        // 刚过期、仍在保留期内 → 留（撤销行的重放证据窗口）
        UUID justExpired = insert(now.minus(Duration.ofHours(1)));
        // 仍在有效期内 → 留
        UUID live = insert(now.plus(Duration.ofDays(13)));

        cleanupTask.tick();

        assertThat(tokens.findById(ancient)).isEmpty();
        assertThat(tokens.findById(justExpired)).isPresent();
        assertThat(tokens.findById(live)).isPresent();
    }

    @Test
    void tick_keepsRevokedRowsThatHaveNotYetExpired() {
        Instant now = Instant.now();

        // 已撤销但仍在有效期内：判据是 expires_at 而不是 revoked_at，这行是未到期令牌的重放证据，必须留
        UUID revokedButLive = UuidV7.generate();
        tokens.insert(RefreshToken.issued(revokedButLive, UuidV7.generate(), hash(),
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(13))));
        tokens.revoke(revokedButLive, null, now);

        cleanupTask.tick();

        assertThat(tokens.findById(revokedButLive)).isPresent();
    }

    /** 造一条 issued_at 早于 expires_at 的行（库内 CHECK 要求），返回主键。 */
    private UUID insert(Instant expiresAt) {
        UUID id = UuidV7.generate();
        tokens.insert(RefreshToken.issued(id, UuidV7.generate(), hash(),
                expiresAt.minus(Duration.ofDays(14)), expiresAt));
        return id;
    }

    /** token_hash 是 CHAR(64)：用两个无连字符 UUID 拼够 64 位十六进制，且每次唯一。 */
    private static String hash() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }
}
