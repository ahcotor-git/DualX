import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/exercise_models.dart' show PageMeta;
import '../../shared/api/replay_models.dart';
import '../../shared/auth/session_store.dart';

/// 供测试与后续集成定位的控件键。
const Key kReplayListAccountKey = Key('replayList.account');
const Key kReplayListErrorKey = Key('replayList.error');
const Key kReplayListLoadingKey = Key('replayList.loading');
const Key kReplayListEmptyKey = Key('replayList.empty');
const Key kReplayListKeywordKey = Key('replayList.keyword');
const Key kReplayListFromKey = Key('replayList.from');
const Key kReplayListToKey = Key('replayList.to');
const Key kReplayListSearchButtonKey = Key('replayList.searchButton');
const Key kReplayListFormErrorKey = Key('replayList.formError');
const Key kReplayListPageKey = Key('replayList.page');
const Key kReplayListPrevButtonKey = Key('replayList.prevButton');
const Key kReplayListNextButtonKey = Key('replayList.nextButton');

/// 按复盘编号取行文本与行内「回放」入口键。
Key replayListRowKey(String replayId) => Key('replayList.row.$replayId');
Key replayListPlayKey(String replayId) => Key('replayList.play.$replayId');

/// 复盘列表端口（`GET /replays`）——按结束时间倒序。
///
/// [keyword] 按想定名模糊匹配；[from] / [to] 为结束时间区间（含端点）；
/// 分页 `page` 从 1 起、`size` 1..200（服务端默认 `page=1` / `size=20`）。
///
/// 本页**只拼非空参数**：留空即不传该键，由服务端走默认值——不臆造缺省。
typedef ReplayLister = Future<ReplayPage> Function({
  String? keyword,
  DateTime? from,
  DateTime? to,
  int? page,
  int? size,
});

/// 打开某场复盘回放器的入口；为 `null` 时不渲染行内「回放」。
typedef ReplayOpener = void Function(Replay replay);

/// 本页固定每页条数（契约 `size` 1..200）。
const int kReplayListPageSize = 20;

