package com.dualx.core.storage;

import com.dualx.core.storage.ImageHeaders.ImageFormat;
import com.dualx.core.storage.ImageHeaders.ImageSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片头嗅探（D09-⑥）：白名单判定与宽高解析。
 *
 * <p>PNG / JPEG 用 JDK 自带的编码器产出**真实字节**，不复刻文件头——复刻只能证明「我按自己的
 * 理解写了解析器」，而真实编码器输出的字节才代表现场上传的东西（含 JPEG 前置 APPn 段）。
 * WebP 没有 JDK 编码器（这正是 {@code ImageHeaders} 存在的理由），只能按规范手工拼块，
 * 故三种块各测一遍。</p>
 *
 * <p>本类只碰字节、不碰磁盘，与 {@link LocalFileStore} 无关。</p>
 */
class ImageHeadersTest {

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, 0x0A, 0x1A, 0x0A};

    @Test
    @DisplayName("PNG：宽高取 IHDR（大端）")
    void png_readsSizeFromIhdr() throws IOException {
        ImageSize size = sniffEncoded(7, 5, "png");

        assertThat(size.format()).isEqualTo(ImageFormat.PNG);
        assertThat(size.width()).isEqualTo(7);
        assertThat(size.height()).isEqualTo(5);
    }

    /**
     * JPEG 的宽高在 SOFn 段里，而 SOFn 前面通常还隔着 JFIF 的 APP0 段——
     * 解析器必须真的按段长逐段跳过。把「高在前、宽在后」写反是这段代码最经典的错法，
     * 故宽高刻意取不同值，写反必然失败。
     */
    @Test
    @DisplayName("JPEG：跳过前置 APPn 段找到 SOFn，且不把高宽写反")
    void jpeg_skipsSegmentsAndReadsSizeFromStartOfFrame() throws IOException {
        ImageSize size = sniffEncoded(9, 4, "jpg");

        assertThat(size.format()).isEqualTo(ImageFormat.JPEG);
        assertThat(size.width()).isEqualTo(9);
        assertThat(size.height()).isEqualTo(4);
    }

    @Test
    @DisplayName("WebP 有损（VP8 ）：14 位宽高在小端双字节里")
    void webpLossy_readsFourteenBitSize() {
        ImageSize size = ImageHeaders.sniff(webpLossy(3000, 2000)).orElseThrow();

        assertThat(size.format()).isEqualTo(ImageFormat.WEBP);
        assertThat(size.width()).isEqualTo(3000);
        assertThat(size.height()).isEqualTo(2000);
    }

    @Test
    @DisplayName("WebP 无损（VP8L）：位打包里存的是「宽 − 1」与「高 − 1」")
    void webpLossless_readsMinusOneEncodedSize() {
        ImageSize size = ImageHeaders.sniff(webpLossless(640, 480)).orElseThrow();

        assertThat(size.width()).isEqualTo(640);
        assertThat(size.height()).isEqualTo(480);
    }

    @Test
    @DisplayName("WebP 扩展（VP8X）：画布尺寸同样按「减一」的 3 字节小端编码")
    void webpExtended_readsCanvasSize() {
        ImageSize size = ImageHeaders.sniff(webpExtended(4000, 3000)).orElseThrow();

        assertThat(size.width()).isEqualTo(4000);
        assertThat(size.height()).isEqualTo(3000);
    }

    @Test
    @DisplayName("白名单外的一律不认：GIF 是合法图片但不在白名单内，文本更不是")
    void sniff_rejectsFormatsOutsideTheWhitelist() {
        assertThat(ImageHeaders.sniff("GIF89a...".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(ImageHeaders.sniff("not an image at all".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(ImageHeaders.sniff(new byte[0])).isEmpty();
        assertThat(ImageHeaders.sniff(null)).isEmpty();
    }

    @Test
    @DisplayName("头被截断或宽高为零 → 不认：读不出边界与不认识的格式同处置，否则标定越界校验会静默失效")
    void sniff_rejectsTruncatedOrZeroSizedHeaders() {
        byte[] lossy = webpLossy(3000, 2000);

        assertThat(ImageHeaders.sniff(PNG_SIGNATURE)).isEmpty();
        assertThat(ImageHeaders.sniff(pngHeader(0, 3000))).isEmpty();
        assertThat(ImageHeaders.sniff(pngHeader(4000, 0))).isEmpty();
        assertThat(ImageHeaders.sniff(Arrays.copyOf(lossy, 29))).isEmpty();
    }

    @Test
    @DisplayName("格式自带的 Content-Type 与扩展名就是契约里的那一组（响应头与落盘文件名都取它）")
    void format_carriesContractContentTypeAndExtension() {
        assertThat(ImageFormat.PNG.contentType()).isEqualTo("image/png");
        assertThat(ImageFormat.PNG.extension()).isEqualTo(".png");
        assertThat(ImageFormat.JPEG.contentType()).isEqualTo("image/jpeg");
        assertThat(ImageFormat.JPEG.extension()).isEqualTo(".jpg");
        assertThat(ImageFormat.WEBP.contentType()).isEqualTo("image/webp");
        assertThat(ImageFormat.WEBP.extension()).isEqualTo(".webp");
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    private static ImageSize sniffEncoded(int width, int height, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean written = ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB),
                format, out);
        assertThat(written).as("JDK 应自带 %s 编码器", format).isTrue();
        return ImageHeaders.sniff(out.toByteArray()).orElseThrow();
    }

    /** 24 字节的最小 PNG 头：签名 + IHDR + 宽高。PNG 的宽高就在固定偏移上，后面还有没有数据不影响。 */
    private static byte[] pngHeader(int width, int height) {
        byte[] b = new byte[24];
        System.arraycopy(PNG_SIGNATURE, 0, b, 0, 8);
        System.arraycopy("IHDR".getBytes(StandardCharsets.US_ASCII), 0, b, 12, 4);
        beInt(b, 16, width);
        beInt(b, 20, height);
        return b;
    }

    /** 有损 VP8：RIFF/WEBP 容器 + "VP8 " 块：3B 帧标签 + 3B 起始码 + 各 2B 的 14 位宽高。 */
    private static byte[] webpLossy(int width, int height) {
        byte[] b = riffChunk("VP8 ", 10);
        b[23] = (byte) 0x9D;
        b[24] = 0x01;
        b[25] = 0x2A;
        leShort(b, 26, width);
        leShort(b, 28, height);
        return b;
    }

    /** 无损 VP8L："VP8L" 块：1B 签名 0x2F + 4B 位打包（低 14 位宽 − 1、次 14 位高 − 1）。 */
    private static byte[] webpLossless(int width, int height) {
        byte[] b = riffChunk("VP8L", 5);
        b[20] = 0x2F;
        leInt(b, 21, ((height - 1) << 14) | (width - 1));
        return b;
    }

    /** 扩展 VP8X："VP8X" 块：1B 标志 + 3B 保留 + 各 3B 的画布宽 − 1、高 − 1。 */
    private static byte[] webpExtended(int width, int height) {
        byte[] b = riffChunk("VP8X", 10);
        leTriple(b, 24, width - 1);
        leTriple(b, 27, height - 1);
        return b;
    }

    /** RIFF 头 12B（RIFF + 长度 + WEBP）+ 块头 8B（块名 + 载荷长度）+ 载荷。 */
    private static byte[] riffChunk(String chunkId, int payloadLength) {
        byte[] b = new byte[20 + payloadLength];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        leInt(b, 4, 4 + 8 + payloadLength);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, b, 8, 4);
        System.arraycopy(chunkId.getBytes(StandardCharsets.US_ASCII), 0, b, 12, 4);
        leInt(b, 16, payloadLength);
        return b;
    }

    private static void beInt(byte[] b, int offset, int value) {
        b[offset] = (byte) (value >>> 24);
        b[offset + 1] = (byte) (value >>> 16);
        b[offset + 2] = (byte) (value >>> 8);
        b[offset + 3] = (byte) value;
    }

    private static void leShort(byte[] b, int offset, int value) {
        b[offset] = (byte) value;
        b[offset + 1] = (byte) (value >>> 8);
    }

    private static void leTriple(byte[] b, int offset, int value) {
        b[offset] = (byte) value;
        b[offset + 1] = (byte) (value >>> 8);
        b[offset + 2] = (byte) (value >>> 16);
    }

    private static void leInt(byte[] b, int offset, int value) {
        b[offset] = (byte) value;
        b[offset + 1] = (byte) (value >>> 8);
        b[offset + 2] = (byte) (value >>> 16);
        b[offset + 3] = (byte) (value >>> 24);
    }
}
