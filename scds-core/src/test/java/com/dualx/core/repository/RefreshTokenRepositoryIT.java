package com.dualx.core.repository;

import com.dualx.core.auth.RefreshToken;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class RefreshTokenRepositoryIT {

    private static final Instant ISSUED = Instant.parse("2026-10-21T01:00:00.000Z");
    private static final Instant EXPIRES = Instant.parse("2026-11-04T01:00:00.000Z");
    private static final Instant REVOKED = Instant.parse("2026-10-21T02:00:00.000Z");

    @Autowired
    private RefreshTokenRepository tokens;

    /** 表上不建外键，故 account_id 用随机 UUID 即可，不需要先造账号。 */
    private static UUID accountId() {
        return UuidV7.generate();
    }

    private static RefreshToken token(UUID accountId, String hash) {
        return RefreshToken.issued(UuidV7.generate(), accountId, hash, ISSUED, EXPIRES);
    }

    @Test
    void insertThenFindById_roundTripsEveryColumn() {
        UUID account = accountId();
        RefreshToken drafted = token(account, "a".repeat(64));

        assertThat(tokens.insert(drafted)).isEqualTo(1);

        RefreshToken loaded = tokens.findById(drafted.id()).orElseThrow();
        assertThat(loaded.accountId()).isEqualTo(account);
        assertThat(loaded.tokenHash()).isEqualTo("a".repeat(64));
        assertThat(loaded.issuedAt()).isEqualTo(ISSUED);
        assertThat(loaded.expiresAt()).isEqualTo(EXPIRES);
        assertThat(loaded.revokedAt()).isNull();
        assertThat(loaded.replacedBy()).isNull();
        assertThat(loaded.usableAt(ISSUED)).isTrue();
    }

    @Test
    void findById_returnsEmptyWhenAbsent() {
        assertThat(tokens.findById(UuidV7.generate())).isEmpty();
    }

    @Test
    void duplicateTokenHash_isRejectedByUniqueIndex() {
        String hash = "b".repeat(64);
        tokens.insert(token(accountId(), hash));

        assertThatThrownBy(() -> tokens.insert(token(accountId(), hash)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void expiresNotAfterIssued_isRejectedByCheck() {
        // MySQL 的 CHECK 违约（错误码 3819）Spring 未能归类，落成 UncategorizedSQLException。
        RefreshToken bad = RefreshToken.issued(UuidV7.generate(), accountId(), "c".repeat(64), ISSUED, ISSUED);

        assertThatThrownBy(() -> tokens.insert(bad))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_refresh_token_expires");
    }

    @Test
    void revoke_marksOnlyUnrevokedRow_andRefusesSecondTime() {
        RefreshToken old = token(accountId(), "d".repeat(64));
        tokens.insert(old);
        UUID replacement = UuidV7.generate();

        assertThat(tokens.revoke(old.id(), replacement, REVOKED)).isEqualTo(1);
        RefreshToken revoked = tokens.findById(old.id()).orElseThrow();
        assertThat(revoked.revokedAt()).isEqualTo(REVOKED);
        assertThat(revoked.replacedBy()).isEqualTo(replacement);
        assertThat(revoked.usableAt(REVOKED)).isFalse();

        // 第二次换发必须失败：两端同时刷新时，只有一个能拿到 1，
        // 另一个据此判定重放，否则会留下两条并行链。
        assertThat(tokens.revoke(old.id(), UuidV7.generate(), REVOKED.plusSeconds(60))).isZero();
        assertThat(tokens.findById(old.id()).orElseThrow().revokedAt()).isEqualTo(REVOKED);
    }

    @Test
    void revoke_acceptsNullReplacement_forLogout() {
        RefreshToken token = token(accountId(), "e".repeat(64));
        tokens.insert(token);

        assertThat(tokens.revoke(token.id(), null, REVOKED)).isEqualTo(1);
        RefreshToken revoked = tokens.findById(token.id()).orElseThrow();
        assertThat(revoked.revokedAt()).isEqualTo(REVOKED);
        assertThat(revoked.replacedBy()).isNull();
    }

    @Test
    void revokeAllByAccountId_touchesOnlyThatAccountsLiveTokens() {
        UUID victim = accountId();
        UUID bystander = accountId();
        RefreshToken alreadyRevoked = token(victim, "f".repeat(64));
        tokens.insert(alreadyRevoked);
        tokens.revoke(alreadyRevoked.id(), null, REVOKED);
        RefreshToken liveFirst = token(victim, "0".repeat(64));
        tokens.insert(liveFirst);
        RefreshToken liveSecond = token(victim, "1".repeat(64));
        tokens.insert(liveSecond);
        RefreshToken other = token(bystander, "2".repeat(64));
        tokens.insert(other);

        Instant sweep = REVOKED.plusSeconds(300);
        assertThat(tokens.revokeAllByAccountId(victim, sweep)).isEqualTo(2);

        // 已撤销行的时间戳是链路证据，不能被这次处置覆盖
        assertThat(tokens.findById(alreadyRevoked.id()).orElseThrow().revokedAt()).isEqualTo(REVOKED);
        assertThat(tokens.findById(liveFirst.id()).orElseThrow().revokedAt()).isEqualTo(sweep);
        assertThat(tokens.findById(liveSecond.id()).orElseThrow().revokedAt()).isEqualTo(sweep);
        // 别人的令牌不受波及
        assertThat(tokens.findById(other.id()).orElseThrow().revokedAt()).isNull();
    }

    @Test
    void expiredRowIsNoLongerUsable_butStaysRevocable() {
        RefreshToken stale = RefreshToken.issued(UuidV7.generate(), accountId(), "3".repeat(64),
                ISSUED, ISSUED.plusSeconds(60));
        tokens.insert(stale);

        assertThat(tokens.findById(stale.id()).orElseThrow().usableAt(ISSUED.plusSeconds(60))).isFalse();
        assertThat(tokens.revoke(stale.id(), null, REVOKED)).isEqualTo(1);
    }
}
