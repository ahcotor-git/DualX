package com.dualx.protocol.serial;

/**
 * 协议 A（马甲串口，ASCII 文本 HEX）帧类型白名单。
 *
 * <p>PROTOCOL.md §1.4 + §6.3：只实现这 6 种，其余帧类型一律丢弃。</p>
 *
 * <p><b>帧长白名单是承重墙</b>：本协议没有长度字段，只能靠「帧头 + 类型 → 合法总长」
 * 定位帧边界。因此这里给出的 {@code lengths} 必须与 §1.3 速查表严格一致，
 * 不得为了让畸形数据「能跑」而放宽。</p>
 */
public enum SerialFrameType {

    /** 心跳/状态帧：短版 13B / 长版 15B（§6.2 纯按长度分支）。 */
    HEARTBEAT("ff03", 0x03, 13, 15),
    /** 室外 GPS 帧。 */
    GPS("ff04", 0x04, 16),
    /** 击中记录帧。 */
    HIT("ff05", 0x05, 11),
    /** 旧室内定位帧（UWB 模式下由业务层丢弃，协议层照常解析）。 */
    INDOOR_LOCATION("ff06", 0x06, 16),
    /** 迫击炮/曲射火力帧。 */
    MORTAR("ff07", 0x07, 12),
    /** SIM 号上报帧。 */
    SIM_NO("ff0a", 0x0A, 18);

    private final String tag;
    private final int code;
    private final int[] lengths;

    SerialFrameType(String tag, int code, int... lengths) {
        this.tag = tag;
        this.code = code;
        this.lengths = lengths;
    }

    /** 帧头文本（小写，如 {@code ff03}）。 */
    public String tag() {
        return tag;
    }

    /** 帧类型字节（如 {@code 0x03}）。 */
    public int code() {
        return code;
    }

    /** 合法总长白名单（升序）。 */
    public int[] lengths() {
        return lengths;
    }

    public boolean supportsLength(int len) {
        for (int l : lengths) {
            if (l == len) {
                return true;
            }
        }
        return false;
    }

    /**
     * 由帧头第 4 个 ASCII 字符（即类型低半字节）解析类型。
     *
     * @param c ASCII 字符，如 '3' / 'a' / 'A'
     * @return 匹配的类型；未登记返回 {@code null}
     */
    public static SerialFrameType fromTypeChar(byte c) {
        int nib = com.dualx.protocol.Hex.nibble(c);
        if (nib < 0) {
            return null;
        }
        for (SerialFrameType t : values()) {
            if (t.code == nib) {
                return t;
            }
        }
        return null;
    }
}
