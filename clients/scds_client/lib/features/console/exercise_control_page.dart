import 'dart:async';

import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/exercise_models.dart';
import '../../shared/auth/session_store.dart';
import 'plan_manager_page.dart' show PlanLister;

/// 供测试与后续集成定位的控件键。
const Key kExerciseControlAccountKey = Key('exerciseControl.account');
const Key kExerciseControlRefreshButtonKey = Key('exerciseControl.refreshButton');
const Key kExerciseControlStartButtonKey = Key('exerciseControl.startButton');
const Key kExerciseControlStopButtonKey = Key('exerciseControl.stopButton');
const Key kExerciseControlLoadingKey = Key('exerciseControl.loading');
const Key kExerciseControlIdleKey = Key('exerciseControl.idle');
const Key kExerciseControlRunningKey = Key('exerciseControl.running');
const Key kExerciseControlErrorKey = Key('exerciseControl.error');
const Key kExerciseControlPlanFieldKey = Key('exerciseControl.plan');
const Key kExerciseControlBattleModeKey = Key('exerciseControl.battleMode');

/// 停演确认对话框的控件键。
const Key kExerciseStopConfirmKey = Key('exerciseControl.stopConfirm');
const Key kExerciseStopCancelKey = Key('exerciseControl.stopCancel');

/// 当前演习日志流水面板的控件键（仅在注入 [ExerciseControlPage.logs] 时渲染）。
const Key kExerciseControlLogsTitleKey = Key('exerciseControl.logsTitle');
const Key kExerciseControlLogsCountKey = Key('exerciseControl.logsCount');
const Key kExerciseControlLogsEmptyKey = Key('exerciseControl.logsEmpty');
const Key kExerciseControlLogsErrorKey = Key('exerciseControl.logsError');

/// 按日志序号取行键（每个日志条目一行）。
Key exerciseControlLogRowKey(int seq) => Key('exerciseControl.log.$seq');

/// 当前演习状态拉取端口（`GET /exercise/current`，**无演习时也回 200 + [Exercise.running]=false**）。
typedef CurrentExerciseLoader = Future<Exercise> Function();

/// 开演端口（`POST /exercise/current`，201，回开演后的 [Exercise]）。
typedef ExerciseStarter = Future<Exercise> Function(StartExerciseRequest request);

/// 停演端口（`DELETE /exercise/current`，回 [StopExerciseResult]）。
typedef ExerciseStopper = Future<StopExerciseResult> Function();

/// 当前演习日志流水拉取端口（`GET /exercise/current/logs`，游标增量）。
///
/// [sinceSeq] 为「只要 seq 大于它的条目」；响应 `latestSeq` 原样作为下次 [sinceSeq] 续拉，
/// 既去重又续拉（`ExerciseLogPage`）。生产接线见 `app/console_app.dart`。
typedef ExerciseLogsLoader = Future<ExerciseLogPage> Function({int? sinceSeq, int? limit});

/// 当前演习日志流水的轮询周期（指挥台滚动日志观感默认，可注入覆盖）。
///
/// 与服务端→马甲的**串口** 200ms 轮询无关：那是硬件协议周期，本值只是客户端刷新频率。
const Duration kExerciseLogInterval = Duration(seconds: 2);

/// 滚动日志在客户端保留的最大条数（超出即丢弃最早的，防长场次内存无界增长）。
const int kExerciseLogMaxEntries = 500;

