package com.dualx.core.state;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.PollMode;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.push.UnitState;
import com.dualx.core.repository.ExerciseUnitRepository;
import com.dualx.core.repository.HitEventRepository;
import com.dualx.core.repository.PlanRosterRepository;
import com.dualx.core.repository.TrackSampleRepository;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import com.dualx.protocol.serial.SerialFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LIFE 与 STATE 的会话编排（设计 06 §4.6）。纯单测：仓储 mock，状态机用真实实现 + 可控时钟。
 */
class SituationSessionBinderTest {

    private static final Instant T0 = Instant.parse("2026-10-24T00:00:00Z");
    private static final LinkKind SOURCE = LinkKind.SERIAL_VEST;

    private static final UUID EXERCISE_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_EXERCISE_ID =
            UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final UUID PLAN_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private MutableClock clock;
    private HitEventRepository hitEvents;
    private TrackSampleRepository trackSamples;
    private TrackSampler sampler;
    private TrackBatchWriter trackWriter;
    private SituationRevisionLog revisionLog;
    private SituationStateService state;
    private PlanRosterRepository planRoster;
    private ExerciseUnitRepository exerciseUnits;
    private SituationSessionBinder binder;

    /** 可控时钟：装载时刻即 {@code tMs} 原点，测试里必须可推。 */
    private static final class MutableClock extends Clock {

        private Instant now = T0;

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        hitEvents = mock(HitEventRepository.class);
        trackSamples = mock(TrackSampleRepository.class);
        sampler = new TrackSampler(1000, 0.05, 100);
        trackWriter = new TrackBatchWriter(trackSamples, 500, 1_000_000, 1000, () -> 0);
        revisionLog = new SituationRevisionLog(16);
        state = new SituationStateService(newQueue(), hitEvents, clock, sampler, trackWriter, revisionLog);
        planRoster = mock(PlanRosterRepository.class);
        exerciseUnits = mock(ExerciseUnitRepository.class);
        binder = new SituationSessionBinder(state, planRoster, exerciseUnits, clock);
    }

    private static StateEventQueue newQueue() {
        return new StateEventQueue(64, StateEventQueue.OverflowPolicy.REJECT);
    }

    // ------------------------------------------------------------------ 开演装载

    @Test
    void onExerciseStartedLoadsRosterFromPlan() {
        when(planRoster.findByPlanId(PLAN_ID)).thenReturn(List.of(
                roster(1, "红军-01", Team.RED),
                roster(2, "蓝军-01", Team.BLUE)));

        int loaded = binder.onExerciseStarted(exercise());

        assertThat(loaded).isEqualTo(2);
        verify(planRoster).findByPlanId(PLAN_ID);
        assertThat(state.exerciseId()).isEqualTo(EXERCISE_ID);
        assertThat(state.snapshot())
                .extracting(UnitState::vestNum).containsExactly(1, 2);
        assertThat(state.snapshot()).as("装载即初始离线")
                .allSatisfy(u -> assertThat(u.status()).isEqualTo(VestStatus.OFFLINE));
    }

    @Test
    void onExerciseStartedIsIdempotentForSameExercise() {
        when(planRoster.findByPlanId(PLAN_ID)).thenReturn(List.of(roster(1, "红军-01", Team.RED)));
        binder.onExerciseStarted(exercise());
        state.dispatch(new StateEvent.Serial(heartbeat(1, 50, 10, 10), SOURCE));
        long revisionAfterChange = state.revision();

        int loaded = binder.onExerciseStarted(exercise());

        assertThat(loaded).isEqualTo(1);
        assertThat(state.revision()).as("同场重复装载不改动任何东西").isEqualTo(revisionAfterChange);
        assertThat(state.unit(1)).get().extracting(UnitState::hp).isEqualTo(50);
    }

    // ------------------------------------------------------------------ 停演收尾

    @Test
    void onExerciseStoppingFreezesEveryUnitAndReturnsRowCount() {
        when(planRoster.findByPlanId(PLAN_ID)).thenReturn(List.of(
                roster(1, "红军-01", Team.RED),
                roster(2, "蓝军-01", Team.BLUE)));
        binder.onExerciseStarted(exercise());
        state.dispatch(new StateEvent.Serial(heartbeat(1, 88, 30, 30), SOURCE));
        when(exerciseUnits.freezeOutcome(any(), anyInt(), any(), any(), anyInt(), anyInt())).thenReturn(1);

        int frozen = binder.onExerciseStopping(EXERCISE_ID);

        assertThat(frozen).isEqualTo(2);
        ArgumentCaptor<Integer> vestNums = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<VestStatus> statuses = ArgumentCaptor.forClass(VestStatus.class);
        verify(exerciseUnits, times(2)).freezeOutcome(
                eq(EXERCISE_ID), vestNums.capture(), statuses.capture(), any(), anyInt(), anyInt());
        assertThat(vestNums.getAllValues()).containsExactlyInAnyOrder(1, 2);
        assertThat(statuses.getAllValues())
                .as("发过心跳的冻结为 LIVE，从未上线的冻结为 OFFLINE")
                .containsExactlyInAnyOrder(VestStatus.LIVE, VestStatus.OFFLINE);
        assertThat(state.running()).isFalse();
        assertThat(state.exerciseId()).isNull();
    }

    @Test
    void onExerciseStoppingFlushesTrackBuffer() {
        when(planRoster.findByPlanId(PLAN_ID)).thenReturn(List.of(roster(1, "红军-01", Team.RED)));
        binder.onExerciseStarted(exercise());
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));
        assertThat(trackWriter.buffered()).as("未达批量阈值，采样点留在缓冲里").isEqualTo(1);

        binder.onExerciseStopping(EXERCISE_ID);

        assertThat(trackWriter.buffered()).isZero();
        verify(trackSamples).insertAll(any());
    }

    @Test
    void onExerciseStoppingWithoutRunningExerciseWritesNothing() {
        int frozen = binder.onExerciseStopping(EXERCISE_ID);

        assertThat(frozen).isZero();
        verifyNoInteractions(exerciseUnits);
    }

    @Test
    void onExerciseStoppingWithMismatchedIdDoesNotWriteNorClearState() {
        when(planRoster.findByPlanId(PLAN_ID)).thenReturn(List.of(roster(1, "红军-01", Team.RED)));
        binder.onExerciseStarted(exercise());

        int frozen = binder.onExerciseStopping(OTHER_EXERCISE_ID);

        assertThat(frozen).isZero();
        verifyNoInteractions(exerciseUnits);
        assertThat(state.running()).as("错场次不得清掉装载中的演习").isTrue();
        assertThat(state.exerciseId()).isEqualTo(EXERCISE_ID);
    }

    // ------------------------------------------------------------------ 测试夹具

    private static Exercise exercise() {
        return new Exercise(EXERCISE_ID, PLAN_ID, "想定-甲", null, BattleMode.TRAIN,
                T0, null, 2, 0, 0, T0, T0);
    }

    private static RosterEntry roster(int vestNum, String name, Team team) {
        Instant epoch = Instant.parse("2026-01-01T00:00:00Z");
        return new RosterEntry(0L, PLAN_ID, vestNum, name, team, 100, 30, 30,
                PollMode.AUTO, epoch, epoch);
    }

    private static SerialFrame.Heartbeat heartbeat(int vestNum, int hp, int ammo1, int ammo2) {
        return new SerialFrame.Heartbeat(vestNum, ammo1, hp, ammo2, 1, 1, new byte[] {(byte) vestNum});
    }
}
