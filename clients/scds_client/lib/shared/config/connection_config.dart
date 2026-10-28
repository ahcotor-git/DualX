import '../api/api_contract.dart';

/// 端口合法区间（TCP 端口全区间，契约端口 [kHttpPort] 落在其中）。
const int kMinPort = 1;
const int kMaxPort = 65535;

/// 边缘网关连接配置：现场主机的地址与端口。
///
/// 默认值必须与 [kDefaultHost] / [kHttpPort] 逐字一致（本机自测形态）；
/// **端口是硬约束**，页面上不提供改端口入口，仅在此保留校验以防误配。
/// 构造即校验：空白地址、越界端口一律抛 [ArgumentError]（这是用户输入边界）。
class ConnectionConfig {
  const ConnectionConfig._({required this.host, required this.port});

  /// 由用户输入的地址与端口构造；`host` 前后空白被裁剪后存储。
  factory ConnectionConfig({required String host, required int port}) {
    final String trimmed = host.trim();
    if (trimmed.isEmpty) {
      throw ArgumentError.value(host, 'host', '实例地址不能为空');
    }
    if (port < kMinPort || port > kMaxPort) {
      throw ArgumentError.value(port, 'port', '端口须在 $kMinPort..$kMaxPort 之间');
    }
    return ConnectionConfig._(host: trimmed, port: port);
  }

  /// 出厂默认：`http://127.0.0.1:11001`（与契约 `servers` 一致）。
  static const ConnectionConfig defaults =
      ConnectionConfig._(host: kDefaultHost, port: kHttpPort);

  final String host;
  final int port;

  /// 网关基地址，可直接交给 `ApiClient(baseUri: ...)`。
  Uri get baseUri => Uri(scheme: 'http', host: host, port: port);

  /// 展示用地址（连接页与错误提示统一用它，避免各处自行拼接）。
  String get display => '$host:$port';

  @override
  String toString() => 'ConnectionConfig($display)';
}
