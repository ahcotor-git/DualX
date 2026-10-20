package com.dualx.api.dto.replay;

import com.dualx.core.domain.ExportFormat;
import jakarta.validation.constraints.NotNull;

/**
 * 复盘导出请求（契约 domain.yaml → {@code ExportRequest}），
 * {@code POST /api/v1/replays/{replayId}/exports} 的请求体。
 *
 * @param format 导出文件格式，复用核心领域枚举 {@code ExportFormat}（XLSX/CSV）
 */
public record ExportRequest(@NotNull ExportFormat format) {
}
