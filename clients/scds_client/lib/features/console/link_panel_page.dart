import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/device_models.dart';
import '../../shared/auth/session_store.dart';

/// 供测试与后续集成定位的控件键（段 62：device 域链路与基站 UI）。
const Key kLinkPanelAccountKey = Key('linkPanel.account');
const Key kLinkPanelRefreshButtonKey = Key('linkPanel.refreshButton');
const Key kLinkPanelLoadingKey = Key('linkPanel.loading');
const Key kLinkPanelErrorKey = Key('linkPanel.error');
const Key kLinkPanelPortsEmptyKey = Key('linkPanel.ports.empty');
const Key kLinkPanelAnchorsEmptyKey = Key('linkPanel.anchors.empty');

/// UWB 基站：新增一台（编号可填 1..8）的入口。
const Key kLinkPanelAnchorCreateKey = Key('linkPanel.anchor.create');

/// 链路开关：串口链路（`POST`/`DELETE /devices/links/serial`）与 UWB 链路
/// （`POST`/`DELETE /devices/links/uwb`）。四个按钮**恒渲染**——当前是否已开由服务端判定
/// （已开再开回 409 `DEVICE_LINK_ALREADY_OPEN`），页面不据可能过期的轮询状态禁用按钮。
const Key kLinkSerialOpenKey = Key('linkPanel.serial.open');
const Key kLinkSerialCloseKey = Key('linkPanel.serial.close');
const Key kLinkUwbOpenKey = Key('linkPanel.uwb.open');
const Key kLinkUwbCloseKey = Key('linkPanel.uwb.close');

/// 链路状态行键（按链路类型，契约要求三种链路恒定各一条）。
Key linkStatusRowKey(LinkKind kind) => Key('linkPanel.link.${kind.wire}');

/// 串口列表行键（按序号）。
Key serialPortRowKey(int index) => Key('linkPanel.port.$index');

/// UWB 基站行键与行内「配置」键。
Key anchorRowKey(int anchorId) => Key('linkPanel.anchor.$anchorId');
Key anchorEditKey(int anchorId) => Key('linkPanel.anchor.edit.$anchorId');

/// 开启串口链路对话框的控件键。
const Key kSerialLinkPortKey = Key('linkPanel.serialDialog.port');
const Key kSerialLinkBaudFieldKey = Key('linkPanel.serialDialog.baud');
const Key kSerialLinkFlowKey = Key('linkPanel.serialDialog.flow');
const Key kSerialLinkErrorKey = Key('linkPanel.serialDialog.error');
const Key kSerialLinkSaveKey = Key('linkPanel.serialDialog.save');
const Key kSerialLinkCancelKey = Key('linkPanel.serialDialog.cancel');

/// 开启 UWB 链路对话框的控件键。
const Key kUwbLinkSourceKey = Key('linkPanel.uwbDialog.source');
const Key kUwbLinkHostFieldKey = Key('linkPanel.uwbDialog.host');
const Key kUwbLinkPortFieldKey = Key('linkPanel.uwbDialog.port');
const Key kUwbLinkSerialPortKey = Key('linkPanel.uwbDialog.serialPort');
const Key kUwbLinkErrorKey = Key('linkPanel.uwbDialog.error');
const Key kUwbLinkSaveKey = Key('linkPanel.uwbDialog.save');
const Key kUwbLinkCancelKey = Key('linkPanel.uwbDialog.cancel');

/// UWB 基站配置对话框的控件键。
const Key kAnchorFormTitleKey = Key('linkPanel.anchorDialog.title');
const Key kAnchorFormIdFieldKey = Key('linkPanel.anchorDialog.id');
const Key kAnchorFormNameFieldKey = Key('linkPanel.anchorDialog.name');
const Key kAnchorFormXFieldKey = Key('linkPanel.anchorDialog.x');
const Key kAnchorFormYFieldKey = Key('linkPanel.anchorDialog.y');
const Key kAnchorFormEnabledKey = Key('linkPanel.anchorDialog.enabled');
const Key kAnchorFormErrorKey = Key('linkPanel.anchorDialog.error');
const Key kAnchorFormSaveKey = Key('linkPanel.anchorDialog.save');
const Key kAnchorFormCancelKey = Key('linkPanel.anchorDialog.cancel');

