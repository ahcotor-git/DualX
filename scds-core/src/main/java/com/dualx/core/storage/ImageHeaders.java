package com.dualx.core.storage;

import java.util.Optional;

/**
 * 图片头嗅探：判定**真实**格式并读出像素宽高（D09-⑥）。
 *
 * <p><b>为什么不用 {@code ImageIO}</b>：JDK 自带解码器只认 PNG / JPEG / GIF / BMP，
 * 白名单里的 WebP **没有解码器**（{@code ImageIO.read} 对 WebP 直接返回 {@code null}）。
 * 若改用「ImageIO 能读就算合法」，WebP 要么被误拒，要么得引第三方解码器——
 * 而这里其实只需要文件头里的几个字段，不解码像素。</p>
 *
 * <p><b>为什么只认图片头</b>：扩展名来自上传方，Content-Type 也是上传方填的，
 * 两者都能随手写成一个脚本。按真实字节判定，才谈得上「白名单」。</p>
 *
 * <p><b>宽高的用途</b>：上传后回填 {@code t_map.image_width/image_height}，
 * {@code putUwbMeta} 的越界校验靠它们比对基站像素坐标（{@code validateUwbMeta}）。
 * 因此「读不出宽高」与「格式不认识」同处置：都算不合法图片，宁可拒收也不存一张
 * 没有边界的底图——否则标定校验会静默失效。</p>
 *
 * <p>纯函数、无状态、不碰磁盘：给一段字节，要么给出格式与尺寸，要么明确说不知道。</p>
 */
public final class ImageHeaders {

    /** 白名单内的图片格式（D09-⑥）。{@code extension} 进落盘文件名，{@code contentType} 进响应头。 */
    public enum ImageFormat {
        PNG("image/png", ".png"),
        JPEG("image/jpeg", ".jpg"),
        WEBP("image/webp", ".webp");

        private final String contentType;
        private final String extension;

        ImageFormat(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public String contentType() {
            return contentType;
        }

        public String extension() {
            return extension;
        }
    }

    /** 嗅探结果：格式 + 像素尺寸。 */
    public record ImageSize(ImageFormat format, int width, int height) {
    }

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A};

    /** VP8（有损）帧头里的起始码，用来确认这段真的是 VP8 关键帧而不是随便凑的四字节。 */
    private static final int VP8_START_CODE_0 = 0x9D;

    private static final int VP8_START_CODE_1 = 0x01;

    private static final int VP8_START_CODE_2 = 0x2A;

    private ImageHeaders() {
    }

    /**
     * 嗅探图片头。
     *
     * @return 格式与像素尺寸；不是白名单内图片、或文件头被截断 / 损坏时返回空
     */
    public static Optional<ImageSize> sniff(byte[] content) {
        if (content == null) {
            return Optional.empty();
        }
        if (startsWith(content, 0, PNG_SIGNATURE)) {
            return png(content);
        }
        if (isJpeg(content)) {
            return jpeg(content);
        }
        if (isWebp(content)) {
            return webp(content);
        }
        return Optional.empty();
    }

    // ── PNG ─────────────────────────────────────────────────────────────────

    /** 8B 签名之后必须是 IHDR：宽高就在它的头 8 个字节里（大端）。 */
    private static Optional<ImageSize> png(byte[] b) {
        if (b.length < 24 || !startsWith(b, 12, "IHDR".getBytes())) {
            return Optional.empty();
        }
        return sized(ImageFormat.PNG, beInt(b, 16), beInt(b, 20));
    }

    // ── JPEG ────────────────────────────────────────────────────────────────

