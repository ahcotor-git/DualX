package com.dualx.core.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 文件存储与签名 URL 配置（{@code scds.files.*}），口径见 decision-register 的
 * 「D09 / D15 执行边界」（D09-①③⑤⑥）。
 *
 * <p>与 {@code scds.auth.jwt.*} 同一套默认值哲学：漏配得到明确默认值，
 * <b>显式配错才是配置错误</b>——静默回落会把现场故障推到运行期才暴露。</p>
 *
 * <p><b>签名密钥未配置是唯一例外</b>：它不像 TTL 那样有「正确的默认值」可回落。
 * 本机开发与自动测试都不该为此先手配一个密钥，故未配置时**随机生成一个并打 WARN**
 * （重启后旧签名 URL 失效；TTL 只有 15 分钟，影响可忽略）；
 * 但显式配了却短于 32 字符则直接启动失败——那是被误当成普通配置项填了弱值的信号。</p>
 *
 * @param root          存储根目录；留空用 {@code ${user.home}/.scds/files}（沿用旧系统 .scds 约定）
 * @param signSecret    签名 URL 的 HMAC 密钥；留空则本次启动随机生成（见上）
 * @param signTtl       签名 URL 有效期，默认 15 分钟
 * @param maxImageBytes 单张底图上限，默认 20MB
 */
@ConfigurationProperties(prefix = "scds.files")
public record FileStorageProperties(
        String root,
        String signSecret,
        Duration signTtl,
        Long maxImageBytes) {

    private static final Logger LOG = LoggerFactory.getLogger(FileStorageProperties.class);

    /** 误填弱值的下限；与「未配置」区分开，故只在显式配置时校验。 */
    private static final int MIN_SECRET_LENGTH = 32;

    private static final Duration DEFAULT_SIGN_TTL = Duration.ofMinutes(15);

    private static final long DEFAULT_MAX_IMAGE_BYTES = 20L * 1024 * 1024;

    public FileStorageProperties {
        if (root == null || root.isBlank()) {
            root = Path.of(System.getProperty("user.home"), ".scds", "files").toString();
        }
        if (signSecret == null || signSecret.isBlank()) {
            signSecret = randomSecret();
            LOG.warn("未配置 scds.files.sign-secret，已为本次启动随机生成签名密钥；"
                    + "重启后此前签发的下载 URL 全部失效。生产部署请在 .env 显式提供 SCDS_FILE_SIGN_SECRET。");
        } else if (signSecret.trim().length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                    "scds.files.sign-secret is too short; at least " + MIN_SECRET_LENGTH + " characters required.");
        }
        if (signTtl == null) {
            signTtl = DEFAULT_SIGN_TTL;
        }
        requirePositive(signTtl, "sign-ttl");
        if (maxImageBytes == null) {
            maxImageBytes = DEFAULT_MAX_IMAGE_BYTES;
        }
        if (maxImageBytes <= 0) {
            throw new IllegalArgumentException("scds.files.max-image-bytes must be positive.");
        }
    }

    private static String randomSecret() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    /**
     * 显式配置的值必须为正。{@code null}（漏配）已在上面补默认值，走不到这里；
     * 报错只带属性名，不回显配置值。
     */
    private static void requirePositive(Duration value, String key) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("scds.files." + key + " must be positive.");
        }
    }
}
