import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/device_models.dart';
import 'package:scds_client/shared/state/links_feed.dart';
import 'package:scds_client/shared/state/links_socket.dart';

/// 段 64：链路状态双通道调度器（WS + 兜底 Poll）。
///
/// 全部用例跑在 `testWidgets` 的**假时钟**上：`tester.pump(d)` 按真实档位
/// （2s 轮询 / 10s 兜底 / 15s 保活 / 3s 退避）精确推进。
///
/// 判据是契约的两条硬约束：**WS 不得替代 HTTP Poll**、**两条通道载荷同构、
/// 共用一套「整表替换」**（链路通道**无** `epoch`/`revision`/`RESYNC`/超窗 close）。
///
/// 注意：`testWidgets` 会在**用例体返回前**校验「无待定定时器」，所以收表
/// （`feed.dispose()`）必须写在用例体内，不能交给 `addTearDown`。
void main() {
  /// 一份「三种链路恒定各一条」的完整快照。
  List<LinkStatus> snapshot({
    LinkState serial = LinkState.closed,
    LinkState uwbTcp = LinkState.open,
  }) =>
      <LinkStatus>[
        LinkStatus(kind: LinkKind.serialVest, state: serial, target: 'COM7', baudRate: 115200),
        LinkStatus(kind: LinkKind.uwbTcpServer, state: uwbTcp, target: '0.0.0.0:12001', fdCount: 1),
        const LinkStatus(kind: LinkKind.uwbSerial, state: LinkState.closed),
      ];

  /// 与 `GET /devices/links` 同构的 WS 下行帧（完整列表快照）。
  String linksJson({String serialState = 'CLOSED'}) => jsonEncode(<dynamic>[
        <String, dynamic>{'kind': 'SERIAL_VEST', 'state': serialState, 'target': 'COM7'},
        <String, dynamic>{'kind': 'UWB_TCP_SERVER', 'state': 'OPEN', 'target': '0.0.0.0:12001'},
        <String, dynamic>{'kind': 'UWB_SERIAL', 'state': 'CLOSED'},
      ]);

  /// WS 永不落地的连接器：把用例按在「WS 未连」的纯 HTTP 档上。
  FakeLinkConnector silentConnector() => FakeLinkConnector(gate: Completer<FakeLinkSocket>());

  group('HTTP 通道（WS 未连，按 pollInterval）', () {
    testWidgets('start 立刻拉一拍，随后按 2s 轮询、整表替换', (WidgetTester tester) async {
      final FakeLinkConnector connector = silentConnector();
      int calls = 0;
      final LinksFeed feed = LinksFeed(
        connect: connector.call,
        fetch: () async {
          calls++;
          return snapshot(serial: calls == 1 ? LinkState.closed : LinkState.open);
        },
      );

      feed.start();
      await tester.pump();

      expect(calls, 1);
      expect(feed.pollSuccessCount, 1);
      expect(feed.links.length, 3);
      expect(feed.link(LinkKind.serialVest)!.state, LinkState.closed);
      expect(feed.link(LinkKind.uwbTcpServer)!.state, LinkState.open);
      expect(feed.isPolling, isTrue);
      expect(feed.wsConnected, isFalse);

      await tester.pump(const Duration(seconds: 2));
      expect(calls, 2);
      expect(feed.pollSuccessCount, 2);
      expect(feed.link(LinkKind.serialVest)!.state, LinkState.open);

      feed.dispose();
    });

    testWidgets('拉取失败：置 lastError、保留陈旧快照', (WidgetTester tester) async {
      final FakeLinkConnector connector = silentConnector();
      int calls = 0;
      final LinksFeed feed = LinksFeed(
        connect: connector.call,
        fetch: () async {
          calls++;
          if (calls == 1) {
            return snapshot();
          }
          throw const ApiException(statusCode: 503);
        },
      );

      feed.start();
      await tester.pump();
      expect(feed.pollSuccessCount, 1);
      expect(feed.link(LinkKind.uwbTcpServer), isNotNull);

      await tester.pump(const Duration(seconds: 2));
      expect(feed.pollSuccessCount, 1);
      expect(feed.lastError, '拉取失败：HTTP_503');
      // 失败不得抹掉上一次成功的快照（保陈旧，不空屏）。
      expect(feed.links.length, 3);
      expect(feed.link(LinkKind.uwbTcpServer)!.state, LinkState.open);

      feed.dispose();
    });

    testWidgets('单飞：在途未回时到期的那一拍不叠加请求', (WidgetTester tester) async {
      final FakeLinkConnector connector = silentConnector();
      final Completer<List<LinkStatus>> gate = Completer<List<LinkStatus>>();
      int calls = 0;
      final LinksFeed feed = LinksFeed(
        connect: connector.call,
        fetch: () {
          calls++;
          return gate.future;
        },
      );

      feed.start();
      await tester.pump();
      expect(calls, 1);

      await tester.pump(const Duration(seconds: 6));
      expect(calls, 1);

      gate.complete(snapshot());
      await tester.pump();
      expect(feed.pollSuccessCount, 1);
      expect(feed.link(LinkKind.serialVest), isNotNull);

      feed.dispose();
    });
  });

  group('WS 上线后的双通道', () {
    testWidgets('WS 连上即切到 10s 兜底档，不再按 2s 拉', (WidgetTester tester) async {
      final FakeLinkConnector connector = FakeLinkConnector();
      int calls = 0;
      final LinksFeed feed = LinksFeed(
        connect: connector.call,
        fetch: () async {
          calls++;
          return snapshot();
        },
      );

      feed.start();
      await tester.pump();

      expect(calls, 1);
      expect(feed.wsConnected, isTrue);
      expect(connector.attempts, 1);

      await tester.pump(const Duration(seconds: 9));
      expect(calls, 1);

      await tester.pump(const Duration(seconds: 1));
      expect(calls, 2);

      feed.dispose();
    });

    testWidgets('WS 帧与 HTTP 同源：整表快照替换（谁后到以谁为准）', (WidgetTester tester) async {
      final FakeLinkConnector connector = FakeLinkConnector();
      int calls = 0;
      final LinksFeed feed = LinksFeed(
        connect: connector.call,
        fetch: () async {
          calls++;
          return snapshot(serial: LinkState.closed);
        },
      );

      feed.start();
      await tester.pump();
      expect(feed.pollSuccessCount, 1);
      expect(feed.link(LinkKind.serialVest)!.state, LinkState.closed);

      connector.last.emit(linksJson(serialState: 'OPEN'));
      await tester.pump();
      expect(feed.wsFrameCount, 1);
      expect(feed.link(LinkKind.serialVest)!.state, LinkState.open);
      expect(feed.lastError, isNull);

      // 10s 后的兜底 Poll 拿到的是服务端当前快照：同构、共用一套整体替换，以最后到达者为准。
      await tester.pump(const Duration(seconds: 10));
      expect(calls, 2);
      expect(feed.pollSuccessCount, 2);
      expect(feed.links.length, 3);
      expect(feed.link(LinkKind.serialVest)!.state, LinkState.closed);

      feed.dispose();
    });

    testWidgets('保活：每 15s 上行一帧 PING', (WidgetTester tester) async {
      final FakeLinkConnector connector = FakeLinkConnector();
      final LinksFeed feed = LinksFeed(
        connect: connector.call,
        fetch: () async => snapshot(),
      );

      feed.start();
      await tester.pump();
      expect(connector.last.sent, isEmpty);

      await tester.pump(const Duration(seconds: 15));
      expect(connector.last.sent, <String>[jsonEncode(const <String, String>{'action': 'PING'})]);

      await tester.pump(const Duration(seconds: 15));
      expect(connector.last.sent.length, 2);

      feed.dispose();
    });

    testWidgets('控制帧 PONG 不入状态也不计丢弃、坏帧 / 对象 / 未知枚举计丢弃且不污染状态',
        (WidgetTester tester) async {
      final FakeLinkConnector connector = FakeLinkConnector();
      final LinksFeed feed = LinksFeed(
        connect: connector.call,
        fetch: () async => snapshot(),
      );

      feed.start();
      await tester.pump();
      expect(feed.link(LinkKind.serialVest)!.state, LinkState.closed);

      // 服务端的保活应答是控制帧：既不入状态，也不算丢弃。
      connector.last.emit('{"action":"PONG"}');
      await tester.pump();
      expect(feed.wsFrameCount, 0);
      expect(feed.discardedWsFrames, 0);

      connector.last.emit('not-json');
      await tester.pump();
      expect(feed.discardedWsFrames, 1);

      // 对象但无 `action`：链路通道下行恒为数组，视为坏帧。
      connector.last.emit('{"kind":"SERIAL_VEST"}');
      await tester.pump();
      expect(feed.discardedWsFrames, 2);

      // 数组但条目不是对象：解析失败。
      connector.last.emit('[1,2]');
      await tester.pump();
      expect(feed.discardedWsFrames, 3);

      // 未知枚举字面量 → 整帧丢弃（不臆造缺省值），也不让半截数据进状态。
      connector.last.emit('[{"kind":"BOGUS","state":"OPEN"}]');
      await tester.pump();
      expect(feed.discardedWsFrames, 4);
      expect(feed.link(LinkKind.serialVest)!.state, LinkState.closed);

      feed.dispose();
    });
  });

  group('断线重连', () {
    testWidgets('普通断线：退回 2s 轮询档，并按 reconnectDelay 退避重连', (WidgetTester tester) async {
      final FakeLinkConnector connector = FakeLinkConnector();
      int calls = 0;
      final LinksFeed feed = LinksFeed(
        connect: connector.call,
        fetch: () async {
          calls++;
          return snapshot();
        },
      );

      feed.start();
      await tester.pump();
      expect(feed.wsConnected, isTrue);
      expect(calls, 1);

      await connector.last.serverClose(reason: 'server going away');
      expect(feed.wsConnected, isFalse);
      expect(feed.lastError, 'server going away');

      await tester.pump();
      expect(calls, 2);

      await tester.pump(const Duration(seconds: 2));
      expect(calls, 3);

      await tester.pump(const Duration(seconds: 3));
      expect(connector.attempts, 2);
      expect(feed.wsConnected, isTrue);

      feed.dispose();
    });

    testWidgets('WS 连接失败：置 lastError 并按 reconnectDelay 重连', (WidgetTester tester) async {
      final FakeLinkConnector connector = FakeLinkConnector(failure: StateError('refused'));
      final LinksFeed feed = LinksFeed(
        connect: connector.call,
        fetch: () async => snapshot(),
      );

      feed.start();
      await tester.pump();
      expect(connector.attempts, 1);
      expect(feed.wsConnected, isFalse);
      expect(feed.lastError, contains('WS 连接失败'));

      await tester.pump(const Duration(seconds: 3));
      expect(connector.attempts, 2);

      connector.failure = null;
      await tester.pump(const Duration(seconds: 3));
      expect(connector.attempts, 3);
      expect(feed.wsConnected, isTrue);
      expect(feed.lastError, isNull);

      feed.dispose();
    });

    testWidgets('stop 撤表断连，再次 start 可恢复', (WidgetTester tester) async {
      final FakeLinkConnector connector = FakeLinkConnector();
      int calls = 0;
      final LinksFeed feed = LinksFeed(
        connect: connector.call,
        fetch: () async {
          calls++;
          return snapshot();
        },
      );

      feed.start();
      await tester.pump();
      final FakeLinkSocket first = connector.last;
      expect(calls, 1);

      feed.stop();
      expect(feed.wsConnected, isFalse);
      expect(feed.isPolling, isFalse);
      expect(first.closeCallCount, 1);

      await tester.pump(const Duration(seconds: 30));
      expect(calls, 1);
      expect(connector.attempts, 1);

      feed.start();
      await tester.pump();
      expect(calls, 2);
      expect(connector.attempts, 2);
      expect(feed.wsConnected, isTrue);

      feed.dispose();
    });
  });

  group('退出竞态', () {
    testWidgets('dispose 后连接才落地：直接关闭、不接管、不重连、不入状态', (WidgetTester tester) async {
      final Completer<FakeLinkSocket> gate = Completer<FakeLinkSocket>();
      final FakeLinkConnector connector = FakeLinkConnector(gate: gate);
      final LinksFeed feed = LinksFeed(
        connect: connector.call,
        fetch: () async => snapshot(),
      );

      feed.start();
      await tester.pump();
      expect(connector.attempts, 1);
      expect(feed.link(LinkKind.serialVest), isNotNull);

      feed.dispose();

      final FakeLinkSocket late = FakeLinkSocket();
      gate.complete(late);
      await tester.pump();

      expect(late.closeCallCount, 1);
      expect(feed.wsConnected, isFalse);
      expect(feed.isPolling, isFalse);
      expect(connector.attempts, 1);
      // 未接管：既没有上行，也没有下行入状态（连接已被直接关掉）。
      expect(late.sent, isEmpty);
      expect(feed.wsFrameCount, 0);
    });
  });
}

