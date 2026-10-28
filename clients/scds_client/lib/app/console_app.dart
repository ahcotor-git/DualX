import 'package:flutter/material.dart';

import '../features/connection/connection_page.dart';
import '../features/console/account_page.dart';
import '../features/console/console_login_page.dart';
import '../features/console/team_summary_page.dart';
import '../shared/api/api_client.dart';
import '../shared/api/auth_api.dart';
import '../shared/api/auth_models.dart';
import '../shared/api/health_api.dart';
import '../shared/api/situation_api.dart';
import '../shared/auth/refreshing_api_transport.dart';
import '../shared/auth/session_store.dart';
import '../shared/config/connection_config.dart';
import '../shared/config/connection_config_store.dart';
import 'client_factory.dart';

/// 指挥端登录路由名：会话终结（[RefreshingApiTransport.onLost]）时据此**弹回登录页**。
const String kConsoleLoginRouteName = 'console/login';

/// 指挥端应用外壳（M6 CLIENT-WINDOWS 段 5；段 6 补账号会话页）——Windows 入口装配（D12）。
///
/// 装配点与单兵端同构（连接配置存储、会话存储、HTTP 客户端工厂），差别只在链路：
/// 连接配置页（**带健康诊断**）→ 账号登录页 → 队伍汇总总览页（AppBar 可进账号与会话页）。
///
/// 账号令牌走 `AccountSession`（`accessToken`），单兵端走 `DeviceSession`——
/// 两套会话在 [SessionStore] 里分开存，互不覆盖。
class ScdsConsoleApp extends StatefulWidget {
  const ScdsConsoleApp({
    super.key,
    required this.configStore,
    required this.sessionStore,
    this.clientFactory = httpApiClient,
  });

  /// 连接配置存储端口。当前为内存实现（不落盘）。
  final ConnectionConfigStore configStore;

  /// 会话存储端口（账号 / 设备两类）。当前为内存实现（不落盘）。
  final SessionStore sessionStore;

  /// HTTP 客户端工厂；换实例即换客户端。
  ///
  /// 类型是窄端口 [ApiTransport] 而非具体 `ApiClient`——生产用 `httpApiClient`（真实 `dart:io`
  /// 连接），测试可注入脚本化传输桩，**不建连接也不发真实请求**。
  final ApiTransport Function(ConnectionConfig config) clientFactory;

  @override
  State<ScdsConsoleApp> createState() => _ScdsConsoleAppState();
}

class _ScdsConsoleAppState extends State<ScdsConsoleApp> {
  ApiTransport? _client;

  /// 当前装配的账号会话（登录时置入、续期时整体替换）。
  ///
  /// 队伍汇总的拉取闭包**每次取用时读这个字段**，续期换到的新 `accessToken`
  /// 才会真正生效；退出时清的是 [SessionStore]（本地会话），本字段留给下一次登录覆盖。
  AccountSession? _account;

  /// 换实例时释放旧客户端（连接池随配置替换一起关闭）。
  ApiTransport _clientFor(ConnectionConfig config) {
    _client?.close();
    return _client = widget.clientFactory(config);
  }

  /// 诊断用**一次性客户端**：探完即关，不顶掉业务链路正在用的 [_client]。
  Future<HealthReport> _probe(ConnectionConfig config) async {
    final ApiTransport probeClient = widget.clientFactory(config);
    try {
      return await InstanceHealthApi(probeClient).probe();
    } finally {
      probeClient.close();
    }
  }

  /// 建业务传输：把 HTTP 客户端包进认证单飞装饰器（M6 CLIENT-RESILIENCE 段 C/D）。
  ///
  /// auth 域与 situation 域客户端都挂它下面，「401 到期 → 刷新 → 重试一次」对整条链路一致生效，
  /// 页面**不必自己拼令牌、也不必自己合并续期结果**：
  /// - [RefreshingApiTransport.onRefreshed]：续期成功 → 写回 [SessionStore]（持久化口径）
  ///   并替换 [_account]（进程内后续请求取新令牌的口径）；
  /// - [RefreshingApiTransport.onLost]：会话终结（刷新被 401 拒绝）→ 清本地会话并弹回登录页；
  ///   留在页上只会误导现场。
  RefreshingApiTransport _transportFor(ApiTransport client, NavigatorState navigator) =>
      RefreshingApiTransport(
        inner: client,
        onRefreshed: (AccountSession session) async {
          _account = session;
          await widget.sessionStore.writeAccount(session);
        },
        onLost: () async {
          _account = null;
          await widget.sessionStore.clearAll();
          _returnToLogin(navigator);
        },
      );

