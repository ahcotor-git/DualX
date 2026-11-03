import 'dart:async';

import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/replay_models.dart';
import '../../shared/auth/session_store.dart';
import 'replay_list_page.dart' show describeReplaySummary;

/// 供测试与后续集成定位的控件键。
const Key kReplayPlayerAccountKey = Key('replayPlayer.account');
const Key kReplayPlayerSummaryKey = Key('replayPlayer.summary');
const Key kReplayPlayerErrorKey = Key('replayPlayer.error');
const Key kReplayPlayerStatusKey = Key('replayPlayer.status');
const Key kReplayPlayerProgressKey = Key('replayPlayer.progress');
const Key kReplayPlayerSliderKey = Key('replayPlayer.slider');
const Key kReplayPlayerPlayButtonKey = Key('replayPlayer.playButton');
const Key kReplayPlayerSpeedKey = Key('replayPlayer.speed');
const Key kReplayPlayerLoadMoreButtonKey = Key('replayPlayer.loadMoreButton');
const Key kReplayPlayerEndKey = Key('replayPlayer.end');
const Key kReplayPlayerEmptyKey = Key('replayPlayer.empty');

/// 按马甲编号取「当前时刻读数」行键。
Key replayPlayerUnitKey(int vestNum) => Key('replayPlayer.unit.$vestNum');

/// 轨迹分片端口（`GET /replays/{replayId}/timeline`）。
///
/// [cursor] 传上一片返回的 `nextCursor`（首次不传）；本页只做「按游标连续拉取」，
/// 其余参数（`limit` / `vestNums` / `stride`）由调用方在闭包里定死，不散进 UI。
typedef ReplayTimelineLoader = Future<TimelineChunk> Function({String? cursor});

/// 可选播放速度（相对演习时间的倍率）。
const List<double> kReplaySpeeds = <double>[0.5, 1, 2, 4];

/// 虚拟时钟步长（毫秒）：每 [kReplayTickMs] 推进一次 [ReplayPlayerPage] 的播放位置。
const int kReplayTickMs = 100;

