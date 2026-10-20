package com.dualx.protocol.serial;

import com.dualx.protocol.Hex;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 协议 A（马甲串口）成帧器 —— 输入 ASCII 字节流，输出 {@link SerialFrame} 列表 + 残留缓冲。
 *
 * <h3>为什么不用 {@code indexOf("ff0")}</h3>
 * 旧实现用字符串查找切包（PROTOCOL.md §1.5 的 4 个分支），存在两个问题：把字节流转成 String 的
 * 额外开销，以及「找不到下一个 affa 就越界吞帧」。本实现改为
 * <b>长度白名单驱动 + 逐字节重同步</b>：
 * <ol>
 *   <li>在字节层扫描帧头 {@code ff0X}（不构造 String）；</li>
 *   <li>由帧类型取合法总长白名单（{@code ff03} = 13/15，其余唯一），逐个候选校验
 *       「帧尾 AF FA + 累加和」；</li>
 *   <li>全部候选都不合法 → 前移 1 字节重新同步（等价于旧实现的「丢弃垃圾前缀」）；
 *       候选因字节不足而未判定完 → 停在此处，缓冲留给下一批（等价于旧实现的「半包缓存」）。</li>
 * </ol>
 *
 * <p>由此，§1.5 的 4 个分支被统一收敛为一条规则，且残留缓冲天然有界
 * （最多为「最长候选帧长 − 1」字节），不需要「连续 10 次未闭合就清空」这类计数兜底。</p>
 *
 * <h3>线程安全</h3>
 * 本类<b>不是</b>线程安全的。每个串口链路独占一个实例。
 */
public final class SerialFramer {

    /** 帧头 ASCII 长度："ff0X" 共 4 个字符。 */
    private static final int HEADER_ASCII_LEN = 4;

