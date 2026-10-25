package com.dualx.core.exercise;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseCommand;
import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.domain.ExerciseLogEntry;
import com.dualx.core.domain.ExerciseLogType;
import com.dualx.core.domain.ExerciseUnit;
import com.dualx.core.domain.Plan;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.repository.ExerciseCommandRepository;
import com.dualx.core.repository.ExerciseLogRepository;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.repository.ExerciseUnitRepository;
import com.dualx.core.repository.HitEventRepository;
import com.dualx.core.repository.PlanRepository;
import com.dualx.core.repository.PlanRosterRepository;
import com.dualx.core.state.SituationSessionBinder;
import com.dualx.core.support.InstanceProperties;
import com.dualx.core.support.UuidV7;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 演习生命周期地基：开演、停演与「冻结结局 + 作废残留指令 + 存证」共用同一处代码。
 *
 * <p>**正常停演与启动自愈必须共用 {@link #freezeAndClose}**，只靠传入的
 * {@link ExerciseEndReason} 区分——两套收尾逻辑各自演化，迟早会在冻结口径或清理顺序上分叉，
 * 而现场看到的是同一张复盘表。
 *
 * <p>为什么收尾合成一个事务：残留的 {@code PENDING} / {@code SENT} 指令如果在收尾与清理之间中断，
 * 而 {@code findPending} 是**全局**口径（不带 exercise_id），它们就会被派发给下一场演习的马甲。
 * 存证与删除必须同生共死：先删后记一旦失败，那些字节就永久消失。
 *
 * <p>本类不做业务判定：阵亡数只数态势状态机已冻结的终局（见
 * {@link ExerciseUnitRepository#countDeadByExerciseId(UUID)}），崩溃场次因此记 0；
 * 心跳见 {@code ExerciseHeartbeatTask}，实例标识见 {@link InstanceProperties}。
 *
 * <p><b>恢复完成门禁</b>：本类持一个一次性置位的就绪位（{@link #markRecovered()}），
 * {@code ExerciseLivenessRecover} 在自愈正常返回后置位。置位前 {@link #startExercise} 一律拒绝
 * （{@link ApiErrorCode#INSTANCE_NOT_READY}，503）——启动瞬间端口已在收请求而自愈未必跑完
 * （设计 01 §4.4）。门禁只拦**开演**：停演、读侧不拦，运维正好要靠它们看见并处理残留场次。
 *
 * <p><b>尚未接线</b>：契约 {@code startExercise} 的步骤 ②③（逐台下发 14B 轮询配置帧、
 * 7B 演习开始帧）与 {@code stopExercise} 的 7B 结束帧都属网关下行，{@link #startExercise} 与
 * {@link #stopExercise} 只做库与内存态，不碰硬件；单实例保护（L6）也未落地。
 */
@Service
public class ExerciseLifecycleService {

    private final ExerciseRepository exercises;
    private final ExerciseUnitRepository units;
    private final HitEventRepository hits;
    private final ExerciseCommandRepository commands;
    private final ExerciseLogRepository logs;
    private final PlanRepository plans;
    private final PlanRosterRepository planRoster;
    private final SituationSessionBinder sessions;
    private final InstanceProperties instanceProperties;

    /** 一次性就绪位，见类 javadoc「恢复完成门禁」；初值 {@code false} 即「尚未自愈」。 */
    private final AtomicBoolean recovered = new AtomicBoolean(false);

    public ExerciseLifecycleService(ExerciseRepository exercises, ExerciseUnitRepository units,
                                    HitEventRepository hits, ExerciseCommandRepository commands,
                                    ExerciseLogRepository logs, PlanRepository plans,
                                    PlanRosterRepository planRoster, SituationSessionBinder sessions,
                                    InstanceProperties instanceProperties) {
        this.exercises = exercises;
        this.units = units;
        this.hits = hits;
        this.commands = commands;
        this.logs = logs;
        this.plans = plans;
        this.planRoster = planRoster;
        this.sessions = sessions;
        this.instanceProperties = instanceProperties;
    }

    /**
     * 当前进行中的演习；无演习时为 {@link Optional#empty()}（契约 {@code getCurrentExercise} 的读侧）。
     *
     * <p><b>读库而不读态势内存态</b>：{@code t_exercise.ended_at IS NULL} 是「有一场未结束的场次」
     * 的权威。进程刚重启、自愈尚未收尾时内存态（{@code SituationStateService} 的 {@code exerciseId}）
     * 是空的，而这一行恰恰是操作员要看见并停掉的事实（设计 01 §4.4）。在线数只存在于态势读侧，
     * 由调用方另行取数——本方法不碰硬件、不碰内存态。
     */
    public Optional<Exercise> currentExercise() {
        return exercises.findRunning();
    }

    /**
     * 发布「启动自愈已完成」：调用方是 {@code ExerciseLivenessRecover}，在自愈正常返回之后调一次。
     *
     * <p><b>只进不退</b>：没有对应的「复位」方法。自愈在一个进程生命周期里只可能跑一次，
     * 置位后再复位没有真实场景，反而会给出「可以退回未就绪」的错觉。
     */
    public void markRecovered() {
        recovered.set(true);
    }

    /**
     * 开演（契约 {@code startExercise}）：建演习行 → 占用运行权 → 快照编成 → 装载态势内存态。
     *
     * <p><b>门禁在方法最前，先于任何查库</b>：自愈未完成时实例的库状态本身就不可信
     * （上一场的残留行还在），此时把「想定不存在」之类的结论报出去只会误导现场。503 的
     * 语义是「稍后重试」，因此优先于 404/400 报出——门禁未放行就谈不上请求内容对不对。
     *
     * <p><b>「至多一场」不做先查后插</b>：判据完全交给库层唯一索引 {@code uk_exercise_running}
     * （生成列 {@code running_flag}）。先 {@code findRunning()} 再 {@code insert()} 之间必有竞态窗口
     * ——那正是 V4 文件头否掉「只靠应用层先查再插」的理由——两个并发请求会同时通过检查；
     * 唯一索引是原子的，冲突时抛 {@link DuplicateKeyException}，这里把它翻成
     * {@link ApiErrorCode#EXERCISE_ALREADY_RUNNING}（设计 01 §4.4 末句）。
     *
     * <p>顺序：想定 → 编成 → 插入。前两者是**这次请求的内容**问题，报出来现场直接就能改；
     * 而「已有在跑场次」是系统状态，只能在插入那一刻才确定。装载
     * （{@link SituationSessionBinder#onExerciseStarted}）放最后一步：它改的是内存态，
     * 之后只剩提交，回滚窗口最小。
     *
     * @param planId              开演所引用的想定
     * @param requestedBattleMode 请求体里的模式；为 {@code null} 表示沿用想定的模式（契约默认）
     * @return 刚建好的演习行（{@code endedAt} 为 null，计数为 0）；库生成的时间戳不在其中
     * @throws BusinessException {@code INSTANCE_NOT_READY}(503) 启动自愈尚未完成；
     *                           {@code EXERCISE_PLAN_NOT_FOUND}(404) 想定不存在；
     *                           {@code EXERCISE_ROSTER_EMPTY}(400) 编成为空；
     *                           {@code EXERCISE_ALREADY_RUNNING}(409) 已有演习进行中
     */
    @Transactional
    public Exercise startExercise(UUID planId, BattleMode requestedBattleMode) {
        if (!recovered.get()) {
            throw new BusinessException(ApiErrorCode.INSTANCE_NOT_READY,
                    "本实例尚未完成启动自愈，暂不可开演；稍后重试");
        }
        Plan plan = plans.findById(planId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.EXERCISE_PLAN_NOT_FOUND,
                        "开演所引用的想定不存在：" + planId));
        List<RosterEntry> roster = planRoster.findByPlanId(planId);
        if (roster.isEmpty()) {
            throw new BusinessException(ApiErrorCode.EXERCISE_ROSTER_EMPTY,
                    "想定编成为空，无法开演：" + planId);
        }

        Instant at = Instant.now();
        Exercise exercise = new Exercise(UuidV7.generate(), plan.id(), plan.name(), plan.mapId(),
                requestedBattleMode == null ? plan.battleMode() : requestedBattleMode,
                at, null, roster.size(), 0, 0, null, null);
        insertRunning(exercise, at);
        units.replaceByExerciseId(exercise.id(), roster.stream()
                .map(entry -> snapshot(exercise.id(), entry))
                .toList());
        sessions.onExerciseStarted(exercise);
        return exercise;
    }

    /** 落演习行并占用本场运行权；唯一索引冲突即「已有在跑场次」，翻成契约的 409。 */
    private void insertRunning(Exercise exercise, Instant at) {
        try {
            exercises.insert(exercise);
        } catch (DuplicateKeyException alreadyRunning) {
            // 只捕 DuplicateKeyException 而不是范围更大的 DataIntegrityViolationException：
            // 本条 insert 会撞的唯一索引只有 uk_exercise_running（主键是新生成的 UUIDv7）。
            throw new BusinessException(ApiErrorCode.EXERCISE_ALREADY_RUNNING,
                    "已有演习进行中，无法开演（本次请求的想定：" + exercise.planId() + "）");
        }
        exercises.claim(exercise.id(), instanceProperties.instanceId(), at);
    }

    /**
     * 开演快照一行。终局四列（{@code finalStatus} / {@code deadAt} / {@code kills} /
     * {@code hitsDealt}）留空，停演时由态势状态机冻结——与
     * {@link ExerciseUnitRepository#insert(ExerciseUnit)} 的口径一致。
     */
    private static ExerciseUnit snapshot(UUID exerciseId, RosterEntry entry) {
        return new ExerciseUnit(0L, exerciseId, entry.vestNum(), entry.name(), entry.team(),
                null, null, 0, 0, null, null);
    }

    /**
     * 停演（契约 {@code stopExercise}）：写终局 → 冻结收尾。
     *
     * <p><b>两步顺序不可换</b>：{@link SituationSessionBinder#onExerciseStopping} 先把全体单位终局
     * 写进 {@code t_exercise_unit}，而 {@link #freezeAndClose} 读的正是库里的 {@code final_status}
     * （{@code countDeadByExerciseId}）——先收尾就会把阵亡数记成 0，且复盘表上再也看不出差别。
     *
     * <p>两步都在同一个事务里：终局写了而收尾没做，会留下一场「已冻结但仍在进行中」的演习，
     * 下次启动自愈又按崩溃口径覆盖一遍。
     *
     * <p><b>尚未接线</b>：契约里的 7B 演习结束帧属网关下行；本方法只管库与内存态。
     * 复盘的落库与 {@code replayId} 归 M5，故调用方目前拿不到非空 replayId。
     *
     * @return 停演结果；{@code endedAt} 取本次请求时刻
     * @throws BusinessException {@code EXERCISE_NOT_RUNNING}(409) 当前没有演习进行中
     */
    @Transactional
    public StopOutcome stopExercise() {
        Instant at = Instant.now();
        UUID exerciseId = exercises.findRunning()
                .map(Exercise::id)
                .orElseThrow(ExerciseLifecycleService::notRunning);

        sessions.onExerciseStopping(exerciseId);

        freezeAndClose(exerciseId, ExerciseEndReason.NORMAL, STOPPED_COMMAND_FAIL_REASON, at)
                .orElseThrow(ExerciseLifecycleService::notRunning);

        return new StopOutcome(exerciseId, at, ExerciseEndReason.NORMAL);
    }

    /** 正常停演时作废残留指令的原因（与自愈的 {@code INSTANCE_RESTART} 相区别）。 */
    private static final String STOPPED_COMMAND_FAIL_REASON = "EXERCISE_STOPPED";

    private static BusinessException notRunning() {
        return new BusinessException(ApiErrorCode.EXERCISE_NOT_RUNNING, "当前没有演习进行中，无法停演");
    }

    /**
     * 一次停演的结果。
     *
     * @param exerciseId 被停掉的演习
     * @param endedAt    结束时刻（本次请求时刻，也是写进 {@code ended_at} 的值）
     * @param endReason  结束原因；走本方法恒为 {@link ExerciseEndReason#NORMAL}
     */
    public record StopOutcome(UUID exerciseId, Instant endedAt, ExerciseEndReason endReason) {
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
