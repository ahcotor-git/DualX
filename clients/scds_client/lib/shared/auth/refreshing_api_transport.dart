import 'dart:async';

import '../api/api_client.dart';
import '../api/api_contract.dart';
import '../api/api_problem.dart';
import '../api/auth_api.dart';
import '../api/auth_models.dart';
import 'session_store.dart';

/// 认证单飞传输装饰器（M6 CLIENT-RESILIENCE 段 C）。
///
/// 把「401 → 刷新令牌 → 重试一次」收在一个地方，业务域客户端（`SituationApi` 等）
/// 与页面都不必知道续期这回事：
///
/// - **触发判据**：只有 `401` + `AUTH_TOKEN_EXPIRED`（**到期**）才续期；
///   `401` + `AUTH_TOKEN_INVALID`（未知 / 篡改 / 已撤销）**不续期**——那不是时间到了，
///   而是令牌不该被信任，续期解决不了；
/// - **注入**：调用方传了 `bearerToken` 就以它为准；传 `null` 时用当前会话的
///   `accessToken`，页面因此不必自己拼令牌；
/// - **重试恰好一次**：重试回来若再 401 直接抛出，**不再刷新**（否则会自激循环）；
/// - **单飞**：并发多笔 401 只发**一次** `POST /api/v1/auth/refresh`，其余复用同一在途结果；
///   若刷新已由别的请求完成（会话引用已换），则**直接用新令牌重试**、不再发刷新——
///   契约的刷新是滚动的，重放旧 `refreshToken` 会导致该账号**全部** `refreshToken` 被撤销（见 `AuthApi.refresh`）；
/// - **刷新失败**：`401` 意味着 `refreshToken` 已不可用（会话终结）——清空本地会话并回调
///   [onLost]（由调用方清会话存储 / 回登录页），**原请求不重试**；非 401 的失败（网络、
///   503 未就绪）**不算会话终结**，只把错误抛出，会话原样保留；
/// - **刷新成功**：服务端可能不回 `user`，此时**保留原身份快照**（变的是令牌，不是身份），
///   并把新会话写回 [onRefreshed]（调用方据此持久化）。
///
/// **不含**：设备令牌（`DeviceSession`）——契约没有设备刷新端点，单兵端不使用本装饰器。
class RefreshingApiTransport implements ApiTransport {
  RefreshingApiTransport({
    required this.inner,
    AccountSession? session,
    this.onRefreshed,
    this.onLost,
  }) {
    // 在体内赋值（不用初始化形式参数）：命名参数不允许以下划线开头，
    // 而字段是私有的 [_session]，故无法写成 `this._session`。
    _session = session;
  }

  /// 内层传输（唯一真正建连接的地方）。
  ///
  /// 刷新请求**走内层**（`AuthApi(inner)`）而不是走自己，否则装饰器会拦自己的刷新调用。
  final ApiTransport inner;

  /// 续期成功后的回调：调用方据此持久化会话（如写 `SessionStore`）。
  final Future<void> Function(AccountSession session)? onRefreshed;

  /// 会话终结（刷新被 401 拒绝）后的回调：调用方据此清本地会话并回登录页。
  final Future<void> Function()? onLost;

  AccountSession? _session;
  Future<AccountSession>? _refreshInFlight;

  /// 实际发出的刷新请求次数（单飞口径的证据：并发 401 下应恒为 1）。
  int refreshCount = 0;

  /// 当前账号会话；未接入或已终结时为 `null`。
  AccountSession? get session => _session;

  /// 是否有刷新在途。
  bool get refreshing => _refreshInFlight != null;

  /// 接入 / 替换当前会话（登录成功、退出后重新登录时调用）。
  void adopt(AccountSession session) => _session = session;

  /// 丢弃当前会话（退出登录时调用）：后续请求不再自动带令牌。
  void forget() => _session = null;

  /// 手动续期入口（指挥端账号页「续期会话」按钮）。
  ///
  /// 与自动续期**共用同一把单飞闸**（见 [\_refreshOnce]）：在途时复用同一 Future，
  /// 不叠加刷新请求——契约的刷新是滚动的，重放旧 `refreshToken` 会让该账号
  /// **全部** `refreshToken` 被撤销。
  ///
  /// 会话缺失或没有 `refreshToken`（未登录 / 设备令牌形态）时按契约直接抛错、不发请求；
  /// 刷新被 401 拒绝时与自动续期同口径：清空会话并回调 [onLost]。
  Future<AccountSession> refreshNow() async {
    final AccountSession? current = _session;
    if (current == null) {
      throw StateError('未接入会话，无法续期');
    }
    if (current.refreshToken == null) {
      throw StateError('当前会话没有刷新令牌，无法续期');
    }
    return _refreshOnce(current);
  }

  @override
  Uri get baseUri => inner.baseUri;

  @override
  Uri api(String path, [Map<String, String>? query]) => inner.api(path, query);

  @override
  Uri resolve(String path, [Map<String, String>? query]) => inner.resolve(path, query);

