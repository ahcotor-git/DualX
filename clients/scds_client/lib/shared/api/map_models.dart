/// map 域契约模型（`docs/api/schemas/domain.yaml` 的 `Map*` / `FloorPoints*` / `Uwb*`）。
///
/// 三块：
/// · **底图**——`MapDetail`（读）与 `MapUpsertRequest`（写）；
/// · **楼层点位表**——`FloorPoint` / `FloorPointsRequest`；
/// · **UWB 标定**——`UwbAnchorPoint` / `UwbMeta`（坐标标定参数）、
///   `UwbCalibrationRequest` / `UwbCalibrationStatus`（中心基准校准）。
///
/// 解析口径与 `exercise_models.dart` / `situation_models.dart` 一致：**不臆造缺省值**。
/// · 契约必填字段缺失 → `TypeError`；形状不符 → `FormatException`；
/// · **未知枚举字面量 → `FormatException`**（宁可显式失败，也不静默落到某个默认分支）；
/// · 契约中可空的字段（无 `required`）→ nullable。
///
/// **请求体口径分两类，不可混淆**（见 `docs/api/paths/map.yaml`）：
/// · **整体替换**（`MapUpsertRequest` / `FloorPoint` / `UwbMeta`）：PUT 全量替换，
///   `null` 就是「置空」，故 `toJson` 显式输出全部键；
/// · **可选缺省**（`UwbCalibrationRequest`）：缺席由服务端补默认值（`sampleFrames` 默认 200），
///   故 `toJson` 只发非 null 键。
///
/// 坐标有两套口径，**不可混用**：[FloorPoint] 的 `xPct` / `yPct` 是**归一化百分比 0~1**，
/// 而 [UwbAnchorPoint] 的 `xPx` / `yPx` 是**底图像素**（沿用冻结的 `uwb_meta` 结构）。
library;

import 'exercise_models.dart' show PageMeta;

/// 底图类型（`domain.yaml#/MapType`）。
///
/// [indoor] 配 UWB 标定；[outdoor] 用 GPS 经纬度，无底图亦可。
enum MapType {
  indoor('INDOOR'),
  outdoor('OUTDOOR');

  const MapType(this.wire);

  /// 契约字面量。
  final String wire;

  static MapType fromWire(String wire) => values.firstWhere(
        (MapType value) => value.wire == wire,
        orElse: () => throw FormatException('未知 MapType 字面量: $wire'),
      );
}

/// 底图详情（`domain.yaml#/MapDetail`）。
///
/// 必填仅 [mapId] / [name] / [type]；其余（含 [floorCount] / [calibrated] /
/// [createdAt] / [updatedAt]）契约未置 `required`，故为 nullable。
class MapDetail {
  const MapDetail({
    required this.mapId,
    required this.name,
    required this.type,
    this.imageUrl,
    this.imageWidth,
    this.imageHeight,
    this.floorCount,
    this.calibrated,
    this.calibratedAt,
    this.createdAt,
    this.updatedAt,
  });

  factory MapDetail.fromJson(Map<String, dynamic> json) => MapDetail(
        mapId: json['mapId'] as String,
        name: json['name'] as String,
        type: MapType.fromWire(json['type'] as String),
        imageUrl: json['imageUrl'] as String?,
        imageWidth: json['imageWidth'] as int?,
        imageHeight: json['imageHeight'] as int?,
        floorCount: json['floorCount'] as int?,
        calibrated: json['calibrated'] as bool?,
        calibratedAt: _dateTimeOrNull(json['calibratedAt']),
        createdAt: _dateTimeOrNull(json['createdAt']),
        updatedAt: _dateTimeOrNull(json['updatedAt']),
      );

  final String mapId;
  final String name;
  final MapType type;

  /// 底图**短时效签名**访问 URL（默认 15 分钟；**根相对路径、不带 `Authorization`**，
  /// 供 `<img>` 直接加载）；未上传图片时为 `null`。
  ///
  /// 形状即 `GET /api/v1/files` 的取文件路由，由客户端自行拼 base URL——服务端不猜部署
  /// 形态下的对外地址。过期后重新取详情换新 URL，**不缓存跨会话**。
  final String? imageUrl;

