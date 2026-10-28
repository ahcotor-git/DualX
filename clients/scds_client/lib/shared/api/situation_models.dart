/// 态势域契约模型（`docs/api/schemas/situation.yaml` + `common.yaml`）。
///
/// 本文件只做**解析**：HTTP Poll 与 WebSocket 两条通道的载荷**同构**，共用这一套类型
/// （硬约束）。不做本地缓存聚合与换代判定——那是态势状态机的事，另行落地。
///
/// 解析口径与 `auth_models.dart` 一致：**不臆造缺省值**。
/// · 契约必填字段缺失 → `TypeError`；
/// · 形状不符（如 `roles` 非数组）→ `FormatException`；
/// · **未知枚举字面量 → `FormatException`**（宁可显式失败，也不静默落到某个默认分支）；
/// · 契约中可空的字段（无 `required` 的集合属性）→ 默认空列表。
library;

/// 阵营（`common.yaml#/Team`）。
enum Team {
  red('RED'),
  blue('BLUE'),
  orange('ORANGE'),
  yellow('YELLOW');

  const Team(this.wire);

  /// 契约字面量。
  final String wire;

  static Team fromWire(String wire) => values.firstWhere(
        (Team value) => value.wire == wire,
        orElse: () => throw FormatException('未知 Team 字面量: $wire'),
      );
}

/// 马甲在线/存活状态（`common.yaml#/VestStatus`）。
///
/// 在线判定为心跳驱动（距最后一次心跳 <= 5000ms 视为在线），由服务端给出，客户端不重算。
enum VestStatus {
  offline('OFFLINE'),
  live('LIVE'),
  die('DIE');

  const VestStatus(this.wire);

  final String wire;

  static VestStatus fromWire(String wire) => values.firstWhere(
        (VestStatus value) => value.wire == wire,
        orElse: () => throw FormatException('未知 VestStatus 字面量: $wire'),
      );
}

/// 命中部位（`common.yaml#/HitPart`）。
enum HitPart {
  unknown('UNKNOWN'),
  head('HEAD'),
  chest('CHEST'),
  leftHand('LEFT_HAND'),
  rightHand('RIGHT_HAND'),
  abdomen('ABDOMEN'),
  back('BACK'),
  leftLeg('LEFT_LEG'),
  rightLeg('RIGHT_LEG');

  const HitPart(this.wire);

  final String wire;

  static HitPart fromWire(String wire) => values.firstWhere(
        (HitPart value) => value.wire == wire,
        orElse: () => throw FormatException('未知 HitPart 字面量: $wire'),
      );
}

/// 武器种类（`common.yaml#/WeaponKind`）。
enum WeaponKind {
  howitzer35('HOWITZER_35'),
  rocket80('ROCKET_80'),
  rocket120('ROCKET_120'),
  ifv('IFV'),
  assaultVehicle('ASSAULT_VEHICLE'),
  apc('APC'),
  hj73('HJ73'),
  mortar('MORTAR'),
  firePoint('FIRE_POINT'),
  mine('MINE'),
  explosive('EXPLOSIVE'),
  grenade('GRENADE');

  const WeaponKind(this.wire);

  final String wire;

  static WeaponKind fromWire(String wire) => values.firstWhere(
        (WeaponKind value) => value.wire == wire,
        orElse: () => throw FormatException('未知 WeaponKind 字面量: $wire'),
      );
}

/// 运动状态（`common.yaml#/MotionState`）。阵亡后服务端冻结坐标，此时为 [frozen]。
enum MotionState {
  moving('MOVING'),
  stationary('STATIONARY'),
  frozen('FROZEN');

  const MotionState(this.wire);

  final String wire;

  static MotionState fromWire(String wire) => values.firstWhere(
        (MotionState value) => value.wire == wire,
        orElse: () => throw FormatException('未知 MotionState 字面量: $wire'),
      );
}

