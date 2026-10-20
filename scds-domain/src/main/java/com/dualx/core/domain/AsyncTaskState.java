package com.dualx.core.domain;

/** 异步任务状态（契约 {@code AsyncTaskState}）。 */
public enum AsyncTaskState {

    /** 已受理，尚未开始。 */
    PENDING,
    /** 执行中。 */
    RUNNING,
    /** 成功，产物可下载。 */
    SUCCEEDED,
    /** 失败，理由见 {@code errorMessage}。 */
    FAILED
}
