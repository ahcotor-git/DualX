import 'package:flutter/material.dart';

import 'features/connection/connection_page.dart';
import 'features/device/device_login_page.dart';
import 'features/situation/situation_page.dart';
import 'shared/api/api_client.dart';
import 'shared/api/auth_api.dart';
import 'shared/api/auth_models.dart';
import 'shared/api/situation_api.dart';
import 'shared/auth/session_store.dart';
import 'shared/config/connection_config.dart';
import 'shared/config/connection_config_store.dart';

void main() {
  runApp(
    ScdsClientApp(
      configStore: InMemoryConnectionConfigStore(),
      sessionStore: InMemorySessionStore(),
    ),
  );
}

/// 由连接配置造 HTTP 客户端。独立成函数，便于测试注入指向桩服务的实现。
ApiClient httpApiClient(ConnectionConfig config) => ApiClient(baseUri: config.baseUri);

/// 单兵端应用外壳（M6 CLIENT-BASE 段 4a/4b/4c）。
///
/// 装配点：连接配置存储、会话存储、HTTP 客户端工厂；导航归本类持有
/// ——连接配置页 → 设备登录页 → 自身态势页，页面之间只通过回调通信。
class ScdsClientApp extends StatefulWidget {
  const ScdsClientApp({
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
  State<ScdsClientApp> createState() => _ScdsClientAppState();
}

class _ScdsClientAppState extends State<ScdsClientApp> {
  ApiClient? _client;

  /// 换实例时释放旧客户端（连接池随配置替换一起关闭）。
  ApiClient _clientFor(ConnectionConfig config) {
    _client?.close();
    return _client = widget.clientFactory(config);
  }

  @override
  void dispose() {
    _client?.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SCDS 单兵端',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal)),
      home: Builder(
        builder: (BuildContext context) => ConnectionPage(
          store: widget.configStore,
          onConnected: (ConnectionConfig config) {
            final ApiClient client = _clientFor(config);
            Navigator.of(context).push(
              MaterialPageRoute<void>(
                builder: (BuildContext pageContext) => DeviceLoginPage(
                  login: ({required int vestNum, required String secret}) async {
                    final DeviceTokenPair pair = await AuthApi(client)
                        .deviceLogin(vestNum: vestNum, secret: secret);
                    final DeviceSession session =
                        DeviceSession.fromTokenPair(vestNum, pair);
                    await widget.sessionStore.writeDevice(session);
                    return session;
                  },
                  onLoggedIn: (DeviceSession session) {
                    final SituationApi situations = SituationApi(client);
                    Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (BuildContext pageContext) => SituationPage(
                          session: session,
                          fetch: () => situations.getVestState(
                            vestNum: session.vestNum,
                            token: session.deviceToken,
                          ),
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
