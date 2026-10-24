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
 * <p><b>开启语义（与契约一致；2026-10-23 收口 R15）</b>：目标链路已处于
 * {@code OPENING}/{@code OPEN} 时，{@code open*} 一律抛 {@code DEVICE_LINK_ALREADY_OPEN}
 * （HTTP 409），<b>不隐式先关闭再重开</b>。理由是客户端必须能区分「我要开的链路已经在开」
 * 与「链路中途断过又重连」；需要重连时由调用方先显式 {@code close*} 再 {@code open*}。</p>
 *
 * <p><b>两种 409 的区别是「谁占着」</b>：{@code DEVICE_LINK_ALREADY_OPEN} 是同一条逻辑链路的
 * 重复开启请求；{@code DEVICE_LINK_SERIAL_BUSY} 是物理资源冲突（同一串口已被本进程另一条链路持有，
 * 或打开时被系统拒绝）。实现方约定：同一物理串口同一时刻只允许被一条链路持有。</p>
 *
 * <p>关闭链路必须释放底层资源（读线程、发送队列、Netty 资源）且可重复调用（幂等）；
 * 未打开时调用 {@code close*} 无副作用。</p>
 */
public interface DeviceLinkAdmin {

    /** 枚举本机可用串口。 */
    List<SerialPortInfo> listSerialPorts();

    /** 打开马甲串口链路；已打开时抛 {@code DEVICE_LINK_ALREADY_OPEN}。 */
    LinkStatus openSerial(LinkConfig config);

    /** 关闭马甲串口链路；未打开时无副作用（幂等）。 */
    LinkStatus closeSerial();

    /** 启动 UWB TCP Server（硬约束端口 12001）；已启动时抛 {@code DEVICE_LINK_ALREADY_OPEN}。 */
    LinkStatus openUwbTcpServer(LinkConfig config);

    /**
     * 打开 UWB 串口链路（与 {@link #openUwbTcpServer} **二选一，互斥**）。
     *
     * <p>两种 UWB 接入方式同时启用会让连续坐标互相污染，因此任一模式已开时，
     * 另一种模式的 {@code open} 同样抛 {@code DEVICE_LINK_ALREADY_OPEN}——
     * 切换接入方式必须先显式关闭当前模式。</p>
     */
    LinkStatus openUwbSerial(LinkConfig config);

    /** 停止 UWB TCP Server 并断开所有接入连接；未启动时无副作用（幂等）。 */
    LinkStatus closeUwbTcpServer();

    /** 关闭 UWB 串口链路；未打开时无副作用（幂等）。 */
    LinkStatus closeUwbSerial();

    /**
     * 当前所有链路的运行态快照。
     *
     * <p>对三种 {@link com.dualx.core.domain.LinkKind} **恒定各返回一条**：未配置过即
     * {@code state=CLOSED}、目标为 {@code null}。这样 HTTP {@code GET /api/v1/devices/links}
     * 与 WebSocket {@code /ws/v1/devices/links} 的载荷同构且稳定，客户端不必区分
     * 「链路缺席」与「链路关闭」。</p>
     */
    List<LinkStatus> snapshot();

    /** 向指定马甲下发一条已编码的下行帧（编码由 {@code com.dualx.protocol} 提供）。 */
    void sendVestCommand(int vestNum, byte[] payload);
}