/// 链路状态读取端口（`GET /devices/links`，三种链路恒定各一条）。
typedef LinkStatusReader = Future<List<LinkStatus>> Function();

/// 本机串口列表端口（`GET /devices/links/serial-ports`）。
typedef SerialPortLister = Future<List<SerialPortInfo>> Function();

/// 开启串口链路端口（`POST /devices/links/serial`）。
typedef SerialLinkOpener = Future<LinkStatus> Function(LinkOpenSerialRequest request);

/// 关闭串口链路端口（`DELETE /devices/links/serial`，**幂等**）。
typedef SerialLinkCloser = Future<LinkStatus> Function();

/// 开启 UWB 链路端口（`POST /devices/links/uwb`，TCP_SERVER / SERIAL 二选一互斥）。
typedef UwbLinkOpener = Future<LinkStatus> Function(LinkOpenUwbRequest request);

/// 关闭 UWB 链路端口（`DELETE /devices/links/uwb`，**幂等**）。
typedef UwbLinkCloser = Future<LinkStatus> Function();

/// UWB 基站台账读取端口（`GET /devices/anchors`）。
typedef AnchorLister = Future<List<Anchor>> Function();

/// UWB 基站配置端口（`PUT /devices/anchors/{anchorId}`，upsert，编号 1..8）。
typedef AnchorWriter = Future<Anchor> Function(int anchorId, AnchorUpsertRequest request);

/// 基站配置对话框产物：编号 + 请求体（`AnchorUpsertRequest` 本身不含编号，编号走路径）。
typedef AnchorDraft = ({int anchorId, AnchorUpsertRequest request});

/// 链路与基站页（M6 CLIENT-WINDOWS 段 62）——device 域后半的指挥端接线。
///
/// 覆盖 `docs/api/paths/device.yaml` 17 端点中、段 61 未接的 **8 个**：
/// 链路状态 1（`getLinks`）、本机串口 1（`serial-ports`）、串口链路开关 2
/// （`linkSerial` POST/DELETE）、UWB 链路开关 2（`linkUwb` POST/DELETE）、
/// UWB 基站 2（`anchors` 列表 + 逐台 upsert）。单马甲链路（`getVestLink`）与
/// `watchLinks` WS 状态层**另开段**。
///
/// 口径：
/// - **状态只读展示 + 开关交服务端判定**：`OPENING` 是异步打开的中间态、`ERROR` 不计入
///   「正在用」（据 `errorMessage` 呈现）；开启 / 关闭按钮不据本地轮询状态禁用——
///   已开再开由服务端回 409 `DEVICE_LINK_ALREADY_OPEN`，端口占用回 409
///   `DEVICE_LINK_SERIAL_BUSY`，页面按错误码如实提示；
/// - **写后重拉 `GET /links`**（不拿单条返回硬合并：开关可能同时改动两族链路）；
/// - **串口必须先关闭再重开**：契约明确「不隐式先关再开」，切换接入方式亦须先关；
/// - **基站坐标是归一化 0~1**（`xPx` / `yPx`，原点左上），**不是像素**，前端不做任何翻转；
/// - **`AnchorUpsertRequest` 是整体替换**（`toJson` 恒输出四键，服务端对缺席项落
///   `0.0`/`0.0`/`false`），故对话框以快照预填四项、保存时**完整提交**。
class LinkPanelPage extends StatefulWidget {
  const LinkPanelPage({
    super.key,
    required this.session,
    required this.loadLinks,
    required this.loadPorts,
    required this.openSerial,
    required this.closeSerial,
    required this.openUwb,
    required this.closeUwb,
    required this.loadAnchors,
    required this.saveAnchor,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  final LinkStatusReader loadLinks;
  final SerialPortLister loadPorts;
  final SerialLinkOpener openSerial;
  final SerialLinkCloser closeSerial;
  final UwbLinkOpener openUwb;
  final UwbLinkCloser closeUwb;
  final AnchorLister loadAnchors;
  final AnchorWriter saveAnchor;

  @override
  State<LinkPanelPage> createState() => _LinkPanelPageState();
}

class _LinkPanelPageState extends State<LinkPanelPage> {
  List<LinkStatus>? _links;
  List<SerialPortInfo>? _ports;
  List<Anchor>? _anchors;

  /// 读写单飞：任一请求在途时禁用按钮，避免重复开 / 关链路。
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// 拉链路状态与基站台账（串口列表按需在开链对话框前读取）。
  Future<void> _load() => _guard(() async {
        final List<LinkStatus> links = await widget.loadLinks();
        final List<Anchor> anchors = await widget.loadAnchors();
        if (!mounted) {
          return;
        }
        setState(() {
          _links = links;
          _anchors = anchors;
        });
      });

  /// 读取本机串口列表并缓存；失败返回 `null`（错误已落 [_error]）。
  Future<List<SerialPortInfo>?> _fetchPorts() async {
    List<SerialPortInfo>? ports;
    await _guard(() async {
      ports = await widget.loadPorts();
    });
    if (ports != null && mounted) {
      setState(() => _ports = ports);
    }
    return ports;
  }

  /// 写成功后重拉 `GET /links` 并以 SnackBar 回执。
  Future<void> _reloadLinks(String notice) async {
    final List<LinkStatus> links = await widget.loadLinks();
    if (!mounted) {
      return;
    }
    setState(() => _links = links);
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(notice)));
  }