  @override
  Future<ApiResponse> get(String path, {Map<String, String>? query, String? bearerToken}) =>
      _authorized(bearerToken,
          (String? token) => inner.get(path, query: query, bearerToken: token));

  @override
  Future<ApiResponse> post(String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      _authorized(
          bearerToken,
          (String? token) =>
              inner.post(path, query: query, jsonBody: jsonBody, bearerToken: token));

  @override
  Future<ApiResponse> put(String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      _authorized(
          bearerToken,
          (String? token) =>
              inner.put(path, query: query, jsonBody: jsonBody, bearerToken: token));

  @override
  Future<ApiResponse> delete(String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      _authorized(
          bearerToken,
          (String? token) =>
              inner.delete(path, query: query, jsonBody: jsonBody, bearerToken: token));

  @override
  Future<ApiResponse> send(String method, String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      _authorized(
          bearerToken,
          (String? token) =>
              inner.send(method, path, query: query, jsonBody: jsonBody, bearerToken: token));

  @override
  Future<ApiResponse> sendBytes(String method, String path,
          {Map<String, String>? query,
          required List<int> body,
          required String contentType,
          String? bearerToken}) =>
      _authorized(
          bearerToken,
          (String? token) => inner.sendBytes(method, path,
              query: query, body: body, contentType: contentType, bearerToken: token));

  /// 探活端点**无令牌**（`/actuator/health` 在安全链白名单），故不过 [\_authorized]。
  @override
  Future<ApiResponse> probe(String path) => inner.probe(path);

  @override
  void close() => inner.close();

  /// 带认证发送：第一次用调用方令牌（未给则用会话令牌），只有「到期 + 可续期」才刷新并**重试一次**。
  Future<ApiResponse> _authorized(
    String? explicitToken,
    Future<ApiResponse> Function(String? token) attempt,
  ) async {
    // 本次请求**实际用的**会话：续期判据要拿它比对，不能用 catch 时刻的当前会话
    // （那时可能已被别的请求换掉了）。
    final AccountSession? used = _session;
    try {
      return await attempt(explicitToken ?? used?.accessToken);
    } on ApiException catch (failure) {
      if (!_needsRefresh(failure)) {
        rethrow;
      }
      if (used?.refreshToken == null) {
        // 没有可用的 refreshToken（未登录 / 设备令牌）：无从续期，原样抛出。
        rethrow;
      }

      // 会话引用已空 = 期间别处已判定会话终结（刷新被 401 拒绝）：不再刷新，原样抛出。
      final AccountSession? current = _session;
      if (current == null) {
        rethrow;
      }

      // 会话引用已换 = 期间别处续期过：直接用新令牌重试，不再发刷新
      // （契约的刷新是滚动的，重放旧 refreshToken 会撤销该账号全部 refreshToken）。
      // 刷新失败的处置（401 → 清会话 + onLost）统一在 [\_refresh] 内，两条入口同口径。
      final AccountSession renewed =
          identical(current, used) ? await _refreshOnce(used!) : current;

      return await attempt(renewed.accessToken);
    }
  }

  /// 只有「401 + 到期」是续期信号；`AUTH_TOKEN_INVALID`（已撤销 / 篡改）续期无益。
  bool _needsRefresh(ApiException failure) =>
      failure.statusCode == 401 && failure.code == ApiErrorCode.authTokenExpired;

  /// 单飞入口：在途时复用同一 Future，不叠加刷新请求。
  Future<AccountSession> _refreshOnce(AccountSession current) {
    final Future<AccountSession>? inFlight = _refreshInFlight;
    if (inFlight != null) {
      return inFlight;
    }
    refreshCount++;
    final Future<AccountSession> pending = _refresh(current);
    _refreshInFlight = pending;
    return pending;
  }

  /// 发一次刷新并换上新会话。
  ///
  /// 刷新被 **401** 拒绝 = `refreshToken` 已不可用（会话终结）：清空会话并回调 [onLost]，
  /// 之后把错误抛出（两条入口——自动续期与 [refreshNow]——同口径）。
  /// 非 401 的失败（网络、503 未就绪）**不算会话终结**，会话原样保留。
  Future<AccountSession> _refresh(AccountSession current) async {
    try {
      final TokenPair pair = await AuthApi(inner).refresh(current.refreshToken!);
      final AccountSession merged = _merge(current, AccountSession.fromTokenPair(pair));
      _session = merged;
      await onRefreshed?.call(merged);
      return merged;
    } on ApiException catch (failure) {
      if (failure.statusCode == 401) {
        _session = null;
        await onLost?.call();
      }
      rethrow;
    } finally {
      _refreshInFlight = null;
    }
  }

  /// 续期后的会话：服务端不回 `user` 时保留原身份；不回 `refreshToken` 时保留原刷新令牌。
  AccountSession _merge(AccountSession current, AccountSession next) => AccountSession(
        accessToken: next.accessToken,
        refreshToken: next.refreshToken ?? current.refreshToken,
        expiresAt: next.expiresAt,
        user: next.user ?? current.user,
      );
}
