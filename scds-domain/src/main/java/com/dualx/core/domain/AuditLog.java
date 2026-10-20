package com.dualx.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 操作审计（对应 t_audit_log）。只增不改，记「人做了什么动作」。
 *
 * <p>下行指令的原文（downlink_hex）不在这里，而在 t_exercise_log——两者职责不重叠：
 * 本表回答「谁在何时下了命令」，那张表回答「发出去的字节是什么」。
 *
 * @param id        自增主键，由库生成
 * @param accountId 系统自身动作（如自动停演）时为 null
 * @param detailJson 动作参数的结构化快照，原样存取；JSON 合法性由服务层保证
 */
public record AuditLog(
        long id,
        UUID accountId,
        String action,
        String targetType,
        String targetId,
        String detailJson,
        String clientIp,
        Instant at) {
}
