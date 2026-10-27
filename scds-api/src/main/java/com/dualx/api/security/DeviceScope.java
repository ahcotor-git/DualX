package com.dualx.api.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.OptionalInt;

/**
 * 单马甲读取的授权判定：指挥台可见全部马甲，单兵设备令牌只见自身（D04「单兵第一版仅自身」）。
 *
 * <p><b>为什么是方法级 {@code @PreAuthorize}</b>：这条规则要拿路径变量与令牌主体比对，
 * 是「同一个 URL 内的资源级范围」，域级 matcher 表达不了——正落在
 * docs/design/03-auth.md §6 Q8「按需补」的例外条款上。业务判定（编成内/外 → 404）
 * 仍在 core，本类只管「谁能看哪一号」。</p>
 *
 * <p>矩阵口径与 {@link SecurityConfig} 同源：{@code situation/vests/**} 只放行
 * {@code AUD_scds-edge}（指挥台）与 {@code AUD_scds-device}（单兵设备），其余用途在
 * filter chain 就被拒，走不到这里。</p>
 *
 * <p><b>bean 名必须显式给</b>：{@code @PreAuthorize} 按名字取 bean，而测试切片用
 * {@code @Import} 注册本类时走的是全限定名生成器——只写 {@code @Component} 会让
 * 「本地能跑、切片里 500」。给出确定的名字，扫到与导入两条路径就都指向同一个 bean。</p>
 */
@Component("deviceScope")
public class DeviceScope {

    /** 指挥台用途；与 {@code SecurityConfig} 的私有常量同源，此处只读不写。 */
    private static final String AUD_EDGE = "AUD_scds-edge";

    /**
     * 是否允许读取 {@code vestNum}。
     *
     * @param authentication 当前认证；主体不是本实例签发的 JWT（含未认证）一律不放行
     */
    public boolean allowSelf(int vestNum, Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        boolean edge = authentication.getAuthorities().stream()
                .anyMatch(authority -> AUD_EDGE.equals(authority.getAuthority()));
        if (edge) {
            return true;
        }
        // 剩下只可能是设备令牌：其 sub 就是马甲编号（RsaTokenSigner#signDeviceToken），
        // 解析不出说明不是设备令牌，按「看不到」处理，不当服务端缺陷。
        return selfVestNum(authentication).stream().anyMatch(self -> self == vestNum);
    }

    private static OptionalInt selfVestNum(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof Jwt jwt) || jwt.getSubject() == null) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(jwt.getSubject()));
        } catch (NumberFormatException notADeviceToken) {
            return OptionalInt.empty();
        }
    }
}
