import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/situation_models.dart';
import 'package:scds_client/shared/state/situation_feed.dart';
import 'package:scds_client/shared/state/situation_socket.dart';

/// 段 B：态势双通道调度器（WS + 兜底 Poll）。
///
/// 全部用例跑在 `testWidgets` 的**假时钟**上：`tester.pump(d)` 按契约的真实档位
/// （2s 轮询 / 10s 兜底 / 15s 保活 / 3s 退避）精确推进，既不真等 10 秒，
/// 也不靠墙上时钟碰运气。
///
/// 判据是三条硬约束：**WS 不得替代 HTTP Poll**、**超窗（close 4000）必须改全量**、
/// **两条通道同源归并谁先到都不让旧帧覆盖新状态**。
///
/// 注意：`testWidgets` 会在**用例体返回前**校验「无待定定时器」，所以收表
/// （`feed.dispose()`）必须写在用例体内，不能交给 `addTearDown`。
void main() {
  const String epoch = 'e-1';

  UnitState unitOf(int vestNum, {VestStatus status = VestStatus.live, int revision = 1}) =>
      UnitState(vestNum: vestNum, status: status, revision: revision);

  SituationEnvelope full({required int revision, List<UnitState> units = const <UnitState>[]}) =>
      SituationEnvelope(
        epoch: epoch,
        revision: revision,
        full: true,
        running: true,
        serverTime: DateTime.utc(2026, 10, 28, 12, 0, 0),
        exerciseId: 'ex-1',
        units: units,
      );

  SituationEnvelope increment({required int revision, List<UnitState> changes = const <UnitState>[]}) =>
      SituationEnvelope(
        epoch: epoch,
        revision: revision,
        full: false,
        running: true,
        serverTime: DateTime.utc(2026, 10, 28, 12, 0, 0),
        exerciseId: 'ex-1',
        changes: changes,
      );

  String frameJson({
    required int revision,
    bool isFull = false,
    List<int> vestNums = const <int>[],
  }) =>
      jsonEncode(<String, dynamic>{
        'epoch': epoch,
        'revision': revision,
        'full': isFull,
        'running': true,
        'serverTime': '2026-10-28T12:00:00Z',
        if (isFull)
          'units': <dynamic>[
            for (final int vestNum in vestNums)
              <String, dynamic>{'vestNum': vestNum, 'status': 'LIVE', 'revision': revision},
          ]
        else
          'changes': <dynamic>[
            for (final int vestNum in vestNums)
              <String, dynamic>{'vestNum': vestNum, 'status': 'LIVE', 'revision': revision},
          ],
      });

  /// WS 永不落地的连接器：把用例按在「WS 未连」的纯 HTTP 档上。
  FakeConnector silentConnector() => FakeConnector(gate: Completer<FakeSituationSocket>());

  group('HTTP 通道（WS 未连，按 pollInterval）', () {
    testWidgets('start 立刻全量拉一拍，随后带本地 revision 续传', (WidgetTester tester) async {
      final FakeConnector connector = silentConnector();
      final List<int?> sinceRevisions = <int?>[];
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) async {
          sinceRevisions.add(sinceRevision);
          return sinceRevision == null
              ? full(revision: 10, units: <UnitState>[unitOf(14)])
              : increment(revision: sinceRevision + 1, changes: <UnitState>[unitOf(21)]);
        },
      );

      feed.start();
      await tester.pump();

      expect(sinceRevisions, <int?>[null]);
      expect(feed.pollSuccessCount, 1);
      expect(feed.cache.revision, 10);
      expect(feed.cache.units.map((UnitState u) => u.vestNum), <int>[14]);
      expect(feed.isPolling, isTrue);
      expect(feed.wsConnected, isFalse);

      await tester.pump(const Duration(seconds: 2));
      expect(sinceRevisions, <int?>[null, 10]);
      expect(feed.pollSuccessCount, 2);
      expect(feed.cache.revision, 11);
      expect(feed.cache.unit(21), isNotNull);

      feed.dispose();
    });

    testWidgets('拉取失败：置 lastError、保留陈旧缓存', (WidgetTester tester) async {
      final FakeConnector connector = silentConnector();
      int calls = 0;
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) async {
          calls++;
          if (calls == 1) {
            return full(revision: 10, units: <UnitState>[unitOf(14)]);
          }
          throw const ApiException(statusCode: 503);
        },
      );

      feed.start();
      await tester.pump();
      expect(feed.pollSuccessCount, 1);

      await tester.pump(const Duration(seconds: 2));
      expect(feed.pollSuccessCount, 1);
      expect(feed.lastError, '拉取失败：HTTP_503');
      // 失败不得抹掉上一次成功的数据（保陈旧，不空屏）。
      expect(feed.cache.revision, 10);
      expect(feed.cache.unit(14), isNotNull);

      feed.dispose();
    });

    testWidgets('409 SITUATION_REVISION_TOO_OLD：作废 revision、不算错误态、下一拍改全量',
        (WidgetTester tester) async {
      final FakeConnector connector = silentConnector();
      final List<int?> sinceRevisions = <int?>[];
      int calls = 0;
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) async {
          calls++;
          sinceRevisions.add(sinceRevision);
          if (calls == 1) {
            return full(revision: 100, units: <UnitState>[unitOf(14)]);
          }
          if (calls == 2) {
            throw const ApiException(
              statusCode: 409,
              problem: ApiProblem(
                type: 'about:blank',
                title: 'SITUATION_REVISION_TOO_OLD',
                status: 409,
                code: ApiErrorCode.situationRevisionTooOld,
                detail: null,
                instance: null,
                traceId: 'trace-1',
              ),
            );
          }
          return full(revision: 200, units: <UnitState>[unitOf(21)]);
        },
      );

      feed.start();
      await tester.pump();

      await tester.pump(const Duration(seconds: 2));
      expect(feed.lastError, isNull);
      expect(feed.cache.needsFullResync, isTrue);
      expect(feed.cache.sinceRevision, isNull);

      await tester.pump(const Duration(seconds: 2));
      expect(sinceRevisions, <int?>[null, 100, null]);
      expect(feed.cache.revision, 200);
      expect(feed.cache.needsFullResync, isFalse);

      feed.dispose();
    });

    testWidgets('单飞：在途未回时到期的那一拍不叠加请求', (WidgetTester tester) async {
      final FakeConnector connector = silentConnector();
      final Completer<SituationEnvelope> gate = Completer<SituationEnvelope>();
      int calls = 0;
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) {
          calls++;
          return gate.future;
        },
      );

      feed.start();
      await tester.pump();
      expect(calls, 1);

      await tester.pump(const Duration(seconds: 6));
      expect(calls, 1);

      gate.complete(full(revision: 5, units: <UnitState>[unitOf(14)]));
      await tester.pump();
      expect(feed.pollSuccessCount, 1);
      expect(feed.cache.revision, 5);

      feed.dispose();
    });
  });

  group('WS 上线后的双通道', () {
    testWidgets('WS 连上即切到 10s 兜底档，不再按 2s 拉', (WidgetTester tester) async {
      final FakeConnector connector = FakeConnector();
      int calls = 0;
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) async {
          calls++;
          return full(revision: calls * 10, units: <UnitState>[unitOf(14)]);
        },
      );

      feed.start();
      await tester.pump();

      expect(calls, 1);
      expect(feed.wsConnected, isTrue);
      expect(connector.attempts, 1);
      // 首个连接尚未对齐 → 不带 sinceRevision 取全量。
      expect(connector.sinceRevisions, <int?>[null]);

      await tester.pump(const Duration(seconds: 9));
      expect(calls, 1);

      await tester.pump(const Duration(seconds: 1));
      expect(calls, 2);

      feed.dispose();
    });

    testWidgets('WS 帧与 HTTP 响应同源归并：同 revision 只采纳一次', (WidgetTester tester) async {
      final FakeConnector connector = FakeConnector();
      int calls = 0;
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) async {
          calls++;
          return sinceRevision == null
              ? full(revision: 10, units: <UnitState>[unitOf(14)])
              : increment(revision: 11, changes: <UnitState>[unitOf(21)]);
        },
      );

      feed.start();
      await tester.pump();
      expect(feed.pollSuccessCount, 1);

      connector.last.emit(frameJson(revision: 11, vestNums: <int>[21]));
      await tester.pump();
      expect(feed.wsFrameCount, 1);
      expect(feed.cache.revision, 11);
      expect(feed.cache.unit(21), isNotNull);

      // 10s 后的兜底 Poll 拿到同一 revision（双通道同源必然如此）：归并层丢弃，不重复计数。
      await tester.pump(const Duration(seconds: 10));
      expect(calls, 2);
      expect(feed.pollSuccessCount, 2);
      expect(feed.cache.discardedFrames, 1);
      expect(feed.cache.revision, 11);

      feed.dispose();
    });

    testWidgets('保活：每 15s 上行一帧 PING', (WidgetTester tester) async {
      final FakeConnector connector = FakeConnector();
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) async => full(revision: 10),
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

    testWidgets('控制帧不算数据、坏帧计入丢弃、未知枚举不入缓存', (WidgetTester tester) async {
      final FakeConnector connector = FakeConnector();
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) async => full(revision: 10, units: <UnitState>[unitOf(14)]),
      );

      feed.start();
      await tester.pump();

      // 服务端的 PONG / RESYNC 回应是控制帧：既不入缓存，也不算丢弃。
      connector.last.emit('{"action":"PONG"}');
      await tester.pump();
      expect(feed.wsFrameCount, 0);
      expect(feed.discardedWsFrames, 0);
      expect(feed.cache.revision, 10);

      connector.last.emit('not-json');
      await tester.pump();
      expect(feed.discardedWsFrames, 1);

      connector.last.emit('[1,2]');
      await tester.pump();
      expect(feed.discardedWsFrames, 2);

      // 未知枚举 → 解析失败（不臆造缺省值），同样只计丢弃、不污染缓存。
      connector.last.emit(
        '{"epoch":"$epoch","revision":11,"full":false,"running":true,'
        '"serverTime":"2026-10-28T12:00:00Z",'
        '"changes":[{"vestNum":21,"status":"BOGUS","revision":11}]}',
      );
      await tester.pump();
      expect(feed.discardedWsFrames, 3);
      expect(feed.cache.revision, 10);

      feed.dispose();
    });

    testWidgets('requestResync：已连时上行 RESYNC 并带本地 revision', (WidgetTester tester) async {
      final FakeConnector connector = FakeConnector();
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) async => full(revision: 10, units: <UnitState>[unitOf(14)]),
      );

      feed.start();
      await tester.pump();
      expect(feed.cache.revision, 10);

      feed.requestResync();
      expect(
        connector.last.sent.single,
        jsonEncode(const <String, Object?>{'action': 'RESYNC', 'sinceRevision': 10}),
      );

      feed.dispose();
    });

    testWidgets('requestResync：未连时作废 revision 并立刻全量拉取', (WidgetTester tester) async {
      final FakeConnector connector = silentConnector();
      final List<int?> sinceRevisions = <int?>[];
      int calls = 0;
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) async {
          calls++;
          sinceRevisions.add(sinceRevision);
          return full(revision: calls * 10, units: <UnitState>[unitOf(14)]);
        },
      );

      feed.start();
      await tester.pump();
      expect(sinceRevisions, <int?>[null]);

      feed.requestResync();
      await tester.pump();
      expect(sinceRevisions, <int?>[null, null]);
      expect(calls, 2);

      feed.dispose();
    });
  });

  group('断线重连', () {
    testWidgets('普通断线：退回 2s 轮询档，并按 reconnectDelay 退避重连', (WidgetTester tester) async {
      final FakeConnector connector = FakeConnector();
      int calls = 0;
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) async {
          calls++;
          return full(revision: 10, units: <UnitState>[unitOf(14)]);
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
      // 本地已对齐：重连时带上 sinceRevision 续传。
      expect(connector.sinceRevisions, <int?>[null, 10]);
      expect(feed.wsConnected, isTrue);

      feed.dispose();
    });

    testWidgets('超窗 close 4000：先作废 revision（兜底 Poll 改全量）并立即重连',
        (WidgetTester tester) async {
      final FakeConnector connector = FakeConnector();
      final List<int?> httpSinceRevisions = <int?>[];
      int calls = 0;
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) async {
          calls++;
          httpSinceRevisions.add(sinceRevision);
          // 兜底 Poll 拿到的是服务端当前快照（同源，与本地过期的 revision 无关）。
          return full(revision: calls == 1 ? 10 : 500, units: <UnitState>[unitOf(14)]);
        },
      );

      feed.start();
      await tester.pump();
      expect(feed.cache.revision, 10);
      expect(feed.cache.needsFullResync, isFalse);

      await connector.last.serverClose(
        code: kSituationRevisionTooOldCloseCode,
        reason: 'SITUATION_REVISION_TOO_OLD',
      );
      expect(feed.cache.needsFullResync, isTrue);
      expect(feed.lastError, isNull);

      // 零时长推进（`pump(Duration.zero)` 会真的走一次假时钟 elapse）：
      // 兜底 Poll 与重连都在「不退避」的前提下发生——若是 3s 退避，此刻仍只会连过 1 次。
      await tester.pump(Duration.zero);
      expect(httpSinceRevisions, <int?>[null, null]);
      expect(connector.attempts, 2);
      expect(connector.sinceRevisions, <int?>[null, 500]);
      expect(feed.wsConnected, isTrue);
      expect(feed.lastError, isNull);

      feed.dispose();
    });

    testWidgets('WS 连接失败：置 lastError 并按 reconnectDelay 重连', (WidgetTester tester) async {
      final FakeConnector connector = FakeConnector(failure: StateError('refused'));
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) async => full(revision: 10, units: <UnitState>[unitOf(14)]),
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
      final FakeConnector connector = FakeConnector();
      int calls = 0;
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) async {
          calls++;
          return full(revision: 10, units: <UnitState>[unitOf(14)]);
        },
      );

      feed.start();
      await tester.pump();
      final FakeSituationSocket first = connector.last;
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
    testWidgets('dispose 后连接才落地：直接关闭、不接管、不重连、不入缓存', (WidgetTester tester) async {
      final Completer<FakeSituationSocket> gate = Completer<FakeSituationSocket>();
      final FakeConnector connector = FakeConnector(gate: gate);
      final SituationFeed feed = SituationFeed(
        connect: connector.call,
        fetch: ({int? sinceRevision}) async => full(revision: 10, units: <UnitState>[unitOf(14)]),
      );

      feed.start();
      await tester.pump();
      expect(connector.attempts, 1);

      feed.dispose();

      final FakeSituationSocket late = FakeSituationSocket();
      gate.complete(late);
      await tester.pump();

      expect(late.closeCallCount, 1);
      expect(feed.wsConnected, isFalse);
      expect(feed.isPolling, isFalse);
      expect(connector.attempts, 1);
      // 未接管：既没有上行，也没有下行入缓存（连接已被直接关掉）。
      expect(late.sent, isEmpty);
      expect(feed.wsFrameCount, 0);
      expect(feed.cache.revision, 10);
    });
  });
}

/// 假连接：手动投喂下行帧、手动收尾，并记录上行帧。
class FakeSituationSocket implements SituationSocket {
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
class FakeConnector {
  FakeConnector({this.failure, this.gate});

  /// 非空则每次连接都抛这个错。
  Object? failure;

  /// 非空则等待闸门放行（不 complete 即「永不落地」）。
  Completer<FakeSituationSocket>? gate;

  final List<FakeSituationSocket> opened = <FakeSituationSocket>[];

  /// 每次连接携带的 `sinceRevision`（`null` = 取全量）。
  final List<int?> sinceRevisions = <int?>[];

  int get attempts => sinceRevisions.length;

  FakeSituationSocket get last => opened.last;

  Future<SituationSocket> call({int? sinceRevision}) async {
    sinceRevisions.add(sinceRevision);
    final Object? failure = this.failure;
    if (failure != null) {
      throw failure;
    }
    final Completer<FakeSituationSocket>? gate = this.gate;
    final FakeSituationSocket socket = gate == null ? FakeSituationSocket() : await gate.future;
    opened.add(socket);
    return socket;
  }
}
