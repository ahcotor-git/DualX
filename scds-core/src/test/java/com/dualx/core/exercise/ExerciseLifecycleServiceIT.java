package com.dualx.core.exercise;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseCommand;
import com.dualx.core.domain.ExerciseCommandState;
import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.domain.ExerciseLogEntry;
import com.dualx.core.domain.ExerciseLogType;
import com.dualx.core.domain.ExerciseUnit;
import com.dualx.core.domain.HitEvent;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;
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
import com.dualx.core.support.UuidV7;
import com.dualx.protocol.HitPart;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class ExerciseLifecycleServiceIT {

    @Autowired
    private ExerciseLifecycleService lifecycle;

    @Autowired
    private ExerciseRepository exercises;

    @Autowired
    private ExerciseUnitRepository units;

    @Autowired
    private HitEventRepository hits;

    @Autowired
    private ExerciseCommandRepository commands;

    @Autowired
    private ExerciseLogRepository logs;

    @Autowired
    private PlanRepository plans;

    @Autowired
    private PlanRosterRepository planRoster;

    /** 测试上下文里的桩（装配点在 {@code scds-app}）；这里只验「装载被转调」，真装载见 SituationStateIT。 */
    @Autowired
    private SituationSessionBinder sessions;

    @Autowired
    private JdbcClient jdbc;

    /** 测试上下文里的马甲串口链路桩（装配点 {@code TestCoreApplication}），快照由 {@link #openVestLink()} 声明。 */
    @Autowired
    private DeviceLinkAdmin links;

    private static final Instant START = Instant.parse("2026-10-20T08:00:00.000Z");
    private static final Instant STOP_AT = Instant.parse("2026-10-20T09:00:00.000Z");

    /**
     * 开演前要求「马甲串口链路已开」（契约 {@code startExercise} 的 409 {@code DEVICE_LINK_NOT_OPEN}）。
     *
     * <p>上下文里的端口是 Mockito 桩，{@code snapshot()} 默认空列表即「未开」，故每个用例前显式声明一份
     * 已开快照；需要验「未开」的用例在自身方法内改写它。这是 core 侧唯一能摆出「链路已开」的地方——
     * 真装配（仿真适配器 + 真实 HTTP）由 {@code scds-app} 的 IT 覆盖。
     */
    @BeforeEach
    void openVestLink() {
        when(links.snapshot()).thenReturn(List.of(
                new LinkStatus(LinkKind.SERIAL_VEST, LinkState.OPEN, "COM7", 115200, null,
                        null, 0, 0, 0, null)));
    }

    private UUID insertRunning(String planName) {
        UUID id = UuidV7.generate();
        exercises.insert(new Exercise(id, UuidV7.generate(), planName, null, BattleMode.TRAIN,
                START, null, 3, 0, 0, null, null));
        return id;
    }

    /** 造一场进行中的演习，引用指定想定——用来撞 {@code uk_exercise_running} 唯一索引。 */
    private UUID seedRunningExerciseReferencing(UUID planId) {
        UUID id = UuidV7.generate();
        exercises.insert(new Exercise(id, planId, "it_life_已在演", null, BattleMode.TRAIN,
                START, null, 1, 0, 0, null, null));
        return id;
    }

    private static ExerciseUnit unit(UUID exerciseId, int vestNum, String name, Team team) {
        return new ExerciseUnit(0L, exerciseId, vestNum, name, team, null, null, 0, 0, null, null);
    }

    private static HitEvent hit(UUID exerciseId, int hitVestNum, int shooterNum, boolean lethal) {
        return new HitEvent(0L, exerciseId, START, 1000, hitVestNum, shooterNum, true,
                null, HitPart.CHEST, lethal, null);
    }

    private static ExerciseCommand queued(UUID exerciseId, int vestNum, String command) {
        return new ExerciseCommand(0L, exerciseId, vestNum, command, ExerciseCommandState.PENDING,
                null, null, "指挥员", START, null, null, null);
    }

    /** 直接读列：{@code Exercise} 领域记录尚未映射 {@code end_reason}。 */
    private String endReasonOf(UUID exerciseId) {
        return jdbc.sql("SELECT end_reason FROM t_exercise WHERE id = :id")
                .param("id", exerciseId.toString())
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /**
     * 按马甲编号取队列主键。不用 {@code findPending} 的下标：那按 {@code queued_at} 排序，
     * 而同一测试里几条指令的入队时刻相同，下标顺序在 SQL 层无保证。
     */
    private long idOfVest(UUID exerciseId, int vestNum) {
        return jdbc.sql("SELECT id FROM t_exercise_command WHERE exercise_id = :exerciseId AND vest_num = :vestNum")
                .param("exerciseId", exerciseId.toString())
                .param("vestNum", vestNum)
                .query(Long.class)
                .single();
    }

    /** 造一份想定；{@code startExercise} 只读它的 name / mapId / battleMode 三项。 */
    private UUID seedPlan(String name) {
        UUID id = UuidV7.generate();
        plans.insert(new Plan(id, name, null, BattleMode.TRAIN, null, null, null));
        return id;
    }

    /** 落想定编成。台账（{@code t_vest}）校验属 PlanService，不在这里复现。 */
    private void seedRoster(UUID planId, int... vestNums) {
        planRoster.replaceByPlanId(planId, Arrays.stream(vestNums)
                .mapToObj(n -> new RosterEntry(0L, planId, n, "马甲" + n, Team.RED, 100, 30, 30,
                        PollMode.AUTO, null, null))
                .toList());
    }

    private String ownerInstanceOf(UUID exerciseId) {
        return jdbc.sql("SELECT owner_instance FROM t_exercise WHERE id = :id")
                .param("id", exerciseId.toString())
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ApiErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(expected));
    }

    // ── 开演 ────────────────────────────────────────────────────────────────

    @Test
    void startExercise_createsTheRowClaimsOwnershipAndSnapshotsTheRoster() {
        UUID planId = seedPlan("it_life_城市攻防开演");
        seedRoster(planId, 1003, 1001, 1002);

        Exercise started = lifecycle.startExercise(planId, null);

        // 演习行取的是想定快照，模式沿用想定（请求体给 null）
        Exercise stored = exercises.findById(started.id()).orElseThrow();
        assertThat(stored.planId()).isEqualTo(planId);
        assertThat(stored.planName()).isEqualTo("it_life_城市攻防开演");
        assertThat(stored.battleMode()).isEqualTo(BattleMode.TRAIN);
        assertThat(stored.running()).isTrue();
        assertThat(stored.rosterCount()).isEqualTo(3);
        assertThat(exercises.findRunning().orElseThrow().id()).isEqualTo(started.id());

        // 运行权：owner_instance 必须落在本实例上，否则自愈与心跳都认不出这行归谁管
        assertThat(ownerInstanceOf(started.id())).isNotBlank();

        // 编成快照：终局四列留空，由停演时的态势状态机冻结
        assertThat(units.findByExerciseId(started.id()))
                .extracting(ExerciseUnit::vestNum).containsExactlyInAnyOrder(1001, 1002, 1003);
        assertThat(units.findByExerciseId(started.id()))
                .allSatisfy(unit -> assertThat(unit.finalStatus()).isNull());

        verify(sessions).onExerciseStarted(started);
    }

    @Test
    void startExercise_withRequestedBattleMode_overridesThePlans() {
        UUID planId = seedPlan("it_life_模式覆盖");
        seedRoster(planId, 1001);

        Exercise started = lifecycle.startExercise(planId, BattleMode.BATTLE);

        assertThat(exercises.findById(started.id()).orElseThrow().battleMode())
                .isEqualTo(BattleMode.BATTLE);
    }

    @Test
    void startExercise_ofMissingPlan_isExercisePlanNotFound() {
        assertCode(() -> lifecycle.startExercise(UuidV7.generate(), null),
                ApiErrorCode.EXERCISE_PLAN_NOT_FOUND);
    }

    @Test
    void startExercise_withEmptyRoster_isRejectedWithoutCreatingAnything() {
        UUID planId = seedPlan("it_life_空编成");

        assertCode(() -> lifecycle.startExercise(planId, null), ApiErrorCode.EXERCISE_ROSTER_EMPTY);

        // 拒绝发生在插库之前：不能留下一场没人、又占着唯一索引的「进行中」演习
        assertThat(exercises.findRunning()).isEmpty();
    }

    @Test
    void startExercise_withVestLinkClosed_isDeviceLinkNotOpen() {
        UUID planId = seedPlan("it_life_链路未开");
        seedRoster(planId, 1001);
        // 覆盖 {@link #openVestLink()} 的默认快照：链路存在但未开
        when(links.snapshot()).thenReturn(List.of(
                new LinkStatus(LinkKind.SERIAL_VEST, LinkState.CLOSED, "COM7", 115200, null,
                        null, 0, 0, 0, null)));

        assertCode(() -> lifecycle.startExercise(planId, null), ApiErrorCode.DEVICE_LINK_NOT_OPEN);

        // 判据排在插库之前：想定与编成都合法，但被前置条件拒下，同样不留半启动态
        assertThat(exercises.findRunning()).isEmpty();
    }

    @Test
    void startExercise_whileAnotherIsRunning_isAlreadyRunning() {
        UUID runningPlan = seedPlan("it_life_已在演");
        UUID runningId = seedRunningExerciseReferencing(runningPlan);
        UUID planId = seedPlan("it_life_再开演");
        seedRoster(planId, 1001);

        assertCode(() -> lifecycle.startExercise(planId, null), ApiErrorCode.EXERCISE_ALREADY_RUNNING);

        // 被拒的请求不留痕：唯一索引挡住的是插入本身，快照与内存态都没走到，原场次不受影响
        assertThat(exercises.findRunning().orElseThrow().id()).isEqualTo(runningId);
        assertThat(units.findByExerciseId(runningId)).isEmpty();
    }

    // ── 停演 ────────────────────────────────────────────────────────────────

    @Test
    void stopExercise_withNothingRunning_isExerciseNotRunning() {
        assertCode(lifecycle::stopExercise, ApiErrorCode.EXERCISE_NOT_RUNNING);
    }

    @Test
    void stopExercise_writesFinalStateBeforeFreezingTheCounts() {
        UUID planId = seedPlan("it_life_停演");
        seedRoster(planId, 1001, 1002);
        Exercise started = lifecycle.startExercise(planId, null);
        commands.enqueue(queued(started.id(), 1001, "RELOAD"));

        // 真 binder 的职责就是把全体单位终局写进 t_exercise_unit；桩在这里照做，于是
        // 「停演后 t_exercise.dead_count 是否为 1」就成了两步顺序的判据——先收尾后写终局会读到 0。
        doAnswer(invocation -> {
            units.freezeOutcome(started.id(), 1001, VestStatus.DIE, START, 2, 3);
            return 1;
        }).when(sessions).onExerciseStopping(any(UUID.class));

        ExerciseLifecycleService.StopOutcome outcome = lifecycle.stopExercise();

        assertThat(outcome.exerciseId()).isEqualTo(started.id());
        assertThat(outcome.endReason()).isEqualTo(ExerciseEndReason.NORMAL);

        Exercise ended = exercises.findById(started.id()).orElseThrow();
        assertThat(ended.running()).isFalse();
        assertThat(ended.endedAt()).isCloseTo(outcome.endedAt(), within(1, ChronoUnit.SECONDS));
        assertThat(ended.rosterCount()).isEqualTo(2);
        assertThat(ended.hitCount()).isZero();
        assertThat(ended.deadCount()).isEqualTo(1);
        assertThat(endReasonOf(started.id())).isEqualTo("NORMAL");

        // 运行位让出来后，下一场才开得起来
        assertThat(exercises.findRunning()).isEmpty();
        // 残留指令随本场一并作废，不会漏给下一场（原因与自愈的 INSTANCE_RESTART 相区别）
        assertThat(commands.findResidualByExerciseId(started.id())).isEmpty();

        verify(sessions).onExerciseStopping(started.id());
    }

    @Test
    void freezeAndClose_freezesAllThreeCountsAndTheReason() {
        UUID id = insertRunning("it_life_城市攻防");
        units.insert(unit(id, 1001, "甲", Team.RED));
        units.insert(unit(id, 1002, "乙", Team.RED));
        units.insert(unit(id, 1003, "丙", Team.BLUE));
        hits.insert(hit(id, 1001, 2001, true));
        hits.insert(hit(id, 1002, 2001, false));
        hits.insert(hit(id, 1002, 2002, true));
        // 态势状态机本该在停演时冻结每行终局；这里只模拟「已冻结一人阵亡」
        units.freezeOutcome(id, 1001, VestStatus.DIE, STOP_AT, 1, 2);

        ExerciseLifecycleService.CloseOutcome outcome = lifecycle
                .freezeAndClose(id, ExerciseEndReason.CRASH_RECOVERED, "INSTANCE_RESTART", STOP_AT)
                .orElseThrow();

        assertThat(outcome.rosterCount()).isEqualTo(3);
        assertThat(outcome.hitCount()).isEqualTo(3);
        assertThat(outcome.deadCount()).isEqualTo(1);
        assertThat(outcome.residualCommands()).isZero();

        Exercise ended = exercises.findById(id).orElseThrow();
        assertThat(ended.running()).isFalse();
        assertThat(ended.endedAt()).isEqualTo(STOP_AT);
        assertThat(ended.rosterCount()).isEqualTo(3);
        assertThat(ended.hitCount()).isEqualTo(3);
        assertThat(ended.deadCount()).isEqualTo(1);
        assertThat(endReasonOf(id)).isEqualTo("CRASH_RECOVERED");
    }

    @Test
    void freezeAndClose_withoutStateFrozenOutcome_recordsZeroDeathsRatherThanGuessing() {
        UUID id = insertRunning("it_life_未冻结终局");
        units.insert(unit(id, 1001, "甲", Team.RED));
        units.insert(unit(id, 1002, "乙", Team.RED));
        // 有致命命中，但没有任何一行终局被冻结——崩溃就是这个样子
        hits.insert(hit(id, 1001, 2001, true));

        ExerciseLifecycleService.CloseOutcome outcome = lifecycle
                .freezeAndClose(id, ExerciseEndReason.CRASH_RECOVERED, "INSTANCE_RESTART", STOP_AT)
                .orElseThrow();

        assertThat(outcome.hitCount()).isEqualTo(1);
        assertThat(outcome.deadCount()).isZero();
    }

    @Test
    void freezeAndClose_expiresResidualCommandsAndWritesSystemEvidence() {
        UUID id = insertRunning("it_life_残留指令");
        commands.enqueue(queued(id, 1001, "DIE"));
        commands.enqueue(queued(id, 1002, "RELOAD"));
        commands.enqueue(queued(id, 1003, "CONFIG"));
        long sentId = idOfVest(id, 1002);
        long ackedId = idOfVest(id, 1003);
        commands.markSent(sentId, STOP_AT, "0009ff03e9a1affa");
        commands.markSent(ackedId, STOP_AT, "0009ff03e9a2affa");
        commands.markAcked(ackedId, STOP_AT);

        ExerciseLifecycleService.CloseOutcome outcome = lifecycle
                .freezeAndClose(id, ExerciseEndReason.CRASH_RECOVERED, "INSTANCE_RESTART", STOP_AT)
                .orElseThrow();

        // 在途的两条被作废并存证；已拿到回执的那条不改写、但随场次一并清出队列
        assertThat(outcome.residualCommands()).isEqualTo(2);
        assertThat(outcome.deletedCommands()).isEqualTo(3);
        assertThat(commands.findResidualByExerciseId(id)).isEmpty();
        assertThat(commands.findById(ackedId)).isEmpty();

        List<ExerciseLogEntry> evidence = logs.findLatest(id, 10);
        assertThat(evidence).hasSize(2);
        assertThat(evidence).allSatisfy(entry -> assertThat(entry.logType()).isEqualTo(ExerciseLogType.SYSTEM));
        assertThat(evidence).extracting(ExerciseLogEntry::vestNum).containsExactly(1001, 1002);
        assertThat(evidence).extracting(ExerciseLogEntry::downlinkHex).containsExactly(null, "0009ff03e9a1affa");
        assertThat(evidence.get(1).detail())
                .contains("id=" + sentId)
                .contains("RELOAD")
                .contains("原状态SENT")
                .contains("INSTANCE_RESTART");
    }

    @Test
    void freezeAndClose_whenAlreadyEnded_isNoOp() {
        UUID id = insertRunning("it_life_重复收尾");
        commands.enqueue(queued(id, 1001, "DIE"));
        lifecycle.freezeAndClose(id, ExerciseEndReason.NORMAL, "EXERCISE_STOPPED", STOP_AT).orElseThrow();

        // 收尾之后又冒出一条在途指令（模拟收尾后才入队的竞态）
        commands.enqueue(queued(id, 1002, "RELOAD"));
        long lateId = commands.findResidualByExerciseId(id).getFirst().id();

        assertThat(lifecycle.freezeAndClose(id, ExerciseEndReason.CRASH_RECOVERED, "INSTANCE_RESTART", STOP_AT))
                .isEmpty();

        // 这不是本场的收尾窗口了：原因不被改写，残留指令不被误清
        assertThat(endReasonOf(id)).isEqualTo("NORMAL");
        assertThat(commands.findById(lateId)).isPresent();
        assertThat(logs.countByExerciseId(id)).isEqualTo(1);
    }

    @Test
    void freezeAndClose_unknownExercise_isNoOp() {
        assertThat(lifecycle.freezeAndClose(UuidV7.generate(), ExerciseEndReason.NORMAL,
                "EXERCISE_STOPPED", STOP_AT)).isEmpty();
    }
}