/// 演习控制页（M6 CLIENT-WINDOWS 段 51）——开演 / 停演生命周期的指挥端接线。
///
/// 顶部展示当前场次（[CurrentExerciseLoader] 拉 `GET /exercise/current`）：
/// - **无演习进行中**：渲染开演表单（想定下拉 + 可选演习模式）+ 「开演」按钮；
/// - **进行中**：渲染场次摘要 + 「停演」按钮（二次确认）。
///
/// 口径：
/// - **无演习不是错误**：契约规定此时 HTTP 200 且 `running=false`，本页据此切到「开演」态，
///   不把 200 当异常；
/// - 开演请求体 `StartExerciseRequest.toJson` **只发非 null 键**——演习模式选「沿用想定」即
///   **不发 `battleMode`**，由服务端沿用想定里的模式（与想定 `PUT` 的整体替换口径相反）；
/// - 开演前置条件（编成非空、马甲串口链路 `OPEN`）由服务端判定并按 400
///   `EXERCISE_ROSTER_EMPTY` / 409 `DEVICE_LINK_NOT_OPEN` 回绝，本页只把错误码映射成现场文案；
/// - 写操作**单飞去重**（按钮禁用）：重复开演 / 重复停演会被服务端以 409 回绝。
class ExerciseControlPage extends StatefulWidget {
  const ExerciseControlPage({
    super.key,
    required this.session,
    required this.load,
    required this.listPlans,
    required this.start,
    required this.stop,
    this.logs,
    this.logInterval = kExerciseLogInterval,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  final CurrentExerciseLoader load;

  /// 想定列表端口（`GET /plans`）——供开演选择器使用（与想定管理页同形）。
  final PlanLister listPlans;

  final ExerciseStarter start;
  final ExerciseStopper stop;

  /// 当前演习日志流水端口（`GET /exercise/current/logs`）；为 `null` 时**不渲染**日志面板
  /// （既有只读用例不注入本端口，页面与行为保持不变）。
  ///
  /// 仅在**该场进行中**时按 [logInterval] 周期增量续拉，展示「指挥台滚动日志」；
  /// 停演后清空并停表。
  final ExerciseLogsLoader? logs;

  /// 日志流水轮询周期；默认 [kExerciseLogInterval]。
  final Duration logInterval;

  @override
  State<ExerciseControlPage> createState() => _ExerciseControlPageState();
}

class _ExerciseControlPageState extends State<ExerciseControlPage> {
  /// 当前演习状态；`null` 表示尚未加载完成。
  Exercise? _current;

  /// 可选想定清单；`null` 表示尚未加载完成。
  List<Plan>? _plans;

  /// 开演表单的当前选择。
  String? _selectedPlanId;

  /// 开演表单的演习模式；`null` 即「沿用想定」（不发 `battleMode`）。
  BattleMode? _battleMode;

  /// 读写单飞：任一请求在途时禁用按钮，避免重复开演 / 重复停演。
  bool _busy = false;
  String? _error;

  /// 已收到的当前演习日志（按 seq 升序），最多保留 [kExerciseLogMaxEntries] 条。
  final List<ExerciseLogEntry> _logs = <ExerciseLogEntry>[];

  /// 日志续拉游标：上次响应 `latestSeq`；`null` 表示从 0 起拉。
  int? _logCursor;

  /// 日志拉取失败的可读文案；成功后清空。
  String? _logError;

  /// 日志轮询计时器；仅在该场进行中且注入了 [ExerciseControlPage.logs] 时存在。
  Timer? _logTimer;

  /// 日志单飞：上一拍未回则跳过本拍，慢网络下不叠加请求。
  bool _logInFlight = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _logTimer?.cancel();
    super.dispose();
  }

  /// 只读刷新：重新拉当前场次与想定清单。
  Future<void> _load() => _guard(_fetch);

  Future<void> _fetch() async {
    final Exercise current = await widget.load();
    final PlanPage page = await widget.listPlans();
    if (!mounted) {
      return;
    }
    setState(() {
      _current = current;
      _plans = page.items;
      _syncSelection(page.items);
    });
    _syncLogTimer();
  }

  /// 依据「该场是否进行中 + 是否注入日志端口」启停日志轮询；停表时清空已收日志。
  ///
  /// 端口为 `null` 时始终不启表（既有只读用例行为不变）；停演（`running=false`）后
  /// 清空并停表，避免把上一场的日志留在下一场。
  void _syncLogTimer() {
    final bool want = widget.logs != null && (_current?.running ?? false);
    if (want) {
      if (_logTimer == null) {
        _logTimer = Timer.periodic(widget.logInterval, (Timer _) => _pollLogs());
        unawaited(_pollLogs());
      }
      return;
    }
    _logTimer?.cancel();
    _logTimer = null;
    if (_logs.isNotEmpty || _logCursor != null || _logError != null) {
      setState(() {
        _logs.clear();
        _logCursor = null;
        _logError = null;
      });
    }
  }

  /// 增量续拉一拍日志；失败只置 [_logError]，**不清空**已收日志（与 `PollingFeed` 同口径）。
  Future<void> _pollLogs() async {
    final ExerciseLogsLoader? loader = widget.logs;
    if (loader == null || _logInFlight) {
      return;
    }
    _logInFlight = true;
    try {
      final ExerciseLogPage page =
          await loader(sinceSeq: _logCursor, limit: kExerciseLogMaxEntries);
      if (!mounted) {
        return;
      }
      setState(() {
        _logs.addAll(page.items);
        if (_logs.length > kExerciseLogMaxEntries) {
          _logs.removeRange(0, _logs.length - kExerciseLogMaxEntries);
        }
        _logCursor = page.latestSeq ?? _logCursor;
        _logError = null;
      });
    } on ApiException catch (failure) {
      if (!mounted) {
        return;
      }
      setState(() => _logError = '日志拉取失败：${failure.code}');
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _logError = '日志拉取失败：$error');
    } finally {
      _logInFlight = false;
    }
  }

