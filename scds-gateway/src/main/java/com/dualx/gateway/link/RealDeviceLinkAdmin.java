package com.dualx.gateway.link;

import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.core.port.HardwareEventSink;
import com.dualx.core.port.SerialPortInfo;
import com.dualx.gateway.serial.JSerialChannel;
import com.dualx.gateway.serial.SerialChannel;
import com.dualx.gateway.serial.SerialVestLink;
import com.dualx.gateway.uwb.UwbSerialLink;
import com.dualx.gateway.uwb.UwbTcpServerLink;
import com.dualx.protocol.ProtocolConstants;

import java.util.List;

/**
 * 真链路装配：把三种物理链路（马甲串口 / UWB TCP_SERVER / UWB 串口）聚合成一个
 * {@link DeviceLinkAdmin}（设计 docs/design/05-hw-link.md §4.1、§4.2）。
 *
 * <p><b>它自己不含任何业务判定</b>：能否开、什么算冲突、串口归谁独占，全部由共用的
 * {@link LinkRegistry} 决定；本类只做「哪个端口方法该落到哪条链路句柄」的分发。
 * 因此它与 {@code com.dualx.gateway.sim.SimulatedLinkAdapter} 走的是同一套状态机语义——
 * 仿真与真链路在 §7 判据上可对拍，差别只在物理动作有没有真的发生。</p>
 *
 * <p><b>端口边界</b>：{@code scds-api} 不依赖本模块，{@code DeviceLinkAdmin} 的实现 bean
 * 由 {@code scds-app} 装配层二选一（真链路 / 仿真），见 {@code com.dualx.config.GatewayConfig}。</p>
 *
 * <p><b>UWB TCP 端口不做注入</b>：{@link UwbTcpServerLink} 只提供生产构造器，恒为硬约束
 * {@link ProtocolConstants#UWB_TCP_SERVER_PORT}（12001）。测试要换端口请直接构造该链路，
 * 别在这里开口子。</p>
 */
public final class RealDeviceLinkAdmin implements DeviceLinkAdmin {

    private final LinkRegistry registry;
    private final SerialVestLink vestLink;
    private final UwbTcpServerLink uwbTcpServerLink;
    private final UwbSerialLink uwbSerialLink;

    public RealDeviceLinkAdmin(LinkRegistry registry, HardwareEventSink sink, int sendQueueCapacity) {
        this(registry, sink, ProtocolConstants.POLLING_INTERVAL_MS, sendQueueCapacity);
    }

    /** 供测试注入更短的轮询周期，避免断言等 200ms。 */
    public RealDeviceLinkAdmin(LinkRegistry registry,
                               HardwareEventSink sink,
                               int pollIntervalMs,
                               int sendQueueCapacity) {
        this.registry = registry;
        this.vestLink = new SerialVestLink(registry, sink, RealDeviceLinkAdmin::openJSerial,
                pollIntervalMs, sendQueueCapacity);
        this.uwbTcpServerLink = new UwbTcpServerLink(registry, sink);
        this.uwbSerialLink = new UwbSerialLink(registry, sink);
    }

    @Override
    public List<SerialPortInfo> listSerialPorts() {
        return JSerialChannel.enumerate();
    }

    @Override
    public LinkStatus openSerial(LinkConfig config) {
        return vestLink.open(config);
    }

    @Override
    public LinkStatus closeSerial() {
        return vestLink.close();
    }

    @Override
    public LinkStatus openUwbTcpServer(LinkConfig config) {
        return uwbTcpServerLink.open(config);
    }

    @Override
    public LinkStatus openUwbSerial(LinkConfig config) {
        return uwbSerialLink.open(config);
    }

    @Override
    public LinkStatus closeUwbTcpServer() {
        return uwbTcpServerLink.close();
    }

    @Override
    public LinkStatus closeUwbSerial() {
        return uwbSerialLink.close();
    }

    @Override
    public List<LinkStatus> snapshot() {
        return registry.snapshot();
    }

    @Override
    public void sendVestCommand(int vestNum, byte[] payload) {
        vestLink.send(payload);
    }

    /**
     * 生产串口工厂。
     *
     * <p>波特率兜底必须在这里做：{@link SerialVestLink} 只把默认值写进链路状态（给人看），
     * 传给工厂的仍是原始 {@link LinkConfig}，{@code serialBaudRate} 可能为 null。
     * 少这一步就会拿 null 去开串口。</p>
     */
    private static SerialChannel openJSerial(LinkConfig config) {
        int baudRate = config.serialBaudRate() != null
                ? config.serialBaudRate()
                : ProtocolConstants.SERIAL_BAUD_RATE;
        return new JSerialChannel(config.serialPortName(), baudRate,
                config.serialFlowControlRtsCts(), ProtocolConstants.POLLING_TIMEOUT_MS);
    }
}
