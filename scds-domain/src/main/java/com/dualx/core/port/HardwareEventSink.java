package com.dualx.core.port;

import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.protocol.serial.SerialFrame;
import com.dualx.protocol.uwb.UwbFrame;

/**
 * 硬件链路的**上行**端口：网关解出完整帧后回调业务核心。
 *
 * <p>由硬件网关（{@code scds-gateway}）调用、由业务核心（{@code scds-core}）实现。
 * 网关只负责「字节流 → 完整帧」的分片重组与 CRC 校验；帧到业务事件（命中、轨迹、日志、
 * 在线心跳）的翻译一律留在核心侧，网关保持哑管道。</p>
 *
 * <p>实现方约定：本接口会被 Netty / 串口的读线程直接调用，实现内部若需阻塞操作
 * （落库、推送），必须自行切换到有界队列 + 工作线程，不得阻塞读线程。</p>
 */
public interface HardwareEventSink {

    /** 收到一整帧合法马甲串口帧。 */
    void onSerialFrame(SerialFrame frame, LinkKind source);

    /** 收到一整帧合法 UWB 二进制帧。 */
    void onUwbFrame(UwbFrame frame, LinkKind source);

    /** 链路开关状态变化（OPENING / OPEN / CLOSED / ERROR），用于推送与健康检查。 */
    void onLinkStateChanged(LinkKind kind, LinkState state, String message);
}
