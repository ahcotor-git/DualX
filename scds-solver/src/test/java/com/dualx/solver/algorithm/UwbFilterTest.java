package com.dualx.solver.algorithm;

import com.dualx.solver.config.ScdsUwbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐马甲测量状态机（门限/入状态机/重捕/超时）行为。用兼容入口 {@code push(x, y, rms, ...)}
 * 直接驱动状态机，从而把「解算数值」与「状态迁移」解耦——本类只测后者。
 *
 * <p>默认参数：{@code residualMaxMeters=2.0}、{@code emaJumpRejectDistCm=200}（入状态机位移门限 2m）、
 * {@code lostTimeoutMs=5000}。</p>
 */
class UwbFilterTest {

    private static final long T0 = 1000L;
    private static final double L = 10.0, W = 8.0;

    private ScdsUwbProperties props;
    private UwbFilter filter;

    @BeforeEach
    void setUp() {
        props = new ScdsUwbProperties();
        props.setMode(true);
        props.setReacquireMinFrames(2);
        filter = new UwbFilter(props, 1);
    }

    /** 兼容入口的薄封装：x/y 为归一化百分比，rms 为米。 */
    private UwbFilter.PushResult push(double x, double y, Double rms, int used, long ts) {
        return filter.push(x, y, rms, used, ts, L, W, null, null);
    }

    @Test
    void firstGoodMeasurementIsAccepted() {
        UwbFilter.PushResult r = push(0.3, 0.25, 0.1, 4, T0);

        assertTrue(r.accepted);
        assertEquals("FIRST_VALID", r.rejectReason);
        assertEquals(UwbFilter.STATUS_OK, r.status);
        assertEquals(UwbFilter.STATUS_OK, filter.getState(T0).status);
    }

    @Test
    void staleTimestampIsRejectedAsDuplicateOrOutOfOrder() {
        push(0.3, 0.25, 0.1, 4, T0);

        UwbFilter.PushResult r = push(0.3, 0.25, 0.1, 4, T0 - 100);

        assertFalse(r.accepted);
        assertEquals("DUPLICATE_OR_OUT_OF_ORDER", r.rejectReason);
    }

    @Test
    void residualAboveThresholdIsHeld() {
        push(0.3, 0.25, 0.1, 4, T0);

        UwbFilter.PushResult r = push(0.31, 0.25, 5.0, 4, T0 + 100);

        assertFalse(r.accepted);
        assertEquals(UwbFilter.STATUS_HELD, r.status, "上一帧有效，坏帧只置 HELD 不改锚点");
    }

    @Test
    void aJumpBeyondTheEntryGateIsRejectedThenReacquiredAfterConsistentFrames() {
        push(0.3, 0.25, 0.1, 4, T0);

        // 位移 = hypot((0.6-0.3)*10, 0) = 3m > 2m 门限 → 入状态机拒绝。
        UwbFilter.PushResult rejected = push(0.6, 0.25, 0.1, 4, T0 + 200);
        assertFalse(rejected.accepted);
        assertEquals("ENTRY_REJECT", rejected.rejectReason);
        assertEquals(UwbFilter.STATUS_RECOVER, rejected.status);

        // 连续同位置的第 2 帧（reacquireMinFrames=2）→ 重捕成功，锚点跳到新位置。
        UwbFilter.PushResult reacquired = push(0.6, 0.25, 0.1, 4, T0 + 300);
        assertTrue(reacquired.accepted);
        assertEquals("REACQUIRED", reacquired.rejectReason);
        assertEquals(UwbFilter.STATUS_OK, reacquired.status);
        assertEquals(0.6, filter.getState(T0 + 300).xPct, 1e-9);
    }

    @Test
    void stateTurnsLostAfterTheTimeout() {
        push(0.3, 0.25, 0.1, 4, T0);

        assertEquals(UwbFilter.STATUS_OK, filter.getState(T0 + 4000).status);
        assertEquals(UwbFilter.STATUS_LOST, filter.getState(T0 + 5001).status);
    }

    @Test
    void resetForFloorChangeClearsEverything() {
        push(0.3, 0.25, 0.1, 4, T0);

        filter.resetForFloorChange();

        assertEquals(UwbFilter.STATUS_UNINITIALIZED, filter.getState(T0).status);
        assertEquals(0, filter.getFrameCountSnapshot());
        assertEquals(-1, filter.getLastFrameId());
    }

    /**
     * 「读快照不推进估计器」：兼容入口只写一次，随后多次读状态不得改变已接受帧数。
     */
    @Test
    void readingStateDoesNotAdvanceTheEstimator() {
        push(0.3, 0.25, 0.1, 4, T0);
        int accepted = filter.getEmaFrameCount();

        filter.getState(T0 + 10);
        filter.getState(T0 + 20);

        assertEquals(accepted, filter.getEmaFrameCount());
    }

    @Test
    void eventsAreRecordedAndTailSnapshotIsBounded() {
        push(0.3, 0.25, 0.1, 4, T0);
        push(0.31, 0.25, 5.0, 4, T0 + 100);

        List<UwbFilter.Event> tail = filter.snapshotEvents(1);

        assertEquals(1, tail.size(), "tail 参数应限制返回的事件条数");
        assertEquals("MEASUREMENT_REJECT", tail.get(0).type);
    }
}
