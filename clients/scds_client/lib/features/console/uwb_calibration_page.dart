import 'dart:async';

import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/map_models.dart';
import '../../shared/auth/session_store.dart';
import '../../shared/widgets/console_error_line.dart';

/// 供测试与后续集成定位的控件键。
const Key kUwbCalibrationAccountKey = Key('uwbCalibration.account');
const Key kUwbCalibrationErrorKey = Key('uwbCalibration.error');

/// 「标定参数」（`uwb-meta`）区块的控件键。
const Key kUwbMetaStatusKey = Key('uwbCalibration.meta.status');
const Key kUwbMetaReloadButtonKey = Key('uwbCalibration.meta.reloadButton');
const Key kUwbMetaSaveButtonKey = Key('uwbCalibration.meta.saveButton');
const Key kUwbMetaRealLengthKey = Key('uwbCalibration.meta.realLength');
const Key kUwbMetaRealWidthKey = Key('uwbCalibration.meta.realWidth');
const Key kUwbMetaFormErrorKey = Key('uwbCalibration.meta.formError');

/// 按基站序号（1..8）取该基站的横 / 纵像素坐标输入键。
Key uwbMetaXKey(int index) => Key('uwbCalibration.meta.x.$index');
Key uwbMetaYKey(int index) => Key('uwbCalibration.meta.y.$index');

/// 「中心基准校准」（`uwb-calibration`）区块的控件键。
const Key kUwbCalibrationStateKey = Key('uwbCalibration.calibration.state');
const Key kUwbCalibrationMessageKey = Key('uwbCalibration.calibration.message');
const Key kUwbCalibrationSampleFramesKey = Key('uwbCalibration.calibration.sampleFrames');
const Key kUwbCalibrationStartButtonKey = Key('uwbCalibration.calibration.startButton');
const Key kUwbCalibrationClearButtonKey = Key('uwbCalibration.calibration.clearButton');

/// 读取 UWB 坐标标定参数端口（`GET /maps/{mapId}/uwb-meta`）。
///
/// **未标定不是错误**：契约用 404 `MAP_NOT_CALIBRATED` 表达「图在、但还没做 UWB 标定」，
/// 与本域其它 404（`MAP_NOT_FOUND`）语义不同；本页据此切「未标定」态而非报错。
typedef UwbMetaLoader = Future<UwbMeta> Function();

/// 保存 UWB 坐标标定参数端口（`PUT /maps/{mapId}/uwb-meta`，**整体替换**）。
typedef UwbMetaSaver = Future<UwbMeta> Function(UwbMeta meta);

/// 触发中心基准校准端口（`POST /maps/{mapId}/uwb-calibration`，202 异步受理）。
///
/// [request] 为 `null` 时**不发请求体**（服务端取默认，`sampleFrames` 默认 200）。
typedef UwbCalibrationStarter = Future<UwbCalibrationStatus> Function(
  UwbCalibrationRequest? request,
);

/// 查询校准状态端口（`GET /maps/{mapId}/uwb-calibration`，轮询用）。
typedef UwbCalibrationPoller = Future<UwbCalibrationStatus> Function();

/// 清除校准结果端口（`DELETE /maps/{mapId}/uwb-calibration`，204 无正文）。
typedef UwbCalibrationClearer = Future<void> Function();

