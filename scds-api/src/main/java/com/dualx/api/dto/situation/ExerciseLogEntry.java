package com.dualx.api.dto.situation;

import com.dualx.protocol.LogType;

import java.time.Instant;

/**
 * 演习日志流水（契约 situation.yaml → {@code ExerciseLogEntry}），指挥台右侧滚动日志。
 *
 * @param seq         日志序号，客户端以其做增量拉取（sinceSeq）
 * @param at          记录时刻
 * @param type        日志类型
 * @param vestNum     关联马甲编号；系统级日志为 null
 * @param operator    手动下发的操作者账号；协议自动产生的为 null
 * @param detail      详情
 * @param downlinkHex 对应下发指令的十六进制原文，逐字节可核对（排障用）
 */
public record ExerciseLogEntry(
        long seq,
        Instant at,
        LogType type,
        Integer vestNum,
        String operator,
        String detail,
        String downlinkHex) {
}
