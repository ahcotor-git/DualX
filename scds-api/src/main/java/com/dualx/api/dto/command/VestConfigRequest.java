package com.dualx.api.dto.command;

import com.dualx.core.domain.PollMode;
import com.dualx.protocol.Team;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 修改马甲运行参数请求体（契约 schemas/domain.yaml → {@code VestConfigRequest}）。
 *
 * <p>对应 14B 轮询配置帧，属<b>覆盖配置</b>语义，下轮轮询才生效；未传字段保持原值，
 * 故此处全部字段可空，仅对传入值做区间约束。</p>
 *
 * @param mode   轮询模式，复用仓储层 {@code PollMode}
 * @param hp     血量 0~255，可空
 * @param ammo1  一号弹药数 0~65535，可空
 * @param ammo2  二号弹药数 0~65535，可空
 * @param team   队伍，可空
 */
public record VestConfigRequest(
        PollMode mode,
        @Min(0) @Max(255) Integer hp,
        @Min(0) @Max(65535) Integer ammo1,
        @Min(0) @Max(65535) Integer ammo2,
        Team team) {
}
