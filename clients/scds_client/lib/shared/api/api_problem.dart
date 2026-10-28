import 'dart:convert';

/// RFC 7807 字段级校验错误，仅在 `code == ApiErrorCode.validationFailed` 时出现。
class FieldError {
  const FieldError({required this.field, required this.message});

  factory FieldError.fromJson(Map<String, dynamic> json) => FieldError(
        field: json['field'] as String? ?? '',
        message: json['message'] as String? ?? '',
      );

  final String field;
  final String message;
}

/// RFC 7807 问题详情（`application/problem+json`，含 `code` / `traceId` / `errors` 扩展）。
///
/// `status` / `code` / `traceId` 是契约必填字段；缺失即视为非 Problem 正文，
/// 由 [tryParse] 返回 `null`，不在客户端臆造缺省值。
class ApiProblem {
  const ApiProblem({
    required this.type,
    required this.title,
    required this.status,
    required this.code,
    required this.detail,
    required this.instance,
    required this.traceId,
    this.errors = const <FieldError>[],
  });

  factory ApiProblem.fromJson(Map<String, dynamic> json) => ApiProblem(
        type: json['type'] as String?,
        title: json['title'] as String?,
        status: json['status'] as int,
        code: json['code'] as String,
        detail: json['detail'] as String?,
        instance: json['instance'] as String?,
        traceId: json['traceId'] as String,
        errors: (json['errors'] as List<dynamic>? ?? const <dynamic>[])
            .map((dynamic item) => FieldError.fromJson(item as Map<String, dynamic>))
            .toList(growable: false),
      );

  /// 人类可读的错误摘要（`about:blank` 时为 null）。
  final String? type;
  final String? title;
  final int status;

  /// 稳定业务错误码，见 `ApiErrorCode`。
  final String code;
  final String? detail;
  final String? instance;

  /// 服务端日志追踪号，排障时必须带上。
  final String traceId;
  final List<FieldError> errors;

  /// 是否为字段级校验失败（`VALIDATION_FAILED`）。
  bool get isValidationFailure => code == 'VALIDATION_FAILED';

  /// 按契约解析 Problem 正文；格式不合（缺必填字段或非 JSON 对象）返回 `null`。
  static ApiProblem? tryParse(List<int> body) {
    if (body.isEmpty) {
      return null;
    }
    final Object? decoded;
    try {
      decoded = jsonDecode(utf8.decode(body));
    } on FormatException {
      return null;
    }
    if (decoded is! Map<String, dynamic>) {
      return null;
    }
    try {
      return ApiProblem.fromJson(decoded);
    } on TypeError {
      return null;
    }
  }
}

/// 非 2xx 响应的统一异常：携带 HTTP 状态码与已解析的 Problem。
///
/// [problem] 为 `null` 表示响应体不符合 Problem 契约（例如代理返回的 HTML 错误页），
/// 此时只能依据 [statusCode] 处置。
class ApiException implements Exception {
  const ApiException({required this.statusCode, this.problem});

  final int statusCode;
  final ApiProblem? problem;

  /// 契约要求错误码；无 Problem 时回落到状态码描述。
  String get code => problem?.code ?? 'HTTP_$statusCode';

  /// 排障用摘要，优先带服务端 traceId。
  String get message => problem == null
      ? 'HTTP $statusCode（响应体不是 application/problem+json）'
      : 'HTTP $statusCode ${problem!.code}（traceId=${problem!.traceId}）';

  @override
  String toString() => 'ApiException: $message';
}
