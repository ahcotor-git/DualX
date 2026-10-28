import 'dart:async';

import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/situation_models.dart';
import '../../shared/auth/session_store.dart';

/// 供测试与后续集成定位的控件键。
const Key kSituationVestNumKey = Key('situation.vestNum');
const Key kSituationStatusKey = Key('situation.status');
const Key kSituationHpKey = Key('situation.hp');
const Key kSituationAmmoKey = Key('situation.ammo');
const Key kSituationPositionKey = Key('situation.position');
const Key kSituationMotionKey = Key('situation.motion');
const Key kSituationHeartbeatKey = Key('situation.heartbeat');
const Key kSituationRefreshCountKey = Key('situation.refreshCount');
const Key kSituationErrorKey = Key('situation.error');

/// 自身态势 HTTP 轮询周期（段 4c 裁定 1000ms）。
///
/// **不要**与服务端→马甲的**串口** 200ms 轮询混同：那是硬件协议周期，由 `scds-gateway`
/// 按冻结常量执行，与客户端无关。契约只要求客户端保持「不超过 10s」的兜底轮询，
/// 本值取 1s 作单兵自身状态的观感默认，可注入覆盖。
const Duration kSituationPollInterval = Duration(seconds: 1);

/// 自身态势轮询器：按固定周期拉取**自身**马甲的实时状态。
///
/// 零依赖手写（`Timer.periodic`），口径：
/// - **至多一个在途请求**：上一拍未回则跳过本拍，慢网络下不叠加请求；
/// - 一次失败**不清空**上次成功数据，只置 [lastError]——现场观感是「数据陈旧 + 明示失败」，
///   比清空成空白更可判读；
/// - 不做本地 revision 归并、不连统一态势 WS（`/ws/v1/situation` 只认账号令牌，
///   单兵端按设计 03 §4.7 不接）。
class SituationPoller extends ChangeNotifier {
  SituationPoller({
    required this.fetch,
    this.interval = kSituationPollInterval,
  });

  /// 单次拉取（生产接线见 `main.dart`：`SituationApi.getVestState`）。
  final Future<UnitState> Function() fetch;

  /// 轮询周期；默认 [kSituationPollInterval]。
  final Duration interval;

  Timer? _timer;
  bool _inFlight = false;
  bool _disposed = false;

  /// 最近一次成功拉取到的自身状态；从未成功过则为 null。
  UnitState? latest;

  /// 最近一次失败的可读描述；成功后清空。
  String? lastError;

  /// 成功拉取次数（现场自测时可作「是否真的在轮询」的证据）。
  int successCount = 0;

  /// 是否处于轮询中。
  bool get isPolling => _timer != null;

  /// 立刻拉一次，随后按 [interval] 周期轮询；重复调用无副作用。
  void start() {
    if (_timer != null) {
      return;
    }
    _timer = Timer.periodic(interval, (Timer _) => _tick());
    _tick();
  }

  /// 停止轮询（可再次 [start]）。
  void stop() {
    _timer?.cancel();
    _timer = null;
    if (!_disposed) {
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _timer?.cancel();
    _timer = null;
    super.dispose();
  }

  Future<void> _tick() async {
    if (_inFlight) {
      return;
    }
    _inFlight = true;
    try {
      final UnitState state = await fetch();
      if (_disposed) {
        return;
      }
      latest = state;
      lastError = null;
      successCount++;
      notifyListeners();
    } on ApiException catch (failure) {
      if (_disposed) {
        return;
      }
      lastError = failure.code == ApiErrorCode.deviceVestNotFound
          ? '本场编成中没有该马甲（${ApiErrorCode.deviceVestNotFound}）'
          : '拉取失败：${failure.code}';
      notifyListeners();
    } catch (error) {
      // 网络不可达等非契约异常：必须变成可读状态，不能冒泡成红屏。
      if (_disposed) {
        return;
      }
      lastError = '拉取失败：$error';
      notifyListeners();
    } finally {
      _inFlight = false;
    }
  }
}

/// 自身态势页（M6 CLIENT-BASE 段 4c）。
///
/// 只读展示 [`getVestState`]（`/api/v1/situation/vests/{vestNum}`）的自身状态；
/// 后端按令牌 `sub` 裁剪范围，单兵令牌见不到其他马甲（`DeviceScope.allowSelf`）。
class SituationPage extends StatefulWidget {
  const SituationPage({
    super.key,
    required this.session,
    required this.fetch,
    this.interval = kSituationPollInterval,
  });

