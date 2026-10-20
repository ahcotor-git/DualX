package com.dualx.protocol;

/** 队伍映射（PROTOCOL.md §5.2，原 {@code Constant.TEAM_MAP}）。 */
public enum Team {

    RED(0x01, "red"),
    BLUE(0x02, "blue"),
    ORANGE(0x03, "orange"),
    YELLOW(0x04, "yellow");

    private final int code;
    private final String key;

    Team(int code, String key) {
        this.code = code;
        this.key = key;
    }

    /** 下发指令中使用的字节码。 */
    public int code() {
        return code;
    }

    /** 与旧系统一致的英文 key（red/blue/orange/yellow）。 */
    public String key() {
        return key;
    }

    /** 按旧 key 查表；未匹配返回 red（与旧 {@code TEAM_MAP.get()} 的 NullPointer 风险不同，这里显式兜底）。 */
    public static Team fromKey(String key) {
        for (Team t : values()) {
            if (t.key.equalsIgnoreCase(key)) {
                return t;
            }
        }
        return RED;
    }

    public static Team fromCode(int code) {
        for (Team t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return null;
    }
}