  Future<void> _openSerialDialog() async {
    final List<SerialPortInfo>? ports = await _fetchPorts();
    if (ports == null || !mounted) {
      return;
    }
    final LinkOpenSerialRequest? request = await showDialog<LinkOpenSerialRequest>(
      context: context,
      builder: (BuildContext dialogContext) => _SerialLinkDialog(ports: ports),
    );
    if (request == null || !mounted) {
      return;
    }
    await _guard(() async {
      await widget.openSerial(request);
      if (!mounted) {
        return;
      }
      await _reloadLinks('串口链路已开启（${request.portName}）');
    });
  }

  Future<void> _closeSerial() => _guard(() async {
        await widget.closeSerial();
        if (!mounted) {
          return;
        }
        await _reloadLinks('串口链路已关闭');
      });

  Future<void> _openUwbDialog() async {
    final List<SerialPortInfo>? ports = await _fetchPorts();
    if (ports == null || !mounted) {
      return;
    }
    final LinkOpenUwbRequest? request = await showDialog<LinkOpenUwbRequest>(
      context: context,
      builder: (BuildContext dialogContext) => _UwbLinkDialog(ports: ports),
    );
    if (request == null || !mounted) {
      return;
    }
    await _guard(() async {
      await widget.openUwb(request);
      if (!mounted) {
        return;
      }
      await _reloadLinks('UWB 链路已开启（${request.dataSource.wire}）');
    });
  }

  Future<void> _closeUwb() => _guard(() async {
        await widget.closeUwb();
        if (!mounted) {
          return;
        }
        await _reloadLinks('UWB 链路已关闭');
      });

  /// 配置基站：[anchor] 为 `null` 表示新增（编号可填 1..8），否则以快照预填且编号只读。
  Future<void> _openAnchorForm({Anchor? anchor}) async {
    final AnchorDraft? draft = await showDialog<AnchorDraft>(
      context: context,
      builder: (BuildContext dialogContext) => _AnchorFormDialog(anchor: anchor),
    );
    if (draft == null || !mounted) {
      return;
    }
    await _guard(() async {
      await widget.saveAnchor(draft.anchorId, draft.request);
      if (!mounted) {
        return;
      }
      final List<Anchor> anchors = await widget.loadAnchors();
      if (!mounted) {
        return;
      }
      setState(() => _anchors = anchors);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('已保存基站 ${draft.anchorId}')));
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
        ApiErrorCode.deviceLinkAlreadyOpen =>
          '该链路已开启，请先关闭再重开（${ApiErrorCode.deviceLinkAlreadyOpen}）',
        ApiErrorCode.deviceLinkSerialBusy =>
          '串口被占用或打开被系统拒绝（${ApiErrorCode.deviceLinkSerialBusy}）',
        ApiErrorCode.deviceLinkNotOpen =>
          '该链路当前未开启，本动作需要它先处于 OPEN（${ApiErrorCode.deviceLinkNotOpen}）',
        ApiErrorCode.validationFailed => '参数校验失败（${ApiErrorCode.validationFailed}）',
        ApiErrorCode.instanceNotReady =>
          '实例尚未就绪，请稍后重试（${ApiErrorCode.instanceNotReady}）',
        _ => '操作失败：${failure.code}',
      };

