/// replay 域契约模型（`docs/api/schemas/domain.yaml` 的 `Replay*` / `TrackSample` /
/// `TimelineChunk` / `ExportRequest`，以及 `common.yaml` 的 `AsyncTask`）。
///
/// 三块：
/// · **复盘元信息**——`Replay`（概览，不含轨迹）与 `ReplayPage`（列表分页）；
/// · **轨迹与统计**——`TrackSample` / `TimelineChunk`（cursor 分片）、`ReplayStatistics`；
/// · **异步导出**——`ExportRequest`（发起）/ `AsyncTask`（进度与产物）。
///
/// 解析口径与 `map_models.dart` / `exercise_models.dart` 一致：**不臆造缺省值**。
/// · 契约必填字段缺失 → `TypeError`；形状不符 → `FormatException`；
/// · **未知枚举字面量 → `FormatException`**（宁可显式失败，也不静默落到某个默认分支）；
/// · 契约中可空的字段（无 `required`）→ nullable。
///
/// 已复用的他域类型：分页元信息 [PageMeta]、演习模式 [BattleMode]（与 `exercise_models.dart` 同源），
/// 队伍汇总 [TeamSummary]、马甲状态 [VestStatus]、命中部位 [HitPart]（与 `situation_models.dart` 同源）
/// ——契约里本就是同一份 `common.yaml` / `situation.yaml` 定义，**不重复造第二份**。
///
/// 时间轴口径：`TrackSample.t` 与 `ReplayStatistics.TimelineBucket.t` 都是
/// **相对演习开始的毫秒偏移**，不是绝对时刻——客户端做插值、变速播放、进度条定位只依赖整型 `t`，
/// 跨时区不会错位，故**不得**在此加回 `startedAt`。
library;

import 'exercise_models.dart' show BattleMode, PageMeta;
import 'situation_models.dart' show HitPart, TeamSummary, VestStatus;

/// 复盘导出文件格式（`domain.yaml#/ExportRequest.format`）。
///
/// 与想定模板下载的 `PlanTemplateFormat` 字面量相同但**语义不同**（一个是导入模板、一个是导出报表），
/// 契约上是两个独立 schema，故各自声明、不复用。
enum ExportFormat {
  xlsx('XLSX'),
  csv('CSV');

  const ExportFormat(this.wire);

  /// 契约字面量。
  final String wire;

  static ExportFormat fromWire(String wire) => values.firstWhere(
        (ExportFormat value) => value.wire == wire,
        orElse: () => throw FormatException('未知 ExportFormat 字面量: $wire'),
      );
}

/// 异步任务状态机（`common.yaml#/AsyncTaskState`）。
enum AsyncTaskState {
  pending('PENDING'),
  running('RUNNING'),
  succeeded('SUCCEEDED'),
  failed('FAILED');

  const AsyncTaskState(this.wire);

  /// 契约字面量。
  final String wire;

  static AsyncTaskState fromWire(String wire) => values.firstWhere(
        (AsyncTaskState value) => value.wire == wire,
        orElse: () => throw FormatException('未知 AsyncTaskState 字面量: $wire'),
      );
}

/// 复盘概览（`domain.yaml#/Replay`）——**不含轨迹**，轨迹走 `TimelineChunk`。
///
/// 必填仅 [replayId] / [planName] / [startedAt]；其余（含 [battleMode] / [durationSeconds] /
/// [participantCount] / [hitCount] / [deadCount]）契约未置 `required`，故为 nullable。
///
/// [replayId] 与演习编号同值（停演时把本场态势落库为一条同 ID 的复盘记录），服务端无独立 replay 表。
class Replay {
  const Replay({
    required this.replayId,
    required this.planName,
    required this.startedAt,
    this.planId,
    this.mapId,
    this.battleMode,
    this.endedAt,
    this.durationSeconds,
    this.participantCount,
    this.hitCount,
    this.deadCount,
  });

  factory Replay.fromJson(Map<String, dynamic> json) => Replay(
        replayId: json['replayId'] as String,
        planName: json['planName'] as String,
        startedAt: DateTime.parse(json['startedAt'] as String),
        planId: json['planId'] as String?,
        mapId: json['mapId'] as String?,
        battleMode: _enumOrNull(json['battleMode'], BattleMode.fromWire),
        endedAt: _dateTimeOrNull(json['endedAt']),
        durationSeconds: json['durationSeconds'] as int?,
        participantCount: json['participantCount'] as int?,
        hitCount: json['hitCount'] as int?,
        deadCount: json['deadCount'] as int?,
      );

  /// 复盘编号（与演习编号同值）。
  final String replayId;
  final String? planId;

