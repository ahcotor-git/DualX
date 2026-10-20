package com.dualx.api.error;

/**
 * 骨架期占位异常。
 *
 * <p>API 层已按契约登记全部路由、参数与 DTO，但业务实现归属服务层（阶段 4）。
 * 占位方法统一抛本异常，由 {@link ApiExceptionHandler} 转成 501 + {@code Problem}，
 * 于是「契约已立起」与「业务未实现」两件事在响应上可区分、可断言。</p>
 */
public class NotImplementedException extends RuntimeException {

    private final String operationId;

    public NotImplementedException(String operationId) {
        super("接口尚未实现：" + operationId);
        this.operationId = operationId;
    }

    /** 契约里的 operationId，排障时用于直接定位到 paths/*.yaml 的哪一段。 */
    public String operationId() {
        return operationId;
    }
}
