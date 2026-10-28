import 'package:flutter/material.dart';

import '../../shared/api/situation_models.dart';
import '../../shared/auth/session_store.dart';
import '../../shared/state/situation_cache.dart';
import '../../shared/state/situation_feed.dart';
import '../../shared/state/situation_socket.dart';

/// 供测试与后续集成定位的控件键。
const Key kOverallSituationAccountKey = Key('overallSituation.account');
const Key kOverallSituationChannelKey = Key('overallSituation.channel');
const Key kOverallSituationCountsKey = Key('overallSituation.counts');
const Key kOverallSituationErrorKey = Key('overallSituation.error');
const Key kOverallSituationResyncButtonKey = Key('overallSituation.resync');
const Key kOverallSituationWaitingKey = Key('overallSituation.waiting');
const Key kOverallSituationEmptyKey = Key('overallSituation.empty');

/// 按马甲编号取行键（每个参演单元一行）。
Key overallSituationUnitRowKey(int vestNum) => Key('overallSituation.unit.$vestNum');

/// 全体态势页（M6 CLIENT-RESILIENCE 段 D 后半）——指挥端接 [SituationFeed] 双通道。
///
/// 与「队伍汇总」页（`TeamSummaryPage`）分工不同：队伍汇总只读
/// `GET /situation/teams/summary` 的**聚合计数**（必须直读——增量帧的 `teamSummary`
/// 恒为空，混用两个来源会把汇总刷没）；本页读**统一态势流**——由 [SituationFeed]
/// 把 WS 主通道与 HTTP 兜底轮询（WS 在线仍须 ≤10s）**同源归并**进 [SituationCache]，
/// 逐单元展示。
///
/// 页面**自建并持有** [SituationFeed]（与单兵端自身态势页持有 `PollingFeed` 同构）：
/// `initState` 启动、`dispose` 释放——缓存随页面生灭，不跨会话泄漏。
class OverallSituationPage extends StatefulWidget {
  const OverallSituationPage({
    super.key,
    required this.session,
    required this.fetch,
    required this.connect,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  /// HTTP 兜底轮询（生产接线见 `app/console_app.dart`：`SituationApi.getSituation`）。
  final SituationFetch fetch;

  /// WS 主通道连接器（生产接线：`connectSituationSocket`，令牌由调用方绑定）。
  final SituationSocketConnector connect;

  @override
  State<OverallSituationPage> createState() => _OverallSituationPageState();
}

class _OverallSituationPageState extends State<OverallSituationPage> {
  late final SituationFeed _feed;

  @override
  void initState() {
    super.initState();
    _feed = SituationFeed(fetch: widget.fetch, connect: widget.connect)..start();
  }

  @override
  void dispose() {
    _feed.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('全体态势'),
        actions: <Widget>[
          TextButton(
            key: kOverallSituationResyncButtonKey,
            onPressed: _feed.requestResync,
            child: const Text('重新同步'),
          ),
        ],
      ),
      body: SafeArea(
        child: ListenableBuilder(
          listenable: _feed,
          builder: (BuildContext context, Widget? child) {
            final SituationCache cache = _feed.cache;
            final List<UnitState> units = cache.units;
            // 「首帧」指任一通道**被采纳**过：在此之前空列表不代表「没有单元」。
            final bool gotFirstFrame = _feed.pollSuccessCount > 0 || _feed.wsFrameCount > 0;
            return ListView(
              padding: const EdgeInsets.all(16),
              children: <Widget>[
                Text(
                  _describeAccount(widget.session),
                  key: kOverallSituationAccountKey,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 4),
                Text(_describeChannel(_feed.wsConnected), key: kOverallSituationChannelKey),
                const SizedBox(height: 4),
                Text(_describeCounts(units.length, _feed), key: kOverallSituationCountsKey),
                if (_feed.lastError != null) ...<Widget>[
                  const SizedBox(height: 8),
                  Text(
                    _feed.lastError!,
                    key: kOverallSituationErrorKey,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ],
                const Divider(height: 32),
                if (!gotFirstFrame && _feed.lastError == null)
                  const Text('等待首帧…', key: kOverallSituationWaitingKey)
                else if (units.isEmpty)
                  const Text('本场没有参演单元', key: kOverallSituationEmptyKey)
                else
                  ...units.map(
                    (UnitState unit) => Padding(
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      child: Text(
                        _describeUnit(unit),
                        key: overallSituationUnitRowKey(unit.vestNum),
                      ),
                    ),
                  ),
              ],
            );
          },
        ),
      ),
    );
  }
}

String _describeAccount(AccountSession session) {
  final String name = session.user?.displayName ?? session.user?.username ?? '（未知账号）';
  final List<String> roles = session.user?.roles ?? const <String>[];
  return roles.isEmpty ? name : '$name（${roles.join(' / ')}）';
}

/// 通道状态：WS 主通道是否已连；未连时是 2s 轮询档，已连时是 ≤10s 兜底档。
String _describeChannel(bool wsConnected) =>
    wsConnected ? '实时（WS 已连，兜底轮询 ≤10s）' : '轮询中（WS 未连，每 2s）';

String _describeCounts(int unitCount, SituationFeed feed) =>
    '单元 $unitCount｜拉取 ${feed.pollSuccessCount} 次｜WS 帧 ${feed.wsFrameCount}';

/// 单元行只做呈现：状态取契约字面量，HP 缺失时如实显示破折号。
String _describeUnit(UnitState unit) => '#${unit.vestNum} ${unit.status.wire}｜HP ${unit.hp ?? '—'}';
