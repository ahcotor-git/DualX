import 'api_client.dart';
import 'exercise_models.dart';

/// exercise 域 HTTP 客户端（只读两端点：当前演习状态 / 当前演习日志流水）。
///
/// 只做「拼路径 + 解析契约模型」，非 2xx 统一由 [ApiTransport] 抛 `ApiException`；
/// 本类**不发指令**——判死/判活/判伤/越界/充弹/改配置等写操作在 command 域另行落地。
///
/// 令牌：本域属指挥端，使用账号 `accessToken`（契约只有 `bearerAuth` 一种方案）。
class ExerciseApi {
  ExerciseApi(this._client);

  final ApiTransport _client;

  /// 当前（或最近一场）演习状态：`GET /api/v1/exercise/current`。
  ///
  /// **无演习进行中时回 200 且 [Exercise.running]=false（不是 404）**，其余业务字段一律缺席；
  /// 调用方必须先判 [Exercise.running] 再解引用场次信息。
  Future<Exercise> getCurrentExercise({required String token}) async {
    final ApiResponse response = await _client.get('/exercise/current', bearerToken: token);
    return Exercise.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 当前演习日志流水：`GET /api/v1/exercise/current/logs`。
  ///
  /// **游标语义**：[sinceSeq] 为「只要 seq 大于它的条目」，默认从 0 起拉全量；
  /// 响应 [ExerciseLogPage.latestSeq] 即本批最大 seq，原样作为下次 [sinceSeq] 续拉即可，
  /// 既去重又续拉。[limit] 取值 1..500（服务端默认 200）。
  ///
  /// 无演习进行中时同样回 200 + 空 items（不是 404），[ExerciseLogPage.latestSeq] 为 `null`。
  Future<ExerciseLogPage> listCurrentExerciseLogs({
    required String token,
    int? sinceSeq,
    int? limit,
  }) async {
    final ApiResponse response = await _client.get(
      '/exercise/current/logs',
      query: _query(<String, Object?>{'sinceSeq': sinceSeq, 'limit': limit}),
      bearerToken: token,
    );
    return ExerciseLogPage.fromJson(response.json! as Map<String, dynamic>);
  }
}

/// 只把**非 null** 参数拼进查询串；全为 null 时不带查询串（走服务端默认值）。
Map<String, String>? _query(Map<String, Object?> raw) {
  final Map<String, String> query = <String, String>{};
  raw.forEach((String key, Object? value) {
    if (value != null) {
      query[key] = '$value';
    }
  });
  return query.isEmpty ? null : query;
}