  /// 原图像素宽 / 高；未上传图片时为 `null`。
  final int? imageWidth;
  final int? imageHeight;

  /// 楼层数。
  final int? floorCount;

  /// UWB 标定（`uwb-meta`）是否齐备：bs1~bs4 与长宽基准必须全部有值。
  final bool? calibrated;

  /// 最近一次写入 UWB 标定参数的时刻；从未标定（或标定早于该字段引入）时为 `null`。
  ///
  /// 与 [calibrated] 同源但**不等价**：[calibrated] 判「参数是否齐备」，本字段记「最近一次
  /// 落库的时刻」；本字段非空必然 `calibrated=true`，反之不成立。它是重启后标定是否生效的
  /// 唯一证据（标定会话状态本身只在内存里）。
  final DateTime? calibratedAt;

  final DateTime? createdAt;
  final DateTime? updatedAt;
}

/// 新建 / 修改底图的请求体（`domain.yaml#/MapUpsertRequest`）。
///
/// 必填：[name]（1~128 字符）/ [type]。PUT 是**整体替换**，两个字段都必填，
/// 故 [toJson] 恒输出全部键。
class MapUpsertRequest {
  const MapUpsertRequest({required this.name, required this.type});

  final String name;
  final MapType type;

  Map<String, Object?> toJson() => <String, Object?>{
        'name': name,
        'type': type.wire,
      };
}

/// 底图分页结果（`paths/map.yaml#/maps.get` 的内联对象，契约未给它起名）。
///
/// 分页元信息复用 `exercise_models.dart` 的 [PageMeta]（同为 `common.yaml#/PageMeta`，
/// 客户端目前只在那一处声明，不重复造第二份）。
class MapPage {
  const MapPage({required this.items, required this.meta});

  factory MapPage.fromJson(Map<String, dynamic> json) => MapPage(
        items: _listOrEmpty(json['items'], MapDetail.fromJson),
        meta: PageMeta.fromJson(json['meta'] as Map<String, dynamic>),
      );

  final List<MapDetail> items;
  final PageMeta meta;
}

/// 楼层平面上的一个点位（`domain.yaml#/FloorPointsRequest.points[]`）。
///
/// 坐标为**归一化百分比** 0~1（与服务端字段同名同义）；与 [UwbAnchorPoint] 的
/// **像素**坐标是两套口径，勿混。
class FloorPoint {
  const FloorPoint({
    required this.posNum,
    required this.xPct,
    required this.yPct,
    this.label,
  });

  factory FloorPoint.fromJson(Map<String, dynamic> json) => FloorPoint(
        posNum: json['posNum'] as int,
        xPct: (json['xPct'] as num).toDouble(),
        yPct: (json['yPct'] as num).toDouble(),
        label: json['label'] as String?,
      );

  /// 点位号，与协议 A `ff03` 长版（15B）上报的 `posNum` 对应（无 UWB 时的粗定位）。
  final int posNum;

  /// 归一化横坐标 0~1。
  final double xPct;

  /// 归一化纵坐标 0~1。
  final double yPct;
  final String? label;

  /// 整体替换口径：四个键**全部显式输出**（[label] 缺席即置 `null`）。
  Map<String, Object?> toJson() => <String, Object?>{
        'posNum': posNum,
        'xPct': xPct,
        'yPct': yPct,
        'label': label,
      };
}

/// 楼层点位表（`domain.yaml#/FloorPointsRequest`）。
///
/// 读（`GET .../points` 回 `{points: [...]}`）与写（`PUT` 请求体）**同形**，契约只给写侧
/// 起了名，故两向复用本类型。**整体替换、幂等**：PUT 之后服务端存的就是这一份清单；
/// 空列表即清空该楼层点位表（合法）。
class FloorPointsRequest {
  const FloorPointsRequest({required this.points});

