package com.dualx.core.domain;

/**
 * 马甲轮询模式（契约 {@code RosterEntry.mode} / {@code VestConfigRequest.mode}）。
 * 对应下行 14B 轮询配置帧的 mode 参数，线上编码见
 * {@code DownlinkCommands.MODE_AUTO} / {@code MODE_MANUAL}，故本枚举不携带字节码。
 */
public enum PollMode {

    /** 自动。 */
    AUTO,
    /** 手动。 */
    MANUAL
}
