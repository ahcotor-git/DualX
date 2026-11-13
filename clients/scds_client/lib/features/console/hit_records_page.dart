import 'dart:async';

import 'package:flutter/material.dart';

import '../../shared/api/api_problem.dart';
import '../../shared/api/situation_models.dart';
import '../../shared/auth/session_store.dart';
import '../../shared/state/situation_cache.dart' show kMaxCachedHits;

/// 供测试与后续集成定位的控件键。
const Key kHitRecordsAccountKey = Key('hitRecords.account');
const Key kHitRecordsCountKey = Key('hitRecords.count');
const Key kHitRecordsEmptyKey = Key('hitRecords.empty');
const Key kHitRecordsErrorKey = Key('hitRecords.error');
const Key kHitRecordsRefreshButtonKey = Key('hitRecords.refresh');

/// 按命中序号取行键（每条命中事件一行）。
Key hitRecordsRowKey(int seq) => Key('hitRecords.row.$seq');

/// 命中流水拉取端口（`GET /situation/hits`，游标增量）。
///
/// [sinceSeq] 为「只要 seq 大于它的条目」；响应 `latestSeq` 原样作为下次 [sinceSeq] 续拉，
/// 既去重又续拉（`HitEventPage`）。生产接线见 `app/console_app.dart`。
typedef HitRecordsLoader = Future<HitEventPage> Function({int? sinceSeq, int? limit});

/// 命中流水的轮询周期（指挥端滚动命中记录观感默认，可注入覆盖）。
const Duration kHitRecordsInterval = Duration(seconds: 2);

/// 命中记录页——把只读端点 `GET /api/v1/situation/hits` 接入指挥端。
///
/// 与「全体态势」页增量帧里自带的 `hits`**同源**（契约明写），本页按 [sinceSeq]
/// 游标**单独补拉**命中流水，供现场核对「谁打了谁、打哪、是否致命」。
///
/// 口径：
/// - **首拍不带游标**（从 0 起拉），此后以响应 `latestSeq` 续拉——服务端 `seq` 单调递增，
///   续拉天然去重，不重复渲染；
/// - 本地至多保留 [kMaxCachedHits] 条（对齐契约 `hitsLimit` 上限 500），超出丢弃最早的；
/// - 拉取失败只置错误文案，**不清空**已收记录（与 `PollingFeed` 同口径）；
/// - 令牌由调用方绑定（闭包读当前 `accessToken`），续期换令牌后自动生效。
class HitRecordsPage extends StatefulWidget {
  const HitRecordsPage({
    super.key,
    required this.session,
    required this.load,
    this.interval = kHitRecordsInterval,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  /// 命中流水拉取端口（生产接线：`SituationApi.listHits`）。
  final HitRecordsLoader load;

  /// 轮询周期；默认 [kHitRecordsInterval]。
  final Duration interval;

  @override
  State<HitRecordsPage> createState() => _HitRecordsPageState();
}

class _HitRecordsPageState extends State<HitRecordsPage> {
  /// 已收到的命中（按 seq 升序），最多保留 [kMaxCachedHits] 条。
  final List<HitEvent> _hits = <HitEvent>[];

  /// 续拉游标：上次响应 `latestSeq`；`null` 表示从 0 起拉。
  int? _cursor;

  String? _error;
  Timer? _timer;

  /// 单飞：上一拍未回则跳过本拍，慢网络下不叠加请求。
  bool _inFlight = false;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(widget.interval, (Timer _) => _poll());
    unawaited(_poll());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  /// 增量续拉一拍；失败只置 [_error]，**不清空**已收记录（与 `PollingFeed` 同口径）。
  Future<void> _poll() async {
    if (_inFlight) {
      return;
    }
    _inFlight = true;
    try {
      final HitEventPage page = await widget.load(sinceSeq: _cursor, limit: kMaxCachedHits);
      if (!mounted) {
        return;
      }
      setState(() {
        _hits.addAll(page.items);
        if (_hits.length > kMaxCachedHits) {
          _hits.removeRange(0, _hits.length - kMaxCachedHits);
        }
        _cursor = page.latestSeq ?? _cursor;
        _error = null;
      });
    } on ApiException catch (failure) {
      if (!mounted) {
        return;
      }
      setState(() => _error = '命中流水拉取失败：${failure.code}');
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _error = '命中流水拉取失败：$error');
    } finally {
      _inFlight = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    // 首部（账号 / 计数 / 错误行 / 分隔线）是固定少量控件，即时构建；
    // 无界增长的命中行交由 `ListView.builder` 惰性构建——屏外行不构建、不占内存，
    // 长场次（至多 kMaxCachedHits 条）滚动更平滑。空态占一行，语义与既有渲染一致。
    final List<Widget> header = <Widget>[
      Text(
        _describeAccount(widget.session),
        key: kHitRecordsAccountKey,
        style: Theme.of(context).textTheme.titleMedium,
      ),
      const SizedBox(height: 4),
      Text('共 ${_hits.length} 条', key: kHitRecordsCountKey),
      if (_error != null) ...<Widget>[
        const SizedBox(height: 8),
        Text(
          _error!,
          key: kHitRecordsErrorKey,
          style: TextStyle(color: Theme.of(context).colorScheme.error),
        ),
      ],
      const Divider(height: 32),
    ];
    final bool empty = _hits.isEmpty;
    return Scaffold(
      appBar: AppBar(
        title: const Text('命中记录'),
        actions: <Widget>[
          TextButton(
            key: kHitRecordsRefreshButtonKey,
            onPressed: _poll,
            child: const Text('刷新'),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView.builder(
          padding: const EdgeInsets.all(16),
          itemCount: header.length + (empty ? 1 : _hits.length),
          itemBuilder: (BuildContext context, int index) {
            if (index < header.length) {
              return header[index];
            }
            if (empty) {
              return const Text('暂无命中记录', key: kHitRecordsEmptyKey);
            }
            final HitEvent hit = _hits[index - header.length];
            return Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Text(
                describeHitEvent(hit),
                key: hitRecordsRowKey(hit.seq),
              ),
            );
          },
        ),
      ),
    );
  }
}

String _describeAccount(AccountSession session) {
  final String name = session.user?.displayName ?? session.user?.username ?? '（未知账号）';
  final List<String> roles = session.user?.roles ?? const <String>[];
  return roles.isEmpty ? name : '$name（${roles.join(' / ')}）';
}

/// 一条命中：序号 · 被击中者 ← 开枪者（单兵/武器·武器种类）· 命中部位 · 是否致命。
///
/// 契约里这些字段**都可选**——缺失时如实显示破折号，不臆造默认值；
/// 逐字段可核对，绝不把「未提供」渲染成「未阵亡」之类的确定结论。
String describeHitEvent(HitEvent hit) {
  final String victim = hit.hitVestNum == null ? '—' : '#${hit.hitVestNum}';
  final String shooter = hit.shooterNum == null ? '—' : '#${hit.shooterNum}';
  final String role = switch (hit.shooterIsSoldier) {
    null => '—',
    true => '单兵',
    false => '武器/载具',
  };
  final String weapon = hit.shooterWeaponKind?.wire ?? '—';
  final String part = hit.hitPart?.wire ?? '—';
  final String lethal = switch (hit.lethal) {
    null => '—',
    true => '阵亡',
    false => '未阵亡',
  };
  return '${hit.seq}｜$victim ← $shooter（$role/$weapon）｜$part｜$lethal';
}
