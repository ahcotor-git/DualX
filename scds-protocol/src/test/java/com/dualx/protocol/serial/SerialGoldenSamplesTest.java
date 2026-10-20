package com.dualx.protocol.serial;

import com.dualx.protocol.Hex;
import com.dualx.protocol.Samples;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议 A 上行帧回归：用 {@code golden_samples.json} 逐条对拍。
 *
 * <p><b>硬性验收条件</b>（阶段 1）：
 * <ul>
 *   <li>样本里 {@code checksumOk=true} 且帧长在长度白名单内的，必须解析出 <b>1</b> 帧，且每个字段与样本一致；</li>
 *   <li>{@code checksumOk=false} 的样本整体绝不能被当作一帧吐出来；</li>
 *   <li>帧长不在白名单内的一律丢弃（本协议没有长度字段，只能靠白名单定位边界）。</li>
 * </ul>
 *
 * <p>⚠️ 样本里 ff03 的 {@code bytes} 并非全是 13/15：18 条畸形样本分三类：
 * <ul>
 *   <li>5 条 13B —— 长度合法但<b>校验失败</b>，必须丢弃；</li>
 *   <li>1 条 12B —— 截断残片，校验碰巧通过但<b>长度非法</b>，必须丢弃；</li>
 *   <li>12 条 25B/36B/37B/96B —— 日志提取时把<b>多条真帧串在一起</b>（如 25B = 13B 残片 + 13B 真帧）。</li>
 * </ul>
 * 第三类里混着的合法子帧是流里真实存在的，成帧器<b>重同步</b>后恢复它们属于正确行为，不算「把畸形串当帧」。
 * 因此判据不是「一个帧都不能吐」，而是：
 * ① 绝不把整条畸形串整体当作一帧；② 吐出来的每一帧都必须自校验通过、长度合法、且是样本里的真实片段。</p>
 */
class SerialGoldenSamplesTest {

    /** ff03 的合法帧长白名单（PROTOCOL.md §1.3 / §1.4①）。 */
    private static final List<Integer> HEARTBEAT_LENGTHS = List.of(13, 15);

    @Test
    @DisplayName("ff03 心跳/状态帧：81 条合法样本逐字段一致，18 条畸形/校验失败样本不得整条成帧")
    void ff03Heartbeat() {
        JsonNode samples = Samples.golden().get("ff03").get("samples");
        assertEquals(99, samples.size(), "样本条数变了，说明基线被改动");

        int decoded = 0;
        int dropped = 0;
        for (JsonNode s : samples) {
            String hex = s.get("hex").asText().toLowerCase(Locale.ROOT);
            int bytes = s.get("bytes").asInt();
            boolean checksumOk = s.get("checksumOk").asBoolean();

            List<SerialFrame> frames = SerialFramer.split(Samples.wire(hex));

            boolean legalLength = HEARTBEAT_LENGTHS.contains(bytes);
            if (!legalLength || !checksumOk) {
                for (SerialFrame f : frames) {
                    String sub = Hex.encode(f.raw());
                    assertTrue(HEARTBEAT_LENGTHS.contains(f.byteLength()),
                            "畸形样本不得吐出非法帧长的帧: " + hex + " -> " + sub);
                    assertTrue(hex.contains(sub), "吐出的帧必须是样本内的真实片段: " + hex + " -> " + sub);
                    assertTrue(checksumOk(f.raw()), "吐出的帧必须自校验通过: " + sub);
                }
                assertTrue(frames.stream().noneMatch(f -> f.byteLength() == bytes),
                        "整条畸形串不得被当作一帧: " + hex);
                dropped++;
                continue;
            }

            assertEquals(1, frames.size(), hex);
            assertTrue(frames.get(0) instanceof SerialFrame.Heartbeat, hex);
            SerialFrame.Heartbeat hb = (SerialFrame.Heartbeat) frames.get(0);

            assertEquals(s.get("vestNum").asInt(), hb.vestNum(), "vestNum " + hex);
            assertEquals(s.get("ammo1").asInt(), hb.ammo1(), "ammo1 " + hex);
            assertEquals(s.get("hp").asInt(), hb.hp(), "hp " + hex);
            assertEquals(s.get("ammo2").asInt(), hb.ammo2(), "ammo2 " + hex);
            assertEquals(bytes == 15, hb.longVersion(), "长版判定 " + hex);
            assertEquals(hex, Hex.encode(hb.raw()), "逐字节还原 " + hex);
            decoded++;
        }

        assertEquals(81, decoded, "合法 ff03 样本数");
        assertEquals(18, dropped, "被丢弃的 ff03 样本数（5 条校验失败 + 1 条 12B 残片 + 12 条拼接串）");
    }

