package com.dualx.core.exercise;

import com.dualx.core.domain.ExerciseCommand;
import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.domain.ExerciseLogEntry;
import com.dualx.core.domain.ExerciseLogType;
import com.dualx.core.repository.ExerciseCommandRepository;
import com.dualx.core.repository.ExerciseLogRepository;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.repository.ExerciseUnitRepository;
import com.dualx.core.repository.HitEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 演习收尾地基：一次事务内完成「冻结结局 + 作废残留指令 + 存证」。
 *
 * <p>**正常停演与启动自愈必须共用本方法**，只靠传入的 {@link ExerciseEndReason} 区分——
 * 两套收尾逻辑各自演化，迟早会在冻结口径或清理顺序上分叉，而现场看到的是同一张复盘表。
 *
 * <p>为什么合成一个事务：残留的 {@code PENDING} / {@code SENT} 指令如果在收尾与清理之间中断，
 * 而 {@code findPending} 是**全局**口径（不带 exercise_id），它们就会被派发给下一场演习的马甲。
 * 存证与删除必须同生共死：先删后记一旦失败，那些字节就永久消失。
 *
 * <p>本类不做业务判定：阵亡数只数态势状态机已冻结的终局（见
 * {@link ExerciseUnitRepository#countDeadByExerciseId(UUID)}），崩溃场次因此记 0；
 * 心跳、实例标识与「恢复完成门禁」属启动侧，见 {@code ExerciseLivenessRecover}。
 */
@Service
public class ExerciseLifecycleService {

    private final ExerciseRepository exercises;
    private final ExerciseUnitRepository units;
    private final HitEventRepository hits;
    private final ExerciseCommandRepository commands;
    private final ExerciseLogRepository logs;

    public ExerciseLifecycleService(ExerciseRepository exercises, ExerciseUnitRepository units,
                                    HitEventRepository hits, ExerciseCommandRepository commands,
                                    ExerciseLogRepository logs) {
        this.exercises = exercises;
        this.units = units;
        this.hits = hits;
        this.commands = commands;
        this.logs = logs;
    }

    /**
     * 收尾一场进行中的演习：冻结三项统计与 {@code ended_at}，再作废并清空本场次的指令队列。
     *
     * <p>{@code ended_at} 取**发现时刻**而非中断时刻——崩溃时不写时间戳，重启时补上，
     * 这两个时刻之间的差距本身就是现场事实（见 {@code end_reason} 契约说明）。
     *
     * @param reason            结束原因，正常停演传 NORMAL、自愈传 CRASH_RECOVERED
     * @param commandFailReason 残留指令作废时写入 {@code t_exercise_command.fail_reason} 的原因，
     *                          自愈传 {@code INSTANCE_RESTART}
     * @param at                收尾时刻（UTC）
     * @return 本次收尾的结果；{@code Optional.empty()} 表示该场次不存在或**已结束**——
     *         此时不做任何事，绝不能把残留指令当作本场的来清（否则会误清下一场的在途指令）
     */
    @Transactional
    public Optional<CloseOutcome> freezeAndClose(UUID exerciseId, ExerciseEndReason reason,
                                                 String commandFailReason, Instant at) {
        int rosterCount = (int) units.countByExerciseId(exerciseId);
        int hitCount = (int) hits.countByExerciseId(exerciseId);
        int deadCount = (int) units.countDeadByExerciseId(exerciseId);

        if (exercises.markEnded(exerciseId, at, hitCount, deadCount, reason) == 0) {
            return Optional.empty();
        }

        // 顺序不可换：先读（拿到原状态与下行原文）→ 再存证 → 再作废 → 最后删除
        List<ExerciseCommand> residual = commands.findResidualByExerciseId(exerciseId);
        for (ExerciseCommand command : residual) {
            logs.append(new ExerciseLogEntry(0L, exerciseId, at, ExerciseLogType.SYSTEM, command.vestNum(),
                    null, evidence(command, commandFailReason), command.downlinkHex()));
        }
        commands.markExpiredByExerciseId(exerciseId, at, commandFailReason);
        int deleted = commands.deleteByExerciseId(exerciseId);

        return Optional.of(new CloseOutcome(rosterCount, hitCount, deadCount, residual.size(), deleted));
    }

    /**
     * 一条残留指令的存证描述。要能脱离队列表独立读懂：队列行随后就被删了，
     * 复盘时只剩这条日志，故把队列主键、指令类型、原状态一并写进 detail。
     */
    private static String evidence(ExerciseCommand command, String failReason) {
        return "残留指令已作废：id=" + command.id() + " " + command.command()
                + " 马甲" + command.vestNum() + " 原状态" + command.state().name()
                + " 原因" + failReason;
    }

    /**
     * 一次收尾的结果。字段是**冻结进库里**的最终值，供调用方打日志与测试断言。
     *
     * @param rosterCount        参演人数（{@code t_exercise_unit} 行数）
     * @param hitCount           命中总数（{@code t_hit_event} 行数）
     * @param deadCount          阵亡人数（终局冻结为 DIE 的 unit 数；崩溃自愈时通常为 0）
     * @param residualCommands   被作废并已存证的残留指令条数
     * @param deletedCommands    从队列表删除的指令行数（含已终结的行，整场次清空）
     */
    public record CloseOutcome(int rosterCount, int hitCount, int deadCount,
                               int residualCommands, int deletedCommands) {
    }
}
