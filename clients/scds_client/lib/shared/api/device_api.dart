import 'api_client.dart';
import 'device_models.dart';
import 'situation_models.dart' show Team, VestStatus;

/// device 域 HTTP 客户端——设备台账与硬件链路（**17 个端点**）。
///
/// 只做「拼路径 + 序列化/解析契约模型」，非 2xx 统一由 [ApiTransport] 抛 `ApiException`。
/// **不判业务**：编号冲突（`DEVICE_VEST_NUM_CONFLICT`）、已绑定 / 未绑定
/// （`DEVICE_VEST_ALREADY_BOUND` / `DEVICE_VEST_NOT_BOUND`）、链路已开 / 端口占用
/// （`DEVICE_LINK_ALREADY_OPEN` / `DEVICE_LINK_SERIAL_BUSY`）、演习进行中禁止注销
/// （`EXERCISE_ALREADY_RUNNING`）等判定全在服务端，本层只把错误透上去，调用方据
/// `ApiException.code` 分支。
///
/// 令牌：本域属指挥端，使用账号 `accessToken`（契约只有 `bearerAuth` 一种方案）。
///
/// 端点分组：
/// · **马甲台账**（`vests` / `vestById`）——列表 / 登记 / 详情 / 修改 / 逻辑注销；
/// · **绑定闭环**（`vestBinding`）——POST 首次绑定 / PUT 换绑 / DELETE 解绑（幂等）；
/// · **单马甲链路**（`vestLink`）——`GET .../link`（在线判定 <=5000ms）；
/// · **UWB 基站**（`anchors` / `anchorById`）——列表 / 逐台 upsert；
/// · **链路状态与开关**（`links` / `serialPorts` / `linkSerial` / `linkUwb`）——查状态、
///   列串口、开 / 关串口链路、开 / 关 UWB 链路。
///
/// 注：链路推送 WS（`GET /ws/v1/devices/links`，子协议 `scds.links.v1`）与 `GET /links`
/// **载荷同构**，其连接与重连属状态层（同 `shared/state/situation_socket.dart` 之于态势），
/// 不在本层；本层只给 REST 兜底轮询用的 [getLinks]。
class DeviceApi {
  DeviceApi(this._client);

  final ApiTransport _client;

  // ── 马甲台账（vests）──────────────────────────────────────────────────────

