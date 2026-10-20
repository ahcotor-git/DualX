package com.dualx.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 复盘导出任务（{@code t_export_task}，契约 {@code AsyncTask}）。
 *
 * <p>文件本体走存储，本记录只承载任务状态与文件位置；{@code filePath} 不对外暴露，
 * 下载走一次性签名 URL。
 *
 * <p>{@code replayId} 即 {@code t_exercise.id}——「演习」与「复盘」是同一行。
 *
 * @param progressPct 0~100，库层有 CHECK 兜底
 * @param expireAt    文件过期时刻；过期后需重新导出
 */
public record ExportTask(
        UUID id,
        UUID replayId,
        ExportFormat format,
        AsyncTaskState state,
        int progressPct,
        String fileName,
        String filePath,
        String errorMessage,
        Instant expireAt,
        Instant createdAt,
        Instant updatedAt) {
}
