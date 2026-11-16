package com.dualx.api.security;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * 双用途 decoder 门面（设计 03b §4 / §10.3）。
 *
 * <p>账号（access / refresh）与设备是<b>两套独立信任</b>——独立 issuer、独立公钥。本类先做结构检查
 * 取出<b>未验签</b>的 {@code aud}，据此<b>选</b>一条信任项：{@code scds-device} 走设备 decoder，
 * 其余走账号 decoder。</p>
 *
 * <p><b>为什么「选」而不是「试遍」。</b>把两把公钥都试一遍，会让「账号密钥签出的设备令牌」在
 * 签名层面通过——那正是 03b §3 要禁的交叉签发。分派后，账号密钥签出的设备令牌会被送到设备
 * decoder，验签失败即 401；设备密钥签出的账号令牌同理。跨用途伪造在这里没有出口。</p>
 *
 * <p><b>拿未验签字段做分派的边界。</b>{@code aud} 在此时尚未验签，因此攻击者可以随意指定它——
 * 但指定它只能改变「用哪把公钥验签」，不能改变「验签必须通过」这件事。伪造用途得到的仍是 401，
 * 只是错误路径从账号那一侧换到了设备那一侧；两者都只回 401 {@code AUTH_TOKEN_INVALID}。</p>
 *
 * <p><b>设备信任项缺席时显式拒绝。</b>纯账号实例（未配设备 issuer / 公钥）不构造设备 decoder，
 * 设备令牌一律 401——宁可拒绝，也不拿账号公钥去验设备令牌。</p>
 */
final class JwtDecoderFacade implements JwtDecoder {

    private final JwtDecoder account;

    /** 设备信任项；本进程不参与设备用途时为 {@code null}。 */
    private final JwtDecoder device;

    JwtDecoderFacade(JwtDecoder account, JwtDecoder device) {
        this.account = account;
        this.device = device;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String audience = JwtDecoderConfig.validateRawClaims(token);
        if (!JwtDecoderConfig.AUD_DEVICE.equals(audience)) {
            return account.decode(token);
        }
        if (device == null) {
            throw new BadJwtException(
                    "本实例未配置设备用途信任项（scds.auth.jwt.device.issuer / public-key），不接受设备令牌");
        }
        return device.decode(token);
    }
}
