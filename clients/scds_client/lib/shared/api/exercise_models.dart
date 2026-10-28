/// exercise 域契约模型（`docs/api/schemas/domain.yaml` + `situation.yaml` + `common.yaml`）。
///
/// 两半：
/// · **只读载荷**——当前演习状态与日志流水的解析；
/// · **写端点载荷**——想定（`Plan` / `PlanUpsertRequest`）、编成（`Roster` / `RosterEntry`）、
///   开演（`StartExerciseRequest`）与停演结果（`StopExerciseResult`）的构造与解析。
///   指挥指令（判死/活/伤/越界/充弹/配置）属 command 域，模型在 `command_models.dart`。
///
/// 解析口径与 `situation_models.dart` / `auth_models.dart` 一致：**不臆造缺省值**。
/// · 契约必填字段缺失 → `TypeError`；
/// · 形状不符 → `FormatException`；
/// · **未知枚举字面量 → `FormatException`**（宁可显式失败，也不静默落到某个默认分支）；
/// · 契约中可空的字段（无 `required`）→ nullable。
///
/// **请求体口径分两类，不可混淆**（见 `docs/api/paths/exercise.yaml`）：
/// · **整体替换**（`PlanUpsertRequest`）：`null` 就是「置空」，故显式输出全部键；
/// · **可选缺省**（`RosterEntry` / `StartExerciseRequest`）：缺席由服务端补默认值，故只发非 null 键。
library;

import 'command_models.dart' show VestMode;
import 'situation_models.dart' show Team;

/// 演习模式（`domain.yaml#/Exercise.battleMode`）。
enum BattleMode {
  train('TRAIN'),
  battle('BATTLE');

  const BattleMode(this.wire);

  /// 契约字面量。
  final String wire;

  static BattleMode fromWire(String wire) => values.firstWhere(
        (BattleMode value) => value.wire == wire,
        orElse: () => throw FormatException('未知 BattleMode 字面量: $wire'),
      );
}

/// 演习结束原因（`domain.yaml#/Exercise.endReason`）。
///
/// [crashRecovered] 只会出现在启动自愈补写的场次里（其终止时刻是进程重启后的**发现时刻**，
/// 不是中断时刻）。进行中的场次该字段为 `null`。
///
/// 注：当前服务端 `Exercise` DTO 尚未下发 `endReason`（契约有、实现未发），故实际解析结果恒为 `null`；
/// 这里保留该字段以对齐契约，**不得据其臆造默认值**。
enum ExerciseEndReason {
  normal('NORMAL'),
  crashRecovered('CRASH_RECOVERED');

  const ExerciseEndReason(this.wire);

  final String wire;

  static ExerciseEndReason fromWire(String wire) => values.firstWhere(
        (ExerciseEndReason value) => value.wire == wire,
        orElse: () => throw FormatException('未知 ExerciseEndReason 字面量: $wire'),
      );
}

/// 演习日志条目类型（`common.yaml#/LogType`）。
///
/// 前 6 个对应 `scds-protocol` 的**冻结**协议帧 `LogType`（带字节 code，不得增删）；
/// [system] 是服务端自造的系统事件（如实例重启后自愈收尾的残留指令存证），
/// **不对应任何协议帧**，可能既无 `vestNum` 也无 `downlinkHex`——客户端应按系统提示样式展示，
/// 不可假设其必有马甲编号。
enum ExerciseLogType {
  startBattle('START_BATTLE'),
  shot('SHOT'),
  die('DIE'),
  live('LIVE'),
  injure('INJURE'),
  reload('RELOAD'),
  system('SYSTEM');

  const ExerciseLogType(this.wire);

  final String wire;

  static ExerciseLogType fromWire(String wire) => values.firstWhere(
        (ExerciseLogType value) => value.wire == wire,
        orElse: () => throw FormatException('未知 ExerciseLogType 字面量: $wire'),
      );
}

/// 当前/最近一场演习状态（`domain.yaml#/Exercise`）。
///
/// **无演习进行中时是 HTTP 200 + [running]=false（不是 404）**：此时除 [running] 外其余业务字段
/// 一律缺席（契约 D15-⑥：不得编造场次 ID 或时间）。调用方**必须先判 [running]** 再解引用
/// [exerciseId] / [planId] / [startedAt] 等。
class Exercise {
  const Exercise({
    required this.running,
    this.exerciseId,
    this.planId,
    this.planName,
    this.mapId,
    this.battleMode,
    this.startedAt,
    this.endedAt,
    this.elapsedSeconds,
    this.rosterCount,
    this.onlineCount,
    this.endReason,
  });

