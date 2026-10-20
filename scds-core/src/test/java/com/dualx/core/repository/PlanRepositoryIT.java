package com.dualx.core.repository;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Plan;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PlanRepositoryIT {

    @Autowired
    private PlanRepository plans;

    @Test
    void insertThenFindById_roundTripsEveryColumn() {
        UUID id = UuidV7.generate();
        UUID mapId = UuidV7.generate();
        Plan drafted = new Plan(id, "城市攻坚", mapId, BattleMode.BATTLE, "红蓝对抗", null, null);

        assertThat(plans.insert(drafted)).isEqualTo(1);

        Plan loaded = plans.findById(id).orElseThrow();
        assertThat(loaded.name()).isEqualTo("城市攻坚");
        assertThat(loaded.mapId()).isEqualTo(mapId);
        assertThat(loaded.battleMode()).isEqualTo(BattleMode.BATTLE);
        assertThat(loaded.remark()).isEqualTo("红蓝对抗");
        assertThat(loaded.createdAt()).isNotNull();
        assertThat(loaded.updatedAt()).isNotNull();
    }

    @Test
    void mapId_mayBeNull() {
        UUID id = UuidV7.generate();
        assertThat(plans.insert(new Plan(id, "未挂图想定", null, BattleMode.TRAIN, null, null, null)))
                .isEqualTo(1);

        assertThat(plans.findById(id).orElseThrow().mapId()).isNull();
    }

    @Test
    void findById_returnsEmptyWhenAbsent() {
        UUID id = UuidV7.generate();
        assertThat(plans.findById(id)).isEmpty();
        assertThat(plans.existsById(id)).isFalse();
    }

    @Test
    void duplicateId_isRejectedByPrimaryKey() {
        UUID id = UuidV7.generate();
        plans.insert(new Plan(id, "甲想定", null, BattleMode.TRAIN, null, null, null));

        assertThatThrownBy(() -> plans.insert(new Plan(id, "乙想定", null, BattleMode.TRAIN, null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void update_changesMutableColumnsIncludingClearingMap() {
        UUID id = UuidV7.generate();
        plans.insert(new Plan(id, "旧名", UuidV7.generate(), BattleMode.BATTLE, "旧备注", null, null));

        assertThat(plans.update(new Plan(id, "新名", null, BattleMode.TRAIN, null, null, null)))
                .isEqualTo(1);

        Plan loaded = plans.findById(id).orElseThrow();
        assertThat(loaded.name()).isEqualTo("新名");
        assertThat(loaded.mapId()).isNull();
        assertThat(loaded.battleMode()).isEqualTo(BattleMode.TRAIN);
        assertThat(loaded.remark()).isNull();
    }

    @Test
    void deleteById_removesRow() {
        UUID id = UuidV7.generate();
        plans.insert(new Plan(id, "待删想定", null, BattleMode.TRAIN, null, null, null));

        assertThat(plans.deleteById(id)).isEqualTo(1);
        assertThat(plans.findById(id)).isEmpty();
        assertThat(plans.deleteById(id)).isZero();
    }

    @Test
    void findAll_ordersByUpdatedAtDescAndPaginates() {
        UUID first = UuidV7.generate();
        UUID second = UuidV7.generate();
        UUID third = UuidV7.generate();
        plans.insert(new Plan(first, "it_plan_pg_1", null, BattleMode.TRAIN, null, null, null));
        plans.insert(new Plan(second, "it_plan_pg_2", null, BattleMode.TRAIN, null, null, null));
        plans.insert(new Plan(third, "it_plan_pg_3", null, BattleMode.TRAIN, null, null, null));

        List<Plan> page1 = plans.findAll(PageRequest.of(1, 2));
        List<Plan> page2 = plans.findAll(PageRequest.of(2, 2));

        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(1);
        assertThat(page1).extracting(Plan::updatedAt).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(page1).extracting(Plan::id)
                .doesNotContainAnyElementsOf(page2.stream().map(Plan::id).toList());
        assertThat(page1).extracting(Plan::id).containsAnyOf(first, second, third);
    }

    @Test
    void findByKeyword_matchesSubstringOfName() {
        UUID match = UuidV7.generate();
        UUID other = UuidV7.generate();
        plans.insert(new Plan(match, "it_plan_夜袭高地", null, BattleMode.TRAIN, null, null, null));
        plans.insert(new Plan(other, "it_plan_昼间巡逻", null, BattleMode.TRAIN, null, null, null));

        List<Plan> found = plans.findByKeyword("高地", PageRequest.of(1, 10));

        assertThat(found).extracting(Plan::id).containsExactly(match);
        assertThat(plans.countByKeyword("高地")).isEqualTo(1);
    }

    @Test
    void findByKeyword_returnsEmptyWhenNoMatch() {
        plans.insert(new Plan(UuidV7.generate(), "it_plan_甲", null, BattleMode.TRAIN, null, null, null));

        assertThat(plans.findByKeyword("it_plan_绝无此名", PageRequest.of(1, 10))).isEmpty();
        assertThat(plans.countByKeyword("it_plan_绝无此名")).isZero();
    }

    @Test
    void findByKeyword_treatsWildcardsAsLiteralText() {
        // 想定名刻意不含 % 与 _
        UUID id = UuidV7.generate();
        plans.insert(new Plan(id, "百分号一百", null, BattleMode.TRAIN, null, null, null));

        // keyword 来自用户输入：'%' 不该被当通配符，否则搜一个字符就能命中全表
        assertThat(plans.findByKeyword("%", PageRequest.of(1, 10))).isEmpty();
        assertThat(plans.countByKeyword("%")).isZero();
        // '_' 同理
        assertThat(plans.findByKeyword("_", PageRequest.of(1, 10))).isEmpty();
        assertThat(plans.countByKeyword("_")).isZero();
    }

    @Test
    void findByKeyword_matchesLiteralWildcardWhenPresent() {
        UUID id = UuidV7.generate();
        plans.insert(new Plan(id, "it_plan_胜率100%", null, BattleMode.TRAIN, null, null, null));

        assertThat(plans.findByKeyword("100%", PageRequest.of(1, 10)))
                .extracting(Plan::id).containsExactly(id);
    }
}
