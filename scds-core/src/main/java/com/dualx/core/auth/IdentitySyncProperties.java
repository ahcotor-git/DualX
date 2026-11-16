package com.dualx.core.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 边缘账号身份同步配置（{@code scds.identity.*}）。
 *
 * <p>边缘只验签云端令牌（设计 03b §2），但 {@code GET /api/v1/auth/me} 需要本地
 * {@code t_account} ＋ {@code t_account_role} 才能返回完整 {@code User}。故由运维在云端离线导出
 * 「账号快照文件」，边缘一次性导入（设计 03c §2–§4）。本属性只承载该文件的路径。</p>
 *
 * <p>默认**无此键** ⇒ 不触发导入；CLOUD 形态下由部署侧按需显式给出路径。</p>
 *
 * @param importFile 云端导出的账号快照文件路径；未配置（或仅空白）时为 {@code null}
 */
@ConfigurationProperties(prefix = "scds.identity")
public record IdentitySyncProperties(String importFile) {

    /** 判据：只有 trim 后非空的路径才算「已配置」；空白串与缺席同义，一律归一为 {@code null}。 */
    public IdentitySyncProperties {
        if (importFile != null && importFile.isBlank()) {
            importFile = null;
        }
    }
}
