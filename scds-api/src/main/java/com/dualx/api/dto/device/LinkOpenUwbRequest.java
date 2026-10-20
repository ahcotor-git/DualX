package com.dualx.api.dto.device;

import jakarta.validation.constraints.NotNull;

/**
 * 开启 UWB 链路请求体（契约 domain.yaml → {@code LinkOpenUwbRequest}）。
 *
 * <p>TCP_SERVER 时服务端监听（端口硬约束 12001，归属网关层，禁止改动）；SERIAL 时占用串口，二者二选一。</p>
 *
 * @param dataSource     接入方式，TCP_SERVER 或 SERIAL
 * @param host           监听地址，仅 TCP_SERVER 有效，默认 0.0.0.0
 * @param port           监听端口，仅 TCP_SERVER 有效，默认 12001
 * @param serialPortName 串口名，仅 SERIAL 有效
 */
public record LinkOpenUwbRequest(@NotNull DataSource dataSource,
                                 String host,
                                 Integer port,
                                 String serialPortName) {

    /** UWB 接入方式（契约 domain.yaml → {@code LinkOpenUwbRequest.dataSource}）。 */
    public enum DataSource {
        TCP_SERVER,
        SERIAL
    }
}
