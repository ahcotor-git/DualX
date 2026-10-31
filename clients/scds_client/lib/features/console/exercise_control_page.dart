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

/// 当前演习状态拉取端口（`GET /exercise/current`，**无演习时也回 200 + [Exercise.running]=false**）。
typedef CurrentExerciseLoader = Future<Exercise> Function();

/// 开演端口（`POST /exercise/current`，201，回开演后的 [Exercise]）。
typedef ExerciseStarter = Future<Exercise> Function(StartExerciseRequest request);

/// 停演端口（`DELETE /exercise/current`，回 [StopExerciseResult]）。
typedef ExerciseStopper = Future<StopExerciseResult> Function();

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
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  final CurrentExerciseLoader load;

  /// 想定列表端口（`GET /plans`）——供开演选择器使用（与想定管理页同形）。
  final PlanLister listPlans;

  final ExerciseStarter start;
  final ExerciseStopper stop;

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

  @override
  void initState() {
    super.initState();
    _load();
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
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: <Widget>[
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

/// 契约里这些字段都可选——缺失时如实显示破折号，不臆造默认值。
String _describeRunning(Exercise current) => '进行中：想定 ${current.planName ?? '—'}'
    '｜模式 ${current.battleMode?.wire ?? '—'}'
    '｜已进行 ${current.elapsedSeconds?.toString() ?? '—'}s'
    '｜参演 ${current.rosterCount?.toString() ?? '—'}'
    '｜在线 ${current.onlineCount?.toString() ?? '—'}';
