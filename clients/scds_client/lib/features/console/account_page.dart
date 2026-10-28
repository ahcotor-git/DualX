import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/auth_models.dart';
import '../../shared/auth/session_store.dart';

/// 供测试与后续集成定位的控件键。
const Key kConsoleAccountIdentityKey = Key('consoleAccount.identity');
const Key kConsoleAccountExpiryKey = Key('consoleAccount.expiry');
const Key kConsoleAccountPermissionsKey = Key('consoleAccount.permissions');
const Key kConsoleAccountRefreshButtonKey = Key('consoleAccount.refreshButton');
const Key kConsoleAccountRefreshProgressKey = Key('consoleAccount.refreshProgress');
const Key kConsoleAccountReloadButtonKey = Key('consoleAccount.reloadButton');
const Key kConsoleAccountLogoutButtonKey = Key('consoleAccount.logoutButton');
const Key kConsoleAccountLogoutProgressKey = Key('consoleAccount.logoutProgress');
const Key kConsoleAccountErrorKey = Key('consoleAccount.error');
const Key kConsoleAccountNoticeKey = Key('consoleAccount.notice');
const Key kConsoleAccountSignedOutKey = Key('consoleAccount.signedOut');
const Key kConsoleAccountReturnButtonKey = Key('consoleAccount.returnButton');

/// 会话续期端口：拿当前会话换一份**新的**账号会话（生产接线调
/// `POST /api/v1/auth/refresh` 并把结果写回 `SessionStore`，见 `app/console_app.dart`）。
typedef AccountRefresh = Future<AccountSession> Function(AccountSession current);

/// 身份重载端口：拿当前会话读一次 `GET /api/v1/auth/me`。
typedef AccountReload = Future<AuthUser> Function(AccountSession current);

/// 退出端口：`POST /api/v1/auth/logout`（**无请求体**，204）；
/// 本端口只负责发请求，**不吞错**——成败都由本页裁定。
typedef AccountLogout = Future<void> Function(AccountSession current);

/// 本地会话清理端口（生产接线即 `SessionStore.clearAll()`）。
typedef SessionClearing = Future<void> Function();

/// 指挥端账号与会话页（M6 CLIENT-WINDOWS 段 6）。
///
/// 补齐段 5 登录页刻意留下的缺口：**续期（refresh）+ 身份重载（me）+ 退出（无体 logout）**。
///
/// 口径：
/// - **续期是手动按钮**，不引定时器、不自动轮询（现场可控优先）；进行中再点直接忽略
///   （单飞去重），按钮同时禁用；
/// - 会话**没有 `refreshToken`** 时不给发请求，直接行内提示需重新登录；
/// - 续期失败按契约错误码分文案：`AUTH_TOKEN_EXPIRED`（刷新令牌已过期）与
///   `AUTH_TOKEN_INVALID`（未知/篡改/已撤销）都指向「重新登录」，**不自动重试**；
/// - 到期只做**只读提示**（本地按 `expiresAt` 判），不触发任何网络动作；
/// - 退出**无论服务端是否确认，一律清本地会话**：accessToken 无状态不可撤销，
///   留着只会让现场误以为还在登录态。服务端未确认时留在本页明示，由用户点「返回登录」
///   再走导航。
class AccountPage extends StatefulWidget {
  const AccountPage({
    super.key,
    required this.session,
    required this.refresh,
    required this.reload,
    required this.logout,
    required this.clearSession,
    this.onLoggedOut,
  });

  /// 进入本页时的账号会话（续期成功后会被整体替换）。
  final AccountSession session;

  final AccountRefresh refresh;
  final AccountReload reload;
  final AccountLogout logout;

  /// 清本地会话（退出路径必调，与服务端结果无关）。
  final SessionClearing clearSession;

  /// 退出后的导航回调（生产接线：弹出本页与队伍汇总页，回到登录页）。
  final VoidCallback? onLoggedOut;

  @override
  State<AccountPage> createState() => _AccountPageState();
}

class _AccountPageState extends State<AccountPage> {
  late AccountSession _session;
  AuthUser? _user;

  String? _error;
  String? _notice;

  bool _refreshing = false;
  bool _reloading = false;
  bool _loggingOut = false;

  /// 服务端未确认退出后停在页上，等用户显式返回登录。
  bool _signedOut = false;

  @override
  void initState() {
    super.initState();
    _session = widget.session;
    _user = widget.session.user;
  }

  Future<void> _refresh() async {
    if (_refreshing) {
      return;
    }

    final String? refreshToken = _session.refreshToken;
    if (refreshToken == null || refreshToken.isEmpty) {
      setState(() {
        _error = '当前会话没有刷新令牌，无法续期，请重新登录';
        _notice = null;
      });
      return;
    }

    setState(() {
      _refreshing = true;
      _error = null;
      _notice = null;
    });

    try {
      final AccountSession next = await widget.refresh(_session);
      if (!mounted) {
        return;
      }
      setState(() {
        _refreshing = false;
        _session = next;
        // 滚动刷新回的 `TokenPair` 可能不带 `user`；缺省保留进入本页时的身份快照。
        _user = next.user ?? _user;
        _notice = '会话已续期，新到期时刻 ${_formatExpiry(next.expiresAt)}';
      });
    } on ApiException catch (failure) {
      if (!mounted) {
        return;
      }
      setState(() {
        _refreshing = false;
        _error = _describeRefresh(failure);
      });
    } catch (error) {
      // 网络不可达等非契约异常：UI 边界必须给出可读结果，不能让异常冒泡成红屏。
      if (!mounted) {
        return;
      }
      setState(() {
        _refreshing = false;
        _error = '续期失败：$error';
      });
    }
  }

