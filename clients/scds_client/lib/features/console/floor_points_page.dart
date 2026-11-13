import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/map_models.dart';
import '../../shared/auth/session_store.dart';
import '../../shared/widgets/console_error_line.dart';

/// 供测试与后续集成定位的控件键。
const Key kFloorPointsAccountKey = Key('floorPoints.account');
const Key kFloorPointsErrorKey = Key('floorPoints.error');
const Key kFloorPointsFloorNumKey = Key('floorPoints.floorNum');
const Key kFloorPointsLoadButtonKey = Key('floorPoints.loadButton');
const Key kFloorPointsSaveButtonKey = Key('floorPoints.saveButton');
const Key kFloorPointsAddButtonKey = Key('floorPoints.addButton');
const Key kFloorPointsStatusKey = Key('floorPoints.status');
const Key kFloorPointsEmptyKey = Key('floorPoints.empty');
const Key kFloorPointsFormErrorKey = Key('floorPoints.formError');

/// 按行序号（0 起，对应页面自上而下第 `index + 1` 行）取该行的
/// 点位号 / 归一化坐标 / 备注 / 删行键。
Key floorPointsPosNumKey(int index) => Key('floorPoints.posNum.$index');
Key floorPointsXKey(int index) => Key('floorPoints.x.$index');
Key floorPointsYKey(int index) => Key('floorPoints.y.$index');
Key floorPointsLabelKey(int index) => Key('floorPoints.label.$index');
Key floorPointsRemoveKey(int index) => Key('floorPoints.remove.$index');

/// 读取某楼层点位表端口（`GET /maps/{mapId}/floors/{floorNum}/points`）。
///
/// 该楼层还没录过点位表时服务端回**空列表**（不是 404）——「这一层还没标点」是常态。
typedef FloorPointsLoader = Future<FloorPointsRequest> Function(int floorNum);

/// 整体替换某楼层点位表端口（`PUT /maps/{mapId}/floors/{floorNum}/points`，**整体替换、幂等**）。
///
/// `points` 为空即清空该楼层点位表（合法）；替换只作用于本楼层，别的楼层不受影响。
typedef FloorPointsSaver = Future<FloorPointsRequest> Function(
  int floorNum,
  FloorPointsRequest request,
);

