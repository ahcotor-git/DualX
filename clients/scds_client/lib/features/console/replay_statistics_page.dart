import 'dart:async';

import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/replay_models.dart';
import '../../shared/api/situation_models.dart' show TeamSummary;
import '../../shared/auth/session_store.dart';
import 'replay_list_page.dart' show describeReplaySummary;
import 'replay_player_page.dart' show formatReplayOffset;

/// 供测试与后续集成定位的控件键。
const Key kReplayStatisticsAccountKey = Key('replayStatistics.account');
const Key kReplayStatisticsSummaryKey = Key('replayStatistics.summary');
const Key kReplayStatisticsLoadingKey = Key('replayStatistics.loading');
const Key kReplayStatisticsErrorKey = Key('replayStatistics.error');
const Key kReplayStatisticsFormErrorKey = Key('replayStatistics.formError');
const Key kReplayStatisticsBucketsKey = Key('replayStatistics.buckets');
const Key kReplayStatisticsReloadButtonKey = Key('replayStatistics.reloadButton');
const Key kReplayStatisticsTeamsEmptyKey = Key('replayStatistics.teamsEmpty');
const Key kReplayStatisticsShootersEmptyKey = Key('replayStatistics.shootersEmpty');
const Key kReplayStatisticsHitPartsEmptyKey = Key('replayStatistics.hitPartsEmpty');
const Key kReplayStatisticsTimelineEmptyKey = Key('replayStatistics.timelineEmpty');

/// 按队伍字面量取战损汇总行键。
Key replayStatisticsTeamRowKey(String teamWire) => Key('replayStatistics.team.$teamWire');

/// 按**名次**（从 1 起）取命中排行行键。
Key replayStatisticsShooterRowKey(int rank) => Key('replayStatistics.shooter.$rank');

/// 按**序号**（从 0 起）取命中部位分布行键。
Key replayStatisticsHitPartRowKey(int index) => Key('replayStatistics.hitPart.$index');

/// 按**序号**（从 0 起）取存活曲线行键。
Key replayStatisticsBucketRowKey(int index) => Key('replayStatistics.bucket.$index');

/// 存活曲线采样段数下限 / 上限（契约 `timelineBuckets` 10..500）。
const int kReplayStatisticsMinBuckets = 10;
const int kReplayStatisticsMaxBuckets = 500;

/// 服务端默认采样段数（契约默认值）——本页**留空即不传该键**，此常量只用于界面文案。
const int kReplayStatisticsDefaultBuckets = 120;

/// 统计端口（`GET /replays/{replayId}/statistics`）。
///
/// [timelineBuckets] 为存活曲线采样段数（10..500）；**留空即 `null`**——本页只拼非空参数，
/// 由服务端走默认值，不臆造缺省。
typedef ReplayStatisticsLoader = Future<ReplayStatistics> Function({int? timelineBuckets});