  /// 选择项与最新清单对齐：原选择失效（或首次加载）时回落到第一个想定。
  void _syncSelection(List<Plan> plans) {
    if (plans.isEmpty) {
      _selectedPlanId = null;
      return;
    }
    final bool stillValid = _selectedPlanId != null &&
        plans.any((Plan plan) => plan.planId == _selectedPlanId);
    if (!stillValid) {
      _selectedPlanId = plans.first.planId;
    }
  }

  Future<void> _start() async {
    final String? planId = _selectedPlanId;
    if (planId == null) {
      setState(() => _error = '请先选择想定再开演');
      return;
    }
    await _guard(() async {
      final Exercise started = await widget.start(
        StartExerciseRequest(planId: planId, battleMode: _battleMode),
      );
      if (!mounted) {
        return;
      }
      setState(() => _current = started);
      _syncLogTimer();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('已开演：想定「${started.planName ?? planId}」')),
      );
    });
  }

  Future<void> _confirmStop() async {
    final bool? confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) => AlertDialog(
        title: const Text('结束演习'),
        content: const Text('确定结束当前演习？结束后可进入复盘。'),
        actions: <Widget>[
          TextButton(
            key: kExerciseStopCancelKey,
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            key: kExerciseStopConfirmKey,
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('停演'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }
    await _guard(() async {
      final StopExerciseResult result = await widget.stop();
      if (!mounted) {
        return;
      }
      // 停演后以服务端为准重拉一次，页面自然切回「开演」态。
      await _fetch();
      if (!mounted) {
        return;
      }
      final String replay =
          result.replayId == null ? '' : '，复盘编号 ${result.replayId}';
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('已停演$replay')),
      );
    });
  }

  /// 单飞执行：置忙 → 执行 → 释放；异常统一落到 [_error]（页面顶部提示），不冒泡成红屏。
  Future<void> _guard(Future<void> Function() action) async {
    if (_busy) {
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });

    try {
      await action();
    } on ApiException catch (failure) {
      if (!mounted) {
        return;
      }
      setState(() => _error = _describeFailure(failure));
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _error = '操作失败：$error');
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  String _describeFailure(ApiException failure) => switch (failure.code) {
        ApiErrorCode.exerciseAlreadyRunning =>
          '已有演习进行中，不能重复开演（${ApiErrorCode.exerciseAlreadyRunning}）',
        ApiErrorCode.exerciseNotRunning =>
          '当前没有进行中的演习（${ApiErrorCode.exerciseNotRunning}）',
        ApiErrorCode.exercisePlanNotFound =>
          '想定不存在（${ApiErrorCode.exercisePlanNotFound}）',
        ApiErrorCode.exerciseRosterEmpty =>
          '该想定编成为空，请先配置编成（${ApiErrorCode.exerciseRosterEmpty}）',
        ApiErrorCode.deviceLinkNotOpen =>
          '马甲串口链路未开启，请先在设备页开启链路（${ApiErrorCode.deviceLinkNotOpen}）',
        ApiErrorCode.validationFailed => '参数校验失败（${ApiErrorCode.validationFailed}）',
        ApiErrorCode.instanceNotReady =>
          '实例尚未就绪，请稍后重试（${ApiErrorCode.instanceNotReady}）',
        _ => '操作失败：${failure.code}',
      };

  List<Widget> _runningBlock(Exercise current) => <Widget>[
        Text(_describeRunning(current), key: kExerciseControlRunningKey),
        const SizedBox(height: 16),
        Align(
          alignment: Alignment.centerLeft,
          child: FilledButton(
            key: kExerciseControlStopButtonKey,
            onPressed: _busy ? null : _confirmStop,
            child: const Text('停演'),
          ),
        ),
      ];

  List<Widget> _idleBlock() {
    final List<Plan> plans = _plans ?? const <Plan>[];
    return <Widget>[
      const Text('当前无演习进行中', key: kExerciseControlIdleKey),
      const SizedBox(height: 16),
      if (_plans == null)
        const Text('加载想定中…')
      else if (plans.isEmpty)
        const Text('暂无想定，请先在想定管理中创建')
      else ...<Widget>[
        InputDecorator(
          decoration: const InputDecoration(labelText: '想定'),
          child: DropdownButtonHideUnderline(
            child: DropdownButton<String>(
              key: kExerciseControlPlanFieldKey,
              value: _selectedPlanId,
              isExpanded: true,
              hint: const Text('请选择想定'),
              items: plans
                  .map(
                    (Plan plan) => DropdownMenuItem<String>(
                      value: plan.planId,
                      child: Text('${plan.name}（编成 ${plan.rosterCount ?? '—'}）'),
                    ),
                  )
                  .toList(growable: false),
              onChanged: _busy
                  ? null
                  : (String? value) => setState(() => _selectedPlanId = value),
            ),
          ),
        ),
        const SizedBox(height: 12),
        InputDecorator(
          decoration: const InputDecoration(labelText: '演习模式'),
          child: DropdownButtonHideUnderline(
            child: DropdownButton<BattleMode?>(
              key: kExerciseControlBattleModeKey,
              value: _battleMode,
              isExpanded: true,
              items: <DropdownMenuItem<BattleMode?>>[
                const DropdownMenuItem<BattleMode?>(
                  value: null,
                  child: Text('沿用想定'),
                ),
                ...BattleMode.values.map(
                  (BattleMode mode) => DropdownMenuItem<BattleMode?>(
                    value: mode,
                    child: Text(mode.wire),
                  ),
                ),
              ],
              onChanged: _busy
                  ? null
                  : (BattleMode? value) => setState(() => _battleMode = value),
            ),
          ),
        ),
      ],
      const SizedBox(height: 16),
      Align(
        alignment: Alignment.centerLeft,
        child: FilledButton(
          key: kExerciseControlStartButtonKey,
          onPressed: _busy ? null : _start,
          child: const Text('开演'),
        ),
      ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final Exercise? current = _current;
    // 首部与状态块是固定少量控件，即时构建；只有无界增长的日志行交由
    // `ListView.builder` 惰性构建（屏外行不构建），长场次（至多
    // kExerciseLogMaxEntries 条）滚动更平滑。未注入日志端口时不产生额外行。
    final List<Widget> header = <Widget>[
      Text(
        _describeAccount(widget.session),
        key: kExerciseControlAccountKey,
        style: Theme.of(context).textTheme.titleMedium,
      ),
      if (_error != null) ...<Widget>[
        const SizedBox(height: 8),
        Text(
          _error!,
          key: kExerciseControlErrorKey,
          style: TextStyle(color: Theme.of(context).colorScheme.error),
        ),
      ],
      const Divider(height: 32),
      if (current == null)
        const Text('加载中…', key: kExerciseControlLoadingKey)
      else if (current.running)
        ..._runningBlock(current)
      else
        ..._idleBlock(),
      if (widget.logs != null) ..._logPanelHeader(),
    ];
    // 日志行（仅在注入日志端口时存在）：空态占一行，否则每条一行。
    final bool logsEmpty = _logs.isEmpty;
    final int logRowCount = widget.logs == null ? 0 : (logsEmpty ? 1 : _logs.length);
    final bool running = current?.running ?? false;
    return Scaffold(
      appBar: AppBar(
        title: const Text('演习控制'),
        actions: <Widget>[
          TextButton(
            key: kExerciseControlRefreshButtonKey,
            onPressed: _busy ? null : _load,
            child: const Text('刷新'),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView.builder(
          padding: const EdgeInsets.all(16),
          itemCount: header.length + logRowCount,
          itemBuilder: (BuildContext context, int index) {
            if (index < header.length) {
              return header[index];
            }
            if (logsEmpty) {
              return Text(
                running ? '暂无日志' : '无进行中的演习',
                key: kExerciseControlLogsEmptyKey,
              );
            }
            final ExerciseLogEntry entry = _logs[index - header.length];
            return Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Text(
                describeLogEntry(entry),
                key: exerciseControlLogRowKey(entry.seq),
              ),
            );
          },
        ),
      ),
    );
  }

  /// 当前演习日志面板的**首部**（仅在注入 [ExerciseControlPage.logs] 时渲染）——
  /// 指挥台滚动日志的标题 / 计数 / 错误行 / 间隔。
  ///
  /// 仅固定少量控件；无界增长的日志行走 [build] 里的 `ListView.builder` 惰性构建。
  /// 未进行中时下方提示「无进行中的演习」（此时表已停、列表已清）。
  List<Widget> _logPanelHeader() {
    return <Widget>[
      const Divider(height: 32),
      Text(
        '当前演习日志',
        key: kExerciseControlLogsTitleKey,
        style: Theme.of(context).textTheme.titleMedium,
      ),
      const SizedBox(height: 4),
      Text('共 ${_logs.length} 条', key: kExerciseControlLogsCountKey),
      if (_logError != null) ...<Widget>[
        const SizedBox(height: 8),
        Text(
          _logError!,
          key: kExerciseControlLogsErrorKey,
          style: TextStyle(color: Theme.of(context).colorScheme.error),
        ),
      ],
      const SizedBox(height: 8),
    ];
  }
}

String _describeAccount(AccountSession session) {
  final String name = session.user?.displayName ?? session.user?.username ?? '（未知账号）';
  final List<String> roles = session.user?.roles ?? const <String>[];
  return roles.isEmpty ? name : '$name（${roles.join(' / ')}）';
}

/// 契约里这些字段都可选——缺失时如实显示破折号，不臆造默认值。
String _describeRunning(Exercise current) => '进行中：想定 ${current.planName ?? '—'}'
    '｜模式 ${current.battleMode?.wire ?? '—'}'
    '｜已进行 ${current.elapsedSeconds?.toString() ?? '—'}s'
    '｜参演 ${current.rosterCount?.toString() ?? '—'}'
    '｜在线 ${current.onlineCount?.toString() ?? '—'}';

/// 日志一行：序号 · 类型 · 对象（马甲编号 / 操作员，两者都有则并列；都无则「系统」）· 详情。
///
/// 逐字段可核对——[ExerciseLogEntry.vestNum] 与 [ExerciseLogEntry.operator] 都可能同时存在
/// （如「操作员对某马甲判死」），故两者并列展示，不互相遮蔽。
String describeLogEntry(ExerciseLogEntry entry) {
  final List<String> subjects = <String>[
    if (entry.vestNum != null) '#${entry.vestNum}',
    if (entry.operator != null) entry.operator!,
  ];
  final String who = subjects.isEmpty ? '系统' : subjects.join('｜');
  final String detail = entry.detail == null ? '' : '｜${entry.detail}';
  return '${entry.seq}｜${entry.type.wire}｜$who$detail';
}
