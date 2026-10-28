import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/situation_models.dart';
import '../../shared/auth/session_store.dart';
import '../../shared/polling/poller.dart';

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

/// 自身态势页（M6 CLIENT-BASE 段 4c）。
///
/// 轮询逻辑在共享层 [PollingFeed]（段 5 抽出，双端复用），本页只负责把
/// 「自身马甲状态」这一个数据源接到它上面，并把 [ApiErrorCode.deviceVestNotFound]
/// 翻成现场可读文案。
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

  /// 单次拉取（生产接线见 `app/device_app.dart`：`SituationApi.getVestState`）。
  final Future<UnitState> Function() fetch;

  /// 轮询周期；默认 [kSituationPollInterval]。
  final Duration interval;

  @override
  State<SituationPage> createState() => _SituationPageState();
}

class _SituationPageState extends State<SituationPage> {
  late final PollingFeed<UnitState> _poller;

  @override
  void initState() {
    super.initState();
    _poller = PollingFeed<UnitState>(
      fetch: widget.fetch,
      interval: widget.interval,
      describeError: (ApiException failure) =>
          failure.code == ApiErrorCode.deviceVestNotFound
              ? '本场编成中没有该马甲（${ApiErrorCode.deviceVestNotFound}）'
              : '拉取失败：${failure.code}',
    )..start();
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
