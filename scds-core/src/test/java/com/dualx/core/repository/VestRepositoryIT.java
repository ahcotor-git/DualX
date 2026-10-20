package com.dualx.core.repository;

import com.dualx.core.domain.Vest;
import com.dualx.core.support.PageRequest;
import com.dualx.protocol.Team;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class VestRepositoryIT {

    @Autowired
    private VestRepository vests;

    private static final Instant SEEN = Instant.parse("2026-10-20T09:15:00.250Z");

    private static Vest vest(int vestNum, Team team) {
        return new Vest(vestNum, null, team, null, null, null, null, false, true, null, null, null, null);
    }

    @Test
    void insertThenFindByVestNum_roundTripsEveryColumn() {
        Vest drafted = new Vest(9001, "张三", Team.RED, "SIM-0001", "V2", "1.0.3",
                "$2a$10$abcdefghijklmnopqrstuv", true, true, SEEN, "一营一连", null, null);

        assertThat(vests.insert(drafted)).isEqualTo(1);

        Vest vest = vests.findByVestNum(9001).orElseThrow();
        assertThat(vest.vestNum()).isEqualTo(9001);
        assertThat(vest.name()).isEqualTo("张三");
        assertThat(vest.team()).isEqualTo(Team.RED);
        assertThat(vest.simNo()).isEqualTo("SIM-0001");
        assertThat(vest.model()).isEqualTo("V2");
        assertThat(vest.firmwareVersion()).isEqualTo("1.0.3");
        assertThat(vest.bindSecretHash()).isEqualTo("$2a$10$abcdefghijklmnopqrstuv");
        assertThat(vest.bound()).isTrue();
        assertThat(vest.enabled()).isTrue();
        // datetime(3) 只到毫秒，用毫秒精度比较
        assertThat(vest.lastSeenAt()).isEqualTo(SEEN);
        assertThat(vest.remark()).isEqualTo("一营一连");
        assertThat(vest.createdAt()).isNotNull();
        assertThat(vest.updatedAt()).isNotNull();
    }

    @Test
    void optionalColumns_mayAllBeNull() {
        assertThat(vests.insert(vest(9002, null))).isEqualTo(1);

        Vest loaded = vests.findByVestNum(9002).orElseThrow();
        assertThat(loaded.name()).isNull();
        assertThat(loaded.team()).isNull();
        assertThat(loaded.simNo()).isNull();
        assertThat(loaded.model()).isNull();
        assertThat(loaded.firmwareVersion()).isNull();
        assertThat(loaded.bindSecretHash()).isNull();
        assertThat(loaded.lastSeenAt()).isNull();
        assertThat(loaded.remark()).isNull();
        assertThat(loaded.bound()).isFalse();
    }

    @Test
    void findByVestNum_returnsEmptyWhenAbsent() {
        assertThat(vests.findByVestNum(9999)).isEmpty();
        assertThat(vests.existsByVestNum(9999)).isFalse();
    }

    @Test
    void duplicateVestNum_isRejectedByPrimaryKey() {
        vests.insert(vest(9003, Team.BLUE));

        assertThatThrownBy(() -> vests.insert(vest(9003, Team.RED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void vestNumAboveContractCeiling_isRejectedByCheck() {
        // 契约 VestDevice.vestNum 上限 65532；65533~65535 是杀伤物编号，不是马甲。
        // MySQL 的 CHECK 违约（错误码 3819）Spring 未能归类，落成 UncategorizedSQLException。
        assertThatThrownBy(() -> vests.insert(vest(65533, null)))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_vest_num");
    }

    @Test
    void update_changesMutableColumnsOnly() {
        vests.insert(vest(9004, Team.RED));

        Instant seen = Instant.parse("2026-10-20T10:00:00.000Z");
        Vest changed = new Vest(9004, "李四", Team.YELLOW, "SIM-0004", "V3", "2.0.0",
                "hash", true, false, seen, "调拨", null, null);

        assertThat(vests.update(changed)).isEqualTo(1);

        Vest loaded = vests.findByVestNum(9004).orElseThrow();
        assertThat(loaded.name()).isEqualTo("李四");
        assertThat(loaded.team()).isEqualTo(Team.YELLOW);
        assertThat(loaded.simNo()).isEqualTo("SIM-0004");
        assertThat(loaded.model()).isEqualTo("V3");
        assertThat(loaded.firmwareVersion()).isEqualTo("2.0.0");
        assertThat(loaded.bound()).isTrue();
        assertThat(loaded.enabled()).isFalse();
        assertThat(loaded.lastSeenAt()).isEqualTo(seen);
        assertThat(loaded.remark()).isEqualTo("调拨");
    }

    @Test
    void update_canClearNullableColumns() {
        vests.insert(new Vest(9005, "王五", Team.ORANGE, "SIM-0005", null, null, null,
                true, true, SEEN, null, null, null));

        assertThat(vests.update(vest(9005, null))).isEqualTo(1);

        Vest loaded = vests.findByVestNum(9005).orElseThrow();
        assertThat(loaded.name()).isNull();
        assertThat(loaded.team()).isNull();
        assertThat(loaded.simNo()).isNull();
        assertThat(loaded.lastSeenAt()).isNull();
        assertThat(loaded.bound()).isFalse();
    }

    @Test
    void deleteByVestNum_removesRow() {
        vests.insert(vest(9006, Team.BLUE));

        assertThat(vests.deleteByVestNum(9006)).isEqualTo(1);
        assertThat(vests.findByVestNum(9006)).isEmpty();
        assertThat(vests.deleteByVestNum(9006)).isZero();
    }

    @Test
    void findAll_paginatesInVestNumOrder() {
        long baseline = vests.count();
        vests.insert(vest(9101, Team.RED));
        vests.insert(vest(9102, Team.RED));
        vests.insert(vest(9103, Team.RED));
        assertThat(vests.count()).isEqualTo(baseline + 3);

        List<Vest> page1 = vests.findAll(PageRequest.of(1, 2));
        List<Vest> page2 = vests.findAll(PageRequest.of(2, 2));

        assertThat(page1).hasSize(2);
        assertThat(page1).extracting(Vest::vestNum).isSorted();
        assertThat(page2).extracting(Vest::vestNum).isSorted();
        // offset 生效：两页不重叠
        assertThat(page1).extracting(Vest::vestNum)
                .doesNotContainAnyElementsOf(page2.stream().map(Vest::vestNum).toList());
    }
}