/// 复盘回放器（M6 CLIENT-WINDOWS 段 57·UI 接线）——replay 域轨迹端的指挥端接线。
///
/// 承载 `GET /replays/{replayId}/timeline`，形态是**数据驱动回放器**：
/// 按 `nextCursor` **边拉边播**、虚拟时钟推进、当前时刻给出各单元读数；**不画底图**。
///
/// 口径：
/// - **时间轴一律是相对开演的毫秒偏移**：`TrackSample.t` 与播放位置都是偏移量，
///   页面**不加回 `startedAt`**——跨时区回放不会错位，也无需换算；
/// - **分片按游标连续拉取**：`nextCursor == null` 即末尾；一场演习的采样点可达数十万条，
///   故**不一次性拉全量**，播到已装载末尾时自动续拉下一片；
/// - **坐标两组不可混用**：室内 `xPct` / `yPct`（归一化 0~1）与室外 `lng` / `lat`（BD-09）
///   在库里只有一套有效，页面**如实分列展示、不自行换算、不翻转 Y 轴**；
/// - 容器退出即停表（`dispose` 取销定时器），不留悬挂时钟。
class ReplayPlayerPage extends StatefulWidget {
  const ReplayPlayerPage({
    super.key,
    required this.session,
    required this.replay,
    required this.loadTimeline,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  /// 目标复盘概览（列表页已拿到，不重复 `GET /replays/{replayId}`）。
  final Replay replay;

  final ReplayTimelineLoader loadTimeline;

  @override
  State<ReplayPlayerPage> createState() => _ReplayPlayerPageState();
}

class _ReplayPlayerPageState extends State<ReplayPlayerPage> {
  /// 已装载的采样点，按拉取先后累积（契约保证片内按 `t` 升序）。
  final List<TrackSample> _samples = <TrackSample>[];

  /// 下一片游标（首次拉取前为 `null`）；是否为末尾看 `_atEnd`。
  String? _cursor;

  /// 是否已拉到末尾（`nextCursor == null`）。
  bool _atEnd = false;

  /// 已装载采样点的最大相对偏移（毫秒）。
  int _maxT = 0;

  /// 播放位置（相对开演的毫秒偏移）。
  int _positionMs = 0;

  bool _playing = false;
  double _speed = 1;

  bool _fetching = false;
  String? _error;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    unawaited(_fetchMore());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  /// 拉取下一片；`_fetching` 单飞，重复调用直接返回。
  ///
  /// 失败即**停表**：否则定时器会在同一位置每拍重试，把错误刷成风暴。
  Future<void> _fetchMore() async {
    if (_fetching || _atEnd) {
      return;
    }
    setState(() {
      _fetching = true;
      _error = null;
    });

    try {
      final TimelineChunk chunk = await widget.loadTimeline(cursor: _cursor);
      if (!mounted) {
        return;
      }
      setState(() {
        for (final TrackSample sample in chunk.samples) {
          _samples.add(sample);
          if (sample.t > _maxT) {
            _maxT = sample.t;
          }
        }
        _cursor = chunk.nextCursor;
        // `nextCursor == null` 即末尾——空片但带游标时仍要接着拉。
        _atEnd = chunk.nextCursor == null;
        _fetching = false;
      });
    } on ApiException catch (failure) {
      if (!mounted) {
        return;
      }
      _pause();
      setState(() {
        _fetching = false;
        _error = _describeFailure(failure);
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      _pause();
      setState(() {
        _fetching = false;
        _error = '拉取轨迹失败：$error';
      });
    }
  }

  void _togglePlay() {
    if (_playing) {
      _pause();
      return;
    }
    // 已播到末尾再按播放＝重播。
    if (_atEnd && _positionMs >= _maxT) {
      _positionMs = 0;
    }
    _startPlayback();
  }

  void _startPlayback() {
    _timer?.cancel();
    setState(() => _playing = true);
    _timer = Timer.periodic(
      const Duration(milliseconds: kReplayTickMs),
      (Timer _) => _onTick(),
    );
  }

  void _pause() {
    _timer?.cancel();
    _timer = null;
    if (_playing) {
      setState(() => _playing = false);
    }
  }

  /// 一拍：按当前速度推进播放位置。
  ///
  /// 追到已装载末尾时——若还有分片就**就地拉下一片**（边拉边播，位置停在末尾等料）；
  /// 若已到末尾则停在末尾并自动暂停。
  void _onTick() {
    final int next = _positionMs + (kReplayTickMs * _speed).round();
    if (next >= _maxT) {
      if (!_atEnd) {
        setState(() => _positionMs = _maxT);
        unawaited(_fetchMore());
        return;
      }
      _positionMs = _maxT;
      _pause();
      return;
    }
    setState(() => _positionMs = next);
  }

  /// 当前时刻各单元的最新读数：取每个马甲 `t <= 播放位置` 的**最后一条**采样。
  List<TrackSample> _readingsAt(int positionMs) {
    final Map<int, TrackSample> latest = <int, TrackSample>{};
    for (final TrackSample sample in _samples) {
      if (sample.t <= positionMs) {
        latest[sample.vestNum] = sample;
      }
    }
    final List<int> nums = latest.keys.toList()..sort();
    return <TrackSample>[for (final int num in nums) latest[num]!];
  }

  Widget _readingRow(TrackSample sample) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Text(describeSampleReading(sample), key: replayPlayerUnitKey(sample.vestNum)),
      );

  @override
  Widget build(BuildContext context) {
    final Replay replay = widget.replay;
    final List<TrackSample> readings = _readingsAt(_positionMs);
    return Scaffold(
      appBar: AppBar(title: const Text('复盘回放')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: <Widget>[
            Text(
              _describeAccount(widget.session),
              key: kReplayPlayerAccountKey,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 4),
            Text(describeReplaySummary(replay), key: kReplayPlayerSummaryKey),
            const Divider(height: 32),
            Row(
              children: <Widget>[
                TextButton(
                  key: kReplayPlayerPlayButtonKey,
                  onPressed: _togglePlay,
                  child: Text(_playing ? '暂停' : '播放'),
                ),
                const SizedBox(width: 8),
                SizedBox(
                  width: 120,
                  child: InputDecorator(
                    decoration: const InputDecoration(labelText: '速度'),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<double>(
                        key: kReplayPlayerSpeedKey,
                        value: _speed,
                        isExpanded: true,
                        items: kReplaySpeeds
                            .map(
                              (double speed) => DropdownMenuItem<double>(
                                value: speed,
                                child: Text('${speed}x'),
                              ),
                            )
                            .toList(),
                        onChanged: (double? value) =>
                            setState(() => _speed = value ?? 1),
                      ),
                    ),
                  ),
                ),
                const Spacer(),
                TextButton(
                  key: kReplayPlayerLoadMoreButtonKey,
                  onPressed: _fetching || _atEnd ? null : () => unawaited(_fetchMore()),
                  child: const Text('加载下一片'),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              '${formatReplayOffset(_positionMs)} / ${formatReplayOffset(_maxT)}',
              key: kReplayPlayerProgressKey,
            ),
            Slider(
              key: kReplayPlayerSliderKey,
              min: 0,
              max: _maxT > 0 ? _maxT.toDouble() : 1,
              value: (_positionMs > _maxT ? _maxT : _positionMs).toDouble(),
              onChanged: _maxT > 0
                  ? (double value) => setState(() => _positionMs = value.round())
                  : null,
            ),
            Text(_describeStatus(), key: kReplayPlayerStatusKey),
            if (_atEnd)
              const Text('已到末尾（无可拉取分片）', key: kReplayPlayerEndKey),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kReplayPlayerErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            const Divider(height: 32),
            if (readings.isEmpty)
              const Text('当前时刻没有位置读数', key: kReplayPlayerEmptyKey)
            else
              ...readings.map(_readingRow),
          ],
        ),
      ),
    );
  }

  String _describeStatus() => '已装载 ${_samples.length} 个采样点'
      '｜游标 ${_cursor ?? '—'}'
      '${_fetching ? '｜拉取中…' : ''}';
}

String _describeAccount(AccountSession session) {
  final String name = session.user?.displayName ?? session.user?.username ?? '（未知账号）';
  final List<String> roles = session.user?.roles ?? const <String>[];
  return roles.isEmpty ? name : '$name（${roles.join(' / ')}）';
}

/// 把**相对开演的毫秒偏移**格式化为 `mm:ss.S`（不是时刻）。
String formatReplayOffset(int milliseconds) {
  final int totalTenths = milliseconds ~/ 100;
  final int tenths = totalTenths % 10;
  final int totalSeconds = totalTenths ~/ 10;
  final int seconds = totalSeconds % 60;
  final int minutes = totalSeconds ~/ 60;
  return '${minutes.toString().padLeft(2, '0')}:'
      '${seconds.toString().padLeft(2, '0')}.'
      '$tenths';
}

/// 一个采样点的读数文本（**当前时刻各单元**的最小展示单元）。
String describeSampleReading(TrackSample sample) => '#${sample.vestNum}'
    '｜${formatReplayOffset(sample.t)}'
    '｜${describeSamplePosition(sample)}'
    '｜血量 ${sample.hp ?? '—'}'
    '｜状态 ${sample.status?.wire ?? '—'}';

/// 坐标平铺两组——库里哪套有效就只显示哪套，**不换算、不翻转**。
///
/// 契约保证同一采样点只会有一套坐标；万一两套都在（不应发生），按室内口径显示，
/// 页面对这个边界**不做静默换算**。
String describeSamplePosition(TrackSample sample) {
  final double? xPct = sample.xPct;
  final double? yPct = sample.yPct;
  if (xPct != null && yPct != null) {
    return '室内 (${xPct.toStringAsFixed(3)}, ${yPct.toStringAsFixed(3)})';
  }
  final double? lng = sample.lng;
  final double? lat = sample.lat;
  if (lng != null && lat != null) {
    return '室外 (${lng.toStringAsFixed(5)}, ${lat.toStringAsFixed(5)})';
  }
  return '无坐标';
}

String _describeFailure(ApiException failure) => switch (failure.code) {
      ApiErrorCode.replayNotFound => '复盘不存在（${ApiErrorCode.replayNotFound}）',
      ApiErrorCode.authForbidden => '当前账号无权查看该复盘（${ApiErrorCode.authForbidden}）',
      ApiErrorCode.validationFailed => '轨迹参数不合法（${ApiErrorCode.validationFailed}）',
      ApiErrorCode.instanceNotReady =>
        '实例尚未就绪，请稍后重试（${ApiErrorCode.instanceNotReady}）',
      _ => '拉取轨迹失败：${failure.code}',
    };
