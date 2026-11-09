import 'dart:async';
import 'dart:io';

import '../api/api_contract.dart';

/// 链路状态 WebSocket 连接端口（M6 CLIENT-WINDOWS 段 64·device 域收口（二））。
///
/// 与 [SituationSocket](../situation_socket.dart) 同构：把 `dart:io` 的具体 `WebSocket`
/// 收敛成一个窄接口，好处有二：
/// - 双通道调度器（`LinksFeed`）可在测试里注入**假连接**，不拉起真实网络；
/// - 关闭码 / 原因可读，调度器据此决定退避重连（链路通道**无超窗**语义）。
///
/// 契约依据：`docs/api/paths/device.yaml#/wsLinks.get` 的 `x-websocket`——子协议
/// `scds.links.v1`，上行仅 `{"action":"PING"}`，下行与 `GET /api/v1/devices/links`
/// **同构**的**完整 `LinkStatus` 列表快照**（不是「只含变化那一条」的局部对象）。
///
/// **与态势通道的差别**（决策 07 D07「不增加链路 revision」）：链路通道**无 `epoch` /
/// `revision` / `RESYNC` / 超窗 close 4000**——每一帧都是整表快照，客户端按「整体替换」
/// 处理即两通道共用一套逻辑。
abstract class LinkSocket {
  /// 服务端下行文本帧（JSON：`LinkStatus` 数组）。
  Stream<String> get messages;

  /// 上行一帧文本（保活 `PING`）。
  void send(String text);

  /// 服务端关闭码；连接结束前为 `null`。
  int? get closeCode;

  /// 服务端关闭原因。
  String? get closeReason;

  /// 主动关闭。
  Future<void> close([int? code, String? reason]);
}

/// 造一条链路 WS 连接（**无续传参数**：链路通道每帧恒为全量快照）。
typedef LinkSocketConnector = Future<LinkSocket> Function();

/// `dart:io` 生产实现：`ws(s)://{host}:11001/ws/v1/devices/links`，子协议 [kLinksSubprotocol]。
///
/// `http` 映射到 `ws`、`https` 映射到 `wss`（私有化单机通常是前者）。
/// 令牌按 `Authorization: Bearer` 提交——契约该通道**只认账号令牌**（只有 `bearerAuth` 一种方案）。
Future<LinkSocket> connectLinkSocket({
  required Uri baseUri,
  required String accessToken,
}) async {
  final Uri target = baseUri.replace(
    scheme: baseUri.scheme == 'https' ? 'wss' : 'ws',
    path: '$kWsPrefix/devices/links',
  );
  final WebSocket socket = await WebSocket.connect(
    target.toString(),
    protocols: <String>[kLinksSubprotocol],
    headers: <String, dynamic>{HttpHeaders.authorizationHeader: 'Bearer $accessToken'},
  );
  return IoWebSocketLinkSocket(socket);
}

/// [LinkSocket] 的 `dart:io` 适配器。
class IoWebSocketLinkSocket implements LinkSocket {
  IoWebSocketLinkSocket(this._socket) {
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
