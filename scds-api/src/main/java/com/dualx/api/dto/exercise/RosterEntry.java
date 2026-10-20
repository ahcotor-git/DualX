package com.dualx.api.dto.exercise;

import com.dualx.core.domain.PollMode;
import com.dualx.protocol.Team;
import jakarta.validation.constraints.NotNull;

/**
 * 编成里的单人参演配置（契约 domain.yaml → {@code RosterEntry}）。
 *
 * @param vestNum 马甲编号，引用设备台账，必填
 * @param name    参演人姓名，可为 null
 * @param team    所属队伍，必填
 * @param hp      开演初始生命值，不传默认 100
 * @param ammo1   弹匣 1 弹药数，不传默认 30
 * @param ammo2   弹匣 2 弹药数，不传默认 30
 * @param mode    轮询模式，不传默认 AUTO
 */
public record RosterEntry(
        @NotNull Integer vestNum,
        String name,
        @NotNull Team team,
        Integer hp,
        Integer ammo1,
        Integer ammo2,
        PollMode mode) {
}
