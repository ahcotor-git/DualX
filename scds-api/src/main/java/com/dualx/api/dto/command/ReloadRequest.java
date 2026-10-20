package com.dualx.api.dto.command;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 充弹请求体（契约 schemas/domain.yaml → {@code ReloadRequest}），全体充弹与单兵充弹共用。
 *
 * @param ammo   弹药数 0~65535，必填
 * @param weapon 武器槽位，必填
 */
public record ReloadRequest(
        @NotNull @Min(0) @Max(65535) Integer ammo,
        @NotNull WeaponSlot weapon) {
}
