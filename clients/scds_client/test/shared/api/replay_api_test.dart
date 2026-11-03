import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_client.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/exercise_models.dart';
import 'package:scds_client/shared/api/replay_api.dart';
import 'package:scds_client/shared/api/replay_models.dart';
import 'package:scds_client/shared/api/situation_models.dart';

/// replay 域契约测试：复盘列表 / 概览、轨迹分片、统计、异步导出与下载，共 7 个端点。
///
/// 与 `map_api_test.dart` 同口径——本机 `HttpServer` 打桩，断言**真实 HTTP 行为**：
/// 路径（含 `{replayId}` / `{taskId}` 展开）、查询串（只带非 null 参数、`vestNums` 逗号分隔、
/// `from`/`to` 转 UTC ISO-8601）、`Bearer` 注入、请求体口径，以及
/// 非 2xx → `ApiException` 的映射。
void main() {
  late HttpServer server;
  late ApiClient client;
  late ReplayApi api;
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
    api = ReplayApi(client);
  });

  tearDown(() async {
    client.close();
    await server.close(force: true);
  });

  group('listReplays（复盘列表）', () {
    test('带 keyword/from/to/page/size：GET /api/v1/replays，时间转 UTC ISO-8601', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[
              <String, dynamic>{
                'replayId': '7b1e6f0a-1111-4a2b-8c3d-000000000001',
                'planId': '7b1e6f0a-1111-4a2b-8c3d-000000000002',
                'planName': '三号高地攻防',
                'mapId': 'map-3',
                'battleMode': 'BATTLE',
                'startedAt': '2026-10-30T01:00:00Z',
                'endedAt': '2026-10-30T02:30:00Z',
                'durationSeconds': 5400,
                'participantCount': 24,
                'hitCount': 137,
                'deadCount': 9,
              },
            ],
            'meta': <String, dynamic>{'page': 1, 'size': 50, 'total': 1},
          });

      final ReplayPage result = await api.listReplays(
        token: 'access-1',
        keyword: '三号',
        from: DateTime.utc(2026, 10, 1),
        to: DateTime.utc(2026, 10, 31, 23, 59),
        page: 1,
        size: 50,
      );

      final Replay replay = result.items.single;
      expect(replay.replayId, '7b1e6f0a-1111-4a2b-8c3d-000000000001');
      expect(replay.planName, '三号高地攻防');
      expect(replay.battleMode, BattleMode.battle);
      expect(replay.durationSeconds, 5400);
      expect(replay.participantCount, 24);
      expect(replay.hitCount, 137);
      expect(replay.deadCount, 9);
      expect(replay.endedAt, isNotNull);
      expect(result.meta.page, 1);
      expect(result.meta.total, 1);
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/replays');
      expect(seen.single.uri.queryParameters, <String, String>{
        'keyword': '三号',
        'from': '2026-10-01T00:00:00.000Z',
        'to': '2026-10-31T23:59:00.000Z',
        'page': '1',
        'size': '50',
      });
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('不带参数：不拼查询串（走服务端默认分页）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[],
            'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 0},
          });

      final ReplayPage result = await api.listReplays(token: 'access-1');

      expect(result.items, isEmpty);
      expect(result.meta.size, 20);
      expect(seen.single.uri.query, isEmpty);
    });

    test('可空字段缺席（planId/mapId/battleMode/durationSeconds）：解析为 null 不臆造', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[
              <String, dynamic>{
                'replayId': 'r-2',
                'planName': '未结束场次',
                'startedAt': '2026-10-31T01:00:00Z',
              },
            ],
            'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
          });

      final Replay replay = (await api.listReplays(token: 'access-1')).items.single;

      expect(replay.planId, isNull);
      expect(replay.mapId, isNull);
      expect(replay.battleMode, isNull);
      expect(replay.endedAt, isNull);
      expect(replay.durationSeconds, isNull);
      expect(replay.hitCount, isNull);
    });

    test('未知 battleMode 字面量：抛 FormatException（不静默落默认分支）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[
              <String, dynamic>{
                'replayId': 'r-3',
                'planName': 'x',
                'startedAt': '2026-10-31T01:00:00Z',
                'battleMode': 'PEACE',
              },
            ],
            'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
          });

      expect(() => api.listReplays(token: 'access-1'), throwsA(isA<FormatException>()));
    });
  });

  group('getReplay / getReplayTimeline', () {
    test('getReplay：GET /api/v1/replays/{replayId}', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'replayId': 'r-1',
            'planName': '夜训',
            'startedAt': '2026-10-30T01:00:00Z',
            'battleMode': 'TRAIN',
          });

      final Replay replay = await api.getReplay(token: 'access-1', replayId: 'r-1');

      expect(replay.replayId, 'r-1');
      expect(replay.battleMode, BattleMode.train);
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/replays/r-1');
    });

    test('getReplay：404 REPLAY_NOT_FOUND → ApiException', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            404,
            <String, dynamic>{
              'status': 404,
              'code': ApiErrorCode.replayNotFound,
              'traceId': 'trace-replay',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure =
          await expectApiFailure(api.getReplay(token: 'access-1', replayId: 'missing'));

      expect(failure.statusCode, 404);
      expect(failure.code, ApiErrorCode.replayNotFound);
      expect(seen.single.uri.path, '$kApiPrefix/replays/missing');
    });

    test('getReplayTimeline：cursor/limit/vestNums/stride——vestNums 逗号分隔', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'samples': <dynamic>[
              <String, dynamic>{'t': 0, 'vestNum': 1, 'xPct': 0.25, 'yPct': 0.5, 'hp': 100},
            ],
            'nextCursor': 'c-2',
          });

      final TimelineChunk chunk = await api.getReplayTimeline(
        token: 'access-1',
        replayId: 'r-1',
        cursor: 'c-1',
        limit: 2000,
        vestNums: <int>[1, 3, 5],
        stride: 10,
      );

      expect(chunk.samples.single.t, 0);
      expect(chunk.samples.single.vestNum, 1);
      expect(chunk.samples.single.xPct, 0.25);
      expect(chunk.nextCursor, 'c-2');
      expect(seen.single.uri.path, '$kApiPrefix/replays/r-1/timeline');
      expect(seen.single.uri.queryParameters, <String, String>{
        'cursor': 'c-1',
        'limit': '2000',
        'vestNums': '1,3,5',
        'stride': '10',
      });
    });

    test('getReplayTimeline：不带参数不拼查询串；nextCursor 为 null 即末尾', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'samples': <dynamic>[],
            'nextCursor': null,
          });

      final TimelineChunk chunk =
          await api.getReplayTimeline(token: 'access-1', replayId: 'r-1');

      expect(chunk.samples, isEmpty);
      expect(chunk.nextCursor, isNull);
      expect(seen.single.uri.query, isEmpty);
    });

    test('轨迹坐标按平铺口径解析：室内与室外两组各自为本套、另一套为 null', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'samples': <dynamic>[
              <String, dynamic>{
                't': 1200,
                'vestNum': 7,
                'xPct': 0.4,
                'yPct': 0.6,
                'lng': null,
                'lat': null,
                'hp': 80,
                'status': 'LIVE',
              },
              <String, dynamic>{
                't': 2400,
                'vestNum': 8,
                'lng': 116.397,
                'lat': 39.908,
              },
            ],
            'nextCursor': null,
          });

      final TimelineChunk chunk =
          await api.getReplayTimeline(token: 'access-1', replayId: 'r-1');

      final TrackSample indoor = chunk.samples.first;
      expect(indoor.xPct, 0.4);
      expect(indoor.yPct, 0.6);
      expect(indoor.lng, isNull);
      expect(indoor.lat, isNull);
      expect(indoor.hp, 80);
      expect(indoor.status, VestStatus.live);

      final TrackSample outdoor = chunk.samples.last;
      expect(outdoor.xPct, isNull);
      expect(outdoor.yPct, isNull);
      expect(outdoor.lng, 116.397);
      expect(outdoor.lat, 39.908);
      expect(outdoor.hp, isNull);
      expect(outdoor.status, isNull);
    });
  });

  group('getReplayStatistics（复盘统计）', () {
    test('GET /api/v1/replays/{replayId}/statistics——四块统计全解析', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'replayId': 'r-1',
            'teams': <dynamic>[
              <String, dynamic>{'team': 'RED', 'total': 12, 'live': 9, 'dead': 3, 'offline': 0},
            ],
            'topShooters': <dynamic>[
              <String, dynamic>{'vestNum': 7, 'name': '张三', 'hitsDealt': 21, 'kills': 4},
            ],
            'hitPartDistribution': <dynamic>[
              <String, dynamic>{'hitPart': 'HEAD', 'count': 6},
            ],
            'timeline': <dynamic>[
              <String, dynamic>{
                't': 0,
                'liveByTeam': <String, dynamic>{'RED': 12, 'BLUE': 12},
              },
            ],
          });

      final ReplayStatistics stats = await api.getReplayStatistics(
        token: 'access-1',
        replayId: 'r-1',
        timelineBuckets: 200,
      );

      expect(stats.replayId, 'r-1');
      expect(stats.teams.single.team, Team.red);
      expect(stats.teams.single.live, 9);
      expect(stats.topShooters.single.name, '张三');
      expect(stats.topShooters.single.kills, 4);
      expect(stats.hitPartDistribution.single.hitPart, HitPart.head);
      expect(stats.hitPartDistribution.single.count, 6);
      expect(stats.timeline.single.t, 0);
      expect(stats.timeline.single.liveByTeam, <String, int>{'RED': 12, 'BLUE': 12});
      expect(seen.single.uri.path, '$kApiPrefix/replays/r-1/statistics');
      expect(seen.single.uri.queryParameters, <String, String>{'timelineBuckets': '200'});
    });

    test('可选集合缺席：解析为空列表（不是 null）', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            200,
            <String, dynamic>{'replayId': 'r-1'},
          );

      final ReplayStatistics stats =
          await api.getReplayStatistics(token: 'access-1', replayId: 'r-1');

      expect(stats.teams, isEmpty);
      expect(stats.topShooters, isEmpty);
      expect(stats.hitPartDistribution, isEmpty);
      expect(stats.timeline, isEmpty);
      expect(seen.single.uri.query, isEmpty);
    });

    test('未知 hitPart 字面量：抛 FormatException', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'replayId': 'r-1',
            'hitPartDistribution': <dynamic>[
              <String, dynamic>{'hitPart': 'TAIL', 'count': 1},
            ],
          });

      expect(
        () => api.getReplayStatistics(token: 'access-1', replayId: 'r-1'),
        throwsA(isA<FormatException>()),
      );
    });
  });

  group('异步导出（exports）', () {
    test('exportReplay：POST（202），正文 {format: XLSX}，回 taskId + PENDING', () async {
      handler = (HttpRequest request) => respondJson(request, 202, <String, dynamic>{
            'taskId': 'task-1',
            'state': 'PENDING',
            'progressPct': 0,
          });

      final AsyncTask task = await api.exportReplay(
        token: 'access-1',
        replayId: 'r-1',
        request: const ExportRequest(format: ExportFormat.xlsx),
      );

      expect(task.taskId, 'task-1');
      expect(task.state, AsyncTaskState.pending);
      expect(task.progressPct, 0);
      expect(task.downloadUrl, isNull);
      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/replays/r-1/exports');
      expect(seen.single.contentType?.mimeType, 'application/json');
      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{'format': 'XLSX'},
      );
    });

    test('exportReplay：CSV 字面量按契约原样送出', () async {
      handler = (HttpRequest request) => respondJson(request, 202, <String, dynamic>{
            'taskId': 'task-2',
            'state': 'RUNNING',
          });

      await api.exportReplay(
        token: 'access-1',
        replayId: 'r-1',
        request: const ExportRequest(format: ExportFormat.csv),
      );

      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{'format': 'CSV'},
      );
    });

    test('exportReplay：401 → ApiException', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            401,
            <String, dynamic>{
              'status': 401,
              'code': ApiErrorCode.authTokenExpired,
              'traceId': 'trace-401',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(api.exportReplay(
        token: 'access-1',
        replayId: 'r-1',
        request: const ExportRequest(format: ExportFormat.xlsx),
      ));

      expect(failure.statusCode, 401);
      expect(failure.code, ApiErrorCode.authTokenExpired);
    });

    test('getExportTask：GET .../exports/{taskId}，SUCCEEDED 带签名 URL 与有效期', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'taskId': 'task-1',
            'state': 'SUCCEEDED',
            'progressPct': 100,
            'fileName': 'replay-r-1.xlsx',
            'downloadUrl': '/api/v1/files?path=exports/r-1.xlsx&exp=1793000000&signature=AbC',
            'expireAt': '2026-10-31T03:00:00Z',
          });

      final AsyncTask task =
          await api.getExportTask(token: 'access-1', replayId: 'r-1', taskId: 'task-1');

      expect(task.state, AsyncTaskState.succeeded);
      expect(task.progressPct, 100);
      expect(task.fileName, 'replay-r-1.xlsx');
      expect(task.downloadUrl, startsWith('/api/v1/files?path='));
      expect(task.expireAt, isNotNull);
      expect(seen.single.uri.path, '$kApiPrefix/replays/r-1/exports/task-1');
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('getExportTask：未知 state 字面量：抛 FormatException', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            200,
            <String, dynamic>{'taskId': 'task-1', 'state': 'CANCELLED'},
          );

      expect(
        () => api.getExportTask(token: 'access-1', replayId: 'r-1', taskId: 'task-1'),
        throwsA(isA<FormatException>()),
      );
    });

    test('downloadExport：GET .../download，回原始二进制与产物 Content-Type', () async {
      const List<int> xlsx = <int>[0x50, 0x4B, 0x03, 0x04, 0x0A, 0x0B];
      handler = (HttpRequest request) async {
        request.response
          ..statusCode = 200
          ..headers.contentType = ContentType.parse(
              'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
        request.response.add(xlsx);
        await request.response.close();
      };

      final ApiResponse response = await api.downloadExport(
        token: 'access-1',
        replayId: 'r-1',
        taskId: 'task-1',
      );

      expect(response.statusCode, 200);
      expect(response.body, xlsx);
      expect(response.contentType,
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/replays/r-1/exports/task-1/download');
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('downloadExport：409 REPLAY_EXPORT_FAILED → ApiException（未完成或已过期）', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            409,
            <String, dynamic>{
              'status': 409,
              'code': ApiErrorCode.replayExportFailed,
              'traceId': 'trace-export',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(api.downloadExport(
        token: 'access-1',
        replayId: 'r-1',
        taskId: 'task-1',
      ));

      expect(failure.statusCode, 409);
      expect(failure.code, ApiErrorCode.replayExportFailed);
    });
  });

  group('契约解析守则', () {
    test('Replay 必填字段缺失（replayId）：抛 TypeError（不臆造缺省值）', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            200,
            <String, dynamic>{'planName': 'x', 'startedAt': '2026-10-31T01:00:00Z'},
          );

      expect(
        () => api.getReplay(token: 'access-1', replayId: 'r-1'),
        throwsA(isA<TypeError>()),
      );
    });

    test('TrackSample 的 xPct 为整数也解析为 double（num → double）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'samples': <dynamic>[
              <String, dynamic>{'t': 0, 'vestNum': 1, 'xPct': 0, 'yPct': 1},
            ],
          });

      final TimelineChunk chunk =
          await api.getReplayTimeline(token: 'access-1', replayId: 'r-1');

      expect(chunk.samples.single.xPct, 0.0);
      expect(chunk.samples.single.yPct, 1.0);
      expect(chunk.nextCursor, isNull);
    });

    test('AsyncTask 必填字段缺失（taskId）：抛 TypeError', () async {
      handler = (HttpRequest request) =>
          respondJson(request, 200, <String, dynamic>{'state': 'PENDING'});

      expect(
        () => api.getExportTask(token: 'access-1', replayId: 'r-1', taskId: 'task-1'),
        throwsA(isA<TypeError>()),
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
