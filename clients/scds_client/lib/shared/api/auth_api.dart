import 'api_client.dart';
import 'auth_models.dart';

/// auth 域 HTTP 客户端（账号登录链路 + 单兵设备登录）。
///
/// 只做「拼路径 + 解析契约模型」，错误统一由 [ApiClient] 抛出的 `ApiException` 表达
/// （非 2xx 已解析 Problem）；本类**不吞错、不重试、不缓存令牌**——
/// 令牌由调用方交给会话存储（见 `shared/auth/session_store.dart`）。
class AuthApi {
  AuthApi(this._client);

  final ApiClient _client;

  /// 账号登录：`POST /api/v1/auth/login`。
  ///
  /// 失败统一 401 `AUTH_BAD_CREDENTIALS`（**不区分**账号不存在与密码错误），
  /// 触发限流为 429。
  Future<TokenPair> login({required String username, required String password}) async {
    final ApiResponse response = await _client.post(
      '/auth/login',
      jsonBody: <String, dynamic>{'username': username, 'password': password},
    );
    return TokenPair.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 刷新令牌：`POST /api/v1/auth/refresh`（滚动刷新，回新的 access/refresh 对）。
  ///
  /// 到期 401 `AUTH_TOKEN_EXPIRED`；未知、篡改或已撤销 401 `AUTH_TOKEN_INVALID`
  /// （已撤销令牌重放会撤销该账号全部 refreshToken）。
  Future<TokenPair> refresh(String refreshToken) async {
    final ApiResponse response = await _client.post(
      '/auth/refresh',
      jsonBody: <String, dynamic>{'refreshToken': refreshToken},
    );
    return TokenPair.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 退出登录：`POST /api/v1/auth/logout`（**无请求体**，204）。
  ///
  /// 按令牌 `sub` 撤销该账号全部 refreshToken（全端退出）；accessToken 无状态不可撤销，
  /// 由短有效期兜底。
  Future<void> logout({required String accessToken}) async {
    await _client.post('/auth/logout', bearerToken: accessToken);
  }

  /// 当前用户：`GET /api/v1/auth/me`。身份源与登录一致的本地账号库。
  Future<AuthUser> me({required String accessToken}) async {
    final ApiResponse response = await _client.get('/auth/me', bearerToken: accessToken);
    return AuthUser.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 单兵设备登录：`POST /api/v1/auth/device/login`（马甲编号 + 绑定密钥）。
  ///
  /// 未绑定或密钥错误为 401 `AUTH_DEVICE_NOT_BOUND`；重复登录使旧令牌失效（单端在线）。
  Future<DeviceTokenPair> deviceLogin({required int vestNum, required String secret}) async {
    final ApiResponse response = await _client.post(
      '/auth/device/login',
      jsonBody: <String, dynamic>{'vestNum': vestNum, 'secret': secret},
    );
    return DeviceTokenPair.fromJson(response.json! as Map<String, dynamic>);
  }
}
