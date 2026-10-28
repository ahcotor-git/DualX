import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_client.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/command_models.dart';
import 'package:scds_client/shared/api/exercise_api.dart';
import 'package:scds_client/shared/api/exercise_models.dart';
import 'package:scds_client/shared/api/situation_models.dart';

/// exercise 域契约测试：只读两端点（当前演习状态 + 日志流水）与写端点
/// （想定 CRUD / 编成 / 模板下载与 multipart 导入 / 开演与停演）。
///
/// 与 `situation_api_test.dart` 同口径——本机 `HttpServer` 打桩，断言**真实 HTTP 行为**：
/// 路径、查询串（只带非 null 参数）、`Bearer` 注入，以及非 2xx → `ApiException` 的映射。
void main() {
  late HttpServer server;
  late ApiClient client;
  late ExerciseApi api;
  late List<_Seen> seen;

  /// 由各用例覆写：默认回 200 空 JSON。
  late Future<void> Function(HttpRequest request) handler;

  setUp(() async {
    seen = <_Seen>[];
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((HttpRequest request) async {
      final List<int> body = await request.fold<List<int>>(
        <int>[],
        (List<int> acc, List<int> chunk) => acc..addAll(chunk),
      );
      seen.add(_Seen(
        method: request.method,
        uri: request.uri,
        authorization: request.headers.value(HttpHeaders.authorizationHeader),
        contentType: request.headers.contentType,
        body: body,
      ));
      await handler(request);
    });
    handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{});
    client = ApiClient(baseUri: Uri(scheme: 'http', host: '127.0.0.1', port: server.port));
    api = ExerciseApi(client);
  });

  tearDown(() async {
    client.close();
    await server.close(force: true);
  });

  group('getCurrentExercise（当前演习）', () {
    test('进行中：GET /api/v1/exercise/current 带 Bearer，回完整 Exercise', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'running': true,
            'exerciseId': 'ex-2026-001',
            'planId': 'plan-7',
            'planName': '城市巷战想定',
            'mapId': 'map-3',
            'battleMode': 'BATTLE',
            'startedAt': '2026-10-29T01:00:00Z',
            'elapsedSeconds': 3725,
            'rosterCount': 16,
            'onlineCount': 14,
          });

      final Exercise exercise = await api.getCurrentExercise(token: 'access-1');

      expect(exercise.running, isTrue);
      expect(exercise.exerciseId, 'ex-2026-001');
      expect(exercise.planName, '城市巷战想定');
      expect(exercise.battleMode, BattleMode.battle);
      expect(exercise.elapsedSeconds, 3725);
      expect(exercise.onlineCount, 14);
      expect(exercise.endedAt, isNull);
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/exercise/current');
      expect(seen.single.uri.query, isEmpty);
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('无演习：回 200 + running=false，其余业务字段全为 null（不编造场次）', () async {
      handler = (HttpRequest request) =>
          respondJson(request, 200, <String, dynamic>{'running': false});

      final Exercise exercise = await api.getCurrentExercise(token: 'access-1');

      expect(exercise.running, isFalse);
      expect(exercise.exerciseId, isNull);
      expect(exercise.planId, isNull);
      expect(exercise.startedAt, isNull);
      expect(exercise.elapsedSeconds, isNull);
      expect(exercise.endReason, isNull);
    });

    test('未知 battleMode 字面量：抛 FormatException（不静默落默认分支）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'running': true,
            'battleMode': 'SKIRMISH',
          });

      expect(
        () => api.getCurrentExercise(token: 'access-1'),
        throwsA(isA<FormatException>()),
      );
    });

    test('非 2xx 抛 ApiException 并带出服务端 code', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            403,
            <String, dynamic>{
              'status': 403,
              'code': ApiErrorCode.authForbidden,
              'traceId': 'trace-exercise',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure =
          await expectApiFailure(api.getCurrentExercise(token: 'access-1'));

      expect(failure.statusCode, 403);
      expect(failure.code, ApiErrorCode.authForbidden);
    });
  });

  group('listCurrentExerciseLogs（日志流水）', () {
    test('带 sinceSeq/limit：GET /api/v1/exercise/current/logs，回 items 与 latestSeq', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[
              <String, dynamic>{
                'seq': 41,
                'at': '2026-10-29T01:10:00Z',
                'type': 'DIE',
                'vestNum': 21,
                'detail': '命中胸部',
              },
              <String, dynamic>{
                'seq': 42,
                'at': '2026-10-29T01:10:02Z',
                'type': 'SYSTEM',
                'detail': '实例重启后自愈收尾',
              },
            ],
            'latestSeq': 42,
          });

      final ExerciseLogPage page =
          await api.listCurrentExerciseLogs(token: 'access-1', sinceSeq: 40, limit: 100);

      expect(page.items.length, 2);
      expect(page.items.first.seq, 41);
      expect(page.items.first.type, ExerciseLogType.die);
      expect(page.items.first.vestNum, 21);
      expect(page.items.last.type, ExerciseLogType.system);
      expect(page.items.last.vestNum, isNull);
      expect(page.latestSeq, 42);
      expect(seen.single.uri.path, '$kApiPrefix/exercise/current/logs');
      expect(seen.single.uri.queryParameters, <String, String>{'sinceSeq': '40', 'limit': '100'});
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('不带参数：不拼查询串（走服务端默认 limit=200）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[],
            'latestSeq': 42,
          });

      final ExerciseLogPage page = await api.listCurrentExerciseLogs(token: 'access-1');

      expect(page.items, isEmpty);
      expect(page.latestSeq, 42);
      expect(seen.single.uri.query, isEmpty);
    });

    test('无演习：回 200 + 空页 + latestSeq=null', () async {
      handler = (HttpRequest request) =>
          respondJson(request, 200, <String, dynamic>{'items': <dynamic>[], 'latestSeq': null});

      final ExerciseLogPage page = await api.listCurrentExerciseLogs(token: 'access-1');

      expect(page.items, isEmpty);
      expect(page.latestSeq, isNull);
    });

    test('未知 LogType 字面量：抛 FormatException', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[
              <String, dynamic>{'seq': 1, 'at': '2026-10-29T01:10:00Z', 'type': 'RESPAWN'},
            ],
            'latestSeq': 1,
          });

      expect(
        () => api.listCurrentExerciseLogs(token: 'access-1'),
        throwsA(isA<FormatException>()),
      );
    });
  });

  group('listPlans（想定列表）', () {
    test('带 keyword/page/size：GET /api/v1/plans，回 items 与 meta', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[
              <String, dynamic>{
                'planId': 'plan-7',
                'name': '城市巷战想定',
                'mapId': 'map-3',
                'battleMode': 'BATTLE',
                'remark': '夜间',
                'rosterCount': 16,
                'createdAt': '2026-10-20T08:00:00Z',
                'updatedAt': '2026-10-28T09:30:00Z',
              },
            ],
            'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
          });

      final PlanPage result =
          await api.listPlans(token: 'access-1', keyword: '巷战', page: 1, size: 20);

      expect(result.items.single.planId, 'plan-7');
      expect(result.items.single.name, '城市巷战想定');
      expect(result.items.single.battleMode, BattleMode.battle);
      expect(result.items.single.rosterCount, 16);
      expect(result.meta.total, 1);
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/plans');
      expect(seen.single.uri.queryParameters,
          <String, String>{'keyword': '巷战', 'page': '1', 'size': '20'});
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('不带参数：不拼查询串（走服务端默认分页）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[],
            'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 0},
          });

      final PlanPage result = await api.listPlans(token: 'access-1');

      expect(result.items, isEmpty);
      expect(result.meta.total, 0);
      expect(seen.single.uri.query, isEmpty);
    });
  });

  group('createPlan / getPlan / updatePlan / deletePlan', () {
    test('createPlan：POST /api/v1/plans，正文全键（含显式 null），201 回 Plan', () async {
      handler = (HttpRequest request) => respondJson(request, 201, <String, dynamic>{
            'planId': 'plan-9',
            'name': '新建想定',
            'battleMode': 'TRAIN',
            'createdAt': '2026-10-29T02:00:00Z',
          });

      final Plan plan = await api.createPlan(
        token: 'access-1',
        request: const PlanUpsertRequest(name: '新建想定'),
      );

      expect(plan.planId, 'plan-9');
      expect(plan.battleMode, BattleMode.train);
      expect(plan.mapId, isNull);
      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/plans');
      expect(seen.single.contentType?.mimeType, 'application/json');
      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{'name': '新建想定', 'mapId': null, 'battleMode': null, 'remark': null},
      );
    });

    test('getPlan：404 → ApiException(planNotFound)', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            404,
            <String, dynamic>{
              'status': 404,
              'code': ApiErrorCode.planNotFound,
              'traceId': 'trace-plan',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure =
          await expectApiFailure(api.getPlan(token: 'access-1', planId: 'missing'));

      expect(failure.statusCode, 404);
      expect(failure.code, ApiErrorCode.planNotFound);
      expect(seen.single.uri.path, '$kApiPrefix/plans/missing');
    });

    test('updatePlan：PUT 整体替换——解绑底图时 mapId=null 亦显式发出', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'planId': 'plan-7',
            'name': '改名后的想定',
            'battleMode': 'BATTLE',
            'createdAt': '2026-10-20T08:00:00Z',
            'updatedAt': '2026-10-29T02:05:00Z',
          });

      final Plan plan = await api.updatePlan(
        token: 'access-1',
        planId: 'plan-7',
        request: const PlanUpsertRequest(name: '改名后的想定', battleMode: BattleMode.battle),
      );

      expect(plan.name, '改名后的想定');
      expect(plan.mapId, isNull);
      expect(seen.single.method, 'PUT');
      expect(seen.single.uri.path, '$kApiPrefix/plans/plan-7');
      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{
          'name': '改名后的想定',
          'mapId': null,
          'battleMode': 'BATTLE',
          'remark': null,
        },
      );
    });

    test('deletePlan：DELETE /api/v1/plans/{id}，204 无正文', () async {
      handler = (HttpRequest request) async {
        request.response.statusCode = 204;
        await request.response.close();
      };

      await api.deletePlan(token: 'access-1', planId: 'plan-9');

      expect(seen.single.method, 'DELETE');
      expect(seen.single.uri.path, '$kApiPrefix/plans/plan-9');
      expect(seen.single.authorization, 'Bearer access-1');
    });
  });

  group('编成（roster）', () {
    test('getRoster：GET /api/v1/plans/{id}/roster，回 entries', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'planId': 'plan-7',
            'entries': <dynamic>[
              <String, dynamic>{
                'vestNum': 21,
                'team': 'RED',
                'name': '突击手',
                'hp': 100,
                'ammo1': 30,
                'ammo2': 5,
                'mode': 'MANUAL',
              },
            ],
          });

      final Roster roster = await api.getRoster(token: 'access-1', planId: 'plan-7');

      expect(roster.planId, 'plan-7');
      expect(roster.entries.single.vestNum, 21);
      expect(roster.entries.single.team, Team.red);
      expect(roster.entries.single.mode, VestMode.manual);
      expect(roster.entries.single.ammo2, 5);
      expect(seen.single.uri.path, '$kApiPrefix/plans/plan-7/roster');
    });

    test('replaceRoster：PUT 全量替换，正文只发非 null 键（缺席走服务端默认）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'planId': 'plan-7',
            'entries': <dynamic>[
              <String, dynamic>{
                'vestNum': 21,
                'team': 'RED',
                'hp': 100,
                'ammo1': 30,
                'ammo2': 30,
                'mode': 'AUTO',
              },
            ],
          });

      final Roster result = await api.replaceRoster(
        token: 'access-1',
        planId: 'plan-7',
        roster: const Roster(
          planId: 'plan-7',
          entries: <RosterEntry>[RosterEntry(vestNum: 21, team: Team.red)],
        ),
      );

      expect(result.entries.single.vestNum, 21);
      expect(seen.single.method, 'PUT');
      expect(seen.single.uri.path, '$kApiPrefix/plans/plan-7/roster');
      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{
          'planId': 'plan-7',
          'entries': <dynamic>[
            <String, dynamic>{'vestNum': 21, 'team': 'RED'},
          ],
        },
      );
    });

    test('replaceRoster：空列表即清空，正文 entries 为空数组', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'planId': 'plan-7',
            'entries': <dynamic>[],
          });

      await api.replaceRoster(
        token: 'access-1',
        planId: 'plan-7',
        roster: const Roster(planId: 'plan-7', entries: <RosterEntry>[]),
      );

      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{'planId': 'plan-7', 'entries': <dynamic>[]},
      );
    });
  });

  group('想定模板与导入（二进制 / multipart）', () {
    test('downloadPlanTemplate：GET 带 format=XLSX，回原始二进制与 contentType', () async {
      const List<int> xlsx = <int>[0x50, 0x4B, 0x03, 0x04, 0x00, 0xFF];
      handler = (HttpRequest request) async {
        request.response
          ..statusCode = 200
          ..headers.contentType = ContentType(
            'application',
            'vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          );
        request.response.add(xlsx);
        await request.response.close();
      };

      final ApiResponse response = await api.downloadPlanTemplate(token: 'access-1');

      expect(response.statusCode, 200);
      expect(response.body, xlsx);
      expect(response.contentType,
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/plans/template');
      expect(seen.single.uri.queryParameters, <String, String>{'format': 'XLSX'});
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('downloadPlanTemplate：format=CSV 时查询串随之改变', () async {
      handler = (HttpRequest request) async {
        request.response
          ..statusCode = 200
          ..headers.contentType = ContentType('text', 'csv', charset: 'utf-8');
        request.response.add(utf8.encode('vestNum,team\n'));
        await request.response.close();
      };

      await api.downloadPlanTemplate(token: 'access-1', format: PlanTemplateFormat.csv);

      expect(seen.single.uri.queryParameters, <String, String>{'format': 'CSV'});
    });

    test('importPlan：POST multipart/form-data，正文含 name="file" 与 filename', () async {
      handler = (HttpRequest request) => respondJson(request, 201, <String, dynamic>{
            'planId': 'plan-imported',
            'name': '导入想定',
            'createdAt': '2026-10-29T02:10:00Z',
          });

      final Plan plan = await api.importPlan(
        token: 'access-1',
        filename: 'template.xlsx',
        bytes: <int>[0x50, 0x4B, 0x03, 0x04],
        name: '导入想定',
      );

      expect(plan.planId, 'plan-imported');
      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/plans/import');
      expect(seen.single.contentType?.mimeType, 'multipart/form-data');
      expect(seen.single.contentType?.parameters['boundary'], isNotEmpty);
      final String body = utf8.decode(seen.single.body, allowMalformed: true);
      expect(body, contains('name="name"'));
      expect(body, contains('导入想定'));
      expect(body, contains('name="file"'));
      expect(body, contains('filename="template.xlsx"'));
    });
  });

  group('开演 / 停演', () {
    test('startExercise：POST /api/v1/exercise/current（只发非 null 键），201 回 Exercise', () async {
      handler = (HttpRequest request) => respondJson(request, 201, <String, dynamic>{
            'running': true,
            'exerciseId': 'ex-2026-002',
            'planId': 'plan-7',
            'battleMode': 'TRAIN',
            'startedAt': '2026-10-29T02:20:00Z',
            'elapsedSeconds': 0,
            'rosterCount': 16,
            'onlineCount': 0,
          });

      final Exercise exercise = await api.startExercise(
        token: 'access-1',
        request: const StartExerciseRequest(planId: 'plan-7'),
      );

      expect(exercise.running, isTrue);
      expect(exercise.exerciseId, 'ex-2026-002');
      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/exercise/current');
      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{'planId': 'plan-7'},
      );
    });

    test('startExercise：409 DEVICE_LINK_NOT_OPEN → ApiException，battleMode 显式发出', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            409,
            <String, dynamic>{
              'status': 409,
              'code': ApiErrorCode.deviceLinkNotOpen,
              'traceId': 'trace-start',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(api.startExercise(
        token: 'access-1',
        request: const StartExerciseRequest(planId: 'plan-7', battleMode: BattleMode.battle),
      ));

      expect(failure.statusCode, 409);
      expect(failure.code, ApiErrorCode.deviceLinkNotOpen);
      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{'planId': 'plan-7', 'battleMode': 'BATTLE'},
      );
    });

    test('stopExercise：DELETE /api/v1/exercise/current，回 StopExerciseResult', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'exerciseId': 'ex-2026-002',
            'endedAt': '2026-10-29T02:40:00Z',
            'replayId': 'ex-2026-002',
            'endReason': 'NORMAL',
          });

      final StopExerciseResult result = await api.stopExercise(token: 'access-1');

      expect(result.exerciseId, 'ex-2026-002');
      expect(result.replayId, 'ex-2026-002');
      expect(result.endReason, ExerciseEndReason.normal);
      expect(seen.single.method, 'DELETE');
      expect(seen.single.uri.path, '$kApiPrefix/exercise/current');
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('stopExercise：409 EXERCISE_NOT_RUNNING → ApiException', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            409,
            <String, dynamic>{
              'status': 409,
              'code': ApiErrorCode.exerciseNotRunning,
              'traceId': 'trace-stop',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(api.stopExercise(token: 'access-1'));

      expect(failure.statusCode, 409);
      expect(failure.code, ApiErrorCode.exerciseNotRunning);
    });
  });
}

/// 打桩服务端记录到的请求快照（`HttpRequest` 在响应关闭后不可读，故先抄下来）。
class _Seen {
  _Seen({
    required this.method,
    required this.uri,
    required this.authorization,
    required this.contentType,
    required this.body,
  });

  final String method;
  final Uri uri;
  final String? authorization;
  final ContentType? contentType;
  final List<int> body;
}

Future<void> respondJson(
  HttpRequest request,
  int status,
  Object body, {
  String contentType = 'application/json',
}) async {
  // 必须显式声明 charset：HttpResponse 在无 charset 时按 latin1 编码，写中文会抛
  // 「Contains invalid characters」，表现为客户端等到超时。
  final List<String> parts = contentType.split('/');
  request.response
    ..statusCode = status
    ..headers.contentType = ContentType(parts[0], parts[1], charset: 'utf-8');
  request.response.add(utf8.encode(jsonEncode(body)));
  await request.response.close();
}

/// 捕获 [ApiException]；未抛出时以 `fail` 报出真实结果。
Future<ApiException> expectApiFailure(Future<Object?> request) async {
  try {
    final Object? result = await request;
    fail('应当抛出 ApiException，实际得到 $result');
  } on ApiException catch (failure) {
    return failure;
  }
}
