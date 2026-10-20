package com.dualx.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 黄金样本读取工具（只读 {@code src/test/resources/samples/} 下的 JSON）。
 *
 * <p>样本来源：旧系统 170 份日志的提取结果，是阶段 1 的唯一验收基线。</p>
 */
public final class Samples {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Samples() {
    }

    /** {@code golden_samples.json}：ff03 99 条 + ff04 3000 条 + ff05 3 条 + uwb41 3000 条。 */
    public static JsonNode golden() {
        return read("samples/golden_samples.json");
    }

    /** {@code sticky_packets.json}：300 条「ff04 + ff03」粘包（无垃圾、无半包）。 */
    public static JsonNode stickyPackets() {
        return read("samples/sticky_packets.json");
    }

    /** {@code synthetic_frames.json}：日志里从未出现过的 ff06 / ff07 / ff0a 人造样本。 */
    public static JsonNode syntheticFrames() {
        return read("samples/synthetic_frames.json");
    }

    public static JsonNode read(String resource) {
        try (InputStream in = Samples.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("样本资源不存在: " + resource);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * ASCII HEX 文本 → 串口线上字节。
     *
     * <p>协议 A 是<b>文本</b>协议：物理层收到的是字符 {@code 'f','f','0','3'}，
     * 而不是 {@code 0xFF,0x03}。所以黄金样本里的 hex 字符串必须先转成 ASCII 字符再喂给 Framer。</p>
     */
    public static byte[] wire(String hex) {
        return hex.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 从 hex 文本中按「小端 int32」独立重算。
     *
     * <p>刻意不走被测代码的 {@code byte[] + 移位} 路径：这里把 4 组 2 字符<b>倒序拼接</b>后
     * 用 {@link Long#parseLong} 解析，属于独立的第二条实现路径。</p>
     */
    public static int leInt32FromHex(String hex, int byteOffset) {
        String s = hex.substring(byteOffset * 2, byteOffset * 2 + 8);
        StringBuilder sb = new StringBuilder(8);
        for (int i = 3; i >= 0; i--) {
            sb.append(s, i * 2, i * 2 + 2);
        }
        return (int) Long.parseLong(sb.toString(), 16);
    }
}
