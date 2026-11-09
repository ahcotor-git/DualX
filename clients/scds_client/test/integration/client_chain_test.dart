/// 段 67「客户端内集成 harness」——**一条代表性端到端链**。
///
/// 这里跑的不是单层单测，而是**跨层拼接**：真 `ApiClient`（真 `dart:io` HTTP）→
/// 真 `RefreshingApiTransport` → 各域 `*Api` → 进程内真 `HttpServer`（见
/// `support/stub_backend.dart`）；WS 侧用真 `connectSituationSocket` / `connectLinkSocket`
/// 对进程内真 WebSocket 升级。断言落在各层之间的接缝上：
/// 路径前缀、`Authorization` 头、请求体形状、非 2xx → `ApiException` 的错误码、WS 帧同构性。
///
/// 代表性链：**登录 → 当前用户 → 当前演习 → 判死指令 → 手动置位被拒（BATTLE）**，
/// 另含「令牌到期 → 单飞刷新 → 重试」与「态势 / 链路 WS 首帧」两条横向接缝。
///
/// 依据：`docs/decision-register.md`「段 66 落地」→ 下一段 ①「段 59~66 集成层走通」；
/// 真后端实例因 Docker daemon 未运行不可用（`docs/environments.md`），故用进程内假后端承载。
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_client.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/auth_api.dart';
import 'package:scds_client/shared/api/auth_models.dart';
import 'package:scds_client/shared/api/command_api.dart';
import 'package:scds_client/shared/api/command_models.dart';
import 'package:scds_client/shared/api/device_models.dart';
import 'package:scds_client/shared/api/exercise_api.dart';
import 'package:scds_client/shared/api/exercise_models.dart';
import 'package:scds_client/shared/api/situation_models.dart';
import 'package:scds_client/shared/auth/refreshing_api_transport.dart';
import 'package:scds_client/shared/auth/session_store.dart';
import 'package:scds_client/shared/state/links_socket.dart';
import 'package:scds_client/shared/state/situation_socket.dart';

import 'support/stub_backend.dart';

