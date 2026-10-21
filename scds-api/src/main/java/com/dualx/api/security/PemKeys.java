package com.dualx.api.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * PEM 密钥装载：把部署方给的 PEM 文本/文件转成 JDK 密钥对象。
 *
 * <p>刻意只用 JDK 原生 API（{@link KeyFactory} + {@link X509EncodedKeySpec} /
 * {@link PKCS8EncodedKeySpec}）：不引 Spring 的 {@code RsaKeyConverters}（它属于
 * {@code spring-security-rsa} 模块，本项目没有依赖），也不引 BouncyCastle——
 * RSA 的 PKCS#8 / X.509 编码 JDK 原生就能解，为它引一个加密库不划算。</p>
 *
 * <p>不支持加密过的私钥（{@code Proc-Type: 4,ENCRYPTED}）：那种形态必须走
 * BouncyCastle 才能解。现场私钥由部署脚本以 0600 权限落盘，读文件权限本身就是保护，
 * 再加一层口令只会在无人值守重启时卡住开机。</p>
 */
final class PemKeys {

    private static final String RSA = "RSA";

    private PemKeys() {
    }

    /** 读公钥 PEM：内联文本优先，其次文件。两者都空则由调用方给出可操作的报错。 */
    static RSAPublicKey readPublicKey(AuthJwtProperties props) {
        String pem = resolve(props.publicKey(), props.publicKeyFile(), "SCDS_JWT_PUBLIC_KEY",
                "SCDS_JWT_PUBLIC_KEY_FILE");
        return parsePublicKey(pem);
    }

    /** 读签名私钥 PEM：内联文本优先，其次文件。 */
    static RSAPrivateKey readPrivateKey(AuthJwtProperties props) {
        String pem = resolve(props.privateKey(), props.privateKeyFile(), "SCDS_JWT_PRIVATE_KEY",
                "SCDS_JWT_PRIVATE_KEY_FILE");
        return parsePrivateKey(pem);
    }

    static RSAPublicKey parsePublicKey(String pem) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance(RSA)
                    .generatePublic(new X509EncodedKeySpec(decode(pem)));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "验签公钥不是合法的 X.509/SubjectPublicKeyInfo PEM（应为 -----BEGIN PUBLIC KEY----- 开头）："
                            + ex.getMessage(), ex);
        }
    }

    static RSAPrivateKey parsePrivateKey(String pem) {
        try {
            return (RSAPrivateKey) KeyFactory.getInstance(RSA)
                    .generatePrivate(new PKCS8EncodedKeySpec(decode(pem)));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "签名私钥不是合法的 PKCS#8 PEM（应为 -----BEGIN PRIVATE KEY----- 开头，"
                            + "而不是 BEGIN RSA PRIVATE KEY 的 PKCS#1）：" + ex.getMessage(), ex);
        }
    }

    /**
     * 公钥指纹：SHA-256(SubjectPublicKeyInfo) 的 base64url。
     *
     * <p>只用于启动日志——把指纹打进日志，运维才能在不接触私钥的前提下核对
     * 「这台机器验的是哪把钥匙」，也让换钥前后可从日志区分。</p>
     */
    static String fingerprint(RSAPublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 未提供 SHA-256", ex);
        }
    }

    private static String resolve(String inline, String file, String inlineEnv, String fileEnv) {
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new UncheckedIOException("读取 PEM 文件失败：" + file, ex);
            }
        }
        throw new IllegalStateException(
                "未配置 PEM：请提供 " + inlineEnv + " 或 " + fileEnv + "（云管签发场景则改配 SCDS_JWKS_URI）");
    }

    /**
     * 去掉 PEM 封装行与头行后取 DER 字节。
     *
     * <p>用 MIME 解码器而非基本解码器：PEM 正文按 64 列折行，基本解码器遇到换行会抛错。
     * 头行（如 {@code Proc-Type: 4,ENCRYPTED}）按「含冒号」过滤——Base64 字符集里没有冒号，
     * 这个判据不会误伤正文。</p>
     */
    private static byte[] decode(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("PEM 内容为空");
        }
        StringBuilder body = new StringBuilder();
        for (String line : pem.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-----") || trimmed.isEmpty() || trimmed.contains(":")) {
                continue;
            }
            body.append(trimmed);
        }
        return Base64.getMimeDecoder().decode(body.toString());
    }
}
