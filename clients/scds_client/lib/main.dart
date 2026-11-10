import 'dart:io';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';

import 'app/console_app.dart';
import 'app/device_app.dart';
import 'shared/auth/secure_session_store.dart';
import 'shared/auth/session_store.dart';
import 'shared/config/connection_config_store.dart';
import 'shared/config/disk_connection_config_store.dart';
import 'shared/io/secure_kv_store.dart';

/// 进程入口：按平台选一个外壳装配（D12「Android 单兵入口 / Windows 指挥入口」）。
///
/// 两端共用同一份 Dart 契约/通信包（`lib/shared/**`）与同一份 HTTP 客户端工厂
/// （`app/client_factory.dart`），差别只在装配链路与页面。
///
/// `dart:io` 的 [Platform] 在 Android / Windows 上都可用；本工程只建这两个平台。
///
/// 段 71：两处存储接**生产实现**——连接配置落盘（应用支持目录下的 JSON 文件），
/// 账号 / 设备令牌落**平台安全存储**（Android Keystore / Windows 凭据管理器），
/// 故重启后免登（登出 / 清除连接配置时一并清除）。
void main() {
  final ConnectionConfigStore configStore =
      DiskConnectionConfigStore(directoryProvider: getApplicationSupportDirectory);
  final SessionStore sessionStore = SecureSessionStore(const PlatformSecureKvStore());
  runApp(
    Platform.isWindows
        ? ScdsConsoleApp(configStore: configStore, sessionStore: sessionStore)
        : ScdsClientApp(configStore: configStore, sessionStore: sessionStore),
  );
}