/// 假连接：手动投喂下行帧、手动收尾，并记录上行帧。
class FakeLinkSocket implements LinkSocket {
  final StreamController<String> _controller = StreamController<String>();

  /// 客户端上行的文本帧。
  final List<String> sent = <String>[];

  /// 被客户端主动关闭的次数。
  int closeCallCount = 0;

  int? _closeCode;
  String? _closeReason;

  @override
  Stream<String> get messages => _controller.stream;

  @override
  void send(String text) => sent.add(text);

  @override
  int? get closeCode => _closeCode;

  @override
  String? get closeReason => _closeReason;

  /// 客户端主动关闭：不改写服务端给出的关闭码（与 `dart:io` 适配器一致）。
  @override
  Future<void> close([int? code, String? reason]) async {
    closeCallCount++;
    if (!_controller.isClosed) {
      await _controller.close();
    }
  }

  /// 投喂一帧下行文本。
  void emit(String raw) => _controller.add(raw);

  /// 服务端收尾（落关闭码 / 原因后关流），触发调度器的 `onDone`。
  Future<void> serverClose({int? code, String? reason}) async {
    _closeCode = code;
    _closeReason = reason;
    if (!_controller.isClosed) {
      await _controller.close();
    }
  }
}

/// 假连接器：可注入失败，或在闸门后挂起（用于复现「连接落地晚于 dispose」）。
class FakeLinkConnector {
  FakeLinkConnector({this.failure, this.gate});

  /// 非空则每次连接都抛这个错。
  Object? failure;

  /// 非空则等待闸门放行（不 complete 即「永不落地」）。
  Completer<FakeLinkSocket>? gate;

  final List<FakeLinkSocket> opened = <FakeLinkSocket>[];

  int _attempts = 0;

  /// 连接尝试次数（含失败）。
  int get attempts => _attempts;

  FakeLinkSocket get last => opened.last;

  Future<LinkSocket> call() async {
    _attempts++;
    final Object? failure = this.failure;
    if (failure != null) {
      throw failure;
    }
    final Completer<FakeLinkSocket>? gate = this.gate;
    final FakeLinkSocket socket = gate == null ? FakeLinkSocket() : await gate.future;
    opened.add(socket);
    return socket;
  }
}