  /// 马甲台账列表：`GET /api/v1/devices/vests`。
  ///
  /// [keyword] 按编号或姓名模糊搜索；[team] / [status] / [bound] 为过滤条件。
  /// 分页 1..200，服务端默认 `page=1` / `size=20`。**不传即走服务端默认**。
  Future<VestDevicePage> listVests({
    required String token,
    String? keyword,
    Team? team,
    VestStatus? status,
    bool? bound,
    int? page,
    int? size,
  }) async {
    final ApiResponse response = await _client.get(
      '/devices/vests',
      query: _query(<String, Object?>{
        'keyword': keyword,
        'team': team?.wire,
        'status': status?.wire,
        'bound': bound,
        'page': page,
        'size': size,
      }),
      bearerToken: token,
    );
    return VestDevicePage.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 登记马甲：`POST /api/v1/devices/vests`（201；编号已存在 → 409 `DEVICE_VEST_NUM_CONFLICT`）。
  Future<VestDevice> createVest({
    required String token,
    required VestDeviceUpsertRequest request,
  }) async {
    final ApiResponse response = await _client.post(
      '/devices/vests',
      jsonBody: request.toJson(),
      bearerToken: token,
    );
    return VestDevice.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 马甲详情：`GET /api/v1/devices/vests/{vestNum}`（不存在 → 404 `DEVICE_VEST_NOT_FOUND`）。
  Future<VestDevice> getVest({required String token, required int vestNum}) async {
    final ApiResponse response = await _client.get('/devices/vests/$vestNum', bearerToken: token);
    return VestDevice.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 修改马甲：`PUT /api/v1/devices/vests/{vestNum}`（**整体替换** `name`/`team`/`model`/`remark`）。
  ///
  /// 契约允许请求体带 `vestNum`（与 POST 共用 schema）；与路径编号不一致时服务端拒
  /// （400 `VALIDATION_FAILED`），故调用方应保证二者一致。
  Future<VestDevice> updateVest({
    required String token,
    required int vestNum,
    required VestDeviceUpsertRequest request,
  }) async {
    final ApiResponse response = await _client.put(
      '/devices/vests/$vestNum',
      jsonBody: request.toJson(),
      bearerToken: token,
    );
    return VestDevice.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 注销马甲：`DELETE /api/v1/devices/vests/{vestNum}`（**逻辑删除**，204，无响应体）。
  ///
  /// 服务端置 `enabled=0` 并清会话、保留绑定密钥；**演习进行中禁止**（409 `EXERCISE_ALREADY_RUNNING`）。
  Future<void> deleteVest({required String token, required int vestNum}) =>
      _client.delete('/devices/vests/$vestNum', bearerToken: token);

  // ── 绑定闭环（binding，决策 04 D03-4）──────────────────────────────────────

  /// 首次绑定单兵终端：`POST /api/v1/devices/vests/{vestNum}/binding`（201）。
  ///
  /// 要求当前**未绑定**；已绑定 → 409 `DEVICE_VEST_ALREADY_BOUND`（换绑请用 [rebindVest]）。
  /// 成功后服务端把该马甲的设备会话 SID 置 NULL（此前签发的设备令牌立即失效）。
  Future<VestDevice> bindVest({
    required String token,
    required int vestNum,
    required VestBindingRequest request,
  }) async {
    final ApiResponse response = await _client.post(
      '/devices/vests/$vestNum/binding',
      jsonBody: request.toJson(),
      bearerToken: token,
    );
    return VestDevice.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 换绑单兵终端：`PUT /api/v1/devices/vests/{vestNum}/binding`。
  ///
  /// 要求当前**已绑定**；未绑定 → 409 `DEVICE_VEST_NOT_BOUND`（首次绑定请用 [bindVest]）。
  /// 用新密钥替换旧摘要，`bound` 保持 1，并清空设备会话 SID 使旧设备令牌失效。
  Future<VestDevice> rebindVest({
    required String token,
    required int vestNum,
    required VestBindingRequest request,
  }) async {
    final ApiResponse response = await _client.put(
      '/devices/vests/$vestNum/binding',
      jsonBody: request.toJson(),
      bearerToken: token,
    );
    return VestDevice.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 解绑单兵终端：`DELETE /api/v1/devices/vests/{vestNum}/binding`（**幂等**，回 `VestDevice`）。
  ///
  /// `bound` 置 0 并清会话，但**保留**绑定密钥——解绑撤销的是「认领」，不是销毁凭据；
  /// 对未绑定的马甲调用同样返回 200。
  Future<VestDevice> unbindVest({required String token, required int vestNum}) async {
    final ApiResponse response =
        await _client.delete('/devices/vests/$vestNum/binding', bearerToken: token);
    return VestDevice.fromJson(response.json! as Map<String, dynamic>);
  }

  // ── 单马甲链路（vestLink）────────────────────────────────────────────────

  /// 单个马甲的链路状态：`GET /api/v1/devices/vests/{vestNum}/link`。
  ///
  /// 在线判定：距最后一次心跳 <= 5000ms 为在线（服务端给出）。
  Future<VestLink> getVestLink({required String token, required int vestNum}) async {
    final ApiResponse response =
        await _client.get('/devices/vests/$vestNum/link', bearerToken: token);
    return VestLink.fromJson(response.json! as Map<String, dynamic>);
  }

  // ── UWB 基站台账（anchors）───────────────────────────────────────────────

  /// UWB 基站台账：`GET /api/v1/devices/anchors`（`Anchor` 数组）。
  ///
  /// 返回基线编号与归一化坐标；解算坐标参数（`realLengthCm` / `realWidthCm`）在地图域的
  /// `uwb-meta` 里维护，不在此端。
  Future<List<Anchor>> listAnchors({required String token}) async {
    final ApiResponse response = await _client.get('/devices/anchors', bearerToken: token);
    return _listOf(response.json, Anchor.fromJson);
  }

  /// 配置 UWB 基站：`PUT /api/v1/devices/anchors/{anchorId}`（upsert，[anchorId] 1..8）。
  Future<Anchor> upsertAnchor({
    required String token,
    required int anchorId,
    required AnchorUpsertRequest request,
  }) async {
    final ApiResponse response = await _client.put(
      '/devices/anchors/$anchorId',
      jsonBody: request.toJson(),
      bearerToken: token,
    );
    return Anchor.fromJson(response.json! as Map<String, dynamic>);
  }

  // ── 链路状态与开关（links）──────────────────────────────────────────────

  /// 全部链路状态：`GET /api/v1/devices/links`（`LinkStatus` 数组，三种链路恒定各一条）。
  ///
  /// 这是 WS 推送通道的 **HTTP 兜底**——契约要求客户端仍低频轮询本端。
  Future<List<LinkStatus>> getLinks({required String token}) async {
    final ApiResponse response = await _client.get('/devices/links', bearerToken: token);
    return _listOf(response.json, LinkStatus.fromJson);
  }

  /// 本机可用串口列表：`GET /api/v1/devices/links/serial-ports`（`SerialPortInfo` 数组）。
  Future<List<SerialPortInfo>> listSerialPorts({required String token}) async {
    final ApiResponse response =
        await _client.get('/devices/links/serial-ports', bearerToken: token);
    return _listOf(response.json, SerialPortInfo.fromJson);
  }

  /// 开启马甲串口链路：`POST /api/v1/devices/links/serial`。
  ///
  /// 链路已处于开启中 / 已开启 → 409 `DEVICE_LINK_ALREADY_OPEN`（**不隐式先关再开**）；
  /// 物理串口被占用或打开被系统拒绝 → 409 `DEVICE_LINK_SERIAL_BUSY`。需要重连请先 [closeVestSerial]。
  Future<LinkStatus> openVestSerial({
    required String token,
    required LinkOpenSerialRequest request,
  }) async {
    final ApiResponse response = await _client.post(
      '/devices/links/serial',
      jsonBody: request.toJson(),
      bearerToken: token,
    );
    return LinkStatus.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 关闭马甲串口链路：`DELETE /api/v1/devices/links/serial`（**幂等**，未开启也回成功）。
  Future<LinkStatus> closeVestSerial({required String token}) async {
    final ApiResponse response = await _client.delete('/devices/links/serial', bearerToken: token);
    return LinkStatus.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 开启 UWB 链路：`POST /api/v1/devices/links/uwb`。
  ///
  /// 二选一且**互斥**：任一模式已开启时，另一种模式的开启请求 → 409 `DEVICE_LINK_ALREADY_OPEN`
  /// （不隐式切换，切换接入方式必须先 [closeUwbLink]）；端口占用 → 409 `DEVICE_LINK_SERIAL_BUSY`。
  Future<LinkStatus> openUwbLink({
    required String token,
    required LinkOpenUwbRequest request,
  }) async {
    final ApiResponse response = await _client.post(
      '/devices/links/uwb',
      jsonBody: request.toJson(),
      bearerToken: token,
    );
    return LinkStatus.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 关闭 UWB 链路：`DELETE /api/v1/devices/links/uwb`（**幂等**，未开启也回成功）。
  Future<LinkStatus> closeUwbLink({required String token}) async {
    final ApiResponse response = await _client.delete('/devices/links/uwb', bearerToken: token);
    return LinkStatus.fromJson(response.json! as Map<String, dynamic>);
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

/// 解析顶层 JSON 数组为契约模型列表。
List<T> _listOf<T>(Object? value, T Function(Map<String, dynamic> json) parse) =>
    (value as List<dynamic>)
        .map((dynamic item) => parse(item as Map<String, dynamic>))
        .toList(growable: false);