  factory FloorPointsRequest.fromJson(Map<String, dynamic> json) => FloorPointsRequest(
        points: _listOrEmpty(json['points'], FloorPoint.fromJson),
      );

  final List<FloorPoint> points;

  Map<String, Object?> toJson() => <String, Object?>{
        'points': points.map((FloorPoint point) => point.toJson()).toList(growable: false),
      };
}

/// 单个基站在底图上的**像素**坐标（`domain.yaml#/UwbAnchorPoint`，原点左上）。
///
/// 字段名 `xPx` / `yPx` 沿用冻结的 `uwb_meta` 结构与 `t_map.uwb_meta` 库列——**字段名与语义
/// 都是像素**；像素→百分比的换算只在解算链路内做，不经本类型。
class UwbAnchorPoint {
  const UwbAnchorPoint({required this.xPx, required this.yPx});

  factory UwbAnchorPoint.fromJson(Map<String, dynamic> json) => UwbAnchorPoint(
        xPx: (json['xPx'] as num).toDouble(),
        yPx: (json['yPx'] as num).toDouble(),
      );

  /// 底图像素横坐标（原点左上）。
  final double xPx;

  /// 底图像素纵坐标（原点左上）。
  final double yPx;

  Map<String, Object?> toJson() => <String, Object?>{'xPx': xPx, 'yPx': yPx};
}

/// UWB 解算坐标系参数（`domain.yaml#/UwbMeta`，旧系统 `uwb_meta.json`）。
///
/// 与库列 `t_map.uwb_meta` **逐字同形**：`bs1`~`bs8` 与两个长宽基准都在**顶层**，
/// 没有 `anchors` 包装层——API、库、解算三处同形，零转换、零漂移。
///
/// **基站编号与 41B 帧测距数组固定一一对应**：`bs(i+1)` ↔ `ranges[i]`（i=0..7），
/// 字节偏移 `7 + 4*i`（小端 int32），**错位会导致解算结果整体偏移**。
/// bs1~bs4 为矩形四角（必填；`bs1↔bs2` 提供长度基准、`bs1↔bs4` 提供宽度基准），bs5~bs8 可选。
class UwbMeta {
  const UwbMeta({
    required this.bs1,
    required this.bs2,
    required this.bs3,
    required this.bs4,
    required this.realLengthCm,
    required this.realWidthCm,
    this.bs5,
    this.bs6,
    this.bs7,
    this.bs8,
  });

  factory UwbMeta.fromJson(Map<String, dynamic> json) => UwbMeta(
        bs1: UwbAnchorPoint.fromJson(json['bs1'] as Map<String, dynamic>),
        bs2: UwbAnchorPoint.fromJson(json['bs2'] as Map<String, dynamic>),
        bs3: UwbAnchorPoint.fromJson(json['bs3'] as Map<String, dynamic>),
        bs4: UwbAnchorPoint.fromJson(json['bs4'] as Map<String, dynamic>),
        bs5: _anchorOrNull(json['bs5']),
        bs6: _anchorOrNull(json['bs6']),
        bs7: _anchorOrNull(json['bs7']),
        bs8: _anchorOrNull(json['bs8']),
        realLengthCm: (json['realLengthCm'] as num).toDouble(),
        realWidthCm: (json['realWidthCm'] as num).toDouble(),
      );

  final UwbAnchorPoint bs1;
  final UwbAnchorPoint bs2;
  final UwbAnchorPoint bs3;
  final UwbAnchorPoint bs4;

  /// 可选基站 bs5~bs8（缺席即 `null`）。
  final UwbAnchorPoint? bs5;
  final UwbAnchorPoint? bs6;
  final UwbAnchorPoint? bs7;
  final UwbAnchorPoint? bs8;

  /// `bs1↔bs2` 现场实测长度（正整数厘米，> 0）。
  final double realLengthCm;

  /// `bs1↔bs4` 现场实测宽度（正整数厘米，> 0）。
  final double realWidthCm;

