package com.dualx.core.exercise;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseCommand;
import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.domain.ExerciseLogEntry;
import com.dualx.core.domain.ExerciseLogType;
import com.dualx.core.domain.ExerciseUnit;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.Plan;
import com.dualx.core.domain.PollMode;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.DeviceLinkAdmin;
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
import com.dualx.protocol.Hex;
import com.dualx.protocol.serial.DownlinkCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>硬件下行已接线</b>：{@link #startExercise} 在库与内存态写定之后，逐台下发 14B 轮询配置帧、
 * 再下发 7B 演习开始帧；{@link #stopExercise} 在冻结收尾之后下发 7B 演习结束帧。物理副作用无法由
 * JDBC 撤回，故按用户裁定「只留痕、不回滚」：链路未开时不下发（只 WARN），真发送失败写一条
 * {@code SYSTEM} 存证行后继续——绝不因硬件影响已开演/已停演的场次与响应码。
 *
 * <p><b>仍未落地</b>：开演前的「链路就绪」硬前置（设计 05 §4.7 与 D06 的 409
 * {@code DEVICE_LINK_NOT_OPEN}）与同库单实例保护（L6）——两者都会牵动既有集成测试，单列后续任务。
 */
@Service
public class ExerciseLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ExerciseLifecycleService.class);

    /**
     * 「全体」下行（7B 开始/结束帧）用的占位马甲编号。
     *
     * <p>与 {@code CommandService} 同口径：帧内目标字段固定 {@code FF FF}，编号不参与编码；传 0 只为给
     * {@code sendVestCommand} 一个占位，真实马甲编号下界为 1，不会与任何真实编号混淆。写日志时按
     * 「系统级日志」记 {@code NULL}。
     */
    private static final int BROADCAST_VEST_NUM = 0;

    private final ExerciseRepository exercises;
    private final ExerciseUnitRepository units;
    private final HitEventRepository hits;
    private final ExerciseCommandRepository commands;
    private final ExerciseLogRepository logs;
    private final PlanRepository plans;
    private final PlanRosterRepository planRoster;
    private final SituationSessionBinder sessions;
    private final DeviceLinkAdmin links;
    private final InstanceProperties instanceProperties;

    /** 一次性就绪位，见类 javadoc「恢复完成门禁」；初值 {@code false} 即「尚未自愈」。 */
    private final AtomicBoolean recovered = new AtomicBoolean(false);

    public ExerciseLifecycleService(ExerciseRepository exercises, ExerciseUnitRepository units,
                                    HitEventRepository hits, ExerciseCommandRepository commands,
                                    ExerciseLogRepository logs, PlanRepository plans,
                                    PlanRosterRepository planRoster, SituationSessionBinder sessions,
                                    DeviceLinkAdmin links, InstanceProperties instanceProperties) {
        this.exercises = exercises;
        this.units = units;
        this.hits = hits;
        this.commands = commands;
        this.logs = logs;
        this.plans = plans;
        this.planRoster = planRoster;
        this.sessions = sessions;
        this.links = links;
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
     * 当前演习的日志流水（契约 {@code listCurrentExerciseLogs}）：{@code seq > sinceSeq} 的条目，
     * 按 seq 升序（即时间顺序），至多 {@code limit} 条。
     *
     * <p><b>游标口径</b>：{@code sinceSeq} 为 {@code null} 表示「不传」，契约要求返回<b>最新一页</b>
     * ——取 seq 最大的 limit 条再升序，故走 {@link ExerciseLogRepository#findLatest}；非 null 走
     * {@link ExerciseLogRepository#findByExerciseIdSince}。库里 {@code seq} 是**全局自增**（不按场次
     * 分段），故换了场次游标也不回退；客户端把本批末条 {@code seq} 作为下次的 {@code sinceSeq}。</p>
     *
     * <p><b>无演习进行中返回空列表，不查库</b>：与态势读侧 {@code SituationQuery.hitsSince} 同一取向
     * （本端点的契约响应只有 200，没有 404，故不编造场次也不报错）。</p>
     */
    public List<ExerciseLogEntry> currentLogs(Long sinceSeq, int limit) {
        return currentExercise()
                .map(running -> sinceSeq == null
                        ? logs.findLatest(running.id(), limit)
                        : logs.findByExerciseIdSince(running.id(), sinceSeq, limit))
                .orElse(List.of());
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
     * （{@link SituationSessionBinder#onExerciseStarted}）放在库写入之后：它改的是内存态。
     *
     * <p><b>硬件下行排在最末（契约步骤 ③④）</b>：库与内存态写定之后才逐台下发 14B、再下发 7B。
     * 这一步只留痕、不回滚——见类 javadoc「硬件下行已接线」。因此本方法的异常面仍是纯业务面，
     * 不会因为现场没有开串口链路而把开演变成失败。
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
        sendStartDownlink(exercise, roster, at);
        return exercise;
    }

    /**
     * 开演的硬件下行（契约 {@code startExercise} 步骤 ③④）：逐台下发 14B 轮询配置帧，再下发 7B 开始帧。
     *
     * <p><b>链路未开就整段不下发，只 WARN</b>：链路未开时 {@code SerialVestLink.send} 会把帧放进无人
     * 消费的发送队列并回报成功——那是假成功（与 {@code CommandService} 同一判据）。此时既不写日志
     * 也不报错：它是**前置条件**问题，按 D06 应以 409 在开演前拦下（本段未做，见类 javadoc），
     * 而不是在开演后往日志表里灌噪声。
     */
    private void sendStartDownlink(Exercise exercise, List<RosterEntry> roster, Instant at) {
        if (!vestLinkOpen()) {
            log.warn("开演下行跳过：马甲串口链路未开，14B 轮询配置帧与 7B 开始帧均未下发"
                    + "（演习已正常开演）：exercise={}", exercise.id());
            return;
        }
        for (RosterEntry entry : roster) {
            sendOrRecord(exercise.id(), entry.vestNum(), "POLL_CONFIG",
                    DownlinkCommands.pollConfig(entry.vestNum(), modeCode(entry.mode()),
                            entry.hp(), entry.ammo1(), entry.ammo2(), entry.team()),
                    "开演下发轮询配置帧：马甲" + entry.vestNum(), at);
        }
        sendOrRecord(exercise.id(), BROADCAST_VEST_NUM, "START_BATTLE",
                DownlinkCommands.startBattle(battleModeCode(exercise.battleMode())),
                "开演下发演习开始帧", at);
    }

    /**
     * 下发一条开演/停演的帧；发送失败只 WARN + 写一条 {@code SYSTEM} 存证行，绝不抛出。
     *
     * <p><b>为什么吞掉一切 {@link RuntimeException}</b>：调用发生在库与内存态写定之后，此刻抛出只会
     * 回滚一场事实上已经开演（或已经停演）的场次，留下一堆已发到硬件的字节与一张对不上的库——正是
     * D11 要禁止的「半启动态」。失败必须留痕，但留痕不能改变事务结局（用户裁定）。
     */
    private void sendOrRecord(UUID exerciseId, int vestNum, String command, byte[] frame,
                              String detail, Instant at) {
        String hex = Hex.encode(frame);
        try {
            links.sendVestCommand(vestNum, frame);
        } catch (RuntimeException failure) {
            log.warn("{} 下发失败（演习状态不受影响）：exercise={} vest={} command={} hex={}",
                    detail, exerciseId, vestNum, command, hex, failure);
            logs.append(new ExerciseLogEntry(0L, exerciseId, at, ExerciseLogType.SYSTEM,
                    vestNum == BROADCAST_VEST_NUM ? null : vestNum, null,
                    detail + "失败：" + failure.getMessage(), hex));
        }
    }

    /** {@link PollMode} → 14B 帧的 mode 字节。显式映射，不依赖枚举声明序（与 {@code CommandService} 同口径）。 */
    private static int modeCode(PollMode mode) {
        return mode == PollMode.MANUAL ? DownlinkCommands.MODE_MANUAL : DownlinkCommands.MODE_AUTO;
    }

    /** {@link BattleMode} → 7B 开始帧的 battleMode 字节。显式映射，不依赖枚举声明序。 */
    private static int battleModeCode(BattleMode mode) {
        return mode == BattleMode.BATTLE ? DownlinkCommands.BATTLE_MODE_BATTLE : DownlinkCommands.BATTLE_MODE_TRAIN;
    }

    /**
     * 马甲串口链路是否已开（与 {@code CommandService.vestLinkOpen} 同口径）。
     *
     * <p>链路非 {@link LinkState#OPEN} 即视为不可下发。core 集成测试上下文里该端口是 Mockito 桩，
     * {@code snapshot()} 返回空列表，故「未开」分支正是默认路径。
     */
    private boolean vestLinkOpen() {
        return links.snapshot().stream()
                .filter(status -> status.kind() == LinkKind.SERIAL_VEST)
                .anyMatch(status -> status.state() == LinkState.OPEN);
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
     * <p><b>7B 结束帧排在冻结之后（契约步骤 ③）</b>：先把终局与 {@code ended_at} 写定，再下发结束帧。
     * 顺序反过来的话，硬件已停而库里还是一场「进行中」，下一次开演会把它当残留处理。发送同样只
     * 留痕、不回滚——见类 javadoc「硬件下行已接线」。
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

        sendFinishDownlink(exerciseId, at);

        return new StopOutcome(exerciseId, at, ExerciseEndReason.NORMAL);
    }

    /**
     * 停演的硬件下行（契约 {@code stopExercise} 步骤 ③）：向全体下发 7B 演习结束帧。
     *
     * <p>与开演同一口径：链路未开时整段不下发（只 WARN），发送失败只 WARN + 写 {@code SYSTEM}
     * 存证行。停演本身已落库完成，硬件是否收到不改变这次停演的事实。
     */
    private void sendFinishDownlink(UUID exerciseId, Instant at) {
        if (!vestLinkOpen()) {
            log.warn("停演下行跳过：马甲串口链路未开，7B 结束帧未下发（演习已正常停演）：exercise={}", exerciseId);
            return;
        }
        sendOrRecord(exerciseId, BROADCAST_VEST_NUM, "FINISH_BATTLE",
                DownlinkCommands.finishBattle(), "停演下发演习结束帧", at);
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
