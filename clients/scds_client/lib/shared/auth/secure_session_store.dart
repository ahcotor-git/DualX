import 'dart:convert';

import '../api/auth_models.dart';
import '../io/secure_kv_store.dart';
import 'session_store.dart';

/// 会话安全存储实现（段 71）：两类会话各以**一段 JSON** 存进[平台安全存储]。
///
/// 与 `InMemorySessionStore` 语义一致的两点：账号 / 设备**分开存**（互不覆盖）、
/// `clearAll` 一次清空；差别只在**落盘并跨进程重启保留**（现场免登）。
///
/// 读侧对**不可解析的旧缓存 / 损坏条目**降级为 `null`（视为未登录）：令牌是
/// 可重新登录取得的会话数据、不是权威记录，故宁可要求重登，也不让一份坏缓存
/// 砖住启动。**令牌只落到安全存储，不写日志**。
class SecureSessionStore implements SessionStore {
  SecureSessionStore(this._kv);

  final SecureKvStore _kv;

  /// 存储键：带 `scds.` 前缀，避免与同机其它应用键冲突。
  static const String accountKey = 'scds.session.account';
  static const String deviceKey = 'scds.session.device';

  @override
  Future<AccountSession?> readAccount() async =>
      _decodeAccount(await _kv.read(accountKey));

  @override
  Future<void> writeAccount(AccountSession session) =>
      _kv.write(accountKey, jsonEncode(_accountToJson(session)));

  @override
  Future<DeviceSession?> readDevice() async => _decodeDevice(await _kv.read(deviceKey));

  @override
  Future<void> writeDevice(DeviceSession session) =>
      _kv.write(deviceKey, jsonEncode(_deviceToJson(session)));

  @override
  Future<void> clearAll() async {
    await _kv.delete(accountKey);
    await _kv.delete(deviceKey);
  }
}

/// 读侧统一兜底：未存 ⇒ `null`；存了但形状不符 / 类型不符 / 时间串不可解析 ⇒ `null`。
AccountSession? _decodeAccount(String? raw) {
  if (raw == null) {
    return null;
  }
  try {
    return _accountFromJson(jsonDecode(raw));
  } on FormatException {
    return null;
  } on TypeError {
    return null;
  }
}

DeviceSession? _decodeDevice(String? raw) {
  if (raw == null) {
    return null;
  }
  try {
    return _deviceFromJson(jsonDecode(raw));
  } on FormatException {
    return null;
  } on TypeError {
    return null;
  }
}

Map<String, Object?> _accountToJson(AccountSession session) => <String, Object?>{
      'accessToken': session.accessToken,
      'refreshToken': session.refreshToken,
      'expiresAt': session.expiresAt.toUtc().toIso8601String(),
      'user': session.user == null ? null : _userToJson(session.user!),
    };

AccountSession _accountFromJson(Object? decoded) {
  final Map<String, dynamic> json = _asMap(decoded, '账号会话');
  final Object? user = json['user'];
  return AccountSession(
    accessToken: json['accessToken'] as String,
    refreshToken: json['refreshToken'] as String?,
    expiresAt: DateTime.parse(json['expiresAt'] as String),
    user: user == null ? null : _userFromJson(user),
  );
}

Map<String, Object?> _userToJson(AuthUser user) => <String, Object?>{
      'userId': user.userId,
      'username': user.username,
      'roles': user.roles,
      'displayName': user.displayName,
      'permissions': user.permissions,
      'lastLoginAt': user.lastLoginAt?.toUtc().toIso8601String(),
    };

AuthUser _userFromJson(Object? decoded) {
  final Map<String, dynamic> json = _asMap(decoded, '账号身份');
  final Object? permissions = json['permissions'];
  return AuthUser(
    userId: json['userId'] as String,
    username: json['username'] as String,
    roles: _stringList(json['roles'], 'roles'),
    displayName: json['displayName'] as String?,
    permissions: permissions == null
        ? const <String>[]
        : _stringList(permissions, 'permissions'),
    lastLoginAt:
        json['lastLoginAt'] == null ? null : DateTime.parse(json['lastLoginAt'] as String),
  );
}

Map<String, Object?> _deviceToJson(DeviceSession session) => <String, Object?>{
      'vestNum': session.vestNum,
      'deviceToken': session.deviceToken,
      'expiresAt': session.expiresAt.toUtc().toIso8601String(),
    };

DeviceSession _deviceFromJson(Object? decoded) {
  final Map<String, dynamic> json = _asMap(decoded, '设备会话');
  return DeviceSession(
    vestNum: (json['vestNum'] as num).toInt(),
    deviceToken: json['deviceToken'] as String,
    expiresAt: DateTime.parse(json['expiresAt'] as String),
  );
}

Map<String, dynamic> _asMap(Object? decoded, String what) {
  if (decoded is! Map<String, dynamic>) {
    throw FormatException('$what 期望 JSON 对象，实际为 ${decoded.runtimeType}');
  }
  return decoded;
}

/// 与 `auth_models.dart` 同口径：形状不符即抛 [FormatException]，不静默丢弃。
List<String> _stringList(Object? value, String field) {
  if (value is! List) {
    throw FormatException('$field 期望字符串数组，实际为 ${value.runtimeType}');
  }
  return List<String>.unmodifiable(value.cast<String>());
}
