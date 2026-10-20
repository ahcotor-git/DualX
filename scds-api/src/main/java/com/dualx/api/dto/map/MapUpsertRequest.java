package com.dualx.api.dto.map;

import com.dualx.core.domain.MapType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 修改底图请求（契约 domain.yaml → {@code MapUpsertRequest}）。
 *
 * @param name 名称，1~128 字符
 * @param type 底图类型
 */
public record MapUpsertRequest(
        @NotBlank @Size(min = 1, max = 128) String name,
        @NotNull MapType type) {
}
