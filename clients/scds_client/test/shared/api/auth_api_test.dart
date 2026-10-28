import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_client.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/auth_api.dart';
import 'package:scds_client/shared/api/auth_models.dart';

/// 段 2b：auth 域客户端（`AuthApi`）的端点契约测试。
///
/// 与段 1b 同口径——本机 `HttpServer` 打桩（不是 mock 库），断言**真实 HTTP 行为**：
/// 路径、请求体、`Bearer` 注入，以及「非 2xx → `ApiException`」的映射。
void main() {
  late HttpServer server;
  late ApiClient client;
  late AuthApi api;
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
    api = AuthApi(client);
  });

  tearDown(() async {
    client.close();
    await server.close(force: true);
  });

  group('login（账号登录）', () {
    test('POST /api/v1/auth/login，正文 username/password，不带 Authorization', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'accessToken': 'access-1',
            'expiresIn': 900,
            'refreshToken': 'refresh-1',
          });

      final TokenPair pair = await api.login(username: 'commander', password: 'p@ss');

      expect(pair.accessToken, 'access-1');
      expect(pair.refreshToken, 'refresh-1');
      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/auth/login');
      expect(seen.single.authorization, isNull);
      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{'username': 'commander', 'password': 'p@ss'},
      );
    });

    test('401 AUTH_BAD_CREDENTIALS 抛 ApiException 并带码', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            401,
            <String, dynamic>{
              'status': 401,
              'code': ApiErrorCode.authBadCredentials,
              'traceId': 'trace-login',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(api.login(username: 'x', password: 'y'));

      expect(failure.statusCode, 401);
      expect(failure.code, ApiErrorCode.authBadCredentials);
    });
  });

  group('refresh（滚动刷新）', () {
    test('POST /api/v1/auth/refresh，正文只带 refreshToken', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'accessToken': 'access-2',
            'expiresIn': 900,
            'refreshToken': 'refresh-2',
          });

      final TokenPair pair = await api.refresh('refresh-1');

      expect(pair.refreshToken, 'refresh-2');
      expect(seen.single.uri.path, '$kApiPrefix/auth/refresh');
      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{'refreshToken': 'refresh-1'},
      );
    });

    test('401 AUTH_TOKEN_EXPIRED 与 AUTH_TOKEN_INVALID 均抛对应码', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            401,
            <String, dynamic>{
              'status': 401,
              'code': ApiErrorCode.authTokenExpired,
              'traceId': 'trace-refresh',
            },
            contentType: kProblemMediaType,
          );
      expect((await expectApiFailure(api.refresh('r'))).code, ApiErrorCode.authTokenExpired);

      handler = (HttpRequest request) => respondJson(
            request,
            401,
            <String, dynamic>{
              'status': 401,
              'code': ApiErrorCode.authTokenInvalid,
              'traceId': 'trace-refresh-2',
            },
            contentType: kProblemMediaType,
          );
      expect((await expectApiFailure(api.refresh('r'))).code, ApiErrorCode.authTokenInvalid);
    });
  });

  group('logout（退出登录）', () {
    test('POST /api/v1/auth/logout：无请求体、带 Bearer、204 不抛', () async {
      handler = (HttpRequest request) async {
        request.response.statusCode = 204;
        await request.response.close();
      };

      await api.logout(accessToken: 'access-1');

      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/auth/logout');
      expect(seen.single.authorization, 'Bearer access-1');
      expect(seen.single.body, isEmpty);
    });
  });

  group('me（当前用户）', () {
    test('GET /api/v1/auth/me 带 Bearer，回 AuthUser', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'userId': 'u-1',
            'username': 'commander',
            'roles': <String>['DIRECTOR'],
          });

      final AuthUser user = await api.me(accessToken: 'access-1');

      expect(user.userId, 'u-1');
      expect(user.roles, <String>['DIRECTOR']);
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/auth/me');
      expect(seen.single.authorization, 'Bearer access-1');
    });
  });

  group('deviceLogin（单兵设备登录）', () {
    test('POST /api/v1/auth/device/login，正文 vestNum/secret，回 DeviceTokenPair', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'deviceToken': 'dev-1',
            'expiresIn': 7200,
          });

      final DeviceTokenPair pair = await api.deviceLogin(vestNum: 14, secret: '8986001234');

      expect(pair.deviceToken, 'dev-1');
      expect(pair.expiresIn, 7200);
      expect(seen.single.uri.path, '$kApiPrefix/auth/device/login');
      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{'vestNum': 14, 'secret': '8986001234'},
      );
    });

    test('401 AUTH_DEVICE_NOT_BOUND 抛 ApiException 并带码', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            401,
            <String, dynamic>{
              'status': 401,
              'code': ApiErrorCode.authDeviceNotBound,
              'traceId': 'trace-device',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(api.deviceLogin(vestNum: 14, secret: 'x'));

      expect(failure.code, ApiErrorCode.authDeviceNotBound);
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
