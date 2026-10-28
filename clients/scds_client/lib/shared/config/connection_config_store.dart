import 'connection_config.dart';

/// 连接配置存储端口：端口与实现分离，便于后续换平台持久化（文件 / SharedPreferences），
/// 调用方（登录页、连接设置页）只依赖本接口。
abstract interface class ConnectionConfigStore {
  /// 读取已保存的连接配置；从未保存过返回 `null`（调用方回落到 [ConnectionConfig.defaults]）。
  Future<ConnectionConfig?> read();

  /// 保存并在下次 [read] 时读回（同一次运行内）。
  Future<void> write(ConnectionConfig config);

  /// 清除已保存的配置（切换实例 / 恢复默认时调用）。
  Future<void> clear();
}

/// 进程内实现：重启即丢、**不落盘**。
///
/// 平台持久化落地前，本实现只供业务接线与测试使用——重启后回到 [ConnectionConfig.defaults]。
class InMemoryConnectionConfigStore implements ConnectionConfigStore {
  ConnectionConfig? _config;

  @override
  Future<ConnectionConfig?> read() async => _config;

  @override
  Future<void> write(ConnectionConfig config) async {
    _config = config;
  }

  @override
  Future<void> clear() async {
    _config = null;
  }
}
