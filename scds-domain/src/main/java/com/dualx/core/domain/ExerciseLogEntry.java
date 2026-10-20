package com.dualx.core.domain;

import com.dualx.protocol.LogType;

import java.time.Instant;
import java.util.UUID;

/**
 * 演习日志流水（{@code t_exercise_log}），指挥台右侧滚动日志的数据源。只增不改不删。
 *
 * <p>与 {@code AuditLog} 职责不重叠：那张表回答「谁在何时下了命令」，本表回答
 * 「这次演习里发生了什么」——协议自动产生的事件没有操作者，人工下发的才有。
 *
 * @param seq          自增主键，由库生成。对外直接充当契约的 {@code ExerciseLogEntry.seq}，
 *                     客户端以它做 {@code sinceSeq} 增量拉取
 * @param vestNum      与马甲无关的系统级日志为 null
 * @param operatorName 人工下发时的账号名；协议自动产生为 null
 * @param downlinkHex  下行指令十六进制原文，逐字节可核对（排障用）
 */
public record ExerciseLogEntry(
        long seq,
        UUID exerciseId,
        Instant at,
        LogType logType,
        Integer vestNum,
        String operatorName,
        String detail,
        String downlinkHex) {
}
