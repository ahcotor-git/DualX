import 'package:flutter/material.dart';

import '../features/connection/connection_page.dart';
import '../features/console/console_login_page.dart';
import '../features/console/team_summary_page.dart';
import '../shared/api/api_client.dart';
import '../shared/api/auth_api.dart';
import '../shared/api/auth_models.dart';
import '../shared/api/health_api.dart';
import '../shared/api/situation_api.dart';
import '../shared/auth/session_store.dart';
import '../shared/config/connection_config.dart';
import '../shared/config/connection_config_store.dart';
import 'client_factory.dart';

/// 指挥端应用外壳（M6 CLIENT-WINDOWS 段 5）——Windows 入口装配（D12）。
///
/// 装配点与单兵端同构（连接配置存储、会话存储、HTTP 客户端工厂），差别只在链路：
/// 连接配置页（**带健康诊断**）→ 账号登录页 → 队伍汇总总览页。
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
  final ApiClient Function(ConnectionConfig config) clientFactory;

  @override
  State<ScdsConsoleApp> createState() => _ScdsConsoleAppState();
}

class _ScdsConsoleAppState extends State<ScdsConsoleApp> {
  ApiClient? _client;

  /// 换实例时释放旧客户端（连接池随配置替换一起关闭）。
  ApiClient _clientFor(ConnectionConfig config) {
    _client?.close();
    return _client = widget.clientFactory(config);
  }

  /// 诊断用**一次性客户端**：探完即关，不顶掉业务链路正在用的 [_client]。
  Future<HealthReport> _probe(ConnectionConfig config) async {
    final ApiClient probeClient = widget.clientFactory(config);
    try {
      return await InstanceHealthApi(probeClient).probe();
    } finally {
      probeClient.close();
    }
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
        builder: (BuildContext context) => ConnectionPage(
          store: widget.configStore,
          probe: _probe,
          onConnected: (ConnectionConfig config) {
            final ApiClient client = _clientFor(config);
            Navigator.of(context).push(
              MaterialPageRoute<void>(
                builder: (BuildContext pageContext) => ConsoleLoginPage(
                  login: ({required String username, required String password}) async {
                    final TokenPair pair = await AuthApi(client)
                        .login(username: username, password: password);
                    final AccountSession session = AccountSession.fromTokenPair(pair);
                    await widget.sessionStore.writeAccount(session);
                    return session;
                  },
                  onLoggedIn: (AccountSession session) {
                    final SituationApi situations = SituationApi(client);
                    Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (BuildContext pageContext) => TeamSummaryPage(
                          session: session,
                          fetch: () =>
                              situations.getTeamSummary(token: session.accessToken),
                        ),
                      ),
                    );
                  },
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
