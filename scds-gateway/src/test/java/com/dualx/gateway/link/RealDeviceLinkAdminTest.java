package com.dualx.gateway.link;

import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.HardwareEventSink;
import com.dualx.protocol.serial.SerialFrame;
import com.dualx.protocol.uwb.UwbFrame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复合适配器的分发验证（真链路版）。
 *
 * <p><b>刻意不碰硬件</b>：不开真串口、不绑 12001。用「各条链路独有的校验失败」来证明端口方法
 * 落到了正确的句柄上——例如空串口名在三条链路上给出三句不同的文案，谁抛的就说明谁被调到，
 * 比对着一份「哪个方法调了哪个对象」的桩断言更接近真实接线。</p>
 *
 * <p>成功路径（真开串口 / 真绑 12001 收帧）留给 H7 的端到端 IT 与现场验收；
 * 本类只保证「分发没有接错线」。</p>
 */
class RealDeviceLinkAdminTest {

    private final LinkRegistry registry = new LinkRegistry();
    private final HardwareEventSink sink = new NoopSink();
    private final RealDeviceLinkAdmin admin = new RealDeviceLinkAdmin(registry, sink, 20, 10);

    @Test
    void snapshotHasThreeKindsAndStartsClosed() {
        List<LinkStatus> snapshot = admin.snapshot();

        assertEquals(List.of(LinkKind.SERIAL_VEST, LinkKind.UWB_TCP_SERVER, LinkKind.UWB_SERIAL),
                snapshot.stream().map(LinkStatus::kind).toList(),
                "快照必须对三种链路恒定各一条，HTTP 与 WS 载荷才能同构");
        assertTrue(snapshot.stream().allMatch(status -> status.state() == LinkState.CLOSED));
        assertTrue(snapshot.stream().allMatch(status -> status.target() == null));
    }

    @Test
    void blankPortNameIsRejectedByTheOwningLink() {
        BusinessException vest = assertThrows(BusinessException.class,
                () -> admin.openSerial(serialConfig(LinkKind.SERIAL_VEST, "  ")));
        assertEquals(ApiErrorCode.VALIDATION_FAILED, vest.code());
        assertTrue(vest.getMessage().contains("马甲串口"), "应落到马甲串口链路：" + vest.getMessage());

        BusinessException uwb = assertThrows(BusinessException.class,
                () -> admin.openUwbSerial(serialConfig(LinkKind.UWB_SERIAL, null)));
        assertEquals(ApiErrorCode.VALIDATION_FAILED, uwb.code());
        assertTrue(uwb.getMessage().contains("UWB 串口"), "应落到 UWB 串口链路：" + uwb.getMessage());

        // 校验在占坑之前判出：失败不留半开状态
        assertEquals(LinkState.CLOSED, statusOf(LinkKind.SERIAL_VEST).state());
        assertEquals(LinkState.CLOSED, statusOf(LinkKind.UWB_SERIAL).state());
    }

    @Test
    void uwbTcpPortIsPinnedToTheHardConstraint() {
        BusinessException failure = assertThrows(BusinessException.class,
                () -> admin.openUwbTcpServer(uwbTcpConfig(9999)));

        assertEquals(ApiErrorCode.VALIDATION_FAILED, failure.code());
        assertTrue(failure.getMessage().contains("12001"), "端口硬约束由网关判定：" + failure.getMessage());
        assertEquals(LinkState.CLOSED, statusOf(LinkKind.UWB_TCP_SERVER).state());
    }

    @Test
    void eachCloseRoutesToItsOwnKindAndIsIdempotent() {
        assertEquals(LinkKind.SERIAL_VEST, admin.closeSerial().kind());
        assertEquals(LinkKind.SERIAL_VEST, admin.closeSerial().kind());

        assertEquals(LinkKind.UWB_TCP_SERVER, admin.closeUwbTcpServer().kind());
        assertEquals(LinkKind.UWB_TCP_SERVER, admin.closeUwbTcpServer().kind());

        assertEquals(LinkKind.UWB_SERIAL, admin.closeUwbSerial().kind());
        assertEquals(LinkKind.UWB_SERIAL, admin.closeUwbSerial().kind());

        assertTrue(admin.snapshot().stream().allMatch(status -> status.state() == LinkState.CLOSED));
    }

    @Test
    void listSerialPortsDelegatesToTheRealEnumerator() {
        // 本机可能一个串口都没有，故只断言「调用得通且不回 null」——
        // 枚举结果与硬件有关，写死数量会在换机器时假失败。
        assertNotNull(admin.listSerialPorts());
    }

    @Test
    void sendVestCommandGoesThroughTheVestLink() {
        BusinessException failure = assertThrows(BusinessException.class,
                () -> admin.sendVestCommand(2, new byte[0]));

        assertEquals(ApiErrorCode.VALIDATION_FAILED, failure.code());
        assertTrue(failure.getMessage().contains("下行帧"), "空载荷应由马甲串口链路拒绝：" + failure.getMessage());
    }

    private LinkStatus statusOf(LinkKind kind) {
        return admin.snapshot().stream()
                .filter(status -> status.kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private static LinkConfig serialConfig(LinkKind kind, String portName) {
        return new LinkConfig(kind, portName, null, false, null, null, false, null, null, null);
    }

    private static LinkConfig uwbTcpConfig(Integer port) {
        return new LinkConfig(LinkKind.UWB_TCP_SERVER, null, null, false, "127.0.0.1", port, false, null, null, null);
    }

    /** 本类只验分发，上行回调不参与断言。 */
    private static final class NoopSink implements HardwareEventSink {

        @Override
        public void onSerialFrame(SerialFrame frame, LinkKind source) {
        }

        @Override
        public void onUwbFrame(UwbFrame frame, LinkKind source) {
        }

        @Override
        public void onLinkStateChanged(LinkKind kind, LinkState state, String message) {
        }
    }
}
