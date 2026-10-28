import 'api_client.dart';
import 'exercise_models.dart';

/// exercise 域 HTTP 客户端——想定/编成/当前演习的状态与生命周期。
///
/// 只做「拼路径 + 序列化/解析契约模型」，非 2xx 统一由 [ApiTransport] 抛 `ApiException`。
/// **不判业务**：开演的前置条件（编成非空、马甲串口链路 `OPEN`）由服务端判定并按
/// 400 `EXERCISE_ROSTER_EMPTY` / 409 `DEVICE_LINK_NOT_OPEN` 回绝，本层只把错误透上去。
///
/// 本域写全部是「想定与场次」层面的动作；指挥指令（判死/活/伤/越界/充弹/配置）在 command 域。
///
/// 令牌：本域属指挥端，使用账号 `accessToken`（契约只有 `bearerAuth` 一种方案）。
class ExerciseApi {
  ExerciseApi(this._client);

  final ApiTransport _client;

  // ── 只读：当前演习 ─────────────────────────────────────────────────────────

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

  // ── 想定（plan）───────────────────────────────────────────────────────────

  /// 想定列表：`GET /api/v1/plans`（[keyword] 按名称模糊搜索；分页 1..200）。
  Future<PlanPage> listPlans({
    required String token,
    String? keyword,
    int? page,
    int? size,
  }) async {
    final ApiResponse response = await _client.get(
      '/plans',
      query: _query(<String, Object?>{'keyword': keyword, 'page': page, 'size': size}),
      bearerToken: token,
    );
    return PlanPage.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 新建想定：`POST /api/v1/plans`（201）。
  Future<Plan> createPlan({
    required String token,
    required PlanUpsertRequest request,
  }) async {
    final ApiResponse response =
        await _client.post('/plans', jsonBody: request.toJson(), bearerToken: token);
    return Plan.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 想定详情：`GET /api/v1/plans/{planId}`（不存在 → 404 `PLAN_NOT_FOUND`）。
  Future<Plan> getPlan({required String token, required String planId}) async {
    final ApiResponse response = await _client.get('/plans/$planId', bearerToken: token);
    return Plan.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 修改想定：`PUT /api/v1/plans/{planId}`——**整体替换**，缺省字段即置空
  /// （见 [PlanUpsertRequest] 的 toJson 口径）。想定正在演习中 → 409 `PLAN_IN_USE`。
  Future<Plan> updatePlan({
    required String token,
    required String planId,
    required PlanUpsertRequest request,
  }) async {
    final ApiResponse response = await _client.put(
      '/plans/$planId',
      jsonBody: request.toJson(),
      bearerToken: token,
    );
    return Plan.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 删除想定：`DELETE /api/v1/plans/{planId}`（204，无正文）。
  ///
  /// 被引用 / 正在演习中 → 409 `PLAN_IN_USE`（不做级联删除）。
  Future<void> deletePlan({required String token, required String planId}) async {
    await _client.delete('/plans/$planId', bearerToken: token);
  }

  // ── 编成（roster）─────────────────────────────────────────────────────────

  /// 获取想定编成：`GET /api/v1/plans/{planId}/roster`。
  Future<Roster> getRoster({required String token, required String planId}) async {
    final ApiResponse response = await _client.get('/plans/$planId/roster', bearerToken: token);
    return Roster.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 整体替换想定编成：`PUT /api/v1/plans/{planId}/roster`（幂等全量替换，空列表即清空）。
  ///
  /// 请求体用服务端回的那份 [Roster]（含 `planId`）；校验失败回 400 `VALIDATION_FAILED`
  /// 且**不产生部分写入**。想定正在演习中 → 409 `PLAN_IN_USE`。
  Future<Roster> replaceRoster({
    required String token,
    required String planId,
    required Roster roster,
  }) async {
    final ApiResponse response = await _client.put(
      '/plans/$planId/roster',
      jsonBody: roster.toJson(),
      bearerToken: token,
    );
    return Roster.fromJson(response.json! as Map<String, dynamic>);
  }

  // ── 想定模板与导入（multipart / 二进制）────────────────────────────────────

  /// 下载想定导入模板：`GET /api/v1/plans/template?format=`——回 XLSX / CSV **二进制**。
  ///
  /// 保持原始 [ApiResponse]（字节 + [ApiResponse.contentType]），由调用方决定落盘或解析，
  /// 本层不解码。
  Future<ApiResponse> downloadPlanTemplate({
    required String token,
    PlanTemplateFormat format = PlanTemplateFormat.xlsx,
  }) =>
      _client.get(
        '/plans/template',
        query: <String, String>{'format': format.wire},
        bearerToken: token,
      );

  /// 从模板文件导入想定：`POST /api/v1/plans/import`（`multipart/form-data`，201）。
  ///
  /// 服务端整文件校验通过才落库；[name] 不传则取文件名。
  Future<Plan> importPlan({
    required String token,
    required String filename,
    required List<int> bytes,
    String? name,
  }) async {
    final MultipartBody body = multipartFormData(
      fields: name == null ? const <String, String>{} : <String, String>{'name': name},
      files: <String, MultipartFile>{
        'file': MultipartFile(filename: filename, bytes: bytes),
      },
    );
    final ApiResponse response = await _client.sendBytes(
      'POST',
      '/plans/import',
      body: body.body,
      contentType: body.contentType,
      bearerToken: token,
    );
    return Plan.fromJson(response.json! as Map<String, dynamic>);
  }

  // ── 生命周期（开演 / 停演）────────────────────────────────────────────────

  /// 开始演习：`POST /api/v1/exercise/current`（201）。
  ///
  /// 前置条件全部在写库之前判定（「请求内容问题先报、运行环境前置后报」）：
  /// 503 `INSTANCE_NOT_READY` → 404 `PLAN_NOT_FOUND` → 400 `EXERCISE_ROSTER_EMPTY` →
  /// 409 `DEVICE_LINK_NOT_OPEN` → 409 `EXERCISE_ALREADY_RUNNING`。
  /// 被判据拒下的请求不留半启动状态。
  Future<Exercise> startExercise({
    required String token,
    required StartExerciseRequest request,
  }) async {
    final ApiResponse response = await _client.post(
      '/exercise/current',
      jsonBody: request.toJson(),
      bearerToken: token,
    );
    return Exercise.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 结束演习：`DELETE /api/v1/exercise/current`。
  ///
  /// 当前没有演习进行中 → 409 `EXERCISE_NOT_RUNNING`（停演**不设**链路门禁）。终局落库完成
  /// 即视为停演成功；结束帧下发失败只留痕、不影响结果。
  Future<StopExerciseResult> stopExercise({required String token}) async {
    final ApiResponse response = await _client.delete('/exercise/current', bearerToken: token);
    return StopExerciseResult.fromJson(response.json! as Map<String, dynamic>);
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
