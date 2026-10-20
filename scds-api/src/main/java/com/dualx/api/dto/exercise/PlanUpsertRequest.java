package com.dualx.api.dto.exercise;

import com.dualx.core.domain.BattleMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 修改想定的请求体（契约 domain.yaml → {@code PlanUpsertRequest}）。
 *
 * @param name       想定名称，1~128 字符，必填
 * @param mapId      绑定的底图，可为 null
 * @param battleMode 演习模式，不传默认 TRAIN
 * @param remark     备注，可为 null
 */
public record PlanUpsertRequest(
        @NotBlank @Size(min = 1, max = 128) String name,
        String mapId,
        BattleMode battleMode,
        String remark) {
}