  factory Exercise.fromJson(Map<String, dynamic> json) => Exercise(
        running: json['running'] as bool,
        exerciseId: json['exerciseId'] as String?,
        planId: json['planId'] as String?,
        planName: json['planName'] as String?,
        mapId: json['mapId'] as String?,
        battleMode: _enumOrNull(json['battleMode'], BattleMode.fromWire),
        startedAt: _dateTimeOrNull(json['startedAt']),
        endedAt: _dateTimeOrNull(json['endedAt']),
        elapsedSeconds: json['elapsedSeconds'] as int?,
        rosterCount: json['rosterCount'] as int?,
        onlineCount: json['onlineCount'] as int?,
        endReason: _enumOrNull(json['endReason'], ExerciseEndReason.fromWire),
      );

  /// 是否进行中；false 时其余业务字段一律为 `null`。
  final bool running;
  final String? exerciseId;
  final String? planId;

  /// 想定名快照。
  final String? planName;

  /// 底图快照，可为 `null`。
  final String? mapId;
  final BattleMode? battleMode;
  final DateTime? startedAt;

  /// 结束时刻；进行中为 `null`。
  final DateTime? endedAt;

  /// 已进行秒数；进行中才有值。
  final int? elapsedSeconds;

  /// 参演人数。
  final int? rosterCount;

  /// 心跳在 5000ms 窗口内的马甲数（在线为心跳驱动，客户端不重算）。
  final int? onlineCount;

  /// 本场结束原因；进行中为 `null`。见 [ExerciseEndReason] 的「实现未下发」说明。
  final ExerciseEndReason? endReason;
}

/// 演习日志流水条目（`situation.yaml#/ExerciseLogEntry`）——指挥台右侧滚动日志。
class ExerciseLogEntry {
  const ExerciseLogEntry({
    required this.seq,
    required this.at,
    required this.type,
    this.vestNum,
    this.operator,
    this.detail,
    this.downlinkHex,
  });

  factory ExerciseLogEntry.fromJson(Map<String, dynamic> json) => ExerciseLogEntry(
        seq: json['seq'] as int,
        at: DateTime.parse(json['at'] as String),
        type: ExerciseLogType.fromWire(json['type'] as String),
        vestNum: json['vestNum'] as int?,
        operator: json['operator'] as String?,
        detail: json['detail'] as String?,
        downlinkHex: json['downlinkHex'] as String?,
      );

  /// 全局单调递增的序号；客户端以其做增量拉取（下次请求作 `sinceSeq`）。
  final int seq;
  final DateTime at;
  final ExerciseLogType type;

  /// 关联马甲编号；系统级条目（[ExerciseLogType.system]）为 `null`。
  final int? vestNum;

  /// 手动下发的操作者账号；协议自动产生的为 `null`。
  final String? operator;
  final String? detail;

  /// 对应下发指令的十六进制原文，逐字节可核对（排障用）。
  final String? downlinkHex;
}

/// 当前演习日志流水的**增量**响应
/// （`paths/exercise.yaml` → `listCurrentExerciseLogs` 的内联对象，契约未给它起名）。
///
/// 与命中流水的 `HitEventPage` 同形但语义不同：这里的 [latestSeq] 是「本批最大 seq」，
/// 客户端下次原样作为 `sinceSeq` 续拉——一个字段就能去重与续拉，**不需要 total**。
class ExerciseLogPage {
  const ExerciseLogPage({required this.items, this.latestSeq});

  factory ExerciseLogPage.fromJson(Map<String, dynamic> json) => ExerciseLogPage(
        items: _listOrEmpty(json['items'], ExerciseLogEntry.fromJson),
        latestSeq: json['latestSeq'] as int?,
      );

  /// 日志条目（按 seq 升序）。
  final List<ExerciseLogEntry> items;

  /// 本批最大 seq，客户端下次请求作为 `sinceSeq`；无条目时为 `null`。
  final int? latestSeq;
}