  Widget _sectionTitle(String text) => Padding(
        padding: const EdgeInsets.only(bottom: 4),
        child: Text(text, style: Theme.of(context).textTheme.titleSmall),
      );

  Widget _linkRow(LinkStatus link) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Text(_describeLink(link), key: linkStatusRowKey(link.kind)),
      );

  Widget _switchRow({
    required String label,
    required Key openKey,
    required Key closeKey,
    required VoidCallback onOpen,
    required VoidCallback onClose,
  }) =>
      Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Row(
          children: <Widget>[
            Expanded(child: Text(label)),
            TextButton(
              key: openKey,
              onPressed: _busy ? null : onOpen,
              child: const Text('开启'),
            ),
            TextButton(
              key: closeKey,
              onPressed: _busy ? null : onClose,
              child: const Text('关闭'),
            ),
          ],
        ),
      );

  Widget _anchorRow(Anchor anchor) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Row(
          children: <Widget>[
            Expanded(
              child: Text(_describeAnchor(anchor), key: anchorRowKey(anchor.anchorId)),
            ),
            TextButton(
              key: anchorEditKey(anchor.anchorId),
              onPressed: _busy ? null : () => _openAnchorForm(anchor: anchor),
              child: const Text('配置'),
            ),
          ],
        ),
      );

  @override
  Widget build(BuildContext context) {
    final List<LinkStatus>? links = _links;
    final List<SerialPortInfo>? ports = _ports;
    final List<Anchor>? anchors = _anchors;
    return Scaffold(
      appBar: AppBar(
        title: const Text('链路与基站'),
        actions: <Widget>[
          TextButton(
            key: kLinkPanelRefreshButtonKey,
            onPressed: _busy ? null : _load,
            child: const Text('刷新'),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        key: kLinkPanelAnchorCreateKey,
        onPressed: _busy ? null : () => _openAnchorForm(),
        icon: const Icon(Icons.add),
        label: const Text('配置基站'),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: <Widget>[
            Text(
              _describeAccount(widget.session),
              key: kLinkPanelAccountKey,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kLinkPanelErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            const Divider(height: 32),
            _sectionTitle('链路状态（三种链路恒定各一条）'),
            if (links == null)
              const Text('加载中…', key: kLinkPanelLoadingKey)
            else
              ...links.map(_linkRow),
            const SizedBox(height: 16),
            _sectionTitle('链路开关'),
            _switchRow(
              label: '马甲串口链路（SERIAL_VEST）',
              openKey: kLinkSerialOpenKey,
              closeKey: kLinkSerialCloseKey,
              onOpen: _openSerialDialog,
              onClose: _closeSerial,
            ),
            _switchRow(
              label: 'UWB 链路（TCP_SERVER / SERIAL 二选一）',
              openKey: kLinkUwbOpenKey,
              closeKey: kLinkUwbCloseKey,
              onOpen: _openUwbDialog,
              onClose: _closeUwb,
            ),
            const SizedBox(height: 16),
            _sectionTitle('可用串口（开启链路时读取）'),
            if (ports == null)
              const Text('尚未读取')
            else if (ports.isEmpty)
              const Text('本机没有可用串口', key: kLinkPanelPortsEmptyKey)
            else
              ...List<Widget>.generate(
                ports.length,
                (int index) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: 2),
                  child: Text(
                    _describePort(ports[index]),
                    key: serialPortRowKey(index),
                  ),
                ),
              ),
            const SizedBox(height: 16),
            _sectionTitle('UWB 基站（编号 1..8）'),
            if (anchors == null)
              const Text('加载中…')
            else if (anchors.isEmpty)
              const Text('还没有配置的基站', key: kLinkPanelAnchorsEmptyKey)
            else
              ...anchors.map(_anchorRow),
          ],
        ),
      ),
    );
  }
}