  /// 想定名——列表按此模糊搜索。
  final String planName;
  final String? mapId;
  final BattleMode? battleMode;
  final DateTime startedAt;

  /// 结束时刻；未正常停演时为 `null`。
  final DateTime? endedAt;

  /// 演习时长（秒）——复盘是**既成事实**，同一记录反复读到的值必须一致，
  /// 故服务端取 `endedAt - startedAt` 的定值，不像「当前演习」那样用 `now()` 清零头。
  final int? durationSeconds;
  final int? participantCount;
  final int? hitCount;
  final int? deadCount;
}

/// 复盘分页结果（`domain.yaml#/ReplayPage`）。
///
/// 分页元信息复用 `exercise_models.dart` 的 [PageMeta]（同为 `common.yaml#/PageMeta`）。
class ReplayPage {
  const ReplayPage({required this.items, required this.meta});

  factory ReplayPage.fromJson(Map<String, dynamic> json) => ReplayPage(
        items: _listOrEmpty(json['items'], Replay.fromJson),
        meta: PageMeta.fromJson(json['meta'] as Map<String, dynamic>),
      );

  final List<Replay> items;
  final PageMeta meta;
}

/// 一个轨迹采样点（`domain.yaml#/TrackSample`）。
///
/// 坐标按契约**平铺**为室内（[xPct] / [yPct]）与室外（[lng] / [lat]）两组：
/// 库里哪一套有效就只写哪一套，另一套本就是 `null`；Y 轴映射已在**写入前**完成，
/// 客户端**不得**自行换算或翻转。
class TrackSample {
  const TrackSample({
    required this.t,
    required this.vestNum,
    this.xPct,
    this.yPct,
    this.lng,
    this.lat,
    this.hp,
    this.status,
  });

  factory TrackSample.fromJson(Map<String, dynamic> json) => TrackSample(
        t: json['t'] as int,
        vestNum: json['vestNum'] as int,
        xPct: (json['xPct'] as num?)?.toDouble(),
        yPct: (json['yPct'] as num?)?.toDouble(),
        lng: (json['lng'] as num?)?.toDouble(),
        lat: (json['lat'] as num?)?.toDouble(),
        hp: json['hp'] as int?,
        status: _enumOrNull(json['status'], VestStatus.fromWire),
      );

  /// 相对演习开始的**毫秒偏移**（时间轴唯一依据，不是绝对时刻）。
  final int t;
  final int vestNum;

  /// 室内归一化横 / 纵坐标 0~1（百分比口径，与服务端 `FloorPoint` 同源）。
  final double? xPct;
  final double? yPct;

  /// BD-09 经纬度（室外口径）。
  final double? lng;
  final double? lat;

  /// 当前血量；未知为 `null`。
  final int? hp;
  final VestStatus? status;
}

/// 轨迹回放分片（`domain.yaml#/TimelineChunk`）。
///
/// 按 [nextCursor] **连续拉取**，[nextCursor] 为 `null` 表示已到末尾。
/// 客户端应**边拉边播**，不要一次性拉全量（一场演习的采样点可达数十万条）。
class TimelineChunk {
  const TimelineChunk({required this.samples, this.nextCursor});

  factory TimelineChunk.fromJson(Map<String, dynamic> json) => TimelineChunk(
        samples: _listOrEmpty(json['samples'], TrackSample.fromJson),
        nextCursor: json['nextCursor'] as String?,
      );

  final List<TrackSample> samples;

  /// 下一片游标；`null` 即已到末尾。
  final String? nextCursor;
}

/// 复盘统计（`domain.yaml#/ReplayStatistics`）。必填仅 [replayId]。
///
/// 对应旧系统复盘页的统计面板；三个集合契约未置 `required`，缺失即空列表。
class ReplayStatistics {
  const ReplayStatistics({
    required this.replayId,
    this.teams = const <TeamSummary>[],
    this.topShooters = const <TopShooter>[],
    this.hitPartDistribution = const <HitPartCount>[],
    this.timeline = const <TimelineBucket>[],
  });

  factory ReplayStatistics.fromJson(Map<String, dynamic> json) => ReplayStatistics(
        replayId: json['replayId'] as String,
        teams: _listOrEmpty(json['teams'], TeamSummary.fromJson),
        topShooters: _listOrEmpty(json['topShooters'], TopShooter.fromJson),
        hitPartDistribution: _listOrEmpty(json['hitPartDistribution'], HitPartCount.fromJson),
        timeline: _listOrEmpty(json['timeline'], TimelineBucket.fromJson),
      );

  final String replayId;

  /// 队伍战损汇总，复用 `situation.yaml#/TeamSummary`。
  final List<TeamSummary> teams;

  /// 命中排行。
  final List<TopShooter> topShooters;

