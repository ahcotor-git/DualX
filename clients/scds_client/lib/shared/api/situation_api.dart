import 'api_client.dart';
import 'situation_models.dart';

/// situation 域 HTTP 客户端（只读四端点：态势 / 单元 / 队伍汇总 / 命中流水）。
///
/// 只做「拼路径 + 解析契约模型」，非 2xx 统一由 [ApiClient] 抛 `ApiException`；
/// 本类**不含 WebSocket、不做定时轮询、不缓存 revision**——
/// 双通道调度与本地缓存聚合属调用方（客户端状态机）职责。
///
/// 令牌两态：指挥端用账号 `accessToken`，单兵端用设备 `deviceToken`，
/// 两者都按 `Bearer` 提交（契约只有 `bearerAuth` 一种方案）。
class SituationApi {
  SituationApi(this._client);

  final ApiClient _client;

  /// 拉取态势：`GET /api/v1/situation`。
  ///
  /// · 不带 [sinceRevision] → 全量快照（`full=true`，取 [SituationEnvelope.units]）；
  /// · 带 [sinceRevision] → 增量（取 [SituationEnvelope.changes] / [SituationEnvelope.removedVestNums]）；
  ///   若该值已超出服务端保留窗口，抛 `SITUATION_REVISION_TOO_OLD`（409），
  ///   调用方应**丢弃本地缓存改不带 [sinceRevision] 重拉全量**。
  ///
  /// [includeSnapshotOnFirst] 默认 true（无演习进行中也回一帧空快照，避免拿不到 revision）；
  /// [hitsLimit] 取值 0..500，默认 200。
  Future<SituationEnvelope> getSituation({
    required String token,
    int? sinceRevision,
    bool? includeSnapshotOnFirst,
    int? hitsLimit,
  }) async {
    final ApiResponse response = await _client.get(
      '/situation',
      query: _query(<String, Object?>{
        'sinceRevision': sinceRevision,
        'includeSnapshotOnFirst': includeSnapshotOnFirst,
        'hitsLimit': hitsLimit,
      }),
      bearerToken: token,
    );
    return SituationEnvelope.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 单个马甲实时状态：`GET /api/v1/situation/vests/{vestNum}`。
  ///
  /// 单兵终端（设备令牌）调用时服务端按授权范围裁剪可见字段（自身即 [vestNum]）。
  /// 编号区间 1..65532（危险物不下发单元）。
  Future<UnitState> getVestState({required int vestNum, required String token}) async {
    final ApiResponse response = await _client.get(
      '/situation/vests/$vestNum',
      bearerToken: token,
    );
    return UnitState.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 各队伍存活/阵亡汇总：`GET /api/v1/situation/teams/summary`（直接回数组）。
  Future<List<TeamSummary>> getTeamSummary({required String token}) async {
    final ApiResponse response = await _client.get('/situation/teams/summary', bearerToken: token);
    return (response.json! as List<dynamic>)
        .map((dynamic item) => TeamSummary.fromJson(item as Map<String, dynamic>))
        .toList(growable: false);
  }

  /// 命中流水增量：`GET /api/v1/situation/hits`。
  ///
  /// 与态势增量里的 `hits` 同源，供「命中记录」面板单独补拉；[limit] 取值 1..500，默认 200。
  Future<HitEventPage> listHits({required String token, int? sinceSeq, int? limit}) async {
    final ApiResponse response = await _client.get(
      '/situation/hits',
      query: _query(<String, Object?>{'sinceSeq': sinceSeq, 'limit': limit}),
      bearerToken: token,
    );
    return HitEventPage.fromJson(response.json! as Map<String, dynamic>);
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
