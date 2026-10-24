package com.dualx.gateway.serial;

import com.dualx.core.port.SerialPortInfo;
import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SerialChannel} 的 jSerialComm 实现。
 *
 * <p>参数按 PROTOCOL.md §1.1：115200 / 8 数据位 / 1 停止位 / 无校验，流控 RTS-CTS 默认关闭。
 * 读超时用半阻塞模式：既不会忙等烧 CPU，也不会长时间霸占读线程。</p>
 *
 * <p>jSerialComm 的 {@code openPort()} 只返回布尔值、不区分「不存在」与「被占用」，
 * 因此统一折成「打开失败」，由链路层转成 409 {@code DEVICE_LINK_SERIAL_BUSY}——
 * 两者的现场处置动作都是「换口或先释放占用者」，契约也把它们并成同一个码。</p>
 */
public final class JSerialChannel implements SerialChannel {

    /** 数据位固定 8；本版本 jSerialComm 未提供 {@code EIGHT_DATA_BITS} 常量。 */
    private static final int DATA_BITS = 8;

    private final SerialPort port;
    private final int readTimeoutMs;

    public JSerialChannel(String portName, int baudRate, boolean flowControlRtsCts, int readTimeoutMs) {
        this.port = SerialPort.getCommPort(portName);
        this.readTimeoutMs = readTimeoutMs;
        port.setComPortParameters(baudRate, DATA_BITS, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setFlowControl(flowControlRtsCts
                ? SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED
                : SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, readTimeoutMs, 0);
    }

    /** 枚举本机可用串口（契约 {@code GET /api/v1/devices/serialPorts}）。 */
    public static List<SerialPortInfo> enumerate() {
        SerialPort[] ports = SerialPort.getCommPorts();
        List<SerialPortInfo> found = new ArrayList<>(ports.length);
        for (SerialPort candidate : ports) {
            found.add(new SerialPortInfo(candidate.getSystemPortName(), candidate.getDescriptivePortName()));
        }
        return List.copyOf(found);
    }

    @Override
    public void open() throws IOException {
        if (port.isOpen()) {
            return;
        }
        if (!port.openPort()) {
            throw new IOException("打开串口失败：" + target() + "（不存在或已被占用）");
        }
    }

    @Override
    public boolean isOpen() {
        return port.isOpen();
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        int read = port.readBytes(buffer, buffer.length);
        if (read < 0) {
            if (!port.isOpen()) {
                throw new IOException("串口已断开：" + target());
            }
            return 0;
        }
        return read;
    }

    @Override
    public void write(byte[] data) throws IOException {
        int written = port.writeBytes(data, data.length);
        if (written != data.length) {
            throw new IOException("串口写入不完整：" + target() + " 期望 " + data.length + "B，实际 " + written + "B");
        }
    }

    @Override
    public void close() {
        if (port.isOpen()) {
            port.closePort();
        }
    }

    @Override
    public String target() {
        return port.getSystemPortName();
    }

    @Override
    public String toString() {
        return "JSerialChannel[" + target() + " readTimeout=" + readTimeoutMs + "ms]";
    }
}
