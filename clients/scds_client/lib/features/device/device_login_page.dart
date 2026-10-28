import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/auth/session_store.dart';

/// 供测试与后续集成定位的控件键。
const Key kDeviceLoginVestFieldKey = Key('deviceLogin.vestNumField');
const Key kDeviceLoginSecretFieldKey = Key('deviceLogin.secretField');
const Key kDeviceLoginSubmitButtonKey = Key('deviceLogin.submitButton');
const Key kDeviceLoginErrorKey = Key('deviceLogin.error');
const Key kDeviceLoginProgressKey = Key('deviceLogin.progress');

/// 设备登录端口：一次登录＝「调 `POST /api/v1/auth/device/login` + 落设备会话」。
///
/// 收敛成函数而不是直接依赖 `AuthApi`，是为了让本页测试注入桩、不拉起真实 HTTP；
/// 生产接线在 `main.dart`（把 `AuthApi` 与会话存储组合起来）。
typedef DeviceLogin = Future<DeviceSession> Function({
  required int vestNum,
  required String secret,
});

/// 单兵设备登录页（M6 CLIENT-BASE 段 4b）。
///
/// 只做「马甲编号 + 绑定密钥」的收集与提交：
/// - 本地校验只覆盖契约的 `required` 与类型（编号须是整数、密钥非空），**不臆造**服务端规则；
/// - 失败按契约错误码呈现（`AUTH_DEVICE_NOT_BOUND` = 未绑定 / 密钥错误），
///   其余错误展示码与状态；**不重试、不落盘、不打印令牌**；
/// - 提交期间禁用按钮，避免并发顶替自己。
class DeviceLoginPage extends StatefulWidget {
  const DeviceLoginPage({super.key, required this.login, this.onLoggedIn});

  /// 登录端口（生产实现见 `main.dart`）。
  final DeviceLogin login;

  /// 登录成功回调（段 4c 在此接上自身态势页）。
  final ValueChanged<DeviceSession>? onLoggedIn;

  @override
  State<DeviceLoginPage> createState() => _DeviceLoginPageState();
}

class _DeviceLoginPageState extends State<DeviceLoginPage> {
  final TextEditingController _vestNum = TextEditingController();
  final TextEditingController _secret = TextEditingController();

  String? _error;
  bool _submitting = false;

  @override
  void dispose() {
    _vestNum.dispose();
    _secret.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_submitting) {
      return;
    }

    final int? vestNum = int.tryParse(_vestNum.text.trim());
    if (vestNum == null) {
      setState(() => _error = '马甲编号须为数字');
      return;
    }
    final String secret = _secret.text.trim();
    if (secret.isEmpty) {
      setState(() => _error = '绑定密钥不能为空');
      return;
    }

    setState(() {
      _submitting = true;
      _error = null;
    });

    try {
      final DeviceSession session =
          await widget.login(vestNum: vestNum, secret: secret);
      if (!mounted) {
        return;
      }
      setState(() => _submitting = false);
      widget.onLoggedIn?.call(session);
    } on ApiException catch (failure) {
      if (!mounted) {
        return;
      }
      setState(() {
        _submitting = false;
        _error = _describe(failure);
      });
    } catch (error) {
      // 网络不可达等非契约异常：UI 边界必须给出可读结果，不能让异常冒泡成红屏。
      if (!mounted) {
        return;
      }
      setState(() {
        _submitting = false;
        _error = '登录失败：$error';
      });
    }
  }

  String _describe(ApiException failure) {
    if (failure.code == ApiErrorCode.authDeviceNotBound) {
      return '马甲编号或绑定密钥不正确（未绑定或已被顶替）';
    }
    return '登录失败：${failure.code}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('单兵登录')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              TextField(
                key: kDeviceLoginVestFieldKey,
                controller: _vestNum,
                enabled: !_submitting,
                keyboardType: TextInputType.number,
                autocorrect: false,
                enableSuggestions: false,
                decoration: const InputDecoration(
                  labelText: '马甲编号',
                  hintText: '14',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                key: kDeviceLoginSecretFieldKey,
                controller: _secret,
                enabled: !_submitting,
                obscureText: true,
                autocorrect: false,
                enableSuggestions: false,
                decoration: const InputDecoration(
                  labelText: '绑定密钥',
                  hintText: '设备台账中登记的绑定密钥',
                ),
              ),
              if (_error != null) ...<Widget>[
                const SizedBox(height: 12),
                Text(
                  _error!,
                  key: kDeviceLoginErrorKey,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ],
              const SizedBox(height: 24),
              FilledButton(
                key: kDeviceLoginSubmitButtonKey,
                onPressed: _submitting ? null : _submit,
                child: _submitting
                    ? const SizedBox(
                        key: kDeviceLoginProgressKey,
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Text('登录'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