/// 开启串口链路对话框——**只收参**，请求与反馈留在「链路与基站」页。
///
/// [portName] 必填（契约 `required`）；[baudRate] / [flowControlRtsCts] 留空 / 不勾即
/// **不传**，走服务端默认（115200 / false）——与 `LinkOpenSerialRequest.toJson`
/// 「只输出非 null 键」的口径一致。
class _SerialLinkDialog extends StatefulWidget {
  const _SerialLinkDialog({required this.ports});

  /// 本机可用串口（用于预填首个串口名）。
  final List<SerialPortInfo> ports;

  @override
  State<_SerialLinkDialog> createState() => _SerialLinkDialogState();
}

class _SerialLinkDialogState extends State<_SerialLinkDialog> {
  late final TextEditingController _port;
  final TextEditingController _baud = TextEditingController();
  bool _flow = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final String? first = widget.ports
        .map((SerialPortInfo port) => port.portName)
        .firstWhere((String? name) => name != null && name.isNotEmpty, orElse: () => null);
    _port = TextEditingController(text: first ?? '');
  }

  @override
  void dispose() {
    _port.dispose();
    _baud.dispose();
    super.dispose();
  }

  void _save() {
    final String portName = _port.text.trim();
    if (portName.isEmpty) {
      setState(() => _error = '请填写串口名（如 COM7）');
      return;
    }
    final String baudText = _baud.text.trim();
    int? baudRate;
    if (baudText.isNotEmpty) {
      final int? parsed = int.tryParse(baudText);
      if (parsed == null || parsed <= 0) {
        setState(() => _error = '波特率需为正整数');
        return;
      }
      baudRate = parsed;
    }
    Navigator.of(context).pop(LinkOpenSerialRequest(
      portName: portName,
      baudRate: baudRate,
      flowControlRtsCts: _flow ? true : null,
    ));
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('开启串口链路'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            const Text('契约要求「已开先关」：链路已开启时请先「关闭」再重开，切换串口同理。'),
            const SizedBox(height: 12),
            TextField(
              key: kSerialLinkPortKey,
              controller: _port,
              decoration: const InputDecoration(labelText: '串口名（如 COM7）'),
            ),
            const SizedBox(height: 12),
            TextField(
              key: kSerialLinkBaudFieldKey,
              controller: _baud,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: '波特率（留空走服务端默认 115200）',
              ),
            ),
            CheckboxListTile(
              key: kSerialLinkFlowKey,
              value: _flow,
              onChanged: (bool? value) => setState(() => _flow = value ?? false),
              title: const Text('启用 RTS/CTS 流控'),
              dense: true,
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
            ),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kSerialLinkErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: <Widget>[
        TextButton(
          key: kSerialLinkCancelKey,
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: kSerialLinkSaveKey,
          onPressed: _save,
          child: const Text('开启'),
        ),
      ],
    );
  }
}

/// 开启 UWB 链路对话框——[UwbDataSource] 二选一：
/// `TCP_SERVER` 由服务端监听（端口硬约束 12001，此处留空即走默认）；
/// `SERIAL` 占用串口，必须给出 [serialPortName]。
class _UwbLinkDialog extends StatefulWidget {
  const _UwbLinkDialog({required this.ports});

  /// 本机可用串口（用于 SERIAL 模式预填）。
  final List<SerialPortInfo> ports;

  @override
  State<_UwbLinkDialog> createState() => _UwbLinkDialogState();
}

class _UwbLinkDialogState extends State<_UwbLinkDialog> {
  UwbDataSource _source = UwbDataSource.tcpServer;
  final TextEditingController _host = TextEditingController();
  final TextEditingController _port = TextEditingController();
  late final TextEditingController _serialPort;
  String? _error;

  @override
  void initState() {
    super.initState();
    final String? first = widget.ports
        .map((SerialPortInfo port) => port.portName)
        .firstWhere((String? name) => name != null && name.isNotEmpty, orElse: () => null);
    _serialPort = TextEditingController(text: first ?? '');
  }