    private static boolean isJpeg(byte[] b) {
        return b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    /**
     * JPEG 的宽高在 SOFn 段里，位置不固定：前面可能隔着任意多个 APPn / DQT / DHT 段，
     * 必须按段长逐段跳过，不能假定「第 N 个字节就是宽」。
     */
    private static Optional<ImageSize> jpeg(byte[] b) {
        int pos = 2;
        while (pos + 1 < b.length) {
            if ((b[pos] & 0xFF) != 0xFF) {
                return Optional.empty();
            }
            int marker = b[pos + 1] & 0xFF;
            pos += 2;
            if (marker == 0xFF) {
                pos--;                            // 段间填充字节，重读同一个 0xFF
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;                         // 无长度字段的独立标记
            }
            if (marker == 0xD9 || marker == 0xDA) {
                return Optional.empty();          // 已到文件尾或进了扫描数据，此前没有 SOF
            }
            if (pos + 1 >= b.length) {
                return Optional.empty();
            }
            int length = beShort(b, pos);
            if (length < 2 || pos + length > b.length) {
                return Optional.empty();
            }
            if (isStartOfFrame(marker)) {
                if (pos + 7 > b.length) {
                    return Optional.empty();
                }
                // 段体：精度(1B) → 高(2B) → 宽(2B)，相对段长字段起点偏移 2
                return sized(ImageFormat.JPEG, beShort(b, pos + 5), beShort(b, pos + 3));
            }
            pos += length;
        }
        return Optional.empty();
    }

    /**
     * SOF0~SOF15 都带宽高，但 {@code C4}（DHT）、{@code C8}（JPG 扩展）、{@code CC}（DAC）
     * 占的是同区间的号却不含尺寸，认错会把数据段的头两个字节当成宽高。
     */
    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    // ── WebP ────────────────────────────────────────────────────────────────

    private static boolean isWebp(byte[] b) {
        return b.length >= 16 && startsWith(b, 0, "RIFF".getBytes()) && startsWith(b, 8, "WEBP".getBytes());
    }

    /** RIFF 容器里第一个块决定编码方式：有损 VP8 / 无损 VP8L / 扩展 VP8X，三者的宽高位置各不同。 */
    private static Optional<ImageSize> webp(byte[] b) {
        if (startsWith(b, 12, "VP8 ".getBytes())) {
            return webpLossy(b);
        }
        if (startsWith(b, 12, "VP8L".getBytes())) {
            return webpLossless(b);
        }
        if (startsWith(b, 12, "VP8X".getBytes())) {
            return webpExtended(b);
        }
        return Optional.empty();
    }

    /** 有损：3B 帧标签 + 3B 起始码 + 2B 宽 + 2B 高（宽高各占 14 位，小端）。 */
    private static Optional<ImageSize> webpLossy(byte[] b) {
        if (b.length < 30
                || (b[23] & 0xFF) != VP8_START_CODE_0
                || (b[24] & 0xFF) != VP8_START_CODE_1
                || (b[25] & 0xFF) != VP8_START_CODE_2) {
            return Optional.empty();
        }
        return sized(ImageFormat.WEBP, leShort(b, 26) & 0x3FFF, leShort(b, 28) & 0x3FFF);
    }

    /** 无损：签名 1B（0x2F）+ 4B 位打包，低 14 位是「宽 − 1」，紧接着 14 位是「高 − 1」。 */
    private static Optional<ImageSize> webpLossless(byte[] b) {
        if (b.length < 25 || (b[20] & 0xFF) != 0x2F) {
            return Optional.empty();
        }
        int bits = leInt(b, 21);
        return sized(ImageFormat.WEBP, (bits & 0x3FFF) + 1, ((bits >>> 14) & 0x3FFF) + 1);
    }

    /** 扩展：1B 标志 + 3B 保留，随后是 3B 画布宽 − 1 与 3B 画布高 − 1（小端）。 */
    private static Optional<ImageSize> webpExtended(byte[] b) {
        if (b.length < 30) {
            return Optional.empty();
        }
        return sized(ImageFormat.WEBP, leTriple(b, 24) + 1, leTriple(b, 27) + 1);
    }

    // ── 读取与校验 ──────────────────────────────────────────────────────────

    /** 宽高必须为正：0 或负数只可能来自损坏的头，放行会让越界校验拿到一个无意义的边界。 */
    private static Optional<ImageSize> sized(ImageFormat format, int width, int height) {
        return width > 0 && height > 0 ? Optional.of(new ImageSize(format, width, height)) : Optional.empty();
    }

    private static boolean startsWith(byte[] source, int offset, byte[] prefix) {
        if (offset + prefix.length > source.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (source[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int beShort(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
    }

    private static int beInt(byte[] b, int offset) {
        return (beShort(b, offset) << 16) | beShort(b, offset + 2);
    }

    private static int leShort(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
    }

    private static int leTriple(byte[] b, int offset) {
        return leShort(b, offset) | ((b[offset + 2] & 0xFF) << 16);
    }

    private static int leInt(byte[] b, int offset) {
        return leShort(b, offset) | (leShort(b, offset + 2) << 16);
    }
}
