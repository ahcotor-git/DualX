/// 段 67「客户端内集成 harness」的**进程内假后端**。
///
/// 只做一件事：用**真 `dart:io`**（真 `HttpServer` + 真 WebSocket 升级）承载一份按
/// **冻结契约**手工构造的应答，让真 `ApiClient` / `RefreshingApiTransport` /
/// `connectSituationSocket` / `connectLinkSocket` 走**真实 TCP 与真实 WS 握手**。
///
/// 它不是 mock 库、不拦截方法调用：客户端发的是真 HTTP 请求、真 WS 升级，断言因此落在
/// 「路径前缀 / 鉴权头 / 请求体 / 状态码 / Problem 错误码 / WS 帧形状」这些**跨层接缝**上——
/// 这正是段 59~66 各层单测（注入假传输 / 假连接）覆盖不到的地方。
///
/// 覆盖的契约面（`docs/api/paths/*`）：
/// · auth：`login` / `refresh`（免鉴权）/ `me` / `logout`
/// · exercise：`GET /exercise/current`（`running` 两态）
/// · command：`POST /command/vests/{n}/kill`、`POST /command/vests/{n}/move`
///   （`BATTLE` → 403 `COMMAND_MOVE_FORBIDDEN`，决策 07）
/// · situation：`GET /situation`（全量信封）+ `ws://…/ws/v1/situation`
/// · device：`GET /devices/links` + `ws://…/ws/v1/devices/links`
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:scds_client/shared/api/api_contract.dart';

/// 一条被观测到的请求（`HttpRequest` 在响应关闭后不可再读，故先抄下来）。
class StubRequest {
  StubRequest({
    required this.method,
    required this.uri,
    required this.bearer,
    required this.body,
  });

  final String method;
  final Uri uri;

  /// `Authorization` 头的原值（无则 `null`）。
  final String? bearer;
  final List<int> body;

  /// 请求体解析为 JSON 对象；空体或非对象时为 `null`。
  Map<String, dynamic>? get json {
    if (body.isEmpty) {
      return null;
    }
    final Object? decoded = jsonDecode(utf8.decode(body));
    return decoded is Map<String, dynamic> ? decoded : null;
  }
}

/// 进程内假后端：一个真 `HttpServer`，按冻结契约应答。
class StubBackend {
  StubBackend._(this._server) {
    _server.listen(_handle);
  }

  /// 绑定随机回环端口并开始接收请求。
  static Future<StubBackend> start({
    String battleMode = 'BATTLE',
    bool running = true,
  }) async {
    final HttpServer server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final StubBackend backend = StubBackend._(server);
    backend.battleMode = battleMode;
    backend.running = running;
    return backend;
  }

  final HttpServer _server;

  /// 客户端应指向的基址（`http://127.0.0.1:<随机端口>`）。HTTP 与 WS 共用。
  Uri get baseUri => Uri(scheme: 'http', host: '127.0.0.1', port: _server.port);

  /// 本场演习模式：`BATTLE` 时手动置位回 403（决策 07）；`TRAIN` 时放行。
  String battleMode = 'BATTLE';

  /// 当前是否有演习进行中（`GET /exercise/current` 的 `running`）。
  bool running = true;

  /// 当前有效的 accessToken（`login` 发放，`refresh` 换新）。
  String accessToken = 'access-local-1';

  /// 置 `true` 时，带鉴权的请求一律回 401 `AUTH_TOKEN_EXPIRED`——用来验证
  /// `RefreshingApiTransport`「到期 → 单飞刷新 → 重试一次」真的走了真 HTTP。
  bool expireAccessToken = false;

  /// 收到的刷新请求次数（单飞口径的证据）。
  int refreshCalls = 0;

  /// WS 升级成功次数（态势 / 链路各一条）。
  int wsUpgrades = 0;

  /// 观测到的请求（按到达序）。
  final List<StubRequest> received = <StubRequest>[];

  /// 观测到的 WS 上行文本帧（如 `{"action":"PING"}`）。
  final List<String> wsUpstream = <String>[];

  /// 关停服务端（含在途连接）。
  Future<void> stop() => _server.close(force: true);

  // ── 分派 ──────────────────────────────────────────────────────────────────

