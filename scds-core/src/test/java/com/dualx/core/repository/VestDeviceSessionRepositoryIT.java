package com.dualx.core.repository;

import com.dualx.core.domain.Vest;
import com.dualx.protocol.Team;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class VestDeviceSessionRepositoryIT {

    private static final Instant LOGIN_AT = Instant.parse("2026-10-21T03:20:00.750Z");

    @Autowired
    private VestDeviceSessionRepository sessions;

    @Autowired
    private VestRepository vests;

    /** device_login_at 目前只写不读（诊断用），故这里直接用 JdbcClient 校验它确实落了库。 */
    @Autowired
    private JdbcClient jdbc;

    private static Vest vest(int vestNum) {
        return new Vest(vestNum, null, Team.RED, null, null, null, null, false, true, null, null, null, null);
    }

    private Instant storedLoginAt(int vestNum) {
        LocalDateTime raw = jdbc.sql("SELECT device_login_at FROM t_vest WHERE vest_num = :vestNum")
                .param("vestNum", vestNum)
                .query(LocalDateTime.class)
                .single();
        return raw == null ? null : raw.toInstant(ZoneOffset.UTC);
    }

    @Test
    void unknownVestHasNoSession_andCannotBeOpened() {
        assertThat(sessions.findSessionSid(9401)).isEmpty();
        assertThat(sessions.openSession(9401, "sid-x", LOGIN_AT)).isZero();
    }

    @Test
    void openSession_thenFindReturnsSid_andStampsLoginTime() {
        vests.insert(vest(9402));

        assertThat(sessions.openSession(9402, "sid-a", LOGIN_AT)).isEqualTo(1);

        assertThat(sessions.findSessionSid(9402)).contains("sid-a");
        assertThat(storedLoginAt(9402)).isEqualTo(LOGIN_AT);
    }

    @Test
    void secondLoginReplacesSid_soOldTokenIsKnockedOut() {
        vests.insert(vest(9403));
        sessions.openSession(9403, "sid-old", LOGIN_AT);

        Instant later = LOGIN_AT.plusSeconds(600);
        assertThat(sessions.openSession(9403, "sid-new", later)).isEqualTo(1);

        // 单端在线就靠这一条：旧令牌的 sid 与库中不再相等，下一次请求即被拒。
        assertThat(sessions.findSessionSid(9403)).hasValue("sid-new");
        assertThat(sessions.findSessionSid(9403)).isNotEqualTo(Optional.of("sid-old"));
        assertThat(storedLoginAt(9403)).isEqualTo(later);
    }

    @Test
    void clearSession_removesSidButKeepsLastLoginTime() {
        vests.insert(vest(9404));
        sessions.openSession(9404, "sid-a", LOGIN_AT);

        assertThat(sessions.clearSession(9404)).isEqualTo(1);

        assertThat(sessions.findSessionSid(9404)).isEmpty();
        // 登出后仍保留最后登录时刻，否则丢掉排障依据
        assertThat(storedLoginAt(9404)).isEqualTo(LOGIN_AT);
    }

    @Test
    void clearingAnAlreadyEmptySessionIsHarmless() {
        vests.insert(vest(9405));

        assertThat(sessions.clearSession(9405)).isEqualTo(1);
        assertThat(sessions.findSessionSid(9405)).isEmpty();
    }

    @Test
    void sessionsAreIsolatedPerVest() {
        vests.insert(vest(9406));
        vests.insert(vest(9407));

        sessions.openSession(9406, "sid-a", LOGIN_AT);

        assertThat(sessions.findSessionSid(9406)).contains("sid-a");
        assertThat(sessions.findSessionSid(9407)).isEmpty();
    }
}
