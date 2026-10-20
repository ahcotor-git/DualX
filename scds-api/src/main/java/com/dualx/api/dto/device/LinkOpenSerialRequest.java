package com.dualx.api.dto.device;

import jakarta.validation.constraints.NotBlank;

/**
 * 开启马甲串口链路请求体（契约 domain.yaml → {@code LinkOpenSerialRequest}）。
 *
 * <p>协议 A 为 ASCII 文本 HEX，默认 115200 波特率；串口枚举与占用检查归网关层实现。</p>
 *
 * @param portName          串口名，如 COM7
 * @param baudRate          波特率，默认 115200
 * @param flowControlRtsCts 是否启用 RTS/CTS 流控，默认 false
 */
public record LinkOpenSerialRequest(@NotBlank String portName,
                                    Integer baudRate,
                                    Boolean flowControlRtsCts) {
}
