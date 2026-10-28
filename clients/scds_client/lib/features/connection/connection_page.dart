import 'package:flutter/material.dart';

import '../../shared/api/health_api.dart';
import '../../shared/config/connection_config.dart';
import '../../shared/config/connection_config_store.dart';

/// 供测试与后续集成定位的控件键（避免用文案查找）。
const Key kConnectionHostFieldKey = Key('connection.hostField');
const Key kConnectionPortFieldKey = Key('connection.portField');
const Key kConnectionSaveButtonKey = Key('connection.saveButton');
const Key kConnectionResetButtonKey = Key('connection.resetButton');
const Key kConnectionClearButtonKey = Key('connection.clearButton');
const Key kConnectionErrorKey = Key('connection.error');
const Key kConnectionHealthButtonKey = Key('connection.healthButton');
const Key kConnectionHealthStatusKey = Key('connection.healthStatus');
const Key kConnectionHealthDetailKey = Key('connection.healthDetail');
const Key kConnectionHealthProgressKey = Key('connection.healthProgress');

/// 健康诊断端口：给一个连接配置，回一份诊断结论（生产接线见 `app/console_app.dart`）。
///
/// 收敛成函数而不是直接依赖 `InstanceHealthApi`，是为了让本页测试注入桩、
/// 不拉起真实 HTTP。
typedef InstanceHealthProbe = Future<HealthReport> Function(ConnectionConfig config);

/// 连接配置页（M6 CLIENT-BASE 段 4a；段 5 起给指挥端挂健康诊断）。
///
/// 只负责「实例地址 + 端口」的读取、校验与保存：
/// - 进入时读 [ConnectionConfigStore]，无已存值则预填 [ConnectionConfig.defaults]；
/// - 保存时先过 [ConnectionConfig] 的构造校验（空白地址 / 越界端口抛 `ArgumentError`），
///   校验通过才写库并回调 [onConnected]；
/// - 校验失败只做行内提示，**不写库、不跳转**。
///
/// **保存不做网络请求**：实例可达性交给可选的 [probe]（指挥端「健康诊断」按钮，
/// 打无令牌的 `GET /actuator/health`）；单兵端不传 [probe]，该区域整块不渲染。
class ConnectionPage extends StatefulWidget {
  const ConnectionPage({super.key, required this.store, this.onConnected, this.probe});

  /// 连接配置存储端口。当前注入内存实现（不落盘）。
  final ConnectionConfigStore store;

  /// 保存成功后的回调（段 4b 在此接上设备登录页）。
  final ValueChanged<ConnectionConfig>? onConnected;

  /// 可选的健康诊断端口；为 `null` 时不显示诊断入口。
  final InstanceHealthProbe? probe;

  @override
  State<ConnectionPage> createState() => _ConnectionPageState();
}

class _ConnectionPageState extends State<ConnectionPage> {
  final TextEditingController _host = TextEditingController();
  final TextEditingController _port = TextEditingController();

  String? _error;
  bool _loading = true;

  HealthReport? _health;
  bool _probing = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _host.dispose();
    _port.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final ConnectionConfig config =
        await widget.store.read() ?? ConnectionConfig.defaults;
    if (!mounted) {
      return;
    }
    setState(() {
      _applyFields(config);
      _loading = false;
    });
  }

  void _applyFields(ConnectionConfig config) {
    _host.text = config.host;
    _port.text = '${config.port}';
  }

  Future<void> _save() async {
    final int? port = int.tryParse(_port.text.trim());
    if (port == null) {
      setState(() => _error = '端口须为数字');
      return;
    }

    final ConnectionConfig config;
    try {
      config = ConnectionConfig(host: _host.text, port: port);
    } on ArgumentError catch (error) {
      setState(() => _error = '${error.message}');
      return;
    }

    await widget.store.write(config);
    if (!mounted) {
      return;
    }
    setState(() {
      _error = null;
      _applyFields(config);
    });
    widget.onConnected?.call(config);
  }

  void _restoreDefaults() {
    setState(() {
      _applyFields(ConnectionConfig.defaults);
      _error = null;
      _health = null;
    });
  }

  Future<void> _clear() async {
    await widget.store.clear();
    if (!mounted) {
      return;
    }
    setState(() {
      _applyFields(ConnectionConfig.defaults);
      _error = null;
      _health = null;
    });
  }

  /// 健康诊断：**不写库、不跳转**，只对**当前字段**指向的实例探活一次。
  ///
  /// 字段本身先过与保存同一套构造校验（地址非空 / 端口数字且在区间内），
  /// 避免拿半成品地址去打网络。
  Future<void> _diagnose() async {
    final InstanceHealthProbe? probe = widget.probe;
    if (probe == null || _probing) {
      return;
    }

    final int? port = int.tryParse(_port.text.trim());
    if (port == null) {
      setState(() => _error = '端口须为数字');
      return;
    }

    final ConnectionConfig config;
    try {
      config = ConnectionConfig(host: _host.text, port: port);
    } on ArgumentError catch (error) {
      setState(() => _error = '${error.message}');
      return;
    }

    setState(() {
      _error = null;
      _probing = true;
      _health = null;
    });

    HealthReport report;
    try {
      report = await probe(config);
    } catch (error) {
      // 端口约定不可抛；但 UI 边界仍必须收敛，不能让异常冒泡成红屏。
      report = HealthReport(status: 'UNREACHABLE', detail: '无法连接实例：$error');
    }

    if (!mounted) {
      return;
    }
    setState(() {
      _probing = false;
      _health = report;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    return Scaffold(
      appBar: AppBar(title: const Text('连接配置')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              TextField(
                key: kConnectionHostFieldKey,
                controller: _host,
                autocorrect: false,
                enableSuggestions: false,
                decoration: const InputDecoration(
                  labelText: '实例地址',
                  hintText: '127.0.0.1',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                key: kConnectionPortFieldKey,
                controller: _port,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  labelText: '端口',
                  hintText: '11001',
                ),
              ),
              if (_error != null) ...<Widget>[
                const SizedBox(height: 12),
                Text(
                  _error!,
                  key: kConnectionErrorKey,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ],
              if (widget.probe != null) ...<Widget>[
                const Divider(height: 32),
                OutlinedButton(
                  key: kConnectionHealthButtonKey,
                  onPressed: _probing ? null : _diagnose,
                  child: _probing
                      ? const SizedBox(
                          height: 16,
                          width: 16,
                          child: CircularProgressIndicator(
                            key: kConnectionHealthProgressKey,
                            strokeWidth: 2,
                          ),
                        )
                      : const Text('健康诊断'),
                ),
                if (_health != null) ...<Widget>[
                  const SizedBox(height: 12),
                  Text(
                    '实例状态：${_health!.status}',
                    key: kConnectionHealthStatusKey,
                    style: TextStyle(
                      color: _health!.isUp
                          ? Theme.of(context).colorScheme.primary
                          : Theme.of(context).colorScheme.error,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(_health!.detail, key: kConnectionHealthDetailKey),
                ],
              ],
              const SizedBox(height: 24),
              FilledButton(
                key: kConnectionSaveButtonKey,
                onPressed: _save,
                child: const Text('保存并继续'),
              ),
              const SizedBox(height: 8),
              OutlinedButton(
                key: kConnectionResetButtonKey,
                onPressed: _restoreDefaults,
                child: const Text('恢复默认'),
              ),
              const SizedBox(height: 8),
              TextButton(
                key: kConnectionClearButtonKey,
                onPressed: _clear,
                child: const Text('清除已保存配置'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
