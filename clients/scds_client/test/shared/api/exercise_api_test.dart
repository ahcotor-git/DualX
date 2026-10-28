import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_client.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/exercise_api.dart';
import 'package:scds_client/shared/api/exercise_models.dart';

/// exercise 域只读两端点的契约测试（当前演习状态 + 当前演习日志流水）。
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
}

/// 打桩服务端记录到的请求快照（`HttpRequest` 在响应关闭后不可读，故先抄下来）。
class _Seen {
  _Seen({
    required this.method,
    required this.uri,
    required this.authorization,
    required this.body,
  });

  final String method;
  final Uri uri;
  final String? authorization;
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
