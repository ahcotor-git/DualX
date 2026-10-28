import 'package:flutter/material.dart';

import '../../shared/config/connection_config.dart';
import '../../shared/config/connection_config_store.dart';

/// 供测试与后续集成定位的控件键（避免用文案查找）。
const Key kConnectionHostFieldKey = Key('connection.hostField');
const Key kConnectionPortFieldKey = Key('connection.portField');
const Key kConnectionSaveButtonKey = Key('connection.saveButton');
const Key kConnectionResetButtonKey = Key('connection.resetButton');
const Key kConnectionClearButtonKey = Key('connection.clearButton');
const Key kConnectionErrorKey = Key('connection.error');

/// 连接配置页（M6 CLIENT-BASE 段 4a）。
///
/// 只负责「实例地址 + 端口」的读取、校验与保存：
/// - 进入时读 [ConnectionConfigStore]，无已存值则预填 [ConnectionConfig.defaults]；
/// - 保存时先过 [ConnectionConfig] 的构造校验（空白地址 / 越界端口抛 `ArgumentError`），
///   校验通过才写库并回调 [onConnected]；
/// - 校验失败只做行内提示，**不写库、不跳转**。
///
/// 不探测连通性、不做网络请求；实例可达性由后续段的真实调用暴露。
class ConnectionPage extends StatefulWidget {
  const ConnectionPage({super.key, required this.store, this.onConnected});

  /// 连接配置存储端口。当前注入内存实现（不落盘）。
  final ConnectionConfigStore store;

  /// 保存成功后的回调（段 4b 在此接上设备登录页）。
  final ValueChanged<ConnectionConfig>? onConnected;

  @override
  State<ConnectionPage> createState() => _ConnectionPageState();
}

class _ConnectionPageState extends State<ConnectionPage> {
  final TextEditingController _host = TextEditingController();
  final TextEditingController _port = TextEditingController();

  String? _error;
  bool _loading = true;

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
