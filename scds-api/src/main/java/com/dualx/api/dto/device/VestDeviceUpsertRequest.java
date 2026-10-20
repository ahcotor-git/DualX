package com.dualx.api.dto.device;

import com.dualx.protocol.Team;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记 / 修改马甲请求体（契约 domain.yaml → {@code VestDeviceUpsertRequest}）。
 *
 * @param vestNum 马甲编号，1~65532
 * @param name    使用人姓名，最长 64
 * @param team    所属队伍
 * @param model   设备型号
 * @param remark  备注
 */
public record VestDeviceUpsertRequest(@NotNull @Min(1) @Max(65532) Integer vestNum,
                                      @Size(max = 64) String name,
                                      Team team,
                                      String model,
                                      String remark) {
}
