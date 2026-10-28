import '../shared/api/api_client.dart';
import '../shared/config/connection_config.dart';

/// 由连接配置造 HTTP 客户端。独立成函数，便于测试注入指向桩服务的实现。
///
/// 放在 `app/` 下（而非某个外壳里）是因为**两个平台入口都要用它**：
/// 单兵端（`device_app.dart`）与指挥端（`console_app.dart`）。
ApiClient httpApiClient(ConnectionConfig config) => ApiClient(baseUri: config.baseUri);
