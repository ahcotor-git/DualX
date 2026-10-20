package com.dualx.protocol;

/** 演习日志类型（PROTOCOL.md §5.3，原 {@code Constant}）。 */
public enum LogType {

    /** 演习开始。 */
    START_BATTLE(0, "演习开始"),
    /** 击中。 */
    SHOT(1, "击中"),
    /** 阵亡。 */
    DIE(2, "阵亡"),
    /** 复活 / 判活。 */
    LIVE(3, "判活"),
    /** 判伤。 */
    INJURE(4, "判伤"),
    /** 换弹。 */
    RELOAD(5, "换弹");

    private final int code;
    private final String label;

    LogType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }
}
