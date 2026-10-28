import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/features/connection/connection_page.dart';
import 'package:scds_client/features/device/device_login_page.dart';
import 'package:scds_client/main.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/auth/session_store.dart';
import 'package:scds_client/shared/config/connection_config.dart';
import 'package:scds_client/shared/config/connection_config_store.dart';

/// M6 CLIENT-BASE 段 4a / 4b：应用外壳装配 + 连接配置页 + 设备登录页。
///
/// 全部为纯 widget 测试：不启动后端、不发真实请求。
/// 连接配置用 `InMemoryConnectionConfigStore`；登录页注入 `DeviceLogin` 桩，
/// **不落盘、不打印令牌**。
void main() {
  late InMemoryConnectionConfigStore store;
  late InMemorySessionStore sessionStore;

  setUp(() {
    store = InMemoryConnectionConfigStore();
    sessionStore = InMemorySessionStore();
  });

  Future<void> pumpApp(WidgetTester tester) async {
    await tester.pumpWidget(
      ScdsClientApp(configStore: store, sessionStore: sessionStore),
    );
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

  DeviceSession session(int vestNum) => DeviceSession(
        vestNum: vestNum,
        deviceToken: 'device-$vestNum',
        expiresAt: DateTime.utc(2026, 10, 29),
      );

  Future<void> pumpLogin(
    WidgetTester tester,
    DeviceLogin login, {
    ValueChanged<DeviceSession>? onLoggedIn,
  }) async {
    await tester.pumpWidget(
      MaterialApp(home: DeviceLoginPage(login: login, onLoggedIn: onLoggedIn)),
    );
    await tester.pumpAndSettle();
  }

  Future<void> enterCredentials(
    WidgetTester tester,
    String vestNum,
    String secret,
  ) async {
    await tester.enterText(find.byKey(kDeviceLoginVestFieldKey), vestNum);
    await tester.enterText(find.byKey(kDeviceLoginSecretFieldKey), secret);
  }

  Future<void> tapSubmit(WidgetTester tester) async {
    await tester.tap(find.byKey(kDeviceLoginSubmitButtonKey));
    await tester.pumpAndSettle();
  }

  group('应用外壳装配', () {
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

    testWidgets('保存合法配置后写入存储并跳转设备登录页', (WidgetTester tester) async {
      await pumpApp(tester);
      await enter(tester, '  192.168.110.110  ', '11001');

      await tapSave(tester);

      final ConnectionConfig? saved = await store.read();
      expect(saved?.host, '192.168.110.110');
      expect(saved?.port, 11001);
      expect(find.byType(DeviceLoginPage), findsOneWidget);
    });
  });

  group('连接配置页校验', () {
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

  group('设备登录页', () {
    testWidgets('编号非数字：本地报错，不调用登录端口', (WidgetTester tester) async {
      final List<(int, String)> calls = <(int, String)>[];
      await pumpLogin(tester, ({required int vestNum, required String secret}) async {
        calls.add((vestNum, secret));
        return session(vestNum);
      });

      await enterCredentials(tester, 'abc', 's3cret');
      await tapSubmit(tester);

      expect(find.text('马甲编号须为数字'), findsOneWidget);
      expect(calls, isEmpty);
    });

    testWidgets('编号为空：本地报错，不调用登录端口', (WidgetTester tester) async {
      final List<(int, String)> calls = <(int, String)>[];
      await pumpLogin(tester, ({required int vestNum, required String secret}) async {
        calls.add((vestNum, secret));
        return session(vestNum);
      });

      await enterCredentials(tester, '', 's3cret');
      await tapSubmit(tester);

      expect(find.text('马甲编号须为数字'), findsOneWidget);
      expect(calls, isEmpty);
    });

    testWidgets('密钥为空：本地报错，不调用登录端口', (WidgetTester tester) async {
      final List<(int, String)> calls = <(int, String)>[];
      await pumpLogin(tester, ({required int vestNum, required String secret}) async {
        calls.add((vestNum, secret));
        return session(vestNum);
      });

      await enterCredentials(tester, '14', '   ');
      await tapSubmit(tester);

      expect(find.text('绑定密钥不能为空'), findsOneWidget);
      expect(calls, isEmpty);
    });

    testWidgets('登录成功：端口收到编号与密钥，回调带出设备会话', (WidgetTester tester) async {
      final List<(int, String)> calls = <(int, String)>[];
      DeviceSession? loggedIn;
      await pumpLogin(
        tester,
        ({required int vestNum, required String secret}) async {
          calls.add((vestNum, secret));
          return session(vestNum);
        },
        onLoggedIn: (DeviceSession value) => loggedIn = value,
      );

      await enterCredentials(tester, ' 14 ', ' s3cret ');
      await tapSubmit(tester);

      expect(calls, <(int, String)>[(14, 's3cret')]);
      expect(loggedIn?.vestNum, 14);
      expect(loggedIn?.deviceToken, 'device-14');
      expect(find.byKey(kDeviceLoginErrorKey), findsNothing);
      expect(find.byKey(kDeviceLoginProgressKey), findsNothing);
    });

    testWidgets('401 AUTH_DEVICE_NOT_BOUND：提示未绑定，不回调', (WidgetTester tester) async {
      DeviceSession? loggedIn;
      await pumpLogin(
        tester,
        ({required int vestNum, required String secret}) async {
          throw const ApiException(
            statusCode: 401,
            problem: ApiProblem(
              type: null,
              title: null,
              status: 401,
              code: ApiErrorCode.authDeviceNotBound,
              detail: null,
              instance: null,
              traceId: 'trace-login',
            ),
          );
        },
        onLoggedIn: (DeviceSession value) => loggedIn = value,
      );

      await enterCredentials(tester, '14', 'wrong');
      await tapSubmit(tester);

      expect(find.textContaining('未绑定'), findsOneWidget);
      expect(loggedIn, isNull);
    });

    testWidgets('无 Problem 正文的 500：按状态码回落到 HTTP_500', (WidgetTester tester) async {
      await pumpLogin(tester, ({required int vestNum, required String secret}) async {
        throw const ApiException(statusCode: 500);
      });

      await enterCredentials(tester, '14', 's3cret');
      await tapSubmit(tester);

      expect(find.textContaining('HTTP_500'), findsOneWidget);
    });

    testWidgets('网络异常：显示可读错误，不冒泡', (WidgetTester tester) async {
      await pumpLogin(tester, ({required int vestNum, required String secret}) async {
        throw StateError('connection refused');
      });

      await enterCredentials(tester, '14', 's3cret');
      await tapSubmit(tester);

      expect(find.byKey(kDeviceLoginErrorKey), findsOneWidget);
      expect(find.textContaining('登录失败'), findsOneWidget);
    });

    testWidgets('提交期间禁用按钮并显示进度，完成后恢复', (WidgetTester tester) async {
      final Completer<DeviceSession> pending = Completer<DeviceSession>();
      await pumpLogin(
        tester,
        ({required int vestNum, required String secret}) => pending.future,
      );

      await enterCredentials(tester, '14', 's3cret');
      await tester.tap(find.byKey(kDeviceLoginSubmitButtonKey));
      await tester.pump();

      expect(find.byKey(kDeviceLoginProgressKey), findsOneWidget);
      expect(
        tester
            .widget<FilledButton>(find.byKey(kDeviceLoginSubmitButtonKey))
            .onPressed,
        isNull,
      );

      pending.complete(session(14));
      await tester.pumpAndSettle();

      expect(find.byKey(kDeviceLoginProgressKey), findsNothing);
      expect(
        tester
            .widget<FilledButton>(find.byKey(kDeviceLoginSubmitButtonKey))
            .onPressed,
        isNotNull,
      );
    });
  });
}