/// UWB 标定面板（M6 CLIENT-WINDOWS 段 54）——底图 UWB 标定子域的指挥端接线。
///
/// 承载 map 域 UWB 标定子域的 **5 个端点**，分两块：
/// - **标定参数**（`uwb-meta` 读 / 写）：基站像素坐标 `bs1`~`bs8`（`xPx`/`yPx`，
///   **原点左上、单位是底图像素**，勿与楼层点位的归一化百分比 `xPct`/`yPct` 混淆）
///   与两个长宽基准；`PUT` 是**整体替换**，故表单以服务端快照预填、保存时十键全提交；
/// - **中心基准校准**（`uwb-calibration` 启动 / 轮询 / 清除）：异步执行，202 只表示已受理，
///   靠 [pollCalibration] 轮询推进，`RUNNING` 之外即停轮询。
///
/// 口径：
/// - **未标定不是错误**：[loadMeta] 回 404 `MAP_NOT_CALIBRATED` 时切「未标定」态、表单可填；
///   只有 `MAP_NOT_FOUND` 等真错误才落到页面顶部提示；
/// - **校准前置由服务端判定**：UWB 链路未开（409 `DEVICE_LINK_NOT_OPEN`）与已有校准在跑
///   （409 `MAP_CALIBRATION_IN_PROGRESS`）都只映射为现场文案，不本地预判；
/// - **轮询不占单飞闸**：轮询期间按钮仍可用，避免把「启动 / 清除」一并锁死。
class UwbCalibrationPage extends StatefulWidget {
  const UwbCalibrationPage({
    super.key,
    required this.session,
    required this.mapId,
    required this.mapName,
    required this.loadMeta,
    required this.saveMeta,
    required this.startCalibration,
    required this.pollCalibration,
    required this.clearCalibration,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  /// 目标底图编号与名称（名称仅用于展示）。
  final String mapId;
  final String mapName;

  final UwbMetaLoader loadMeta;
  final UwbMetaSaver saveMeta;
  final UwbCalibrationStarter startCalibration;
  final UwbCalibrationPoller pollCalibration;
  final UwbCalibrationClearer clearCalibration;

  @override
  State<UwbCalibrationPage> createState() => _UwbCalibrationPageState();
}

class _UwbCalibrationPageState extends State<UwbCalibrationPage> {
  /// 已加载的标定参数；`null` 表示未标定（或尚未加载）。
  UwbMeta? _meta;

  /// 是否已尝试加载过标定参数（区分「加载中」与「未标定」）。
  bool _metaLoaded = false;

  /// 校准状态；`null` 表示尚未查询过。
  UwbCalibrationStatus? _calibration;

  /// `bs1`~`bs8` 的横 / 纵像素坐标输入（索引 0..7 对应 bs1..bs8）。
  late final List<TextEditingController> _bsX;
  late final List<TextEditingController> _bsY;
  late final TextEditingController _realLength;
  late final TextEditingController _realWidth;
  late final TextEditingController _sampleFrames;

  /// 写操作单飞：任一请求在途时禁用按钮。
  bool _busy = false;

  /// 页面顶部错误（真错误才落这里，「未标定」不落）。
  String? _error;

  /// 表单行内错误（校验不通过时不发请求）。
  String? _formError;

  /// 校准进度轮询定时器（仅在 `RUNNING` 期间存活）。
  Timer? _pollTimer;

  @override
  void initState() {
    super.initState();
    _bsX = List<TextEditingController>.generate(8, (int _) => TextEditingController());
    _bsY = List<TextEditingController>.generate(8, (int _) => TextEditingController());
    _realLength = TextEditingController();
    _realWidth = TextEditingController();
    _sampleFrames = TextEditingController();
    _load();
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    for (final TextEditingController controller in _bsX) {
      controller.dispose();
    }
    for (final TextEditingController controller in _bsY) {
      controller.dispose();
    }
    _realLength.dispose();
    _realWidth.dispose();
    _sampleFrames.dispose();
    super.dispose();
  }

  /// 拉标定参数与校准状态。「未标定」是常态、不是错误。
  Future<void> _load() async {
    await _guard(() async {
      await _loadMeta();
      await _refreshCalibration();
    });
  }

  Future<void> _loadMeta() async {
    try {
      final UwbMeta meta = await widget.loadMeta();
      if (!mounted) {
        return;
      }
      _meta = meta;
      _fillMeta(meta);
    } on ApiException catch (failure) {
      if (failure.code != ApiErrorCode.mapNotCalibrated) {
        rethrow;
      }
      // 图在、但还没标定：切「未标定」态，字段留空待填。
      if (!mounted) {
        return;
      }
      _meta = null;
    } finally {
      if (mounted) {
        _metaLoaded = true;
      }
    }
  }

  Future<void> _refreshCalibration() async {
    final UwbCalibrationStatus status = await widget.pollCalibration();
    if (!mounted) {
      return;
    }
    setState(() => _calibration = status);
    _syncPollTimer(status);
  }

  /// 以服务端快照预填表单（`PUT` 是整体替换，缺预填就会把未改字段冲掉）。
  void _fillMeta(UwbMeta meta) {
    final List<UwbAnchorPoint?> anchors = <UwbAnchorPoint?>[
      meta.bs1,
      meta.bs2,
      meta.bs3,
      meta.bs4,
      meta.bs5,
      meta.bs6,
      meta.bs7,
      meta.bs8,
    ];
    for (int i = 0; i < anchors.length; i++) {
      final UwbAnchorPoint? anchor = anchors[i];
      _bsX[i].text = anchor == null ? '' : _format(anchor.xPx);
      _bsY[i].text = anchor == null ? '' : _format(anchor.yPx);
    }
    _realLength.text = _format(meta.realLengthCm);
    _realWidth.text = _format(meta.realWidthCm);
  }

  Future<void> _saveMeta() async {
    final UwbMeta? meta = _readMeta();
    if (meta == null) {
      return;
    }
    await _guard(() async {
      final UwbMeta saved = await widget.saveMeta(meta);
      if (!mounted) {
        return;
      }
      _meta = saved;
      _fillMeta(saved);
      _snack('已保存 UWB 标定参数');
    });
  }

  Future<void> _startCalibration() async {
    await _guard(() async {
      final UwbCalibrationStatus status =
          await widget.startCalibration(_readCalibrationRequest());
      if (!mounted) {
        return;
      }
      setState(() => _calibration = status);
      _syncPollTimer(status);
      _snack('已受理 UWB 校准');
    });
  }

  Future<void> _clearCalibration() async {
    await _guard(() async {
      await widget.clearCalibration();
      if (!mounted) {
        return;
      }
      _stopPoll();
      setState(() => _calibration = null);
      _snack('已清除 UWB 校准结果');
    });
  }

  /// 轮询一拍：命中终态即停表；轮询失败也停表并把错误落页面。
  Future<void> _poll() async {
    if (!mounted) {
      return;
    }
    UwbCalibrationStatus status;
    try {
      status = await widget.pollCalibration();
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
    setState(() => _calibration = status);
    _syncPollTimer(status);
  }

  /// `RUNNING` 期间按 1s 轮询；其余状态（含已受理但未在跑）即停。
  void _syncPollTimer(UwbCalibrationStatus status) {
    if (status.state == UwbCalibrationState.running) {
      _pollTimer ??= Timer.periodic(const Duration(seconds: 1), (Timer _) => _poll());
    } else {
      _stopPoll();
    }
  }

  void _stopPoll() {
    _pollTimer?.cancel();
    _pollTimer = null;
  }

  /// 读表单为 [UwbMeta]；校验不通过时设行内错误并回 `null`（不发请求）。
  ///
  /// bs1~bs4 必填（矩形四角），bs5~bs8 可选（要么两空、要么两值齐全）；
  /// 长宽基准为现场实测正整数厘米。
  UwbMeta? _readMeta() {
    final List<UwbAnchorPoint?> anchors = <UwbAnchorPoint?>[];
    for (int i = 0; i < 8; i++) {
      final int index = i + 1;
      final String xText = _bsX[i].text.trim();
      final String yText = _bsY[i].text.trim();
      if (xText.isEmpty && yText.isEmpty) {
        if (index <= 4) {
          _formFail('基站 $index 为必填（矩形四角）');
          return null;
        }
        anchors.add(null);
        continue;
      }
      final double? x = double.tryParse(xText);
      final double? y = double.tryParse(yText);
      if (x == null || y == null) {
        _formFail('基站 $index 的坐标需为数值，且横纵都填');
        return null;
      }
      anchors.add(UwbAnchorPoint(xPx: x, yPx: y));
    }

    final double? length = double.tryParse(_realLength.text.trim());
    final double? width = double.tryParse(_realWidth.text.trim());
    if (length == null || length <= 0) {
      _formFail('bs1↔bs2 实测长度需为正数厘米');
      return null;
    }
    if (width == null || width <= 0) {
      _formFail('bs1↔bs4 实测宽度需为正数厘米');
      return null;
    }

    return UwbMeta(
      bs1: anchors[0]!,
      bs2: anchors[1]!,
      bs3: anchors[2]!,
      bs4: anchors[3]!,
      bs5: anchors[4],
      bs6: anchors[5],
      bs7: anchors[6],
      bs8: anchors[7],
      realLengthCm: length,
      realWidthCm: width,
    );
  }

  /// 读采样帧数；留空即「不发该键」（服务端取默认 200）。
  UwbCalibrationRequest? _readCalibrationRequest() {
    final String text = _sampleFrames.text.trim();
    if (text.isEmpty) {
      return null;
    }
    final int? frames = int.tryParse(text);
    if (frames == null || frames <= 0) {
      _formFail('采样帧数需为正整数，或留空走服务端默认');
      return null;
    }
    return UwbCalibrationRequest(sampleFrames: frames);
  }

  void _formFail(String message) {
    if (mounted) {
      setState(() {
        _formError = message;
        _error = null;
      });
    }
  }

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
      _formError = null;
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
        ApiErrorCode.mapNotFound => '底图不存在（${ApiErrorCode.mapNotFound}）',
        ApiErrorCode.mapNotCalibrated =>
          '该底图尚未标定，请先填写并保存标定参数（${ApiErrorCode.mapNotCalibrated}）',
        ApiErrorCode.mapUwbMetaIncomplete =>
          '标定参数不完整或几何不自洽（四角需退化检查、长宽为正）（${ApiErrorCode.mapUwbMetaIncomplete}）',
        ApiErrorCode.mapCalibrationInProgress =>
          '已有校准在进行，请等待完成（${ApiErrorCode.mapCalibrationInProgress}）',
        ApiErrorCode.deviceLinkNotOpen =>
          'UWB 链路未开启，请先开链路再校准（${ApiErrorCode.deviceLinkNotOpen}）',
        ApiErrorCode.validationFailed => '参数校验失败（${ApiErrorCode.validationFailed}）',
        ApiErrorCode.instanceNotReady =>
          '实例尚未就绪，请稍后重试（${ApiErrorCode.instanceNotReady}）',
        _ => '操作失败：${failure.code}',
      };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('UWB 标定｜${widget.mapName}'),
        actions: <Widget>[
          TextButton(
            key: kUwbMetaReloadButtonKey,
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
              key: kUwbCalibrationAccountKey,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            if (_error != null)
              ConsoleErrorLine(text: _error!, textKey: kUwbCalibrationErrorKey),
            const Divider(height: 32),
            _metaSection(context),
            const Divider(height: 32),
            _calibrationSection(context),
          ],
        ),
      ),
    );
  }

  Widget _metaSection(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text('标定参数（基站像素坐标）', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          Text(_describeMeta(), key: kUwbMetaStatusKey),
          const SizedBox(height: 8),
          for (int i = 0; i < 8; i++) _anchorRow(i + 1, required: i < 4),
          const SizedBox(height: 8),
          TextField(
            key: kUwbMetaRealLengthKey,
            controller: _realLength,
            decoration: const InputDecoration(labelText: 'bs1↔bs2 实测长度（厘米，正整数）'),
          ),
          const SizedBox(height: 8),
          TextField(
            key: kUwbMetaRealWidthKey,
            controller: _realWidth,
            decoration: const InputDecoration(labelText: 'bs1↔bs4 实测宽度（厘米，正整数）'),
          ),
          if (_formError != null)
            ConsoleErrorLine(text: _formError!, textKey: kUwbMetaFormErrorKey),
          const SizedBox(height: 12),
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton(
              key: kUwbMetaSaveButtonKey,
              onPressed: _busy ? null : _saveMeta,
              child: const Text('保存标定参数'),
            ),
          ),
        ],
      );

  Widget _anchorRow(int index, {required bool required}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: <Widget>[
            SizedBox(
              width: 96,
              child: Text('bs$index${required ? '（必填）' : '（可选）'}'),
            ),
            Expanded(
              child: TextField(
                key: uwbMetaXKey(index),
                controller: _bsX[index - 1],
                decoration: const InputDecoration(labelText: 'xPx'),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                key: uwbMetaYKey(index),
                controller: _bsY[index - 1],
                decoration: const InputDecoration(labelText: 'yPx'),
              ),
            ),
          ],
        ),
      );

  Widget _calibrationSection(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text('中心基准校准', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          Text(_describeCalibration(), key: kUwbCalibrationStateKey),
          if (_calibration?.message != null)
            ConsoleErrorLine(
              text: _calibration!.message!,
              textKey: kUwbCalibrationMessageKey,
              spacing: 4,
            ),
          const SizedBox(height: 12),
          TextField(
            key: kUwbCalibrationSampleFramesKey,
            controller: _sampleFrames,
            decoration: const InputDecoration(labelText: '采样帧数（留空走服务端默认 200）'),
          ),
          const SizedBox(height: 12),
          Row(
            children: <Widget>[
              FilledButton(
                key: kUwbCalibrationStartButtonKey,
                onPressed: _busy ? null : _startCalibration,
                child: const Text('启动校准'),
              ),
              const SizedBox(width: 12),
              TextButton(
                key: kUwbCalibrationClearButtonKey,
                onPressed: _busy ? null : _clearCalibration,
                child: const Text('清除校准结果'),
              ),
            ],
          ),
        ],
      );

  /// 标定参数状态文本：未加载 / 未标定 / 已标定（带长宽基准）。
  String _describeMeta() {
    final UwbMeta? meta = _meta;
    if (meta != null) {
      return '已标定｜长 ${_format(meta.realLengthCm)}cm｜宽 ${_format(meta.realWidthCm)}cm';
    }
    return _metaLoaded ? '未标定' : '加载中…';
  }

  /// 校准状态文本：未查询 / 字面量 + 进度 + 已采样帧数。
  String _describeCalibration() {
    final UwbCalibrationStatus? status = _calibration;
    if (status == null) {
      return '未查询';
    }
    final StringBuffer buffer = StringBuffer(status.state.wire);
    if (status.progressPct != null) {
      buffer.write('｜进度 ${status.progressPct}%');
    }
    if (status.sampledFrames != null) {
      buffer.write('｜已采样 ${status.sampledFrames} 帧');
    }
    return buffer.toString();
  }
}

String _describeAccount(AccountSession session) {
  final String name = session.user?.displayName ?? session.user?.username ?? '（未知账号）';
  final List<String> roles = session.user?.roles ?? const <String>[];
  return roles.isEmpty ? name : '$name（${roles.join(' / ')}）';
}

/// 坐标展示：整数不带小数点，非整数保留原样（不臆造精度）。
String _format(double value) =>
    value == value.roundToDouble() ? value.toInt().toString() : '$value';
