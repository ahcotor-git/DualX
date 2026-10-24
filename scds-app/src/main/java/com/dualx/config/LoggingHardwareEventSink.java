package com.dualx.config;

import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.port.HardwareEventSink;
import com.dualx.protocol.serial.SerialFrame;
import com.dualx.protocol.uwb.UwbFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行帧与链路状态的**日志型** sink（设计 docs/design/05-hw-link.md §4.7）。
 *
 * <p><b>它是明确标注的临时实现，不是最终消费者</b>：把帧翻成业务事件（命中、轨迹、日志、
 * 在线心跳）属 STATE 工作包。在有真正的 sink 之前，本类存在的意义是让「上行帧到端口边界
 * 为止」这条边界<b>以日志形式暴露</b>，而不是让帧静默消失——静默消失会让「网关没解出帧」
 * 与「有帧但没人要」变成同一个现象，现场无法区分。</p>
 *
 * <p><b>不阻塞读线程</b>：本类只写日志，不入队、不落库、不等待。它会被串口读线程与 Netty
 * worker 直接调用，任何阻塞都会拖死链路（{@link HardwareEventSink} 的实现方约定）。</p>
 *
 * <p><b>帧级日志是 DEBUG</b>：现场按 200ms 轮询 + 多马甲上报，INFO 级打每帧会淹没日志；
 * 需要排障时临时把本类日志级别调到 DEBUG，而不是让它常态占满磁盘。</p>
 */
public final class LoggingHardwareEventSink implements HardwareEventSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingHardwareEventSink.class);

    public LoggingHardwareEventSink() {
        log.warn("上行帧当前无业务消费者：已装配日志型 sink（{}）。帧到 HardwareEventSink 边界为止，"
                + "不落库、不推送、不解算；真正的业务消费在 STATE 工作包落地。", getClass().getSimpleName());
    }

    @Override
    public void onSerialFrame(SerialFrame frame, LinkKind source) {
        if (log.isDebugEnabled()) {
            log.debug("上行串口帧[{}]：{}", source, frame);
        }
    }

    @Override
    public void onUwbFrame(UwbFrame frame, LinkKind source) {
        if (log.isDebugEnabled()) {
            // 只打马甲号与测距数组长度：UwbFrame 的 rangesMm 是 int[]，直接打会输出数组标识而非内容。
            log.debug("上行 UWB 帧[{}]：马甲 {}，测距 {} 项", source, frame.vestNum(),
                    frame.rangesMm() == null ? 0 : frame.rangesMm().length);
        }
    }

    @Override
    public void onLinkStateChanged(LinkKind kind, LinkState state, String message) {
        if (state == LinkState.ERROR) {
            log.warn("链路状态变化：{} → {}{}", kind, state, suffix(message));
        } else {
            log.info("链路状态变化：{} → {}{}", kind, state, suffix(message));
        }
    }

    private static String suffix(String message) {
        return message == null || message.isBlank() ? "" : "（" + message + "）";
    }
}