/// 复盘列表页（M6 CLIENT-WINDOWS 段 57·UI 接线）——replay 域只读面（列表 + 概览）的指挥端接线。
///
/// 承载 `GET /replays`；行内「回放」经 [onOpenReplay] 交给调用方 push 回放器页面
/// （未注入端口则不渲染该按钮）。
///
/// 口径：
/// - **`replayId` 就是演习编号**（停演时把本场态势落库为一条同 ID 的复盘记录），
///   不是另一套编号，展示与跳转都用它；
/// - **时间过滤是结束时间区间**（含端点），本页把 `yyyy-MM-dd` 按 **UTC 零点**解释，
///   避免本地时区把区间整体平移；非法输入在客户端拦下、不发请求（服务端另有 `VALIDATION_FAILED`）；
/// - 契约里 `durationSeconds` / `participantCount` / `hitCount` / `deadCount` 都可选——
///   缺失时如实显示破折号，**不臆造 0**。
class ReplayListPage extends StatefulWidget {
  const ReplayListPage({
    super.key,
    required this.session,
    required this.list,
    this.onOpenReplay,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  final ReplayLister list;

  /// 打开该场复盘回放器的入口；为 `null` 时不渲染行内「回放」。
  final ReplayOpener? onOpenReplay;

  @override
  State<ReplayListPage> createState() => _ReplayListPageState();
}

class _ReplayListPageState extends State<ReplayListPage> {
  final TextEditingController _keyword = TextEditingController();
  final TextEditingController _from = TextEditingController();
  final TextEditingController _to = TextEditingController();

  List<Replay>? _items;
  PageMeta? _meta;

  /// 当前页码（从 1 起）；以服务端回传的 `meta.page` 为准。
  int _page = 1;

  /// 单飞：任一请求在途时禁用按钮。
  bool _busy = false;
  String? _error;
  String? _formError;

  @override
  void initState() {
    super.initState();
    _load(1);
  }

  @override
  void dispose() {
    _keyword.dispose();
    _from.dispose();
    _to.dispose();
    super.dispose();
  }

  /// 查询：先本地解析时间区间（非法即拦下），再从第 1 页重拉。
  void _search() {
    if (_isInvalidInstant(_from.text) || _isInvalidInstant(_to.text)) {
      setState(() => _formError = '时间需为 yyyy-MM-dd 或 ISO-8601（留空表示不限）');
      return;
    }
    setState(() => _formError = null);
    _load(1);
  }

  Future<void> _load(int page) async {
    if (_busy) {
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });

    try {
      final ReplayPage result = await widget.list(
        keyword: _blankToNull(_keyword.text),
        from: _readInstant(_from.text),
        to: _readInstant(_to.text),
        page: page,
        size: kReplayListPageSize,
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _items = result.items;
        _meta = result.meta;
        _page = result.meta.page;
      });
    } on ApiException catch (failure) {
      if (!mounted) {
        return;
      }
      setState(() => _error = _describeFailure(failure));
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _error = '加载失败：$error');
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  void _goto(int delta) {
    final int target = _page + delta;
    if (target < 1) {
      return;
    }
    _load(target);
  }

  bool get _hasNext {
    final PageMeta? meta = _meta;
    return meta != null && _page * meta.size < meta.total;
  }

  Widget _replayRow(Replay replay) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: <Widget>[
            Expanded(
              child: Text(describeReplaySummary(replay), key: replayListRowKey(replay.replayId)),
            ),
            if (widget.onOpenReplay != null)
              TextButton(
                key: replayListPlayKey(replay.replayId),
                onPressed: _busy ? null : () => widget.onOpenReplay!(replay),
                child: const Text('回放'),
              ),
          ],
        ),
      );

  @override
  Widget build(BuildContext context) {
    final List<Replay>? items = _items;
    final PageMeta? meta = _meta;
    return Scaffold(
      appBar: AppBar(
        title: const Text('复盘'),
        actions: <Widget>[
          TextButton(
            key: kReplayListSearchButtonKey,
            onPressed: _busy ? null : _search,
            child: const Text('查询'),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: <Widget>[
            Text(
              _describeAccount(widget.session),
              key: kReplayListAccountKey,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 12),
            TextField(
              key: kReplayListKeywordKey,
              controller: _keyword,
              decoration: const InputDecoration(labelText: '想定名（模糊）'),
            ),
            const SizedBox(height: 8),
            Row(
              children: <Widget>[
                Expanded(
                  child: TextField(
                    key: kReplayListFromKey,
                    controller: _from,
                    decoration: const InputDecoration(labelText: '结束时间起（yyyy-MM-dd）'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    key: kReplayListToKey,
                    controller: _to,
                    decoration: const InputDecoration(labelText: '结束时间止（yyyy-MM-dd）'),
                  ),
                ),
              ],
            ),
            if (_formError != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _formError!,
                key: kReplayListFormErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kReplayListErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            const Divider(height: 32),
            if (items == null)
              const Text('加载中…', key: kReplayListLoadingKey)
            else if (items.isEmpty)
              const Text('没有符合条件的复盘', key: kReplayListEmptyKey)
            else
              ...items.map(_replayRow),
            if (meta != null) ...<Widget>[
              const Divider(height: 32),
              Row(
                children: <Widget>[
                  TextButton(
                    key: kReplayListPrevButtonKey,
                    onPressed: _busy || _page <= 1 ? null : () => _goto(-1),
                    child: const Text('上一页'),
                  ),
                  Expanded(
                    child: Text(
                      '第 $_page 页｜共 ${meta.total} 场（每页 ${meta.size}）',
                      key: kReplayListPageKey,
                      textAlign: TextAlign.center,
                    ),
                  ),
                  TextButton(
                    key: kReplayListNextButtonKey,
                    onPressed: _busy || !_hasNext ? null : () => _goto(1),
                    child: const Text('下一页'),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// 复盘一行的摘要文本（列表页与回放器页共用）。
///
/// 可选字段缺失时显示破折号：复盘是**既成事实**，读不到的计数不该被填成 0。
String describeReplaySummary(Replay replay) => '${replay.planName}'
    '｜模式 ${replay.battleMode?.wire ?? '—'}'
    '｜开始 ${replay.startedAt.toIso8601String()}'
    '｜时长 ${replay.durationSeconds == null ? '—' : '${replay.durationSeconds}s'}'
    '｜参演 ${_count(replay.participantCount)}'
    '｜命中 ${_count(replay.hitCount)}'
    '｜阵亡 ${_count(replay.deadCount)}';

String _describeAccount(AccountSession session) {
  final String name = session.user?.displayName ?? session.user?.username ?? '（未知账号）';
  final List<String> roles = session.user?.roles ?? const <String>[];
  return roles.isEmpty ? name : '$name（${roles.join(' / ')}）';
}

/// 契约里这些计数**都可选**——缺失时如实显示破折号，不臆造 0。
String _count(int? value) => value?.toString() ?? '—';

String? _blankToNull(String text) {
  final String value = text.trim();
  return value.isEmpty ? null : value;
}

bool _isInvalidInstant(String text) =>
    text.trim().isNotEmpty && _readInstant(text) == null;

/// 解析时间过滤输入：留空即 `null`（不传该键）。
///
/// 只给日期（`yyyy-MM-dd`，长度 10）时按 **UTC 零点**解释——否则本地时区会把区间整体平移一天；
/// 完整 ISO-8601 则原样交服务端（`ReplayApi` 送查询串前统一转 UTC）。
DateTime? _readInstant(String text) {
  final String value = text.trim();
  if (value.isEmpty) {
    return null;
  }
  final DateTime? parsed = DateTime.tryParse(value);
  if (parsed == null) {
    return null;
  }
  return value.length == 10
      ? DateTime.utc(parsed.year, parsed.month, parsed.day)
      : parsed;
}

String _describeFailure(ApiException failure) => switch (failure.code) {
      ApiErrorCode.replayNotFound => '复盘不存在（${ApiErrorCode.replayNotFound}）',
      ApiErrorCode.authForbidden => '当前账号无权查看复盘（${ApiErrorCode.authForbidden}）',
      ApiErrorCode.validationFailed => '查询参数不合法（${ApiErrorCode.validationFailed}）',
      ApiErrorCode.instanceNotReady =>
        '实例尚未就绪，请稍后重试（${ApiErrorCode.instanceNotReady}）',
      _ => '加载失败：${failure.code}',
    };
