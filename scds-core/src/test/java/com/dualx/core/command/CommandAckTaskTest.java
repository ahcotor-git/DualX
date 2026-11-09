package com.dualx.core.command;

import com.dualx.core.domain.ExerciseCommand;
import com.dualx.core.domain.ExerciseCommandState;
import com.dualx.core.repository.ExerciseCommandRepository;
import com.dualx.core.state.SituationStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CommandAckTask} 的纯逻辑验收（无 DB、无 Spring 上下文）。
 *
 * <p>判据是「{@code sent_at} 之后是否收到该马甲心跳」，因此这里把两个协作者都换成桩：队列用
 * {@link ExerciseCommandRepository} 桩喂入待确认行，态势用 {@link SituationStateService} 桩喂入
 * 「最近心跳时刻」。真实的 SQL（{@code findSent} / {@code markAcked}）由
 * {@code ExerciseCommandRepositoryIT} 覆盖。</p>
 */
class CommandAckTaskTest {

    private static final Instant SENT_AT = Instant.parse("2026-11-09T08:00:00.000Z");
    private static final Instant NOW = Instant.parse("2026-11-09T08:00:01.000Z");

    private ExerciseCommandRepository commands;
    private SituationStateService state;
    private CommandAckTask task;

    @BeforeEach
    void setUp() {
        commands = mock(ExerciseCommandRepository.class);
        state = mock(SituationStateService.class);
        task = new CommandAckTask(commands, state, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ExerciseCommand inFlight(long id, int vestNum, Instant sentAt) {
        return new ExerciseCommand(id, UUID.randomUUID(), vestNum, "DIE",
                ExerciseCommandState.SENT, "{}", "0009ff03e9a1affa", "指挥员",
                sentAt, sentAt, null, null);
    }

    private void heartbeatAt(int vestNum, Instant at) {
        when(state.vestLink(vestNum)).thenReturn(Optional.of(
                new SituationStateService.VestLink(vestNum, true, at, "ff03", 5, 0)));
    }

    @Test
    void tick_acksWhenHeartbeatArrivesAfterSend() {
        when(commands.findSent(anyInt())).thenReturn(List.of(inFlight(7L, 1001, SENT_AT)));
        heartbeatAt(1001, SENT_AT.plusMillis(200));

        task.tick();

        verify(commands).markAcked(7L, NOW);
    }

    @Test
    void tick_acksWhenHeartbeatEqualsSendInstant() {
        when(commands.findSent(anyInt())).thenReturn(List.of(inFlight(7L, 1001, SENT_AT)));
        heartbeatAt(1001, SENT_AT);

        task.tick();

        verify(commands).markAcked(7L, NOW);
    }

    @Test
    void tick_leavesSentWhenHeartbeatPredatesSend() {
        when(commands.findSent(anyInt())).thenReturn(List.of(inFlight(7L, 1001, SENT_AT)));
        heartbeatAt(1001, SENT_AT.minusMillis(200));

        task.tick();

        verify(commands, never()).markAcked(anyLong(), any());
    }

    @Test
    void tick_leavesSentWhenVestUnknown() {
        when(commands.findSent(anyInt())).thenReturn(List.of(inFlight(7L, 1001, SENT_AT)));
        when(state.vestLink(1001)).thenReturn(Optional.empty());

        task.tick();

        verify(commands, never()).markAcked(anyLong(), any());
    }

    @Test
    void tick_leavesSentWhenInRosterButNeverHeartbeated() {
        when(commands.findSent(anyInt())).thenReturn(List.of(inFlight(7L, 1001, SENT_AT)));
        when(state.vestLink(1001)).thenReturn(Optional.of(
                new SituationStateService.VestLink(1001, false, null, null, 0, 0)));

        task.tick();

        verify(commands, never()).markAcked(anyLong(), any());
    }

    @Test
    void tick_neverAcksBroadcast() {
        when(commands.findSent(anyInt())).thenReturn(List.of(inFlight(7L, 0, SENT_AT)));

        task.tick();

        // 广播无单一马甲可确认：不去问态势，也不写 ACKED
        verify(commands, never()).markAcked(anyLong(), any());
        verifyNoInteractions(state);
    }

    @Test
    void tick_leavesSentWhenSentAtMissing() {
        when(commands.findSent(anyInt())).thenReturn(List.of(inFlight(7L, 1001, null)));

        task.tick();

        verify(commands, never()).markAcked(anyLong(), any());
        verifyNoInteractions(state);
    }

    @Test
    void tick_doesNothingWhenNoInFlightCommand() {
        when(commands.findSent(anyInt())).thenReturn(List.of());

        task.tick();

        verify(commands, never()).markAcked(anyLong(), any());
        verifyNoInteractions(state);
    }

    @Test
    void tick_acksOnlyTheConfirmedInAMixedBatch() {
        when(commands.findSent(anyInt())).thenReturn(List.of(
                inFlight(1L, 1001, SENT_AT),
                inFlight(2L, 2002, SENT_AT),
                inFlight(3L, 0, SENT_AT)));
        heartbeatAt(1001, SENT_AT.plusMillis(100));
        when(state.vestLink(2002)).thenReturn(Optional.empty());

        task.tick();

        verify(commands).markAcked(1L, NOW);
        verify(commands, never()).markAcked(eq(2L), any());
        verify(commands, never()).markAcked(eq(3L), any());
    }
}
