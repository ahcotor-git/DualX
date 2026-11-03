import 'api_client.dart';
import 'map_models.dart';

/// map 域 HTTP 客户端——底图、楼层点位表与 UWB 标定。
///
/// 只做「拼路径 + 序列化/解析契约模型」，非 2xx 统一由 [ApiTransport] 抛 `ApiException`。
/// **不判业务**：底图被想定引用（409 `MAP_IN_USE`）、尚未标定（404 `MAP_NOT_CALIBRATED`）、
/// 图片类型 / 大小不合格（415 `UNSUPPORTED_MEDIA_TYPE` / 413 `PAYLOAD_TOO_LARGE`）、
/// 校准冲突（409 `MAP_CALIBRATION_IN_PROGRESS`）等判定全在服务端，本层只把错误透上去。
///
/// 令牌：本域属指挥端，使用账号 `accessToken`（契约只有 `bearerAuth` 一种方案）。
///
/// 注：[MapDetail.imageUrl] 是**不带 Bearer** 的短时效签名 URL，客户端直接把它拼上 base URL
/// 交给 `<img>` 加载即可；本层不代劳，也不缓存跨会话。
class MapApi {
  MapApi(this._client);

  final ApiTransport _client;

  // ── 底图（map）────────────────────────────────────────────────────────────

