import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_client.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/situation_api.dart';
import 'package:scds_client/shared/api/situation_models.dart';

/// 段 3c：situation 域只读四端点的契约测试。
///
/// 与段 2b 同口径——本机 `HttpServer` 打桩，断言**真实 HTTP 行为**：
/// 路径、查询串（只带非 null 参数）、`Bearer` 注入，以及非 2xx → `ApiException` 的映射。
void main() {
  late HttpServer server;
  late ApiClient client;
  late SituationApi api;
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
    api = SituationApi(client);
  });

  tearDown(() async {
    client.close();
    await server.close(force: true);
  });

  group('getSituation（态势）', () {
    test('不带 sinceRevision：GET /api/v1/situation 且查询串为空、带 Bearer，回全量快照', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'epoch': 'e-1',
            'revision': 42,
            'full': true,
            'running': true,
            'serverTime': '2026-10-28T12:00:00Z',
            'units': <dynamic>[
              <String, dynamic>{'vestNum': 14, 'status': 'LIVE', 'revision': 42},
            ],
          });

      final SituationEnvelope envelope = await api.getSituation(token: 'access-1');

      expect(envelope.revision, 42);
      expect(envelope.full, isTrue);
      expect(envelope.units.single.vestNum, 14);
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/situation');
      expect(seen.single.uri.query, isEmpty);
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('带 sinceRevision 与 hitsLimit、includeSnapshotOnFirst=false：查询串逐项拼出', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'epoch': 'e-1',
            'revision': 43,
            'full': false,
            'running': true,
            'serverTime': '2026-10-28T12:00:05Z',
            'changes': <dynamic>[
              <String, dynamic>{'vestNum': 21, 'status': 'DIE', 'revision': 43},
            ],
            'removedVestNums': <dynamic>[99],
          });

      final SituationEnvelope envelope = await api.getSituation(
        token: 'dev-token',
        sinceRevision: 42,
        includeSnapshotOnFirst: false,
        hitsLimit: 100,
      );

      expect(envelope.changes.single.status, VestStatus.die);
      expect(envelope.removedVestNums, <int>[99]);
      expect(seen.single.uri.queryParameters, <String, String>{
        'sinceRevision': '42',
        'includeSnapshotOnFirst': 'false',
        'hitsLimit': '100',
      });
      expect(seen.single.authorization, 'Bearer dev-token');
    });

    test('409 SITUATION_REVISION_TOO_OLD 抛 ApiException 并带码（调用方改全量重拉）', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            409,
            <String, dynamic>{
              'status': 409,
              'code': ApiErrorCode.situationRevisionTooOld,
              'traceId': 'trace-situation',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(api.getSituation(token: 'access-1', sinceRevision: 1));

      expect(failure.statusCode, 409);
      expect(failure.code, ApiErrorCode.situationRevisionTooOld);
    });
  });

  group('getVestState（单马甲）', () {
    test('GET /api/v1/situation/vests/14 带 Bearer，回 UnitState', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'vestNum': 14,
            'status': 'LIVE',
            'hp': 100,
            'ammo1': 30,
            'position': <String, dynamic>{'coordinateSystem': 'INDOOR', 'xPct': 0.25, 'yPct': 0.5},
            'positionSource': 'UWB',
            'revision': 42,
          });

      final UnitState unit = await api.getVestState(vestNum: 14, token: 'dev-token');

      expect(unit.vestNum, 14);
      expect(unit.hp, 100);
      expect(unit.position!.xPct, 0.25);
      expect(seen.single.uri.path, '$kApiPrefix/situation/vests/14');
      expect(seen.single.authorization, 'Bearer dev-token');
    });

    test('404 抛 ApiException 并带出服务端 code', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            404,
            <String, dynamic>{
              'status': 404,
              'code': ApiErrorCode.deviceVestNotFound,
              'traceId': 'trace-vest',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(api.getVestState(vestNum: 99, token: 'access-1'));

      expect(failure.statusCode, 404);
      expect(failure.code, ApiErrorCode.deviceVestNotFound);
      expect(seen.single.uri.path, '$kApiPrefix/situation/vests/99');
    });
  });

  group('getTeamSummary（队伍汇总）', () {
    test('GET /api/v1/situation/teams/summary 直接回数组', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <dynamic>[
            <String, dynamic>{'team': 'RED', 'total': 8, 'live': 7, 'dead': 1},
            <String, dynamic>{'team': 'BLUE', 'total': 8, 'live': 6, 'dead': 2},
          ]);

      final List<TeamSummary> summaries = await api.getTeamSummary(token: 'access-1');

      expect(summaries.length, 2);
      expect(summaries.first.team, Team.red);
      expect(summaries.first.live, 7);
      expect(summaries.last.team, Team.blue);
      expect(seen.single.uri.path, '$kApiPrefix/situation/teams/summary');
    });

    test('无演习时回空数组，不臆造条目', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <dynamic>[]);

      expect(await api.getTeamSummary(token: 'access-1'), isEmpty);
    });
  });

  group('listHits（命中流水）', () {
    test('GET /api/v1/situation/hits 带 sinceSeq/limit，回 items 与 latestSeq', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[
              <String, dynamic>{
                'seq': 12,
                'at': '2026-10-28T12:01:00Z',
                'hitVestNum': 21,
                'shooterNum': 14,
                'shooterIsSoldier': true,
                'hitPart': 'CHEST',
                'lethal': true,
              },
            ],
            'latestSeq': 12,
          });

      final HitEventPage page = await api.listHits(token: 'access-1', sinceSeq: 11, limit: 50);

      expect(page.items.single.seq, 12);
      expect(page.items.single.hitPart, HitPart.chest);
      expect(page.items.single.lethal, isTrue);
      expect(page.latestSeq, 12);
      expect(seen.single.uri.path, '$kApiPrefix/situation/hits');
      expect(seen.single.uri.queryParameters, <String, String>{'sinceSeq': '11', 'limit': '50'});
    });

    test('不带参数时不拼查询串（走服务端默认 limit=200）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[],
            'latestSeq': 12,
          });

      final HitEventPage page = await api.listHits(token: 'access-1');

      expect(page.items, isEmpty);
      expect(page.latestSeq, 12);
      expect(seen.single.uri.query, isEmpty);
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
