package com.dualx.core.repository;

import com.dualx.core.domain.PollMode;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.support.UuidV7;
import com.dualx.protocol.Team;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PlanRosterRepositoryIT {

    @Autowired
    private PlanRosterRepository roster;

    private static RosterEntry entry(UUID planId, int vestNum, Team team) {
        return new RosterEntry(0L, planId, vestNum, null, team, 100, 30, 30, PollMode.AUTO, null, null);
    }

    @Test
    void insertThenFindByPlanId_roundTripsEveryColumn() {
        UUID planId = UuidV7.generate();
        RosterEntry drafted = new RosterEntry(0L, planId, 101, "张三", Team.RED, 80, 15, 7,
                PollMode.MANUAL, null, null);

        assertThat(roster.insert(drafted)).isEqualTo(1);

        List<RosterEntry> found = roster.findByPlanId(planId);
        assertThat(found).hasSize(1);
        RosterEntry loaded = found.getFirst();
        assertThat(loaded.id()).isPositive();
        assertThat(loaded.planId()).isEqualTo(planId);
        assertThat(loaded.vestNum()).isEqualTo(101);
        assertThat(loaded.name()).isEqualTo("张三");
        assertThat(loaded.team()).isEqualTo(Team.RED);
        assertThat(loaded.hp()).isEqualTo(80);
        assertThat(loaded.ammo1()).isEqualTo(15);
        assertThat(loaded.ammo2()).isEqualTo(7);
        assertThat(loaded.mode()).isEqualTo(PollMode.MANUAL);
        assertThat(loaded.createdAt()).isNotNull();
        assertThat(loaded.updatedAt()).isNotNull();
    }

    @Test
    void name_mayBeNull() {
        UUID planId = UuidV7.generate();
        roster.insert(entry(planId, 1, Team.BLUE));

        assertThat(roster.findByPlanId(planId).getFirst().name()).isNull();
    }

    @Test
    void findByPlanId_ordersByTeamThenVestNum() {
        UUID planId = UuidV7.generate();
        roster.insert(entry(planId, 300, Team.RED));
        roster.insert(entry(planId, 200, Team.RED));
        roster.insert(entry(planId, 100, Team.BLUE));

        List<RosterEntry> found = roster.findByPlanId(planId);

        // BLUE 在字母序上先于 RED；同一队伍内按马甲编号升序
        assertThat(found).extracting(RosterEntry::team)
                .containsExactly(Team.BLUE, Team.RED, Team.RED);
        assertThat(found).extracting(RosterEntry::vestNum).containsExactly(100, 200, 300);
    }

    @Test
    void findByPlanId_returnsEmptyWhenAbsent() {
        assertThat(roster.findByPlanId(UuidV7.generate())).isEmpty();
        assertThat(roster.countByPlanId(UuidV7.generate())).isZero();
    }

    @Test
    void duplicatePlanIdVestNum_isRejectedByUniqueKey() {
        UUID planId = UuidV7.generate();
        roster.insert(entry(planId, 101, Team.RED));

        assertThatThrownBy(() -> roster.insert(entry(planId, 101, Team.BLUE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameVestNumOnDifferentPlans_isAllowed() {
        UUID planA = UuidV7.generate();
        UUID planB = UuidV7.generate();

        assertThat(roster.insert(entry(planA, 101, Team.RED))).isEqualTo(1);
        assertThat(roster.insert(entry(planB, 101, Team.RED))).isEqualTo(1);

        assertThat(roster.findByPlanId(planA)).hasSize(1);
        assertThat(roster.findByPlanId(planB)).hasSize(1);
    }

    @Test
    void vestNumAboveContractCeiling_isRejectedByCheck() {
        // 65533~65535 是杀伤物编号，编成里只允许 1~65532
        assertThatThrownBy(() -> roster.insert(entry(UuidV7.generate(), 65533, Team.RED)))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_plan_roster_vest_num");
    }

    @Test
    void replaceByPlanId_swapsWholeSet() {
        UUID planId = UuidV7.generate();
        roster.insert(entry(planId, 101, Team.RED));
        roster.insert(entry(planId, 102, Team.RED));
        long oldId = roster.findByPlanId(planId).getFirst().id();

        int written = roster.replaceByPlanId(planId, List.of(
                entry(planId, 201, Team.BLUE),
                entry(planId, 202, Team.BLUE),
                entry(planId, 203, Team.BLUE)));

        assertThat(written).isEqualTo(3);
        assertThat(roster.countByPlanId(planId)).isEqualTo(3);
        assertThat(roster.findByPlanId(planId)).extracting(RosterEntry::vestNum)
                .containsExactly(201, 202, 203);
        // 先删后插：旧行的代理主键不复用
        assertThat(roster.findByPlanId(planId).getFirst().id()).isNotEqualTo(oldId);
    }

    @Test
    void replaceByPlanId_withEmptyList_clearsTheRoster() {
        UUID planId = UuidV7.generate();
        roster.insert(entry(planId, 101, Team.RED));

        assertThat(roster.replaceByPlanId(planId, List.of())).isZero();
        assertThat(roster.findByPlanId(planId)).isEmpty();
    }

    @Test
    void replaceByPlanId_leavesOtherPlansUntouched() {
        UUID target = UuidV7.generate();
        UUID other = UuidV7.generate();
        roster.insert(entry(target, 101, Team.RED));
        roster.insert(entry(other, 101, Team.RED));

        roster.replaceByPlanId(target, List.of(entry(target, 999, Team.YELLOW)));

        assertThat(roster.findByPlanId(target)).extracting(RosterEntry::vestNum).containsExactly(999);
        assertThat(roster.findByPlanId(other)).extracting(RosterEntry::vestNum).containsExactly(101);
    }

    @Test
    void deleteByPlanId_removesAllEntriesOfThatPlan() {
        UUID planId = UuidV7.generate();
        roster.insert(entry(planId, 101, Team.RED));
        roster.insert(entry(planId, 102, Team.RED));

        assertThat(roster.deleteByPlanId(planId)).isEqualTo(2);
        assertThat(roster.findByPlanId(planId)).isEmpty();
        assertThat(roster.deleteByPlanId(planId)).isZero();
    }
}