  /// 弹回登录路由。用 `popUntil` 而非「按层数 pop」：不管当前叠了几层页面都停在登录页；
  /// 登录路由不在栈内（或已在登录页）时停在栈底。
  void _returnToLogin(NavigatorState navigator) {
    if (!mounted) {
      return;
    }
    navigator.popUntil(
      (Route<dynamic> route) => route.isFirst || route.settings.name == kConsoleLoginRouteName,
    );
  }

  @override
  void dispose() {
    _client?.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SCDS 指挥端',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo)),
      home: Builder(
        builder: (BuildContext context) {
          final NavigatorState navigator = Navigator.of(context);
          return ConnectionPage(
            store: widget.configStore,
            probe: _probe,
            onConnected: (ConnectionConfig config) {
              final ApiTransport client = _clientFor(config);
              final RefreshingApiTransport transport = _transportFor(client, navigator);
              final AuthApi auth = AuthApi(transport);
              final SituationApi situations = SituationApi(transport);

              navigator.push(
                MaterialPageRoute<void>(
                  settings: const RouteSettings(name: kConsoleLoginRouteName),
                  builder: (BuildContext pageContext) => ConsoleLoginPage(
                    login: ({required String username, required String password}) async {
                      final TokenPair pair = await auth.login(
                        username: username,
                        password: password,
                      );
                      final AccountSession session = AccountSession.fromTokenPair(pair);
                      await widget.sessionStore.writeAccount(session);
                      return session;
                    },
                    onLoggedIn: (AccountSession session) {
                      _account = session;
                      // 登录成功即把会话接进装饰器：后续域请求的 401 才有令牌可续。
                      transport.adopt(session);
                      navigator.push(
                        MaterialPageRoute<void>(
                          builder: (BuildContext pageContext) => TeamSummaryPage(
                            session: session,
                            fetch: () => situations.getTeamSummary(
                              token: _account!.accessToken,
                            ),
                            onOpenAccount: () => _openAccount(
                              navigator: navigator,
                              transport: transport,
                              auth: auth,
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                ),
              );
            },
          );
        },
      ),
    );
  }

  /// 段 6 + RES-D：打开账号与会话页（续期 / 身份重载 / 退出）。
  ///
  /// 三个端口都**委托给同一条 [RefreshingApiTransport]**：
  /// - 续期按钮 → [RefreshingApiTransport.refreshNow]，与自动续期共用单飞闸
  ///   （契约的刷新是滚动的，重放旧 `refreshToken` 会撤销该账号全部 `refreshToken`）；
  /// - 身份重载 / 退出 → 走 `AuthApi(transport)`，令牌到期时自动续期并重试一次。
  ///
  /// 续期的落盘（`SessionStore` / [_account]）与合并（保留原身份）已由装饰器统一处理，
  /// 本方法**不再自己合并会话**；退出时把装饰器里的会话一并作废（[forget]），
  /// 免得本地会话清了、装饰器还攥着旧令牌发请求。
  void _openAccount({
    required NavigatorState navigator,
    required RefreshingApiTransport transport,
    required AuthApi auth,
  }) {
    final AccountSession? current = _account;
    if (current == null) {
      return;
    }

    navigator.push(
      MaterialPageRoute<void>(
        builder: (BuildContext pageContext) => AccountPage(
          session: current,
          refresh: (AccountSession _) => transport.refreshNow(),
          reload: (AccountSession session) => auth.me(accessToken: session.accessToken),
          logout: (AccountSession session) => auth.logout(accessToken: session.accessToken),
          clearSession: () async {
            _account = null;
            transport.forget();
            await widget.sessionStore.clearAll();
          },
          onLoggedOut: () {
            // 退出后回到登录页：弹出账号页与队伍汇总页（两层）。
            if (navigator.canPop()) {
              navigator.pop();
            }
            if (navigator.canPop()) {
              navigator.pop();
            }
          },
        ),
      ),
    );
  }
}
