import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_client.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/auth_models.dart';
import 'package:scds_client/shared/auth/refreshing_api_transport.dart';
import 'package:scds_client/shared/auth/session_store.dart';

/// M6 CLIENT-RESILIENCE 段 C 的契约测试：认证单飞（401 → 刷新 → 重试一次）。
///
/// 与 [api_client] 的测试同口径：用本机 `HttpServer` 打桩（不引 mock 库），
/// 断言的是**真实的请求序列与 Authorization 头**——续期链路的正确性
/// 恰恰体现在「谁在第几次请求带了哪个令牌」。
void main() {
  late HttpServer server;
  late ApiClient client;
  late List<_Call> calls;

  /// 由各用例覆写：按请求路径 / 头决定回什么。
  late Future<void> Function(_Call call, HttpRequest request) responder;

  late int lostCount;
  late List<AccountSession> refreshed;

  AccountSession session() => AccountSession(
        accessToken: 'access-old',
        refreshToken: 'refresh-1',
        expiresAt: DateTime.utc(2030),
        user: const AuthUser(userId: 'u-1', username: 'commander', roles: <String>['DIRECTOR']),
      );

  RefreshingApiTransport transportWith(AccountSession? current) => RefreshingApiTransport(
        inner: client,
        session: current,
        onRefreshed: (AccountSession next) async => refreshed.add(next),
        onLost: () async => lostCount++,
      );

  late RefreshingApiTransport transport;

  setUp(() async {
    calls = <_Call>[];
    lostCount = 0;
    refreshed = <AccountSession>[];
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((HttpRequest request) async {
      final List<int> body = await request.fold<List<int>>(
        <int>[],
        (List<int> acc, List<int> chunk) => acc..addAll(chunk),
      );
      final _Call call = _Call(
        path: request.uri.path,
        authorization: request.headers.value(HttpHeaders.authorizationHeader),
        body: body,
      );
      calls.add(call);
      await responder(call, request);
    });
    responder = (_Call call, HttpRequest request) =>
        respondJson(request, 200, <String, dynamic>{'running': true});
    client = ApiClient(baseUri: Uri(scheme: 'http', host: '127.0.0.1', port: server.port));
    transport = transportWith(session());
  });

  tearDown(() async {
    client.close();
    await server.close(force: true);
  });

  /// 「被旧令牌判到期」：仅当请求带的是 `access-old` 时回 401 `AUTH_TOKEN_EXPIRED`。
  Future<void> expiredForOldToken(_Call call, HttpRequest request) async {
    if (call.authorization == 'Bearer access-old') {
      await respondProblem(request, 401, ApiErrorCode.authTokenExpired);
      return;
    }
    await respondJson(request, 200, <String, dynamic>{'running': true});
  }

  /// 刷新端点成功响应（滚动刷新：回新的 access + refresh 对，**不回** `user`）。
  Future<void> refreshOk(HttpRequest request) async {
    await respondJson(request, 200, <String, dynamic>{
      'accessToken': 'access-new',
      'expiresIn': 900,
      'refreshToken': 'refresh-2',
    });
  }

  /// 已记录的请求路径序列（续期链路的顺序证据）。
  List<String> paths() => calls.map((_Call call) => call.path).toList(growable: false);

  group('续期重试', () {
    test('401 到期：刷新成功后按新令牌重试一次，返回重试响应', () async {
      responder = (_Call call, HttpRequest request) async {
        if (call.path.endsWith('/auth/refresh')) {
          await refreshOk(request);
          return;
        }
        await expiredForOldToken(call, request);
      };

      final ApiResponse response = await transport.get('/situation');

      expect(response.statusCode, 200);
      expect(response.json, <String, dynamic>{'running': true});
      expect(paths(), <String>[
        '$kApiPrefix/situation',
        '$kApiPrefix/auth/refresh',
        '$kApiPrefix/situation',
      ]);
      // 未传 bearerToken → 取会话令牌；重试必须换成刷新回来的新令牌。
      expect(calls[0].authorization, 'Bearer access-old');
      expect(calls[2].authorization, 'Bearer access-new');
      expect(
        (jsonDecode(utf8.decode(calls[1].body)) as Map<String, dynamic>)['refreshToken'],
        'refresh-1',
      );

      expect(transport.refreshCount, 1);
      expect(transport.session!.accessToken, 'access-new');
      expect(transport.session!.refreshToken, 'refresh-2');
      // 刷新响应未回 user：身份快照保留，变的是令牌。
      expect(transport.session!.user!.username, 'commander');
      expect(refreshed.single.accessToken, 'access-new');
      expect(lostCount, 0);
    });

    test('重试仍 401 到期：只重试一次，不再刷新（避免自激循环）', () async {
      responder = (_Call call, HttpRequest request) async {
        if (call.path.endsWith('/auth/refresh')) {
          await refreshOk(request);
          return;
        }
        await respondProblem(request, 401, ApiErrorCode.authTokenExpired);
      };

      final ApiException failure = await expectApiFailure(transport.get('/situation'));

      expect(failure.statusCode, 401);
      expect(failure.code, ApiErrorCode.authTokenExpired);
      expect(transport.refreshCount, 1);
      expect(paths().length, 3);
      expect(paths().where((String path) => path.endsWith('/auth/refresh')).length, 1);
    });

    test('调用方显式传令牌：以调用方为准，不被会话令牌覆盖', () async {
      responder = (_Call call, HttpRequest request) async {
        await respondJson(request, 200, <String, dynamic>{'ok': true});
      };

      await transport.get('/situation', bearerToken: 'access-explicit');

      expect(calls.single.authorization, 'Bearer access-explicit');
      expect(transport.refreshCount, 0);
    });
  });

  group('单飞', () {
    test('并发两笔 401：只发一次 refresh，两笔都按新令牌成功', () async {
      final Completer<void> gate = Completer<void>();
      responder = (_Call call, HttpRequest request) async {
        if (call.path.endsWith('/auth/refresh')) {
          // 卡住刷新响应，制造「两笔 401 都在等同一次刷新」的真实并发。
          await gate.future;
          await refreshOk(request);
          return;
        }
        await expiredForOldToken(call, request);
      };

      final Future<ApiResponse> first = transport.get('/situation');
      final Future<ApiResponse> second = transport.get('/situation/vests/1');
      // 等到「两笔业务请求都已 401、且刷新已入队（被 gate 卡住）」再放行——
      // 否则可能在两笔都还没走到续期时就去断言 refreshCount。
      await waitFor(
        () =>
            calls.where((_Call call) => !call.path.endsWith('/auth/refresh')).length == 2 &&
            transport.refreshCount == 1,
        reason: '两笔业务请求未都 401 并进入同一次刷新',
      );

      gate.complete();
      final List<ApiResponse> both =
          await Future.wait(<Future<ApiResponse>>[first, second]);

      expect(both.map((ApiResponse r) => r.statusCode), <int>[200, 200]);
      expect(transport.refreshCount, 1);
      expect(paths().where((String path) => path.endsWith('/auth/refresh')).length, 1);
      // 两笔重试都换了新令牌。
      expect(
        calls.where((_Call call) => call.path.endsWith('/situation')).last.authorization,
        'Bearer access-new',
      );
    });

    test('后到的 401（本次用的是旧令牌、期间已续期）：直接换新令牌重试，不发第二次 refresh',
        () async {
      final Completer<void> holdLate401 = Completer<void>();
      responder = (_Call call, HttpRequest request) async {
        if (call.path.endsWith('/auth/refresh')) {
          await refreshOk(request);
          return;
        }
        if (call.authorization == 'Bearer access-old') {
          // 把这一笔的 401 压到另一笔完成续期之后再回。
          if (call.path.endsWith('/vests/1')) {
            await holdLate401.future;
          }
          await respondProblem(request, 401, ApiErrorCode.authTokenExpired);
          return;
        }
        await respondJson(request, 200, <String, dynamic>{'ok': true});
      };

      final Future<ApiResponse> late = transport.get('/situation/vests/1');
      await waitFor(
        () => calls.any((_Call call) => call.path.endsWith('/vests/1')),
        reason: '后到的那笔未到达服务端',
      );

      // 这一笔带着旧令牌 → 401 → 完成一次续期 → 用新令牌重试成功。
      expect((await transport.get('/situation')).statusCode, 200);
      expect(transport.refreshCount, 1);

      holdLate401.complete();
      expect((await late).statusCode, 200);

      // 关键：不再发第二次刷新（重放旧 refreshToken 会撤销该账号全部 refreshToken）。
      expect(transport.refreshCount, 1);
      expect(paths().where((String path) => path.endsWith('/auth/refresh')).length, 1);
      expect(calls.last.authorization, 'Bearer access-new');
    });
  });

  group('不触发续期', () {
    test('非 401（403 禁止）不刷新，直接抛出', () async {
      responder = (_Call call, HttpRequest request) async {
        await respondProblem(request, 403, ApiErrorCode.commandMoveForbidden);
      };

      final ApiException failure = await expectApiFailure(transport.get('/situation'));

      expect(failure.statusCode, 403);
      expect(transport.refreshCount, 0);
      expect(paths().length, 1);
    });

    test('401 但 code 为 AUTH_TOKEN_INVALID（已撤销 / 篡改）不刷新', () async {
      responder = (_Call call, HttpRequest request) async {
        await respondProblem(request, 401, ApiErrorCode.authTokenInvalid);
      };

      final ApiException failure = await expectApiFailure(transport.get('/situation'));

      expect(failure.code, ApiErrorCode.authTokenInvalid);
      expect(transport.refreshCount, 0);
      expect(lostCount, 0);
    });

    test('未接入会话：401 直接抛出，不发刷新', () async {
      responder = (_Call call, HttpRequest request) async {
        await respondProblem(request, 401, ApiErrorCode.authTokenExpired);
      };
      final RefreshingApiTransport bare = transportWith(null);

      final ApiException failure = await expectApiFailure(bare.get('/situation'));

      expect(failure.statusCode, 401);
      expect(bare.refreshCount, 0);
      expect(calls.single.authorization, isNull);
    });

    test('会话无 refreshToken（设备令牌形态）：401 直接抛出，不发刷新', () async {
      responder = (_Call call, HttpRequest request) async {
        await respondProblem(request, 401, ApiErrorCode.authTokenExpired);
      };
      final RefreshingApiTransport deviceLike = transportWith(
        AccountSession(accessToken: 'device-token', expiresAt: DateTime.utc(2030)),
      );

      await expectApiFailure(deviceLike.get('/situation'));

      expect(deviceLike.refreshCount, 0);
      expect(calls.single.authorization, 'Bearer device-token');
    });
  });

  group('续期失败', () {
    test('refresh 被 401 拒绝：清空本地会话、回调 onLost、原请求不重试', () async {
      responder = (_Call call, HttpRequest request) async {
        if (call.path.endsWith('/auth/refresh')) {
          await respondProblem(request, 401, ApiErrorCode.authTokenInvalid);
          return;
        }
        await respondProblem(request, 401, ApiErrorCode.authTokenExpired);
      };

      final ApiException failure = await expectApiFailure(transport.get('/situation'));

      expect(failure.code, ApiErrorCode.authTokenInvalid);
      expect(transport.session, isNull);
      expect(lostCount, 1);
      expect(paths().length, 2);
    });

    test('refresh 遇非 401（503 未就绪）：不算会话终结——不清会话、不回调 onLost', () async {
      responder = (_Call call, HttpRequest request) async {
        if (call.path.endsWith('/auth/refresh')) {
          await respondProblem(request, 503, ApiErrorCode.instanceNotReady);
          return;
        }
        await respondProblem(request, 401, ApiErrorCode.authTokenExpired);
      };

      final ApiException failure = await expectApiFailure(transport.get('/situation'));

      expect(failure.statusCode, 503);
      expect(transport.session, isNotNull);
      expect(transport.session!.refreshToken, 'refresh-1');
      expect(lostCount, 0);
      expect(paths().length, 2);
    });

    test('刷新成功但服务端未回 user / refreshToken：保留原身份与原刷新令牌', () async {
      responder = (_Call call, HttpRequest request) async {
        if (call.path.endsWith('/auth/refresh')) {
          await respondJson(request, 200, <String, dynamic>{
            'accessToken': 'access-new',
            'expiresIn': 600,
          });
          return;
        }
        await expiredForOldToken(call, request);
      };

      await transport.get('/situation');

      expect(transport.session!.accessToken, 'access-new');
      expect(transport.session!.refreshToken, 'refresh-1');
      expect(transport.session!.user!.userId, 'u-1');
    });
  });

  group('手动续期', () {
    test('refreshNow：发一次 refresh，整体换会话并回调 onRefreshed', () async {
      responder = (_Call call, HttpRequest request) => refreshOk(request);

      final AccountSession next = await transport.refreshNow();

      expect(paths(), <String>['$kApiPrefix/auth/refresh']);
      expect(transport.refreshCount, 1);
      expect(next.accessToken, 'access-new');
      expect(next.refreshToken, 'refresh-2');
      expect(transport.session!.accessToken, 'access-new');
      expect(transport.session!.refreshToken, 'refresh-2');
      // 刷新响应未回 user：身份快照保留（变的是令牌，不是身份）。
      expect(transport.session!.user!.username, 'commander');
      expect(refreshed.single.accessToken, 'access-new');
      expect(lostCount, 0);
    });

    test('并发两笔 refreshNow：只发一次 refresh，两笔复用同一结果', () async {
      final Completer<void> gate = Completer<void>();
      responder = (_Call call, HttpRequest request) async {
        await gate.future;
        await refreshOk(request);
      };

      final Future<AccountSession> first = transport.refreshNow();
      final Future<AccountSession> second = transport.refreshNow();
      expect(transport.refreshCount, 1);

      gate.complete();
      final List<AccountSession> both =
          await Future.wait(<Future<AccountSession>>[first, second]);

      expect(both.map((AccountSession s) => s.accessToken), <String>['access-new', 'access-new']);
      expect(transport.refreshCount, 1);
      // 单飞口径的直接证据：服务端只收到一次刷新请求。
      expect(paths(), <String>['$kApiPrefix/auth/refresh']);
    });

    test('未接入会话：抛错且不发请求', () async {
      final RefreshingApiTransport bare = transportWith(null);

      await expectLater(bare.refreshNow(), throwsStateError);

      expect(calls, isEmpty);
      expect(bare.refreshCount, 0);
    });

    test('会话无 refreshToken（设备令牌形态）：抛错且不发请求', () async {
      final RefreshingApiTransport deviceLike = transportWith(
        AccountSession(accessToken: 'device-token', expiresAt: DateTime.utc(2030)),
      );

      await expectLater(deviceLike.refreshNow(), throwsStateError);

      expect(calls, isEmpty);
      expect(deviceLike.refreshCount, 0);
    });

    test('刷新被 401 拒绝：与自动续期同口径——清空会话、回调 onLost、原样抛出', () async {
      responder = (_Call call, HttpRequest request) =>
          respondProblem(request, 401, ApiErrorCode.authTokenInvalid);

      final ApiException failure = await expectApiFailure(transport.refreshNow());

      expect(failure.code, ApiErrorCode.authTokenInvalid);
      expect(transport.session, isNull);
      expect(lostCount, 1);
      expect(paths(), <String>['$kApiPrefix/auth/refresh']);
    });

    test('刷新遇非 401（503 未就绪）：不算会话终结——清空会话不生效、不回调 onLost', () async {
      responder = (_Call call, HttpRequest request) =>
          respondProblem(request, 503, ApiErrorCode.instanceNotReady);

      final ApiException failure = await expectApiFailure(transport.refreshNow());

      expect(failure.statusCode, 503);
      expect(transport.session, isNotNull);
      expect(lostCount, 0);
    });
  });

  group('透传', () {
    test('成功请求不触发刷新', () async {
      final ApiResponse response = await transport.get('/situation');

      expect(response.statusCode, 200);
      expect(transport.refreshCount, 0);
      expect(paths(), <String>['$kApiPrefix/situation']);
    });

    test('路径拼接与探测透传内层', () async {
      expect(transport.baseUri, client.baseUri);
      expect(transport.api('/auth/me').path, '$kApiPrefix/auth/me');
      expect(transport.resolve('actuator/health').path, '/actuator/health');

      await transport.probe(kHealthProbePath);

      expect(paths(), <String>['/actuator/health']);
      expect(transport.refreshCount, 0);
    });

    test('close 透传：关闭后底层不再接受请求', () async {
      transport.close();

      await expectLater(transport.get('/situation'), throwsA(anything));
    });
  });
}

