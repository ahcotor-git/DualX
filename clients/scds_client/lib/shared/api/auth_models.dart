/// auth 域契约模型（Dart 手写，逐字对齐 `docs/api/schemas/domain.yaml`）。
///
/// 与 [ApiProblem] 同口径：**字段缺失不臆造缺省值**——必填字段缺失或形状不符即抛
/// [FormatException]，由调用方决定如何处置；可选字段缺失时为 `null`。
library;

/// 当前登录账号（`domain.yaml#/User`）。
///
/// 必填：`userId` / `username` / `roles`；`permissions` 缺失按空列表处理
/// （契约未列为必填，且它只用于前端显隐按钮，服务端仍二次校验）。
class AuthUser {
  const AuthUser({
    required this.userId,
    required this.username,
    required this.roles,
    this.displayName,
    this.permissions = const <String>[],
    this.lastLoginAt,
  });

  final String userId;
  final String username;

  /// 角色编码，取值 `ADMIN` / `DIRECTOR` / `OPERATOR` / `VIEWER`。
  final List<String> roles;
  final String? displayName;

  /// 细粒度权限点，前端据此显隐（不代替服务端校验）。
  final List<String> permissions;
  final DateTime? lastLoginAt;

  factory AuthUser.fromJson(Map<String, dynamic> json) => AuthUser(
        userId: json['userId'] as String,
        username: json['username'] as String,
        roles: _stringList(json['roles'], 'roles'),
        displayName: json['displayName'] as String?,
        permissions: json['permissions'] == null
            ? const <String>[]
            : _stringList(json['permissions'], 'permissions'),
        lastLoginAt:
            json['lastLoginAt'] == null ? null : DateTime.parse(json['lastLoginAt'] as String),
      );
}

/// 账号令牌对（`domain.yaml#/TokenPair`）。必填：`accessToken` / `expiresIn`。
///
/// `aud` 区分令牌用途（`scds-edge` / `scds-refresh` / `scds-device`），不是租户；
/// 载荷**刻意不含 tenantId**（每客户独立实例，客户端不得实现勿需的隔离逻辑）。
class TokenPair {
  const TokenPair({
    required this.accessToken,
    required this.expiresIn,
    this.refreshToken,
    this.user,
  });

  final String accessToken;

  /// accessToken 剩余有效秒数。
  final int expiresIn;

  /// 滚动刷新用；PRIVATE 形态由边缘实例自签自验。
  final String? refreshToken;
  final AuthUser? user;

  factory TokenPair.fromJson(Map<String, dynamic> json) => TokenPair(
        accessToken: json['accessToken'] as String,
        expiresIn: (json['expiresIn'] as num).toInt(),
        refreshToken: json['refreshToken'] as String?,
        user: json['user'] == null ? null : AuthUser.fromJson(json['user'] as Map<String, dynamic>),
      );
}

/// 单兵设备令牌对（`domain.yaml#/DeviceTokenPair`）。必填：`deviceToken` / `expiresIn`。
class DeviceTokenPair {
  const DeviceTokenPair({required this.deviceToken, required this.expiresIn});

  final String deviceToken;
  final int expiresIn;

  factory DeviceTokenPair.fromJson(Map<String, dynamic> json) => DeviceTokenPair(
        deviceToken: json['deviceToken'] as String,
        expiresIn: (json['expiresIn'] as num).toInt(),
      );
}

/// 把 JSON 值收成不可变字符串列表；形状不符时抛 [FormatException]（不静默丢弃）。
List<String> _stringList(Object? value, String field) {
  if (value is! List) {
    throw FormatException('$field 期望字符串数组，实际为 ${value.runtimeType}');
  }
  return List<String>.unmodifiable(value.cast<String>());
}
