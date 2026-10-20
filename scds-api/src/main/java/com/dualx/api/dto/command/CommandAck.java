package com.dualx.api.dto.command;

import java.time.Instant;

/**
 * 指挥指令受理回执（契约 schemas/domain.yaml → {@code CommandAck}），command 域全部接口的 200 响应体。
 *
 * <p>{@code accepted=false} 仍属「成功受理」（马甲离线 / 队列满），前端应提示而非报错。</p>
 *
 * @param vestNum     目标马甲编号
 * @param command     指令名，如 DIE / RELOAD
 * @param accepted    是否入队；false 表示已受理但设备未确认
 * @param queuedAt    入队时刻
 * @param downlinkHex 下发指令十六进制原文（逐字节可核对）
 */
public record CommandAck(
        int vestNum,
        String command,
        boolean accepted,
        Instant queuedAt,
        String downlinkHex) {
}
