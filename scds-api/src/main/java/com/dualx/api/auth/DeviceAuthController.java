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
 * <p><b>整类条件注册。</b>设备令牌同样要由本进程用私钥签出（{@code aud = scds-device}），
 * 因此它与账号的 {@code login} / {@code refresh} 受同一个能力开关约束：{@code
 * scds.auth.jwt.signing-enabled=false} 的实例只验签不签发，这个端点**不注册**（路由缺席），
 * 而不是注册后在 {@link DeviceAuthService#login} 里抛 500——后者把「本实例没有这个能力」
 * 报成了服务端缺陷，客户端也拿不到可据以换 baseUrl 的信号。{@code matchIfMissing=true}
 * 与 {@link AccountAuthController} 一致：配置缺省时按可签发处理。</p>
 *
 * <p><b>为什么 {@code device/me} 不在这里：</b>它只验签不签发（校验 SID 单端在线 + 读台账），
 * 与 {@code /api/v1/auth/me} 同理留在不条件注册的 {@link AuthController}。</p>
 *
 * <p>本类不做任何校验与令牌处理——那些是 {@link DeviceAuthService} 的职责；这里只负责
 * 「HTTP 请求 → 服务调用 → 契约 DTO」的翻译。</p>
 */
@RestController
@ConditionalOnProperty(prefix = "scds.auth.jwt", name = "signing-enabled",
        havingValue = "true", matchIfMissing = true)
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
