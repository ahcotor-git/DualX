package com.dualx.core.state;

import com.dualx.core.domain.BaseMap;
import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.MapType;
import com.dualx.core.domain.PollMode;
import com.dualx.core.domain.PositionSource;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.push.UnitState;
import com.dualx.core.repository.BaseMapRepository;
import com.dualx.core.repository.ExerciseUnitRepository;
import com.dualx.core.repository.HitEventRepository;
import com.dualx.core.repository.PlanRosterRepository;
import com.dualx.core.repository.TrackSampleRepository;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import com.dualx.protocol.serial.SerialFrame;
import com.dualx.protocol.uwb.UwbFrame;
import com.dualx.solver.config.ScdsUwbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
    private BaseMapRepository baseMaps;
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
        ScdsUwbProperties uwb = new ScdsUwbProperties();
        uwb.setMode(true);
        state = new SituationStateService(newQueue(), hitEvents, clock, sampler, trackWriter, revisionLog,
                new IndoorPositionResolver(uwb));
        planRoster = mock(PlanRosterRepository.class);
        exerciseUnits = mock(ExerciseUnitRepository.class);
        baseMaps = mock(BaseMapRepository.class);
        binder = new SituationSessionBinder(state, planRoster, exerciseUnits, baseMaps, clock);
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

    // ------------------------------------------------------------------ 开演装载室内解算几何

    @Test
    void onExerciseStartedLoadsIndoorGeometrySoUwbFramesResolve() {
        UUID mapId = UUID.fromString("12121212-3434-5656-7878-909090909090");
        when(planRoster.findByPlanId(PLAN_ID)).thenReturn(List.of(roster(1, "红军-01", Team.RED)));
        when(baseMaps.findById(mapId)).thenReturn(Optional.of(indoorMap(mapId)));

        binder.onExerciseStarted(exerciseWithMap(mapId));
        state.dispatch(new StateEvent.Uwb(uwbFrame(1), LinkKind.UWB_TCP_SERVER));

        assertThat(state.unit(1)).get()
                .as("binder 必须把本场底图的几何交给态势，否则 UWB 帧永远解不出坐标")
                .extracting(UnitState::positionSource).isEqualTo(PositionSource.UWB);
    }

    @Test
    void onExerciseStartedWithoutMapLeavesUwbFramesUnsolved() {
        when(planRoster.findByPlanId(PLAN_ID)).thenReturn(List.of(roster(1, "红军-01", Team.RED)));

        binder.onExerciseStarted(exercise());   // mapId=null：室外纯 GPS
        state.dispatch(new StateEvent.Uwb(uwbFrame(1), LinkKind.UWB_TCP_SERVER));

        assertThat(state.unit(1)).get()
                .extracting(UnitState::positionSource).isEqualTo(PositionSource.NONE);
        assertThat(state.uwbFramesWithoutFix()).isEqualTo(1);
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
        return exerciseWithMap(null);
    }

    private static Exercise exerciseWithMap(UUID mapId) {
        return new Exercise(EXERCISE_ID, PLAN_ID, "想定-甲", mapId, BattleMode.TRAIN,
                T0, null, 2, 0, 0, T0, T0);
    }

    /** 2000×1600 像素、现场 30m×18m 的可解几何（与 {@code IndoorPositionResolverTest} 同一份）。 */
    private static BaseMap indoorMap(UUID id) {
        String uwbMeta = """
                {"bs1":{"xPx":120,"yPx":1565},"bs2":{"xPx":1980,"yPx":1565},\
                "bs3":{"xPx":1980,"yPx":240},"bs4":{"xPx":120,"yPx":240},\
                "realLengthCm":3000,"realWidthCm":1800}""";
        return new BaseMap(id, "本场底图", MapType.INDOOR, "maps/test.png", 2000, 1600,
                null, uwbMeta, T0, T0);
    }

    /** 物理矩形正中心的一帧测距：四角等距 √306 m ≈ 17.493 m。 */
    private static UwbFrame uwbFrame(int vestNum) {
        return new UwbFrame(0, vestNum, new int[] {17493, 17493, 17493, 17493, 0, 0, 0, 0},
                new byte[] {(byte) vestNum});
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
