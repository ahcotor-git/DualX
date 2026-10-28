import 'api_client.dart';
import 'command_models.dart';

/// command 域 HTTP 客户端（指挥指令下发，**8 个写操作**）：
/// 判死 / 判活 / 判伤 / 超范围 / 单兵充弹 / 全体充弹 / 覆盖配置 / 手动置位。
///
/// 契约口径：**全部为 POST/PUT**（不再像旧系统那样用 GET 触发），响应统一为 [CommandAck]。
/// **HTTP 200 只表示「已受理」**——[CommandAck.accepted] 为 `false` 表示未入队、未下发任何字节
/// （马甲离线 / 队列满），调用方应提示而非报错；非 2xx 由 [ApiTransport] 抛 `ApiException`。
///
/// 令牌：本域属指挥端，使用账号 `accessToken`（契约只有 `bearerAuth` 一种方案）。
class CommandApi {
  CommandApi(this._client);

  final ApiTransport _client;

  /// 判死：`POST /api/v1/command/vests/{vestNum}/kill`（8B 判死帧，阵亡后坐标冻结）。
  Future<CommandAck> killVest({required int vestNum, required String token}) =>
      _post('/command/vests/$vestNum/kill', token: token);

  /// 判活 / 复活：`POST /api/v1/command/vests/{vestNum}/revive`（解除冻结）。
  Future<CommandAck> reviveVest({required int vestNum, required String token}) =>
      _post('/command/vests/$vestNum/revive', token: token);

  /// 判伤：`POST /api/v1/command/vests/{vestNum}/injure`。
  Future<CommandAck> injureVest({required int vestNum, required String token}) =>
      _post('/command/vests/$vestNum/injure', token: token);

  /// 超范围预警：`POST /api/v1/command/vests/{vestNum}/out-of-range`。
  Future<CommandAck> outOfRangeVest({required int vestNum, required String token}) =>
      _post('/command/vests/$vestNum/out-of-range', token: token);

  /// 单兵充弹：`POST /api/v1/command/vests/{vestNum}/reload`（11B 单兵充弹帧，需请求体）。
  Future<CommandAck> reloadVest({
    required int vestNum,
    required ReloadRequest request,
    required String token,
  }) =>
      _post('/command/vests/$vestNum/reload', token: token, jsonBody: request.toJson());

  /// 全体充弹：`POST /api/v1/command/reload-all`——对当前编成内所有马甲下发 11B 充弹帧。
  ///
  /// 广播帧不指向具体马甲，回执 [CommandAck.vestNum] 固定为 `0`（见 [CommandAck.isBroadcast]）。
  Future<CommandAck> reloadAllVests({required ReloadRequest request, required String token}) =>
      _post('/command/reload-all', token: token, jsonBody: request.toJson());

  /// 覆盖配置：`PUT /api/v1/command/vests/{vestNum}/config`（队伍 / HP / 弹药 / 轮询模式）。
  ///
  /// 对应 14B 轮询配置帧，**下一次轮询时**生效，故是**覆盖配置**语义而非即时单次指令；
  /// [VestConfigRequest] 未传的字段保持原值。
  Future<CommandAck> setVestConfig({
    required int vestNum,
    required VestConfigRequest request,
    required String token,
  }) async {
    final ApiResponse response = await _client.put(
      '/command/vests/$vestNum/config',
      jsonBody: request.toJson(),
      bearerToken: token,
    );
    return CommandAck.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 手动置位（联调专用）：`POST /api/v1/command/vests/{vestNum}/move`。
  ///
  /// **正式演习中服务端拒绝**（403 `COMMAND_MOVE_FORBIDDEN`）——仅供联调与演示。
  Future<CommandAck> moveVest({
    required int vestNum,
    required MoveVestRequest request,
    required String token,
  }) =>
      _post('/command/vests/$vestNum/move', token: token, jsonBody: request.toJson());

  Future<CommandAck> _post(String path, {required String token, Object? jsonBody}) async {
    final ApiResponse response = await _client.post(path, jsonBody: jsonBody, bearerToken: token);
    return CommandAck.fromJson(response.json! as Map<String, dynamic>);
  }
}
