import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../api/api_problem.dart';
import '../api/device_models.dart';
import 'links_socket.dart';

/// HTTP 单次拉取：`DeviceApi.getLinks`（令牌由调用方绑定）。
typedef LinksFetch = Future<List<LinkStatus>> Function();

/// 链路状态**双通道**调度器（M6 CLIENT-WINDOWS 段 64·device 域收口（二））。
///
/// 契约硬约束（`docs/api/paths/device.yaml#/wsLinks.get`）：**WS 不得替代 HTTP Poll**，
/// WS 在线时仍须保持低频兜底轮询——本类即把这条落地成一台小状态机：
///
/// - **同源归并**：WS 帧与 HTTP 响应都是同一份**完整 `LinkStatus` 列表快照**，两者都喂给
///   内部的「整体替换」——两条通道共用一套逻辑，**不合并单条增量**（决策 07 D07：不增加链路 revision）；
/// - **双节奏轮询**：WS 未连 → [pollInterval]；WS 已连 → [wsPollFloor]（低频兜底，两条定时器互斥切换）；
/// - **保活**：每 [keepAliveInterval] 上行 `{"action":"PING"}`（服务端 30s 无上行会主动断开，
///   并以 `{"action":"PONG"}` 应答）；**控制帧不是数据**，不入状态、也不计丢弃；
/// - **重连**：普通断线按 [reconnectDelay] 退避重连（链路通道**无超窗**语义，无需 RESYNC / 改全量）；
/// - **单飞**：HTTP 至多一个在途请求（两套定时器共用同一把闸）；
/// - **退出竞态**：连接在 [dispose] 之后才建好时直接关闭、不接管，且此后不再重连、不再通知。
///
/// 令牌续期（401 → 刷新 → 重试）**不在本类**：那是认证层的单飞职责，另行落地。
class LinksFeed extends ChangeNotifier {
  LinksFeed({
    required this.fetch,
    required this.connect,
    this.pollInterval = const Duration(seconds: 2),
    this.wsPollFloor = const Duration(seconds: 10),
    this.keepAliveInterval = const Duration(seconds: 15),
    this.reconnectDelay = const Duration(seconds: 3),
  });

  /// HTTP 拉取（生产接线：`DeviceApi.getLinks`，令牌由调用方绑定）。
  final LinksFetch fetch;

  /// WS 连接器（生产接线：`connectLinkSocket`；测试注入假连接）。
  final LinkSocketConnector connect;

  /// WS 未连时的 HTTP 轮询周期。
  final Duration pollInterval;

  /// WS 已连时的 HTTP 兜底周期（低频，仍由两条定时器互斥切换）。
  final Duration wsPollFloor;

  /// WS 上行 `PING` 保活周期（服务端 30s 无上行断开）。
  final Duration keepAliveInterval;

  /// 普通断线后的重连退避。
  final Duration reconnectDelay;

  Timer? _httpTimer;
  Timer? _wsTimer;
  Timer? _keepAliveTimer;
  Timer? _reconnectTimer;

  LinkSocket? _socket;
  bool _wsConnected = false;
  bool _inFlight = false;
  bool _disposed = false;

  /// 按链路类型持有的最近一次快照（两通道同源，整体替换）。
  final Map<LinkKind, LinkStatus> _links = <LinkKind, LinkStatus>{};

  /// 最近一次失败 / 断线的可读描述；恢复后清空。
  String? lastError;

  /// HTTP 成功拉取次数。
  int pollSuccessCount = 0;

  /// WS 采纳帧数（被替换进状态的链路快照）。
  int wsFrameCount = 0;

  /// WS 被丢弃的帧数（不可解析 / 非数组 / 条目解析失败）；控制帧不计入。
  int discardedWsFrames = 0;

  /// 连接尝试次数（含失败；现场自测时作「真的在重连」的证据）。
  int connectAttempts = 0;

  /// 当前链路状态（按 [LinkKind] 声明序：串口 → UWB TCP → UWB 串口，只读快照）。
  List<LinkStatus> get links {
    final List<LinkStatus> list = <LinkStatus>[];
    for (final LinkKind kind in LinkKind.values) {
      final LinkStatus? status = _links[kind];
      if (status != null) {
        list.add(status);
      }
    }
    return List<LinkStatus>.unmodifiable(list);
  }

  /// 取某条链路的状态；尚未收到该条时为 `null`。
  LinkStatus? link(LinkKind kind) => _links[kind];

  /// WS 当前是否已连。
  bool get wsConnected => _wsConnected;

  /// 是否至少有一套轮询在跑。
  bool get isPolling => _httpTimer != null || _wsTimer != null;

  /// 启动：先起 HTTP 轮询（立刻拉一拍），并发起 WS 连接。
  void start() {
    if (_disposed) {
      return;
    }
    _startHttpTimer();
    unawaited(_connectWs());
  }

