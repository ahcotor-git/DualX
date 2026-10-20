package com.dualx.protocol;

/** 马甲状态（PROTOCOL.md §5.1，原 {@code Constant}）。 */
public enum VestStatus {

    /** 离线。 */
    OFFLINE(0, "离线"),
    /** 存活。 */
    LIVE(1, "存活"),
    /** 阵亡。 */
    DIE(2, "阵亡");

    private final int code;
    private final String label;

    VestStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static VestStatus fromCode(int code) {
        for (VestStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return OFFLINE;
    }
}
