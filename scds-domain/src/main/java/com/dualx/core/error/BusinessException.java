package com.dualx.core.error;

import java.time.Duration;

/**
 * 业务失败：core 把「哪里不合规」告诉接口层，接口层再决定 HTTP 形状。
 *
 * <p>core 只携带 {@link ApiErrorCode}，不携带 HTTP 状态——状态码是接口层的事，
 * 让领域代码去判「该 409 还是 400」会把契约细节漏进领域层。码到状态的映射表在
 * api 的 {@code ApiExceptionHandler}。</p>
 *
 * <p>调用点只该抛具体原因，不要把可预期的分支写成返回值：契约里所有失败共用一种
 * 响应形状，异常出口天然让它保持一种。</p>
 */
public class BusinessException extends RuntimeException {

    private final ApiErrorCode code;
    private final Duration retryAfter;

    public BusinessException(ApiErrorCode code, String message) {
        this(code, message, null);
    }

    /**
     * @param retryAfter 仅 {@link ApiErrorCode#RATE_LIMITED} 使用：写进 429 的
     *                   {@code Retry-After} 头，让客户端知道多久后可以再试
     */
    public BusinessException(ApiErrorCode code, String message, Duration retryAfter) {
        super(message);
        this.code = code;
        this.retryAfter = retryAfter;
    }

    /** 登录限流：把剩余锁定时间一并带出去。 */
    public static BusinessException rateLimited(Duration retryAfter) {
        return new BusinessException(ApiErrorCode.RATE_LIMITED, "登录尝试过于频繁，请稍后再试", retryAfter);
    }

    public ApiErrorCode code() {
        return code;
    }

    /** 仅限流场景非空。 */
    public Duration retryAfter() {
        return retryAfter;
    }
}