  /// 命中部位分布。
  final List<HitPartCount> hitPartDistribution;

  /// 时间轴上的存活人数曲线。
  final List<TimelineBucket> timeline;
}

/// 命中排行条目（`ReplayStatistics.topShooters[]`，契约内联对象，字段皆非必填）。
class TopShooter {
  const TopShooter({this.vestNum, this.name, this.hitsDealt, this.kills});

  factory TopShooter.fromJson(Map<String, dynamic> json) => TopShooter(
        vestNum: json['vestNum'] as int?,
        name: json['name'] as String?,
        hitsDealt: json['hitsDealt'] as int?,
        kills: json['kills'] as int?,
      );

  final int? vestNum;

  /// 未登记时为 `null`。
  final String? name;

  /// 命中他方次数。
  final int? hitsDealt;
  final int? kills;
}

/// 命中部位分布条目（`ReplayStatistics.hitPartDistribution[]`，字段皆非必填）。
class HitPartCount {
  const HitPartCount({this.hitPart, this.count});

  factory HitPartCount.fromJson(Map<String, dynamic> json) => HitPartCount(
        hitPart: _enumOrNull(json['hitPart'], HitPart.fromWire),
        count: json['count'] as int?,
      );

  final HitPart? hitPart;
  final int? count;
}

/// 存活人数曲线采样段（`ReplayStatistics.timeline[]`，字段皆非必填）。
class TimelineBucket {
  const TimelineBucket({this.t, this.liveByTeam});

  factory TimelineBucket.fromJson(Map<String, dynamic> json) => TimelineBucket(
        t: json['t'] as int?,
        liveByTeam: _intMapOrNull(json['liveByTeam']),
      );

  /// 相对演习开始的毫秒偏移。
  final int? t;

  /// 各队存活人数，键为队伍名（`RED` / `BLUE` / …）。
  final Map<String, int>? liveByTeam;
}

/// 复盘导出请求（`domain.yaml#/ExportRequest`）。必填仅 [format]。
class ExportRequest {
  const ExportRequest({required this.format});

  final ExportFormat format;

  Map<String, Object?> toJson() => <String, Object?>{'format': format.wire};
}

/// 异步任务（`common.yaml#/AsyncTask`）——导出受理 / 轮询的载荷。必填：[taskId] / [state]。
///
/// 导出是**异步**的：`POST .../exports` 固定回 202（要么新建 `PENDING`、要么复用同一复盘同格式的
/// 活跃任务，对客户端都是「已受理，去轮询」），进度靠本类型轮询。
class AsyncTask {
  const AsyncTask({
    required this.taskId,
    required this.state,
    this.progressPct,
    this.fileName,
    this.downloadUrl,
    this.expireAt,
    this.errorMessage,
  });

  factory AsyncTask.fromJson(Map<String, dynamic> json) => AsyncTask(
        taskId: json['taskId'] as String,
        state: AsyncTaskState.fromWire(json['state'] as String),
        progressPct: json['progressPct'] as int?,
        fileName: json['fileName'] as String?,
        downloadUrl: json['downloadUrl'] as String?,
        expireAt: _dateTimeOrNull(json['expireAt']),
        errorMessage: json['errorMessage'] as String?,
      );

  final String taskId;
  final AsyncTaskState state;

  /// 进度百分比 0~100。
  final int? progressPct;
  final String? fileName;

  /// **短时效（默认 15 分钟）可重复**签名 URL，**根相对路径、不带 `Authorization`**，
  /// 可被浏览器直接拉取；仅 `state=SUCCEEDED` 时有效，每次查询由服务端**重新签发**。
  ///
  /// 到期以 [expireAt] 为准，过期后需重新发起导出——**不要跨会话缓存**。
  /// 走 `Bearer` 的等价路径见 `ReplayApi.downloadExport`。
  final String? downloadUrl;

  /// 产物有效期。
  final DateTime? expireAt;

  /// 失败原因说明；无则 `null`。
  final String? errorMessage;
}

T? _enumOrNull<T>(Object? value, T Function(String wire) parse) =>
    value == null ? null : parse(value as String);

DateTime? _dateTimeOrNull(Object? value) =>
    value == null ? null : DateTime.parse(value as String);

Map<String, int>? _intMapOrNull(Object? value) {
  if (value == null) {
    return null;
  }
  return (value as Map<String, dynamic>)
      .map((String key, dynamic count) => MapEntry<String, int>(key, count as int));
}

List<T> _listOrEmpty<T>(Object? value, T Function(Map<String, dynamic> json) parse) =>
    (value as List<dynamic>? ?? const <dynamic>[])
        .map((dynamic item) => parse(item as Map<String, dynamic>))
        .toList(growable: false);