  Future<void> _handle(HttpRequest request) async {
    final String path = request.uri.path;
    if (WebSocketTransformer.isUpgradeRequest(request)) {
      wsUpgrades++;
      if (path == '$kWsPrefix/situation') {
        await _situationWs(request);
      } else if (path == '$kWsPrefix/devices/links') {
        await _linksWs(request);
      } else {
        request.response.statusCode = HttpStatus.notFound;
        await request.response.close();
      }
      return;
    }

    final List<int> body = await request.fold<List<int>>(
      <int>[],
      (List<int> acc, List<int> chunk) => acc..addAll(chunk),
    );
    received.add(StubRequest(
      method: request.method,
      uri: request.uri,
      bearer: request.headers.value(HttpHeaders.authorizationHeader),
      body: body,
    ));

    // —— auth：登录 / 刷新 / 退出，均免鉴权（契约 security: []）——
    if (request.method == 'POST' && path == '$kApiPrefix/auth/login') {
      return _json(
        request,
        200,
        _tokenPairJson(access: accessToken, refresh: 'refresh-local-1', withUser: true),
      );
    }
    if (request.method == 'POST' && path == '$kApiPrefix/auth/refresh') {
      refreshCalls++;
      accessToken = 'access-local-2';
      expireAccessToken = false;
      // 刻意不回 user：验证装饰器「变的是令牌、不是身份」的保留口径。
      return _json(
        request,
        200,
        _tokenPairJson(access: accessToken, refresh: 'refresh-local-2'),
      );
    }
    if (request.method == 'POST' && path == '$kApiPrefix/auth/logout') {
      return _json(request, 204, null);
    }

    // —— 以下端点均需鉴权（契约只有 bearerAuth 一种方案）——
    if (!_authorized(request.headers.value(HttpHeaders.authorizationHeader))) {
      return _problem(request, 401, ApiErrorCode.authTokenExpired);
    }

    if (request.method == 'GET' && path == '$kApiPrefix/auth/me') {
      return _json(request, 200, _userJson());
    }
    if (request.method == 'GET' && path == '$kApiPrefix/exercise/current') {
      return _json(request, 200, _exerciseJson());
    }
    if (request.method == 'GET' && path == '$kApiPrefix/situation') {
      return _json(request, 200, _situationEnvelopeJson());
    }
    if (request.method == 'GET' && path == '$kApiPrefix/devices/links') {
      return _json(request, 200, _linksJson());
    }

    final RegExpMatch? command = _commandPattern.firstMatch(path);
    if (request.method == 'POST' && command != null) {
      final int vestNum = int.parse(command.group(1)!);
      final String action = command.group(2)!;
      if (action == 'kill') {
        return _json(
          request,
          200,
          _commandAckJson(vestNum, 'DIE', accepted: true, downlinkHex: 'ff0100070007'),
        );
      }
      if (action == 'move') {
        if (battleMode == 'BATTLE') {
          return _problem(request, 403, ApiErrorCode.commandMoveForbidden);
        }
        // `moveVest` 不下发任何字节 → downlinkHex 恒为 null（决策 07）。
        return _json(request, 200, _commandAckJson(vestNum, 'MOVE', accepted: true));
      }
    }

    return _problem(request, 404, ApiErrorCode.routeNotFound);
  }

  /// 鉴权判据：`expireAccessToken` 一置即整表过期为「到期」，否则比对当前有效令牌。
  bool _authorized(String? bearer) =>
      !expireAccessToken && bearer == 'Bearer $accessToken';

  // ── WebSocket ─────────────────────────────────────────────────────────────

  Future<void> _situationWs(HttpRequest request) async {
    final WebSocket socket = await WebSocketTransformer.upgrade(
      request,
      protocolSelector: _selectSituationProtocol,
    );
    socket.add(jsonEncode(_situationEnvelopeJson()));
    socket.listen(
      (dynamic frame) {
        wsUpstream.add(frame is String ? frame : '');
        final Object? decoded = frame is String ? _tryDecode(frame) : null;
        if (decoded is Map<String, dynamic> && decoded['action'] == 'PING') {
          socket.add(jsonEncode(<String, Object?>{'action': 'PONG'}));
        }
      },
      onError: (Object _) {},
    );
    await socket.done;
  }

  Future<void> _linksWs(HttpRequest request) async {
    final WebSocket socket = await WebSocketTransformer.upgrade(
      request,
      protocolSelector: _selectLinksProtocol,
    );
    socket.add(jsonEncode(_linksJson()));
    socket.listen(
      (dynamic frame) => wsUpstream.add(frame is String ? frame : ''),
      onError: (Object _) {},
    );
    await socket.done;
  }

  // ── 应答构造（逐条对齐 docs/api/schemas/*）─────────────────────────────────

  Map<String, Object?> _userJson() => <String, Object?>{
        'userId': 'u-1',
        'username': 'commander',
        'displayName': '指挥员',
        'roles': <String>['DIRECTOR'],
        'permissions': <String>['exercise:start', 'command:kill', 'device:link:open'],
      };

  Map<String, Object?> _tokenPairJson({
    required String access,
    required String? refresh,
    bool withUser = false,
  }) =>
      <String, Object?>{
        'accessToken': access,
        'expiresIn': 3600,
        'refreshToken': refresh,
        if (withUser) 'user': _userJson(),
      };