/// 分页元信息（`common.yaml#/PageMeta`）。必填：`page` / `size` / `total`。
class PageMeta {
  const PageMeta({required this.page, required this.size, required this.total});

  factory PageMeta.fromJson(Map<String, dynamic> json) => PageMeta(
        page: json['page'] as int,
        size: json['size'] as int,
        total: json['total'] as int,
      );

  /// 页码，从 1 开始。
  final int page;
  final int size;
  final int total;
}

/// 想定（`domain.yaml#/Plan`）——`listPlans` / `getPlan` / `createPlan` / `updatePlan` 的载荷。
///
/// 必填仅 [planId] / [name] / [createdAt]；其余（[mapId] / [battleMode] / [remark] /
/// [rosterCount] / [updatedAt]）契约未置 `required`，故为 nullable。
class Plan {
  const Plan({
    required this.planId,
    required this.name,
    required this.createdAt,
    this.mapId,
    this.battleMode,
    this.remark,
    this.rosterCount,
    this.updatedAt,
  });

  factory Plan.fromJson(Map<String, dynamic> json) => Plan(
        planId: json['planId'] as String,
        name: json['name'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
        mapId: json['mapId'] as String?,
        battleMode: _enumOrNull(json['battleMode'], BattleMode.fromWire),
        remark: json['remark'] as String?,
        rosterCount: json['rosterCount'] as int?,
        updatedAt: _dateTimeOrNull(json['updatedAt']),
      );

  final String planId;
  final String name;

  /// 绑定的底图（室内 UWB 或室外 GPS）；未绑定为 `null`。
  final String? mapId;
  final BattleMode? battleMode;
  final String? remark;

  /// 编成人数。
  final int? rosterCount;
  final DateTime createdAt;
  final DateTime? updatedAt;
}

/// 想定分页结果（`domain.yaml#/PlanPage`）。
class PlanPage {
  const PlanPage({required this.items, required this.meta});

  factory PlanPage.fromJson(Map<String, dynamic> json) => PlanPage(
        items: _listOrEmpty(json['items'], Plan.fromJson),
        meta: PageMeta.fromJson(json['meta'] as Map<String, dynamic>),
      );

  final List<Plan> items;
  final PageMeta meta;
}

/// 新建 / 修改想定的请求体（`domain.yaml#/PlanUpsertRequest`）。必填：[name]（1~128 字符）。
///
/// **PUT 是整体替换**：服务端 `ExerciseController#draft` 把缺席字段一律当 `null` 落库，
/// 故 [toJson] **显式输出全部四个键**（`null` 也发）——「解绑底图」就是把 `mapId: null` 发出去。
/// 这与 `VestConfigRequest` 的「只发非 null、缺席即保持原值」是**相反**的口径，不可混淆。
/// [battleMode] 缺席时服务端按契约默认 `TRAIN`。
class PlanUpsertRequest {
  const PlanUpsertRequest({required this.name, this.mapId, this.battleMode, this.remark});

  final String name;
  final String? mapId;
  final BattleMode? battleMode;
  final String? remark;

  Map<String, Object?> toJson() => <String, Object?>{
        'name': name,
        'mapId': mapId,
        'battleMode': battleMode?.wire,
        'remark': remark,
      };
}

/// 编成单条（`domain.yaml#/RosterEntry`）。必填：[vestNum] / [team]。
///
/// [hp] / [ammo1] / [ammo2] / [mode] 缺席时服务端补契约默认（100 / 30 / 30 / AUTO），
/// 故 [toJson] **只发非 null 键**，不臆造默认值。
class RosterEntry {
  const RosterEntry({
    required this.vestNum,
    required this.team,
    this.name,
    this.hp,
    this.ammo1,
    this.ammo2,
    this.mode,
  });

  factory RosterEntry.fromJson(Map<String, dynamic> json) => RosterEntry(
        vestNum: json['vestNum'] as int,
        team: Team.fromWire(json['team'] as String),
        name: json['name'] as String?,
        hp: json['hp'] as int?,
        ammo1: json['ammo1'] as int?,
        ammo2: json['ammo2'] as int?,
        mode: _enumOrNull(json['mode'], VestMode.fromWire),
      );

  /// 引用设备台账里的马甲编号。
  final int vestNum;
  final Team team;
  final String? name;

