package com.dualx.core.state;

import com.dualx.core.domain.CoordinateSystem;
import com.dualx.core.domain.HitEvent;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.MotionState;
import com.dualx.core.domain.PollMode;
import com.dualx.core.domain.PositionSource;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.domain.SampleReason;
import com.dualx.core.domain.TrackSample;
import com.dualx.core.push.UnitState;
import com.dualx.core.repository.HitEventRepository;
import com.dualx.core.repository.TrackSampleRepository;
import com.dualx.protocol.HitPart;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import com.dualx.protocol.WeaponKind;
import com.dualx.protocol.serial.SerialFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单一状态所有者的状态机行为。纯单测：仓储用 mock，时钟用可控 {@link Clock}，不起线程。
 */
class SituationStateServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-24T00:00:00Z");
    private static final LinkKind SOURCE = LinkKind.SERIAL_VEST;

    private MutableClock clock;
    private HitEventRepository hitEvents;
    private TrackSampleRepository trackSamples;
    private TrackSampler sampler;
    private TrackBatchWriter trackWriter;
    private SituationRevisionLog revisionLog;
    private SituationStateService state;

    /** 可控时钟：状态机内部一律走它取时间。 */
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
        // 采样周期 1000ms、室内位移阈值 0.05、室外 100m；批量阈值取很大，避免单测里自动落库，
        // 只在显式 flush（停演收尾）时观察缓冲。
        sampler = new TrackSampler(1000, 0.05, 100);
        trackWriter = new TrackBatchWriter(trackSamples, 500, 1_000_000, 1000, () -> 0);
        revisionLog = new SituationRevisionLog(16);
        state = newService();
        state.beginExercise(EXERCISE_ID, T0, List.of(
                roster(1, "红军-01", Team.RED),
                roster(2, "蓝军-01", Team.BLUE)));
    }

    private SituationStateService newService() {
        return new SituationStateService(newQueue(), hitEvents, clock, sampler, trackWriter, revisionLog);
    }

    private static StateEventQueue newQueue() {
        return new StateEventQueue(64, StateEventQueue.OverflowPolicy.REJECT);
    }

    // ------------------------------------------------------------------ 心跳与在线

    @Test
    void heartbeatBringsOfflineVestLiveAndCarriesState() {
        assertThat(state.unit(1)).get()
                .extracting(UnitState::status).isEqualTo(VestStatus.OFFLINE);

        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));

        assertThat(state.unit(1)).get().satisfies(u -> {
            assertThat(u.status()).isEqualTo(VestStatus.LIVE);
            assertThat(u.hp()).isEqualTo(100);
            assertThat(u.ammo1()).isEqualTo(30);
            assertThat(u.ammo2()).isEqualTo(30);
            assertThat(u.lastHeartbeatAt()).isEqualTo(T0);
            assertThat(u.position()).isNull();
            assertThat(u.positionSource()).isEqualTo(PositionSource.NONE);
        });
    }

    @Test
    void unchangedHeartbeatRefreshesWindowWithoutBumpingRevision() {
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));
        long afterFirst = state.revision();

        clock.advance(Duration.ofMillis(200));
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));

        // 心跳窗口基必须刷新（否则会被判离线），但不得计成一次变化：
        // 否则 200ms 一轮的心跳会刷空增量环形窗口。
        assertThat(state.unit(1)).get()
                .extracting(UnitState::lastHeartbeatAt).isEqualTo(T0.plusMillis(200));
        assertThat(state.revision()).isEqualTo(afterFirst);
    }

    @Test
    void offlineScanFlipsLiveToOfflineOnlyAfterWindow() {
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));

        clock.advance(Duration.ofMillis(5000));
        state.dispatch(new StateEvent.OfflineScan(clock.instant()));
        assertThat(state.unit(1)).get()
                .as("正好 5000ms 仍在窗口内（判据是 > 5000）")
                .extracting(UnitState::status).isEqualTo(VestStatus.LIVE);

        clock.advance(Duration.ofMillis(1));
        state.dispatch(new StateEvent.OfflineScan(clock.instant()));
        assertThat(state.unit(1)).get()
                .extracting(UnitState::status).isEqualTo(VestStatus.OFFLINE);
    }

    @Test
    void offlineScanDoesNotTouchVestsThatNeverHadHeartbeat() {
        state.dispatch(new StateEvent.OfflineScan(T0.plusSeconds(60)));

        assertThat(state.unit(2)).get()
                .extracting(UnitState::status).isEqualTo(VestStatus.OFFLINE);
        assertThat(state.revision()).isZero();
    }

    // ------------------------------------------------------------------ 阵亡冻结与判活

    @Test
    void deathFreezesPositionAndBackfillsLethalToTheShooter() {
        // 先定位（GPS），再被打，再阵亡
        state.dispatch(new StateEvent.Serial(gps(1, 116.397428, 39.90923), SOURCE));
        state.dispatch(new StateEvent.Serial(hit(1, 1801, HitPart.CHEST.code()), SOURCE));
        when(hitEvents.markMostRecentLethal(EXERCISE_ID, 1)).thenReturn(Optional.of(1801));

        clock.advance(Duration.ofSeconds(1));
        state.dispatch(new StateEvent.Serial(heartbeat(1, 0, 30, 30), SOURCE));

        assertThat(state.unit(1)).get().satisfies(u -> {
            assertThat(u.status()).isEqualTo(VestStatus.DIE);
            assertThat(u.motion()).isEqualTo(MotionState.FROZEN);
            assertThat(u.deadAt()).isEqualTo(T0.plusSeconds(1));
            assertThat(u.position().wgs84Lng()).isEqualTo(116.397428);
        });
        assertThat(state.kills(1801)).isEqualTo(1);
    }

    @Test
    void deadVestIgnoresLaterHeartbeatsAndCoordinates() {
        state.dispatch(new StateEvent.Serial(gps(1, 116.397428, 39.90923), SOURCE));
        state.dispatch(new StateEvent.Serial(heartbeat(1, 0, 30, 30), SOURCE));

        clock.advance(Duration.ofSeconds(10));
        state.dispatch(new StateEvent.Serial(gps(1, 100.0, 20.0), SOURCE));
        state.dispatch(new StateEvent.Serial(heartbeat(1, 88, 30, 30), SOURCE));

        assertThat(state.unit(1)).get().satisfies(u -> {
            assertThat(u.status()).isEqualTo(VestStatus.DIE);
            assertThat(u.hp()).as("阵亡后心跳不得改状态").isZero();
            assertThat(u.position().wgs84Lng())
                    .as("阵亡后坐标必须冻结在最后有效值").isEqualTo(116.397428);
        });
    }

    @Test
    void reviveUnfreezesButDoesNotRestorePosition() {
        state.dispatch(new StateEvent.Serial(gps(1, 116.397428, 39.90923), SOURCE));
        state.dispatch(new StateEvent.Serial(heartbeat(1, 0, 30, 30), SOURCE));

        assertThat(state.revive(1)).isTrue();

        assertThat(state.unit(1)).get().satisfies(u -> {
            assertThat(u.status()).isEqualTo(VestStatus.LIVE);
            assertThat(u.deadAt()).isNull();
            assertThat(u.motion()).isNull();
            assertThat(u.position()).as("坐标不还原，等下一帧到达").isNotNull();
        });
        assertThat(state.revive(1)).as("非阵亡单元判活返回 false").isFalse();
        assertThat(state.revive(999)).isFalse();
    }

    // ------------------------------------------------------------------ 命中与计数

    @Test
    void hitIsPersistedWithVictimCoordinatesAndCountedPerShooter() {
        state.dispatch(new StateEvent.Serial(gps(1, 116.397428, 39.90923), SOURCE));

        state.dispatch(new StateEvent.Serial(hit(1, 42, HitPart.HEAD.code()), SOURCE));

        ArgumentCaptor<HitEvent> captor = ArgumentCaptor.forClass(HitEvent.class);
        verify(hitEvents).insert(captor.capture());
        HitEvent saved = captor.getValue();
        assertThat(saved.exerciseId()).isEqualTo(EXERCISE_ID);
        assertThat(saved.hitVestNum()).isEqualTo(1);
        assertThat(saved.shooterNum()).isEqualTo(42);
        assertThat(saved.shooterIsSoldier()).isTrue();
        assertThat(saved.shooterWeaponKind()).as("单兵命中不带武器种类").isNull();
        assertThat(saved.hitPart()).isEqualTo(HitPart.HEAD);
        assertThat(saved.lethal()).as("致死只能由心跳倒推，命中先落 false").isFalse();
        assertThat(saved.position().wgs84Lng()).isEqualTo(116.397428);
        assertThat(saved.tMs()).isZero();
        assertThat(state.hitsDealt(42)).isEqualTo(1);
    }

    @Test
    void weaponHitCarriesWeaponKindAndIsNotSoldier() {
        state.dispatch(new StateEvent.Serial(hit(1, 1801, HitPart.ABDOMEN.code()), SOURCE));

        ArgumentCaptor<HitEvent> captor = ArgumentCaptor.forClass(HitEvent.class);
        verify(hitEvents).insert(captor.capture());
        assertThat(captor.getValue().shooterIsSoldier()).isFalse();
        assertThat(captor.getValue().shooterWeaponKind()).isEqualTo(WeaponKind.HOWITZER_35);
    }

    @Test
    void hitWithoutRunningExerciseIsNotPersisted() {
        state = newService();

        state.dispatch(new StateEvent.Serial(hit(1, 42, HitPart.HEAD.code()), SOURCE));

        verify(hitEvents, never()).insert(any());
        assertThat(state.framesWithoutExercise()).isEqualTo(1);
    }

    @Test
    void lethalWithoutPendingHitLeavesKillsUntouched() {
        when(hitEvents.markMostRecentLethal(EXERCISE_ID, 1)).thenReturn(Optional.empty());

        state.dispatch(new StateEvent.Serial(heartbeat(1, 0, 30, 30), SOURCE));

        assertThat(state.unit(1)).get()
                .extracting(UnitState::status).isEqualTo(VestStatus.DIE);
        assertThat(state.kills(42)).isZero();
    }

    // ------------------------------------------------------------------ 采样与增量窗口接线

    @Test
    void changeIsSampledAsStateChangeWhileUnchangedHeartbeatIsNot() {
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));   // 首次观察 → STATE_CHANGE

        clock.advance(Duration.ofMillis(200));
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));   // 值未变、未到采样周期 → 不记

        state.endExercise();

        assertThat(flushedTrackSamples())
                .extracting(TrackSample::sampleReason)
                .containsExactly(SampleReason.STATE_CHANGE);
    }

    @Test
    void offlineScanLeavesAStateChangeSample() {
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));

        clock.advance(Duration.ofMillis(5001));
        state.dispatch(new StateEvent.OfflineScan(clock.instant()));

        state.endExercise();

        assertThat(flushedTrackSamples())
                .extracting(TrackSample::sampleReason)
                .as("判活/在线建立与离线跳变各留一条 STATE_CHANGE")
                .containsExactly(SampleReason.STATE_CHANGE, SampleReason.STATE_CHANGE);
    }

    @Test
    void hitDeathAndReviveAreSampledAsDiscreteEvents() {
        state.dispatch(new StateEvent.Serial(gps(1, 116.397428, 39.90923), SOURCE));
        state.dispatch(new StateEvent.Serial(hit(1, 42, HitPart.HEAD.code()), SOURCE));
        state.dispatch(new StateEvent.Serial(heartbeat(1, 0, 30, 30), SOURCE));

        assertThat(state.revive(1)).isTrue();

        state.endExercise();

        assertThat(flushedTrackSamples())
                .filteredOn(s -> s.vestNum() == 1)
                .extracting(TrackSample::sampleReason)
                .as("首次定位 + 命中 + 阵亡 + 判活")
                .containsExactly(SampleReason.STATE_CHANGE, SampleReason.EVENT,
                        SampleReason.EVENT, SampleReason.EVENT);
    }

    @Test
    void endExerciseFlushesTrackBufferAndClearsWindow() {
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));

        assertThat(trackWriter.buffered()).as("未达批量阈值，采样点留在缓冲里").isEqualTo(1);
        assertThat(revisionLog.size()).isEqualTo(1);

        state.endExercise();

        assertThat(trackWriter.buffered()).isZero();
        verify(trackSamples).insertAll(any());
    }

    @Test
    void beginExerciseOnNewExerciseClearsWindowAndSamplingBaseline() {
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));
        assertThat(revisionLog.size()).isEqualTo(1);
        assertThat(sampler.tracked(1)).isTrue();

        state.beginExercise(OTHER_EXERCISE_ID, T0.plusSeconds(60), List.of(roster(1, "红军-01", Team.RED)));

        assertThat(state.revision()).isZero();
        assertThat(revisionLog.size()).isZero();
        assertThat(sampler.tracked(1)).as("跨演习必须清采样基准，否则与上一场作差").isFalse();
    }

    // ------------------------------------------------------------------ 编成、终局与链路

    @Test
    void framesFromVestsOutsideRosterAreCountedNotApplied() {
        state.dispatch(new StateEvent.Serial(heartbeat(7, 100, 30, 30), SOURCE));

        assertThat(state.unit(7)).isEmpty();
        assertThat(state.framesFromUnknownVest()).isEqualTo(1);
    }

    @Test
    void beginExerciseIsIdempotentForSameExercise() {
        long revisionBefore = state.revision();
        state.dispatch(new StateEvent.Serial(heartbeat(1, 50, 10, 10), SOURCE));
        long revisionAfterChange = state.revision();
        assertThat(revisionAfterChange).isGreaterThan(revisionBefore);

        state.beginExercise(EXERCISE_ID, T0, List.of(roster(1, "红军-01", Team.RED)));

        assertThat(state.revision()).as("同场重复装载不改动任何东西").isEqualTo(revisionAfterChange);
        assertThat(state.unit(1)).get()
                .extracting(UnitState::hp).isEqualTo(50);
    }

    @Test
    void endExerciseReturnsOutcomesAndClearsState() {
        state.dispatch(new StateEvent.Serial(hit(1, 42, HitPart.HEAD.code()), SOURCE));
        when(hitEvents.markMostRecentLethal(EXERCISE_ID, 1)).thenReturn(Optional.of(42));
        state.dispatch(new StateEvent.Serial(heartbeat(1, 0, 30, 30), SOURCE));

        List<SituationStateService.UnitOutcome> outcomes = state.endExercise();

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0).vestNum()).isEqualTo(1);
        assertThat(outcomes.get(0).finalStatus()).isEqualTo(VestStatus.DIE);
        assertThat(outcomes.get(0).kills())
                .as("击杀记在开枪者名下，不是被击中者名下").isZero();
        assertThat(outcomes.get(0).hitsDealt()).isZero();
        assertThat(outcomes.get(1).finalStatus())
                .as("从未收到心跳的单元终局按离线冻结").isEqualTo(VestStatus.OFFLINE);
        assertThat(state.kills(42)).isEqualTo(1);
        assertThat(state.running()).isFalse();
        assertThat(state.snapshot()).isEmpty();
        assertThat(state.endExercise()).isEmpty();
    }

    @Test
    void linkStateChangesAreRecorded() {
        state.dispatch(new StateEvent.LinkChanged(SOURCE, LinkState.OPEN, "opened"));

        assertThat(state.linkState(SOURCE)).contains(LinkState.OPEN);
        assertThat(state.linkState(LinkKind.UWB_TCP_SERVER)).isEmpty();
        assertThat(state.revision()).as("链路状态不是单元变化，不计 revision").isZero();
    }

    @Test
    void gpsStoresWgs84OnlyAndUwbFramesAreCountedNotSolved() {
        state.dispatch(new StateEvent.Serial(gps(2, 116.1, 39.1), SOURCE));

        assertThat(state.unit(2)).get().satisfies(u -> {
            assertThat(u.positionSource()).isEqualTo(PositionSource.GPS);
            assertThat(u.position().coordinateSystem()).isEqualTo(CoordinateSystem.OUTDOOR);
            assertThat(u.position().wgs84Lng()).isEqualTo(116.1);
            assertThat(u.position().lng())
                    .as("BD-09 换算规则未闭合（Q-LEGACY-RULE），本段不臆造").isNull();
        });
    }

    @Test
    void snapshotIsOrderedByVestNum() {
        assertThat(state.snapshot()).extracting(UnitState::vestNum).containsExactly(1, 2);
    }

    // ------------------------------------------------------------------ 测试夹具

    private static final java.util.UUID EXERCISE_ID =
            java.util.UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final java.util.UUID OTHER_EXERCISE_ID =
            java.util.UUID.fromString("99999999-8888-7777-6666-555555555555");

    /** 停演收尾 flush 后，累计落库的全部轨迹采样点（按批次顺序拼接）。 */
    @SuppressWarnings("unchecked")
    private List<TrackSample> flushedTrackSamples() {
        ArgumentCaptor<List<TrackSample>> captor = ArgumentCaptor.forClass(List.class);
        verify(trackSamples, atLeastOnce()).insertAll(captor.capture());
        List<TrackSample> all = new ArrayList<>();
        for (List<TrackSample> batch : captor.getAllValues()) {
            all.addAll(batch);
        }
        return all;
    }

    private static RosterEntry roster(int vestNum, String name, Team team) {
        Instant epoch = Instant.parse("2026-01-01T00:00:00Z");
        return new RosterEntry(0L, EXERCISE_ID, vestNum, name, team, 100, 30, 30,
                PollMode.AUTO, epoch, epoch);
    }

    private static SerialFrame.Heartbeat heartbeat(int vestNum, int hp, int ammo1, int ammo2) {
        return new SerialFrame.Heartbeat(vestNum, ammo1, hp, ammo2, 1, 1, new byte[] {(byte) vestNum});
    }

    private static SerialFrame.Hit hit(int hitVestNum, int shooterNum, int hitPart) {
        return new SerialFrame.Hit(hitVestNum, shooterNum, hitPart, new byte[] {(byte) hitVestNum});
    }

    private static SerialFrame.Gps gps(int vestNum, double lng, double lat) {
        return new SerialFrame.Gps(vestNum, (long) (lng * 1_000_000), (long) (lat * 1_000_000),
                lng, lat, new byte[] {(byte) vestNum});
    }
}
