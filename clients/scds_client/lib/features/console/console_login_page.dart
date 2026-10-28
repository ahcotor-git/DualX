import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/auth/session_store.dart';

/// 供测试与后续集成定位的控件键。
const Key kConsoleLoginUsernameFieldKey = Key('consoleLogin.usernameField');
const Key kConsoleLoginPasswordFieldKey = Key('consoleLogin.passwordField');
const Key kConsoleLoginSubmitButtonKey = Key('consoleLogin.submitButton');
const Key kConsoleLoginErrorKey = Key('consoleLogin.error');
const Key kConsoleLoginProgressKey = Key('consoleLogin.progress');

/// 账号登录端口：一次登录＝「调 `POST /api/v1/auth/login` + 落账号会话」。
///
/// 与设备登录同理收敛成函数：本页测试注入桩、不拉起真实 HTTP；
/// 生产接线在 `app/console_app.dart`（把 `AuthApi` 与会话存储组合起来）。
typedef AccountLogin = Future<AccountSession> Function({
  required String username,
  required String password,
});

/// 指挥端账号登录页（M6 CLIENT-WINDOWS 段 5）。
///
/// 只做「账号 + 密码」的收集与提交：
/// - 本地校验只覆盖契约的 `required`（两者都非空），**不臆造**服务端规则
///   （口令强度、账号格式都由服务端裁决）；
/// - 失败按契约错误码呈现：`AUTH_BAD_CREDENTIALS`（**不区分**账号不存在与密码错误）、
///   `RATE_LIMITED`（触发限流）；其余错误展示码与状态；
///   **不重试、不落盘、不打印令牌**；
/// - 提交期间禁用按钮，避免并发顶替自己。
///
/// 本页**不实现** refresh 滚动续期与 logout（`POST /auth/logout` 无请求体）——
/// 属 M6 CLIENT-WINDOWS 的后续段，见 `snapshot.md` 的段 5 局限。
class ConsoleLoginPage extends StatefulWidget {
  const ConsoleLoginPage({super.key, required this.login, this.onLoggedIn});

  /// 登录端口（生产实现见 `app/console_app.dart`）。
  final AccountLogin login;

  /// 登录成功回调（段 5 在此接上队伍汇总总览页）。
  final ValueChanged<AccountSession>? onLoggedIn;

  @override
  State<ConsoleLoginPage> createState() => _ConsoleLoginPageState();
}

class _ConsoleLoginPageState extends State<ConsoleLoginPage> {
  final TextEditingController _username = TextEditingController();
  final TextEditingController _password = TextEditingController();

  String? _error;
  bool _submitting = false;

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_submitting) {
      return;
    }

    final String username = _username.text.trim();
    if (username.isEmpty) {
      setState(() => _error = '账号不能为空');
      return;
    }
    final String password = _password.text;
    if (password.isEmpty) {
      setState(() => _error = '密码不能为空');
      return;
    }

    setState(() {
      _submitting = true;
      _error = null;
    });

    try {
      final AccountSession session =
          await widget.login(username: username, password: password);
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

  String _describe(ApiException failure) => switch (failure.code) {
        ApiErrorCode.authBadCredentials => '账号或密码不正确',
        ApiErrorCode.rateLimited => '尝试过于频繁，请稍后再试',
        _ => '登录失败：${failure.code}',
      };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('指挥端登录')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              TextField(
                key: kConsoleLoginUsernameFieldKey,
                controller: _username,
                enabled: !_submitting,
                autocorrect: false,
                enableSuggestions: false,
                decoration: const InputDecoration(
                  labelText: '账号',
                  hintText: 'admin',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                key: kConsoleLoginPasswordFieldKey,
                controller: _password,
                enabled: !_submitting,
                obscureText: true,
                decoration: const InputDecoration(labelText: '密码'),
              ),
              if (_error != null) ...<Widget>[
                const SizedBox(height: 12),
                Text(
                  _error!,
                  key: kConsoleLoginErrorKey,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ],
              const SizedBox(height: 24),
              FilledButton(
                key: kConsoleLoginSubmitButtonKey,
                onPressed: _submitting ? null : _submit,
                child: _submitting
                    ? const SizedBox(
                        height: 16,
                        width: 16,
                        child: CircularProgressIndicator(
                          key: kConsoleLoginProgressKey,
                          strokeWidth: 2,
                        ),
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
