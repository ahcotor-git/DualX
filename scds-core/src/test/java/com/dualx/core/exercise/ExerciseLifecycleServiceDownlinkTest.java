package com.dualx.core.exercise;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.domain.ExerciseLogEntry;
import com.dualx.core.domain.ExerciseLogType;
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
import com.dualx.core.support.InstanceProperties;
import com.dualx.core.support.UuidV7;
import com.dualx.protocol.Hex;
import com.dualx.protocol.Team;
import com.dualx.protocol.serial.DownlinkCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 开演/停演的**硬件下行**（契约 {@code startExercise} 步骤 ③④、{@code stopExercise} 步骤 ③）的单元面：
 * 链路就绪与否、发了什么字节、顺序对不对、失败留不留痕、以及「失败绝不改变事务结局」。
 *
 * <p><b>为什么不写成 {@code @SpringBootTest}</b>：core 的测试上下文里 {@code DeviceLinkAdmin} 是按类共享的
 * Mockito 桩，一个上下文摆不出「链路已开」「链路未开」与「发送失败」三条互斥路径。这里直接控制端口，才能
 * 把三条路径都摆出来；真装配（仿真适配器 + 真实 HTTP）由 {@code scds-app} 的 {@code ExerciseDownlinkHttpIT}
 * 覆盖。</p>
 *
 * <p><b>为什么断言的是字节而不是调用次数</b>：14B 帧的 mode/hp/弹药/队伍一旦映射错位，现场看到的
 * 是「马甲收得到帧但配置全错」，比不发更糟。故用 {@link DownlinkCommands} 现编码一份期望值逐字节比对，
 * 而不是只数调用次数。</p>
 */
class ExerciseLifecycleServiceDownlinkTest {

    private final ExerciseRepository exercises = mock(ExerciseRepository.class);
    private final ExerciseUnitRepository units = mock(ExerciseUnitRepository.class);
    private final HitEventRepository hits = mock(HitEventRepository.class);
    private final ExerciseCommandRepository commands = mock(ExerciseCommandRepository.class);
    private final ExerciseLogRepository logs = mock(ExerciseLogRepository.class);
    private final PlanRepository plans = mock(PlanRepository.class);
    private final PlanRosterRepository planRoster = mock(PlanRosterRepository.class);
    private final SituationSessionBinder sessions = mock(SituationSessionBinder.class);
    private final DeviceLinkAdmin links = mock(DeviceLinkAdmin.class);

    private final UUID planId = UuidV7.generate();
    private final List<RosterEntry> roster = List.of(
            rosterEntry(1, "甲", Team.RED, 100, 30, 30, PollMode.AUTO),
            rosterEntry(2, "乙", Team.BLUE, 80, 15, 0, PollMode.MANUAL));

    private ExerciseLifecycleService lifecycle;

    private static RosterEntry rosterEntry(int vestNum, String name, Team team, int hp,
                                           int ammo1, int ammo2, PollMode mode) {
        return new RosterEntry(0L, UuidV7.generate(), vestNum, name, team, hp, ammo1, ammo2,
                mode, null, null);
    }

    @BeforeEach
    void setUp() {
        lifecycle = new ExerciseLifecycleService(exercises, units, hits, commands, logs, plans,
                planRoster, sessions, links, new InstanceProperties(null));
        lifecycle.markRecovered();
        when(plans.findById(planId)).thenReturn(Optional.of(
                new Plan(planId, "下行单测想定", null, BattleMode.TRAIN, null, null, null)));
        when(planRoster.findByPlanId(planId)).thenReturn(roster);
    }

    /** 造一个「马甲串口链路已开」的快照；其余两条按契约恒有、状态随意。 */
    private void vestLinkOpen() {
        when(links.snapshot()).thenReturn(List.of(
                new LinkStatus(LinkKind.SERIAL_VEST, LinkState.OPEN, "COM7", 115200, null,
                        null, 0, 0, 0, null),
                new LinkStatus(LinkKind.UWB_TCP_SERVER, LinkState.CLOSED, null, null, null,
                        null, 0, 0, 0, null),
                new LinkStatus(LinkKind.UWB_SERIAL, LinkState.CLOSED, null, null, null,
                        null, 0, 0, 0, null)));
    }

    @Test
    void startSendsPollConfigPerVestThenStartBattleInOrder() {
        vestLinkOpen();

        Exercise started = lifecycle.startExercise(planId, BattleMode.BATTLE);

        // 顺序即语义：逐台配置先到，开始帧后到；反之马甲会以默认配置进入演习
        InOrder inOrder = inOrder(links);
        inOrder.verify(links).sendVestCommand(1, DownlinkCommands.pollConfig(
                1, DownlinkCommands.MODE_AUTO, 100, 30, 30, Team.RED));
        inOrder.verify(links).sendVestCommand(2, DownlinkCommands.pollConfig(
                2, DownlinkCommands.MODE_MANUAL, 80, 15, 0, Team.BLUE));
        inOrder.verify(links).sendVestCommand(0, DownlinkCommands.startBattle(
                DownlinkCommands.BATTLE_MODE_BATTLE));
        // 逐台各一次 + 开始帧一次，别无他发（顺序由上面的 InOrder 保证）
        verify(links, times(3)).sendVestCommand(anyInt(), any());

        // 请求体的 battleMode 覆盖想定的 TRAIN，7B 帧里带的必须是覆盖后的值
        assertThat(started.battleMode()).isEqualTo(BattleMode.BATTLE);
        // 链路已开且全部发送成功：不留任何存证
        verifyNoInteractions(logs);
    }

