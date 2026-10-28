import '../api/situation_models.dart';

/// 本地缓存保留的命中事件上限（对齐契约 `hitsLimit` 上限 500）。
const int kMaxCachedHits = 500;

/// 态势本地缓存与归并——**双通道同源归并**的状态机（M6 CLIENT-RESILIENCE 段 A）。
///
/// HTTP Poll 与 WebSocket 回的是**同一个 `SituationEnvelope`**（契约硬约束），
/// 两条通道谁先到都无所谓：[apply] 按 `epoch` + `revision` 归并成**一份**视图，
/// 陈旧 / 重复的帧被丢弃，不会用旧数据覆盖新状态。
///
/// 口径（依据 `docs/api/schemas/situation.yaml` 与 `paths/situation.yaml`）：
/// - **换代判定**：`epoch` 与本地不一致即视为服务端进程重启——清本地状态并置
///   [needsFullResync]，此后 [sinceRevision] 返回 `null`，调用方据此改拉全量；
/// - **全量帧**（`full=true`）：整体替换 [units]，[revision] 取帧值，清除待全量标志；
/// - **增量帧**（`full=false`）：仅在本地已对齐时可用；`revision <= 本地` 视为陈旧 /
///   重复（双通道同源必然出现），**丢弃**；否则 upsert [changes] 并按 [removedVestNums] 删除；
/// - **未对齐时**（尚未收到过全量）增量帧一律丢弃，避免在半截状态上叠加；
/// - **命中**按 `seq` 去重、升序保留最近 [kMaxCachedHits] 条；
/// - [teamSummary] 只保留**最近一次非空**（契约增量帧的 `teamSummary` 恒为空）。
///
/// 本类**不做网络、不认识令牌**：拉取与连接由调用方（双通道调度器）负责；
/// 换实例 / 退出登录时调用 [reset]，保证缓存**不跨会话泄漏**。
class SituationCache {
  String? _epoch;
  int _revision = 0;
  bool _running = false;
  String? _exerciseId;
  DateTime? _serverTime;
  final Map<int, UnitState> _units = <int, UnitState>{};
  final List<HitEvent> _hits = <HitEvent>[];
  List<TeamSummary> _teamSummary = const <TeamSummary>[];
  int _latestHitSeq = 0;
  bool _needsFullResync = true;
  int _appliedFrames = 0;
  int _discardedFrames = 0;

  /// 服务端进程代标识；从未收到帧时为 `null`。
  String? get epoch => _epoch;

  /// 本地已归并到的态势版本号；未对齐时为 0。
  int get revision => _revision;

  /// 当前是否有演习进行中（取自最近一次被采纳的帧）。
  bool get running => _running;

  String? get exerciseId => _exerciseId;
  DateTime? get serverTime => _serverTime;

  /// 是否必须改拉全量：服务端换代、本地 revision 过旧（`SITUATION_REVISION_TOO_OLD`）、
  /// 或尚未取得过全量时为 `true`。
  bool get needsFullResync => _needsFullResync;

  /// 续传参数：**未对齐时为 `null`**（调用方据此不带 `sinceRevision` 取全量）。
  int? get sinceRevision => _needsFullResync ? null : _revision;

  /// 当前参演单元（按 [UnitState.vestNum] 升序，只读快照）。
  List<UnitState> get units {
    final List<UnitState> list = _units.values.toList();
    list.sort((UnitState a, UnitState b) => a.vestNum.compareTo(b.vestNum));
    return List<UnitState>.unmodifiable(list);
  }

  /// 单元数量。
  int get unitCount => _units.length;

  /// 取单个单元；不存在回 `null`。
  UnitState? unit(int vestNum) => _units[vestNum];

  /// 已归并的命中事件（升序、去重、上限 [kMaxCachedHits]，只读快照）。
  List<HitEvent> get hits => List<HitEvent>.unmodifiable(_hits);

  /// 最近一次非空的队伍汇总；从未收到非空值时为**空列表**。
  List<TeamSummary> get teamSummary => _teamSummary;

  /// 最新命中序号，供命中面板增量拉取用的 `sinceSeq`。
  int get latestHitSeq => _latestHitSeq;

  /// 被采纳的帧数（现场自测时作「真的在归并」的证据）。
  int get appliedFrames => _appliedFrames;

  /// 被丢弃的帧数（陈旧 / 重复 / 未对齐）。
  int get discardedFrames => _discardedFrames;

  /// 归并一帧；返回是否被采纳（`false` 表示丢弃）。
  bool apply(SituationEnvelope envelope) {
    final bool epochChanged = _epoch != null && envelope.epoch != _epoch;
    if (epochChanged) {
      _clearState();
      _needsFullResync = true;
    }
    _epoch = envelope.epoch;

    if (envelope.full) {
      _units.clear();
      for (final UnitState unit in envelope.units) {
        _units[unit.vestNum] = unit;
      }
      _needsFullResync = false;
    } else {
      // 增量只在已对齐时可用：未对齐（等全量）或帧不比本地新（陈旧/重复）一律丢弃。
      if (_needsFullResync || envelope.revision <= _revision) {
        _discardedFrames++;
        return false;
      }
      for (final UnitState unit in envelope.changes) {
        _units[unit.vestNum] = unit;
      }
      for (final int vestNum in envelope.removedVestNums) {
        _units.remove(vestNum);
      }
    }

    _revision = envelope.revision;
    _running = envelope.running;
    _exerciseId = envelope.exerciseId;
    _serverTime = envelope.serverTime;
    _appendHits(envelope.hits);
    if (envelope.teamSummary.isNotEmpty) {
      _teamSummary = List<TeamSummary>.unmodifiable(envelope.teamSummary);
    }
    _appliedFrames++;
    return true;
  }

  /// 标记必须改拉全量（`409 SITUATION_REVISION_TOO_OLD` 或 WS close 4000 后调用）。
  void markFullResync() {
    _needsFullResync = true;
  }

  /// 清空一切（换实例 / 退出登录时调用，保证缓存不跨会话泄漏）。
  void reset() {
    _clearState();
    _epoch = null;
    _revision = 0;
    _running = false;
    _exerciseId = null;
    _serverTime = null;
    _latestHitSeq = 0;
    _needsFullResync = true;
    _appliedFrames = 0;
    _discardedFrames = 0;
  }

  void _clearState() {
    _units.clear();
    _hits.clear();
    _teamSummary = const <TeamSummary>[];
    _latestHitSeq = 0;
    _revision = 0;
    _running = false;
    _exerciseId = null;
  }

  void _appendHits(List<HitEvent> incoming) {
    if (incoming.isEmpty) {
      return;
    }
    final List<HitEvent> sorted = List<HitEvent>.of(incoming)
      ..sort((HitEvent a, HitEvent b) => a.seq.compareTo(b.seq));
    for (final HitEvent hit in sorted) {
      // 只收比已持有更新的序号：既去重，也不让快照回放的老命中搅乱顺序。
      if (hit.seq > _latestHitSeq) {
        _hits.add(hit);
        _latestHitSeq = hit.seq;
      }
    }
    if (_hits.length > kMaxCachedHits) {
      _hits.removeRange(0, _hits.length - kMaxCachedHits);
    }
  }
}
