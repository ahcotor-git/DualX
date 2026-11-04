import 'dart:async';

import 'package:flutter/material.dart';

import '../../shared/api/api_client.dart';
import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/replay_models.dart';
import '../../shared/auth/session_store.dart';
import 'replay_list_page.dart' show describeReplaySummary;

/// 供测试与后续集成定位的控件键。
const Key kReplayExportAccountKey = Key('replayExport.account');
const Key kReplayExportSummaryKey = Key('replayExport.summary');
const Key kReplayExportFormatKey = Key('replayExport.format');
const Key kReplayExportStartButtonKey = Key('replayExport.startButton');
const Key kReplayExportStateKey = Key('replayExport.state');
const Key kReplayExportMessageKey = Key('replayExport.message');
const Key kReplayExportDownloadUrlKey = Key('replayExport.downloadUrl');
const Key kReplayExportDownloadButtonKey = Key('replayExport.downloadButton');
const Key kReplayExportErrorKey = Key('replayExport.error');

/// 发起导出端口（`POST /replays/{replayId}/exports`）——**固定回 202**。
///
/// 同一复盘同一格式的并发导出会被服务端**合并**（重复调用回同一个 `taskId`），
/// 故端口只接 [format]，调用方按当前令牌拼路径。
typedef ReplayExportStarter = Future<AsyncTask> Function(ExportFormat format);

/// 轮询导出任务端口（`GET /replays/{replayId}/exports/{taskId}`）。
typedef ReplayExportPoller = Future<AsyncTask> Function(String taskId);

/// 下载导出产物端口（`GET /replays/{replayId}/exports/{taskId}/download`，按格式回二进制）。
///
/// 该路径要求 Bearer；[ApiResponse] 保留原始字节与 `contentType`，本页不解码、直接交落盘端口。
typedef ReplayExportDownloader = Future<ApiResponse> Function(String taskId);

/// 落盘导出产物端口：把下载到的原始字节按 [filename] 交给调用方保存。
///
/// Flutter 桌面 / Android **没有内置写文件对话框**，本页**刻意不引第三方依赖**——
/// 由调用方注入（测试注入假件驱动全流程）。为 `null` 时**不渲染「下载」按钮**：
/// 此时产物仍可经 `AsyncTask.downloadUrl` 这道**短时效签名 URL** 交浏览器 / 下载器直取。
typedef ReplayExportSaver = Future<void> Function(
  String filename,
  List<int> bytes,
  String? contentType,
);

/// 轮询间隔（仅 `PENDING` / `RUNNING` 期间生效）。
const Duration kReplayExportPollInterval = Duration(seconds: 1);

