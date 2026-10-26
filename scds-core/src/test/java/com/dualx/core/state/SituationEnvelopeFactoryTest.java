package com.dualx.core.state;

import com.dualx.core.domain.HitEvent;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.PollMode;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.push.SituationEnvelope;
import com.dualx.core.push.TeamSummary;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 态势线载荷组装器（M4 PUSH-MODEL）：全量帧 / 增量帧 / 超窗 / 单马甲 404 / 线载荷转换。
 * 纯单测：状态机用真实实现 + 可控时钟，仓储 mock。
 */
class SituationEnvelopeFactoryTest {

    private static final Instant T0 = Instant.parse("2026-10-24T00:00:00Z");
    private static final LinkKind SOURCE = LinkKind.SERIAL_VEST;

    private static final UUID EXERCISE_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    private MutableClock clock;
    private HitEventRepository hitEvents;
    private SituationStateService state;
    private SituationQuery query;
    private SituationEnvelopeFactory factory;

    /** 可控时钟：状态机与信封的 serverTime 都走它。 */
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
        state = newState(new SituationRevisionLog(16));
        rebuild();
        state.beginExercise(EXERCISE_ID, T0, List.of(
                roster(1, "红军-01", Team.RED),
                roster(2, "蓝军-01", Team.BLUE)));
    }

    private void rebuild() {
        query = new SituationQuery(state, hitEvents);
        factory = new SituationEnvelopeFactory(query, clock);
    }

    private SituationStateService newState(SituationRevisionLog revisionLog) {
        TrackSampleRepository trackSamples = mock(TrackSampleRepository.class);
        return new SituationStateService(
                new StateEventQueue(64, StateEventQueue.OverflowPolicy.REJECT),
                hitEvents, clock,
                new TrackSampler(1000, 0.05, 100),
                new TrackBatchWriter(trackSamples, 500, 1_000_000, 1000, () -> 0),
                revisionLog);
    }

    // ------------------------------------------------------------------ 全量帧

    @Test
    void fullFrameCarriesSnapshotTeamSummaryAndServerTime() {
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));
        state.dispatch(new StateEvent.Serial(hit(2, 1, HitPart.HEAD.code()), SOURCE));
        when(hitEvents.findByExerciseIdSince(EXERCISE_ID, null, 200)).thenReturn(List.of(hitEvent(11L)));

        SituationEnvelope envelope = factory.full(200);

        assertThat(envelope.full()).isTrue();
        assertThat(envelope.running()).isTrue();
        assertThat(envelope.exerciseId()).isEqualTo(EXERCISE_ID.toString());
        assertThat(envelope.serverTime()).isEqualTo(T0);
        assertThat(envelope.revision()).isEqualTo(query.revision());
        assertThat(envelope.units()).extracting(UnitState::vestNum).containsExactly(1, 2);
        assertThat(envelope.changes()).isEmpty();
        assertThat(envelope.removedVestNums()).isEmpty();
        assertThat(envelope.hits()).extracting(com.dualx.core.push.HitEvent::seq).containsExactly(11L);
        assertThat(envelope.teamSummary()).containsExactly(
                new TeamSummary(Team.RED, 1, 1, 0, 0, 1),
                new TeamSummary(Team.BLUE, 1, 0, 0, 1, 0));
    }

    @Test
    void fullFrameWithoutExerciseIsAnEmptySnapshot() {
        state.endExercise();

        SituationEnvelope envelope = factory.full(200);

        assertThat(envelope.full()).isTrue();
        assertThat(envelope.running()).isFalse();
        assertThat(envelope.exerciseId()).isNull();
        assertThat(envelope.units()).isEmpty();
        assertThat(envelope.teamSummary()).isEmpty();
        assertThat(envelope.hits()).isEmpty();
    }

    // ------------------------------------------------------------------ 增量帧

    @Test
    void deltaFrameCarriesWindowChangesWithEmptyHitsAndTeamSummary() {
        long switchRevision = query.revision();
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));

        SituationEnvelope envelope = factory.delta(switchRevision);

        assertThat(envelope.full()).isFalse();
        assertThat(envelope.running()).isTrue();
        assertThat(envelope.units()).isEmpty();
        assertThat(envelope.changes()).extracting(UnitState::vestNum).containsExactly(1);
        assertThat(envelope.removedVestNums()).isEmpty();
        assertThat(envelope.hits()).as("seq 与 revision 互不推导，增量帧一律不带命中").isEmpty();
        assertThat(envelope.teamSummary())
                .as("增量帧只带变化，权威汇总只在全量帧里出现（客户端按本地 units 自算）")
                .isEmpty();
    }

    @Test
    void deltaFrameIsEmptyChangesWhenUpToDate() {
        SituationEnvelope envelope = factory.delta(query.revision());

        assertThat(envelope.full()).isFalse();
        assertThat(envelope.changes()).isEmpty();
    }

    @Test
    void deltaThrowsRevisionTooOldWhenOutOfWindow() {
        state = newState(new SituationRevisionLog(1));
        rebuild();
        state.beginExercise(EXERCISE_ID, T0, List.of(roster(1, "红军-01", Team.RED)));
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));
        clock.advance(Duration.ofMillis(200));
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 29, 30), SOURCE));

        assertThatThrownBy(() -> factory.delta(0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ApiErrorCode.SITUATION_REVISION_TOO_OLD);
    }

    // ------------------------------------------------------------------ 单马甲

    @Test
    void unitIsReturnedForRosterMemberAndThrowsForOutsider() {
        assertThat(factory.unit(2)).extracting(UnitState::team).isEqualTo(Team.BLUE);

        assertThatThrownBy(() -> factory.unit(7))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ApiErrorCode.DEVICE_VEST_NOT_FOUND);
    }

    @Test
    void unitThrowsForAnyVestWhenNoExerciseIsRunning() {
        state.endExercise();

        assertThatThrownBy(() -> factory.unit(1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ApiErrorCode.DEVICE_VEST_NOT_FOUND);
    }

    // ------------------------------------------------------------------ 命中线载荷

    @Test
    void hitsDropLibraryOnlyColumnsWhenConvertedToWirePayload() {
        HitEvent library = hitEvent(11L);
        when(hitEvents.findByExerciseIdSince(EXERCISE_ID, 10L, 50)).thenReturn(List.of(library));

        List<com.dualx.core.push.HitEvent> wire = factory.hits(10L, 50);

        assertThat(wire).containsExactly(new com.dualx.core.push.HitEvent(
                11L, T0, 2, 1, true, WeaponKind.fromNum(1), HitPart.HEAD, false, null));
    }

    @Test
    void hitsAreEmptyWithoutRunningExercise() {
        state.endExercise();

        assertThat(factory.hits(null, 200)).isEmpty();
    }

    // ------------------------------------------------------------------ 测试夹具

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

    private static HitEvent hitEvent(long seq) {
        return new HitEvent(seq, EXERCISE_ID, T0, 0, 2, 1, true,
                WeaponKind.fromNum(1), HitPart.HEAD, false, null);
    }
}