  /// 底图列表：`GET /api/v1/maps`（[type] 过滤；分页 1..200，服务端默认 20）。
  Future<MapPage> listMaps({
    required String token,
    MapType? type,
    int? page,
    int? size,
  }) async {
    final ApiResponse response = await _client.get(
      '/maps',
      query: _query(<String, Object?>{'type': type?.wire, 'page': page, 'size': size}),
      bearerToken: token,
    );
    return MapPage.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 新建底图：`POST /api/v1/maps`（201）。
  Future<MapDetail> createMap({
    required String token,
    required MapUpsertRequest request,
  }) async {
    final ApiResponse response =
        await _client.post('/maps', jsonBody: request.toJson(), bearerToken: token);
    return MapDetail.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 底图详情：`GET /api/v1/maps/{mapId}`（不存在 → 404 `MAP_NOT_FOUND`）。
  ///
  /// 每次调用都会拿回一份新的 [MapDetail.imageUrl]（签名带 `exp`），**不要跨会话缓存**。
  Future<MapDetail> getMap({required String token, required String mapId}) async {
    final ApiResponse response = await _client.get('/maps/$mapId', bearerToken: token);
    return MapDetail.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 修改底图：`PUT /api/v1/maps/{mapId}`——**整体替换**（见 [MapUpsertRequest]）。
  Future<MapDetail> updateMap({
    required String token,
    required String mapId,
    required MapUpsertRequest request,
  }) async {
    final ApiResponse response =
        await _client.put('/maps/$mapId', jsonBody: request.toJson(), bearerToken: token);
    return MapDetail.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 删除底图：`DELETE /api/v1/maps/{mapId}`（204，无正文）。
  ///
  /// 已被想定引用 → 409 `MAP_IN_USE`（不做级联删除）。
  Future<void> deleteMap({required String token, required String mapId}) async {
    await _client.delete('/maps/$mapId', bearerToken: token);
  }

  // ── 底图图片（multipart / 二进制）──────────────────────────────────────────

  /// 上传底图图片：`POST /api/v1/maps/{mapId}/image`（`multipart/form-data`）。
  ///
  /// 服务端按**真实图片头**判定类型（仅 PNG / JPEG / WebP，[contentType] 与扩展名都不作数），
  /// 单图上限 20MB；原格式落盘不转码，成功即回填 `imageWidth` / `imageHeight`。
  /// 同一底图重复上传即替换，旧文件删除、`imageUrl` 随之变化（天然规避浏览器旧图缓存）。
  Future<MapDetail> uploadMapImage({
    required String token,
    required String mapId,
    required String filename,
    required List<int> bytes,
    String contentType = 'application/octet-stream',
  }) async {
    final MultipartBody body = multipartFormData(
      files: <String, MultipartFile>{
        'file': MultipartFile(filename: filename, bytes: bytes, contentType: contentType),
      },
    );
    final ApiResponse response = await _client.sendBytes(
      'POST',
      '/maps/$mapId/image',
      body: body.body,
      contentType: body.contentType,
      bearerToken: token,
    );
    return MapDetail.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 下载底图图片：`GET /api/v1/maps/{mapId}/image`。
  ///
  /// **按上传原格式返回**（PNG / JPEG / WebP 各自对应 [ApiResponse.contentType]），不统一转码。
  /// 该路径要求 Bearer；保持原始 [ApiResponse]（字节 + contentType），由调用方决定落盘或渲染，
  /// 本层不解码。供 `<img>` 直接加载的免鉴权签名 URL 见 [MapDetail.imageUrl]。
  Future<ApiResponse> downloadMapImage({required String token, required String mapId}) =>
      _client.get('/maps/$mapId/image', bearerToken: token);

  // ── 楼层点位表（floor points）─────────────────────────────────────────────

  /// 楼层点位表：`GET /api/v1/maps/{mapId}/floors/{floorNum}/points`。
  ///
  /// 点位号与协议 A `ff03` 长版（15B）上报的 `posNum` 对应，用于无 UWB 时的粗定位。
  Future<FloorPointsRequest> listFloorPoints({
    required String token,
    required String mapId,
    required int floorNum,
  }) async {
    final ApiResponse response =
        await _client.get('/maps/$mapId/floors/$floorNum/points', bearerToken: token);
    return FloorPointsRequest.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 整体替换楼层点位表：`PUT /api/v1/maps/{mapId}/floors/{floorNum}/points`（幂等全量替换）。
  ///
  /// 空列表即清空该楼层点位表（合法）。
  Future<FloorPointsRequest> replaceFloorPoints({
    required String token,
    required String mapId,
    required int floorNum,
    required FloorPointsRequest request,
  }) async {
    final ApiResponse response = await _client.put(
      '/maps/$mapId/floors/$floorNum/points',
      jsonBody: request.toJson(),
      bearerToken: token,
    );
    return FloorPointsRequest.fromJson(response.json! as Map<String, dynamic>);
  }

  // ── UWB 标定（uwb-meta / uwb-calibration）────────────────────────────────

  /// 获取 UWB 坐标标定参数：`GET /api/v1/maps/{mapId}/uwb-meta`。
  ///
  /// 服务端**分码**区分两种 404：`MAP_NOT_FOUND`（id 打错了）与 `MAP_NOT_CALIBRATED`
  /// （图在、但还没做 UWB 标定）——现场处置动作不同（换图 vs 去标定），前端应分别引导。
  Future<UwbMeta> getUwbMeta({required String token, required String mapId}) async {
    final ApiResponse response = await _client.get('/maps/$mapId/uwb-meta', bearerToken: token);
    return UwbMeta.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 保存 UWB 坐标标定参数：`PUT /api/v1/maps/{mapId}/uwb-meta`——**整体替换**（见 [UwbMeta]）。
  ///
  /// 基站与测距数组错位会导致解算结果整体偏移，服务端保存前做一致性校验：字段级问题
  /// （bs1~bs4 缺席、坐标非数值）回 400 `VALIDATION_FAILED`（`ApiProblem.errors` 逐字段回填）；
  /// 语义问题（坐标越出底图宽高、四角退化共线、长宽非正整数厘米）回 400 `MAP_UWB_META_INCOMPLETE`。
  Future<UwbMeta> putUwbMeta({
    required String token,
    required String mapId,
    required UwbMeta meta,
  }) async {
    final ApiResponse response = await _client.put(
      '/maps/$mapId/uwb-meta',
      jsonBody: meta.toJson(),
      bearerToken: token,
    );
    return UwbMeta.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 触发 UWB 中心基准校准：`POST /api/v1/maps/{mapId}/uwb-calibration`（202）。
  ///
  /// **异步**：202 只表示已受理，进度用 [getUwbCalibration] 轮询。
  /// [request] 省略则不发送请求体（服务端取默认；`sampleFrames` 默认 200）。
  /// 已有校准在进行 → 409 `MAP_CALIBRATION_IN_PROGRESS`；UWB 链路未开启亦回 409。
  Future<UwbCalibrationStatus> startUwbCalibration({
    required String token,
    required String mapId,
    UwbCalibrationRequest? request,
  }) async {
    final ApiResponse response = await _client.post(
      '/maps/$mapId/uwb-calibration',
      jsonBody: request?.toJson(),
      bearerToken: token,
    );
    return UwbCalibrationStatus.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 查询 UWB 校准状态：`GET /api/v1/maps/{mapId}/uwb-calibration`。
  Future<UwbCalibrationStatus> getUwbCalibration({
    required String token,
    required String mapId,
  }) async {
    final ApiResponse response =
        await _client.get('/maps/$mapId/uwb-calibration', bearerToken: token);
    return UwbCalibrationStatus.fromJson(response.json! as Map<String, dynamic>);
  }

  /// 清除 UWB 校准结果：`DELETE /api/v1/maps/{mapId}/uwb-calibration`（204，无正文）。
  Future<void> clearUwbCalibration({required String token, required String mapId}) async {
    await _client.delete('/maps/$mapId/uwb-calibration', bearerToken: token);
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
