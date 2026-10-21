package com.dualx.core.storage;

import com.dualx.core.port.FileUrlSigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * {@link FileUrlSigner} 的 HMAC-SHA256 实现（D09-②⑤）。
 *
 * <p>被签内容固定为 {@code 相对路径 + '\n' + exp}。用换行当分隔符是为了消除拼接歧义：
 * 直接串接时 {@code "a/1" + "23"} 与 {@code "a/12" + "3"} 会得到同一份摘要，
 * 分隔符让不同的（路径, 到期）组合必然落到不同摘要。</p>
 *
 * <p>比较用 {@link MessageDigest#isEqual}（恒定时间），不用 {@code String.equals}——
 * 后者会按字节短路比较，理论上可被逐字节试探。</p>
 */
@Component
@EnableConfigurationProperties(FileStorageProperties.class)
public class HmacFileUrlSigner implements FileUrlSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;
    private final Duration ttl;
    private final Clock clock;

    /**
     * 必须显式标 {@link Autowired}：本类有两个构造器，Spring 在「多构造器且无标注」时
     * 会退化成找无参构造器，结果是启动即报 No default constructor found。
     */
    @Autowired
    public HmacFileUrlSigner(FileStorageProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 可注入时钟的构造器，仅供测试推进时间；生产走单参构造器。 */
    HmacFileUrlSigner(FileStorageProperties properties, Clock clock) {
        this.key = properties.signSecret().getBytes(StandardCharsets.UTF_8);
        this.ttl = properties.signTtl();
        this.clock = clock;
    }

    @Override
    public SignedFileUrl sign(String relativePath) {
        String exp = Long.toString(clock.instant().plus(ttl).getEpochSecond());
        return new SignedFileUrl(exp, hmac(relativePath, exp));
    }

    @Override
    public boolean verify(String relativePath, String exp, String signature) {
        if (relativePath == null || exp == null || signature == null || signature.isEmpty()) {
            return false;
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(exp);
        } catch (NumberFormatException notANumber) {
            return false;
        }
        if (clock.instant().getEpochSecond() > expiresAt) {
            return false;
        }
        byte[] expected = hmac(relativePath, exp).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private String hmac(String relativePath, String exp) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            mac.update(relativePath.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            mac.update(exp.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (GeneralSecurityException e) {
            // 算法与密钥在 JDK 与配置校验层面已确定可用，走到这里说明运行环境损坏，不是业务失败。
            throw new IllegalStateException("签发文件 URL 失败", e);
        }
    }
}
