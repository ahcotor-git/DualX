package com.dualx.api.dto.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 绑定 / 换绑请求体（契约 domain.yaml → {@code VestBindingRequest}）。
 *
 * <p>只收明文密钥，服务端存 BCrypt 摘要、永不回显（决策 04 D03-4）。
 * 长度下限 8 与上限 128 都是契约写死的：下限挡住 {@code 1234} 这种现场凭据，
 * 上限与 BCrypt 输入成本无关，只为挡住把整份文件粘进来的误操作。</p>
 *
 * @param secret 单兵 App 绑定密钥明文，仅上行一次
 */
public record VestBindingRequest(@NotBlank @Size(min = 8, max = 128) String secret) {
}