    @Test
    @DisplayName("ff04 室外 GPS 帧：3000 条全部解析，经纬度按大端 ÷1000000")
    void ff04Gps() {
        JsonNode samples = Samples.golden().get("ff04").get("samples");
        assertEquals(3000, samples.size());

        for (JsonNode s : samples) {
            String hex = s.get("hex").asText().toLowerCase(Locale.ROOT);
            assertEquals(16, s.get("bytes").asInt());
            assertTrue(s.get("checksumOk").asBoolean());

            List<SerialFrame> frames = SerialFramer.split(Samples.wire(hex));
            assertEquals(1, frames.size(), hex);
            SerialFrame.Gps gps = (SerialFrame.Gps) frames.get(0);

            assertEquals(s.get("vestNum").asInt(), gps.vestNum(), "vestNum " + hex);
            assertEquals(s.get("lngRaw").asLong(), gps.lngRaw(), "lngRaw " + hex);
            assertEquals(s.get("latRaw").asLong(), gps.latRaw(), "latRaw " + hex);
            assertEquals(s.get("lng").asDouble(), gps.lng(), 1e-9, "lng " + hex);
            assertEquals(s.get("lat").asDouble(), gps.lat(), 1e-9, "lat " + hex);
            assertEquals(hex, Hex.encode(gps.raw()), "逐字节还原 " + hex);
        }
    }

    @Test
    @DisplayName("ff05 击中记录帧：3 条全部解析（击杀判定核心）")
    void ff05Hit() {
        JsonNode samples = Samples.golden().get("ff05").get("samples");
        assertEquals(3, samples.size());

        for (JsonNode s : samples) {
            String hex = s.get("hex").asText().toLowerCase(Locale.ROOT);
            assertEquals(11, s.get("bytes").asInt());
            assertTrue(s.get("checksumOk").asBoolean());

            List<SerialFrame> frames = SerialFramer.split(Samples.wire(hex));
            assertEquals(1, frames.size(), hex);
            SerialFrame.Hit hit = (SerialFrame.Hit) frames.get(0);

            assertEquals(s.get("hitVestNum").asInt(), hit.hitVestNum(), "hitVestNum " + hex);
            assertEquals(s.get("shooterNum").asInt(), hit.shooterNum(), "shooterNum " + hex);
            assertEquals(s.get("hitPart").asInt(), hit.hitPart(), "hitPart " + hex);
            assertEquals(hex, Hex.encode(hit.raw()), "逐字节还原 " + hex);
        }
    }

    @Test
    @DisplayName("ff06 / ff07 / ff0a 人造样本：日志里从未出现过的帧类型")
    void syntheticFrames() {
        JsonNode synth = Samples.syntheticFrames();

        JsonNode ff06 = synth.get("ff06");
        assertEquals(16, ff06.get("bytes").asInt());
        SerialFrame.IndoorLocation indoor =
                (SerialFrame.IndoorLocation) one(ff06.get("hex").asText());
        assertEquals(ff06.get("expect").get("vestNum").asInt(), indoor.vestNum(), "ff06 vestNum");
        assertEquals(ff06.get("expect").get("width").asLong(), indoor.width(), "ff06 width");
        assertEquals(ff06.get("expect").get("height").asLong(), indoor.height(), "ff06 height");

        JsonNode ff07 = synth.get("ff07");
        assertEquals(12, ff07.get("bytes").asInt());
        SerialFrame.Mortar mortar = (SerialFrame.Mortar) one(ff07.get("hex").asText());
        assertEquals(ff07.get("expect").get("vestNum").asInt(), mortar.vestNum(), "ff07 vestNum");
        assertEquals(ff07.get("expect").get("angle").asInt(), mortar.angle(), "ff07 angle");
        assertEquals(ff07.get("expect").get("distance").asInt(), mortar.distance(), "ff07 distance");

        JsonNode ff0a = synth.get("ff0a");
        assertEquals(18, ff0a.get("bytes").asInt());
        SerialFrame.SimNo simNo = (SerialFrame.SimNo) one(ff0a.get("hex").asText());
        assertEquals(ff0a.get("expect").get("vestNum").asInt(), simNo.vestNum(), "ff0a vestNum");
        assertEquals(ff0a.get("expect").get("simNo").asText(), simNo.simNo(), "ff0a simNo");
    }

    private static SerialFrame one(String hex) {
        List<SerialFrame> frames = SerialFramer.split(Samples.wire(hex.toLowerCase(Locale.ROOT)));
        assertEquals(1, frames.size(), hex);
        return frames.get(0);
    }

    /**
     * 独立复算协议 A 累加和：校验字节位于 {@code len - 3}（其后两位是帧尾 {@code AF FA}），
     * 等于前面所有字节之和 {@code & 0xFF}。刻意不调用被测代码。
     */
    private static boolean checksumOk(byte[] raw) {
        int expected = raw[raw.length - 3] & 0xFF;
        int sum = 0;
        for (int i = 0; i < raw.length - 3; i++) {
            sum += raw[i] & 0xFF;
        }
        return (sum & 0xFF) == expected;
    }
}