/// 参演单元角色，由编号区间推导（`situation.yaml#/UnitState.combatRole`）。
enum CombatRole {
  soldier('SOLDIER'),
  weapon('WEAPON'),
  vehicle('VEHICLE'),
  hazard('HAZARD');

  const CombatRole(this.wire);

  final String wire;

  static CombatRole fromWire(String wire) => values.firstWhere(
        (CombatRole value) => value.wire == wire,
        orElse: () => throw FormatException('未知 CombatRole 字面量: $wire'),
      );
}

/// 坐标来源（`situation.yaml#/UnitState.positionSource`）。
enum PositionSource {
  uwb('UWB'),
  gps('GPS'),
  manual('MANUAL'),
  none('NONE');

  const PositionSource(this.wire);

  final String wire;

  static PositionSource fromWire(String wire) => values.firstWhere(
        (PositionSource value) => value.wire == wire,
        orElse: () => throw FormatException('未知 PositionSource 字面量: $wire'),
      );
}

/// 坐标系（`common.yaml#/GeoPoint.coordinateSystem`）。
enum CoordinateSystem {
  indoor('INDOOR'),
  outdoor('OUTDOOR');

  const CoordinateSystem(this.wire);

  final String wire;

  static CoordinateSystem fromWire(String wire) => values.firstWhere(
        (CoordinateSystem value) => value.wire == wire,
        orElse: () => throw FormatException('未知 CoordinateSystem 字面量: $wire'),
      );
}

/// 统一坐标载体（`common.yaml#/GeoPoint`）。
///
/// **两套坐标同时下发，前端不得自行换算**：室内用 [xPct] / [yPct]（0~1，原点左上，
/// 服务端已完成 Y 轴映射，前端不得做 `1 - y` 翻转）；室外 [lng] / [lat] 为 BD-09，
/// [wgs84Lng] / [wgs84Lat] 为 WGS-84。
class GeoPoint {
  const GeoPoint({
    this.coordinateSystem,
    this.xPct,
    this.yPct,
    this.floorNum,
    this.posNum,
    this.lng,
    this.lat,
    this.wgs84Lng,
    this.wgs84Lat,
  });

  factory GeoPoint.fromJson(Map<String, dynamic> json) => GeoPoint(
        coordinateSystem: _enumOrNull(json['coordinateSystem'], CoordinateSystem.fromWire),
        xPct: _doubleOrNull(json['xPct']),
        yPct: _doubleOrNull(json['yPct']),
        floorNum: json['floorNum'] as int?,
        posNum: json['posNum'] as int?,
        lng: _doubleOrNull(json['lng']),
        lat: _doubleOrNull(json['lat']),
        wgs84Lng: _doubleOrNull(json['wgs84Lng']),
        wgs84Lat: _doubleOrNull(json['wgs84Lat']),
      );

  final CoordinateSystem? coordinateSystem;

  /// 室内横坐标（归一化 0~1）。
  final double? xPct;

  /// 室内纵坐标（归一化 0~1）。
  final double? yPct;
  final int? floorNum;
  final int? posNum;

  /// BD-09 经度 / 纬度。
  final double? lng;
  final double? lat;

  /// WGS-84 经度 / 纬度。
  final double? wgs84Lng;
  final double? wgs84Lat;
}

/// 单个参演单元的实时状态（`situation.yaml#/UnitState`）。
///
/// **危险物（65533~65535）不作为单元下发**，故 [vestNum] 上限为 65532。
class UnitState {
  const UnitState({
    required this.vestNum,
    required this.status,
    required this.revision,
    this.name,
    this.team,
    this.combatRole,
    this.weaponKind,
    this.hp,
    this.ammo1,
    this.ammo2,
    this.position,
    this.motion,
    this.signalQuality,
    this.positionSource,
    this.lastHeartbeatAt,
    this.deadAt,
  });

