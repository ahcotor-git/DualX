package com.dualx.gateway.link;

import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.LinkStatusPushPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路注册表与状态机单测（无硬件、无 Spring）。
 *
 * <p>覆盖的是 review.md R15 收口后的口径：已开重开返回 409 且**不隐式重开**、
 * 两种 UWB 接入互斥、同一物理串口独占、关闭幂等且计数归零。</p>
 */
class LinkRegistryTest {

    private static LinkStatus of(LinkRegistry registry, LinkKind kind) {
        return registry.snapshot().stream()
                .filter(status -> status.kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private static ApiErrorCode codeOf(Throwable throwable) {
        return ((BusinessException) throwable).code();
    }

    @Test
    void snapshotAlwaysCarriesEveryKindAsClosed() {
        LinkRegistry registry = new LinkRegistry();

        List<LinkStatus> snapshot = registry.snapshot();

        assertEquals(LinkKind.values().length, snapshot.size());
        for (LinkStatus status : snapshot) {
            assertEquals(LinkState.CLOSED, status.state());
            assertNull(status.target());
            assertNull(status.baudRate());
            assertEquals(0L, status.framesTotal());
            assertEquals(0L, status.droppedTotal());
            assertEquals(0L, status.crcErrorTotal());
            assertNull(status.errorMessage());
        }
    }

    @Test
    void openGoesThroughOpeningThenOpen() {
        LinkRegistry registry = new LinkRegistry();

        registry.beginOpen(LinkKind.SERIAL_VEST, "COM7", "COM7", 115200);

        assertEquals(LinkState.OPENING, of(registry, LinkKind.SERIAL_VEST).state());
        assertEquals("COM7", of(registry, LinkKind.SERIAL_VEST).target());
        assertEquals(115200, of(registry, LinkKind.SERIAL_VEST).baudRate());
        assertTrue(registry.slot(LinkKind.SERIAL_VEST).active());

        registry.finishOpen(LinkKind.SERIAL_VEST);

        assertEquals(LinkState.OPEN, of(registry, LinkKind.SERIAL_VEST).state());
    }

    @Test
    void reopeningAnOpenLinkIsConflictAndDoesNotSilentlyReopen() {
        LinkRegistry registry = new LinkRegistry();
        registry.beginOpen(LinkKind.SERIAL_VEST, "COM7", "COM7", 115200);
        registry.finishOpen(LinkKind.SERIAL_VEST);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> registry.beginOpen(LinkKind.SERIAL_VEST, "COM7", "COM7", 115200));

        assertEquals(ApiErrorCode.DEVICE_LINK_ALREADY_OPEN, codeOf(failure));
        // 冲突之后原链路不受影响，仍保持已开启
        assertEquals(LinkState.OPEN, of(registry, LinkKind.SERIAL_VEST).state());
        assertEquals("COM7", of(registry, LinkKind.SERIAL_VEST).target());
    }

    @Test
    void openingDuringOpeningIsAlsoConflict() {
        LinkRegistry registry = new LinkRegistry();
        registry.beginOpen(LinkKind.SERIAL_VEST, "COM7", "COM7", 115200);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> registry.beginOpen(LinkKind.SERIAL_VEST, "COM7", "COM7", 115200));

        assertEquals(ApiErrorCode.DEVICE_LINK_ALREADY_OPEN, codeOf(failure));
        assertEquals(LinkState.OPENING, of(registry, LinkKind.SERIAL_VEST).state());
    }

    @Test
    void theTwoUwbAccessModesAreMutuallyExclusive() {
        LinkRegistry registry = new LinkRegistry();
        registry.beginOpen(LinkKind.UWB_TCP_SERVER, null, "0.0.0.0:12001", null);
        registry.finishOpen(LinkKind.UWB_TCP_SERVER);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> registry.beginOpen(LinkKind.UWB_SERIAL, "COM3", "COM3", 460800));

        assertEquals(ApiErrorCode.DEVICE_LINK_ALREADY_OPEN, codeOf(failure));
        assertEquals(LinkState.CLOSED, of(registry, LinkKind.UWB_SERIAL).state());

        // 关闭当前模式后，另一种模式可以开启；切换必须显式关闭
        registry.close(LinkKind.UWB_TCP_SERVER);
        registry.beginOpen(LinkKind.UWB_SERIAL, "COM3", "COM3", 460800);

