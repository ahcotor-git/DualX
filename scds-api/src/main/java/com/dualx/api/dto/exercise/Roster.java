package com.dualx.api.dto.exercise;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 想定编成（契约 domain.yaml → {@code Roster}），获取编成的响应体，也是整体替换编成的请求体。
 *
 * @param planId  想定 ID
 * @param entries 逐人参演配置；校验时级联到每个元素
 */
public record Roster(
        @NotBlank String planId,
        @NotNull @Valid List<RosterEntry> entries) {
}
