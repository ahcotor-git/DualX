package com.dualx.core.state;

import com.dualx.core.domain.HitEvent;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.PollMode;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.push.UnitState;
import com.dualx.core.repository.HitEventRepository;
import com.dualx.core.repository.TrackSampleRepository;
import com.dualx.protocol.HitPart;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import com.dualx.protocol.WeaponKind;
import com.dualx.protocol.serial.SerialFrame;
import com.dualx.solver.config.ScdsUwbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 态势读侧（S7）：快照 / 单马甲 / 增量窗口 / 队伍汇总 / 命中流水。
 * 纯单测：状态机用真实实现 + 可控时钟，仓储 mock。
 */
class SituationQueryTest {

    private static final Instant T0 = Instant.parse("2026-10-24T00:00:00Z");
    private static final LinkKind SOURCE = LinkKind.SERIAL_VEST;

    private static final UUID EXERCISE_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    private MutableClock clock;
    private HitEventRepository hitEvents;
    private SituationStateService state;
    private SituationQuery query;

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
        state = newState(new SituationRevisionLog(16));
        query = new SituationQuery(state, hitEvents);
        state.beginExercise(EXERCISE_ID, T0, List.of(
                roster(1, "红军-01", Team.RED),
                roster(2, "蓝军-01", Team.BLUE)));
    }

    private SituationStateService newState(SituationRevisionLog revisionLog) {
        TrackSampleRepository trackSamples = mock(TrackSampleRepository.class);
        return new SituationStateService(
                new StateEventQueue(64, StateEventQueue.OverflowPolicy.REJECT),
                hitEvents, clock,
                new TrackSampler(1000, 0.05, 100),
                new TrackBatchWriter(trackSamples, 500, 1_000_000, 1000, () -> 0),
                revisionLog,
                new IndoorPositionResolver(new ScdsUwbProperties()));
    }

    // ------------------------------------------------------------------ 全量与单马甲

    @Test
    void snapshotIsOrderedByVestNumAndCarriesRosterIdentity() {
        assertThat(query.running()).isTrue();
        assertThat(query.exerciseId()).isEqualTo(EXERCISE_ID);
        assertThat(query.snapshot()).extracting(UnitState::vestNum).containsExactly(1, 2);
        assertThat(query.snapshot()).first()
                .satisfies(u -> {
                    assertThat(u.name()).isEqualTo("红军-01");
                    assertThat(u.team()).isEqualTo(Team.RED);
                    assertThat(u.status()).isEqualTo(VestStatus.OFFLINE);
                    assertThat(u.revision())
                            .as("装载后的单元 stamped 切换点 revision")
                            .isEqualTo(query.revision());
                });
    }

    @Test
    void unitIsEmptyForVestOutsideRoster() {
        assertThat(query.unit(2)).get()
                .extracting(UnitState::team).isEqualTo(Team.BLUE);
        assertThat(query.unit(7)).isEmpty();
    }

    // ------------------------------------------------------------------ 增量窗口

    @Test
    void changesSinceReturnsWindowChangesThenEmptiesWhenUpToDate() {
        long switchRevision = query.revision();   // 装载切换点
        assertThat(query.changesSince(switchRevision)).as("刚装载、尚无变化")
                .contains(List.of());
        assertThat(query.changesSince(switchRevision - 1))
                .as("早于切换点的 sinceRevision 一律超窗 → 客户端改全量").isEmpty();

        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));

        List<UnitState> window = query.changesSince(switchRevision).orElseThrow();
        assertThat(window).extracting(UnitState::vestNum).containsExactly(1);
        assertThat(query.changesSince(query.revision())).as("已是最新时为「空列表」而非空 Optional")
                .contains(List.of());
    }

    @Test
    void changesSinceIsEmptyOptionalWhenRevisionOutOfWindow() {
        state = newState(new SituationRevisionLog(1));
        query = new SituationQuery(state, hitEvents);
        state.beginExercise(EXERCISE_ID, T0, List.of(
                roster(1, "红军-01", Team.RED),
                roster(2, "蓝军-01", Team.BLUE)));
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));
        clock.advance(Duration.ofMillis(200));
        state.dispatch(new StateEvent.Serial(heartbeat(2, 100, 30, 30), SOURCE));

        assertThat(query.changesSince(0)).as("revision 已被淘汰 → 超窗")
                .isEmpty();
        List<UnitState> boundary = query.changesSince(2).orElseThrow();
        assertThat(boundary).as("边界：since 等于已淘汰 revision 仍可补齐")
                .extracting(UnitState::vestNum).containsExactly(2);
    }

    // ------------------------------------------------------------------ 队伍汇总

    @Test
    void teamSummaryCountsStatusesAndHitsDealtPerTeam() {
        state.dispatch(new StateEvent.Serial(heartbeat(1, 100, 30, 30), SOURCE));
        state.dispatch(new StateEvent.Serial(heartbeat(2, 0, 30, 30), SOURCE));
        state.dispatch(new StateEvent.Serial(hit(2, 1, HitPart.HEAD.code()), SOURCE));

        assertThat(query.teamSummary()).containsExactly(
                new SituationQuery.TeamCounts(Team.RED, 1, 1, 0, 0, 1),
                new SituationQuery.TeamCounts(Team.BLUE, 1, 0, 1, 0, 0));
    }

    // ------------------------------------------------------------------ 命中流水

    @Test
    void hitsSinceAsksRepositoryScopedToRunningExercise() {
        HitEvent event = hitEvent(11L);
        when(hitEvents.findByExerciseIdSince(EXERCISE_ID, null, 200)).thenReturn(List.of(event));

        assertThat(query.hitsSince(null, 200)).containsExactly(event);
        verify(hitEvents).findByExerciseIdSince(EXERCISE_ID, null, 200);
    }

    @Test
    void hitsSinceIsEmptyWithoutRunningExercise() {
        state.endExercise();

        assertThat(query.hitsSince(null, 200)).isEmpty();
        verify(hitEvents, never()).findByExerciseIdSince(any(), any(), anyInt());
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
