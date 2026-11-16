package com.dualx.api.auth;

import com.dualx.api.dto.auth.DeviceLoginRequest;
import com.dualx.api.dto.auth.DeviceTokenPair;
import com.dualx.core.auth.DeviceAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备登录端点：{@code POST /api/v1/auth/device/login}（设计 03 §4.9、02 §4）。
 *
 * <p><b>整类条件注册，且只认设备开关。</b>设备令牌由**设备**私钥签出（{@code aud = scds-device}），
 * 因此本类的注册条件必须与设备签发能力同源——{@code scds.auth.jwt.device.signing-enabled=true}
 * 才注册；{@code false}（{@link com.dualx.api.security.DeviceJwtProperties} 的**缺省值**）时不注册，
 * 路由缺席即 404（安全层出口见 {@link com.dualx.api.security.DisabledCapabilityRouteFilter}）。</p>
 *
 * <p><b>不再与账号的 {@code login} / {@code refresh} 共用开关</b>（03b §5 / §10.2 段 92 拆分）。
 * 此前由账号开关注册，于是「账号开 / 设备关」时端点已注册却发现设备签发器未启用，
 * {@link DeviceAuthService#login} 里的能力检查会抛 {@code IllegalStateException} 变成 500——
 * 那正是 §5 第 137 行明令禁止的「把配置错误当成能力关闭响应」。因此这里
 * {@code matchIfMissing=false}（与账号的 {@code matchIfMissing=true} 相反）：漏配设备开关时按
 * **不可签发**处理，与 {@code DeviceRsaTokenSigner} 的默认关闭保持一致，宁可 404 也不 500。</p>
 *
 * <p><b>为什么 {@code device/me} 不在这里：</b>它只验签不签发（校验 SID 单端在线 + 读台账），
 * 与 {@code /api/v1/auth/me} 同理留在不条件注册的 {@link AuthController}。</p>
 *
 * <p>本类不做任何校验与令牌处理——那些是 {@link DeviceAuthService} 的职责；这里只负责
 * 「HTTP 请求 → 服务调用 → 契约 DTO」的翻译。</p>
 */
@RestController
@ConditionalOnProperty(prefix = "scds.auth.jwt.device", name = "signing-enabled",
        havingValue = "true", matchIfMissing = false)
public class DeviceAuthController {

    private final DeviceAuthService deviceAuthService;

    public DeviceAuthController(DeviceAuthService deviceAuthService) {
        this.deviceAuthService = deviceAuthService;
    }

    /** 单兵终端登录：马甲编号 + 绑定密钥换取设备令牌。 */
    @PostMapping("/api/v1/auth/device/login")
    public DeviceTokenPair deviceLogin(@RequestBody @Valid DeviceLoginRequest request,
                                      HttpServletRequest httpRequest) {
        // 限流口径只用 remoteAddr。理由同账号登录：未建立可信代理链前不读 X-Forwarded-For，
        // 任意客户端都能伪造该头，用它做限流键等于把限流键交给攻击者（设计 03 §4.10）。
        return AuthController.toDeviceTokenPair(deviceAuthService.login(request.vestNum(), request.secret(),
                httpRequest.getRemoteAddr()));
    }
}
