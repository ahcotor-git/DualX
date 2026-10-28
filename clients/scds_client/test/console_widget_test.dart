import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/app/client_factory.dart';
import 'package:scds_client/app/console_app.dart';
import 'package:scds_client/features/connection/connection_page.dart';
import 'package:scds_client/features/console/account_page.dart';
import 'package:scds_client/features/console/command_page.dart';
import 'package:scds_client/features/console/console_login_page.dart';
import 'package:scds_client/features/console/overall_situation_page.dart';
import 'package:scds_client/features/console/team_summary_page.dart';
import 'package:scds_client/shared/api/api_client.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/auth_models.dart';
import 'package:scds_client/shared/api/command_models.dart';
import 'package:scds_client/shared/api/health_api.dart';
import 'package:scds_client/shared/api/situation_models.dart';
import 'package:scds_client/shared/auth/session_store.dart';
import 'package:scds_client/shared/config/connection_config.dart';
import 'package:scds_client/shared/config/connection_config_store.dart';
import 'package:scds_client/shared/state/situation_socket.dart';

/// M6 CLIENT-WINDOWS 段 5：指挥端外壳装配 + 连接配置页健康诊断 + 账号登录页 + 队伍汇总总览页。
///
/// 全部为纯 widget 测试：不启动后端、不发真实请求。
/// 健康诊断注入 `InstanceHealthProbe` 桩；登录页注入 `AccountLogin` 桩；
/// 汇总页注入 `fetch` 桩并用 `tester.pump(Duration)` 推进假时钟——
/// **不落盘、不打印令牌、不依赖真实网络**。
///
/// RES-D 追加「指挥端认证接线」组：注入脚本化 `ApiTransport` 桩（**同样不建连接**），
/// 断言的是真实的请求序列与 `Authorization` 头——认证接线的正确性恰恰体现在
/// 「谁在第几次请求带了哪个令牌」。
void main() {
  late InMemoryConnectionConfigStore store;
  late InMemorySessionStore sessionStore;

  setUp(() {
    store = InMemoryConnectionConfigStore();
    sessionStore = InMemorySessionStore();
  });

  Future<void> pumpConsole(
    WidgetTester tester, {
    ApiTransport Function(ConnectionConfig config)? clientFactory,
  }) async {
    await tester.pumpWidget(
      ScdsConsoleApp(
        configStore: store,
        sessionStore: sessionStore,
        clientFactory: clientFactory ?? httpApiClient,
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> pumpConnection(WidgetTester tester, InstanceHealthProbe probe) async {
    await tester.pumpWidget(
      MaterialApp(home: ConnectionPage(store: store, probe: probe)),
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

  Future<void> tapDiagnose(WidgetTester tester) async {
    await tester.tap(find.byKey(kConnectionHealthButtonKey));
    await tester.pumpAndSettle();
  }

  AccountSession accountSession({
    String username = 'admin',
    List<String> roles = const <String>['OPERATOR'],
    List<String> permissions = const <String>[],
    String accessToken = 'access-1',
    String? refreshToken,
    DateTime? expiresAt,
  }) =>
      AccountSession(
        accessToken: accessToken,
        refreshToken: refreshToken,
        expiresAt: expiresAt ?? DateTime.utc(2026, 10, 29),
        user: AuthUser(
          userId: 'u-1',
          username: username,
          roles: roles,
          permissions: permissions,
        ),
      );

  Future<void> pumpLogin(
    WidgetTester tester,
    AccountLogin login, {
    ValueChanged<AccountSession>? onLoggedIn,
  }) async {
    await tester.pumpWidget(
      MaterialApp(home: ConsoleLoginPage(login: login, onLoggedIn: onLoggedIn)),
    );
    await tester.pumpAndSettle();
  }

  Future<void> enterCredentials(
    WidgetTester tester,
    String username,
    String password,
  ) async {
    await tester.enterText(find.byKey(kConsoleLoginUsernameFieldKey), username);
    await tester.enterText(find.byKey(kConsoleLoginPasswordFieldKey), password);
  }

  Future<void> tapLogin(WidgetTester tester) async {
    await tester.tap(find.byKey(kConsoleLoginSubmitButtonKey));
    await tester.pumpAndSettle();
  }

  /// 段 6：造一个契约形状的 `ApiException`（Problem 必填字段齐全）。
  ApiException apiFailure(int statusCode, String code) => ApiException(
        statusCode: statusCode,
        problem: ApiProblem(
          type: null,
          title: null,
          status: statusCode,
          code: code,
          detail: null,
          instance: null,
          traceId: 'trace-$code',
        ),
      );

  Future<void> pumpAccount(
    WidgetTester tester, {
    AccountSession? session,
    AccountRefresh? refresh,
    AccountReload? reload,
    AccountLogout? logout,
    SessionClearing? clearSession,
    VoidCallback? onLoggedOut,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: AccountPage(
          session: session ?? accountSession(),
          refresh: refresh ?? (AccountSession current) async => accountSession(),
          reload: reload ??
              (AccountSession current) async => AuthUser(
                    userId: 'u-1',
                    username: 'admin',
                    roles: const <String>['OPERATOR'],
                  ),
          logout: logout ?? (AccountSession current) async {},
          clearSession: clearSession ?? () async {},
          onLoggedOut: onLoggedOut,
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> tapAccountButton(WidgetTester tester, Key key) async {
    await tester.tap(find.byKey(key));
    await tester.pumpAndSettle();
  }

  TeamSummary teamSummary(
    Team team, {
    int? total = 6,
    int? live = 4,
    int? dead = 1,
    int? offline = 1,
    int? hitsDealt = 12,
  }) =>
      TeamSummary(
        team: team,
        total: total,
        live: live,
        dead: dead,
        offline: offline,
        hitsDealt: hitsDealt,
      );

  Future<void> pumpSummary(
    WidgetTester tester,
    Future<List<TeamSummary>> Function() fetch, {
    Duration interval = const Duration(milliseconds: 200),
    VoidCallback? onOpenAccount,
    VoidCallback? onOpenSituation,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: TeamSummaryPage(
          session: accountSession(),
          fetch: fetch,
          interval: interval,
          onOpenAccount: onOpenAccount,
          onOpenSituation: onOpenSituation,
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 D 后半：造一条契约形状的态势信封（默认全量）。
  SituationEnvelope fullEnvelope({
    required int revision,
    List<UnitState> units = const <UnitState>[],
  }) =>
      SituationEnvelope(
        epoch: 'e-1',
        revision: revision,
        full: true,
        running: true,
        serverTime: DateTime.utc(2026, 10, 28, 12),
        exerciseId: 'ex-1',
        units: units,
      );

  UnitState unitOf(int vestNum, {VestStatus status = VestStatus.live, int? hp}) =>
      UnitState(vestNum: vestNum, status: status, revision: 1, hp: hp);

  /// 把「全体态势」页直接挂在树上（注入假 fetch / 假连接器，**不建真实连接**）。
  Future<void> pumpOverall(
    WidgetTester tester, {
    required Future<SituationEnvelope> Function({int? sinceRevision}) fetch,
    required Future<SituationSocket> Function({int? sinceRevision}) connect,
    void Function(int vestNum)? onCommand,
    Future<CommandAck> Function(ReloadRequest request)? reloadAll,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: OverallSituationPage(
          session: accountSession(),
          fetch: fetch,
          connect: connect,
          onCommand: onCommand,
          reloadAll: reloadAll,
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 47：造一条契约形状的受理回执。
  CommandAck ackOf({
    int vestNum = 14,
    String command = 'DIE',
    bool accepted = true,
    String? downlinkHex,
  }) =>
      CommandAck(
        vestNum: vestNum,
        command: command,
        accepted: accepted,
        downlinkHex: downlinkHex,
      );

  /// 段 47：未注入的指令端口被触发即判失败（用例应只点注入过的按钮）。
  Future<CommandAck> unexpectedCommand() async => fail('未预期的指令下发');

  /// 段 47：单兵指令页直接挂树——六个端口都是闭包桩，**不发真实请求**。
  ///
  /// 页面较长（`ListView` 懒构建），把测试视口放大到一屏容纳全部控件，
  /// 免得底部的回执 / 错误文本因在屏外而未构建。
  Future<void> pumpCommand(
    WidgetTester tester, {
    int vestNum = 14,
    required Future<CommandAck> Function() kill,
    Future<CommandAck> Function()? revive,
    Future<CommandAck> Function()? injure,
    Future<CommandAck> Function()? outOfRange,
    Future<CommandAck> Function(ReloadRequest request)? reload,
    Future<CommandAck> Function(VestConfigRequest request)? setConfig,
  }) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: CommandPage(
          vestNum: vestNum,
          session: accountSession(),
          kill: kill,
          revive: revive ?? unexpectedCommand,
          injure: injure ?? unexpectedCommand,
          outOfRange: outOfRange ?? unexpectedCommand,
          reload: reload ?? (ReloadRequest _) => unexpectedCommand(),
          setConfig: setConfig ?? (VestConfigRequest _) => unexpectedCommand(),
        ),
      ),
    );
    await tester.pump();
  }

  /// 卸载整棵树：汇总页 `dispose` 会取消周期定时器，避免测试结束时残留 timer。
  Future<void> unmount(WidgetTester tester) => tester.pumpWidget(const SizedBox());

  group('指挥端外壳装配', () {
    testWidgets('启动即进入连接配置页，预填契约默认地址且带健康诊断入口', (WidgetTester tester) async {
      await pumpConsole(tester);

      expect(find.byType(ConnectionPage), findsOneWidget);
      expect(hostText(tester), '127.0.0.1');
      expect(portText(tester), '11001');
      expect(find.byKey(kConnectionHealthButtonKey), findsOneWidget);
      expect(find.byKey(kConnectionHealthStatusKey), findsNothing);
    });

    testWidgets('存储已有配置时预填该配置', (WidgetTester tester) async {
      await store.write(ConnectionConfig(host: '192.168.110.110', port: 11001));

      await pumpConsole(tester);

      expect(hostText(tester), '192.168.110.110');
      expect(portText(tester), '11001');
    });

    testWidgets('保存合法配置后写入存储并跳转指挥端登录页', (WidgetTester tester) async {
      await pumpConsole(tester);
      await enter(tester, '  192.168.110.110  ', '11001');

      await tapSave(tester);

      final ConnectionConfig? saved = await store.read();
      expect(saved?.host, '192.168.110.110');
      expect(saved?.port, 11001);
      expect(find.byType(ConsoleLoginPage), findsOneWidget);
    });

    testWidgets('空白地址：行内报错，不写存储、不跳转', (WidgetTester tester) async {
      await pumpConsole(tester);
      await enter(tester, '   ', '11001');

      await tapSave(tester);

      expect(find.byKey(kConnectionErrorKey), findsOneWidget);
      expect(find.text('实例地址不能为空'), findsOneWidget);
      expect(await store.read(), isNull);
      expect(find.byType(ConnectionPage), findsOneWidget);
    });
  });

  group('连接配置页健康诊断', () {
    testWidgets('未点诊断按钮时不探测；点击后按结果展示状态与结论', (WidgetTester tester) async {
      final List<ConnectionConfig> calls = <ConnectionConfig>[];
      await pumpConnection(tester, (ConnectionConfig config) async {
        calls.add(config);
        return const HealthReport(status: 'UP', httpStatus: 200, detail: '实例就绪（HTTP 200）');
      });

      expect(calls, isEmpty);
      expect(find.byKey(kConnectionHealthStatusKey), findsNothing);

      await tapDiagnose(tester);

      expect(calls.length, 1);
      expect(calls.single.display, '127.0.0.1:11001');
      expect(tester.widget<Text>(find.byKey(kConnectionHealthStatusKey)).data, '实例状态：UP');
      expect(
        tester.widget<Text>(find.byKey(kConnectionHealthDetailKey)).data,
        contains('实例就绪'),
      );
      expect(find.byKey(kConnectionHealthProgressKey), findsNothing);
    });

    testWidgets('诊断用当前字段而非已存配置：改地址后按新地址探测', (WidgetTester tester) async {
      final List<ConnectionConfig> calls = <ConnectionConfig>[];
      await store.write(ConnectionConfig(host: '10.0.0.9', port: 11001));
      await pumpConnection(tester, (ConnectionConfig config) async {
        calls.add(config);
        return const HealthReport(status: 'UP', httpStatus: 200, detail: '实例就绪（HTTP 200）');
      });
      await enter(tester, '192.168.110.110', '11001');

      await tapDiagnose(tester);

      expect(calls.single.display, '192.168.110.110:11001');
      final ConnectionConfig? stillStored = await store.read();
      expect(stillStored?.host, '10.0.0.9');
    });

    testWidgets('实例可达但未就绪 503：显示 HTTP_503 且给出依赖提示', (WidgetTester tester) async {
      await pumpConnection(
        tester,
        (ConnectionConfig config) async => const HealthReport(
          status: 'HTTP_503',
          httpStatus: 503,
          detail: '实例可达但未就绪（HTTP 503）——数据库/依赖可能尚未就绪',
        ),
      );

      await tapDiagnose(tester);

      expect(tester.widget<Text>(find.byKey(kConnectionHealthStatusKey)).data, '实例状态：HTTP_503');
      expect(
        tester.widget<Text>(find.byKey(kConnectionHealthDetailKey)).data,
        contains('未就绪'),
      );
    });

    testWidgets('探活端口抛异常：收敛成 UNREACHABLE，不冒泡成红屏', (WidgetTester tester) async {
      await pumpConnection(
        tester,
        (ConnectionConfig config) async => throw StateError('connection refused'),
      );

      await tapDiagnose(tester);

      expect(find.byKey(kConnectionHealthStatusKey), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(kConnectionHealthDetailKey)).data,
        contains('无法连接实例'),
      );
    });

    testWidgets('端口非数字时点诊断：行内报错，不调用探活', (WidgetTester tester) async {
      final List<ConnectionConfig> calls = <ConnectionConfig>[];
      await pumpConnection(tester, (ConnectionConfig config) async {
        calls.add(config);
        return const HealthReport(status: 'UP', httpStatus: 200, detail: '实例就绪（HTTP 200）');
      });
      await enter(tester, '192.168.110.110', 'abc');

      await tapDiagnose(tester);

      expect(find.text('端口须为数字'), findsOneWidget);
      expect(calls, isEmpty);
      expect(find.byKey(kConnectionHealthStatusKey), findsNothing);
    });
  });

  group('指挥端登录页', () {
    testWidgets('账号为空：本地报错，不调用登录端口', (WidgetTester tester) async {
      final List<(String, String)> calls = <(String, String)>[];
      await pumpLogin(tester, ({required String username, required String password}) async {
        calls.add((username, password));
        return accountSession();
      });

      await enterCredentials(tester, '   ', 'p@ssw0rd');
      await tapLogin(tester);

      expect(find.text('账号不能为空'), findsOneWidget);
      expect(calls, isEmpty);
    });

    testWidgets('密码为空：本地报错，不调用登录端口', (WidgetTester tester) async {
      final List<(String, String)> calls = <(String, String)>[];
      await pumpLogin(tester, ({required String username, required String password}) async {
        calls.add((username, password));
        return accountSession();
      });

      await enterCredentials(tester, 'admin', '');
      await tapLogin(tester);

      expect(find.text('密码不能为空'), findsOneWidget);
      expect(calls, isEmpty);
    });

    testWidgets('登录成功：账号去首尾空白、密码按原样提交，回调带出账号会话', (WidgetTester tester) async {
      final List<(String, String)> calls = <(String, String)>[];
      AccountSession? loggedIn;
      await pumpLogin(
        tester,
        ({required String username, required String password}) async {
          calls.add((username, password));
          return accountSession(username: username);
        },
        onLoggedIn: (AccountSession value) => loggedIn = value,
      );

      await enterCredentials(tester, ' admin ', ' p@ss ');
      await tapLogin(tester);

      expect(calls, <(String, String)>[('admin', ' p@ss ')]);
      expect(loggedIn?.accessToken, 'access-1');
      expect(loggedIn?.user?.username, 'admin');
      expect(find.byKey(kConsoleLoginErrorKey), findsNothing);
      expect(find.byKey(kConsoleLoginProgressKey), findsNothing);
    });

    testWidgets('401 AUTH_BAD_CREDENTIALS：提示账号或密码不正确，不回调', (WidgetTester tester) async {
      AccountSession? loggedIn;
      await pumpLogin(
        tester,
        ({required String username, required String password}) async {
          throw const ApiException(
            statusCode: 401,
            problem: ApiProblem(
              type: null,
              title: null,
              status: 401,
              code: ApiErrorCode.authBadCredentials,
              detail: null,
              instance: null,
              traceId: 'trace-login',
            ),
          );
        },
        onLoggedIn: (AccountSession value) => loggedIn = value,
      );

      await enterCredentials(tester, 'admin', 'wrong');
      await tapLogin(tester);

      expect(find.text('账号或密码不正确'), findsOneWidget);
      expect(loggedIn, isNull);
    });

    testWidgets('429 RATE_LIMITED：提示尝试过于频繁', (WidgetTester tester) async {
      await pumpLogin(tester, ({required String username, required String password}) async {
        throw const ApiException(
          statusCode: 429,
          problem: ApiProblem(
            type: null,
            title: null,
            status: 429,
            code: ApiErrorCode.rateLimited,
            detail: null,
            instance: null,
            traceId: 'trace-limit',
          ),
        );
      });

      await enterCredentials(tester, 'admin', 'p@ssw0rd');
      await tapLogin(tester);

      expect(find.textContaining('过于频繁'), findsOneWidget);
    });

    testWidgets('网络异常：显示可读错误，不冒泡', (WidgetTester tester) async {
      await pumpLogin(tester, ({required String username, required String password}) async {
        throw StateError('connection refused');
      });

      await enterCredentials(tester, 'admin', 'p@ssw0rd');
      await tapLogin(tester);

      expect(find.byKey(kConsoleLoginErrorKey), findsOneWidget);
      expect(find.textContaining('登录失败'), findsOneWidget);
    });

    testWidgets('提交期间禁用按钮并显示进度，完成后恢复', (WidgetTester tester) async {
      final Completer<AccountSession> pending = Completer<AccountSession>();
      await pumpLogin(
        tester,
        ({required String username, required String password}) => pending.future,
      );

      await enterCredentials(tester, 'admin', 'p@ssw0rd');
      await tester.tap(find.byKey(kConsoleLoginSubmitButtonKey));
      await tester.pump();

      expect(find.byKey(kConsoleLoginProgressKey), findsOneWidget);
      expect(
        tester.widget<FilledButton>(find.byKey(kConsoleLoginSubmitButtonKey)).onPressed,
        isNull,
      );

      pending.complete(accountSession());
      await tester.pumpAndSettle();

      expect(find.byKey(kConsoleLoginProgressKey), findsNothing);
      expect(
        tester.widget<FilledButton>(find.byKey(kConsoleLoginSubmitButtonKey)).onPressed,
        isNotNull,
      );
    });
  });

  group('队伍汇总总览页', () {
    testWidgets('首帧到达前提示等待；成功后展示账号、刷新次数与各队伍汇总', (WidgetTester tester) async {
      final Completer<List<TeamSummary>> pending = Completer<List<TeamSummary>>();
      await pumpSummary(tester, () => pending.future, interval: const Duration(seconds: 2));

      expect(find.text('admin（OPERATOR）'), findsOneWidget);
      expect(find.text('等待首帧…'), findsOneWidget);
      expect(find.byKey(kTeamSummaryErrorKey), findsNothing);

      pending.complete(<TeamSummary>[
        teamSummary(Team.red, total: 6, live: 4, dead: 1, offline: 1, hitsDealt: 12),
        teamSummary(Team.blue, total: 6, live: 3, dead: 2, offline: 1, hitsDealt: 9),
      ]);
      await tester.pump();
      await tester.pump();

      expect(find.textContaining('已刷新 1 次'), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(teamSummaryRowKey(Team.red))).data,
        'RED｜总数 6｜存活 4｜阵亡 1｜离线 1｜命中送出 12',
      );
      expect(
        tester.widget<Text>(find.byKey(teamSummaryRowKey(Team.blue))).data,
        'BLUE｜总数 6｜存活 3｜阵亡 2｜离线 1｜命中送出 9',
      );

      await unmount(tester);
    });

    testWidgets('可选计数缺失时显示破折号，不臆造 0', (WidgetTester tester) async {
      await pumpSummary(
        tester,
        () async => <TeamSummary>[
          teamSummary(
            Team.orange,
            total: null,
            live: null,
            dead: null,
            offline: null,
            hitsDealt: null,
          ),
        ],
      );

      expect(
        tester.widget<Text>(find.byKey(teamSummaryRowKey(Team.orange))).data,
        'ORANGE｜总数 —｜存活 —｜阵亡 —｜离线 —｜命中送出 —',
      );

      await unmount(tester);
    });

    testWidgets('空数组：提示本场没有已编成的队伍', (WidgetTester tester) async {
      await pumpSummary(tester, () async => const <TeamSummary>[]);

      expect(find.byKey(kTeamSummaryEmptyKey), findsOneWidget);
      expect(find.text('本场没有已编成的队伍'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('按注入周期轮询：每过一个周期成功计数 +1', (WidgetTester tester) async {
      int calls = 0;
      await pumpSummary(
        tester,
        () async {
          calls++;
          return <TeamSummary>[teamSummary(Team.red)];
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
      final Completer<List<TeamSummary>> pending = Completer<List<TeamSummary>>();
      await pumpSummary(
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

      pending.complete(<TeamSummary>[teamSummary(Team.red)]);
      await tester.pump();
      await tester.pump();
      expect(find.textContaining('已刷新 1 次'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('网络异常：显示可读错误，不冒泡、不清空上次成功数据', (WidgetTester tester) async {
      int calls = 0;
      await pumpSummary(
        tester,
        () async {
          calls++;
          if (calls == 1) {
            return <TeamSummary>[teamSummary(Team.red, live: 5)];
          }
          throw StateError('connection refused');
        },
        interval: const Duration(milliseconds: 200),
      );

      expect(
        tester.widget<Text>(find.byKey(teamSummaryRowKey(Team.red))).data,
        contains('存活 5'),
      );

      await tester.pump(const Duration(milliseconds: 200));
      await tester.pump();

      expect(find.byKey(kTeamSummaryErrorKey), findsOneWidget);
      expect(find.textContaining('拉取失败'), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(teamSummaryRowKey(Team.red))).data,
        contains('存活 5'),
      );

      await unmount(tester);
    });

    testWidgets('401 AUTH_TOKEN_EXPIRED：按契约错误码呈现，不冒泡', (WidgetTester tester) async {
      await pumpSummary(tester, () async {
        throw const ApiException(
          statusCode: 401,
          problem: ApiProblem(
            type: null,
            title: null,
            status: 401,
            code: ApiErrorCode.authTokenExpired,
            detail: null,
            instance: null,
            traceId: 'trace-expired',
          ),
        );
      });

      expect(find.byKey(kTeamSummaryErrorKey), findsOneWidget);
      expect(find.textContaining(ApiErrorCode.authTokenExpired), findsOneWidget);
      expect(find.text('等待首帧…'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('提供账号入口时点击触发回调', (WidgetTester tester) async {
      bool opened = false;
      await pumpSummary(
        tester,
        () async => const <TeamSummary>[],
        onOpenAccount: () => opened = true,
      );

      expect(find.byKey(kTeamSummaryAccountEntryKey), findsOneWidget);

      await tester.tap(find.byKey(kTeamSummaryAccountEntryKey));
      await tester.pump();

      expect(opened, isTrue);

      await unmount(tester);
    });

    testWidgets('未注入账号入口时不渲染该入口', (WidgetTester tester) async {
      await pumpSummary(tester, () async => const <TeamSummary>[]);

      expect(find.byKey(kTeamSummaryAccountEntryKey), findsNothing);

      await unmount(tester);
    });

    testWidgets('提供全体态势入口时点击触发回调', (WidgetTester tester) async {
      bool opened = false;
      await pumpSummary(
        tester,
        () async => const <TeamSummary>[],
        onOpenSituation: () => opened = true,
      );

      expect(find.byKey(kTeamSummarySituationEntryKey), findsOneWidget);

      await tester.tap(find.byKey(kTeamSummarySituationEntryKey));
      await tester.pump();

      expect(opened, isTrue);

      await unmount(tester);
    });

    testWidgets('未注入全体态势入口时不渲染该入口', (WidgetTester tester) async {
      await pumpSummary(tester, () async => const <TeamSummary>[]);

      expect(find.byKey(kTeamSummarySituationEntryKey), findsNothing);

      await unmount(tester);
    });
  });

  group('指挥端账号与会话页', () {
    testWidgets('展示当前身份、到期时刻与权限', (WidgetTester tester) async {
      await pumpAccount(
        tester,
        session: accountSession(
          username: 'duty',
          roles: const <String>['DIRECTOR'],
          permissions: const <String>['situation.read'],
        ),
      );

      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountIdentityKey)).data,
        'duty（DIRECTOR）',
      );
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountExpiryKey)).data,
        contains('令牌到期'),
      );
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountPermissionsKey)).data,
        '权限：situation.read',
      );
      expect(find.byKey(kConsoleAccountErrorKey), findsNothing);
    });

    testWidgets('已过期：只读提示已过期，不自动发起续期', (WidgetTester tester) async {
      int refreshCalls = 0;
      await pumpAccount(
        tester,
        session: accountSession(
          refreshToken: 'refresh-1',
          expiresAt: DateTime.utc(2020, 1, 1),
        ),
        refresh: (AccountSession current) async {
          refreshCalls++;
          return accountSession();
        },
      );

      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountExpiryKey)).data,
        contains('已过期'),
      );
      expect(refreshCalls, 0);
      expect(find.byKey(kConsoleAccountNoticeKey), findsNothing);
    });

    testWidgets('续期成功：会话被整体替换并给出新到期提示', (WidgetTester tester) async {
      final List<String> used = <String>[];
      await pumpAccount(
        tester,
        session: accountSession(refreshToken: 'refresh-1'),
        refresh: (AccountSession current) async {
          used.add(current.refreshToken ?? '');
          return accountSession(
            accessToken: 'access-2',
            refreshToken: 'refresh-2',
            expiresAt: DateTime.utc(2026, 10, 30, 12),
          );
        },
      );

      await tapAccountButton(tester, kConsoleAccountRefreshButtonKey);

      expect(used, <String>['refresh-1']);
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountExpiryKey)).data,
        contains('2026-10-30'),
      );
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountNoticeKey)).data,
        contains('已续期'),
      );
      expect(find.byKey(kConsoleAccountRefreshProgressKey), findsNothing);
    });

    testWidgets('续期进行中再次点击：不重复调用，按钮禁用', (WidgetTester tester) async {
      int refreshCalls = 0;
      final Completer<AccountSession> pending = Completer<AccountSession>();
      await pumpAccount(
        tester,
        session: accountSession(refreshToken: 'refresh-1'),
        refresh: (AccountSession current) {
          refreshCalls++;
          return pending.future;
        },
      );

      await tester.tap(find.byKey(kConsoleAccountRefreshButtonKey));
      await tester.pump();

      expect(find.byKey(kConsoleAccountRefreshProgressKey), findsOneWidget);
      expect(
        tester.widget<FilledButton>(find.byKey(kConsoleAccountRefreshButtonKey)).onPressed,
        isNull,
      );

      await tester.tap(find.byKey(kConsoleAccountRefreshButtonKey), warnIfMissed: false);
      await tester.pump();
      expect(refreshCalls, 1);

      pending.complete(accountSession(accessToken: 'access-2'));
      await tester.pumpAndSettle();

      expect(find.byKey(kConsoleAccountRefreshProgressKey), findsNothing);
    });

    testWidgets('会话没有刷新令牌：行内提示，不发续期请求', (WidgetTester tester) async {
      int refreshCalls = 0;
      await pumpAccount(
        tester,
        session: accountSession(),
        refresh: (AccountSession current) async {
          refreshCalls++;
          return accountSession();
        },
      );

      await tapAccountButton(tester, kConsoleAccountRefreshButtonKey);

      expect(refreshCalls, 0);
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountErrorKey)).data,
        contains('没有刷新令牌'),
      );
    });

    testWidgets('401 AUTH_TOKEN_EXPIRED：提示刷新令牌已过期', (WidgetTester tester) async {
      await pumpAccount(
        tester,
        session: accountSession(refreshToken: 'refresh-1'),
        refresh: (AccountSession current) async =>
            throw apiFailure(401, ApiErrorCode.authTokenExpired),
      );

      await tapAccountButton(tester, kConsoleAccountRefreshButtonKey);

      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountErrorKey)).data,
        '刷新令牌已过期，请重新登录',
      );
      expect(find.byKey(kConsoleAccountNoticeKey), findsNothing);
    });

    testWidgets('401 AUTH_TOKEN_INVALID：提示刷新令牌无效或已被撤销', (WidgetTester tester) async {
      await pumpAccount(
        tester,
        session: accountSession(refreshToken: 'refresh-1'),
        refresh: (AccountSession current) async =>
            throw apiFailure(401, ApiErrorCode.authTokenInvalid),
      );

      await tapAccountButton(tester, kConsoleAccountRefreshButtonKey);

      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountErrorKey)).data,
        contains('无效或已被撤销'),
      );
    });

    testWidgets('续期网络异常：显示可读错误，不冒泡', (WidgetTester tester) async {
      await pumpAccount(
        tester,
        session: accountSession(refreshToken: 'refresh-1'),
        refresh: (AccountSession current) async => throw StateError('connection refused'),
      );

      await tapAccountButton(tester, kConsoleAccountRefreshButtonKey);

      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountErrorKey)).data,
        contains('续期失败'),
      );
    });

    testWidgets('重新载入身份：展示 me 返回的账号与权限', (WidgetTester tester) async {
      final List<String> tokens = <String>[];
      await pumpAccount(
        tester,
        reload: (AccountSession current) async {
          tokens.add(current.accessToken);
          return AuthUser(
            userId: 'u-1',
            username: 'duty',
            roles: const <String>['ADMIN'],
            permissions: const <String>['plan.write'],
          );
        },
      );

      await tapAccountButton(tester, kConsoleAccountReloadButtonKey);

      expect(tokens, <String>['access-1']);
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountIdentityKey)).data,
        'duty（ADMIN）',
      );
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountPermissionsKey)).data,
        '权限：plan.write',
      );
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountNoticeKey)).data,
        contains('已重新载入身份'),
      );
    });

    testWidgets('身份重载失败：行内给出可读错误', (WidgetTester tester) async {
      await pumpAccount(
        tester,
        reload: (AccountSession current) async =>
            throw apiFailure(401, ApiErrorCode.authTokenExpired),
      );

      await tapAccountButton(tester, kConsoleAccountReloadButtonKey);

      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountErrorKey)).data,
        '身份重载失败：${ApiErrorCode.authTokenExpired}',
      );
    });

    testWidgets('退出成功：清本地会话并回登录页', (WidgetTester tester) async {
      int cleared = 0;
      int loggedOut = 0;
      await pumpAccount(
        tester,
        logout: (AccountSession current) async {},
        clearSession: () async {
          cleared++;
        },
        onLoggedOut: () => loggedOut++,
      );

      await tapAccountButton(tester, kConsoleAccountLogoutButtonKey);

      expect(cleared, 1);
      expect(loggedOut, 1);
      expect(find.byKey(kConsoleAccountErrorKey), findsNothing);
      expect(find.byKey(kConsoleAccountSignedOutKey), findsNothing);
    });

    testWidgets('退出服务端未确认：仍清本地会话，留在本页，点返回登录才导航', (WidgetTester tester) async {
      int cleared = 0;
      int loggedOut = 0;
      await pumpAccount(
        tester,
        logout: (AccountSession current) async =>
            throw apiFailure(401, ApiErrorCode.authTokenInvalid),
        clearSession: () async {
          cleared++;
        },
        onLoggedOut: () => loggedOut++,
      );

      await tapAccountButton(tester, kConsoleAccountLogoutButtonKey);

      expect(cleared, 1);
      expect(loggedOut, 0);
      expect(find.byKey(kConsoleAccountSignedOutKey), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountErrorKey)).data,
        contains('服务端未确认，已本地退出'),
      );
      expect(find.byKey(kConsoleAccountLogoutButtonKey), findsNothing);

      await tapAccountButton(tester, kConsoleAccountReturnButtonKey);

      expect(loggedOut, 1);
    });

    testWidgets('退出网络异常：同样清本地会话并留在本页', (WidgetTester tester) async {
      int cleared = 0;
      int loggedOut = 0;
      await pumpAccount(
        tester,
        logout: (AccountSession current) async => throw StateError('connection refused'),
        clearSession: () async {
          cleared++;
        },
        onLoggedOut: () => loggedOut++,
      );

      await tapAccountButton(tester, kConsoleAccountLogoutButtonKey);

      expect(cleared, 1);
      expect(loggedOut, 0);
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountErrorKey)).data,
        contains('服务端未确认，已本地退出'),
      );
    });
  });

  group('指挥端认证接线（RES-D）', () {
    /// 契约 `TokenPair` 形状的登录/刷新响应体。
    Map<String, dynamic> pairBody(String accessToken, String refreshToken) => <String, dynamic>{
          'accessToken': accessToken,
          'expiresIn': 900,
          'refreshToken': refreshToken,
          'user': <String, dynamic>{
            'userId': 'u-1',
            'username': 'admin',
            'roles': <String>['OPERATOR'],
          },
        };

    /// 走完「连接配置 → 登录」并停在队伍汇总页（全程只打脚本化传输桩）。
    Future<void> signIn(WidgetTester tester, ScriptedTransport transport) async {
      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);
    }

    testWidgets('登录后：队伍汇总按登录令牌带 Bearer', (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(pairBody('access-1', 'refresh-1'));
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[
              <String, dynamic>{'team': 'RED', 'total': 6, 'live': 4, 'dead': 1},
            ]);
        }
        fail('未预期的请求：${call.key}');
      };

      await signIn(tester, transport);

      expect(transport.sequence, <String>[
        'POST $kApiPrefix/auth/login',
        'GET $kApiPrefix/situation/teams/summary',
      ]);
      // 登录不带令牌；登录后的域请求带登录拿到的 accessToken。
      expect(transport.calls.first.authorization, isNull);
      expect(transport.calls.last.authorization, 'Bearer access-1');
      expect((await sessionStore.readAccount())!.accessToken, 'access-1');
      expect(find.byType(TeamSummaryPage), findsOneWidget);
    });

    testWidgets('队伍汇总遇 401 到期：自动续期一次并按新令牌重试（页面无感）', (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(pairBody('access-1', 'refresh-1'));
          case 'GET $kApiPrefix/situation/teams/summary':
            if (call.authorization == 'Bearer access-1') {
              throw apiFailure(401, ApiErrorCode.authTokenExpired);
            }
            return jsonResponse(<Object>[]);
          case 'POST $kApiPrefix/auth/refresh':
            // 滚动刷新：回新令牌对，**不回** `user`。
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-2',
              'expiresIn': 900,
              'refreshToken': 'refresh-2',
            });
        }
        fail('未预期的请求：${call.key}');
      };

      await signIn(tester, transport);

      expect(transport.sequence, <String>[
        'POST $kApiPrefix/auth/login',
        'GET $kApiPrefix/situation/teams/summary',
        'POST $kApiPrefix/auth/refresh',
        'GET $kApiPrefix/situation/teams/summary',
      ]);
      expect(transport.calls.last.authorization, 'Bearer access-2');
      // 续期结果写回会话存储，且保留登录时的身份快照。
      final AccountSession stored = (await sessionStore.readAccount())!;
      expect(stored.accessToken, 'access-2');
      expect(stored.refreshToken, 'refresh-2');
      expect(stored.user!.username, 'admin');
      // 页面无感：401 已在传输层被消化，汇总页不显示错误。
      expect(find.byKey(kTeamSummaryErrorKey), findsNothing);
      expect(find.byKey(kTeamSummaryEmptyKey), findsOneWidget);
    });

    testWidgets('账号页续期按钮：委托传输换新令牌并写回会话存储', (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(pairBody('access-1', 'refresh-1'));
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'POST $kApiPrefix/auth/refresh':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-2',
              'expiresIn': 1800,
              'refreshToken': 'refresh-2',
            });
        }
        fail('未预期的请求：${call.key}');
      };

      await signIn(tester, transport);
      await tapAccountButton(tester, kTeamSummaryAccountEntryKey);
      await tapAccountButton(tester, kConsoleAccountRefreshButtonKey);

      // 汇总页 2s 轮询期间会再拉一次 summary，与本次续期无关：只校验
      // 「登录 → 首次汇总 → 续期」这条子序列，并单独断言续期**只打一次**
      // （滚动刷新不可重放）。
      expect(
        transport.sequence,
        containsAllInOrder(<String>[
          'POST $kApiPrefix/auth/login',
          'GET $kApiPrefix/situation/teams/summary',
          'POST $kApiPrefix/auth/refresh',
        ]),
      );
      expect(
        transport.sequence.where((String key) => key == 'POST $kApiPrefix/auth/refresh'),
        hasLength(1),
      );
      // 刷新提交的是登录拿到的刷新令牌。
      final ScriptedCall refreshCall = transport.calls.lastWhere(
        (ScriptedCall call) => call.key == 'POST $kApiPrefix/auth/refresh',
      );
      final Map<String, dynamic> body = refreshCall.body! as Map<String, dynamic>;
      expect(body['refreshToken'], 'refresh-1');
      final AccountSession stored = (await sessionStore.readAccount())!;
      expect(stored.accessToken, 'access-2');
      expect(stored.refreshToken, 'refresh-2');
      expect(stored.user!.username, 'admin');
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountNoticeKey)).data,
        contains('已续期'),
      );
    });

    testWidgets('会话终结（刷新被 401 拒绝）：清本地会话并弹回登录页', (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(pairBody('access-1', 'refresh-1'));
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'POST $kApiPrefix/auth/refresh':
            throw apiFailure(401, ApiErrorCode.authTokenInvalid);
        }
        fail('未预期的请求：${call.key}');
      };

      await signIn(tester, transport);
      await tapAccountButton(tester, kTeamSummaryAccountEntryKey);
      expect(find.byType(AccountPage), findsOneWidget);

      await tapAccountButton(tester, kConsoleAccountRefreshButtonKey);

      // 会话终结：本地会话被清，页面栈一路弹回登录页（账号页与汇总页都不再在栈上）。
      expect(await sessionStore.readAccount(), isNull);
      expect(find.byType(AccountPage), findsNothing);
      expect(find.byType(TeamSummaryPage), findsNothing);
      expect(find.byType(ConsoleLoginPage), findsOneWidget);
    });
  });

  /// 段 D 后半：指挥端「全体态势」页接 `SituationFeed`（WS + 10s 兜底 Poll 双通道）。
  ///
  /// 用例全部跑在 `testWidgets` 的**假时钟**上，注入假 `fetch` 与假连接器——**不建真实连接**。
  /// 判据是三条：WS 不得替代 HTTP 兜底轮询、两条通道同源归并让旧帧不覆盖新状态、
  /// 缓存随页面生灭（不跨会话泄漏）。
  group('指挥端全体态势页（RES-D 后半）', () {
    testWidgets('首帧前提示等待；首帧到达后展示账号、通道状态与各单元', (WidgetTester tester) async {
      final Completer<SituationEnvelope> pending = Completer<SituationEnvelope>();
      final TestSituationConnector connector = TestSituationConnector(silent: true);
      await pumpOverall(
        tester,
        fetch: ({int? sinceRevision}) => pending.future,
        connect: connector.call,
      );

      expect(
        tester.widget<Text>(find.byKey(kOverallSituationAccountKey)).data,
        'admin（OPERATOR）',
      );
      expect(find.byKey(kOverallSituationWaitingKey), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(kOverallSituationChannelKey)).data,
        contains('WS 未连'),
      );

      pending.complete(fullEnvelope(
        revision: 10,
        units: <UnitState>[
          unitOf(14, hp: 88),
          unitOf(21, status: VestStatus.die, hp: 0),
        ],
      ));
      await tester.pump();
      await tester.pump();

      expect(
        tester.widget<Text>(find.byKey(overallSituationUnitRowKey(14))).data,
        '#14 LIVE｜HP 88',
      );
      expect(
        tester.widget<Text>(find.byKey(overallSituationUnitRowKey(21))).data,
        '#21 DIE｜HP 0',
      );
      expect(find.byKey(kOverallSituationWaitingKey), findsNothing);
      expect(
        tester.widget<Text>(find.byKey(kOverallSituationCountsKey)).data,
        contains('单元 2'),
      );

      await unmount(tester);
    });

    testWidgets('WS 连上：通道切为实时，且收到的 WS 帧同源归并进单元列表', (WidgetTester tester) async {
      final TestSituationConnector connector = TestSituationConnector();
      await pumpOverall(
        tester,
        fetch: ({int? sinceRevision}) async =>
            fullEnvelope(revision: 10, units: <UnitState>[unitOf(14)]),
        connect: connector.call,
      );

      expect(
        tester.widget<Text>(find.byKey(kOverallSituationChannelKey)).data,
        contains('WS 已连'),
      );
      expect(find.byKey(overallSituationUnitRowKey(14)), findsOneWidget);
      // 首个连接未对齐 → 不带 sinceRevision 取全量。
      expect(connector.sinceRevisions, <int?>[null]);

      connector.last.emit(jsonEncode(<String, dynamic>{
        'epoch': 'e-1',
        'revision': 11,
        'full': false,
        'running': true,
        'serverTime': '2026-10-28T12:00:00Z',
        'changes': <dynamic>[
          <String, dynamic>{'vestNum': 21, 'status': 'LIVE', 'revision': 11},
        ],
      }));
      await tester.pump();
      await tester.pump();

      expect(find.byKey(overallSituationUnitRowKey(21)), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(kOverallSituationCountsKey)).data,
        contains('单元 2'),
      );

      await unmount(tester);
    });

    testWidgets('重新同步按钮：WS 已连时上行 RESYNC 帧并带本地 revision', (WidgetTester tester) async {
      final TestSituationConnector connector = TestSituationConnector();
      await pumpOverall(
        tester,
        fetch: ({int? sinceRevision}) async =>
            fullEnvelope(revision: 10, units: <UnitState>[unitOf(14)]),
        connect: connector.call,
      );

      await tester.tap(find.byKey(kOverallSituationResyncButtonKey));
      await tester.pump();

      expect(
        connector.last.sent.single,
        jsonEncode(const <String, Object?>{'action': 'RESYNC', 'sinceRevision': 10}),
      );

      await unmount(tester);
    });

    testWidgets('拉取失败：显示可读错误，保留陈旧单元数据', (WidgetTester tester) async {
      int calls = 0;
      final TestSituationConnector connector = TestSituationConnector(silent: true);
      await pumpOverall(
        tester,
        fetch: ({int? sinceRevision}) async {
          calls++;
          if (calls == 1) {
            return fullEnvelope(revision: 10, units: <UnitState>[unitOf(14, hp: 77)]);
          }
          throw StateError('connection refused');
        },
        connect: connector.call,
      );

      expect(
        tester.widget<Text>(find.byKey(overallSituationUnitRowKey(14))).data,
        contains('77'),
      );

      // WS 未连 → 按 2s 档轮询；下一拍失败。
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();

      expect(find.byKey(kOverallSituationErrorKey), findsOneWidget);
      expect(find.textContaining('拉取失败'), findsOneWidget);
      // 失败不得抹掉上一次成功的数据（保陈旧，不空屏）。
      expect(
        tester.widget<Text>(find.byKey(overallSituationUnitRowKey(14))).data,
        contains('77'),
      );

      await unmount(tester);
    });

    testWidgets('缓存隔离：重开页面为新会话，首拉仍取全量且不残留上一会话的单元', (WidgetTester tester) async {
      final List<int?> sinceRevisions = <int?>[];
      final TestSituationConnector connector = TestSituationConnector(silent: true);
      await pumpOverall(
        tester,
        fetch: ({int? sinceRevision}) async {
          sinceRevisions.add(sinceRevision);
          return fullEnvelope(revision: 10, units: <UnitState>[unitOf(14)]);
        },
        connect: connector.call,
      );

      expect(sinceRevisions, <int?>[null]);
      expect(find.byKey(overallSituationUnitRowKey(14)), findsOneWidget);

      await unmount(tester);

      // 重开一页：页面自建新的 feed / 缓存——不得残留上一会话的单元，首个请求仍为全量。
      sinceRevisions.clear();
      final Completer<SituationEnvelope> pending = Completer<SituationEnvelope>();
      await pumpOverall(
        tester,
        fetch: ({int? sinceRevision}) {
          sinceRevisions.add(sinceRevision);
          return pending.future;
        },
        connect: connector.call,
      );

      expect(sinceRevisions, <int?>[null]);
      expect(find.byKey(overallSituationUnitRowKey(14)), findsNothing);
      expect(find.byKey(kOverallSituationWaitingKey), findsOneWidget);

      await unmount(tester);
    });
  });

  /// 段 47：单兵指挥指令页——从「全体态势」页所选单元行进入，目标编号不手输。
  ///
  /// 六个写端点全部是注入的闭包桩（**不发真实请求**）。判据围绕三条契约口径：
  /// 「受理 ≠ 下发」（`accepted=false` 只提示未下发）、覆盖配置的**只发非空键**语义、
  /// 以及写操作的**单飞去重**。
  group('指挥指令页（段 47）', () {
    testWidgets('目标来自所选单元行：判死下发后显示受理回执（含下发字节）', (WidgetTester tester) async {
      int kills = 0;
      await pumpCommand(tester, kill: () async {
        kills++;
        return ackOf(command: 'DIE', downlinkHex: '0B01');
      });

      expect(tester.widget<Text>(find.byKey(kCommandTargetKey)).data, '目标 #14｜admin（OPERATOR）');
      expect(find.text('尚未下发指令'), findsOneWidget);

      await tester.tap(find.byKey(kCommandKillButtonKey));
      await tester.pump();

      expect(kills, 1);
      expect(
        tester.widget<Text>(find.byKey(kCommandAckKey)).data,
        'DIE → #14｜已下发：0B01',
      );
    });

    testWidgets('accepted=false：提示「未下发」而非报错（HTTP 200 只表示已受理）', (WidgetTester tester) async {
      await pumpCommand(tester, kill: () async => ackOf(command: 'DIE', accepted: false));

      await tester.tap(find.byKey(kCommandKillButtonKey));
      await tester.pump();

      expect(
        tester.widget<Text>(find.byKey(kCommandAckKey)).data,
        contains('未下发'),
      );
      expect(find.byKey(kCommandErrorKey), findsNothing);
    });

    testWidgets('契约错误码映射为现场文案（演习未运行）', (WidgetTester tester) async {
      await pumpCommand(
        tester,
        kill: () async => throw apiFailure(409, ApiErrorCode.exerciseNotRunning),
      );

      await tester.tap(find.byKey(kCommandKillButtonKey));
      await tester.pump();

      expect(
        tester.widget<Text>(find.byKey(kCommandErrorKey)).data,
        contains('当前没有进行中的演习'),
      );
      expect(find.byKey(kCommandAckKey), findsNothing);
    });

    testWidgets('单兵充弹：非法弹量不发请求，合法则带契约请求体', (WidgetTester tester) async {
      final List<ReloadRequest> sent = <ReloadRequest>[];
      await pumpCommand(tester, kill: unexpectedCommand, reload: (ReloadRequest request) async {
        sent.add(request);
        return ackOf(command: 'RELOAD', downlinkHex: '0B02');
      });

      await tester.enterText(find.byKey(kCommandReloadAmmoFieldKey), 'abc');
      await tester.tap(find.byKey(kCommandReloadButtonKey));
      await tester.pump();

      expect(sent, isEmpty);
      expect(
        tester.widget<Text>(find.byKey(kCommandErrorKey)).data,
        contains('0..65535'),
      );

      await tester.enterText(find.byKey(kCommandReloadAmmoFieldKey), '60000');
      await tester.tap(find.byKey(kCommandReloadButtonKey));
      await tester.pump();

      expect(sent.single.ammo, 60000);
      expect(sent.single.weapon, WeaponSlot.primary);
      expect(
        tester.widget<Text>(find.byKey(kCommandAckKey)).data,
        contains('RELOAD → #14'),
      );
    });

    testWidgets('覆盖配置：全空不发请求；只填 HP 则请求体只含 hp 键', (WidgetTester tester) async {
      final List<VestConfigRequest> sent = <VestConfigRequest>[];
      await pumpCommand(tester, kill: unexpectedCommand,
          setConfig: (VestConfigRequest request) async {
        sent.add(request);
        return ackOf(command: 'CONFIG', downlinkHex: '0E01');
      });

      await tester.tap(find.byKey(kCommandConfigSaveButtonKey));
      await tester.pump();

      expect(sent, isEmpty);
      expect(
        tester.widget<Text>(find.byKey(kCommandErrorKey)).data,
        contains('至少'),
      );

      await tester.enterText(find.byKey(kCommandConfigHpFieldKey), '200');
      await tester.tap(find.byKey(kCommandConfigSaveButtonKey));
      await tester.pump();

      // 覆盖语义：未填字段不得下发（`null` 不是「置空」）。
      expect(sent.single.toJson(), <String, Object?>{'hp': 200});
      expect(
        tester.widget<Text>(find.byKey(kCommandAckKey)).data,
        contains('CONFIG → #14'),
      );
    });

    testWidgets('下发期间单飞去重：连点只发一次请求', (WidgetTester tester) async {
      final Completer<CommandAck> pending = Completer<CommandAck>();
      int kills = 0;
      await pumpCommand(tester, kill: () {
        kills++;
        return pending.future;
      });

      await tester.tap(find.byKey(kCommandKillButtonKey));
      await tester.pump();
      await tester.tap(find.byKey(kCommandKillButtonKey));
      await tester.pump();

      expect(kills, 1);

      pending.complete(ackOf(command: 'DIE'));
      await tester.pump();

      expect(
        tester.widget<Text>(find.byKey(kCommandAckKey)).data,
        contains('DIE → #14'),
      );
    });
  });

  /// 段 47：「全体态势」页的两处指令入口——单元行 → 单兵指令页；AppBar → 全体充弹（广播）。
  ///
  /// 两个端口都是**可选注入**：不注入时页面与既有只读用例渲染完全一致（回归在此固定）。
  group('指挥端全体态势页·指令入口（段 47）', () {
    Future<void> pumpWithUnits(
      WidgetTester tester, {
      void Function(int vestNum)? onCommand,
      Future<CommandAck> Function(ReloadRequest request)? reloadAll,
    }) async {
      final TestSituationConnector connector = TestSituationConnector(silent: true);
      await pumpOverall(
        tester,
        fetch: ({int? sinceRevision}) async => fullEnvelope(
          revision: 10,
          units: <UnitState>[unitOf(14), unitOf(21)],
        ),
        connect: connector.call,
        onCommand: onCommand,
        reloadAll: reloadAll,
      );
      await tester.pump();
    }

    testWidgets('注入 onCommand：单元行可点，回调目标编号', (WidgetTester tester) async {
      final List<int> tapped = <int>[];
      await pumpWithUnits(tester, onCommand: tapped.add);

      // 未注入 reloadAll → AppBar 不渲染广播入口。
      expect(find.byKey(kOverallSituationReloadAllButtonKey), findsNothing);

      await tester.tap(find.byKey(overallSituationUnitTapKey(21)));
      await tester.pump();

      expect(tapped, <int>[21]);
      // 行文本键不变（点击只是包了一层 InkWell）。
      expect(
        tester.widget<Text>(find.byKey(overallSituationUnitRowKey(21))).data,
        '#21 LIVE｜HP —',
      );

      await unmount(tester);
    });

    testWidgets('未注入 onCommand：行仍为只读文本，无点击键', (WidgetTester tester) async {
      await pumpWithUnits(tester);

      expect(find.byKey(overallSituationUnitRowKey(14)), findsOneWidget);
      expect(find.byKey(overallSituationUnitTapKey(14)), findsNothing);

      await unmount(tester);
    });

    testWidgets('注入 reloadAll：AppBar 入口 → 对话框 → 广播回执提示为「全体」', (WidgetTester tester) async {
      final List<ReloadRequest> sent = <ReloadRequest>[];
      await pumpWithUnits(tester, reloadAll: (ReloadRequest request) async {
        sent.add(request);
        return ackOf(vestNum: 0, command: 'RELOAD', downlinkHex: '0B03');
      });

      await tester.tap(find.byKey(kOverallSituationReloadAllButtonKey));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      await tester.enterText(find.byKey(kReloadAllAmmoFieldKey), '50');
      await tester.tap(find.byKey(kReloadAllConfirmKey));
      await tester.pump();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(sent.single.ammo, 50);
      expect(sent.single.weapon, WeaponSlot.primary);
      // 广播回执 vestNum 固定为 0 → 文案显示「全体」。
      expect(find.text('RELOAD → 全体｜已下发：0B03'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('全体充弹对话框：非法弹量不关闭且不发请求', (WidgetTester tester) async {
      final List<ReloadRequest> sent = <ReloadRequest>[];
      await pumpWithUnits(tester, reloadAll: (ReloadRequest request) async {
        sent.add(request);
        return ackOf(vestNum: 0, command: 'RELOAD');
      });

      await tester.tap(find.byKey(kOverallSituationReloadAllButtonKey));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      await tester.enterText(find.byKey(kReloadAllAmmoFieldKey), 'x');
      await tester.tap(find.byKey(kReloadAllConfirmKey));
      await tester.pump();

      expect(sent, isEmpty);
      expect(find.byKey(kReloadAllErrorKey), findsOneWidget);
      expect(find.byKey(kReloadAllConfirmKey), findsOneWidget);

      await tester.tap(find.byKey(kReloadAllCancelKey));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      await unmount(tester);
    });
  });
}

/// RES-D：脚本化传输桩——按请求回预置响应，并把**真实收到的请求**记下来。
///
/// 路径拼接借内层 [ApiClient]（基址取一个不会连接的端口）：测试里不必重抄一份
/// `/api/v1` 拼接规则，同时**不建连接、不发真实请求**。
/// 非 2xx 由用例以 `throw apiFailure(...)` 表达，与 [ApiClient] 的抛错形状一致。
class ScriptedTransport implements ApiTransport {
  ScriptedTransport() : _inner = ApiClient(baseUri: Uri.parse('http://127.0.0.1:1'));

  final ApiClient _inner;

  /// 已收到的请求（按发生顺序）。
  final List<ScriptedCall> calls = <ScriptedCall>[];

  /// 由用例覆写：按请求决定回什么；缺省回 200 空对象。
  Future<ApiResponse> Function(ScriptedCall call) responder =
      (ScriptedCall call) async => jsonResponse(<String, dynamic>{});

  /// 已收到的「方法 + 路径」序列（接线顺序的直接证据）。
  List<String> get sequence =>
      calls.map((ScriptedCall call) => call.key).toList(growable: false);

  @override
  Uri get baseUri => _inner.baseUri;

  @override
  Uri api(String path, [Map<String, String>? query]) => _inner.api(path, query);

  @override
  Uri resolve(String path, [Map<String, String>? query]) => _inner.resolve(path, query);

  @override
  Future<ApiResponse> get(String path, {Map<String, String>? query, String? bearerToken}) =>
      _record('GET', path, query: query, bearerToken: bearerToken);

  @override
  Future<ApiResponse> post(String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      _record('POST', path, query: query, jsonBody: jsonBody, bearerToken: bearerToken);

  @override
  Future<ApiResponse> put(String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      _record('PUT', path, query: query, jsonBody: jsonBody, bearerToken: bearerToken);

  @override
  Future<ApiResponse> delete(String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      _record('DELETE', path, query: query, jsonBody: jsonBody, bearerToken: bearerToken);

  @override
  Future<ApiResponse> send(String method, String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      _record(method, path, query: query, jsonBody: jsonBody, bearerToken: bearerToken);

  /// 原始字节体（multipart）：桩只记路径与令牌，正文不参与指挥端接线断言。
  @override
  Future<ApiResponse> sendBytes(String method, String path,
          {Map<String, String>? query,
          required List<int> body,
          required String contentType,
          String? bearerToken}) =>
      _record(method, path, query: query, bearerToken: bearerToken);

  @override
  Future<ApiResponse> probe(String path) => _record('GET', path, absolute: true);

  @override
  void close() => _inner.close();

  Future<ApiResponse> _record(
    String method,
    String path, {
    Map<String, String>? query,
    Object? jsonBody,
    String? bearerToken,
    bool absolute = false,
  }) {
    final Uri uri = absolute ? _inner.resolve(path, query) : _inner.api(path, query);
    calls.add(ScriptedCall(
      method: method,
      path: uri.path,
      authorization: (bearerToken == null || bearerToken.isEmpty) ? null : 'Bearer $bearerToken',
      body: jsonBody,
    ));
    // `Future.sync` 把同步抛错也转成 future 错误——与 `ApiClient` 的抛错形状一致。
    return Future<ApiResponse>.sync(() => responder(calls.last));
  }
}

/// 脚本化传输桩收到的一次请求。
class ScriptedCall {
  const ScriptedCall({
    required this.method,
    required this.path,
    required this.authorization,
    this.body,
  });

  final String method;
  final String path;

  /// 已还原成 `Bearer <token>` 的授权头；未带令牌为 `null`。
  final String? authorization;
  final Object? body;

  /// 「方法 + 路径」，用于断言请求序列。
  String get key => '$method $path';
}

/// 契约形状的 JSON 响应（UTF-8 编码，与 `ApiClient` 解码头一致）。
ApiResponse jsonResponse(Object body, {int statusCode = 200}) => ApiResponse(
      statusCode: statusCode,
      contentType: 'application/json',
      body: utf8.encode(jsonEncode(body)),
    );

/// 段 D 后半：假态势 WS 连接——手动投喂下行帧、记录上行帧，**不建真实连接**。
class TestSituationSocket implements SituationSocket {
  // 单订阅控制器（与 `situation_feed_test.dart` 一致）：仅 `SituationFeed` 一个监听者。
  final StreamController<String> _controller = StreamController<String>();

  /// 客户端上行的文本帧。
  final List<String> sent = <String>[];

  int closeCallCount = 0;

  @override
  Stream<String> get messages => _controller.stream;

  @override
  void send(String text) => sent.add(text);

  @override
  int? get closeCode => null;

  @override
  String? get closeReason => null;

  @override
  Future<void> close([int? code, String? reason]) async {
    closeCallCount++;
    if (!_controller.isClosed) {
      await _controller.close();
    }
  }

  /// 投喂一帧下行文本。
  void emit(String raw) {
    if (!_controller.isClosed) {
      _controller.add(raw);
    }
  }
}

/// 假连接器：`silent=true` 时返回一个**永不落地**的 future（把用例按在「WS 未连」档）。
class TestSituationConnector {
  TestSituationConnector({this.silent = false});

  final bool silent;

  /// 每次连接携带的 `sinceRevision`（`null` = 取全量）。
  final List<int?> sinceRevisions = <int?>[];

  final List<TestSituationSocket> opened = <TestSituationSocket>[];

  TestSituationSocket get last => opened.last;

  Future<SituationSocket> call({int? sinceRevision}) {
    sinceRevisions.add(sinceRevision);
    if (silent) {
      return Completer<SituationSocket>().future;
    }
    final TestSituationSocket socket = TestSituationSocket();
    opened.add(socket);
    return Future<SituationSocket>.value(socket);
  }
}
