import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_client.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';

/// 段 1 通信基座的契约测试。
///
/// 用本机 `HttpServer` 打桩（不是 mock 库），所以断言的是**真实 HTTP 行为**：
/// 请求头、正文编码、状态码与非 2xx → `ApiException` 的映射。
void main() {
  late HttpServer server;
  late ApiClient client;
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
        accept: request.headers.value(HttpHeaders.acceptHeader),
        contentType: request.headers.contentType,
        body: body,
      ));
      await handler(request);
    });
    handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{});
    client = ApiClient(
      baseUri: Uri(scheme: 'http', host: '127.0.0.1', port: server.port),
    );
  });

  tearDown(() async {
    client.close();
    await server.close(force: true);
  });

  group('路径拼接', () {
    test('api() 自动带 /api/v1 前缀并编码查询参数', () {
      final Uri uri = client.api('/auth/login', <String, String>{'mode': 'local'});

      expect(uri.scheme, 'http');
      expect(uri.host, '127.0.0.1');
      expect(uri.port, server.port);
      expect(uri.path, '$kApiPrefix/auth/login');
      expect(uri.queryParameters['mode'], 'local');
    });

    test('resolve() 对不带前导斜杠的路径也能补齐', () {
      expect(client.resolve('api/v1/auth/me').path, '$kApiPrefix/auth/me');
    });

    test('契约常量与后端硬约束一致', () {
      expect(kHttpPort, 11001);
      expect(kApiPrefix, '/api/v1');
      expect(kWsPrefix, '/ws/v1');
      expect(kProblemMediaType, 'application/problem+json');
    });
  });

  group('请求构造', () {
    test('GET 回 200：正文按 JSON 解析，且 Accept 覆盖 problem+json', () async {
      handler = (HttpRequest request) =>
          respondJson(request, 200, <String, dynamic>{'username': 'commander'});

      final ApiResponse response = await client.get('/auth/me');

      expect(response.statusCode, 200);
      expect((response.json! as Map<String, dynamic>)['username'], 'commander');
      expect(seen.single.method, 'GET');
      expect(seen.single.accept, contains(kProblemMediaType));
      expect(seen.single.authorization, isNull);
    });

    test('POST 带 jsonBody：Content-Type 为 application/json 且正文为 UTF-8 JSON', () async {
      final ApiResponse response = await client.post(
        '/auth/login',
        jsonBody: <String, dynamic>{'username': 'commander', 'password': 'p@ss'},
      );

      expect(response.statusCode, 200);
      expect(seen.single.method, 'POST');
      expect(seen.single.contentType?.mimeType, 'application/json');
      expect(seen.single.contentType?.charset, 'utf-8');
      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{'username': 'commander', 'password': 'p@ss'},
      );
    });

    test('bearerToken 非空才注入 Authorization，空串不注入', () async {
      await client.get('/auth/me', bearerToken: 'access-token-1');
      expect(seen.single.authorization, 'Bearer access-token-1');

      await client.get('/auth/me', bearerToken: '');
      expect(seen.last.authorization, isNull);
    });

    test('二进制正文原样返回（产物下载不被文本解码破坏）', () async {
      final List<int> binary = <int>[0x89, 0x50, 0x4E, 0x47, 0x00, 0xFF];
      handler = (HttpRequest request) async {
        request.response
          ..statusCode = 200
          ..headers.contentType = ContentType('application', 'octet-stream');
        request.response.add(binary);
        await request.response.close();
      };

      final ApiResponse response = await client.get('/replays/1/exports/2/download');

      expect(response.statusCode, 200);
      expect(response.body, binary);
      expect(response.contentType, 'application/octet-stream');
    });
  });

  group('非 2xx 统一抛 ApiException', () {
    test('Problem 正文：带出 code / status / traceId / errors', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            409,
            <String, dynamic>{
              'type': 'about:blank',
              'title': '演习已在进行中',
              'status': 409,
              'code': ApiErrorCode.exerciseAlreadyRunning,
              'detail': '本实例已有进行中的演习',
              'instance': '/api/v1/exercise/start',
              'traceId': 'trace-abc-123',
            },
            contentType: kProblemMediaType,
          );

      await expectLater(
        client.post('/exercise/start'),
        throwsA(
          isA<ApiException>()
              .having((ApiException e) => e.statusCode, 'statusCode', 409)
              .having((ApiException e) => e.code, 'code', ApiErrorCode.exerciseAlreadyRunning)
              .having((ApiException e) => e.problem?.traceId, 'traceId', 'trace-abc-123')
              .having((ApiException e) => e.message, 'message', contains('trace-abc-123')),
        ),
      );
    });

    test('VALIDATION_FAILED 带出字段级 errors', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            400,
            <String, dynamic>{
              'status': 400,
              'code': ApiErrorCode.validationFailed,
              'traceId': 'trace-validation',
              'errors': <Map<String, String>>[
                <String, String>{'field': 'planId', 'message': '不能为空'},
              ],
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(client.post('/exercise/start'));

      expect(failure.problem!.isValidationFailure, isTrue);
      expect(failure.problem!.errors.single.field, 'planId');
      expect(failure.problem!.errors.single.message, '不能为空');
    });

    test('非 Problem 正文（如代理 HTML 错误页）：problem 为 null、code 回落 HTTP_<status>', () async {
      handler = (HttpRequest request) async {
        request.response
          ..statusCode = 502
          ..headers.contentType = ContentType.html;
        request.response.write('<html><body>Bad Gateway</body></html>');
        await request.response.close();
      };

      final ApiException failure = await expectApiFailure(client.get('/auth/me'));

      expect(failure.statusCode, 502);
      expect(failure.problem, isNull);
      expect(failure.code, 'HTTP_502');
    });

    test('2xx 不抛异常（含 201 / 204 空正文）', () async {
      handler = (HttpRequest request) async {
        request.response.statusCode = 204;
        await request.response.close();
      };

      final ApiResponse response = await client.delete('/plans/1');

      expect(response.statusCode, 204);
      expect(response.body, isEmpty);
      expect(response.json, isNull);
    });
  });

  group('ApiProblem.tryParse 不臆造缺省值', () {
    test('合法 Problem 正常解析', () {
      final ApiProblem? problem = ApiProblem.tryParse(utf8.encode(jsonEncode(<String, dynamic>{
        'status': 403,
        'code': ApiErrorCode.fileUrlInvalid,
        'traceId': 'trace-file',
      })));

      expect(problem, isNotNull);
      expect(problem!.code, ApiErrorCode.fileUrlInvalid);
      expect(problem.status, 403);
      expect(problem.errors, isEmpty);
    });

    test('缺必填字段（traceId）返回 null', () {
      expect(
        ApiProblem.tryParse(utf8.encode(jsonEncode(<String, dynamic>{'status': 400, 'code': 'X'}))),
        isNull,
      );
    });

    test('非 JSON / 非对象 / 空正文一律返回 null', () {
      expect(ApiProblem.tryParse(utf8.encode('not json')), isNull);
      expect(ApiProblem.tryParse(utf8.encode('[1,2,3]')), isNull);
      expect(ApiProblem.tryParse(<int>[]), isNull);
    });
  });

  group('sendBytes / multipartFormData（原始字节请求体）', () {
    test('sendBytes：原样送出字节并保留自定义 Content-Type（含 boundary）', () async {
      const String payload = '----b\r\nraw\r\n--b--\r\n';

      await client.sendBytes(
        'POST',
        '/plans/import',
        body: utf8.encode(payload),
        contentType: 'multipart/form-data; boundary=b',
        bearerToken: 'access-token-1',
      );

      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/plans/import');
      expect(seen.single.contentType?.mimeType, 'multipart/form-data');
      expect(seen.single.contentType?.parameters['boundary'], 'b');
      expect(utf8.decode(seen.single.body), payload);
      expect(seen.single.authorization, 'Bearer access-token-1');
    });

    test('sendBytes：非 2xx 同样抛 ApiException', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            413,
            <String, dynamic>{
              'status': 413,
              'code': 'PAYLOAD_TOO_LARGE',
              'traceId': 'trace-upload',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(client.sendBytes(
        'POST',
        '/plans/import',
        body: <int>[1, 2, 3],
        contentType: 'application/octet-stream',
      ));

      expect(failure.statusCode, 413);
      expect(failure.code, 'PAYLOAD_TOO_LARGE');
    });

    test('multipartFormData：按 RFC 7578 拼装，fields 在前 files 在后、结尾闭合', () {
      final MultipartBody body = multipartFormData(
        fields: <String, String>{'name': '导入想定'},
        files: <String, MultipartFile>{
          'file': MultipartFile(
            filename: 'template.xlsx',
            bytes: <int>[0x50, 0x4B], // 'PK'
            contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          ),
        },
        random: Random(1),
      );

      expect(body.contentType, startsWith('multipart/form-data; boundary=----scdsBoundary'));
      final String boundary = body.contentType.split('boundary=').last;
      final String text = utf8.decode(body.body);

      expect(text, startsWith('--$boundary\r\n'));
      expect(text, endsWith('--$boundary--\r\n'));
      expect(text, contains('Content-Disposition: form-data; name="name"\r\n\r\n导入想定\r\n'));
      expect(
        text,
        contains('Content-Disposition: form-data; name="file"; filename="template.xlsx"\r\n'),
      );
      expect(text, contains('\r\n\r\nPK\r\n'));
      // fields 必须排在 files 之前。
      expect(text.indexOf('name="name"'), lessThan(text.indexOf('name="file"')));
    });
  });
}

/// 打桩服务端记录到的请求快照（`HttpRequest` 在响应关闭后不可读，故先抄下来）。
class _Seen {
  _Seen({
    required this.method,
    required this.uri,
    required this.authorization,
    required this.accept,
    required this.contentType,
    required this.body,
  });

  final String method;
  final Uri uri;
  final String? authorization;
  final String? accept;
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
Future<ApiException> expectApiFailure(Future<ApiResponse> request) async {
  try {
    final ApiResponse response = await request;
    fail('应当抛出 ApiException，实际得到 HTTP ${response.statusCode}');
  } on ApiException catch (failure) {
    return failure;
  }
}
