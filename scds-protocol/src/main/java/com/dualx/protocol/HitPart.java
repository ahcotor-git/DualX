package com.dualx.protocol;

/** 击中部位映射 —— 实兵演习（单兵）（PROTOCOL.md §5.4）。 */
public enum HitPart {

    UNKNOWN(0, "未知"),
    HEAD(1, "头部"),
    CHEST(2, "胸部"),
    LEFT_HAND(3, "左手"),
    RIGHT_HAND(4, "右手"),
    ABDOMEN(5, "腹部"),
    BACK(6, "背部"),
    LEFT_LEG(7, "左腿"),
    RIGHT_LEG(8, "右腿");

    private final int code;
    private final String label;

    HitPart(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static HitPart fromCode(int code) {
        for (HitPart p : values()) {
            if (p.code == code) {
                return p;
            }
        }
        return UNKNOWN;
    }
}