  /// 停止：撤掉所有定时器并断开 WS（可再次 [start]）。
  void stop() {
    if (_disposed) {
      return;
    }
    _stopHttpTimer();
    _stopWsTimer();
    _stopKeepAlive();
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    final LinkSocket? socket = _socket;
    _socket = null;
    _wsConnected = false;
    if (socket != null) {
      unawaited(socket.close());
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _stopHttpTimer();
    _stopWsTimer();
    _stopKeepAlive();
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    final LinkSocket? socket = _socket;
    _socket = null;
    _wsConnected = false;
    if (socket != null) {
      unawaited(socket.close());
    }
    super.dispose();
  }

  // ---------------------------------------------------------------- HTTP 通道

  void _startHttpTimer() {
    if (_disposed || _httpTimer != null) {
      return;
    }
    _httpTimer = Timer.periodic(pollInterval, (Timer _) => unawaited(_pollTick()));
    unawaited(_pollTick());
  }

  void _stopHttpTimer() {
    _httpTimer?.cancel();
    _httpTimer = null;
  }

  void _startWsTimer() {
    if (_disposed || _wsTimer != null) {
      return;
    }
    _wsTimer = Timer.periodic(wsPollFloor, (Timer _) => unawaited(_pollTick()));
  }

  void _stopWsTimer() {
    _wsTimer?.cancel();
    _wsTimer = null;
  }

  Future<void> _pollTick() async {
    if (_disposed || _inFlight) {
      return;
    }
    _inFlight = true;
    try {
      final List<LinkStatus> snapshot = await fetch();
      if (_disposed) {
        return;
      }
      _replace(snapshot);
      pollSuccessCount++;
      lastError = null;
      notifyListeners();
    } on ApiException catch (failure) {
      if (_disposed) {
        return;
      }
      lastError = '拉取失败：${failure.code}';
      notifyListeners();
    } catch (error) {
      if (_disposed) {
        return;
      }
      lastError = '拉取失败：$error';
      notifyListeners();
    } finally {
      _inFlight = false;
    }
  }

  // ------------------------------------------------------------------ WS 通道

  Future<void> _connectWs() async {
    if (_disposed) {
      return;
    }
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    connectAttempts++;

    final LinkSocket socket;
    try {
      socket = await connect();
    } catch (error) {
      if (_disposed) {
        return;
      }
      lastError = 'WS 连接失败：$error';
      _scheduleReconnect(reconnectDelay);
      notifyListeners();
      return;
    }

    if (_disposed) {
      // 退出竞态：连接在 dispose 之后才回来——直接关掉，不接管、不重连。
      unawaited(socket.close());
      return;
    }

    _socket = socket;
    _wsConnected = true;
    lastError = null;
    _enterWsMode();
    socket.messages.listen(
      _onFrame,
      onError: (Object error) => _onSocketDown(reason: '$error'),
      onDone: () => _onSocketDown(reason: null),
    );
    notifyListeners();
  }

  void _scheduleReconnect(Duration delay) {
    if (_disposed) {
      return;
    }
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(delay, () => unawaited(_connectWs()));
  }

  void _onSocketDown({required String? reason}) {
    if (_disposed) {
      return;
    }
    final LinkSocket? socket = _socket;
    if (socket == null) {
      // onError 与 onDone 可能同帧先后到达：只认第一次收尾。
      return;
    }
    _socket = null;
    _wsConnected = false;
    _exitWsMode();
    lastError = socket.closeReason ?? reason ?? 'WS 已断开';
    _scheduleReconnect(reconnectDelay);
    notifyListeners();
  }

  void _onFrame(String raw) {
    if (_disposed) {
      return;
    }
    final Object? decoded;
    try {
      decoded = jsonDecode(raw);
    } catch (_) {
      discardedWsFrames++;
      return;
    }
    if (decoded is Map<String, dynamic>) {
      // 控制帧（`{"action":"PONG"}` 保活应答）不是链路数据，不入状态、也不算丢弃。
      if (decoded.containsKey('action')) {
        return;
      }
      discardedWsFrames++;
      return;
    }
    if (decoded is! List<dynamic>) {
      discardedWsFrames++;
      return;
    }
    try {
      // 整表快照：**任一条目解析失败即整帧丢弃**（不臆造缺省值，不让半截数据进状态）。
      final List<LinkStatus> snapshot = <LinkStatus>[
        for (final Object? item in decoded)
          LinkStatus.fromJson(item as Map<String, dynamic>),
      ];
      _replace(snapshot);
      wsFrameCount++;
      lastError = null;
      notifyListeners();
    } catch (_) {
      discardedWsFrames++;
      notifyListeners();
    }
  }

  /// 整体替换：两通道同源（HTTP 与 WS 都是完整列表快照），谁先到都以整表覆盖。
  void _replace(List<LinkStatus> incoming) {
    _links.clear();
    for (final LinkStatus status in incoming) {
      _links[status.kind] = status;
    }
  }

  void _enterWsMode() {
    _stopHttpTimer();
    _startWsTimer();
    _startKeepAlive();
  }

  void _exitWsMode() {
    _stopWsTimer();
    _stopKeepAlive();
    _startHttpTimer();
  }

  void _startKeepAlive() {
    if (_disposed || _keepAliveTimer != null) {
      return;
    }
    _keepAliveTimer = Timer.periodic(keepAliveInterval, (Timer _) => _sendPing());
  }

  void _stopKeepAlive() {
    _keepAliveTimer?.cancel();
    _keepAliveTimer = null;
  }

  void _sendPing() {
    final LinkSocket? socket = _socket;
    if (socket == null) {
      return;
    }
    try {
      socket.send(jsonEncode(const <String, String>{'action': 'PING'}));
    } catch (_) {
      // 上行失败交给 onDone / onError。
    }
  }
}
