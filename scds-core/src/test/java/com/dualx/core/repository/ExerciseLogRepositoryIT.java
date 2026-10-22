package com.dualx.core.repository;

import com.dualx.core.domain.ExerciseLogEntry;
import com.dualx.core.domain.ExerciseLogType;
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
class ExerciseLogRepositoryIT {

    @Autowired
    private ExerciseLogRepository logs;

    private static final Instant AT = Instant.parse("2026-10-20T08:00:01.000Z");

    private static ExerciseLogEntry entry(UUID exerciseId, ExerciseLogType type, Integer vestNum, String detail) {
        return new ExerciseLogEntry(0L, exerciseId, AT, type, vestNum, null, detail, null);
    }

    @Test
    void append_andFindLatest_roundTripsAllColumns() {
        UUID exerciseId = UuidV7.generate();
        logs.append(new ExerciseLogEntry(0L, exerciseId, AT, ExerciseLogType.SHOT, 1001,
                "指挥员", "命中胸部", "0009ff03e9a1affa"));

        ExerciseLogEntry found = logs.findLatest(exerciseId, 10).getFirst();
        assertThat(found.seq()).isPositive();
        assertThat(found.exerciseId()).isEqualTo(exerciseId);
        assertThat(found.at()).isEqualTo(AT);
        assertThat(found.logType()).isEqualTo(ExerciseLogType.SHOT);
        assertThat(found.vestNum()).isEqualTo(1001);
        assertThat(found.operatorName()).isEqualTo("指挥员");
        assertThat(found.detail()).isEqualTo("命中胸部");
        assertThat(found.downlinkHex()).isEqualTo("0009ff03e9a1affa");
        assertThat(logs.countByExerciseId(exerciseId)).isEqualTo(1);
    }

    @Test
    void append_systemLevelLog_allowsNullVestNumAndOperator() {
        UUID exerciseId = UuidV7.generate();
        logs.append(new ExerciseLogEntry(0L, exerciseId, AT, ExerciseLogType.START_BATTLE, null, null, null, null));

        ExerciseLogEntry found = logs.findLatest(exerciseId, 10).getFirst();
        assertThat(found.vestNum()).isNull();
        assertThat(found.operatorName()).isNull();
        assertThat(found.detail()).isNull();
        assertThat(found.downlinkHex()).isNull();
    }

    /**
     * V10 把 {@code ck_exercise_log_type} 扩到含 {@code SYSTEM}。库侧的 CHECK 与 Java 枚举是两处
     * 独立声明，只有真的写进去再读回来，才能证明两边都认这个值——单测只能证明枚举里写得下。
     */
    @Test
    void append_systemEntry_survivesTheDatabaseCheckConstraint() {
        UUID exerciseId = UuidV7.generate();
        logs.append(new ExerciseLogEntry(0L, exerciseId, AT, ExerciseLogType.SYSTEM, null, null,
                "实例重启，残留指令已作废", null));

        assertThat(logs.findLatest(exerciseId, 10).getFirst().logType()).isEqualTo(ExerciseLogType.SYSTEM);
    }

    @Test
    void findLatest_returnsTheNewestPageInAscendingOrder() {
        UUID exerciseId = UuidV7.generate();
        for (int i = 1; i <= 5; i++) {
            logs.append(entry(exerciseId, ExerciseLogType.SHOT, i, "第" + i + "条"));
        }

        List<ExerciseLogEntry> latest = logs.findLatest(exerciseId, 3);

        // 取 seq 最大的 3 条，但按 seq 升序给出——滚动日志从旧到新读
        assertThat(latest).extracting(ExerciseLogEntry::detail)
                .containsExactly("第3条", "第4条", "第5条");
    }

    @Test
    void findByExerciseIdSince_returnsOnlyNewerEntriesInAscendingOrder() {
        UUID exerciseId = UuidV7.generate();
        logs.append(entry(exerciseId, ExerciseLogType.SHOT, 1, "第1条"));
        logs.append(entry(exerciseId, ExerciseLogType.SHOT, 2, "第2条"));
        long cursor = logs.findLatest(exerciseId, 10).get(1).seq();
        logs.append(entry(exerciseId, ExerciseLogType.DIE, 3, "第3条"));
        logs.append(entry(exerciseId, ExerciseLogType.LIVE, 4, "第4条"));

        assertThat(logs.findByExerciseIdSince(exerciseId, cursor, 100))
                .extracting(ExerciseLogEntry::detail)
                .containsExactly("第3条", "第4条");
    }

    @Test
    void findByExerciseIdSince_withNullCursor_returnsFromBeginning() {
        UUID exerciseId = UuidV7.generate();
        logs.append(entry(exerciseId, ExerciseLogType.SHOT, 1, "第1条"));
        logs.append(entry(exerciseId, ExerciseLogType.SHOT, 2, "第2条"));

        assertThat(logs.findByExerciseIdSince(exerciseId, null, 100)).hasSize(2);
    }

    @Test
    void findByExerciseIdSince_respectsLimit() {
        UUID exerciseId = UuidV7.generate();
        for (int i = 1; i <= 5; i++) {
            logs.append(entry(exerciseId, ExerciseLogType.SHOT, i, "第" + i + "条"));
        }

        assertThat(logs.findByExerciseIdSince(exerciseId, null, 2))
                .extracting(ExerciseLogEntry::detail)
                .containsExactly("第1条", "第2条");
    }

    @Test
    void findByExerciseId_isScopedToItsExercise() {
        UUID mine = UuidV7.generate();
        UUID other = UuidV7.generate();
        logs.append(entry(mine, ExerciseLogType.SHOT, 1, "我的"));
        logs.append(entry(other, ExerciseLogType.SHOT, 2, "别人的"));

        assertThat(logs.findLatest(mine, 10)).extracting(ExerciseLogEntry::detail).containsExactly("我的");
        assertThat(logs.countByExerciseId(other)).isEqualTo(1);
        assertThat(logs.countByExerciseId(mine)).isEqualTo(1);
    }
}
