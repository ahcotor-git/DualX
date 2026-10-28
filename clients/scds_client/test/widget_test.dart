import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/features/connection/connection_page.dart';
import 'package:scds_client/main.dart';
import 'package:scds_client/shared/config/connection_config.dart';
import 'package:scds_client/shared/config/connection_config_store.dart';

/// M6 CLIENT-BASE 段 4a：入口装配与连接配置页（本文件替代脚手架默认计数器用例）。
///
/// 全部为纯 widget 测试：不启动后端、不发真实请求；
/// 存储用 `InMemoryConnectionConfigStore`，**不落盘、不碰平台安全存储**。
void main() {
  late InMemoryConnectionConfigStore store;

  setUp(() {
    store = InMemoryConnectionConfigStore();
  });

  Future<void> pumpApp(WidgetTester tester) async {
    await tester.pumpWidget(ScdsClientApp(configStore: store));
    await tester.pumpAndSettle();
  }

  String hostText(WidgetTester tester) =>
      tester.widget<TextField>(find.byKey(kConnectionHostFieldKey)).controller!.text;

  String portText(WidgetTester tester) =>
      tester.widget<TextField>(find.byKey(kConnectionPortFieldKey)).controller!.text;

  Future<void> enter(WidgetTester tester, String host, String port) async {
    await tester.enterText(find.byKey(kConnectionHostFieldKey), host);
    await tester.enterText(find.byKey(kConnectionPortFieldKey), port);
  }

  Future<void> tapSave(WidgetTester tester) async {
    await tester.tap(find.byKey(kConnectionSaveButtonKey));
    await tester.pumpAndSettle();
  }

  group('入口装配', () {
    testWidgets('启动即进入连接配置页，预填契约默认地址', (WidgetTester tester) async {
      await pumpApp(tester);

      expect(find.byType(ConnectionPage), findsOneWidget);
      expect(hostText(tester), '127.0.0.1');
      expect(portText(tester), '11001');
      expect(find.byKey(kConnectionErrorKey), findsNothing);
    });

    testWidgets('存储已有配置时预填该配置', (WidgetTester tester) async {
      await store.write(ConnectionConfig(host: '192.168.110.110', port: 11001));

      await pumpApp(tester);

      expect(hostText(tester), '192.168.110.110');
      expect(portText(tester), '11001');
    });
  });

  group('保存并继续', () {
    testWidgets('合法输入裁剪空白、写入存储并跳到确认页', (WidgetTester tester) async {
      await pumpApp(tester);
      await enter(tester, '  192.168.110.110  ', '11001');

      await tapSave(tester);

      final ConnectionConfig? saved = await store.read();
      expect(saved?.host, '192.168.110.110');
      expect(saved?.port, 11001);
      expect(find.byKey(const Key('pendingHome.display')), findsOneWidget);
      expect(find.text('192.168.110.110:11001'), findsOneWidget);
    });

    testWidgets('空白地址：行内报错，不写存储、不跳转', (WidgetTester tester) async {
      await pumpApp(tester);
      await enter(tester, '   ', '11001');

      await tapSave(tester);

      expect(find.byKey(kConnectionErrorKey), findsOneWidget);
      expect(find.text('实例地址不能为空'), findsOneWidget);
      expect(await store.read(), isNull);
      expect(find.byType(ConnectionPage), findsOneWidget);
    });

    testWidgets('端口非数字：行内报错，不写存储', (WidgetTester tester) async {
      await pumpApp(tester);
      await enter(tester, '192.168.110.110', 'abc');

      await tapSave(tester);

      expect(find.text('端口须为数字'), findsOneWidget);
      expect(await store.read(), isNull);
    });

    testWidgets('端口越界 70000：行内报错含合法区间，不写存储', (WidgetTester tester) async {
      await pumpApp(tester);
      await enter(tester, '192.168.110.110', '70000');

      await tapSave(tester);

      expect(find.byKey(kConnectionErrorKey), findsOneWidget);
      expect(find.textContaining('65535'), findsOneWidget);
      expect(await store.read(), isNull);
    });

    testWidgets('报错后修正输入可保存成功，错误提示消失', (WidgetTester tester) async {
      await pumpApp(tester);
      await enter(tester, '', '11001');
      await tapSave(tester);
      expect(find.byKey(kConnectionErrorKey), findsOneWidget);

      await enter(tester, '192.168.110.110', '11001');
      await tapSave(tester);

      expect(find.byKey(kConnectionErrorKey), findsNothing);
      expect((await store.read())?.display, '192.168.110.110:11001');
    });
  });

  group('恢复默认与清除', () {
    testWidgets('恢复默认把字段写回出厂默认，且不改存储', (WidgetTester tester) async {
      await store.write(ConnectionConfig(host: '192.168.110.110', port: 12001));
      await pumpApp(tester);

      await tester.tap(find.byKey(kConnectionResetButtonKey));
      await tester.pumpAndSettle();

      expect(hostText(tester), '127.0.0.1');
      expect(portText(tester), '11001');
      expect((await store.read())?.host, '192.168.110.110');
    });

    testWidgets('清除清空存储并把字段写回出厂默认', (WidgetTester tester) async {
      await store.write(ConnectionConfig(host: '192.168.110.110', port: 12001));
      await pumpApp(tester);

      await tester.tap(find.byKey(kConnectionClearButtonKey));
      await tester.pumpAndSettle();

      expect(await store.read(), isNull);
      expect(hostText(tester), '127.0.0.1');
      expect(portText(tester), '11001');
    });
  });
}