  Future<void> _reload() async {
    if (_reloading) {
      return;
    }

    setState(() {
      _reloading = true;
      _error = null;
      _notice = null;
    });

    try {
      final AuthUser user = await widget.reload(_session);
      if (!mounted) {
        return;
      }
      setState(() {
        _reloading = false;
        _user = user;
        _notice = '已重新载入身份';
      });
    } on ApiException catch (failure) {
      if (!mounted) {
        return;
      }
      setState(() {
        _reloading = false;
        _error = '身份重载失败：${failure.code}';
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _reloading = false;
        _error = '身份重载失败：$error';
      });
    }
  }

  Future<void> _logout() async {
    if (_loggingOut) {
      return;
    }

    setState(() {
      _loggingOut = true;
      _error = null;
      _notice = null;
    });

    String? failureReason;
    try {
      await widget.logout(_session);
    } on ApiException catch (failure) {
      failureReason = failure.code;
    } catch (error) {
      failureReason = '$error';
    }

    // 无论服务端是否确认，一律清本地会话（accessToken 无状态，留不住也不该留）。
    await widget.clearSession();
    if (!mounted) {
      return;
    }

    if (failureReason == null) {
      setState(() => _loggingOut = false);
      widget.onLoggedOut?.call();
      return;
    }

    setState(() {
      _loggingOut = false;
      _signedOut = true;
      _error = '服务端未确认，已本地退出（$failureReason）';
    });
  }

  String _describeRefresh(ApiException failure) => switch (failure.code) {
        ApiErrorCode.authTokenExpired => '刷新令牌已过期，请重新登录',
        ApiErrorCode.authTokenInvalid => '刷新令牌无效或已被撤销，请重新登录',
        _ => '续期失败：${failure.code}',
      };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('账号与会话')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: <Widget>[
            Text(
              _describeIdentity(_user),
              key: kConsoleAccountIdentityKey,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 4),
            Text(_describeExpiry(_session), key: kConsoleAccountExpiryKey),
            const SizedBox(height: 4),
            Text(_describePermissions(_user), key: kConsoleAccountPermissionsKey),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 12),
              Text(
                _error!,
                key: kConsoleAccountErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            if (_notice != null) ...<Widget>[
              const SizedBox(height: 12),
              Text(_notice!, key: kConsoleAccountNoticeKey),
            ],
            const Divider(height: 32),
            if (_signedOut) ...<Widget>[
              Text('已退出登录', key: kConsoleAccountSignedOutKey),
              const SizedBox(height: 12),
              FilledButton(
                key: kConsoleAccountReturnButtonKey,
                onPressed: widget.onLoggedOut,
                child: const Text('返回登录'),
              ),
            ] else ...<Widget>[
              FilledButton(
                key: kConsoleAccountRefreshButtonKey,
                onPressed: _refreshing ? null : _refresh,
                child: _refreshing
                    ? const SizedBox(
                        height: 16,
                        width: 16,
                        child: CircularProgressIndicator(
                          key: kConsoleAccountRefreshProgressKey,
                          strokeWidth: 2,
                        ),
                      )
                    : const Text('续期会话'),
              ),
              const SizedBox(height: 8),
              OutlinedButton(
                key: kConsoleAccountReloadButtonKey,
                onPressed: _reloading ? null : _reload,
                child: const Text('重新载入身份'),
              ),
              const SizedBox(height: 8),
              OutlinedButton(
                key: kConsoleAccountLogoutButtonKey,
                onPressed: _loggingOut ? null : _logout,
                child: _loggingOut
                    ? const SizedBox(
                        height: 16,
                        width: 16,
                        child: CircularProgressIndicator(
                          key: kConsoleAccountLogoutProgressKey,
                          strokeWidth: 2,
                        ),
                      )
                    : const Text('退出登录'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

String _describeIdentity(AuthUser? user) {
  if (user == null) {
    return '（未知账号）';
  }
  final String name = user.displayName ?? user.username;
  return user.roles.isEmpty ? name : '$name（${user.roles.join(' / ')}）';
}

String _describePermissions(AuthUser? user) {
  final List<String> permissions = user?.permissions ?? const <String>[];
  return permissions.isEmpty ? '权限：无（服务端仍二次校验）' : '权限：${permissions.join(' / ')}';
}

/// 到期时刻**只读**呈现：本地按 `expiresAt` 判过期，不触发任何网络动作。
String _describeExpiry(AccountSession session) {
  final String at = _formatExpiry(session.expiresAt);
  final bool expired = !session.expiresAt.isAfter(DateTime.now().toUtc());
  return expired ? '令牌到期：$at（已过期，请续期或重新登录）' : '令牌到期：$at';
}

/// 不用 `intl`：手写补零格式化本地时间，避免为一行文案引依赖。
String _formatExpiry(DateTime expiresAt) {
  final DateTime local = expiresAt.toLocal();
  String two(int value) => value < 10 ? '0$value' : '$value';
  return '${local.year}-${two(local.month)}-${two(local.day)}'
      ' ${two(local.hour)}:${two(local.minute)}:${two(local.second)}';
}