/// 复盘导出面板（M6 CLIENT-WINDOWS 段 58·UI 收口）——replay 域 exports 三端点的指挥端接线。
///
/// 承载 `POST /replays/{replayId}/exports`（发起，固定 202）+ `GET .../exports/{taskId}`
/// （轮询进度）+ `GET .../exports/{taskId}/download`（按格式取二进制产物）。
///
/// 口径：
/// - **`replayId` 就是演习编号**（与列表行同源，页面不再 `GET /replays/{replayId}` 复拉概览）；
/// - **POST 固定 202 即「已受理」**——要么新建 `PENDING`、要么复用同一复盘同格式的活跃任务，
///   对客户端都是「去轮询」，故没有 200 / 202 两种成功码之分；
/// - **轮询不占单飞闸**：仅 `PENDING` / `RUNNING` 期间按 [kReplayExportPollInterval] 起表，
///   终态（`SUCCEEDED` / `FAILED`）即停表，轮询失败也停表（不每拍重试）；
/// - **产物两条取用路径**：面板给出 `AsyncTask.downloadUrl`（短时效、根相对、不带 Bearer，
///   交浏览器 / 下载器直取）；「下载」按钮走 Bearer 的 `downloadExport` 并把字节交 [ReplayExportSaver]；
/// - **可选字段缺失即不渲染 / 破折号**——`fileName` / `downloadUrl` / `expireAt` / `progressPct`
///   契约皆非必填，读不到就不显示，**不臆造 0 或占位 URL**。
class ReplayExportPage extends StatefulWidget {
  const ReplayExportPage({
    super.key,
    required this.session,
    required this.replay,
    required this.startExport,
    required this.pollExport,
    required this.downloadExport,
    this.saveBytes,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  /// 目标复盘概览（列表页已拿到，不重复 `GET /replays/{replayId}`）。
  final Replay replay;

  final ReplayExportStarter startExport;
  final ReplayExportPoller pollExport;
  final ReplayExportDownloader downloadExport;

  /// 落盘端口；为 `null` 时不渲染「下载」按钮（产物仍可走 [AsyncTask.downloadUrl]）。
  final ReplayExportSaver? saveBytes;

  @override
  State<ReplayExportPage> createState() => _ReplayExportPageState();
}

class _ReplayExportPageState extends State<ReplayExportPage> {
  ExportFormat _format = ExportFormat.xlsx;

  /// 当前导出任务；`null` 表示尚未发起。
  AsyncTask? _task;

  /// 发起该任务时选定的格式——`AsyncTask` 不回带格式，缺 `fileName` 时据此回退本地文件名
  /// （**只用于本地命名，不作业务判定**）。
  ExportFormat? _taskFormat;

  /// 用户发起动作（发起 / 下载）单飞：在途时禁用按钮。
  bool _busy = false;
  String? _error;

  /// 轮询定时器（仅 `PENDING` / `RUNNING` 期间存活）。
  Timer? _pollTimer;

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }

  /// 发起导出：按所选格式 POST（固定 202），拿到任务后按状态起表。
  Future<void> _start() => _guard(() async {
        final AsyncTask task = await widget.startExport(_format);
        if (!mounted) {
          return;
        }
        setState(() {
          _task = task;
          _taskFormat = _format;
        });
        _syncPollTimer(task);
      });

  /// 下载产物：走 Bearer 取二进制，再交落盘端口保存（不落盘端口即无此按钮）。
  Future<void> _download() => _guard(() async {
        final AsyncTask? task = _task;
        final ReplayExportSaver? save = widget.saveBytes;
        if (task == null || save == null) {
          return;
        }
        final ApiResponse response = await widget.downloadExport(task.taskId);
        if (!mounted) {
          return;
        }
        final String filename = _fileName(task);
        await save(filename, response.body, response.contentType);
        if (!mounted) {
          return;
        }
        _snack('已保存 $filename');
      });

  /// 轮询一拍：命中终态即停表；轮询失败也停表并把错误落页面（不每拍重试）。
  Future<void> _poll() async {
    final AsyncTask? task = _task;
    if (!mounted || task == null) {
      return;
    }
    AsyncTask next;
    try {
      next = await widget.pollExport(task.taskId);
    } on ApiException catch (failure) {
      _stopPoll();
      if (!mounted) {
        return;
      }
      setState(() => _error = _describeFailure(failure));
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() => _task = next);
    _syncPollTimer(next);
  }

  /// `PENDING` / `RUNNING` 期间按 [kReplayExportPollInterval] 轮询；终态（含失败）即停。
  void _syncPollTimer(AsyncTask task) {
    if (task.state == AsyncTaskState.pending || task.state == AsyncTaskState.running) {
      _pollTimer ??= Timer.periodic(kReplayExportPollInterval, (Timer _) => _poll());
    } else {
      _stopPoll();
    }
  }

  void _stopPoll() {
    _pollTimer?.cancel();
    _pollTimer = null;
  }

  /// 产物文件名：优先取服务端 `fileName`；缺失时以本地格式回退（仅用于命名）。
  String _fileName(AsyncTask task) =>
      task.fileName ?? 'replay-${widget.replay.replayId}.${(_taskFormat ?? _format).name}';

  void _snack(String notice) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(notice)));

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

  @override
  Widget build(BuildContext context) {
    final AsyncTask? task = _task;
    return Scaffold(
      appBar: AppBar(title: const Text('复盘导出')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: <Widget>[
            Text(
              _describeAccount(widget.session),
              key: kReplayExportAccountKey,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 4),
            Text(describeReplaySummary(widget.replay), key: kReplayExportSummaryKey),
            const SizedBox(height: 12),
            InputDecorator(
              decoration: const InputDecoration(labelText: '导出格式'),
              child: DropdownButtonHideUnderline(
                child: DropdownButton<ExportFormat>(
                  key: kReplayExportFormatKey,
                  value: _format,
                  isExpanded: true,
                  items: ExportFormat.values
                      .map(
                        (ExportFormat format) =>
                            DropdownMenuItem<ExportFormat>(value: format, child: Text(format.wire)),
                      )
                      .toList(),
                  onChanged: (ExportFormat? value) =>
                      setState(() => _format = value ?? _format),
                ),
              ),
            ),
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerLeft,
              child: FilledButton(
                key: kReplayExportStartButtonKey,
                onPressed: _busy ? null : _start,
                child: const Text('发起导出'),
              ),
            ),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kReplayExportErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            const Divider(height: 32),
            if (task == null)
              const Text('尚未发起导出', key: kReplayExportStateKey)
            else ...<Widget>[
              Text(_describeTask(task), key: kReplayExportStateKey),
              if (task.errorMessage != null) ...<Widget>[
                const SizedBox(height: 4),
                Text(
                  task.errorMessage!,
                  key: kReplayExportMessageKey,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ],
              if (task.downloadUrl != null) ...<Widget>[
                const SizedBox(height: 4),
                Text(task.downloadUrl!, key: kReplayExportDownloadUrlKey),
              ],
              if (widget.saveBytes != null && task.state == AsyncTaskState.succeeded) ...<Widget>[
                const SizedBox(height: 12),
                Align(
                  alignment: Alignment.centerLeft,
                  child: FilledButton(
                    key: kReplayExportDownloadButtonKey,
                    onPressed: _busy ? null : _download,
                    child: const Text('下载'),
                  ),
                ),
              ],
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

/// 任务状态一行：字面量 + 进度 / 文件名 / 过期时刻（可选字段缺失即不写，不臆造）。
String _describeTask(AsyncTask task) {
  final StringBuffer buffer = StringBuffer(task.state.wire);
  if (task.progressPct != null) {
    buffer.write('｜进度 ${task.progressPct}%');
  }
  if (task.fileName != null) {
    buffer.write('｜文件 ${task.fileName}');
  }
  if (task.expireAt != null) {
    buffer.write('｜过期 ${task.expireAt!.toIso8601String()}');
  }
  return buffer.toString();
}

String _describeFailure(ApiException failure) => switch (failure.code) {
      ApiErrorCode.replayNotFound => '复盘不存在（${ApiErrorCode.replayNotFound}）',
      ApiErrorCode.replayExportFailed =>
        '导出失败或产物已过期，请重新发起导出（${ApiErrorCode.replayExportFailed}）',
      ApiErrorCode.authForbidden => '当前账号无权导出该复盘（${ApiErrorCode.authForbidden}）',
      ApiErrorCode.validationFailed => '导出参数不合法（${ApiErrorCode.validationFailed}）',
      ApiErrorCode.instanceNotReady =>
        '实例尚未就绪，请稍后重试（${ApiErrorCode.instanceNotReady}）',
      _ => '操作失败：${failure.code}',
    };
