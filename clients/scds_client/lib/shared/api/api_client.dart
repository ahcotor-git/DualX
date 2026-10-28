import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'api_contract.dart';
import 'api_problem.dart';

/// HTTP 响应：状态码、Content-Type 与原始字节。
///
/// 保留原始字节而非直接解码，是因为下载类端点回的是二进制产物（XLSX/CSV/图片）。
class ApiResponse {
  const ApiResponse({required this.statusCode, required this.contentType, required this.body});

  final int statusCode;
  final String? contentType;
  final List<int> body;

  /// 以 UTF-8 解码正文（容错），仅用于文本/JSON 响应。
  String get text => utf8.decode(body, allowMalformed: true);

  /// 解析 JSON 正文；空正文返回 `null`。
  Object? get json => body.isEmpty ? null : jsonDecode(text);
}

/// 边缘网关 HTTP 客户端。
///
/// 只依赖 `dart:io`（目标平台为 Android / Windows，不引第三方 HTTP 包）。
/// 非 2xx 一律**不吞**：解析 Problem 后抛 [ApiException]，调用方据 `code` 分支。
class ApiClient {
  ApiClient({Uri? baseUri, this.timeout = const Duration(seconds: 15), HttpClient? httpClient})
      : baseUri = baseUri ?? Uri(scheme: 'http', host: kDefaultHost, port: kHttpPort),
        _http = httpClient ?? HttpClient() {
    _http.connectionTimeout = timeout;
  }

  /// 网关基地址（默认 `http://127.0.0.1:11001`）。现场主机由连接配置覆盖。
  final Uri baseUri;

  /// 连接与单次请求的整体超时。
  final Duration timeout;

  final HttpClient _http;

  /// 拼接业务接口路径，自动带 [kApiPrefix]。
  Uri api(String path, [Map<String, String>? query]) => resolve('$kApiPrefix$path', query);

  /// 拼接任意相对路径到 [baseUri]。
  Uri resolve(String path, [Map<String, String>? query]) {
    final String normalized = path.startsWith('/') ? path : '/$path';
    final Uri relative = Uri(
      path: normalized,
      queryParameters: (query == null || query.isEmpty) ? null : query,
    );
    return baseUri.resolveUri(relative);
  }

  Future<ApiResponse> get(String path, {Map<String, String>? query, String? bearerToken}) =>
      send('GET', path, query: query, bearerToken: bearerToken);

  Future<ApiResponse> post(String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      send('POST', path, query: query, jsonBody: jsonBody, bearerToken: bearerToken);

  Future<ApiResponse> put(String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      send('PUT', path, query: query, jsonBody: jsonBody, bearerToken: bearerToken);

  Future<ApiResponse> delete(String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      send('DELETE', path, query: query, jsonBody: jsonBody, bearerToken: bearerToken);

  /// 底层发送：非 2xx 抛 [ApiException]；2xx 返回原始响应。
  ///
  /// 路径统一经 [api] 拼接，即所有 `get`/`post`/`put`/`delete` 自动带 [kApiPrefix]
  /// （契约 51 条 REST 均在 `/api/v1` 下），业务域客户端只传**业务相对路径**。
  Future<ApiResponse> send(
    String method,
    String path, {
    Map<String, String>? query,
    Object? jsonBody,
    String? bearerToken,
  }) async {
    final HttpClientRequest request = await _http.openUrl(method, api(path, query));
    request.headers.set(HttpHeaders.acceptHeader, 'application/json, $kProblemMediaType');
    if (bearerToken != null && bearerToken.isNotEmpty) {
      request.headers.set(HttpHeaders.authorizationHeader, 'Bearer $bearerToken');
    }
    if (jsonBody != null) {
      request.headers.contentType = ContentType('application', 'json', charset: 'utf-8');
      request.add(utf8.encode(jsonEncode(jsonBody)));
    }

    final HttpClientResponse response = await request.close().timeout(timeout);
    final List<int> bytes = await _readAll(response).timeout(timeout);
    final ApiResponse result = ApiResponse(
      statusCode: response.statusCode,
      contentType: response.headers.contentType?.mimeType,
      body: bytes,
    );
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw ApiException(statusCode: response.statusCode, problem: ApiProblem.tryParse(bytes));
    }
    return result;
  }

  /// 释放底层连接池。
  void close() => _http.close(force: true);

  static Future<List<int>> _readAll(HttpClientResponse response) async {
    final List<int> bytes = <int>[];
    await for (final List<int> chunk in response) {
      bytes.addAll(chunk);
    }
    return bytes;
  }
}
