package com.dualx.core.port;

/**
 * 文件访问签名端口（D09-②⑤）：为受保护文件签发**短时效、可重复使用**的访问凭据。
 *
 * <p><b>为什么不做一次性</b>：一次性签名 URL 需要服务端消费记录、失效判重与客户端重试设计，
 * 而它要防的「URL 被转发」在本项目里价值有限——单实例、内网/受控穿透、TTL 只有 15 分钟。
 * 可重复签名让 {@code <img>} 刷新、断网重拉都不必回到授权路径重取，代价小得多。</p>
 *
 * <p><b>为什么不复用 JWT 的 RSA 私钥</b>：签名 URL 与令牌是两个信任边界，共用密钥会让
 * 「轮换文件密钥」变成「顺带让所有人重新登录」。故本端口用独立的 HMAC 对称密钥。</p>
 *
 * <p><b>本端口不含 URL 组装</b>：它只负责「签什么、验得过不过」，
 * 前缀、路由形状与查询参数命名属接口层（{@code scds-api}），换部署形态不必动端口。</p>
 */
public interface FileUrlSigner {

    /** 为相对路径签发一份签名（含到期时刻与签名值）。 */
    SignedFileUrl sign(String relativePath);

    /**
     * 校验签名与有效期。
     *
     * <p>任何一项不成立都返回 {@code false}，不区分「签名错」与「已过期」——
     * 调用方对两者的响应是同一个（拒绝访问），区分只会给探测者提供额外信息。</p>
     */
    boolean verify(String relativePath, String exp, String signature);

    /**
     * 签名结果。两个字段都是字符串，因为它们最终以查询参数形式出现在 URL 上：
     * {@code exp} 是 Unix 秒（十进制），{@code signature} 是 URL-safe Base64（无填充）。
     */
    record SignedFileUrl(String exp, String signature) {
    }
}