    @Test
    void startBattleFrameFollowsTheRequestedMode() {
        vestLinkOpen();

        lifecycle.startExercise(planId, null);

        // 不传 battleMode 时沿用想定的 TRAIN
        verify(links).sendVestCommand(0, DownlinkCommands.startBattle(DownlinkCommands.BATTLE_MODE_TRAIN));
    }

    @Test
    void startDownlinkFailureLeavesEvidenceAndStillStartsTheExercise() {
        vestLinkOpen();
        doThrow(new BusinessException(ApiErrorCode.COMMAND_QUEUE_FULL, "串口发送队列已满"))
                .when(links).sendVestCommand(anyInt(), any());

        AtomicReference<Exercise> started = new AtomicReference<>();
        assertThatCode(() -> started.set(lifecycle.startExercise(planId, BattleMode.TRAIN)))
                .as("硬件失败不得把开演变成异常——那会回滚一场事实上已经开始、字节也已经发出去的演习")
                .doesNotThrowAnyException();
        // 开演行照常落库：硬件失败不影响库写入（这里直接看返回行，不依赖 mock 的 findRunning）
        assertThat(started.get()).as("开演的库写入不受硬件失败影响").isNotNull();
        verify(exercises).insert(any(Exercise.class));

        // 三条帧全部失败 → 三条 SYSTEM 存证；每条都能脱离队列独立读懂（含原帧 hex）
        ArgumentCaptor<ExerciseLogEntry> captured = ArgumentCaptor.forClass(ExerciseLogEntry.class);
        verify(logs, times(3)).append(captured.capture());
        List<ExerciseLogEntry> evidence = captured.getAllValues();
        assertThat(evidence).extracting(ExerciseLogEntry::logType).containsOnly(ExerciseLogType.SYSTEM);
        assertThat(evidence).extracting(ExerciseLogEntry::vestNum).containsExactly(1, 2, null);
        assertThat(evidence).extracting(ExerciseLogEntry::operatorName).containsOnlyNulls();
        assertThat(evidence.get(0).downlinkHex()).isEqualTo(Hex.encode(DownlinkCommands.pollConfig(
                1, DownlinkCommands.MODE_AUTO, 100, 30, 30, Team.RED)));
        assertThat(evidence.get(0).detail()).contains("失败").contains("马甲1");
        assertThat(evidence.get(2).downlinkHex()).isEqualTo(Hex.encode(
                DownlinkCommands.startBattle(DownlinkCommands.BATTLE_MODE_TRAIN)));
    }

    @Test
    void startIsRejectedWith409WithoutSendingOrLoggingWhenVestLinkIsClosed() {
        when(links.snapshot()).thenReturn(List.of(
                new LinkStatus(LinkKind.SERIAL_VEST, LinkState.CLOSED, "COM7", 115200, null,
                        null, 0, 0, 0, null)));

        // 链路未开是开演的**硬前置**（设计 05 §4.7 / 决策 D06）：409 在写库前拦下，而不是「开演成功但静默跳过」
        assertThatThrownBy(() -> lifecycle.startExercise(planId, BattleMode.TRAIN))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.DEVICE_LINK_NOT_OPEN));

        // 链路未开时下发只会进到无人消费的队列并回报成功（假成功），故这里必须一条都不发
        verify(links, never()).sendVestCommand(anyInt(), any());
        // 且不写日志、不落库：它是前置条件问题，拒下即可，不在日志表灌噪声也不留半启动态
        verifyNoInteractions(logs);
        verifyNoInteractions(exercises);
    }

    @Test
    void stopSendsFinishBattleAfterFreezing() {
        vestLinkOpen();
        UUID exerciseId = UuidV7.generate();
        when(exercises.findRunning()).thenReturn(Optional.of(new Exercise(exerciseId, planId,
                "下行单测想定", null, BattleMode.TRAIN, Instant.parse("2026-10-20T08:00:00Z"),
                null, 2, 0, 0, null, null)));
        when(exercises.markEnded(eq(exerciseId), any(), anyInt(), anyInt(), eq(ExerciseEndReason.NORMAL)))
                .thenReturn(1);

        ExerciseLifecycleService.StopOutcome outcome = lifecycle.stopExercise();

        assertThat(outcome.exerciseId()).isEqualTo(exerciseId);
        assertThat(outcome.endReason()).isEqualTo(ExerciseEndReason.NORMAL);
        // 冻结在前、结束帧在后：反过来会留下一场「硬件已停、库里仍在演」的场次
        InOrder inOrder = inOrder(exercises, links);
        inOrder.verify(exercises).markEnded(eq(exerciseId), any(), anyInt(), anyInt(),
                eq(ExerciseEndReason.NORMAL));
        inOrder.verify(links).sendVestCommand(0, DownlinkCommands.finishBattle());
        verifyNoInteractions(logs);
    }
}
