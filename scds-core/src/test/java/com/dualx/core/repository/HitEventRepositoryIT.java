package com.dualx.core.repository;

import com.dualx.core.domain.CoordinateSystem;
import com.dualx.core.domain.GeoPoint;
import com.dualx.core.domain.HitEvent;
import com.dualx.core.support.SqlTime;
import com.dualx.core.support.UuidV7;
import com.dualx.protocol.HitPart;
import com.dualx.protocol.WeaponKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class HitEventRepositoryIT {

    @Autowired
    private HitEventRepository hits;

    @Autowired
    private JdbcClient jdbc;

    private static final Instant AT = Instant.parse("2026-10-20T08:00:05.000Z");

    private static GeoPoint indoor() {
        return new GeoPoint(CoordinateSystem.INDOOR, 0.25, 0.75, 2, 17, null, null, null, null);
    }

    private static GeoPoint outdoor() {
        return new GeoPoint(CoordinateSystem.OUTDOOR, null, null, null, null,
                116.397428, 39.90923, 116.391275, 39.9075);
    }

    /** 按「编号 → 单兵/武器」推导，与生产代码同一口径。 */
    private static HitEvent hit(UUID exerciseId, int shooterNum, int hitVestNum,
                                HitPart part, boolean lethal, GeoPoint position) {
        return new HitEvent(0L, exerciseId, AT, 5000, hitVestNum, shooterNum,
                WeaponKind.isSoldier(shooterNum), WeaponKind.fromNum(shooterNum), part, lethal, position);
    }

    @Test
    void insert_singleSoldierHit_roundTripsAllColumns() {
        UUID exerciseId = UuidV7.generate();
        hits.insert(hit(exerciseId, 1001, 1002, HitPart.CHEST, true, indoor()));

        HitEvent found = hits.findByExerciseIdSince(exerciseId, null, 10).getFirst();
        assertThat(found.seq()).isPositive();
        assertThat(found.exerciseId()).isEqualTo(exerciseId);
        assertThat(found.at()).isEqualTo(AT);
        assertThat(found.tMs()).isEqualTo(5000);
        assertThat(found.shooterNum()).isEqualTo(1001);
        assertThat(found.hitVestNum()).isEqualTo(1002);
        assertThat(found.shooterIsSoldier()).isTrue();
        assertThat(found.shooterWeaponKind()).isNull();
        assertThat(found.hitPart()).isEqualTo(HitPart.CHEST);
        assertThat(found.lethal()).isTrue();
        assertThat(found.position()).isEqualTo(indoor());
    }

    @Test
    void insert_heavyWeaponHit_derivesWeaponKindFromNum() {
        UUID exerciseId = UuidV7.generate();
        // 3100 落在 IFV(3001~3200)
        hits.insert(hit(exerciseId, 3100, 1002, HitPart.BACK, false, outdoor()));

        HitEvent found = hits.findByExerciseIdSince(exerciseId, null, 10).getFirst();
        assertThat(found.shooterIsSoldier()).isFalse();
        assertThat(found.shooterWeaponKind()).isEqualTo(WeaponKind.IFV);
        assertThat(found.lethal()).isFalse();
        assertThat(found.position()).isEqualTo(outdoor());
    }

    @Test
    void insert_withoutPosition_roundTripsNullPosition() {
        UUID exerciseId = UuidV7.generate();
        hits.insert(hit(exerciseId, 1001, 1002, HitPart.UNKNOWN, false, null));

        assertThat(hits.findByExerciseIdSince(exerciseId, null, 10).getFirst().position()).isNull();
    }

    @Test
    void findByExerciseIdSince_returnsAscendingAndIncremental() {
        UUID exerciseId = UuidV7.generate();
        hits.insert(hit(exerciseId, 1001, 2001, HitPart.HEAD, true, indoor()));
        hits.insert(hit(exerciseId, 1002, 2002, HitPart.LEFT_LEG, false, indoor()));
        hits.insert(hit(exerciseId, 1003, 2003, HitPart.ABDOMEN, true, indoor()));

        List<HitEvent> all = hits.findByExerciseIdSince(exerciseId, null, 10);
        assertThat(all).hasSize(3);
        assertThat(all).extracting(HitEvent::seq).isSorted();
        assertThat(all).extracting(HitEvent::shooterNum).containsExactly(1001, 1002, 1003);

        long cursor = all.get(1).seq();
        List<HitEvent> rest = hits.findByExerciseIdSince(exerciseId, cursor, 10);
        assertThat(rest).extracting(HitEvent::shooterNum).containsExactly(1003);
        assertThat(hits.findByExerciseIdSince(exerciseId, all.getLast().seq(), 10)).isEmpty();
    }

    @Test
    void findByExerciseIdSince_respectsLimit() {
        UUID exerciseId = UuidV7.generate();
        for (int i = 0; i < 5; i++) {
            hits.insert(hit(exerciseId, 1001 + i, 2001, HitPart.CHEST, false, indoor()));
        }

        assertThat(hits.findByExerciseIdSince(exerciseId, null, 2)).hasSize(2);
    }

    @Test
    void queries_areScopedToExercise() {
        UUID mine = UuidV7.generate();
        UUID other = UuidV7.generate();
        hits.insert(hit(mine, 1001, 2001, HitPart.CHEST, true, indoor()));
        hits.insert(hit(other, 1002, 2002, HitPart.BACK, false, indoor()));

        assertThat(hits.findByExerciseIdSince(mine, null, 10)).hasSize(1);
        assertThat(hits.countByExerciseId(mine)).isEqualTo(1);
    }

    @Test
    void countByShooter_andLethal_countsSeparately() {
        UUID exerciseId = UuidV7.generate();
        hits.insert(hit(exerciseId, 1001, 2001, HitPart.HEAD, true, indoor()));
        hits.insert(hit(exerciseId, 1001, 2002, HitPart.CHEST, true, indoor()));
        hits.insert(hit(exerciseId, 1001, 2003, HitPart.LEFT_HAND, false, indoor()));
        hits.insert(hit(exerciseId, 1002, 2004, HitPart.BACK, true, indoor()));

        assertThat(hits.countByShooter(exerciseId, 1001)).isEqualTo(3);
        assertThat(hits.countLethalByShooter(exerciseId, 1001)).isEqualTo(2);
        assertThat(hits.countByShooter(exerciseId, 1002)).isEqualTo(1);
        assertThat(hits.countLethalByShooter(exerciseId, 1002)).isEqualTo(1);
        assertThat(hits.countByShooter(exerciseId, 9999)).isZero();
    }

    @Test
    void deleteByExerciseId_removesOnlyThatExercise() {
        UUID mine = UuidV7.generate();
        UUID other = UuidV7.generate();
        hits.insert(hit(mine, 1001, 2001, HitPart.CHEST, true, indoor()));
        hits.insert(hit(other, 1002, 2002, HitPart.BACK, false, indoor()));

        assertThat(hits.deleteByExerciseId(mine)).isEqualTo(1);
        assertThat(hits.countByExerciseId(mine)).isZero();
        assertThat(hits.countByExerciseId(other)).isEqualTo(1);
    }

    @Test
    void hitPartCheck_rejectsDirtyValueWrittenAroundTheRepository() {
        // Java 枚举造不出非法值，故用原生 SQL 证明库层 CHECK 确实在兜底
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO t_hit_event (exercise_id, at, t_ms, hit_vest_num, shooter_num,
                                                 shooter_is_soldier, hit_part, lethal)
                        VALUES (:exerciseId, :at, 1, 1001, 1002, 1, 'NECK', 0)
                        """)
                .param("exerciseId", UuidV7.generate().toString())
                .param("at", SqlTime.toDb(AT))
                .update())
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_hit_event_hit_part");
    }

    @Test
    void coordinateSystemCheck_rejectsDirtyValueWrittenAroundTheRepository() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO t_hit_event (exercise_id, at, t_ms, hit_vest_num, shooter_num,
                                                 shooter_is_soldier, hit_part, lethal, coordinate_system)
                        VALUES (:exerciseId, :at, 1, 1001, 1002, 1, 'HEAD', 0, 'MARS')
                        """)
                .param("exerciseId", UuidV7.generate().toString())
                .param("at", SqlTime.toDb(AT))
                .update())
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_hit_event_coord_system");
    }
}