/// 健康探测端点（与 `HealthApi.kHealthPath` 同值；此处避免跨包依赖只取字面量）。
const String kHealthProbePath = '/actuator/health';

/// 轮询等待条件成立；超时即失败（用于等待「请求已到达服务端」这类真实 I/O 事件）。
Future<void> waitFor(bool Function() done, {required String reason}) async {
  for (int i = 0; i < 400; i++) {
    if (done()) {
      return;
    }
    await Future<void>.delayed(const Duration(milliseconds: 5));
  }
  fail('等待超时：$reason');
}

/// 打桩服务端记录到的请求快照（`HttpRequest` 在响应关闭后不可读，故先抄下来）。
class _Call {
  _Call({required this.path, required this.authorization, required this.body});

  final String path;
  final String? authorization;
  final List<int> body;
}

Future<void> respondJson(
  HttpRequest request,
  int status,
  Object body, {
  String contentType = 'application/json',
}) async {
  // 必须显式声明 charset：HttpResponse 无 charset 时按 latin1 编码，写中文会抛。
  final List<String> parts = contentType.split('/');
  request.response
    ..statusCode = status
    ..headers.contentType = ContentType(parts[0], parts[1], charset: 'utf-8');
  request.response.add(utf8.encode(jsonEncode(body)));
  await request.response.close();
}

/// 回一个 RFC 7807 Problem（`code` 是客户端分支的唯一依据）。
Future<void> respondProblem(HttpRequest request, int status, String code) => respondJson(
      request,
      status,
      <String, dynamic>{
        'status': status,
        'code': code,
        'traceId': 'trace-$code',
      },
      contentType: kProblemMediaType,
    );

/// 捕获 [ApiException]；未抛出时以 `fail` 报出真实结果。
///
/// 泛型入参：既接受 `Future<ApiResponse>`（业务请求），也接受 `Future<AccountSession>`
/// （手动续期 [RefreshingApiTransport.refreshNow]）。
Future<ApiException> expectApiFailure<T>(Future<T> request) async {
  try {
    final T result = await request;
    fail('应当抛出 ApiException，实际得到 $result');
  } on ApiException catch (failure) {
    return failure;
  }
}
