package com.dualx.core.exercise;

import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.repository.ExerciseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 启动自愈（设计 01 §4.4）：把上次异常退出留下的「进行中」场次收尾。
 *
 * <p>起因是一个功能性缺陷：{@code uk_exercise_running} 把「进行中」完全绑定在
 * {@code ended_at IS NULL} 上，而该列只由正常停演路径写入。进程崩溃/断电后库里就留下一行
 * 永不结束的演习，新演习必然撞唯一索引，只能靠人工改库绕过。
 *
 * <p><b>规则：启动时发现「进行中」的行，一律收尾</b>——包括「就是本实例、心跳还很新」的快速重启。
 * 不区分的原因见 §4.1：态势模型、链路句柄、UWB 标定上下文都只在内存里，且现场物理状态已经前进，
 * 补不回来。既然无法接管，就不该有「有时候接管、有时候收尾」这种现场无法预期的行为。
 *
 * <p>收尾动作本身不是这里实现的：一律转交 {@link ExerciseLifecycleService#freezeAndClose}，
 * 与正常停演共用同一段代码，只靠 {@link ExerciseEndReason#CRASH_RECOVERED} 区分。
 *
 * <p>{@code @Order(10)} 只决定本 Runner 与其他 Runner 的先后；它<b>不能</b>阻止已监听的 HTTP 端口
 * 收请求。设计 §4.4 要求的「恢复完成门禁」（恢复未完成时开演被拒）与 {@code startExercise} 同批落地，
 * 本类尚不承载门禁状态。恢复失败时本类不吞异常：启动直接失败，比「起来了却开不了演」更早被运维发现。
 */
@Component
@Order(10)
public class ExerciseLivenessRecover implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExerciseLivenessRecover.class);

    /** 残留指令作废时写入 {@code t_exercise_command.fail_reason} 的原因，与设计 §4.4 第 2 步一致。 */
    private static final String RESTART_FAIL_REASON = "INSTANCE_RESTART";

    private final ExerciseRepository exercises;
    private final ExerciseLifecycleService lifecycle;

    public ExerciseLivenessRecover(ExerciseRepository exercises, ExerciseLifecycleService lifecycle) {
        this.exercises = exercises;
        this.lifecycle = lifecycle;
    }

    @Override
    public void run(ApplicationArguments args) {
        exercises.findRunningLiveness().ifPresent(this::recover);
    }

    private void recover(ExerciseRepository.RunningLiveness running) {
        Instant at = Instant.now();
        ExerciseLifecycleService.CloseOutcome outcome = lifecycle
                .freezeAndClose(running.exerciseId(), ExerciseEndReason.CRASH_RECOVERED,
                        RESTART_FAIL_REASON, at)
                .orElse(null);
        if (outcome == null) {
            // 查询与收尾之间被别的路径结束了（例如操作员恰好点了停演）。这不是故障，
            // 但也不能记成「自愈收尾」——那会把一次正常停演误标成异常中断。
            log.info("启动自愈跳过：演习 {} 在检查与收尾之间已结束，按原结束原因保留。", running.exerciseId());
            return;
        }

        // WARN 而非 INFO：这一场带着「现场数据可能不完整」的含义，需要被看到。
        // 带上原实例与最后心跳，是为了让现场能把中断时刻和物理事件对上。
        log.warn("启动自愈收尾僵尸演习：exerciseId={}，原实例={}，最后心跳={}，收尾时刻={}；"
                        + "冻结 roster={} hit={} dead={}，作废残留指令 {} 条并清出队列 {} 行。"
                        + "该场次的轨迹/命中/日志已保留，复盘列表按 endReason=CRASH_RECOVERED 可识别。",
                running.exerciseId(), running.ownerInstance(), running.heartbeatAt(), at,
                outcome.rosterCount(), outcome.hitCount(), outcome.deadCount(),
                outcome.residualCommands(), outcome.deletedCommands());
    }
}