  @override
  void dispose() {
    _host.dispose();
    _port.dispose();
    _serialPort.dispose();
    super.dispose();
  }

  void _save() {
    if (_source == UwbDataSource.serial) {
      final String serialPortName = _serialPort.text.trim();
      if (serialPortName.isEmpty) {
        setState(() => _error = 'SERIAL 模式需填写串口名');
        return;
      }
      Navigator.of(context).pop(LinkOpenUwbRequest(
        dataSource: UwbDataSource.serial,
        serialPortName: serialPortName,
      ));
      return;
    }

    final String host = _host.text.trim();
    final String portText = _port.text.trim();
    int? port;
    if (portText.isNotEmpty) {
      final int? parsed = int.tryParse(portText);
      if (parsed == null || parsed < 1 || parsed > 65535) {
        setState(() => _error = '监听端口需为 1..65535 的整数');
        return;
      }
      port = parsed;
    }
    Navigator.of(context).pop(LinkOpenUwbRequest(
      dataSource: UwbDataSource.tcpServer,
      host: host.isEmpty ? null : host,
      port: port,
    ));
  }

  @override
  Widget build(BuildContext context) {
    final bool serial = _source == UwbDataSource.serial;
    return AlertDialog(
      title: const Text('开启 UWB 链路'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            InputDecorator(
              decoration: const InputDecoration(labelText: '接入方式'),
              child: DropdownButtonHideUnderline(
                child: DropdownButton<UwbDataSource>(
                  key: kUwbLinkSourceKey,
                  value: _source,
                  isExpanded: true,
                  items: UwbDataSource.values
                      .map(
                        (UwbDataSource value) => DropdownMenuItem<UwbDataSource>(
                          value: value,
                          child: Text(value.wire),
                        ),
                      )
                      .toList(growable: false),
                  onChanged: (UwbDataSource? value) =>
                      setState(() => _source = value ?? UwbDataSource.tcpServer),
                ),
              ),
            ),
            const SizedBox(height: 12),
            if (serial)
              TextField(
                key: kUwbLinkSerialPortKey,
                controller: _serialPort,
                decoration: const InputDecoration(labelText: '串口名（如 COM7）'),
              )
            else ...<Widget>[
              TextField(
                key: kUwbLinkHostFieldKey,
                controller: _host,
                decoration: const InputDecoration(
                  labelText: '监听地址（留空走服务端默认 0.0.0.0）',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                key: kUwbLinkPortFieldKey,
                controller: _port,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  labelText: '监听端口（留空走硬约束默认 12001）',
                ),
              ),
            ],
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kUwbLinkErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: <Widget>[
        TextButton(
          key: kUwbLinkCancelKey,
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: kUwbLinkSaveKey,
          onPressed: _save,
          child: const Text('开启'),
        ),
      ],
    );
  }
}

/// 基站配置对话框——**只收参**，`pop(AnchorDraft)`（编号 + 请求体）。
///
/// 新增时编号可填 1..8；编辑时编号只读（路径编号即身份）。`xPx` / `yPx` 是
/// **归一化坐标 0~1**（不是像素），留空即传 `null`（服务端对缺席项落 `0.0`），
/// 非空则必须落在 0~1 之间。
class _AnchorFormDialog extends StatefulWidget {
  const _AnchorFormDialog({this.anchor});

  /// 编辑目标；`null` 表示新增。
  final Anchor? anchor;

  @override
  State<_AnchorFormDialog> createState() => _AnchorFormDialogState();
}

class _AnchorFormDialogState extends State<_AnchorFormDialog> {
  late final TextEditingController _id;
  late final TextEditingController _name;
  late final TextEditingController _x;
  late final TextEditingController _y;
  late bool _enabled;
  String? _error;

  @override
  void initState() {
    super.initState();
    final Anchor? anchor = widget.anchor;
    _id = TextEditingController(text: anchor == null ? '' : '${anchor.anchorId}');
    _name = TextEditingController(text: anchor?.name ?? '');
    _x = TextEditingController(text: anchor?.xPx?.toString() ?? '');
    _y = TextEditingController(text: anchor?.yPx?.toString() ?? '');
    _enabled = anchor?.enabled ?? true;
  }