/// 复盘统计面板（M6 CLIENT-WINDOWS 段 57·UI 接线·统计面板）——replay 域 statistics 端点的指挥端接线。
///
/// 承载 `GET /replays/{replayId}/statistics`：四段如实展示——队伍战损汇总 / 命中排行 /
/// 命中部位分布 / 存活人数曲线。**只读、不画底图、不做二次计算**。
///
/// 口径：
/// - **`replayId` 就是演习编号**（与列表行同源，页面不再 `GET /replays/{replayId}` 复拉概览）；
/// - **缺失即破折号**——契约里四段的字段**皆非必填**，读不到的计数**不臆造 0**；
/// - **排行与分布按服务端顺序如实展示**（本页不重排、不聚合）；
/// - **存活曲线的 `t` 是相对开演的毫秒偏移**（复用 `formatReplayOffset`，出 `mm:ss.S` **不是时刻**，
///   也不加回 `startedAt`）；
/// - **采样段数越界 / 非法在客户端拦下**（不发请求），镜像服务端 `VALIDATION_FAILED`。
class ReplayStatisticsPage extends StatefulWidget {
  const ReplayStatisticsPage({
    super.key,
    required this.session,
    required this.replay,
    required this.load,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  /// 目标复盘概览（列表页已拿到，不重复 `GET /replays/{replayId}`）。
  final Replay replay;

  final ReplayStatisticsLoader load;

  @override
  State<ReplayStatisticsPage> createState() => _ReplayStatisticsPageState();
}

class _ReplayStatisticsPageState extends State<ReplayStatisticsPage> {
  final TextEditingController _buckets = TextEditingController();

  ReplayStatistics? _stats;

  /// 单飞：任一请求在途时禁用重载。
  bool _busy = false;
  String? _error;
  String? _formError;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  @override
  void dispose() {
    _buckets.dispose();
    super.dispose();
  }

  /// 重载：先本地校验采样段数（非法即拦下、不发请求），再拉统计。
  ///
  /// 校验不过时**保留上一份已装载的数据**，只提示表单错误——不把页面清空。
  void _reload() {
    final String text = _buckets.text.trim();
    int? buckets;
    if (text.isNotEmpty) {
      final int? parsed = int.tryParse(text);
      if (parsed == null ||
          parsed < kReplayStatisticsMinBuckets ||
          parsed > kReplayStatisticsMaxBuckets) {
        setState(() => _formError = '采样段数需为 '
            '$kReplayStatisticsMinBuckets..$kReplayStatisticsMaxBuckets '
            '的整数（留空走服务端默认）');
        return;
      }
      buckets = parsed;
    }
    setState(() => _formError = null);
    unawaited(_load(buckets));
  }

  Future<void> _load(int? buckets) async {
    if (_busy) {
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });

    try {
      final ReplayStatistics stats = await widget.load(timelineBuckets: buckets);
      if (!mounted) {
        return;
      }
      setState(() => _stats = stats);
    } on ApiException catch (failure) {
      if (!mounted) {
        return;
      }
      setState(() => _error = _describeFailure(failure));
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _error = '加载失败：$error');
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  Widget _section(String title, List<Widget> children) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 4),
          ...children,
          const Divider(height: 32),
        ],
      );

  Widget _line(String text, Key key) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Text(text, key: key),
      );

  @override
  Widget build(BuildContext context) {
    final ReplayStatistics? stats = _stats;
    return Scaffold(
      appBar: AppBar(title: const Text('复盘统计')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: <Widget>[
            Text(
              _describeAccount(widget.session),
              key: kReplayStatisticsAccountKey,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 4),
            Text(describeReplaySummary(widget.replay), key: kReplayStatisticsSummaryKey),
            const SizedBox(height: 12),
            Row(
              children: <Widget>[
                Expanded(
                  child: TextField(
                    key: kReplayStatisticsBucketsKey,
                    controller: _buckets,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: '存活曲线采样段数（$kReplayStatisticsMinBuckets..'
                          '$kReplayStatisticsMaxBuckets，留空默认）',
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                TextButton(
                  key: kReplayStatisticsReloadButtonKey,
                  onPressed: _busy ? null : _reload,
                  child: const Text('重载'),
                ),
              ],
            ),
            if (_formError != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _formError!,
                key: kReplayStatisticsFormErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kReplayStatisticsErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            const Divider(height: 32),
            if (stats == null)
              const Text('加载中…', key: kReplayStatisticsLoadingKey)
            else ...<Widget>[
              _section(
                '队伍战损汇总',
                stats.teams.isEmpty
                    ? <Widget>[const Text('没有队伍汇总', key: kReplayStatisticsTeamsEmptyKey)]
                    : <Widget>[
                        for (final TeamSummary team in stats.teams)
                          _line(describeTeamSummary(team), replayStatisticsTeamRowKey(team.team.wire)),
                      ],
              ),
              _section(
                '命中排行',
                stats.topShooters.isEmpty
                    ? <Widget>[const Text('没有命中排行', key: kReplayStatisticsShootersEmptyKey)]
                    : <Widget>[
                        for (int i = 0; i < stats.topShooters.length; i++)
                          _line(
                            describeTopShooter(i + 1, stats.topShooters[i]),
                            replayStatisticsShooterRowKey(i + 1),
                          ),
                      ],
              ),
              _section(
                '命中部位分布',
                stats.hitPartDistribution.isEmpty
                    ? <Widget>[const Text('没有命中部位分布', key: kReplayStatisticsHitPartsEmptyKey)]
                    : <Widget>[
                        for (int i = 0; i < stats.hitPartDistribution.length; i++)
                          _line(
                            describeHitPartCount(stats.hitPartDistribution[i]),
                            replayStatisticsHitPartRowKey(i),
                          ),
                      ],
              ),
              _section(
                '存活人数曲线',
                stats.timeline.isEmpty
                    ? <Widget>[const Text('没有存活曲线', key: kReplayStatisticsTimelineEmptyKey)]
                    : <Widget>[
                        for (int i = 0; i < stats.timeline.length; i++)
                          _line(
                            describeTimelineBucket(stats.timeline[i]),
                            replayStatisticsBucketRowKey(i),
                          ),
                      ],
              ),
            ],
          ],
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

/// 队伍战损一行；契约里五项计数**皆非必填**——缺失如实显示破折号，不臆造 0。
String describeTeamSummary(TeamSummary summary) => '${summary.team.wire}'
    '｜总 ${_count(summary.total)}'
    '｜存活 ${_count(summary.live)}'
    '｜阵亡 ${_count(summary.dead)}'
    '｜离线 ${_count(summary.offline)}'
    '｜命中 ${_count(summary.hitsDealt)}';

/// 命中排行一行。[rank] 为**名次**（从 1 起）——按服务端回传顺序编号，本页不重排。
String describeTopShooter(int rank, TopShooter shooter) => '第 $rank 名'
    '｜${shooter.name ?? '（未登记）'}'
    '｜编号 ${_count(shooter.vestNum)}'
    '｜命中 ${_count(shooter.hitsDealt)}'
    '｜击杀 ${_count(shooter.kills)}';

/// 命中部位分布一行。
String describeHitPartCount(HitPartCount entry) =>
    '${entry.hitPart?.wire ?? '—'}｜${_count(entry.count)}';

/// 存活曲线一行：`t` 是**相对开演的毫秒偏移**，出 `mm:ss.S`（不是时刻）。
String describeTimelineBucket(TimelineBucket bucket) {
  final int? t = bucket.t;
  return '${t == null ? '—' : formatReplayOffset(t)}'
      '｜${_describeLiveByTeam(bucket.liveByTeam)}';
}

/// 各队存活人数：键为队伍名（`RED` / `BLUE` / …）；缺失或空即为破折号。
String _describeLiveByTeam(Map<String, int>? liveByTeam) {
  if (liveByTeam == null || liveByTeam.isEmpty) {
    return '—';
  }
  return liveByTeam.entries
      .map((MapEntry<String, int> entry) => '${entry.key} ${entry.value}')
      .join(' / ');
}

String _count(int? value) => value?.toString() ?? '—';

String _describeFailure(ApiException failure) => switch (failure.code) {
      ApiErrorCode.replayNotFound => '复盘不存在（${ApiErrorCode.replayNotFound}）',
      ApiErrorCode.authForbidden => '当前账号无权查看该复盘（${ApiErrorCode.authForbidden}）',
      ApiErrorCode.validationFailed => '统计参数不合法（${ApiErrorCode.validationFailed}）',
      ApiErrorCode.instanceNotReady =>
        '实例尚未就绪，请稍后重试（${ApiErrorCode.instanceNotReady}）',
      _ => '加载统计失败：${failure.code}',
    };
