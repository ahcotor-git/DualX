import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_client.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/command_api.dart';
import 'package:scds_client/shared/api/command_models.dart';
import 'package:scds_client/shared/api/situation_models.dart' show Team;

/// command 域 8 个写操作的契约测试（判死/判活/判伤/超范围/单兵充弹/全体充弹/覆盖配置/手动置位）。
///
/// 与 `exercise_api_test.dart` 同口径——本机 `HttpServer` 打桩，断言**真实 HTTP 行为**：
/// 方法（POST/PUT）、路径、请求体 JSON（只含非 null 键）、`Bearer` 注入，
/// 以及非 2xx → `ApiException` 的映射、`accepted=false` 仍属 200 成功受理。
void main() {
  late HttpServer server;
  late ApiClient client;
  late CommandApi api;
  late List<_Seen> seen;

  /// 由各用例覆写：默认回 200 一条最小 `CommandAck`。
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
    handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
          'vestNum': 0,
          'command': 'NOOP',
          'accepted': true,
        });
    client = ApiClient(baseUri: Uri(scheme: 'http', host: '127.0.0.1', port: server.port));
    api = CommandApi(client);
  });

  tearDown(() async {
    client.close();
    await server.close(force: true);
  });

  group('单兵指令（无请求体）', () {
    test('killVest：POST /api/v1/command/vests/14/kill 且无体、带 Bearer，回 CommandAck', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'vestNum': 14,
            'command': 'DIE',
            'accepted': true,
            'queuedAt': '2026-10-29T02:00:00Z',
            'downlinkHex': '0009ff000e3aaffa',
          });

      final CommandAck ack = await api.killVest(vestNum: 14, token: 'access-1');

      expect(ack.vestNum, 14);
      expect(ack.command, 'DIE');
      expect(ack.accepted, isTrue);
      expect(ack.downlinkHex, '0009ff000e3aaffa');
      expect(ack.isBroadcast, isFalse);
      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/command/vests/14/kill');
      expect(seen.single.uri.query, isEmpty);
      expect(seen.single.body, isEmpty);
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('reviveVest：POST .../revive', () async {
      await api.reviveVest(vestNum: 21, token: 'access-1');
      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/command/vests/21/revive');
      expect(seen.single.body, isEmpty);
    });

    test('injureVest：POST .../injure', () async {
      await api.injureVest(vestNum: 7, token: 'access-1');
      expect(seen.single.uri.path, '$kApiPrefix/command/vests/7/injure');
    });

    test('outOfRangeVest：POST .../out-of-range', () async {
      await api.outOfRangeVest(vestNum: 7, token: 'access-1');
      expect(seen.single.uri.path, '$kApiPrefix/command/vests/7/out-of-range');
    });
  });

  group('充弹', () {
    test('reloadVest：POST .../reload 带 {ammo, weapon} 请求体', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'vestNum': 14,
            'command': 'RELOAD',
            'accepted': true,
          });

      await api.reloadVest(
        vestNum: 14,
        request: const ReloadRequest(ammo: 30, weapon: WeaponSlot.primary),
        token: 'access-1',
      );

      expect(seen.single.uri.path, '$kApiPrefix/command/vests/14/reload');
      expect(_bodyJson(seen.single), <String, dynamic>{'ammo': 30, 'weapon': 'PRIMARY'});
    });

    test('reloadAllVests：POST /api/v1/command/reload-all，回执 vestNum=0 视为「全体」', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'vestNum': 0,
            'command': 'RELOAD',
            'accepted': true,
          });

      final CommandAck ack = await api.reloadAllVests(
        request: const ReloadRequest(ammo: 5, weapon: WeaponSlot.secondary),
        token: 'access-1',
      );

      expect(ack.vestNum, 0);
      expect(ack.isBroadcast, isTrue);
      expect(seen.single.uri.path, '$kApiPrefix/command/reload-all');
      expect(_bodyJson(seen.single), <String, dynamic>{'ammo': 5, 'weapon': 'SECONDARY'});
    });
  });

  group('覆盖配置与手动置位', () {
    test('setVestConfig：PUT .../config，体只含非 null 键（覆盖语义，未传保持原值）', () async {
      await api.setVestConfig(
        vestNum: 14,
        request: const VestConfigRequest(hp: 80, team: Team.red),
        token: 'access-1',
      );

      expect(seen.single.method, 'PUT');
      expect(seen.single.uri.path, '$kApiPrefix/command/vests/14/config');
      expect(_bodyJson(seen.single), <String, dynamic>{'hp': 80, 'team': 'RED'});
    });

    test('setVestConfig：全字段传入时逐键序列化（mode 用契约字面量）', () async {
      await api.setVestConfig(
        vestNum: 14,
        request: const VestConfigRequest(
          mode: VestMode.manual,
          hp: 100,
          ammo1: 30,
          ammo2: 3,
          team: Team.blue,
        ),
        token: 'access-1',
      );

      expect(_bodyJson(seen.single), <String, dynamic>{
        'mode': 'MANUAL',
        'hp': 100,
        'ammo1': 30,
        'ammo2': 3,
        'team': 'BLUE',
      });
    });

    test('moveVest：POST .../move 带 {floorNum, posNum}', () async {
      await api.moveVest(
        vestNum: 14,
        request: const MoveVestRequest(floorNum: 3, posNum: 12),
        token: 'access-1',
      );

      expect(seen.single.uri.path, '$kApiPrefix/command/vests/14/move');
      expect(_bodyJson(seen.single), <String, dynamic>{'floorNum': 3, 'posNum': 12});
    });
  });

  group('受理语义与错误', () {
    test('accepted=false：仍属 HTTP 200 成功受理，不抛异常、queuedAt 为空', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'vestNum': 14,
            'command': 'DIE',
            'accepted': false,
            'queuedAt': null,
          });

      final CommandAck ack = await api.killVest(vestNum: 14, token: 'access-1');

      expect(ack.accepted, isFalse);
      expect(ack.queuedAt, isNull);
    });

    test('409 EXERCISE_NOT_RUNNING（无演习）抛 ApiException 并带码', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            409,
            <String, dynamic>{
              'status': 409,
              'code': ApiErrorCode.exerciseNotRunning,
              'traceId': 'trace-command',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure =
          await expectApiFailure(api.killVest(vestNum: 14, token: 'access-1'));

      expect(failure.statusCode, 409);
      expect(failure.code, ApiErrorCode.exerciseNotRunning);
    });

    test('403 COMMAND_MOVE_FORBIDDEN（正式演习禁手动置位）抛 ApiException 并带码', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            403,
            <String, dynamic>{
              'status': 403,
              'code': ApiErrorCode.commandMoveForbidden,
              'traceId': 'trace-move',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(api.moveVest(
        vestNum: 14,
        request: const MoveVestRequest(floorNum: 1, posNum: 1),
        token: 'access-1',
      ));

      expect(failure.statusCode, 403);
      expect(failure.code, ApiErrorCode.commandMoveForbidden);
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

/// 解析打桩请求的 JSON 请求体（无体时返回空 map）。
Map<String, dynamic> _bodyJson(_Seen seen) => seen.body.isEmpty
    ? <String, dynamic>{}
    : jsonDecode(utf8.decode(seen.body)) as Map<String, dynamic>;

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