/// 楼层点位编辑页（M6 CLIENT-WINDOWS 段 55）——map 域楼层点位子域的指挥端接线。
///
/// 承载 map 域的**最后 2 个端点**：`GET` / `PUT /maps/{mapId}/floors/{floorNum}/points`。
/// 点位号 `posNum` 与协议 A `ff03` 长版（15B）上报的 `posNum` 对应，是无 UWB 时的粗定位来源，
/// 也是 COMMAND 手动置位（`moveVest`）的坐标来源（见 `CommandService` 的 `MAP_FLOOR_POINT_NOT_FOUND`）。
///
/// 口径：
/// - **坐标是归一化百分比**：`xPct` / `yPct` 取 0~1（原点左上），
///   与 UWB 标定参数的**底图像素** `xPx` / `yPx` 是两套口径，勿混；
/// - **写是整体替换**：`PUT` 落库的就是这一份清单，`null` 的 `label` 即「置空」，
///   故表单以服务端快照预填、保存时四键全提交，**不做「不传即不改」的假设**；
/// - **先「加载」再「保存」**：楼层号可改，改后必须重新加载才能保存——否则会把上一层的清单
///   整体写到新楼层上（整体替换误伤），本页据此在保存前置校验挡下；
/// - 写操作**单飞去重**（按钮禁用）：重复提交会重复替换。
class FloorPointsPage extends StatefulWidget {
  const FloorPointsPage({
    super.key,
    required this.session,
    required this.mapId,
    required this.mapName,
    required this.loadPoints,
    required this.savePoints,
    this.floorCount,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  /// 目标底图编号与名称（名称仅用于展示）。
  final String mapId;
  final String mapName;

  /// 楼层总数；**仅用于输入框提示**，本地不据此强校验（以服务端为准）。
  final int? floorCount;

  final FloorPointsLoader loadPoints;
  final FloorPointsSaver savePoints;

  @override
  State<FloorPointsPage> createState() => _FloorPointsPageState();
}

/// 一行的四个输入（点位号 / 归一化横纵坐标 / 备注）。
class _PointRow {
  _PointRow({
    String posNum = '',
    String x = '',
    String y = '',
    String label = '',
  })  : posNum = TextEditingController(text: posNum),
        x = TextEditingController(text: x),
        y = TextEditingController(text: y),
        label = TextEditingController(text: label);

  factory _PointRow.of(FloorPoint point) => _PointRow(
        posNum: '${point.posNum}',
        x: _format(point.xPct),
        y: _format(point.yPct),
        label: point.label ?? '',
      );

  final TextEditingController posNum;
  final TextEditingController x;
  final TextEditingController y;
  final TextEditingController label;

  void dispose() {
    posNum.dispose();
    x.dispose();
    y.dispose();
    label.dispose();
  }
}

class _FloorPointsPageState extends State<FloorPointsPage> {
  /// 楼层号输入（初值 1；改后须重新「加载」）。
  late final TextEditingController _floorNum;

  /// 当前编辑中的点位行；`null` 表示尚未加载过任何楼层。
  List<_PointRow>? _rows;

  /// 已加载（且当前 [_rows] 与之对应）的楼层号；保存时须与输入框一致。
  int? _loadedFloorNum;

  /// 读写单飞：任一请求在途时禁用按钮。
  bool _busy = false;

  /// 页面顶部错误。
  String? _error;

  /// 表单行内错误（校验不通过时不发请求）。
  String? _formError;

  @override
  void initState() {
    super.initState();
    _floorNum = TextEditingController(text: '1');
    _load();
  }

  @override
  void dispose() {
    _floorNum.dispose();
    _disposeRows();
    super.dispose();
  }

  void _disposeRows() {
    for (final _PointRow row in _rows ?? const <_PointRow>[]) {
      row.dispose();
    }
  }

  /// 拉取当前楼层号的点位表，并以服务端快照整表预填。
  Future<void> _load() async {
    final int? floorNum = _readFloorNum();
    if (floorNum == null) {
      return;
    }
    await _guard(() async {
      final FloorPointsRequest loaded = await widget.loadPoints(floorNum);
      if (!mounted) {
        return;
      }
      _fillRows(loaded.points);
      _loadedFloorNum = floorNum;
    });
  }

  /// 保存整表：整体替换当前已加载的楼层。
  Future<void> _save() async {
    final int? floorNum = _readFloorNum();
    if (floorNum == null) {
      return;
    }
    if (floorNum != _loadedFloorNum) {
      _formFail('楼层号已改，请先「加载」该楼层再保存');
      return;
    }
    final FloorPointsRequest? request = _readRows();
    if (request == null) {
      return;
    }
    await _guard(() async {
      final FloorPointsRequest saved = await widget.savePoints(floorNum, request);
      if (!mounted) {
        return;
      }
      _fillRows(saved.points);
      _snack('已保存楼层 $floorNum 的 ${saved.points.length} 个点位');
    });
  }

  void _addRow() {
    setState(() {
      _formError = null;
      (_rows ??= <_PointRow>[]).add(_PointRow());
    });
  }

  void _removeRow(int index) {
    final List<_PointRow> rows = _rows!;
    final _PointRow removed = rows[index];
    setState(() {
      _formError = null;
      rows.removeAt(index);
    });
    removed.dispose();
  }

  /// 以服务端快照重建整表（老的控制器一并释放）。
  void _fillRows(List<FloorPoint> points) {
    _disposeRows();
    _rows = points.map(_PointRow.of).toList(growable: true);
  }

  int? _readFloorNum() {
    final int? floorNum = int.tryParse(_floorNum.text.trim());
    if (floorNum == null) {
      _formFail('楼层号需为整数');
      return null;
    }
    return floorNum;
  }

  /// 读表为 [FloorPointsRequest]；校验不通过时设行内错误并回 `null`（不发请求）。
  ///
  /// `posNum` 需为整数（契约只要求非空）；`xPct` / `yPct` 需为 0~1 的数值
  /// （镜像服务端 `@DecimalMin("0") @DecimalMax("1")`，本地先挡一道免于白跑一趟）。
  FloorPointsRequest? _readRows() {
    final List<_PointRow> rows = _rows ?? <_PointRow>[];
    final List<FloorPoint> points = <FloorPoint>[];
    for (int i = 0; i < rows.length; i++) {
      final int line = i + 1;
      final int? posNum = int.tryParse(rows[i].posNum.text.trim());
      if (posNum == null) {
        _formFail('第 $line 行点位号需为整数');
        return null;
      }
      final double? x = double.tryParse(rows[i].x.text.trim());
      if (x == null || x < 0 || x > 1) {
        _formFail('第 $line 行 xPct 需为 0~1 的数值（归一化百分比）');
        return null;
      }
      final double? y = double.tryParse(rows[i].y.text.trim());
      if (y == null || y < 0 || y > 1) {
        _formFail('第 $line 行 yPct 需为 0~1 的数值（归一化百分比）');
        return null;
      }
      final String label = rows[i].label.text.trim();
      points.add(FloorPoint(
        posNum: posNum,
        xPct: x,
        yPct: y,
        label: label.isEmpty ? null : label,
      ));
    }
    return FloorPointsRequest(points: points);
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
        ApiErrorCode.validationFailed => '点位参数校验失败（${ApiErrorCode.validationFailed}）',
        ApiErrorCode.authForbidden => '当前账号无权限修改点位（${ApiErrorCode.authForbidden}）',
        ApiErrorCode.instanceNotReady =>
          '实例尚未就绪，请稍后重试（${ApiErrorCode.instanceNotReady}）',
        _ => '操作失败：${failure.code}',
      };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('楼层点位｜${widget.mapName}')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: <Widget>[
            Text(
              _describeAccount(widget.session),
              key: kFloorPointsAccountKey,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            if (_error != null)
              ConsoleErrorLine(text: _error!, textKey: kFloorPointsErrorKey),
            const Divider(height: 32),
            _floorPicker(context),
            const SizedBox(height: 8),
            Text(_describeStatus(), key: kFloorPointsStatusKey),
            const Divider(height: 32),
            ..._pointSection(context),
          ],
        ),
      ),
    );
  }

  Widget _floorPicker(BuildContext context) => Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: <Widget>[
          SizedBox(
            width: 200,
            child: TextField(
              key: kFloorPointsFloorNumKey,
              controller: _floorNum,
              decoration: InputDecoration(
                labelText: widget.floorCount == null
                    ? '楼层号'
                    : '楼层号（本底图共 ${widget.floorCount} 层）',
              ),
            ),
          ),
          const SizedBox(width: 12),
          FilledButton(
            key: kFloorPointsLoadButtonKey,
            onPressed: _busy ? null : _load,
            child: const Text('加载'),
          ),
        ],
      );

  List<Widget> _pointSection(BuildContext context) {
    final List<_PointRow>? rows = _rows;
    if (rows == null) {
      return const <Widget>[Text('尚未加载；填好楼层号后点「加载」。')];
    }
    return <Widget>[
      if (rows.isEmpty)
        const Text('该楼层还没有点位', key: kFloorPointsEmptyKey)
      else
        for (int i = 0; i < rows.length; i++) _pointRow(i),
      const SizedBox(height: 12),
      Align(
        alignment: Alignment.centerLeft,
        child: TextButton(
          key: kFloorPointsAddButtonKey,
          onPressed: _busy ? null : _addRow,
          child: const Text('新增点位'),
        ),
      ),
      if (_formError != null)
        ConsoleErrorLine(text: _formError!, textKey: kFloorPointsFormErrorKey),
      const SizedBox(height: 12),
      Align(
        alignment: Alignment.centerLeft,
        child: FilledButton(
          key: kFloorPointsSaveButtonKey,
          onPressed: _busy ? null : _save,
          child: const Text('保存点位表'),
        ),
      ),
    ];
  }

  Widget _pointRow(int index) {
    final _PointRow row = _rows![index];
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: <Widget>[
          SizedBox(width: 28, child: Text('${index + 1}')),
          SizedBox(
            width: 96,
            child: TextField(
              key: floorPointsPosNumKey(index),
              controller: row.posNum,
              decoration: const InputDecoration(labelText: 'posNum'),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              key: floorPointsXKey(index),
              controller: row.x,
              decoration: const InputDecoration(labelText: 'xPct 0~1'),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              key: floorPointsYKey(index),
              controller: row.y,
              decoration: const InputDecoration(labelText: 'yPct 0~1'),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              key: floorPointsLabelKey(index),
              controller: row.label,
              decoration: const InputDecoration(labelText: '备注（可空）'),
            ),
          ),
          IconButton(
            key: floorPointsRemoveKey(index),
            onPressed: _busy ? null : () => _removeRow(index),
            icon: const Icon(Icons.delete_outline),
          ),
        ],
      ),
    );
  }

  /// 状态文本：未加载 / 已加载楼层与点位个数。
  String _describeStatus() {
    final List<_PointRow>? rows = _rows;
    if (rows == null) {
      return '未加载';
    }
    return '楼层 $_loadedFloorNum｜共 ${rows.length} 个点位';
  }
}

String _describeAccount(AccountSession session) {
  final String name = session.user?.displayName ?? session.user?.username ?? '（未知账号）';
  final List<String> roles = session.user?.roles ?? const <String>[];
  return roles.isEmpty ? name : '$name（${roles.join(' / ')}）';
}

/// 归一化坐标展示：整数不带小数点，非整数保留原样（不臆造精度）。
String _format(double value) =>
    value == value.roundToDouble() ? value.toInt().toString() : '$value';
