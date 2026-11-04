/// device 域契约模型（`docs/api/schemas/domain.yaml` 的 `VestDevice*` / `Anchor*` /
/// `LinkOpen*Request`，以及 `common.yaml` 的 `LinkKind` / `LinkState` / `LinkStatus`）。
///
/// 四块：
/// · **马甲台账**——`VestDevice`（台账行）/ `VestDevicePage`（列表分页）/ `VestDeviceUpsertRequest`（登记与修改）；
/// · **绑定闭环**——`VestBindingRequest`（首次绑定 / 换绑共用的请求体，只带明文密钥）；
/// · **链路状态**——`LinkStatus`（全部链路）/ `VestLink`（单马甲链路）/ `SerialPortInfo`（本机串口）；
/// · **UWB 基站**——`Anchor`（台账行）/ `AnchorUpsertRequest`（配置）。
///
/// 解析口径与 `map_models.dart` / `replay_models.dart` 一致：**不臆造缺省值**。
/// · 契约必填字段缺失 → `TypeError`；形状不符 → `FormatException`；
/// · **未知枚举字面量 → `FormatException`**（宁可显式失败，也不静默落到某个默认分支）；
/// · 契约中可空的字段（无 `required`）→ nullable。
///
/// 已复用的他域类型：分页元信息 [PageMeta]（与 `exercise_models.dart` 同源）、
/// 阵营 [Team]（与 `situation_models.dart` 同源）——契约里本就是同一份 `common.yaml`
/// 定义，**不重复造第二份**（与 `replay_models.dart` 复用口径一致）。
library;

import 'exercise_models.dart' show PageMeta;
import 'situation_models.dart' show Team;

/// 链路类型（`common.yaml#/LinkKind`）——链路状态列表恒定各一条，`kind` 是它的身份键。
enum LinkKind {
  serialVest('SERIAL_VEST'),
  uwbTcpServer('UWB_TCP_SERVER'),
  uwbSerial('UWB_SERIAL');

  const LinkKind(this.wire);

  /// 契约字面量。
  final String wire;

  static LinkKind fromWire(String wire) => values.firstWhere(
        (LinkKind value) => value.wire == wire,
        orElse: () => throw FormatException('未知 LinkKind 字面量: $wire'),
      );
}

/// 链路状态（`common.yaml#/LinkState`）。
///
/// `OPENING` 是异步打开的中间态；`ERROR` **不计入 active**——它是「打开失败留下的错」，
/// 客户端据 [LinkStatus.errorMessage] 呈现，不要当成「正在用」。
enum LinkState {
  closed('CLOSED'),
  opening('OPENING'),
  open('OPEN'),
  error('ERROR');

  const LinkState(this.wire);

  /// 契约字面量。
  final String wire;

  static LinkState fromWire(String wire) => values.firstWhere(
        (LinkState value) => value.wire == wire,
        orElse: () => throw FormatException('未知 LinkState 字面量: $wire'),
      );
}

/// UWB 接入方式（`domain.yaml#/LinkOpenUwbRequest.dataSource`）。
///
/// 二选一且**互斥**：任一模式已开启时，另一种模式的开启请求会被服务端拒（409）。
enum UwbDataSource {
  tcpServer('TCP_SERVER'),
  serial('SERIAL');

  const UwbDataSource(this.wire);

  /// 契约字面量。
  final String wire;

  static UwbDataSource fromWire(String wire) => values.firstWhere(
        (UwbDataSource value) => value.wire == wire,
        orElse: () => throw FormatException('未知 UwbDataSource 字面量: $wire'),
      );
}

/// 链路状态（`common.yaml#/LinkStatus`）——`GET /links` 与 `GET /ws/v1/devices/links`
/// 两条通道**同构**共用本类型。必填仅 [kind] / [state]。
///
/// [lastFrameAt] 是最近一次收到**合法帧**的时间；坏帧只计 [droppedTotal]（另有 [crcErrorTotal]）。
class LinkStatus {
  const LinkStatus({
    required this.kind,
    required this.state,
    this.target,
    this.baudRate,
    this.fdCount,
    this.lastFrameAt,
    this.framesTotal,
    this.droppedTotal,
    this.crcErrorTotal,
    this.errorMessage,
  });

