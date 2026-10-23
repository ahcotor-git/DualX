package com.dualx.core.exercise;

import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.support.InstanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 演习心跳（设计 01 §4.3）：只为本进程持有的进行中场次续 {@code heartbeat_at}。
 *
 * <p>为什么需要它：{@code uk_exercise_running} 只保证「同一时刻至多一场」，管不住
 * 「这一场还有没有活着的进程在跑」。{@code heartbeat_at} 才是活跃性依据，启动自愈靠它
 * 判断上次退出的场次已经没人管。
 *
 * <p>何时触发：{@code @EnableScheduling} 只在唯一入口 {@code ScdsNextApplication} 上开启，
 * 故本任务在应用内自动运行、在 {@code scds-core} 的测试上下文里不会自动跑（测试直接调 {@link #tick()}）。
 *
 * <p>两处刻意的克制：
 * <ul>
 *   <li>无进行中场次时只做一次轻量查询即返回，不写库——两秒一次的空写会白白产生 IO。</li>
 *   <li>续心跳失败只在日志记 ERROR，<b>绝不自动收尾</b>（§4.5）：心跳线程死了不代表演习停了，
 *       把真在跑的一场误停的代价远大于僵尸多存活一段时间。可观测指标待议题 05 定案后再补。</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(InstanceProperties.class)
public class ExerciseHeartbeatTask {

    private static final Logger log = LoggerFactory.getLogger(ExerciseHeartbeatTask.class);

    /** 与马甲在线判定（5000ms）同量级，便于现场统一理解；两者数值不必相同，也不共用配置项。 */
    private static final long PERIOD_MS = 2000;

    private final ExerciseRepository exercises;
    private final InstanceProperties instanceProperties;

    public ExerciseHeartbeatTask(ExerciseRepository exercises, InstanceProperties instanceProperties) {
        this.exercises = exercises;
        this.instanceProperties = instanceProperties;
    }

    @Scheduled(fixedDelay = PERIOD_MS)
    public void tick() {
        String instanceId = instanceProperties.instanceId();
        exercises.findRunningLiveness()
                .filter(running -> instanceId.equals(running.ownerInstance()))
                .ifPresent(running -> beat(running, instanceId));
    }

    private void beat(ExerciseRepository.RunningLiveness running, String instanceId) {
        try {
            // 影响 0 行不是故障：这一场刚被收尾或换了手（守卫里带了 owner_instance，不可省）。
            if (exercises.heartbeat(running.exerciseId(), instanceId, Instant.now()) == 0) {
                log.info("停止续心跳：演习 {} 已不属于本实例（已收尾或已换手）。", running.exerciseId());
            }
        } catch (RuntimeException failure) {
            log.error("演习 {} 心跳写入失败，本次放弃；按设计 01 §4.5 不做自动收尾。",
                    running.exerciseId(), failure);
        }
    }
}