        assertEquals(LinkState.OPENING, of(registry, LinkKind.UWB_SERIAL).state());
    }

    @Test
    void onePhysicalSerialPortCannotBeHeldByTwoLinks() {
        LinkRegistry registry = new LinkRegistry();
        registry.beginOpen(LinkKind.SERIAL_VEST, "COM3", "COM3", 115200);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> registry.beginOpen(LinkKind.UWB_SERIAL, "COM3", "COM3", 460800));

        assertEquals(ApiErrorCode.DEVICE_LINK_SERIAL_BUSY, codeOf(failure));

        // 换一个没被占用的串口即可开启
        registry.beginOpen(LinkKind.UWB_SERIAL, "COM4", "COM4", 460800);

        assertEquals(LinkState.OPENING, of(registry, LinkKind.UWB_SERIAL).state());
    }

    @Test
    void failedPhysicalOpenReleasesThePortAndLandsOnError() {
        LinkRegistry registry = new LinkRegistry();
        registry.beginOpen(LinkKind.SERIAL_VEST, "COM7", "COM7", 115200);

        registry.abortOpen(LinkKind.SERIAL_VEST, "串口不存在");

        LinkStatus status = of(registry, LinkKind.SERIAL_VEST);
        assertEquals(LinkState.ERROR, status.state());
        assertEquals("串口不存在", status.errorMessage());
        assertFalse(registry.slot(LinkKind.SERIAL_VEST).active());

        // ERROR 不是「已开启」，重试不会被自身的重开冲突挡住；
        // 且失败时占的串口已释放，别的链路能拿到
        registry.beginOpen(LinkKind.UWB_SERIAL, "COM7", "COM7", 460800);
        assertEquals(LinkState.OPENING, of(registry, LinkKind.UWB_SERIAL).state());
    }

    @Test
    void closeIsIdempotentAndResetsCounters() {
        LinkRegistry registry = new LinkRegistry();
        LinkSlot slot = registry.beginOpen(LinkKind.UWB_TCP_SERVER, null, "0.0.0.0:12001", null);
        registry.finishOpen(LinkKind.UWB_TCP_SERVER);
        slot.recordFrame();
        slot.recordFrame();
        slot.recordDropped();
        slot.recordCrcError();
        slot.updateConnections(2);

        LinkStatus busy = of(registry, LinkKind.UWB_TCP_SERVER);
        assertEquals(2L, busy.framesTotal());
        assertEquals(1L, busy.droppedTotal());
        assertEquals(1L, busy.crcErrorTotal());
        assertEquals(2, busy.fdCount());
        assertNotNull(busy.lastFrameAt());

        registry.close(LinkKind.UWB_TCP_SERVER);
        registry.close(LinkKind.UWB_TCP_SERVER);

        LinkStatus closed = of(registry, LinkKind.UWB_TCP_SERVER);
        assertEquals(LinkState.CLOSED, closed.state());
        assertNull(closed.target());
        assertNull(closed.fdCount());
        assertEquals(0L, closed.framesTotal());
        assertEquals(0L, closed.droppedTotal());
        assertEquals(0L, closed.crcErrorTotal());
    }

    @Test
    void countersRestartFromZeroOnEachOpen() {
        LinkRegistry registry = new LinkRegistry();
        LinkSlot slot = registry.beginOpen(LinkKind.SERIAL_VEST, "COM7", "COM7", 115200);
        registry.finishOpen(LinkKind.SERIAL_VEST);
        slot.recordFrame();
        slot.recordFrame();
        slot.recordFrame();

        registry.close(LinkKind.SERIAL_VEST);
        LinkSlot reopened = registry.beginOpen(LinkKind.SERIAL_VEST, "COM7", "COM7", 115200);

        assertEquals(0L, of(registry, LinkKind.SERIAL_VEST).framesTotal());
        assertNull(of(registry, LinkKind.SERIAL_VEST).lastFrameAt());
        reopened.recordFrame();
        assertEquals(1L, of(registry, LinkKind.SERIAL_VEST).framesTotal());
    }

    // ------------------------------------------------------------------ 变化扇出（Q9 链路同构落地）

    /** 记录每次 {@code publish} 的载荷，用来断言「变化 → 推整份快照」的次数与形态。 */
    private static final class RecordingPush implements LinkStatusPushPort {

        private final List<List<LinkStatus>> published = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(List<LinkStatus> snapshot) {
            published.add(snapshot);
        }

        int count() {
            return published.size();
        }

        List<LinkStatus> at(int index) {
            return published.get(index);
        }

        LinkState stateOf(int index, LinkKind kind) {
            return at(index).stream().filter(status -> status.kind() == kind).findFirst().orElseThrow().state();
        }
    }

    @Test
    void everyStateTransitionFansOutAFullSnapshot() {
        RecordingPush push = new RecordingPush();
        LinkRegistry registry = new LinkRegistry(push);

        registry.beginOpen(LinkKind.SERIAL_VEST, "COM7", "COM7", 115200);
        registry.finishOpen(LinkKind.SERIAL_VEST);
        registry.close(LinkKind.SERIAL_VEST);

        assertEquals(3, push.count());
        assertEquals(LinkState.OPENING, push.stateOf(0, LinkKind.SERIAL_VEST));
        assertEquals(LinkState.OPEN, push.stateOf(1, LinkKind.SERIAL_VEST));
        assertEquals(LinkState.CLOSED, push.stateOf(2, LinkKind.SERIAL_VEST));
        // 载荷与 snapshot() 是同一份数据：同构不是人工校准，而是「只有一个出口」
        assertEquals(LinkKind.values().length, push.at(0).size());
        assertEquals(registry.snapshot(), push.at(2));
    }

    @Test
    void abortAndConnectionChangeAlsoFanOut() {
        RecordingPush push = new RecordingPush();
        LinkRegistry registry = new LinkRegistry(push);

        LinkSlot slot = registry.beginOpen(LinkKind.UWB_TCP_SERVER, null, "0.0.0.0:12001", null);
        registry.finishOpen(LinkKind.UWB_TCP_SERVER);
        slot.updateConnections(2);
        registry.abortOpen(LinkKind.UWB_TCP_SERVER, "监听端口被占");

        assertEquals(4, push.count());
        assertEquals(2, push.at(2).stream().filter(status -> status.kind() == LinkKind.UWB_TCP_SERVER)
                .findFirst().orElseThrow().fdCount());
        assertEquals(LinkState.ERROR, push.stateOf(3, LinkKind.UWB_TCP_SERVER));
    }

    @Test
    void perFrameCountersDoNotFanOut() {
        RecordingPush push = new RecordingPush();
        LinkRegistry registry = new LinkRegistry(push);
        LinkSlot slot = registry.beginOpen(LinkKind.SERIAL_VEST, "COM7", "COM7", 115200);
        registry.finishOpen(LinkKind.SERIAL_VEST);
        int before = push.count();

        slot.recordFrame();
        slot.recordFrame();
        slot.recordDropped();
        slot.recordCrcError();

        // 契约只要求链路状态变化时推送；逐帧重推会淹没真正的变化（设计 02 §6 Q7）
        assertEquals(before, push.count(), "逐帧热路径不触发推送");
        assertEquals(2L, of(registry, LinkKind.SERIAL_VEST).framesTotal(), "计数照常累加，只是不推");
    }

    @Test
    void conflictingOpenDoesNotFanOut() {
        RecordingPush push = new RecordingPush();
        LinkRegistry registry = new LinkRegistry(push);
        registry.beginOpen(LinkKind.SERIAL_VEST, "COM7", "COM7", 115200);
        int before = push.count();

        assertThrows(BusinessException.class,
                () -> registry.beginOpen(LinkKind.SERIAL_VEST, "COM7", "COM7", 115200));

        assertEquals(before, push.count(), "冲突被挡下时状态没有迁移，不该推送");
    }

    @Test
    void pushFailureDoesNotBreakTheStateMachine() {
        LinkRegistry registry = new LinkRegistry(snapshot -> {
            throw new IllegalStateException("客户端连接已断");
        });

        LinkSlot slot = registry.beginOpen(LinkKind.SERIAL_VEST, "COM7", "COM7", 115200);

        // 推送异常绝不能让「链路其实已经开了」这件事对调用方表现为失败
        assertEquals(LinkState.OPENING, of(registry, LinkKind.SERIAL_VEST).state());
        assertTrue(slot.active());
    }
}