  Map<String, Object?> _exerciseJson() => running
      ? <String, Object?>{
          'exerciseId': 'ex-2026-11-09',
          'planId': 'plan-7',
          'planName': '城区防守演练',
          'mapId': 'map-3',
          'battleMode': battleMode,
          'running': true,
          'startedAt': '2026-11-09T08:00:00Z',
          'elapsedSeconds': 420,
          'rosterCount': 12,
          'onlineCount': 11,
        }
      // 无演习进行中：HTTP 200 + running=false，其余业务字段一律缺席（D15-⑥）。
      : <String, Object?>{'running': false};

  Map<String, Object?> _commandAckJson(
    int vestNum,
    String command, {
    required bool accepted,
    String? downlinkHex,
  }) =>
      <String, Object?>{
        'vestNum': vestNum,
        'command': command,
        'accepted': accepted,
        if (accepted) 'queuedAt': '2026-11-09T08:07:00Z',
        'downlinkHex': downlinkHex,
      };

  Map<String, Object?> _situationEnvelopeJson() => <String, Object?>{
        'epoch': 'epoch-1',
        'revision': 42,
        'full': true,
        'running': running,
        'exerciseId': running ? 'ex-2026-11-09' : null,
        'serverTime': '2026-11-09T08:07:00Z',
        'units': running ? <Map<String, Object?>>[_unitJson()] : <Map<String, Object?>>[],
        'teamSummary': <Map<String, Object?>>[
          <String, Object?>{
            'team': 'RED',
            'total': 6,
            'live': 5,
            'dead': 1,
            'offline': 0,
            'hitsDealt': 3,
          },
        ],
      };

  Map<String, Object?> _unitJson() => <String, Object?>{
        'vestNum': 7,
        'name': '张三',
        'team': 'RED',
        'combatRole': 'SOLDIER',
        'status': 'LIVE',
        'hp': 100,
        'ammo1': 30,
        'ammo2': 30,
        'motion': 'MOVING',
        'positionSource': 'UWB',
        'signalQuality': 4,
        'revision': 42,
        'position': <String, Object?>{
          'coordinateSystem': 'INDOOR',
          'xPct': 0.42,
          'yPct': 0.58,
          'floorNum': 1,
          'posNum': 3,
        },
        'lastHeartbeatAt': '2026-11-09T08:06:58Z',
      };

  List<Map<String, Object?>> _linksJson() => <Map<String, Object?>>[
        <String, Object?>{
          'kind': 'SERIAL_VEST',
          'state': 'OPEN',
          'target': 'COM7',
          'baudRate': 115200,
          'framesTotal': 1234,
          'droppedTotal': 2,
          'crcErrorTotal': 0,
        },
        <String, Object?>{
          'kind': 'UWB_TCP_SERVER',
          'state': 'OPEN',
          'target': '0.0.0.0:12001',
          'fdCount': 2,
          'framesTotal': 88,
          'droppedTotal': 0,
          'crcErrorTotal': 0,
        },
        <String, Object?>{
          'kind': 'UWB_SERIAL',
          'state': 'CLOSED',
          'framesTotal': 0,
          'droppedTotal': 0,
          'crcErrorTotal': 0,
        },
      ];

  Map<String, Object?> _problemJson(int status, String code) => <String, Object?>{
        'type': 'about:blank',
        'title': code,
        'status': status,
        'code': code,
        'traceId': 'trace-stub-$status',
      };

  // ── 写出 ──────────────────────────────────────────────────────────────────

  Future<void> _json(
    HttpRequest request,
    int status,
    Object? body, {
    String contentType = 'application/json',
  }) async {
    // 必须显式声明 charset：HttpResponse 无 charset 时按 latin1 编码，写中文会抛
    // 「Contains invalid characters」，表现为客户端等到超时（与 api_client_test 同因）。
    final List<String> parts = contentType.split('/');
    request.response
      ..statusCode = status
      ..headers.contentType = ContentType(parts[0], parts[1], charset: 'utf-8');
    if (body != null) {
      request.response.add(utf8.encode(jsonEncode(body)));
    }
    await request.response.close();
  }

  Future<void> _problem(HttpRequest request, int status, String code) =>
      _json(request, status, _problemJson(status, code), contentType: kProblemMediaType);
}

/// 子协议协商：客户端请求了哪个就回哪个（dart:io 客户端在请求了子协议却未被选定时会握手失败）。
String? _selectSituationProtocol(List<String> protocols) =>
    protocols.contains(kSituationSubprotocol) ? kSituationSubprotocol : null;

String? _selectLinksProtocol(List<String> protocols) =>
    protocols.contains(kLinksSubprotocol) ? kLinksSubprotocol : null;

final RegExp _commandPattern = RegExp('^$kApiPrefix/command/vests/(\\d+)/([a-z-]+)\$');

Object? _tryDecode(String frame) {
  try {
    return jsonDecode(frame);
  } on FormatException {
    return null;
  }
}