  @override
  void dispose() {
    _id.dispose();
    _name.dispose();
    _x.dispose();
    _y.dispose();
    super.dispose();
  }

  double? _coord(TextEditingController controller, String label) {
    final String text = controller.text.trim();
    if (text.isEmpty) {
      return null;
    }
    final double? value = double.tryParse(text);
    if (value == null || value < 0 || value > 1) {
      throw FormatException('$label 需为 0~1 的归一化坐标');
    }
    return value;
  }

  void _save() {
    final int? anchorId = int.tryParse(_id.text.trim());
    if (anchorId == null || anchorId < 1 || anchorId > 8) {
      setState(() => _error = '基站编号需为 1..8 的整数');
      return;
    }
    final double? xPx;
    final double? yPx;
    try {
      xPx = _coord(_x, 'X');
      yPx = _coord(_y, 'Y');
    } on FormatException catch (failure) {
      setState(() => _error = failure.message);
      return;
    }
    final String name = _name.text.trim();
    Navigator.of(context).pop((
      anchorId: anchorId,
      request: AnchorUpsertRequest(
        name: name.isEmpty ? null : name,
        xPx: xPx,
        yPx: yPx,
        enabled: _enabled,
      ),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final bool editing = widget.anchor != null;
    return AlertDialog(
      title: Text(editing ? '配置基站' : '新增基站', key: kAnchorFormTitleKey),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            TextField(
              key: kAnchorFormIdFieldKey,
              controller: _id,
              enabled: !editing,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: '基站编号（1..8）'),
            ),
            const SizedBox(height: 12),
            TextField(
              key: kAnchorFormNameFieldKey,
              controller: _name,
              decoration: const InputDecoration(labelText: '名称'),
            ),
            const SizedBox(height: 12),
            TextField(
              key: kAnchorFormXFieldKey,
              controller: _x,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(
                labelText: 'X（归一化 0~1，原点左上）',
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              key: kAnchorFormYFieldKey,
              controller: _y,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(
                labelText: 'Y（归一化 0~1，原点左上）',
              ),
            ),
            CheckboxListTile(
              key: kAnchorFormEnabledKey,
              value: _enabled,
              onChanged: (bool? value) => setState(() => _enabled = value ?? false),
              title: const Text('启用（bs1~bs4 为矩形四角，必开）'),
              dense: true,
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
            ),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kAnchorFormErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: <Widget>[
        TextButton(
          key: kAnchorFormCancelKey,
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: kAnchorFormSaveKey,
          onPressed: _save,
          child: const Text('保存'),
        ),
      ],
    );
  }
}

String _describeAccount(AccountSession session) {
  final String name = session.user?.displayName ?? session.user?.username ?? '（未知账号）';
  final List<String> roles = session.user?.roles ?? const <String>[];
  return roles.isEmpty ? name : '$name（${roles.join(' / ')}）';
}

/// 契约里除 `kind` / `state` 外都可选——缺失时如实显示破折号，不臆造 0 / 空串。
String _describeLink(LinkStatus link) => '${link.kind.wire}'
    '｜状态 ${link.state.wire}'
    '｜目标 ${link.target ?? '—'}'
    '｜帧 ${_count(link.framesTotal)}'
    '｜丢帧 ${_count(link.droppedTotal)}'
    '｜CRC ${_count(link.crcErrorTotal)}'
    '｜错误 ${link.errorMessage ?? '—'}';

String _describePort(SerialPortInfo port) =>
    '${port.portName ?? '—'}｜${port.description ?? '—'}';

String _describeAnchor(Anchor anchor) => '基站 ${anchor.anchorId}'
    '｜名称 ${anchor.name ?? '—'}'
    '｜X ${anchor.xPx?.toString() ?? '—'}'
    '｜Y ${anchor.yPx?.toString() ?? '—'}'
    '｜${anchor.enabled == true ? '启用' : (anchor.enabled == false ? '停用' : '—')}';

String _count(int? value) => value?.toString() ?? '—';