  factory UnitState.fromJson(Map<String, dynamic> json) => UnitState(
        vestNum: json['vestNum'] as int,
        status: VestStatus.fromWire(json['status'] as String),
        revision: json['revision'] as int,
        name: json['name'] as String?,
        team: _enumOrNull(json['team'], Team.fromWire),
        combatRole: _enumOrNull(json['combatRole'], CombatRole.fromWire),
        weaponKind: _enumOrNull(json['weaponKind'], WeaponKind.fromWire),
        hp: json['hp'] as int?,
        ammo1: json['ammo1'] as int?,
        ammo2: json['ammo2'] as int?,
        position: _objectOrNull(json['position'], GeoPoint.fromJson),
        motion: _enumOrNull(json['motion'], MotionState.fromWire),
        signalQuality: json['signalQuality'] as int?,
        positionSource: _enumOrNull(json['positionSource'], PositionSource.fromWire),
        lastHeartbeatAt: _dateTimeOrNull(json['lastHeartbeatAt']),
        deadAt: _dateTimeOrNull(json['deadAt']),
      );

  /// 马甲编号（协议 uint16）。
  final int vestNum;
  final String? name;
  final Team? team;
  final CombatRole? combatRole;
  final WeaponKind? weaponKind;

  /// 在线判定见 [VestStatus]；**在线为心跳驱动，客户端不自行计时**。
  final VestStatus status;
  final int? hp;

  /// 主武器弹药 / 副武器弹药。
  final int? ammo1;
  final int? ammo2;

  /// 三态：在线且已定位 → 最后有效坐标；离线且从未定位 → `null`；阵亡 → 冻结在最后有效位置。
  final GeoPoint? position;
  final MotionState? motion;

  /// UWB 解算时的可用基站数（0~8）；小于 2 时坐标不可信。
  final int? signalQuality;
  final PositionSource? positionSource;
  final DateTime? lastHeartbeatAt;

  /// 阵亡时刻。
  final DateTime? deadAt;

  /// 该单元最后一次变化的全局 revision。
  final int revision;
}

/// 命中事件（`situation.yaml#/HitEvent`）。
class HitEvent {
  const HitEvent({
    required this.seq,
    required this.at,
    this.hitVestNum,
    this.shooterNum,
    this.shooterIsSoldier,
    this.shooterWeaponKind,
    this.hitPart,
    this.lethal,
    this.position,
  });

  factory HitEvent.fromJson(Map<String, dynamic> json) => HitEvent(
        seq: json['seq'] as int,
        at: DateTime.parse(json['at'] as String),
        hitVestNum: json['hitVestNum'] as int?,
        shooterNum: json['shooterNum'] as int?,
        shooterIsSoldier: json['shooterIsSoldier'] as bool?,
        shooterWeaponKind: _enumOrNull(json['shooterWeaponKind'], WeaponKind.fromWire),
        hitPart: _enumOrNull(json['hitPart'], HitPart.fromWire),
        lethal: json['lethal'] as bool?,
        position: _objectOrNull(json['position'], GeoPoint.fromJson),
      );

  /// 全局单调递增的命中序号，用于去重与增量拉取。
  final int seq;
  final DateTime at;

  /// 被击中者。
  final int? hitVestNum;

  /// 开枪者编号：<= 1800 为单兵，否则为武器 / 载具 / 危险物。
  final int? shooterNum;
  final bool? shooterIsSoldier;
  final WeaponKind? shooterWeaponKind;
  final HitPart? hitPart;

  /// 是否导致阵亡（服务端判定结果，前端不再自行计算）。
  final bool? lethal;

  /// 被击中者当时的坐标。
  final GeoPoint? position;
}

/// 队伍汇总（`situation.yaml#/TeamSummary`）。
class TeamSummary {
  const TeamSummary({
    required this.team,
    this.total,
    this.live,
    this.dead,
    this.offline,
    this.hitsDealt,
  });

  factory TeamSummary.fromJson(Map<String, dynamic> json) => TeamSummary(
        team: Team.fromWire(json['team'] as String),
        total: json['total'] as int?,
        live: json['live'] as int?,
        dead: json['dead'] as int?,
        offline: json['offline'] as int?,
        hitsDealt: json['hitsDealt'] as int?,
      );

