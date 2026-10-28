import 'package:flutter/material.dart';

import '../../shared/api/situation_models.dart';
import '../../shared/auth/session_store.dart';
import '../../shared/polling/poller.dart';

/// 供测试与后续集成定位的控件键。
const Key kTeamSummaryAccountKey = Key('teamSummary.account');
const Key kTeamSummaryRefreshCountKey = Key('teamSummary.refreshCount');
const Key kTeamSummaryErrorKey = Key('teamSummary.error');
const Key kTeamSummaryEmptyKey = Key('teamSummary.empty');

/// 段 6 新增：进入「账号与会话」页的入口（未注入回调时整块不渲染）。
const Key kTeamSummaryAccountEntryKey = Key('teamSummary.accountEntry');

/// RES-D 后半新增：进入「全体态势」页的入口（未注入回调时整块不渲染）。
const Key kTeamSummarySituationEntryKey = Key('teamSummary.situationEntry');

/// 按队伍取行键（`TeamSummary` 每队一行）。
Key teamSummaryRowKey(Team team) => Key('teamSummary.row.${team.wire}');

/// 队伍汇总总览的轮询周期（段 5 取 2000ms）。
///
/// 队伍汇总是**聚合读**、不是自身生死读数，没必要压到 1s；契约只要求客户端保持
/// 「不超过 10s」的兜底轮询。**不要**与服务端→马甲的串口 200ms 周期混同；
/// 本值可注入覆盖，WS 化在双通道段再议。
const Duration kTeamSummaryPollInterval = Duration(seconds: 2);

/// 队伍汇总总览页（M6 CLIENT-WINDOWS 段 5）。
///
/// 直接读 `GET /api/v1/situation/teams/summary`（**直接回数组**），不走
/// `SituationEnvelope.teamSummary`——后者在增量帧里恒为空（字段级口径），
/// 客户端若混用两个来源就会把汇总“刷没”。
///
/// 只读展示：队伍、总数、存活、阵亡、离线、命中送出；轮询逻辑在共享层 [PollingFeed]。
class TeamSummaryPage extends StatefulWidget {
  const TeamSummaryPage({
    super.key,
    required this.session,
    required this.fetch,
    this.interval = kTeamSummaryPollInterval,
    this.onOpenAccount,
    this.onOpenSituation,
  });

  /// 已登录的账号会话（提供 `accessToken` 与身份快照）。
  final AccountSession session;

  /// 单次拉取（生产接线见 `app/console_app.dart`：`SituationApi.getTeamSummary`）。
  final Future<List<TeamSummary>> Function() fetch;

  /// 轮询周期；默认 [kTeamSummaryPollInterval]。
  final Duration interval;

  /// 段 6 新增：打开「账号与会话」页；为 `null` 时不渲染入口。
  final VoidCallback? onOpenAccount;

  /// RES-D 后半新增：打开「全体态势」页（接 `SituationFeed` 双通道）；为 `null` 时不渲染入口。
  final VoidCallback? onOpenSituation;

  @override
  State<TeamSummaryPage> createState() => _TeamSummaryPageState();
}

class _TeamSummaryPageState extends State<TeamSummaryPage> {
  late final PollingFeed<List<TeamSummary>> _poller;

  @override
  void initState() {
    super.initState();
    _poller = PollingFeed<List<TeamSummary>>(
      fetch: widget.fetch,
      interval: widget.interval,
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
      appBar: AppBar(
        title: const Text('队伍汇总'),
        actions: <Widget>[
          if (widget.onOpenSituation != null)
            TextButton(
              key: kTeamSummarySituationEntryKey,
              onPressed: widget.onOpenSituation,
              child: const Text('全体态势'),
            ),
          if (widget.onOpenAccount != null)
            TextButton(
              key: kTeamSummaryAccountEntryKey,
              onPressed: widget.onOpenAccount,
              child: const Text('账号'),
            ),
        ],
      ),
      body: SafeArea(
        child: ListenableBuilder(
          listenable: _poller,
          builder: (BuildContext context, Widget? child) {
            final List<TeamSummary>? teams = _poller.latest;
            return ListView(
              padding: const EdgeInsets.all(16),
              children: <Widget>[
                Text(
                  _describeAccount(widget.session),
                  key: kTeamSummaryAccountKey,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 4),
                Text(
                  _poller.successCount == 0
                      ? '等待首帧…'
                      : '已刷新 ${_poller.successCount} 次'
                          '（每 ${widget.interval.inMilliseconds} ms）',
                  key: kTeamSummaryRefreshCountKey,
                ),
                if (_poller.lastError != null) ...<Widget>[
                  const SizedBox(height: 8),
                  Text(
                    _poller.lastError!,
                    key: kTeamSummaryErrorKey,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ],
                const Divider(height: 32),
                if (teams != null && teams.isEmpty)
                  const Text('本场没有已编成的队伍', key: kTeamSummaryEmptyKey),
                ...?teams?.map(
                  (TeamSummary summary) => Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: Text(
                      _describeTeam(summary),
                      key: teamSummaryRowKey(summary.team),
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

String _describeTeam(TeamSummary summary) {
  return '${summary.team.wire}'
      '｜总数 ${_count(summary.total)}'
      '｜存活 ${_count(summary.live)}'
      '｜阵亡 ${_count(summary.dead)}'
      '｜离线 ${_count(summary.offline)}'
      '｜命中送出 ${_count(summary.hitsDealt)}';
}

/// 契约里这些计数**都可选**——缺失时如实显示破折号，不臆造 0。
String _count(int? value) => value?.toString() ?? '—';
