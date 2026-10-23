package com.dualx.core.exercise;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.support.InstanceProperties;
import com.dualx.core.support.SqlTime;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 心跳任务的验收（设计 01 §7 判据 2、3 的服务侧部分）。
 *
 * <p>测试上下文不带 {@code @EnableScheduling}，任务不会自己跑，因此这里先断言「没跑就没动」，
 * 再手工调 {@link ExerciseHeartbeatTask#tick()} 驱动一个周期——比等 10 秒轮询稳定得多，
 * 也考的是同一段代码。
 */
@SpringBootTest
@Transactional
class ExerciseHeartbeatTaskIT {

    @Autowired
    private ExerciseHeartbeatTask heartbeatTask;

    @Autowired
    private ExerciseRepository exercises;

    @Autowired
    private ExerciseLifecycleService lifecycle;

    @Autowired
    private InstanceProperties instanceProperties;

    @Autowired
    private JdbcClient jdbc;

    private static final Instant START = Instant.parse("2026-10-20T08:00:00.000Z");

    private static final String OTHER_INSTANCE = "instance-other";

    @Test
    void instanceId_defaultsToAFreshValueAndHonoursTheConfiguredOne() {
        InstanceProperties generated = new InstanceProperties(null);
        assertThat(generated.instanceId()).isNotBlank();
        // 每次解析都是一枚新标识：重启后的本实例必然不等于崩溃前的 owner
        assertThat(generated.instanceId()).isNotEqualTo(new InstanceProperties(null).instanceId());

        assertThat(new InstanceProperties("  edge-a  ").instanceId()).isEqualTo("edge-a");
    }

    @Test
    void tick_advancesHeartbeatOfTheExerciseThisInstanceOwns() {
        UUID id = runningOwnedBy(instanceProperties.instanceId());
        assertThat(heartbeatOf(id)).isEqualTo(START);

        heartbeatTask.tick();

        // 无需等到「10s 内变化 3 次」：一次 tick 就是一次写入，周期由 @Scheduled 保证
        assertThat(heartbeatOf(id)).isAfter(START);
    }

    @Test
    void tick_leavesAnotherInstancesExerciseAlone() {
        UUID id = runningOwnedBy(OTHER_INSTANCE);

        heartbeatTask.tick();

        // 别家的场次不该被本进程续活，否则活跃性判定失去意义
        assertThat(heartbeatOf(id)).isEqualTo(START);
    }

    @Test
    void tick_afterCloseOut_doesNotResurrectTheHeartbeat() {
        UUID id = runningOwnedBy(instanceProperties.instanceId());
        lifecycle.freezeAndClose(id, ExerciseEndReason.NORMAL, "EXERCISE_STOPPED", START.plusSeconds(600))
                .orElseThrow();

        heartbeatTask.tick();

        // 收尾后守卫（ended_at IS NULL）挡住续写，心跳停在最后那个值上
        assertThat(heartbeatOf(id)).isEqualTo(START);
    }

    /** 插入一场进行中的演习并把它占用给指定实例，返回其 id。 */
    private UUID runningOwnedBy(String instanceId) {
        UUID id = UuidV7.generate();
        exercises.insert(new Exercise(id, UuidV7.generate(), "it_heartbeat_心跳", null, BattleMode.TRAIN,
                START, null, 4, 0, 0, null, null));
        exercises.claim(id, instanceId, START);
        return id;
    }

    private Instant heartbeatOf(UUID id) {
        return jdbc.sql("SELECT heartbeat_at FROM t_exercise WHERE id = :id")
                .param("id", id.toString())
                .query(LocalDateTime.class)
                .optional()
                .map(SqlTime::fromDb)
                .orElse(null);
    }
}
