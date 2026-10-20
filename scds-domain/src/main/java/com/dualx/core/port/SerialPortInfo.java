package com.dualx.core.port;

/**
 * 本机可用串口条目（契约 paths/device.yaml → {@code listSerialPorts} 响应项）。
 *
 * <p>由 {@link DeviceLinkAdmin#listSerialPorts()} 返回，属于「硬件端口」契约的一部分：
 * 串口枚举能力只存在于网关侧，api 侧不应感知 jSerialComm。</p>
 *
 * @param portName    串口名，如 COM7
 * @param description 串口描述，可能为空
 */
public record SerialPortInfo(String portName, String description) {
}
