package com.dualx.api.dto.device;

/**
 * 本机可用串口条目（契约 paths/device.yaml → {@code listSerialPorts} 响应项）。
 *
 * @param portName    串口名，如 COM7
 * @param description 串口描述，可能为空
 */
public record SerialPortInfo(String portName, String description) {
}