    /** 线上每 1 个协议字节占 2 个 ASCII 字符（十六进制文本）。 */
    private static final int ASCII_PER_BYTE = 2;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);

    /**
     * 喂入一批字节，返回本次能完整切出的帧。
     *
     * @param chunk 从串口读到的新字节（可为 null / 空）
     * @return 已校验通过的帧，按出现顺序排列；未闭合的残包留在内部缓冲
     */
    public List<SerialFrame> feed(byte[] chunk) {
        if (chunk != null && chunk.length > 0) {
            buffer.write(chunk, 0, chunk.length);
        }
        byte[] buf = buffer.toByteArray();
        List<SerialFrame> frames = new ArrayList<>();
        int consumed = scan(buf, frames);
        buffer.reset();
        if (consumed < buf.length) {
            buffer.write(buf, consumed, buf.length - consumed);
        }
        return frames;
    }

    /** 当前未闭合的残留字节（半包）。 */
    public byte[] remainder() {
        return buffer.toByteArray();
    }

    /** 清空残留缓冲（例如串口重连后）。 */
    public void reset() {
        buffer.reset();
    }

    /**
     * 一次性切分一个完整缓冲，不保留残包。
     *
     * <p>黄金样本回归与单元测试用；生产链路请用 {@link #feed(byte[])} 以保留半包。</p>
     */
    public static List<SerialFrame> split(byte[] data) {
        List<SerialFrame> frames = new ArrayList<>();
        if (data != null && data.length > 0) {
            scan(data, frames);
        }
        return frames;
    }

    // ==================== 内核 ====================

    /**
     * 扫描并切帧。
     *
     * @return 已消费到的下标；调用方应保留 {@code buf[consumed..]} 作为残包
     */
    private static int scan(byte[] buf, List<SerialFrame> out) {
        final int n = buf.length;
        int i = 0;
        while (i < n) {
            if (n - i < HEADER_ASCII_LEN) {
                // 连帧头都凑不满，整段留作残包（可能是 "f" / "ff" / "ff0"）
                break;
            }
            if (!isHeaderPrefix(buf, i)) {
                i++;
                continue;
            }
            SerialFrameType type = SerialFrameType.fromTypeChar(buf[i + 3]);
            if (type == null) {
                i++;
                continue;
            }

            boolean needMoreBytes = false;
            boolean accepted = false;
            for (int len : type.lengths()) {
                int asciiLen = len * ASCII_PER_BYTE;
                if (n - i < asciiLen) {
                    needMoreBytes = true;
                    continue;
                }
                if (!tailOk(buf, i, len) || !checksumOk(buf, i, len)) {
                    continue;
                }
                out.add(decode(buf, i, len, type));
                i += asciiLen;
                accepted = true;
                break;
            }
            if (!accepted) {
                if (needMoreBytes) {
                    break;
                }
                i++;
            }
        }
        return i;
    }

    private static boolean isHeaderPrefix(byte[] buf, int i) {
        return isF(buf[i]) && isF(buf[i + 1]) && buf[i + 2] == '0';
    }

    private static boolean isF(byte b) {
        return b == 'f' || b == 'F';
    }

    /** 帧尾必须是 AF FA（帧内倒数第 2、1 字节）。 */
    private static boolean tailOk(byte[] buf, int base, int len) {
        return Hex.asciiByteAt(buf, base, len - 2) == 0xAF
                && Hex.asciiByteAt(buf, base, len - 1) == 0xFA;
    }

    /**
     * 校验：{@code AFFA} 前一位 == 前面所有字节的累加和（溢出取低 8 位）。见 PROTOCOL.md §1.3。
     */
    private static boolean checksumOk(byte[] buf, int base, int len) {
        int ckIndex = len - 3;
        int sum = 0;
        for (int k = 0; k < ckIndex; k++) {
            int b = Hex.asciiByteAt(buf, base, k);
            if (b < 0) {
                return false;
            }
            sum += b;
        }
        int expect = Hex.asciiByteAt(buf, base, ckIndex);
        return expect >= 0 && (sum & 0xFF) == expect;
    }

    // ==================== 解码（PROTOCOL.md §1.4） ====================

    private static SerialFrame decode(byte[] buf, int base, int len, SerialFrameType type) {
        byte[] raw = decodeBytes(buf, base, len);
        switch (type) {
            case HEARTBEAT: {
                int vestNum = be16(buf, base, 3);
                int ammo1 = be16(buf, base, 5);
                int hp = Hex.asciiByteAt(buf, base, 7);
                int ammo2 = be16(buf, base, 8);
                Integer floorNum = null;
                Integer posNum = null;
                if (len == 15) {
                    floorNum = Hex.asciiByteAt(buf, base, 10);
                    posNum = Hex.asciiByteAt(buf, base, 11);
                }
                return new SerialFrame.Heartbeat(vestNum, ammo1, hp, ammo2, floorNum, posNum, raw);
            }
            case GPS: {
                int vestNum = be16(buf, base, 3);
                long lngRaw = be32(buf, base, 5);
                long latRaw = be32(buf, base, 9);
                return new SerialFrame.Gps(vestNum, lngRaw, latRaw, lngRaw / 1_000_000.0, latRaw / 1_000_000.0, raw);
            }
            case HIT: {
                return new SerialFrame.Hit(be16(buf, base, 3), be16(buf, base, 5),
                        Hex.asciiByteAt(buf, base, 7), raw);
            }
            case INDOOR_LOCATION: {
                return new SerialFrame.IndoorLocation(be16(buf, base, 3), be32(buf, base, 5), be32(buf, base, 9), raw);
            }
            case MORTAR: {
                return new SerialFrame.Mortar(be16(buf, base, 3), be16(buf, base, 5), be16(buf, base, 7), raw);
            }
            case SIM_NO: {
                // [5..14] 共 10 字节，语义是「SIM 卡的十六进制文本」，故原样取 ASCII 字符
                String simNo = new String(buf, base + 10, 20, StandardCharsets.ISO_8859_1);
                return new SerialFrame.SimNo(be16(buf, base, 3), simNo, raw);
            }
            default:
                throw new IllegalStateException("unreachable: " + type);
        }
    }

    /** 把帧内的 ASCII 十六进制文本还原为协议字节（长度 = 帧总长，即 PROTOCOL.md 里的 11~18B）。 */
    private static byte[] decodeBytes(byte[] buf, int base, int len) {
        byte[] out = new byte[len];
        for (int k = 0; k < len; k++) {
            out[k] = (byte) Hex.asciiByteAt(buf, base, k);
        }
        return out;
    }

    /** 大端 16 位无符号读取（帧内字节下标）。 */
    private static int be16(byte[] buf, int base, int byteIndex) {
        return (Hex.asciiByteAt(buf, base, byteIndex) << 8) | Hex.asciiByteAt(buf, base, byteIndex + 1);
    }

    /** 大端 32 位无符号读取（帧内字节下标）。 */
    private static long be32(byte[] buf, int base, int byteIndex) {
        return ((long) Hex.asciiByteAt(buf, base, byteIndex) << 24)
                | ((long) Hex.asciiByteAt(buf, base, byteIndex + 1) << 16)
                | ((long) Hex.asciiByteAt(buf, base, byteIndex + 2) << 8)
                | Hex.asciiByteAt(buf, base, byteIndex + 3);
    }
}
