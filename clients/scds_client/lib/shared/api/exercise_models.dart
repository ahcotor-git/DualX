/// exercise 域契约模型（`docs/api/schemas/domain.yaml` + `situation.yaml` + `common.yaml`）。
///
/// 本文件只做**解析**：当前演习状态与日志流水两条只读载荷的类型。
/// 指挥指令（判死/活/伤/越界/充弹/配置）是写操作，模型另行落地。
///
/// 解析口径与 `situation_models.dart` / `auth_models.dart` 一致：**不臆造缺省值**。
/// · 契约必填字段缺失 → `TypeError`；
/// · 形状不符 → `FormatException`；
/// · **未知枚举字面量 → `FormatException`**（宁可显式失败，也不静默落到某个默认分支）；
/// · 契约中可空的字段（无 `required`）→ nullable。
library;

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

T? _enumOrNull<T>(Object? value, T Function(String wire) parse) =>
    value == null ? null : parse(value as String);

DateTime? _dateTimeOrNull(Object? value) =>
    value == null ? null : DateTime.parse(value as String);

List<T> _listOrEmpty<T>(Object? value, T Function(Map<String, dynamic> json) parse) =>
    (value as List<dynamic>? ?? const <dynamic>[])
        .map((dynamic item) => parse(item as Map<String, dynamic>))
        .toList(growable: false);
