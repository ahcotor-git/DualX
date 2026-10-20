package com.dualx.core.repository;

import com.dualx.core.domain.AuditLog;
import com.dualx.core.domain.Account;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AuditLogRepositoryIT {

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private AccountRepository accounts;

    private static final Instant AT = Instant.parse("2026-10-20T08:00:00.000Z");

    @Test
    void insert_withAccount_roundTripsJsonDetail() {
        UUID accountId = UuidV7.generate();
        accounts.insert(new Account(accountId, "it_audit_a", null, "hash", true, null, null, null));

        assertThat(auditLogs.insert(new AuditLog(0L, accountId, "exercise.start", "EXERCISE",
                "ex-1", "{\"battleMode\":\"TRAIN\"}", "10.0.0.5", AT))).isEqualTo(1);

        List<AuditLog> found = auditLogs.findByAccountId(accountId, PageRequest.of(1, 10));
        assertThat(found).hasSize(1);
        AuditLog log = found.getFirst();
        assertThat(log.id()).isPositive();
        assertThat(log.action()).isEqualTo("exercise.start");
        assertThat(log.targetType()).isEqualTo("EXERCISE");
        assertThat(log.targetId()).isEqualTo("ex-1");
        assertThat(log.detailJson()).contains("TRAIN");
        assertThat(log.clientIp()).isEqualTo("10.0.0.5");
        assertThat(log.at()).isEqualTo(AT);
    }

    @Test
    void insert_systemAction_allowsNullAccountAndOptionalColumns() {
        // account_id / target_type / target_id / detail_json / client_ip 均可空
        assertThat(auditLogs.insert(new AuditLog(0L, null, "exercise.autoStop", null, null, null, null, AT)))
                .isEqualTo(1);
    }

    @Test
    void findByAccountId_ordersNewestFirst() {
        UUID accountId = UuidV7.generate();
        accounts.insert(new Account(accountId, "it_audit_b", null, "hash", true, null, null, null));
        auditLogs.insert(new AuditLog(0L, accountId, "first", null, null, null, null, AT));
        auditLogs.insert(new AuditLog(0L, accountId, "second", null, null, null, null, AT));
        auditLogs.insert(new AuditLog(0L, accountId, "third", null, null, null, null, AT));

        List<AuditLog> found = auditLogs.findByAccountId(accountId, PageRequest.of(1, 10));

        assertThat(found).extracting(AuditLog::action).containsExactly("third", "second", "first");
        assertThat(auditLogs.countByAccountId(accountId)).isEqualTo(3);
    }

    @Test
    void findByAccountId_respectsPaging() {
        UUID accountId = UuidV7.generate();
        accounts.insert(new Account(accountId, "it_audit_c", null, "hash", true, null, null, null));
        for (int i = 0; i < 5; i++) {
            auditLogs.insert(new AuditLog(0L, accountId, "act" + i, null, null, null, null, AT));
        }

        assertThat(auditLogs.findByAccountId(accountId, PageRequest.of(1, 2))).hasSize(2);
        assertThat(auditLogs.findByAccountId(accountId, PageRequest.of(3, 2))).hasSize(1);
        assertThat(auditLogs.findByAccountId(accountId, PageRequest.of(4, 2))).isEmpty();
    }
}
