package com.dualx.protocol;

/**
 * 十六进制工具（零依赖）。
 *
 * <p>协议 A 的串口链路上行是 <b>ASCII 文本 HEX</b>（收到的是字符 'f','f','0','3'），
 * 因此这里的核心是「ASCII 流 → 字节值」的定位读取，而不是普通的 byte[] → hex。</p>
 *
 * <p>本类不得引入任何第三方依赖：scds-protocol 主代码零依赖是硬约束。</p>
 */
public final class Hex {

    private static final char[] LOWER = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    /** 单个 ASCII 字符 → 半字节（0..15）；非十六进制字符返回 -1。大小写均可。 */
    public static int nibble(byte c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /**
     * 在 ASCII HEX 流中读取第 {@code byteIndex} 个字节（每字节占 2 个 ASCII 字符）。
     *
     * @param src       ASCII HEX 字节流
     * @param base      帧起始下标
     * @param byteIndex 帧内字节下标（0-based）
     * @return 0..255；任一字符非法时返回 -1
     */
    public static int asciiByteAt(byte[] src, int base, int byteIndex) {
        int p = base + byteIndex * 2;
        int hi = nibble(src[p]);
        int lo = nibble(src[p + 1]);
        if (hi < 0 || lo < 0) {
            return -1;
        }
        return (hi << 4) | lo;
    }

    /** byte[] → 小写 hex 字符串。 */
    public static String encode(byte[] data) {
        if (data == null) {
            return "";
        }
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = LOWER[v >>> 4];
            out[i * 2 + 1] = LOWER[v & 0x0F];
        }
        return new String(out);
    }

    /** hex 字符串 → byte[]；长度为奇数或含非法字符时抛 IllegalArgumentException。 */
    public static byte[] decode(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("hex must not be null");
        }
        int n = hex.length();
        if ((n & 1) != 0) {
            throw new IllegalArgumentException("hex length must be even: " + n);
        }
        byte[] out = new byte[n / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("illegal hex char at " + (i * 2));
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 单字节 → 两位大写 hex（日志用）。 */
    public static String hex2(int value) {
        int v = value & 0xFF;
        return new String(new char[]{Character.toUpperCase(Character.forDigit(v >>> 4, 16)),
                Character.toUpperCase(Character.forDigit(v & 0x0F, 16))});
    }
}
