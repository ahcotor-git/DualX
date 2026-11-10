import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// 平台安全存储窄端口（段 71）：只做「按 key 存 / 取 / 删**一段字符串**」。
///
/// 会话端口（`shared/auth/session_store.dart`）只依赖本端口，不直接引平台插件；
/// 生产实现是 [PlatformSecureKvStore]（`flutter_secure_storage`：Android 走
/// Keystore / EncryptedSharedPreferences，Windows 走凭据管理器（DPAPI））；
/// 测试注入假件或插件自带的测试平台，**不触碰真实平台通道**。
abstract interface class SecureKvStore {
  /// 读 [key] 对应的字符串；不存在回 `null`。
  Future<String?> read(String key);

  /// 写 [key]（已存在则覆盖）。
  Future<void> write(String key, String value);

  /// 删 [key]；不存在时无副作用。
  Future<void> delete(String key);
}

/// [SecureKvStore] 的 `flutter_secure_storage` 实现。
///
/// 用各平台**默认**加密选项（本工程只建 Android / Windows 两个平台）；
/// 调用方无需关心密钥从哪来——由插件在平台侧管理。
class PlatformSecureKvStore implements SecureKvStore {
  const PlatformSecureKvStore();

  static const FlutterSecureStorage _storage = FlutterSecureStorage();

  @override
  Future<String?> read(String key) => _storage.read(key: key);

  @override
  Future<void> write(String key, String value) => _storage.write(key: key, value: value);

  @override
  Future<void> delete(String key) => _storage.delete(key: key);
}
