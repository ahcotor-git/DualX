package com.dualx.core.port;

import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkStatus;

import java.util.List;

/**
 * 硬件链路的**下行**端口（Ports &amp; Adapters 中的 Port）。
 *
 * <p>由业务核心（{@code scds-core}）调用、由硬件网关（{@code scds-gateway}）实现。
 * 接口定义在领域内核而非网关侧，因此 core 只依赖自己的抽象、gateway 只依赖本模块完成适配，
 * 两侧互不依赖，既无循环依赖，也不会把 Netty/串口库拖进业务核心。</p>
 *
 * <p>实现方约定：同一物理串口同一时刻只允许被一条链路持有（对应契约错误码
 * {@code DEVICE_LINK_SERIAL_BUSY}）；关闭链路必须释放底层资源且可重复调用（幂等）。</p>
 */
public interface DeviceLinkAdmin {

    /** 枚举本机可用串口。 */
    List<SerialPortInfo> listSerialPorts();

    /** 打开马甲串口链路；已打开则先关闭再重开。 */
    LinkStatus openSerial(LinkConfig config);

    /** 关闭马甲串口链路；未打开时无副作用。 */
    LinkStatus closeSerial();

    /** 启动 UWB TCP Server（硬约束端口 12001）；已启动则先停再起。 */
    LinkStatus openUwbTcpServer(LinkConfig config);

    /** 停止 UWB TCP Server 并断开所有接入连接。 */
    LinkStatus closeUwbTcpServer();

    /**
     * 当前所有链路的运行态快照。
     *
     * <p>HTTP {@code GET /api/v1/devices/links} 与 WebSocket {@code /ws/v1/devices/links}
     * 共用这一份真源，以保证双通道载荷同构。</p>
     */
    List<LinkStatus> snapshot();

    /** 向指定马甲下发一条已编码的下行帧（编码由 {@code com.dualx.protocol} 提供）。 */
    void sendVestCommand(int vestNum, byte[] payload);
}
