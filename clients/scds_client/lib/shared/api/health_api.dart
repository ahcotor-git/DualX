import 'api_client.dart';
import 'api_problem.dart';

/// 健康诊断端点：`GET /actuator/health`（**无令牌**，安全链白名单，见 `testing.md` RT-HEALTH）。
///
/// 不是业务路由（不带 `/api/v1`），故走 [ApiTransport.probe] 而非业务 `get`。
const String kHealthPath = '/actuator/health';

/// 一次健康诊断的结果。
///
/// [status] 是**观测到的字面量**，不是推断值：
/// · `UP` / `DOWN` / `OUT_OF_SERVICE` —— 健康端点如实回的值（Spring Actuator `status`）；
/// · `HTTP_<code>` —— 实例**可达**但健康端点没回 2xx（无 Problem 正文，只知状态码）；
/// · `UNREACHABLE` —— 连 HTTP 响应都没拿到（拒绝连接 / 超时 / DNS）；
/// · `UNKNOWN` —— 拿到了 2xx，但响应体不含字符串 `status`。
class HealthReport {
  const HealthReport({
    required this.status,
    required this.detail,
    this.httpStatus,
  });

  final String status;

  /// 现场可读的一句话（诊断结论或失败原因）。
  final String detail;

  /// HTTP 状态码；[status] 为 `UNREACHABLE` 时为 `null`（根本没到 HTTP 层）。
  final int? httpStatus;

  /// 实例健康（唯一判据是健康端点自己回的 `UP`）。
  bool get isUp => status == 'UP';

  /// 实例在 HTTP 层是否可达（拿到过响应，哪怕状态码不是 2xx）。
  bool get reachable => httpStatus != null;

  @override
  String toString() => 'HealthReport($status, http=$httpStatus)';
}

/// 实例健康诊断端口（指挥端「连接配置」页的诊断按钮）。
///
/// **只读、无令牌、不改任何状态**；与 contract 的 `error.md` 无关——
/// 探活失败本身就是诊断结果，因此 [probe] **永不抛异常**。
class InstanceHealthApi {
  InstanceHealthApi(this._client);

  final ApiTransport _client;

  /// 探活一次；任何失败都收敛成 [HealthReport]，由 UI 呈现。
  Future<HealthReport> probe() async {
    try {
      final ApiResponse response = await _client.probe(kHealthPath);
      final Object? json = response.json;
      if (json is Map<String, dynamic> && json['status'] is String) {
        final String status = json['status'] as String;
        return HealthReport(
          status: status,
          httpStatus: response.statusCode,
          detail: status == 'UP'
              ? '实例就绪（HTTP ${response.statusCode}）'
              : '实例已响应，但状态为 $status（HTTP ${response.statusCode}）',
        );
      }
      return HealthReport(
        status: 'UNKNOWN',
        httpStatus: response.statusCode,
        detail: '实例已响应，但响应体不含 status 字段（HTTP ${response.statusCode}）',
      );
    } on ApiException catch (failure) {
      // 非 2xx：**可达**（拿到了 HTTP 响应），但健康端点没回 2xx。
      return HealthReport(
        status: 'HTTP_${failure.statusCode}',
        httpStatus: failure.statusCode,
        detail: failure.statusCode == 503
            ? '实例可达但未就绪（HTTP 503）——数据库/依赖可能尚未就绪'
            : '实例可达但健康端点返回 ${failure.message}',
      );
    } catch (error) {
      // 连接被拒 / 超时等：UI 边界必须给出可读结论，不能让异常冒泡成红屏。
      return HealthReport(status: 'UNREACHABLE', detail: '无法连接实例：$error');
    }
  }
}
