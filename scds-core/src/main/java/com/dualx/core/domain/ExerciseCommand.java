package com.dualx.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 下行指令队列（{@code t_exercise_command}）。
 *
 * <p>与 {@code t_exercise_log} 的分工：本表是「待发/已发未确认的工作队列」，指令送达即离队
 * （可清理）；日志表是「只增的流水」，指令一发出就记一条，永不清理。
 *
 * <p>之所以把队列落库而不只放内存：马甲离线时指令要挂起等待重连（契约
 * {@code CommandAck.accepted=false} 即此语义），进程重启不能让挂起的指令凭空消失。
 *
 * @param id          自增代理键，由库生成
 * @param command     指令名，如 DIE / RELOAD / CONFIG（无对应契约枚举，故为自由文本）
 * @param payloadJson 指令参数的结构化快照，原样存取；JSON 合法性由服务层保证
 * @param downlinkHex 组装出的下行原文；PENDING 阶段可能尚未生成
 */
public record ExerciseCommand(
        long id,
        UUID exerciseId,
        int vestNum,
        String command,
        ExerciseCommandState state,
        String payloadJson,
        String downlinkHex,
        String operatorName,
        Instant queuedAt,
        Instant sentAt,
        Instant finishedAt,
        String failReason) {
}
