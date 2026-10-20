package com.dualx.api.dto.common;

import com.dualx.core.domain.AsyncTaskState;

import java.time.Instant;

/**
 * 异步任务（契约 common.yaml → {@code AsyncTask}），导出类接口的 202 响应体与查询体。
 *
 * @param taskId      任务 ID
 * @param state       任务状态，枚举取值复用仓储层 {@code AsyncTaskState}（与契约逐字一致）
 * @param progressPct 进度百分比 0~100
 * @param fileName    产物文件名，成功前为 null
 * @param downloadUrl 一次性签名下载地址，仅 {@code state=SUCCEEDED} 时有效
 * @param expireAt    产物过期时刻
 * @param errorMessage 失败原因，仅 {@code state=FAILED} 时有效
 */
public record AsyncTask(String taskId, AsyncTaskState state, Integer progressPct, String fileName,
                        String downloadUrl, Instant expireAt, String errorMessage) {
}
