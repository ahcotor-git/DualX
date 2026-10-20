package com.dualx.core.domain;

/**
 * 演习模式（契约 {@code Plan.battleMode}）。下发行指令 {@code 0x06} 的 battleMode 参数，
 * 线上编码由 scds-gateway 依 {@code DownlinkCommands.BATTLE_MODE_*} 完成，
 * 故本枚举不携带字节码，避免出现第二份真值。
 */
public enum BattleMode {

    /** 训练。 */
    TRAIN,
    /** 正式演习。 */
    BATTLE
}
