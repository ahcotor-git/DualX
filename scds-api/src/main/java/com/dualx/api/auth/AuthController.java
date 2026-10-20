package com.dualx.api.auth;

import com.dualx.api.dto.auth.DeviceLoginRequest;
import com.dualx.api.dto.auth.DeviceSelf;
import com.dualx.api.dto.auth.DeviceTokenPair;
import com.dualx.api.dto.auth.LoginRequest;
import com.dualx.api.dto.auth.RefreshRequest;
import com.dualx.api.dto.auth.TokenPair;
import com.dualx.api.dto.auth.User;
import com.dualx.api.error.NotImplementedException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号与设备认证接口。
 *
 * <p>覆盖现场两类登录：指挥台账号（账号密码换 JWT）与单兵终端马甲 App（马甲编号 + 绑定密钥换设备令牌），
 * 以及各自的「取自身信息」查询。骨架期仅登记路由、参数与 DTO，业务实现留待后续阶段。</p>
 */
@RestController
public class AuthController {

    /** 账号登录：发放 access/refresh 令牌。 */
    @PostMapping("/api/v1/auth/login")
    public TokenPair login(@RequestBody @Valid LoginRequest request) {
        throw new NotImplementedException("login");
    }

    /** 用 refreshToken 滚动换取新的 accessToken。 */
    @PostMapping("/api/v1/auth/refresh")
    public TokenPair refreshToken(@RequestBody @Valid RefreshRequest request) {
        throw new NotImplementedException("refreshToken");
    }

    /** 退出登录：使当前 refreshToken 失效。 */
    @PostMapping("/api/v1/auth/logout")
    public void logout() {
        throw new NotImplementedException("logout");
    }

    /** 获取当前登录用户。 */
    @GetMapping("/api/v1/auth/me")
    public User getCurrentUser() {
        throw new NotImplementedException("getCurrentUser");
    }

    /** 单兵终端登录：马甲编号 + 绑定密钥换取设备令牌。 */
    @PostMapping("/api/v1/auth/device/login")
    public DeviceTokenPair deviceLogin(@RequestBody @Valid DeviceLoginRequest request) {
        throw new NotImplementedException("deviceLogin");
    }

    /** 单兵终端获取自身信息与实时状态。 */
    @GetMapping("/api/v1/auth/device/me")
    public DeviceSelf getDeviceSelf() {
        throw new NotImplementedException("getDeviceSelf");
    }
}
