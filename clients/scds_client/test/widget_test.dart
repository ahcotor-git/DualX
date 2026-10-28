import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/features/connection/connection_page.dart';
import 'package:scds_client/features/device/device_login_page.dart';
import 'package:scds_client/features/situation/situation_page.dart';
import 'package:scds_client/main.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/situation_models.dart';
import 'package:scds_client/shared/auth/session_store.dart';
import 'package:scds_client/shared/config/connection_config.dart';
import 'package:scds_client/shared/config/connection_config_store.dart';

/// M6 CLIENT-BASE 段 4a / 4b / 4c：应用外壳装配 + 连接配置页 + 设备登录页 + 自身态势页。
///
/// 全部为纯 widget 测试：不启动后端、不发真实请求。
/// 连接配置用 `InMemoryConnectionConfigStore`；登录页注入 `DeviceLogin` 桩；
/// 态势页注入 `fetch` 桩并用 `tester.pump(Duration)` 推进假时钟——
/// **不落盘、不打印令牌、不依赖真实网络**。
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

  UnitState unit({
    int vestNum = 14,
    VestStatus status = VestStatus.live,
    int? hp = 100,
    int? ammo1 = 30,
    int? ammo2 = 8,
    GeoPoint? position,
    MotionState? motion = MotionState.stationary,
    int revision = 7,
  }) =>
      UnitState(
        vestNum: vestNum,
        status: status,
        revision: revision,
        hp: hp,
        ammo1: ammo1,
        ammo2: ammo2,
        position: position,
        motion: motion,
      );

  Future<void> pumpSituation(
    WidgetTester tester,
    Future<UnitState> Function() fetch, {
    Duration interval = const Duration(milliseconds: 200),
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: SituationPage(session: session(14), fetch: fetch, interval: interval),
      ),
    );
    await tester.pump();
  }

  /// 卸载整棵树：态势页 `dispose` 会取消周期定时器，避免测试结束时残留 timer。
  Future<void> unmount(WidgetTester tester) => tester.pumpWidget(const SizedBox());

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

  group('自身态势页', () {
    testWidgets('首帧到达前提示等待；成功后展示自身字段与刷新次数', (WidgetTester tester) async {
      final Completer<UnitState> pending = Completer<UnitState>();
      await pumpSituation(tester, () => pending.future, interval: const Duration(seconds: 1));

      expect(find.text('马甲 #14'), findsOneWidget);
      expect(find.text('等待首帧…'), findsOneWidget);
      expect(find.byKey(kSituationErrorKey), findsNothing);

      pending.complete(unit(
        hp: 88,
        ammo1: 30,
        ammo2: 8,
        position: const GeoPoint(
          coordinateSystem: CoordinateSystem.indoor,
          xPct: 0.25,
          yPct: 0.5,
          floorNum: 1,
          posNum: 3,
        ),
      ));
      await tester.pump();
      await tester.pump();

      expect(find.textContaining('已刷新 1 次'), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(kSituationStatusKey)).data, 'LIVE');
      expect(tester.widget<Text>(find.byKey(kSituationHpKey)).data, '88');
      expect(tester.widget<Text>(find.byKey(kSituationAmmoKey)).data, '30 / 8');
      expect(tester.widget<Text>(find.byKey(kSituationMotionKey)).data, 'STATIONARY');
      expect(
        tester.widget<Text>(find.byKey(kSituationPositionKey)).data,
        contains('室内 x=0.25% y=0.5% 层=1 点=3'),
      );

      await unmount(tester);
    });

    testWidgets('按注入周期轮询：每过一个周期成功计数 +1', (WidgetTester tester) async {
      int calls = 0;
      await pumpSituation(
        tester,
        () async {
          calls++;
          return unit();
        },
        interval: const Duration(milliseconds: 200),
      );

      expect(calls, 1);

      await tester.pump(const Duration(milliseconds: 200));
      await tester.pump();
      expect(calls, 2);
      expect(find.textContaining('已刷新 2 次'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('上一拍未回时跳过本拍，不叠加在途请求', (WidgetTester tester) async {
      int calls = 0;
      final Completer<UnitState> pending = Completer<UnitState>();
      await pumpSituation(
        tester,
        () {
          calls++;
          return pending.future;
        },
        interval: const Duration(milliseconds: 200),
      );

      expect(calls, 1);

      await tester.pump(const Duration(milliseconds: 200));
      await tester.pump(const Duration(milliseconds: 200));
      expect(calls, 1);

      pending.complete(unit());
      await tester.pump();
      await tester.pump();
      expect(find.textContaining('已刷新 1 次'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('404 DEVICE_VEST_NOT_FOUND：提示不在本场编成，但保留上次成功数据', (WidgetTester tester) async {
      int calls = 0;
      await pumpSituation(
        tester,
        () async {
          calls++;
          if (calls == 1) {
            return unit(hp: 77);
          }
          throw const ApiException(
            statusCode: 404,
            problem: ApiProblem(
              type: null,
              title: null,
              status: 404,
              code: ApiErrorCode.deviceVestNotFound,
              detail: null,
              instance: null,
              traceId: 'trace-vest',
            ),
          );
        },
        interval: const Duration(milliseconds: 200),
      );

      expect(tester.widget<Text>(find.byKey(kSituationHpKey)).data, '77');

      await tester.pump(const Duration(milliseconds: 200));
      await tester.pump();

      expect(find.textContaining('本场编成中没有该马甲'), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(kSituationHpKey)).data, '77');

      await unmount(tester);
    });

    testWidgets('网络异常：显示可读错误，不冒泡、不清空页面', (WidgetTester tester) async {
      await pumpSituation(tester, () async => throw StateError('connection refused'));

      expect(find.byKey(kSituationErrorKey), findsOneWidget);
      expect(find.textContaining('拉取失败'), findsOneWidget);
      expect(find.text('等待首帧…'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('坐标只呈现不换算：室外同时给出 BD-09 与 WGS-84', (WidgetTester tester) async {
      await pumpSituation(
        tester,
        () async => unit(
          position: const GeoPoint(
            coordinateSystem: CoordinateSystem.outdoor,
            lng: 116.397,
            lat: 39.908,
            wgs84Lng: 116.391,
            wgs84Lat: 39.902,
          ),
        ),
      );

      final String shown = tester.widget<Text>(find.byKey(kSituationPositionKey)).data!;
      expect(shown, contains('室外 BD-09 经=116.397 纬=39.908'));
      expect(shown, contains('WGS-84 经=116.391 纬=39.902'));

      await unmount(tester);
    });
  });
}