  /// 已登录的设备会话（提供马甲编号与设备令牌）。
  final DeviceSession session;

  /// 单次拉取（生产接线见 `main.dart`：`SituationApi.getVestState`）。
  final Future<UnitState> Function() fetch;

  /// 轮询周期；默认 [kSituationPollInterval]。
  final Duration interval;

  @override
  State<SituationPage> createState() => _SituationPageState();
}

class _SituationPageState extends State<SituationPage> {
  late final SituationPoller _poller;

  @override
  void initState() {
    super.initState();
    _poller = SituationPoller(fetch: widget.fetch, interval: widget.interval)..start();
  }

  @override
  void dispose() {
    _poller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('自身态势')),
      body: SafeArea(
        child: ListenableBuilder(
          listenable: _poller,
          builder: (BuildContext context, Widget? child) {
            final UnitState? unit = _poller.latest;
            return ListView(
              padding: const EdgeInsets.all(16),
              children: <Widget>[
                Text(
                  '马甲 #${widget.session.vestNum}',
                  key: kSituationVestNumKey,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 4),
                Text(
                  _poller.successCount == 0
                      ? '等待首帧…'
                      : '已刷新 ${_poller.successCount} 次'
                          '（每 ${widget.interval.inMilliseconds} ms）',
                  key: kSituationRefreshCountKey,
                ),
                if (_poller.lastError != null) ...<Widget>[
                  const SizedBox(height: 8),
                  Text(
                    _poller.lastError!,
                    key: kSituationErrorKey,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ],
                const Divider(height: 32),
                _row(kSituationStatusKey, '状态', unit?.status.wire),
                _row(null, '队伍', unit?.team?.wire),
                _row(kSituationHpKey, 'HP', unit?.hp),
                _row(
                  kSituationAmmoKey,
                  '弹药',
                  unit == null ? null : '${_orDash(unit.ammo1)} / ${_orDash(unit.ammo2)}',
                ),
                _row(kSituationPositionKey, '位置', _describePosition(unit?.position)),
                _row(kSituationMotionKey, '运动', unit?.motion?.wire),
                _row(null, '信号', unit?.signalQuality),
                _row(null, '定位来源', unit?.positionSource?.wire),
                _row(
                  kSituationHeartbeatKey,
                  '最后心跳',
                  unit?.lastHeartbeatAt?.toIso8601String(),
                ),
                _row(null, '阵亡时刻', unit?.deadAt?.toIso8601String()),
                _row(null, '态势 revision', unit?.revision),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _row(Key? key, String label, Object? value) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            SizedBox(width: 96, child: Text(label)),
            Expanded(child: Text(_orDash(value), key: key)),
          ],
        ),
      );
}

String _orDash(Object? value) => value?.toString() ?? '—';

/// 坐标只做**呈现**：室内用整图百分比，室外区分 BD-09 与 WGS-84 两套，客户端不做换算。
String _describePosition(GeoPoint? point) {
  if (point == null) {
    return '—';
  }
  final String base = switch (point.coordinateSystem) {
    CoordinateSystem.indoor =>
      '室内 x=${_orDash(point.xPct)}% y=${_orDash(point.yPct)}% 层=${_orDash(point.floorNum)} 点=${_orDash(point.posNum)}',
    CoordinateSystem.outdoor =>
      '室外 BD-09 经=${_orDash(point.lng)} 纬=${_orDash(point.lat)}',
    null => '坐标系未下发',
  };
  if (point.wgs84Lng == null && point.wgs84Lat == null) {
    return base;
  }
  return '$base｜WGS-84 经=${_orDash(point.wgs84Lng)} 纬=${_orDash(point.wgs84Lat)}';
}