  /// 整体替换口径：`bs1`~`bs8` 与两个长宽基准**全部显式输出**（bs5~bs8 缺席即置 `null`）。
  Map<String, Object?> toJson() => <String, Object?>{
        'bs1': bs1.toJson(),
        'bs2': bs2.toJson(),
        'bs3': bs3.toJson(),
        'bs4': bs4.toJson(),
        'bs5': bs5?.toJson(),
        'bs6': bs6?.toJson(),
        'bs7': bs7?.toJson(),
        'bs8': bs8?.toJson(),
        'realLengthCm': realLengthCm,
        'realWidthCm': realWidthCm,
      };
}

/// 触发 UWB 中心基准校准的请求体（`domain.yaml#/UwbCalibrationRequest`）——**全部字段可选**。
///
/// 服务端 `sampleFrames` 默认 200（对应 `scds-solver` 的 `calibSampleFrames`），
/// 故 [toJson] **只发非 null 键**，不臆造默认值。
class UwbCalibrationRequest {
  const UwbCalibrationRequest({this.sampleFrames, this.bs1ToBs2Cm, this.bs1ToBs3Cm});

  /// 采样帧数；不传走服务端默认 200。
  final int? sampleFrames;

  /// 现场卷尺实测长度（厘米），用于反推标定系数。
  final double? bs1ToBs2Cm;

  /// 现场卷尺实测宽度（厘米）。
  final double? bs1ToBs3Cm;

  Map<String, Object?> toJson() {
    final Map<String, Object?> json = <String, Object?>{};
    if (sampleFrames != null) {
      json['sampleFrames'] = sampleFrames;
    }
    if (bs1ToBs2Cm != null) {
      json['bs1ToBs2Cm'] = bs1ToBs2Cm;
    }
    if (bs1ToBs3Cm != null) {
      json['bs1ToBs3Cm'] = bs1ToBs3Cm;
    }
    return json;
  }
}

/// UWB 校准状态机（`domain.yaml#/UwbCalibrationStatus.state`）。
enum UwbCalibrationState {
  idle('IDLE'),
  running('RUNNING'),
  succeeded('SUCCEEDED'),
  failed('FAILED');

  const UwbCalibrationState(this.wire);

  /// 契约字面量。
  final String wire;

  static UwbCalibrationState fromWire(String wire) => values.firstWhere(
        (UwbCalibrationState value) => value.wire == wire,
        orElse: () => throw FormatException('未知 UwbCalibrationState 字面量: $wire'),
      );
}

/// UWB 校准状态（`domain.yaml#/UwbCalibrationStatus`）。必填仅 [state]。
///
/// 触发端点（`POST .../uwb-calibration`）异步执行、回 202，进度靠本状态轮询。
class UwbCalibrationStatus {
  const UwbCalibrationStatus({
    required this.state,
    this.progressPct,
    this.sampledFrames,
    this.appliedAt,
    this.message,
  });

  factory UwbCalibrationStatus.fromJson(Map<String, dynamic> json) => UwbCalibrationStatus(
        state: UwbCalibrationState.fromWire(json['state'] as String),
        progressPct: json['progressPct'] as int?,
        sampledFrames: json['sampledFrames'] as int?,
        appliedAt: _dateTimeOrNull(json['appliedAt']),
        message: json['message'] as String?,
      );

  final UwbCalibrationState state;

  /// 进度百分比 0~100。
  final int? progressPct;

  /// 已采样帧数。
  final int? sampledFrames;

  /// 标定结果落库时刻；[message] 为失败原因说明（无则 `null`）。
  final DateTime? appliedAt;
  final String? message;
}

UwbAnchorPoint? _anchorOrNull(Object? value) =>
    value == null ? null : UwbAnchorPoint.fromJson(value as Map<String, dynamic>);

DateTime? _dateTimeOrNull(Object? value) =>
    value == null ? null : DateTime.parse(value as String);

List<T> _listOrEmpty<T>(Object? value, T Function(Map<String, dynamic> json) parse) =>
    (value as List<dynamic>? ?? const <dynamic>[])
        .map((dynamic item) => parse(item as Map<String, dynamic>))
        .toList(growable: false);
