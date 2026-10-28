import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../api/api_contract.dart';
import '../api/api_problem.dart';
import '../api/situation_models.dart';
import 'situation_cache.dart';
import 'situation_socket.dart';

/// HTTP 单次拉取：带 `sinceRevision` 取增量，不带取全量。
typedef SituationFetch = Future<SituationEnvelope> Function({int? sinceRevision});

/// 态势**双通道**调度器（M6 CLIENT-RESILIENCE 段 B）。
///
/// 契约硬约束（`docs/api/paths/situation.yaml`）：**WS 不得替代 HTTP Poll**，
/// WS 在线时仍须保持不超过 10s 的兜底轮询——本类即把这条落地成一台小状态机：
///
/// - **同源归并**：WS 帧与 HTTP 响应都是同一个 `SituationEnvelope`，两者都喂给
///   [SituationCache]，由缓存按 `epoch` + `revision` 归并（陈旧 / 重复帧自然被丢）；
/// - **双节奏轮询**：WS 未连 → [pollInterval]（建议 2s）；WS 已连 → [wsPollFloor]
///   （10s 兜底，两条定时器互斥切换）；
/// - **重连**：普通断线按 [reconnectDelay] 退避重连；**超窗 close 4000** 判为
///   `SITUATION_REVISION_TOO_OLD`——作废本地 revision（[SituationCache.markFullResync]）
///   后**立即**不带 `sinceRevision` 重连取全量；
/// - **保活**：每 [keepAliveInterval] 上行 `{"action":"PING"}`（服务端 30s 无上行会主动断开）；
/// - **单飞**：HTTP 至多一个在途请求（两套定时器共用同一把闸）；
/// - **退出竞态**：连接在 [dispose] 之后才建好时直接关闭、不接管，且此后不再重连、不再通知。
///
/// 令牌续期（401 → 刷新 → 重试）**不在本类**：那是认证层的单飞职责，另行落地。
class SituationFeed extends ChangeNotifier {
  SituationFeed({
    required this.fetch,
    required this.connect,
    SituationCache? cache,
    this.pollInterval = const Duration(seconds: 2),
    this.wsPollFloor = const Duration(seconds: 10),
    this.keepAliveInterval = const Duration(seconds: 15),
    this.reconnectDelay = const Duration(seconds: 3),
  }) : cache = cache ?? SituationCache();

  /// 同源归并的本地缓存（两条通道共用一份视图）。
  final SituationCache cache;

  /// HTTP 拉取（生产接线：`SituationApi.getSituation`，令牌由调用方绑定）。
  final SituationFetch fetch;

  /// WS 连接器（生产接线：`connectSituationSocket`；测试注入假连接）。
  final SituationSocketConnector connect;

  /// WS 未连时的 HTTP 轮询周期（契约建议 2s）。
  final Duration pollInterval;

  /// WS 已连时的 HTTP 兜底周期（契约硬约束：不得超过 10s）。
  final Duration wsPollFloor;

  /// WS 上行 `PING` 保活周期（服务端 30s 无上行断开）。
  final Duration keepAliveInterval;

  /// 普通断线后的重连退避。
  final Duration reconnectDelay;

  Timer? _httpTimer;
  Timer? _wsTimer;
  Timer? _keepAliveTimer;
  Timer? _reconnectTimer;

  SituationSocket? _socket;
  bool _wsConnected = false;
  bool _inFlight = false;
  bool _disposed = false;

  /// 最近一次失败 / 断线的可读描述；恢复后清空。
  String? lastError;

  /// HTTP 成功拉取次数。
  int pollSuccessCount = 0;

  /// WS 采纳帧数（被缓存接收的态势帧）。
  int wsFrameCount = 0;

  /// WS 被丢弃的帧数（不可解析 / 非信封 / 归并被拒）。
  int discardedWsFrames = 0;

  /// 连接尝试次数（含失败；现场自测时作「真的在重连」的证据）。
  int connectAttempts = 0;

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
    final SituationSocket? socket = _socket;
    _socket = null;
    _wsConnected = false;
    if (socket != null) {
      unawaited(socket.close());
    }
  }

  /// 请求服务端按本地 revision 重新补齐（WS 未连时退化为「作废 revision + 立即全量拉取」）。
  void requestResync() {
    if (_disposed) {
      return;
    }
    final SituationSocket? socket = _socket;
    if (socket == null) {
      cache.markFullResync();
      unawaited(_pollTick());
      return;
    }
    try {
      socket.send(jsonEncode(<String, Object?>{
        'action': 'RESYNC',
        'sinceRevision': cache.revision,
      }));
    } catch (_) {
      // 上行失败：交给 onDone / onError 走重连，这里不另开分叉。
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
    final SituationSocket? socket = _socket;
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
      final SituationEnvelope envelope = await fetch(sinceRevision: cache.sinceRevision);
      if (_disposed) {
        return;
      }
      cache.apply(envelope);
      pollSuccessCount++;
      lastError = null;
      notifyListeners();
    } on ApiException catch (failure) {
      if (_disposed) {
        return;
      }
      if (failure.code == ApiErrorCode.situationRevisionTooOld) {
        // 过旧：作废本地 revision，下一拍自动改全量；这不算错误态。
        cache.markFullResync();
        lastError = null;
      } else {
        lastError = '拉取失败：${failure.code}';
      }
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

    final SituationSocket socket;
    try {
      socket = await connect(sinceRevision: cache.sinceRevision);
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
    final SituationSocket? socket = _socket;
    if (socket == null) {
      // onError 与 onDone 可能同帧先后到达：只认第一次收尾。
      return;
    }
    _socket = null;
    _wsConnected = false;

    if (socket.closeCode == kSituationRevisionTooOldCloseCode) {
      // 超窗（close 4000 + reason SITUATION_REVISION_TOO_OLD）：本地 revision 作废，
      // 立即不带 sinceRevision 重连取全量。
      // **先作废再退出 WS 模式**：否则紧接着由 [_exitWsMode] 触发的那一拍兜底 Poll
      // 会带着已经过期的 `sinceRevision` 出去，必然再吃一次 409。
      cache.markFullResync();
      _exitWsMode();
      lastError = null;
      _scheduleReconnect(Duration.zero);
    } else {
      _exitWsMode();
      lastError = socket.closeReason ?? reason ?? 'WS 已断开';
      _scheduleReconnect(reconnectDelay);
    }
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
    if (decoded is! Map<String, dynamic>) {
      discardedWsFrames++;
      return;
    }
    if (decoded.containsKey('action')) {
      // 控制帧（PONG / RESYNC 回应）不是态势数据，不入缓存。
      return;
    }
    try {
      final SituationEnvelope envelope = SituationEnvelope.fromJson(decoded);
      if (cache.apply(envelope)) {
        wsFrameCount++;
        lastError = null;
      } else {
        discardedWsFrames++;
      }
      notifyListeners();
    } catch (_) {
      discardedWsFrames++;
      notifyListeners();
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
    final SituationSocket? socket = _socket;
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
