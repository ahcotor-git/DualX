import 'dart:async';
import 'dart:io';

import '../api/api_contract.dart';

/// 态势 WebSocket 连接端口（M6 CLIENT-RESILIENCE 段 B）。
///
/// 把 `dart:io` 的具体 `WebSocket` 收敛成一个窄接口，好处有二：
/// - 双通道调度器（[SituationFeed]）可在测试里注入**假连接**，不拉起真实网络；
/// - 关闭码 / 原因可读，调度器据此区分「超窗 close 4000」（须改全量）与普通断线（退避重连）。
///
/// 契约依据：`docs/api/paths/situation.yaml` 的 `x-websocket`——子协议 `scds.situation.v1`，
/// 上行 `{"action":"PING"|"RESYNC"}`，下行与 HTTP Poll **同构**的 `SituationEnvelope`。
abstract class SituationSocket {
  /// 服务端下行文本帧（JSON）。
  Stream<String> get messages;

  /// 上行一帧文本（保活 `PING` / 补齐 `RESYNC`）。
  void send(String text);

  /// 服务端关闭码；连接结束前为 `null`（超窗为 `4000`）。
  int? get closeCode;

  /// 服务端关闭原因（超窗为 `SITUATION_REVISION_TOO_OLD`）。
  String? get closeReason;

  /// 主动关闭。
  Future<void> close([int? code, String? reason]);
}

/// 造一条态势 WS 连接；`sinceRevision` 为 `null` 即取全量。
typedef SituationSocketConnector = Future<SituationSocket> Function({int? sinceRevision});

/// 超窗关闭码（契约：`sinceRevision` 超出服务端保留窗口时服务端以 close 4000 拒绝）。
const int kSituationRevisionTooOldCloseCode = 4000;

/// `dart:io` 生产实现：`ws(s)://{host}:11001/ws/v1/situation`，子协议 [kSituationSubprotocol]。
///
/// `http` 映射到 `ws`、`https` 映射到 `wss`（私有化单机通常是前者）。
/// 令牌按 `Authorization: Bearer` 提交——契约该通道**只认账号 / 设备令牌**（只有 `bearerAuth` 一种方案）。
Future<SituationSocket> connectSituationSocket({
  required Uri baseUri,
  required String accessToken,
  int? sinceRevision,
}) async {
  final Uri target = baseUri.replace(
    scheme: baseUri.scheme == 'https' ? 'wss' : 'ws',
    path: '$kWsPrefix/situation',
    queryParameters: sinceRevision == null
        ? null
        : <String, String>{'sinceRevision': '$sinceRevision'},
  );
  final WebSocket socket = await WebSocket.connect(
    target.toString(),
    protocols: <String>[kSituationSubprotocol],
    headers: <String, dynamic>{HttpHeaders.authorizationHeader: 'Bearer $accessToken'},
  );
  return IoWebSocketSituationSocket(socket);
}

/// [SituationSocket] 的 `dart:io` 适配器。
class IoWebSocketSituationSocket implements SituationSocket {
  IoWebSocketSituationSocket(this._socket) {
    // `WebSocket` 是 `Stream<dynamic>`，按契约窄化为文本帧后再转发。
    _socket.cast<String>().listen(
      _controller.add,
      onError: _controller.addError,
      onDone: _controller.close,
    );
  }

  final WebSocket _socket;
  final StreamController<String> _controller = StreamController<String>();

  @override
  Stream<String> get messages => _controller.stream;

  @override
  void send(String text) => _socket.add(text);

  @override
  int? get closeCode => _socket.closeCode;

  @override
  String? get closeReason => _socket.closeReason;

  @override
  Future<void> close([int? code, String? reason]) => _socket.close(code, reason);
}