  factory LinkStatus.fromJson(Map<String, dynamic> json) => LinkStatus(
        kind: LinkKind.fromWire(json['kind'] as String),
        state: LinkState.fromWire(json['state'] as String),
        target: json['target'] as String?,
        baudRate: json['baudRate'] as int?,
        fdCount: json['fdCount'] as int?,
        lastFrameAt: _dateTimeOrNull(json['lastFrameAt']),
        framesTotal: json['framesTotal'] as int?,
        droppedTotal: json['droppedTotal'] as int?,
        crcErrorTotal: json['crcErrorTotal'] as int?,
        errorMessage: json['errorMessage'] as String?,
      );

  final LinkKind kind;
  final LinkState state;

  /// 串口名（如 `COM7`）或监听地址（如 `0.0.0.0:12001`）。
  final String? target;

  /// 串口波特率；UWB TCP Server 无此项。
  final int? baudRate;

  /// UWB TCP Server 当前接入的连接数。
  final int? fdCount;

  /// 最近一次收到合法帧的时间。
  final DateTime? lastFrameAt;

  /// 本次开启以来收到的合法帧总数。
  final int? framesTotal;

  /// 本次开启以来丢弃的坏帧总数（校验失败 / 长度非法）。
  final int? droppedTotal;

  /// CRC 校验失败计数。
  final int? crcErrorTotal;

  /// `state=ERROR` 时的原因说明。
  final String? errorMessage;
}

/// 马甲台账行（`domain.yaml#/VestDevice`）。必填仅 [vestNum]（1~65532）。
///
/// [bound] 与 [enabled] 是**两件事**：`bound=false` 是「设备是好的，只是没被某台单兵终端认领」；
/// `enabled=false` 是「设备不算数」（报修 / 退役 / 逻辑注销），演习中会被忽略。
class VestDevice {
  const VestDevice({
    required this.vestNum,
    this.name,
    this.team,
    this.simNo,
    this.model,
    this.firmwareVersion,
    this.remark,
    this.bound,
    this.enabled,
    this.lastSeenAt,
    this.createdAt,
    this.updatedAt,
  });

  factory VestDevice.fromJson(Map<String, dynamic> json) => VestDevice(
        vestNum: json['vestNum'] as int,
        name: json['name'] as String?,
        team: _enumOrNull(json['team'], Team.fromWire),
        simNo: json['simNo'] as String?,
        model: json['model'] as String?,
        firmwareVersion: json['firmwareVersion'] as String?,
        remark: json['remark'] as String?,
        bound: json['bound'] as bool?,
        enabled: json['enabled'] as bool?,
        lastSeenAt: _dateTimeOrNull(json['lastSeenAt']),
        createdAt: _dateTimeOrNull(json['createdAt']),
        updatedAt: _dateTimeOrNull(json['updatedAt']),
      );

  /// 马甲编号（1~65532）。
  final int vestNum;

  /// 使用人姓名。
  final String? name;
  final Team? team;

  /// SIM 号，来自协议 A `ff0a`（18B）上报。
  final String? simNo;
  final String? model;
  final String? firmwareVersion;

  /// 台账备注。
  final String? remark;

  /// 是否已与某台单兵终端 App 绑定。
  final bool? bound;

  /// 台账启用状态；`false` 表示报修 / 退役。
  final bool? enabled;
  final DateTime? lastSeenAt;
  final DateTime? createdAt;
  final DateTime? updatedAt;
}

/// 马甲台账分页结果（`domain.yaml#/VestDevicePage`）。
///
/// 分页元信息复用 `exercise_models.dart` 的 [PageMeta]（同为 `common.yaml#/PageMeta`）。
class VestDevicePage {
  const VestDevicePage({required this.items, required this.meta});

  factory VestDevicePage.fromJson(Map<String, dynamic> json) => VestDevicePage(
        items: _listOrEmpty(json['items'], VestDevice.fromJson),
        meta: PageMeta.fromJson(json['meta'] as Map<String, dynamic>),
      );

  final List<VestDevice> items;
  final PageMeta meta;
}

