package com.dualx.protocol.uwb;

import com.dualx.protocol.Hex;
import com.dualx.protocol.ProtocolConstants;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 协议 B：UWB 41 字节二进制帧成帧器。
 *
 * <p>语义与旧 {@code UwbFrameFramer} 完全一致（逐字节对齐）：固定 41B，
 * 帧头 {@code AA 29}、帧尾 {@code 55}、校验 {@code sum(byte[0..38]) & 0xFF == byte[39]}；
 * 任一条件不满足就把匹配位置前移 1 字节继续找，直到剩余不足 41 字节为止（剩余留作残包）。</p>
 *
 * <p>与旧实现的差别：</p>
 * <ul>
 *   <li>零依赖 —— 不再使用 hutool {@code HexUtil} 与 slf4j，因此能落在零依赖的 scds-protocol 模块；</li>
 *   <li>纯函数式 —— 返回 {@link UwbFrame} 列表而不是 {@code byte[]}，日志与统计交由调用方。</li>
 * </ul>
 *
 * <p>两种接入方式（串口 / TCP 12001）共用本成帧器，不新增 TCP 专用成帧逻辑（PROTOCOL.md §3.2）。</p>
 *
 * <p>本类<b>不是</b>线程安全的。每条 UWB 链路独占一个实例。</p>
 */
public final class UwbFramer {

    private static final byte HEAD_0 = (byte) 0xAA;
    private static final byte HEAD_1 = (byte) 0x29;
    private static final byte TAIL = (byte) 0x55;

    private static final int FRAME_LEN = ProtocolConstants.UWB_FRAME_LEN;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(FRAME_LEN * 8);

    /** 喂入一批字节，返回本次能完整切出并通过校验的帧。 */
    public List<UwbFrame> feed(byte[] chunk) {
        if (chunk != null && chunk.length > 0) {
            buffer.write(chunk, 0, chunk.length);
        }
        byte[] buf = buffer.toByteArray();
        List<UwbFrame> frames = new ArrayList<>();
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

    /** 清空残留缓冲。 */
    public void reset() {
        buffer.reset();
    }

    /** 一次性切分完整缓冲，不保留残包（回归测试用）。 */
    public static List<UwbFrame> split(byte[] data) {
        List<UwbFrame> frames = new ArrayList<>();
        if (data != null && data.length > 0) {
            scan(data, frames);
        }
        return frames;
    }

    /**
     * 校验一段候选字节是否为合法 41B 帧（帧头 + 帧尾 + 校验）。
     *
     * @param buf  源缓冲
     * @param pos  候选起始下标
     * @return 合法则 true
     */
    public static boolean isValidFrameAt(byte[] buf, int pos) {
        if (buf == null || pos < 0 || buf.length - pos < FRAME_LEN) {
            return false;
        }
        if (buf[pos] != HEAD_0 || buf[pos + 1] != HEAD_1) {
            return false;
        }
        if (buf[pos + FRAME_LEN - 1] != TAIL) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < FRAME_LEN - 2; i++) {
            sum += buf[pos + i] & 0xFF;
        }
        return (sum & 0xFF) == (buf[pos + FRAME_LEN - 2] & 0xFF);
    }

    /** 丢弃时用于日志的可读片段（HEX）。 */
    public static String headHex(byte[] buf, int pos, int show) {
        if (buf == null || pos < 0 || pos >= buf.length) {
            return "";
        }
        int len = Math.min(show, buf.length - pos);
        return Hex.encode(Arrays.copyOfRange(buf, pos, pos + len));
    }

    /**
     * 扫描并切帧。
     *
     * @return 已消费到的下标；调用方保留 {@code buf[consumed..]} 作为残包
     */
    private static int scan(byte[] buf, List<UwbFrame> out) {
        int pos = 0;
        while (buf.length - pos >= FRAME_LEN) {
            if (isValidFrameAt(buf, pos)) {
                out.add(UwbFrame.decode(Arrays.copyOfRange(buf, pos, pos + FRAME_LEN)));
                pos += FRAME_LEN;
            } else {
                pos++;
            }
        }
        return pos;
    }
}