void main() {
  late StubBackend backend;
  late ApiClient http;
  late RefreshingApiTransport transport;
  late AuthApi auth;
  late AccountSession session;

  setUp(() async {
    backend = await StubBackend.start();
    // 真 ApiClient 指向进程内真 HttpServer（注入的 baseUri 覆盖默认 127.0.0.1:11001）。
    http = ApiClient(baseUri: backend.baseUri);
    transport = RefreshingApiTransport(inner: http);
    auth = AuthApi(transport);
    // 真登录：拿到真 TokenPair 并接入会话（后续端点用同一 accessToken）。
    final TokenPair pair = await auth.login(username: 'commander', password: 'p@ss');
    session = AccountSession.fromTokenPair(pair);
    transport.adopt(session);
  });

  tearDown(() async {
    transport.close();
    await backend.stop();
  });

  group('代表性端到端链（真 HTTP / 真 WS）', () {
    test('登录 → 当前用户 → 当前演习 → 判死 → 手动置位被拒（BATTLE）', () async {
      // 1) 当前用户：身份源与登录同库。
      final AuthUser user = await auth.me(accessToken: session.accessToken);
      expect(user.username, 'commander');
      expect(user.roles, <String>['DIRECTOR']);

      // 2) 当前演习：running=true，带出想定快照与场次模式。
      final Exercise exercise =
          await ExerciseApi(transport).getCurrentExercise(token: session.accessToken);
      expect(exercise.running, isTrue);
      expect(exercise.exerciseId, 'ex-2026-11-09');
      expect(exercise.planName, '城区防守演练');
      expect(exercise.battleMode, BattleMode.battle);

      final CommandApi commands = CommandApi(transport);

      // 3) 判死：HTTP 200 只表示「已受理」，下发字节可逐字节核对。
      final CommandAck kill =
          await commands.killVest(vestNum: 7, token: session.accessToken);
      expect(kill.accepted, isTrue);
      expect(kill.command, 'DIE');
      expect(kill.downlinkHex, 'ff0100070007');

      // 4) 手动置位：BATTLE 场 → 403 `COMMAND_MOVE_FORBIDDEN`（决策 07）。
      final ApiException forbidden = await _expectFailure(commands.moveVest(
        vestNum: 7,
        request: const MoveVestRequest(floorNum: 1, posNum: 3),
        token: session.accessToken,
      ));
      expect(forbidden.statusCode, 403);
      expect(forbidden.code, ApiErrorCode.commandMoveForbidden);
      expect(forbidden.problem?.traceId, 'trace-stub-403');
    });

    test('请求面：真 HTTP 带 /api/v1 前缀、Bearer 头与契约请求体', () async {
      backend.battleMode = 'TRAIN'; // 放行手动置位，本用例只关心请求面本身。
      await ExerciseApi(transport).getCurrentExercise(token: session.accessToken);
      await CommandApi(transport).moveVest(
        vestNum: 7,
        request: const MoveVestRequest(floorNum: 1, posNum: 3),
        token: session.accessToken,
      );

      final StubRequest current = _last(backend, '$kApiPrefix/exercise/current');
      expect(current.method, 'GET');
      expect(current.bearer, 'Bearer access-local-1');

      final StubRequest move = _last(backend, '$kApiPrefix/command/vests/7/move');
      expect(move.method, 'POST');
      expect(move.bearer, 'Bearer access-local-1');
      // MoveVestRequest.toJson 只输出非 null 键。
      expect(move.json, <String, dynamic>{'floorNum': 1, 'posNum': 3});
    });

    test('无演习进行中：回 200 + running=false（不是 404）', () async {
      backend.running = false;

      final Exercise exercise =
          await ExerciseApi(transport).getCurrentExercise(token: session.accessToken);

      expect(exercise.running, isFalse);
      expect(exercise.exerciseId, isNull);
      expect(exercise.startedAt, isNull);
    });

    test('TRAIN 场：手动置位放行，且不下发任何字节（downlinkHex 为 null）', () async {
      backend.battleMode = 'TRAIN';

      final CommandAck ack = await CommandApi(transport).moveVest(
        vestNum: 7,
        request: const MoveVestRequest(floorNum: 2, posNum: 9),
        token: session.accessToken,
      );

      expect(ack.accepted, isTrue);
      expect(ack.downlinkHex, isNull);
    });

    test('退出登录：204 空正文不抛异常', () async {
      await auth.logout(accessToken: session.accessToken);

      final StubRequest logout = _last(backend, '$kApiPrefix/auth/logout');
      expect(logout.method, 'POST');
      expect(logout.bearer, 'Bearer access-local-1');
    });
  });

  group('令牌续期（真 HTTP：到期 → 单飞刷新 → 重试一次）', () {
    test('401 AUTH_TOKEN_EXPIRED → 刷新一次 → 重试成功，且保留原身份', () async {
      backend.expireAccessToken = true;

      final AuthUser user = await auth.me(accessToken: session.accessToken);

      expect(user.username, 'commander');
      expect(transport.refreshCount, 1);
      expect(backend.refreshCalls, 1);
      // 刷新响应刻意不含 user → 装饰器保留原身份快照，只换令牌。
      expect(transport.session?.accessToken, 'access-local-2');
      expect(transport.session?.user?.username, 'commander');
    });

    test('刷新后重试用的是新令牌（旧令牌已作废）', () async {
      backend.expireAccessToken = true;

      await auth.me(accessToken: session.accessToken);

      final StubRequest retry = _last(backend, '$kApiPrefix/auth/me');
      expect(retry.bearer, 'Bearer access-local-2');
    });
  });

  group('WebSocket（真 WS 升级 + 子协议协商）', () {
    test('态势 WS：连上即收一帧 full 快照（与 HTTP Poll 同构）', () async {
      final SituationSocket socket = await connectSituationSocket(
        baseUri: backend.baseUri,
        accessToken: session.accessToken,
      );

      final SituationEnvelope envelope = SituationEnvelope.fromJson(
        jsonDecode(await socket.messages.first) as Map<String, dynamic>,
      );

      expect(envelope.full, isTrue);
      expect(envelope.epoch, 'epoch-1');
      expect(envelope.revision, 42);
      expect(envelope.running, isTrue);
      expect(envelope.units.single.vestNum, 7);
      expect(envelope.units.single.status, VestStatus.live);
      expect(envelope.teamSummary.single.team, Team.red);
      await socket.close();
    });

    test('链路 WS：连上即收整表快照（数组，非单条）', () async {
      final LinkSocket socket = await connectLinkSocket(
        baseUri: backend.baseUri,
        accessToken: session.accessToken,
      );

      final List<LinkStatus> links = (jsonDecode(await socket.messages.first) as List<dynamic>)
          .map((dynamic item) => LinkStatus.fromJson(item as Map<String, dynamic>))
          .toList();

      expect(links.length, 3);
      expect(links.first.kind, LinkKind.serialVest);
      expect(links.first.state, LinkState.open);
      expect(links.first.target, 'COM7');
      expect(links[1].kind, LinkKind.uwbTcpServer);
      expect(links[1].fdCount, 2);
      await socket.close();
    });

    test('态势 WS：上行 PING → 服务端回 PONG（保活）', () async {
      final SituationSocket socket = await connectSituationSocket(
        baseUri: backend.baseUri,
        accessToken: session.accessToken,
      );
      final List<String> frames = <String>[];
      final StreamSubscription<String> subscription = socket.messages.listen(frames.add);
      await _waitFor(() => frames.isNotEmpty);

      socket.send(jsonEncode(<String, Object?>{'action': 'PING'}));
      await _waitFor(() => frames.length >= 2);

      expect(jsonDecode(frames.last), <String, dynamic>{'action': 'PONG'});
      expect(backend.wsUpstream, contains('{"action":"PING"}'));

      await subscription.cancel();
      await socket.close();
    });
  });
}

/// 取最近一条命中该路径的请求。
StubRequest _last(StubBackend backend, String path) => backend.received
    .lastWhere((StubRequest request) => request.uri.path == path);

/// 捕获 [ApiException]；未抛出时以 `fail` 报出真实结果。
Future<ApiException> _expectFailure(Future<Object?> request) async {
  try {
    final Object? result = await request;
    fail('应当抛出 ApiException，实际得到 $result');
  } on ApiException catch (failure) {
    return failure;
  }
}

/// 轮询等待条件成立（真 WS 帧到达有往返延迟，不用固定 sleep 以免抖动）。
Future<void> _waitFor(
  bool Function() predicate, {
  Duration timeout = const Duration(seconds: 3),
}) async {
  final DateTime deadline = DateTime.now().add(timeout);
  while (!predicate()) {
    if (DateTime.now().isAfter(deadline)) {
      fail('等待条件超时');
    }
    await Future<void>.delayed(const Duration(milliseconds: 10));
  }
}