/// 登记 / 修改马甲请求体（`domain.yaml#/VestDeviceUpsertRequest`）。必填仅 [vestNum]。
///
/// **整体替换口径**：服务端的 `updateVest` 用请求里的 `name`/`team`/`model`/`remark`
/// **原样覆盖**当前行（未传即置 `null`），故 [toJson] 恒输出全部键——要保留某字段必须显式回填，
/// 要清空某字段就传 `null`（不能靠「省略」表达保留）。
class VestDeviceUpsertRequest {
  const VestDeviceUpsertRequest({
    required this.vestNum,
    this.name,
    this.team,
    this.model,
    this.remark,
  });

  final int vestNum;
  final String? name;
  final Team? team;
  final String? model;
  final String? remark;

  Map<String, Object?> toJson() => <String, Object?>{
        'vestNum': vestNum,
        'name': name,
        'team': team?.wire,
        'model': model,
        'remark': remark,
      };
}

/// 绑定 / 换绑请求体（`domain.yaml#/VestBindingRequest`）。必填 [secret]（8~128）。
///
/// [secret] 是单兵 App 绑定密钥**明文**，仅上行一次；服务端只存 BCrypt 摘要，
/// 任何响应与列表都不回显。客户端**不得**把它写进日志或本地明文存储。
class VestBindingRequest {
  const VestBindingRequest({required this.secret});

  final String secret;

  Map<String, Object?> toJson() => <String, Object?>{'secret': secret};
}

/// 单马甲的链路状态（`paths/device.yaml#/vestLink.get` 的内联对象，契约未给它起名）。
///
/// 在线判定：距最后一次心跳 <= 5000ms 为在线，由服务端给出，客户端不重算。
/// 该内联对象无 `required`，故各字段按契约置 nullable。
class VestLink {
  const VestLink({
    this.vestNum,
    this.online,
    this.lastHeartbeatAt,
    this.lastFrameType,
    this.framesTotal,
    this.droppedTotal,
  });

  factory VestLink.fromJson(Map<String, dynamic> json) => VestLink(
        vestNum: json['vestNum'] as int?,
        online: json['online'] as bool?,
        lastHeartbeatAt: _dateTimeOrNull(json['lastHeartbeatAt']),
        lastFrameType: json['lastFrameType'] as String?,
        framesTotal: json['framesTotal'] as int?,
        droppedTotal: json['droppedTotal'] as int?,
      );

  final int? vestNum;

  /// 是否在线（心跳窗口内）。
  final bool? online;
  final DateTime? lastHeartbeatAt;

  /// 最近一次收到的上行帧类型（如 `ff03` / `ff04` / `ff05` / `ff07` / `ff0a`）。
  final String? lastFrameType;
  final int? framesTotal;
  final int? droppedTotal;
}

/// 本机可用串口（`paths/device.yaml#/serialPorts.get` 的内联对象）。
///
/// 无 `required`，按契约置 nullable。
class SerialPortInfo {
  const SerialPortInfo({this.portName, this.description});

  factory SerialPortInfo.fromJson(Map<String, dynamic> json) => SerialPortInfo(
        portName: json['portName'] as String?,
        description: json['description'] as String?,
      );

  /// 串口名，如 `COM7`。
  final String? portName;

  /// 串口描述（如 USB 转串口型号）；无则 `null`。
  final String? description;
}

/// UWB 基站台账行（`domain.yaml#/Anchor`）。必填仅 [anchorId]（1~8）。
///
/// [xPx] / [yPx] 是底图**归一化坐标（0~1，原点左上）**——字段名沿用冻结的 `uwb_meta` 与旧资产，
/// **不是像素值**；Y 轴映射由服务端完成，前端不得做 `1 - y` 之类的翻转（D15-⑤）。
/// [anchorId] 直接对应 41B 帧的 `ranges[anchorId-1]`。
class Anchor {
  const Anchor({
    required this.anchorId,
    this.name,
    this.xPx,
    this.yPx,
    this.enabled,
  });

  factory Anchor.fromJson(Map<String, dynamic> json) => Anchor(
        anchorId: json['anchorId'] as int,
        name: json['name'] as String?,
        xPx: (json['xPx'] as num?)?.toDouble(),
        yPx: (json['yPx'] as num?)?.toDouble(),
        enabled: json['enabled'] as bool?,
      );