  final Team team;
  final int? total;
  final int? live;
  final int? dead;
  final int? offline;
  final int? hitsDealt;
}

/// 态势信封（`situation.yaml#/SituationEnvelope`）——HTTP Poll 响应体与 WS 帧体**完全一致**。
class SituationEnvelope {
  const SituationEnvelope({
    required this.epoch,
    required this.revision,
    required this.full,
    required this.running,
    required this.serverTime,
    this.exerciseId,
    this.units = const <UnitState>[],
    this.changes = const <UnitState>[],
    this.removedVestNums = const <int>[],
    this.hits = const <HitEvent>[],
    this.teamSummary = const <TeamSummary>[],
  });

  factory SituationEnvelope.fromJson(Map<String, dynamic> json) => SituationEnvelope(
        epoch: json['epoch'] as String,
        revision: json['revision'] as int,
        full: json['full'] as bool,
        running: json['running'] as bool,
        serverTime: DateTime.parse(json['serverTime'] as String),
        exerciseId: json['exerciseId'] as String?,
        units: _listOrEmpty(json['units'], UnitState.fromJson),
        changes: _listOrEmpty(json['changes'], UnitState.fromJson),
        removedVestNums: _intList(json['removedVestNums']),
        hits: _listOrEmpty(json['hits'], HitEvent.fromJson),
        teamSummary: _listOrEmpty(json['teamSummary'], TeamSummary.fromJson),
      );

  /// 服务端进程代标识：**与本帧保存的 epoch 不一致即视为服务端已重启**，
  /// 本地 `sinceRevision` 一律作废，必须改不带 `sinceRevision` 取全量。
  final String epoch;

  /// 全局单调递增的态势版本号，客户端须保存并用于 `sinceRevision` 续传。
  final int revision;

  /// true=全量快照（[units] 有效）；false=增量（[changes] / [removedVestNums] 有效）。
  final bool full;

  /// 当前是否有演习进行中；false 时 [units] 为空数组。
  final bool running;
  final String? exerciseId;
  final DateTime serverTime;

  /// 全量快照（仅 full=true 时出现）。
  final List<UnitState> units;

  /// 增量变化（仅 full=false 时出现）。
  final List<UnitState> changes;

  /// 本增量中退场的马甲编号。
  final List<int> removedVestNums;

  /// 本增量 / 快照覆盖区间内新增的命中事件。
  final List<HitEvent> hits;
  final List<TeamSummary> teamSummary;
}

/// 命中流水分页（`GET /api/v1/situation/hits` 响应体）。
class HitEventPage {
  const HitEventPage({required this.items, this.latestSeq});

  factory HitEventPage.fromJson(Map<String, dynamic> json) => HitEventPage(
        items: _listOrEmpty(json['items'], HitEvent.fromJson),
        latestSeq: json['latestSeq'] as int?,
      );

  final List<HitEvent> items;

  /// 服务端当前最新命中序号，供下次增量拉取的 `sinceSeq`。
  final int? latestSeq;
}

T? _enumOrNull<T>(Object? value, T Function(String wire) parse) =>
    value == null ? null : parse(value as String);

T? _objectOrNull<T>(Object? value, T Function(Map<String, dynamic> json) parse) =>
    value == null ? null : parse(value as Map<String, dynamic>);

double? _doubleOrNull(Object? value) => (value as num?)?.toDouble();

DateTime? _dateTimeOrNull(Object? value) =>
    value == null ? null : DateTime.parse(value as String);

List<int> _intList(Object? value) => (value as List<dynamic>? ?? const <dynamic>[])
    .map((dynamic item) => item as int)
    .toList(growable: false);

List<T> _listOrEmpty<T>(Object? value, T Function(Map<String, dynamic> json) parse) =>
    (value as List<dynamic>? ?? const <dynamic>[])
        .map((dynamic item) => parse(item as Map<String, dynamic>))
        .toList(growable: false);
