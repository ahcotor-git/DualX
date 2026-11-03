import 'api_client.dart';
import 'replay_models.dart';

/// replay 域 HTTP 客户端——复盘列表 / 概览、轨迹分片、统计与异步导出（**7 个端点**）。
///
/// 只做「拼路径 + 序列化/解析契约模型」，非 2xx 统一由 [ApiTransport] 抛 `ApiException`。
/// **不判业务**：复盘不存在（404 `REPLAY_NOT_FOUND`）、导出任务未完成或产物已过期
/// （409 `REPLAY_EXPORT_FAILED`）等判定全在服务端，本层只把错误透上去。
///
/// 令牌：本域属指挥端，使用账号 `accessToken`（契约只有 `bearerAuth` 一种方案）。
///
/// 时间轴口径：`TrackSample.t` 是**相对开演的毫秒偏移**；分片按 `nextCursor` 连续拉取，
/// `null` 即末尾——客户端应边拉边播，不要一次性拉全量。
///
/// 注：[AsyncTask.downloadUrl] 是**不带 Bearer** 的短时效签名 URL，客户端直接把它拼上 base URL
/// 交给浏览器 / 下载器即可；本层不代劳，也不缓存跨会话。走 Bearer 的等价路径见 [downloadExport]。
class ReplayApi {
  ReplayApi(this._client);

  final ApiTransport _client;

  // ── 复盘（replays）────────────────────────────────────────────────────────

  /// 复盘列表：`GET /api/v1/replays`——按结束时间倒序。
  ///
  /// [keyword] 按想定名模糊匹配；[from] / [to] 为结束时间区间（含端点）。
  /// 分页 1..200，服务端默认 `page=1` / `size=20`。
  Future<ReplayPage> listReplays({
    required String token,
    String? keyword,
    DateTime? from,
    DateTime? to,
    int? page,
    int? size,
  }) async {
    final ApiResponse response = await _client.get(
      '/replays',
      query: _query(<String, Object?>{
        'keyword': keyword,
        'from': _instant(from),
        'to': _instant(to),
        'page': page,
        'size': size,
      }),
      bearerToken: token,
    );
    return ReplayPage.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 复盘概览：`GET /api/v1/replays/{replayId}`（不存在 → 404 `REPLAY_NOT_FOUND`）。
  ///
  /// 只回场次元信息，**不含轨迹**；轨迹走 [getReplayTimeline]。
  Future<Replay> getReplay({required String token, required String replayId}) async {
    final ApiResponse response = await _client.get('/replays/$replayId', bearerToken: token);
    return Replay.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 轨迹分片拉取：`GET /api/v1/replays/{replayId}/timeline`。
  ///
  /// [cursor] 传上一片返回的 `nextCursor`（首次不传）；[limit] 为单片采样点上限（1..20000，
  /// 服务端默认 5000）；[vestNums] 可只取关注的人员（不传即全体，多值以逗号分隔）；
  /// [stride] 允许服务端按步长抽稀（1..60，1 表示不抽稀），用于低性能终端的全局预览。
  ///
  /// 三个参数都**不传即走服务端默认**——本层不臆造默认值。
  Future<TimelineChunk> getReplayTimeline({
    required String token,
    required String replayId,
    String? cursor,
    int? limit,
    List<int>? vestNums,
    int? stride,
  }) async {
    final ApiResponse response = await _client.get(
      '/replays/$replayId/timeline',
      query: _query(<String, Object?>{
        'cursor': cursor,
        'limit': limit,
        'vestNums': vestNums?.join(','),
        'stride': stride,
      }),
      bearerToken: token,
    );
    return TimelineChunk.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 复盘统计：`GET /api/v1/replays/{replayId}/statistics`。
  ///
  /// [timelineBuckets] 为存活曲线采样段数（10..500，服务端默认 120）。
  Future<ReplayStatistics> getReplayStatistics({
    required String token,
    required String replayId,
    int? timelineBuckets,
  }) async {
    final ApiResponse response = await _client.get(
      '/replays/$replayId/statistics',
      query: _query(<String, Object?>{'timelineBuckets': timelineBuckets}),
      bearerToken: token,
    );
    return ReplayStatistics.fromJson(response.json! as Map<String, dynamic>);
  }

  // ── 异步导出（exports）────────────────────────────────────────────────────

  /// 发起复盘导出：`POST /api/v1/replays/{replayId}/exports`——**异步**，固定回 **202**。
  ///
  /// 202 只表示已受理，进度用 [getExportTask] 轮询。同一复盘同一格式的并发导出会被**合并**
  /// （重复调用返回同一个 `taskId`），故不会出现 200 / 202 两种成功码。
  Future<AsyncTask> exportReplay({
    required String token,
    required String replayId,
    required ExportRequest request,
  }) async {
    final ApiResponse response = await _client.post(
      '/replays/$replayId/exports',
      jsonBody: request.toJson(),
      bearerToken: token,
    );
    return AsyncTask.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 查询导出任务进度：`GET /api/v1/replays/{replayId}/exports/{taskId}`。
  Future<AsyncTask> getExportTask({
    required String token,
    required String replayId,
    required String taskId,
  }) async {
    final ApiResponse response =
        await _client.get('/replays/$replayId/exports/$taskId', bearerToken: token);
    return AsyncTask.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 下载导出文件：`GET /api/v1/replays/{replayId}/exports/{taskId}/download`。
  ///
  /// **按产物格式返回二进制**（XLSX / CSV 各自对应 [ApiResponse.contentType]），不统一转码，
  /// 并带 `Content-Disposition: attachment`。仅 `state=SUCCEEDED` 且未过期时可下载：
  /// 任务未完成或产物已过期 → 409 `REPLAY_EXPORT_FAILED`（需重新发起导出）。
  ///
  /// 该路径要求 Bearer；保持原始 [ApiResponse]（字节 + contentType），由调用方决定落盘，
  /// 本层不解码。供浏览器免鉴权直取的签名 URL 见 [AsyncTask.downloadUrl]。
  Future<ApiResponse> downloadExport({
    required String token,
    required String replayId,
    required String taskId,
  }) =>
      _client.get('/replays/$replayId/exports/$taskId/download', bearerToken: token);
}

/// `date-time` 查询参数：统一转 UTC 后以 ISO-8601 送出（服务端按 `Instant` 解析）。
String? _instant(DateTime? value) => value?.toUtc().toIso8601String();

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
