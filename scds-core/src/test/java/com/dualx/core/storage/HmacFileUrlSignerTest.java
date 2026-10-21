package com.dualx.core.storage;

import com.dualx.core.port.FileUrlSigner.SignedFileUrl;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 签名 URL 的边界行为（D09-②⑤）。纯单测，不碰数据库与文件系统，用可控时钟验证有效期。
 */
class HmacFileUrlSignerTest {

    private static final String PATH = "maps/0f8f/map.png";
    private static final String SECRET_A = "a".repeat(48);
    private static final String SECRET_B = "b".repeat(48);

    private final MutableClock clock = new MutableClock();
    private final HmacFileUrlSigner signer = new HmacFileUrlSigner(properties(SECRET_A), clock);

    @Test
    void signedUrlVerifiesBeforeExpiry() {
        SignedFileUrl signed = signer.sign(PATH);

        assertThat(signer.verify(PATH, signed.exp(), signed.signature())).isTrue();
        assertThat(Long.parseLong(signed.exp()))
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(15)).getEpochSecond());
    }

    @Test
    void signatureIsReusableUntilExpiry() {
        // 「可重复」是 D09-② 的取舍：浏览器与 <img> 刷新不该被一次性语义打断。
        SignedFileUrl signed = signer.sign(PATH);

        assertThat(signer.verify(PATH, signed.exp(), signed.signature())).isTrue();
        clock.advance(Duration.ofMinutes(14));
        assertThat(signer.verify(PATH, signed.exp(), signed.signature())).isTrue();
    }

    @Test
    void expiredSignatureIsRejected() {
        SignedFileUrl signed = signer.sign(PATH);

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertThat(signer.verify(PATH, signed.exp(), signed.signature())).isFalse();
    }

    @Test
    void tamperedPathIsRejected() {
        SignedFileUrl signed = signer.sign(PATH);

        assertThat(signer.verify("maps/0f8f/other.png", signed.exp(), signed.signature())).isFalse();
    }

    @Test
    void extendedExpiryWithoutResigningIsRejected() {
        SignedFileUrl signed = signer.sign(PATH);
        long forged = Long.parseLong(signed.exp()) + 3600;

        assertThat(signer.verify(PATH, Long.toString(forged), signed.signature())).isFalse();
    }

    @Test
    void signatureFromAnotherSecretIsRejected() {
        SignedFileUrl signed = new HmacFileUrlSigner(properties(SECRET_B), clock).sign(PATH);

        assertThat(signer.verify(PATH, signed.exp(), signed.signature())).isFalse();
    }

    @Test
    void malformedInputsAreRejected() {
        SignedFileUrl signed = signer.sign(PATH);

        assertThat(signer.verify(PATH, "不是数字", signed.signature())).isFalse();
        assertThat(signer.verify(PATH, signed.exp(), "")).isFalse();
        assertThat(signer.verify(PATH, null, signed.signature())).isFalse();
        assertThat(signer.verify(null, signed.exp(), signed.signature())).isFalse();
        assertThat(signer.verify(PATH, signed.exp(), null)).isFalse();
    }

    private static FileStorageProperties properties(String signSecret) {
        return new FileStorageProperties(null, signSecret, null, null);
    }

    /** 可手动推进的时钟：签名有效期靠它验证，用真实时钟没法在毫秒内跨过 15 分钟。 */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-21T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
