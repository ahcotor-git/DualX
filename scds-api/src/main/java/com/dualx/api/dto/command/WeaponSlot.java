package com.dualx.api.dto.command;

/**
 * 充弹指令的武器槽位（契约 schemas/domain.yaml → {@code ReloadRequest.weapon}）。
 *
 * <p>取值与契约 enum 逐字一致；线上字节码见 scds-protocol 的
 * {@code DownlinkCommands.WEAPON_PRIMARY} / {@code WEAPON_SECONDARY}，
 * 与同为「武器」语义但表示重武器/载具种类的 {@code com.dualx.protocol.WeaponKind}
 * 不是一回事，故单独定义。</p>
 */
public enum WeaponSlot {

    /** 主武器槽位。 */
    PRIMARY,
    /** 副武器槽位。 */
    SECONDARY
}