  /// 基线编号 1..8。
  final int anchorId;
  final String? name;

  /// 底图归一化横坐标 0~1（原点左上）——不是像素值。
  final double? xPx;

  /// 底图归一化纵坐标 0~1（原点左上）——不是像素值。
  final double? yPx;

  /// bs5~bs8 允许关闭；bs1~bs4 为矩形四角，必开。
  final bool? enabled;
}

/// 配置 UWB 基站请求体（`domain.yaml#/AnchorUpsertRequest`）。契约未置 `required`。
///
/// **整体替换口径**：服务端 `xPx` / `yPx` / `enabled` 是**原始类型**，请求里缺席即落 `0.0` / `0.0` / `false`，
/// 故 [toJson] 恒输出全部键，调用方须显式填齐四项（不要指望「省略即保留原值」）。
class AnchorUpsertRequest {
  const AnchorUpsertRequest({this.name, this.xPx, this.yPx, this.enabled});

  final String? name;

  /// 底图归一化横坐标 0~1。
  final double? xPx;

  /// 底图归一化纵坐标 0~1。
  final double? yPx;
  final bool? enabled;

  Map<String, Object?> toJson() => <String, Object?>{
        'name': name,
        'xPx': xPx,
        'yPx': yPx,
        'enabled': enabled,
      };
}

/// 开启马甲串口链路请求体（`domain.yaml#/LinkOpenSerialRequest`）。必填 [portName]。
///
/// [baudRate] / [flowControlRtsCts] 契约有默认值（115200 / false），服务端对 null 会**兜底**，
/// 故 [toJson] 只输出非 `null` 的键——省略即走服务端默认。
class LinkOpenSerialRequest {
  const LinkOpenSerialRequest({
    required this.portName,
    this.baudRate,
    this.flowControlRtsCts,
  });

  final String portName;

  /// 波特率；不传由服务端兜底 115200。
  final int? baudRate;

  /// 是否启用 RTS/CTS 流控；不传由服务端兜底 false。
  final bool? flowControlRtsCts;

  Map<String, Object?> toJson() {
    final Map<String, Object?> json = <String, Object?>{'portName': portName};
    if (baudRate != null) {
      json['baudRate'] = baudRate;
    }
    if (flowControlRtsCts != null) {
      json['flowControlRtsCts'] = flowControlRtsCts;
    }
    return json;
  }
}

/// 开启 UWB 链路请求体（`domain.yaml#/LinkOpenUwbRequest`）。必填 [dataSource]。
///
/// [host] / [port] 仅 [UwbDataSource.tcpServer] 有效（服务端兜底 `0.0.0.0` / `12001`，
/// **12001 是硬约束，禁止改动**）；[serialPortName] 仅 [UwbDataSource.serial] 有效。
/// [toJson] 只输出非 `null` 的键，省略即走服务端默认。
class LinkOpenUwbRequest {
  const LinkOpenUwbRequest({
    required this.dataSource,
    this.host,
    this.port,
    this.serialPortName,
  });

  final UwbDataSource dataSource;

  /// 监听地址；仅 TCP_SERVER 有效。
  final String? host;

  /// 监听端口；仅 TCP_SERVER 有效。
  final int? port;

  /// 串口名；仅 SERIAL 有效。
  final String? serialPortName;

  Map<String, Object?> toJson() {
    final Map<String, Object?> json = <String, Object?>{'dataSource': dataSource.wire};
    if (host != null) {
      json['host'] = host;
    }
    if (port != null) {
      json['port'] = port;
    }
    if (serialPortName != null) {
      json['serialPortName'] = serialPortName;
    }
    return json;
  }
}

T? _enumOrNull<T>(Object? value, T Function(String wire) parse) =>
    value == null ? null : parse(value as String);

DateTime? _dateTimeOrNull(Object? value) =>
    value == null ? null : DateTime.parse(value as String);

List<T> _listOrEmpty<T>(Object? value, T Function(Map<String, dynamic> json) parse) =>
    (value as List<dynamic>? ?? const <dynamic>[])
        .map((dynamic item) => parse(item as Map<String, dynamic>))
        .toList(growable: false);
