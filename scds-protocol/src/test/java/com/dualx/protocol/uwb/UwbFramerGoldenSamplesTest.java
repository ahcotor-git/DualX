package com.dualx.protocol.uwb;

import com.dualx.protocol.Samples;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议 B（UWB 41B 二进制帧）回归：用 {@code golden_samples.json} 的 {@code uwb41} 段逐条对拍。
 *
 * <p>⚠️ <b>样本里的 {@code vestNum} 与 {@code rangesMm} 不可信</b>：生成样本的 PowerShell 脚本
 * 在拼小端 int32 时被 {@code byte -shl} 的 8 位截断坑了，实际只读了每 4 字节组的<b>第一个字节</b>
 * （如 {@code 34 12 00 00} 被误读成 {@code 0x34 = 52}）。因此期望值一律用
 * {@link Samples#leInt32FromHex} 从 hex 文本<b>独立重算</b>；样本里的
 * {@code bytes} / {@code reserved} / {@code headOk} / {@code tailOk} / {@code checksumOk} 仍可信。</p>
 *
 * <p>与协议 A 不同，这里是<b>二进制</b>协议：hex 文本必须先还原成真实字节，绝不能走
 * {@link Samples#wire}（那是 ASCII 文本通道）。</p>
 */
class UwbFramerGoldenSamplesTest {

    private static final int FRAME_LEN = 41;

    @Test
    @DisplayName("uwb41 黄金样本 3000 条：逐字节还原 + reserved/vestNum/ranges 与独立重算一致")
    void goldenSamplesDecodeByteExact() {
        JsonNode samples = Samples.golden().get("uwb41").get("samples");
        assertEquals(3000, samples.size(), "样本条数变了，说明基线被改动");

        int decoded = 0;
        for (JsonNode s : samples) {
            String hex = s.get("hex").asText().toLowerCase(Locale.ROOT);
            assertEquals(FRAME_LEN, s.get("bytes").asInt(), hex);
            assertTrue(s.get("headOk").asBoolean() && s.get("tailOk").asBoolean(), hex);
            assertTrue(s.get("checksumOk").asBoolean(), hex);

            byte[] wire = hexToBytes(hex);
            assertTrue(checksumOk(wire), "样本自身校验必须通过: " + hex);

            List<UwbFrame> frames = UwbFramer.split(wire);
            assertEquals(1, frames.size(), "单条 41B 样本必须切出恰好 1 帧: " + hex);
            UwbFrame f = frames.get(0);

            assertArrayEquals(wire, f.raw(), "逐字节还原 " + hex);
            assertEquals(s.get("reserved").asInt(), f.reserved(), "reserved " + hex);
            assertEquals(Samples.leInt32FromHex(hex, 3), f.vestNum(), "vestNum " + hex);
            for (int i = 0; i < UwbFrame.RANGE_COUNT; i++) {
                assertEquals(Samples.leInt32FromHex(hex, UwbFrame.RANGE_BASE + UwbFrame.RANGE_BYTES * i),
                        f.rangeMm(i), "rangesMm[" + i + "] " + hex);
            }
            decoded++;
        }
        assertEquals(3000, decoded);
    }

    @Test
    @DisplayName("半包：先喂前 20 字节不出帧，补齐后才出帧")
    void halfPacketAcrossFeeds() {
        byte[] wire = firstSampleBytes();
        UwbFramer framer = new UwbFramer();

        assertTrue(framer.feed(Arrays.copyOfRange(wire, 0, 20)).isEmpty());
        assertEquals(20, framer.remainder().length);

        List<UwbFrame> out = framer.feed(Arrays.copyOfRange(wire, 20, FRAME_LEN));
        assertEquals(1, out.size());
        assertArrayEquals(wire, out.get(0).raw());
        assertEquals(0, framer.remainder().length);
    }

    @Test
    @DisplayName("逐字节喂入 1 帧：结果与一次性喂入完全一致")
    void byteByByteFeed() {
        byte[] wire = firstSampleBytes();
        UwbFramer framer = new UwbFramer();
        UwbFrame got = null;
        for (byte b : wire) {
            List<UwbFrame> out = framer.feed(new byte[]{b});
            for (UwbFrame f : out) {
                got = f;
            }
        }
        assertEquals(UwbFramer.split(wire).get(0), got);
        assertEquals(0, framer.remainder().length);
    }

    @Test
    @DisplayName("垃圾前缀：前移重同步后仍能切出真帧，且 raw 逐字节正确")
    void garbagePrefixIsResynced() {
        byte[] wire = firstSampleBytes();
        byte[] dirty = new byte[3 + FRAME_LEN];
        dirty[0] = 0x01;
        dirty[1] = 0x02;
        dirty[2] = 0x03;
        System.arraycopy(wire, 0, dirty, 3, FRAME_LEN);

        List<UwbFrame> frames = UwbFramer.split(dirty);
        assertEquals(1, frames.size());
        assertArrayEquals(wire, frames.get(0).raw());
    }

    @Test
    @DisplayName("污染校验字节 / 帧尾 / 帧头：一律丢弃")
    void corruptedFramesAreDropped() {
        byte[] wire = firstSampleBytes();

        byte[] badChecksum = wire.clone();
        badChecksum[39] = (byte) (badChecksum[39] ^ 0xFF);
        UwbFramer framer = new UwbFramer();
        assertTrue(framer.feed(badChecksum).isEmpty(), "校验字节被改必须丢弃");
        assertEquals(FRAME_LEN - 1, framer.remainder().length, "残包长度应为 40（不足 41 无法成帧）");

        byte[] badTail = wire.clone();
        badTail[40] = 0x00;
        assertTrue(UwbFramer.split(badTail).isEmpty(), "帧尾被改必须丢弃");

        byte[] badHead = wire.clone();
        badHead[0] = (byte) 0xAB;
        assertTrue(UwbFramer.split(badHead).isEmpty(), "帧头被改必须丢弃");
    }

    @Test
    @DisplayName("粘包：两帧串接必须无损切分；脏数据流残包恒 < 41 字节")
    void stickyAndBoundedRemainder() {
        JsonNode samples = Samples.golden().get("uwb41").get("samples");
        byte[] a = hexToBytes(samples.get(0).get("hex").asText().toLowerCase(Locale.ROOT));
        byte[] b = hexToBytes(samples.get(1).get("hex").asText().toLowerCase(Locale.ROOT));

        byte[] sticky = new byte[FRAME_LEN * 2];
        System.arraycopy(a, 0, sticky, 0, FRAME_LEN);
        System.arraycopy(b, 0, sticky, FRAME_LEN, FRAME_LEN);

        List<UwbFrame> frames = UwbFramer.split(sticky);
        assertEquals(2, frames.size());
        assertArrayEquals(a, frames.get(0).raw());
        assertArrayEquals(b, frames.get(1).raw());

        // 纯垃圾流：永远吐不出帧，且残留必须有界（< 41），否则缓冲会被慢慢撑爆
        UwbFramer framer = new UwbFramer();
        byte[] garbage = new byte[500];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = (byte) (i * 7 + 1);
        }
        assertTrue(framer.feed(garbage).isEmpty());
        assertTrue(framer.remainder().length < FRAME_LEN,
                "残包必须 < 41 字节，实际 " + framer.remainder().length);
    }

    private static byte[] firstSampleBytes() {
        String hex = Samples.golden().get("uwb41").get("samples").get(0)
                .get("hex").asText().toLowerCase(Locale.ROOT);
        return hexToBytes(hex);
    }

    /** 二进制 hex 文本 → 真实字节（协议 B 是二进制协议，不能走 ASCII 通道）。 */
    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 独立复算协议 B 累加和：{@code sum(byte[0..38]) & 0xFF == byte[39]}，帧尾 {@code byte[40] == 0x55}。 */
    private static boolean checksumOk(byte[] f) {
        int sum = 0;
        for (int i = 0; i < FRAME_LEN - 2; i++) {
            sum += f[i] & 0xFF;
        }
        return (sum & 0xFF) == (f[FRAME_LEN - 2] & 0xFF) && f[FRAME_LEN - 1] == 0x55;
    }
}