  /// 开演初始生命值，0..255（上限由 14B 轮询配置帧的 1 字节承载决定）。
  final int? hp;

  /// 主武器弹药，0..65535（上限由 14B 帧的 2 字节大端承载决定）。
  final int? ammo1;
  final int? ammo2;

  /// 轮询模式，与 command 域覆盖配置共用 [VestMode]。
  final VestMode? mode;

  Map<String, Object?> toJson() {
    final Map<String, Object?> json = <String, Object?>{
      'vestNum': vestNum,
      'team': team.wire,
    };
    if (name != null) {
      json['name'] = name;
    }
    if (hp != null) {
      json['hp'] = hp;
    }
    if (ammo1 != null) {
      json['ammo1'] = ammo1;
    }
    if (ammo2 != null) {
      json['ammo2'] = ammo2;
    }
    if (mode != null) {
      json['mode'] = mode!.wire;
    }
    return json;
  }
}

/// 想定编成（`domain.yaml#/Roster`）。必填：[planId] / [entries]。
///
/// **整体替换、幂等**：PUT 之后服务端存的就是这一份清单；空列表即清空（合法）。
/// 服务端校验编号存在于设备台账、不重复、不跨队，任一不满足回 400 `VALIDATION_FAILED`，
/// **不产生部分写入**。
class Roster {
  const Roster({required this.planId, required this.entries});

  factory Roster.fromJson(Map<String, dynamic> json) => Roster(
        planId: json['planId'] as String,
        entries: _listOrEmpty(json['entries'], RosterEntry.fromJson),
      );

  final String planId;
  final List<RosterEntry> entries;

  Map<String, Object?> toJson() => <String, Object?>{
        'planId': planId,
        'entries': entries
            .map((RosterEntry entry) => entry.toJson())
            .toList(growable: false),
      };
}

/// 开始演习请求体（`domain.yaml#/StartExerciseRequest`）。必填：[planId]。
///
/// [battleMode] 缺席时服务端沿用想定里的模式，故 [toJson] 只发非 null 键。
class StartExerciseRequest {
  const StartExerciseRequest({required this.planId, this.battleMode});

  final String planId;
  final BattleMode? battleMode;

  Map<String, Object?> toJson() {
    final Map<String, Object?> json = <String, Object?>{'planId': planId};
    if (battleMode != null) {
      json['battleMode'] = battleMode!.wire;
    }
    return json;
  }
}

/// 停演结果（`paths/exercise.yaml#/exerciseCurrent.delete` 的内联对象，契约未给它起名）。
///
/// [replayId] 即本场 `exerciseId`（`t_exercise` 一行两用），可直接跳转复盘；
/// 走本端点停演 [endReason] 恒为 [ExerciseEndReason.normal]，[ExerciseEndReason.crashRecovered]
/// 只会出现在启动自愈补写的场次里。
class StopExerciseResult {
  const StopExerciseResult({
    required this.exerciseId,
    required this.endedAt,
    this.replayId,
    this.endReason,
  });

  factory StopExerciseResult.fromJson(Map<String, dynamic> json) => StopExerciseResult(
        exerciseId: json['exerciseId'] as String,
        endedAt: DateTime.parse(json['endedAt'] as String),
        replayId: json['replayId'] as String?,
        endReason: _enumOrNull(json['endReason'], ExerciseEndReason.fromWire),
      );

  final String exerciseId;
  final DateTime endedAt;
  final String? replayId;
  final ExerciseEndReason? endReason;
}

/// 想定导入模板格式（`paths/exercise.yaml#/planTemplate` 的 `format` 查询参数）。
enum PlanTemplateFormat {
  xlsx('XLSX'),
  csv('CSV');

  const PlanTemplateFormat(this.wire);

  /// 契约字面量。
  final String wire;
}

T? _enumOrNull<T>(Object? value, T Function(String wire) parse) =>
    value == null ? null : parse(value as String);

DateTime? _dateTimeOrNull(Object? value) =>
    value == null ? null : DateTime.parse(value as String);

List<T> _listOrEmpty<T>(Object? value, T Function(Map<String, dynamic> json) parse) =>
    (value as List<dynamic>? ?? const <dynamic>[])
        .map((dynamic item) => parse(item as Map<String, dynamic>))
        .toList(growable: false);
