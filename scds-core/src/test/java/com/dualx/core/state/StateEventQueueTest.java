package com.dualx.core.state;

import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.state.StateEventQueue.OverflowPolicy;
import com.dualx.protocol.serial.SerialFrame;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 有界事件队列的溢出语义与边界行为。纯单测（不碰数据库、不起线程）。
 */
class StateEventQueueTest {

    private static StateEvent.Serial heartbeat(int vestNum) {
        return new StateEvent.Serial(
                new SerialFrame.Heartbeat(vestNum, 30, 100, 30, 1, 1, new byte[] {(byte) vestNum}),
                LinkKind.SERIAL_VEST);
    }

    private static StateEvent.Serial hit(int hitVestNum) {
        return new StateEvent.Serial(
                new SerialFrame.Hit(hitVestNum, 1801, 1, new byte[] {(byte) hitVestNum}),
                LinkKind.SERIAL_VEST);
    }

    private static StateEvent linkChanged() {
        return new StateEvent.LinkChanged(LinkKind.SERIAL_VEST, LinkState.OPEN, "opened");
    }

    @Test
    void remainingCapacityIsPreservedInFifoOrder() throws Exception {
        var queue = new StateEventQueue(3, OverflowPolicy.REJECT);

        var first = heartbeat(1);
        var second = hit(2);
        var third = linkChanged();
        assertThat(queue.offer(first)).isTrue();
        assertThat(queue.offer(second)).isTrue();
        assertThat(queue.offer(third)).isTrue();
        assertThat(queue.size()).isEqualTo(3);
        assertThat(queue.capacity()).isEqualTo(3);

        assertThat(queue.take()).isSameAs(first);
        assertThat(queue.take()).isSameAs(second);
        assertThat(queue.take()).isSameAs(third);
        assertThat(queue.size()).isZero();
    }

    @Test
    void rejectPolicyRejectsWhenFullWithoutBlocking() {
        var queue = new StateEventQueue(2, OverflowPolicy.REJECT);
        assertThat(queue.offer(heartbeat(1))).isTrue();
        assertThat(queue.offer(heartbeat(2))).isTrue();

        long startedAt = System.nanoTime();
        assertThat(queue.offer(heartbeat(3))).isFalse();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMs).as("非阻塞：不得在满队列上等待").isLessThan(500);
        assertThat(queue.size()).isEqualTo(2);
        assertThat(queue.rejectedCount()).isEqualTo(1);
        assertThat(queue.droppedCount()).isZero();
    }

    @Test
    void dropLowPriorityDiscardsIncomingHeartbeatWhenFull() throws Exception {
        var queue = new StateEventQueue(2, OverflowPolicy.DROP_LOW_PRIORITY);
        var kept1 = heartbeat(1);
        var kept2 = heartbeat(2);
        queue.offer(kept1);
        queue.offer(kept2);

        // 入队者自己是可丢的心跳刷新 → 直接丢它，队内原样不动（下一拍会重报）
        assertThat(queue.offer(heartbeat(3))).isFalse();

        assertThat(queue.droppedCount()).isEqualTo(1);
        assertThat(queue.rejectedCount()).isZero();
        assertThat(queue.take()).isSameAs(kept1);
        assertThat(queue.take()).isSameAs(kept2);
    }

    @Test
    void dropLowPriorityEvictsOldestHeartbeatToAdmitCriticalEvent() throws Exception {
        var queue = new StateEventQueue(3, OverflowPolicy.DROP_LOW_PRIORITY);
        var oldestHeartbeat = heartbeat(1);
        var secondHeartbeat = heartbeat(2);
        var thirdHeartbeat = heartbeat(3);
        queue.offer(oldestHeartbeat);
        queue.offer(secondHeartbeat);
        queue.offer(thirdHeartbeat);

        var criticalHit = hit(9);
        assertThat(queue.offer(criticalHit)).isTrue();

        // 淘汰的是「最旧的可丢事件」，命中不许因满队列而丢
        assertThat(queue.droppedCount()).isEqualTo(1);
        assertThat(queue.rejectedCount()).isZero();
        assertThat(queue.take()).isSameAs(secondHeartbeat);
        assertThat(queue.take()).isSameAs(thirdHeartbeat);
        assertThat(queue.take()).isSameAs(criticalHit);
    }

    @Test
    void dropLowPriorityFallsBackToRejectWhenNothingIsDroppable() {
        var queue = new StateEventQueue(2, OverflowPolicy.DROP_LOW_PRIORITY);
        queue.offer(hit(1));
        queue.offer(hit(2));

        assertThat(queue.offer(hit(3))).isFalse();

        assertThat(queue.droppedCount()).isZero();
        assertThat(queue.rejectedCount()).isEqualTo(1);
        assertThat(queue.size()).isEqualTo(2);
    }

    @Test
    void pollReturnsNullOnTimeoutAndThenDrainsWhatArrives() throws Exception {
        var queue = new StateEventQueue(1, OverflowPolicy.REJECT);

        assertThat(queue.poll(20, TimeUnit.MILLISECONDS))
                .as("空队列 poll 超时返回 null，不抛异常").isNull();

        var event = new StateEvent.OfflineScan(Instant.parse("2026-10-24T00:00:00Z"));
        assertThat(queue.offer(event)).isTrue();
        assertThat(queue.poll(20, TimeUnit.MILLISECONDS)).isSameAs(event);
    }

    @Test
    void nonPositiveCapacityIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StateEventQueue(0, OverflowPolicy.REJECT));
    }
}
