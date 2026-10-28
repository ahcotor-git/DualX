import 'package:flutter/material.dart';

import 'features/connection/connection_page.dart';
import 'shared/config/connection_config.dart';
import 'shared/config/connection_config_store.dart';

void main() {
  runApp(ScdsClientApp(configStore: InMemoryConnectionConfigStore()));
}

/// 单兵端应用外壳（M6 CLIENT-BASE 段 4a）。
///
/// 只做「装配」：注入连接配置存储端口，并把首页指向连接配置页。
/// 段 4b 在此接上设备登录页与自身态势轮询。
class ScdsClientApp extends StatelessWidget {
  const ScdsClientApp({super.key, required this.configStore});

  /// 连接配置存储端口。当前为内存实现（不落盘）；
  /// 换平台安全存储需另行授权引依赖。
  final ConnectionConfigStore configStore;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SCDS 单兵端',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal)),
      home: Builder(
        builder: (BuildContext context) => ConnectionPage(
          store: configStore,
          onConnected: (ConnectionConfig config) {
            Navigator.of(context).push(
              MaterialPageRoute<void>(
                builder: (BuildContext pageContext) =>
                    _PendingHomePage(config: config),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// 段 4a 的临时落点：确认连接配置已保存。
///
/// 段 4b 用设备登录页替换本页，届时本类删除。
class _PendingHomePage extends StatelessWidget {
  const _PendingHomePage({required this.config});

  final ConnectionConfig config;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('连接已保存')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            Text(config.display, key: const Key('pendingHome.display')),
            const SizedBox(height: 8),
            const Text('设备登录与自身态势在段 4b 接入'),
          ],
        ),
      ),
    );
  }
}
