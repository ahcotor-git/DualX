import 'dart:convert';
import 'dart:io';

import 'connection_config.dart';
import 'connection_config_store.dart';

/// 连接配置落盘实现（段 71）：以 JSON 文件写在**应用支持目录**下。
///
/// 连接配置**不是机密**（现场主机地址 + 硬约束端口），故落普通文件；
/// 令牌一类的机密另走[平台安全存储]（`shared/auth/secure_session_store.dart`）。
///
/// [directoryProvider] 由调用方注入（生产用 `path_provider` 的
/// `getApplicationSupportDirectory`；测试注入临时目录），本类**不直接依赖插件**，
/// 便于单测在真实文件系统上跑通读写。
///
/// 读侧对**不可解析 / 字段非法**的存量文件降级为 `null`（调用方回落到
/// `ConnectionConfig.defaults`）：一份坏配置不得砖住启动，现场可重新填一次。
class DiskConnectionConfigStore implements ConnectionConfigStore {
  DiskConnectionConfigStore({
    required Future<Directory> Function() directoryProvider,
    String fileName = 'connection_config.json',
  })  : _directoryProvider = directoryProvider, // ignore: prefer_initializing_formals
        _fileName = fileName; // ignore: prefer_initializing_formals

  final Future<Directory> Function() _directoryProvider;
  final String _fileName;

  @override
  Future<ConnectionConfig?> read() async {
    final File file = await _file();
    if (!await file.exists()) {
      return null;
    }
    try {
      final Object? decoded = jsonDecode(await file.readAsString());
      if (decoded is! Map<String, dynamic>) {
        throw FormatException('连接配置期望 JSON 对象，实际为 ${decoded.runtimeType}');
      }
      return ConnectionConfig(
        host: decoded['host'] as String,
        port: (decoded['port'] as num).toInt(),
      );
    } on FormatException {
      return null;
    } on TypeError {
      return null;
    } on ArgumentError {
      return null;
    }
  }

  @override
  Future<void> write(ConnectionConfig config) async {
    final File file = await _file();
    await file.parent.create(recursive: true);
    await file.writeAsString(jsonEncode(<String, Object?>{
      'host': config.host,
      'port': config.port,
    }));
  }

  @override
  Future<void> clear() async {
    final File file = await _file();
    if (await file.exists()) {
      await file.delete();
    }
  }

  Future<File> _file() async {
    final Directory dir = await _directoryProvider();
    return File('${dir.path}${Platform.pathSeparator}$_fileName');
  }
}
