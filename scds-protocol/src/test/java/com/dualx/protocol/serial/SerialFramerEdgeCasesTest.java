package com.dualx.protocol.serial;

import com.dualx.protocol.Hex;
import com.dualx.protocol.Samples;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议 A 成帧器边界：垃圾前缀 / 半包 / 校验失败 / 粘包无损切分 / 残留缓冲有界。
 *
 * <p>这些场景对应 PROTOCOL.md §1.5 旧实现里的 4 个 {@code indexOf} 分支，
 * 新实现用「长度白名单 + 逐字节重同步」统一收敛。</p>
 */
class SerialFramerEdgeCasesTest {

    /** 合法 13B 短版心跳（样本第一条）。 */
    private static final String FF03_SHORT = "ff03000002001e64001ea4affa";
    /** 合法 16B GPS（样本第一条）。 */
    private static final String FF04 = "ff0400000207136d12017e8822c7affa";
    /** 合法 15B 长版心跳：num=7 ammo1=30 hp=100 ammo2=25 楼层=3 点位=12。 */
    private static final String FF03_LONG = "ff03000007001e640019030cb3affa";

    private static final int MAX_REMAINDER = 2 * 18 - 1; // 最长合法帧（ff0a 18B）占 36 字符，残包天然 < 36

    @Test
    @DisplayName("短版 13B 与长版 15B 心跳共存：长版才带楼层/点位")
    void shortAndLongHeartbeat() {
        List<SerialFrame> frames = SerialFramer.split(Samples.wire(FF03_SHORT + FF03_LONG));
        assertEquals(2, frames.size());

        SerialFrame.Heartbeat shortVer = (SerialFrame.Heartbeat) frames.get(0);
        assertFalse(shortVer.longVersion());
        assertNull(shortVer.floorNum());
        assertNull(shortVer.posNum());
        assertEquals(2, shortVer.vestNum());
        assertEquals(30, shortVer.ammo1());
        assertEquals(100, shortVer.hp());
        assertEquals(30, shortVer.ammo2());

        SerialFrame.Heartbeat longVer = (SerialFrame.Heartbeat) frames.get(1);
        assertTrue(longVer.longVersion());
        assertEquals(7, longVer.vestNum());
        assertEquals(30, longVer.ammo1());
        assertEquals(100, longVer.hp());
        assertEquals(25, longVer.ammo2());
        assertEquals(3, longVer.floorNum());
        assertEquals(12, longVer.posNum());
    }

    @Test
    @DisplayName("头部垃圾字节被丢掉，完整帧照常解析（旧分支③）")
    void garbagePrefixIsDropped() {
        List<SerialFrame> frames = SerialFramer.split(Samples.wire("00112233" + FF03_SHORT));
        assertEquals(1, frames.size());
        assertEquals(FF03_SHORT, Hex.encode(frames.get(0).raw()));
    }

    @Test
    @DisplayName("假帧头（ff0 但不构成合法帧）能正确重同步到真帧（旧分支③+④）")
    void falseHeaderIsResynced() {
        List<SerialFrame> frames = SerialFramer.split(Samples.wire("ff0300000" + FF03_LONG));
        assertEquals(1, frames.size());
        assertEquals(FF03_LONG, Hex.encode(frames.get(0).raw()));
        assertEquals(12, ((SerialFrame.Heartbeat) frames.get(0)).posNum());
    }

    @Test
    @DisplayName("校验和不符的帧必须被丢弃")
    void corruptedChecksumIsDropped() {
        // 把 ammo2 由 0x001e 改成 0x001f，校验字节仍是 a4 → 必须整帧丢弃
        String corrupted = "ff03000002001e64001fa4affa";
        assertTrue(SerialFramer.split(Samples.wire(corrupted)).isEmpty());
    }

    @Test
    @DisplayName("帧长不在白名单内一律丢弃（12B 截断残片 / 9B 垃圾）")
    void illegalLengthIsDropped() {
        assertTrue(SerialFramer.split(Samples.wire("ff030001037464005836affa")).isEmpty(), "12B 残片");
        assertTrue(SerialFramer.split(Samples.wire("ff0300000")).isEmpty(), "9B 残片");
    }

    @Test
    @DisplayName("半包：分多次 feed 也能拼出完整帧，累计结果与一次喂入一致（旧分支④）")
    void halfPacketAcrossFeeds() {
        String stream = FF03_SHORT + FF04 + FF03_LONG;
        byte[] wire = Samples.wire(stream);

        SerialFramer framer = new SerialFramer();
        List<SerialFrame> got = new ArrayList<>();
        for (int i = 0; i < wire.length; i += 5) {
            got.addAll(framer.feed(java.util.Arrays.copyOfRange(wire, i, Math.min(i + 5, wire.length))));
            assertTrue(framer.remainder().length <= MAX_REMAINDER, "残包必须天然有界");
        }

        assertEquals(3, got.size());
        assertEquals(0, framer.remainder().length, "全部闭合后不应有残包");
        assertEquals(FF03_SHORT, Hex.encode(got.get(0).raw()));
        assertEquals(FF04, Hex.encode(got.get(1).raw()));
        assertEquals(FF03_LONG, Hex.encode(got.get(2).raw()));
    }

    @Test
    @DisplayName("纯垃圾流：不吐帧、不炸缓冲、残留缓冲有界")
    void garbageStreamIsSafe() {
        SerialFramer framer = new SerialFramer();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("ff0300000"); // 反复出现假帧头，最坏情况
        }
        List<SerialFrame> frames = framer.feed(Samples.wire(sb.toString()));

        assertTrue(frames.isEmpty());
        assertTrue(framer.remainder().length <= MAX_REMAINDER, "残留 = " + framer.remainder().length);
    }

    @Test
    @DisplayName("sticky_packets.json：300 条粘包无损切分为 GPS + 心跳，且拼接后与原文逐字节一致")
    void stickyPacketsAreSplitLosslessly() {
        JsonNode packets = Samples.stickyPackets();
        assertEquals(300, packets.size());

        for (JsonNode packet : packets) {
            String hex = packet.asText().toLowerCase(Locale.ROOT);
            assertEquals(29, Hex.decode(hex).length, "ff04(16B) + ff03(13B)");

            List<SerialFrame> frames = SerialFramer.split(Samples.wire(hex));
            assertEquals(2, frames.size(), hex);
            assertTrue(frames.get(0) instanceof SerialFrame.Gps, hex);
            assertTrue(frames.get(1) instanceof SerialFrame.Heartbeat, hex);
            assertEquals(hex, Hex.encode(frames.get(0).raw()) + Hex.encode(frames.get(1).raw()),
                    "无损切分：拼接必须与原文一致");
        }
    }

    @Test
    @DisplayName("sticky 样本逐字节分片喂入：结果与一次喂入完全一致")
    void stickyPacketsAcrossFeeds() {
        JsonNode packets = Samples.stickyPackets();
        for (JsonNode packet : packets) {
            String hex = packet.asText().toLowerCase(Locale.ROOT);

            SerialFramer framer = new SerialFramer();
            List<SerialFrame> got = new ArrayList<>();
            byte[] wire = Samples.wire(hex);
            for (byte b : wire) {
                got.addAll(framer.feed(new byte[]{b}));
            }

            assertEquals(2, got.size(), hex);
            assertEquals(0, framer.remainder().length, hex);
            assertEquals(hex, Hex.encode(got.get(0).raw()) + Hex.encode(got.get(1).raw()), hex);
        }
    }
}
