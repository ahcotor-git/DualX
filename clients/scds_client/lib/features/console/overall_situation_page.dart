import 'package:flutter/material.dart';

import '../../shared/api/api_problem.dart';
import '../../shared/api/command_models.dart';
import '../../shared/api/situation_models.dart';
import '../../shared/auth/session_store.dart';
import '../../shared/state/situation_cache.dart';
import '../../shared/state/situation_feed.dart';
import '../../shared/state/situation_socket.dart';
import 'command_page.dart' show describeCommandAck;

/// 供测试与后续集成定位的控件键。
const Key kOverallSituationAccountKey = Key('overallSituation.account');
const Key kOverallSituationChannelKey = Key('overallSituation.channel');
const Key kOverallSituationCountsKey = Key('overallSituation.counts');
const Key kOverallSituationErrorKey = Key('overallSituation.error');
const Key kOverallSituationResyncButtonKey = Key('overallSituation.resync');
const Key kOverallSituationWaitingKey = Key('overallSituation.waiting');
const Key kOverallSituationEmptyKey = Key('overallSituation.empty');

/// 段 47 新增：全体充弹（广播）入口与对话框控件键。
const Key kOverallSituationReloadAllButtonKey = Key('overallSituation.reloadAll');
const Key kReloadAllAmmoFieldKey = Key('reloadAll.ammoField');
const Key kReloadAllWeaponKey = Key('reloadAll.weapon');
const Key kReloadAllErrorKey = Key('reloadAll.error');
const Key kReloadAllConfirmKey = Key('reloadAll.confirm');
const Key kReloadAllCancelKey = Key('reloadAll.cancel');

/// 按马甲编号取行键（每个参演单元一行）。
Key overallSituationUnitRowKey(int vestNum) => Key('overallSituation.unit.$vestNum');

/// 段 47 新增：按马甲编号取「可点行」键（仅当注入了 [OverallSituationPage.onCommand]）。
Key overallSituationUnitTapKey(int vestNum) => Key('overallSituation.unitTap.$vestNum');

/// 全体态势页（M6 CLIENT-RESILIENCE 段 D 后半）——指挥端接 [SituationFeed] 双通道。
///
/// 与「队伍汇总」页（`TeamSummaryPage`）分工不同：队伍汇总只读
/// `GET /situation/teams/summary` 的**聚合计数**（必须直读——增量帧的 `teamSummary`
/// 恒为空，混用两个来源会把汇总刷没）；本页读**统一态势流**——由 [SituationFeed]
/// 把 WS 主通道与 HTTP 兜底轮询（WS 在线仍须 ≤10s）**同源归并**进 [SituationCache]，
/// 逐单元展示。
///
/// 页面**自建并持有** [SituationFeed]（与单兵端自身态势页持有 `PollingFeed` 同构）：
/// `initState` 启动、`dispose` 释放——缓存随页面生灭，不跨会话泄漏。
class OverallSituationPage extends StatefulWidget {
  const OverallSituationPage({
    super.key,
    required this.session,
    required this.fetch,
    required this.connect,
    this.onCommand,
    this.reloadAll,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  /// HTTP 兜底轮询（生产接线见 `app/console_app.dart`：`SituationApi.getSituation`）。
  final SituationFetch fetch;

  /// WS 主通道连接器（生产接线：`connectSituationSocket`，令牌由调用方绑定）。
  final SituationSocketConnector connect;

  /// 段 47 新增：点某个单元行 → 打开该马甲的指挥指令页；为 `null` 时行不可点
  /// （既有只读用例不注入本端口，渲染与行为**保持不变**）。
  final void Function(int vestNum)? onCommand;

  /// 段 47 新增：全体充弹（广播）端口；为 `null` 时 AppBar 不渲染该入口。
  final Future<CommandAck> Function(ReloadRequest request)? reloadAll;

  @override
  State<OverallSituationPage> createState() => _OverallSituationPageState();
}

class _OverallSituationPageState extends State<OverallSituationPage> {
  late final SituationFeed _feed;

  @override
  void initState() {
    super.initState();
    _feed = SituationFeed(fetch: widget.fetch, connect: widget.connect)..start();
  }

  @override
  void dispose() {
    _feed.dispose();
    super.dispose();
  }

  /// 段 47：全体充弹（广播）——对话框只负责收参，下发与反馈留在本页。
  ///
  /// 广播回执 `vestNum` 固定为 0，[describeCommandAck] 会显示为「全体」；
  /// `accepted=false` 时提示「未下发」而**不是**报错（HTTP 200 只表示已受理）。
  Future<void> _openReloadAll() async {
    final ReloadRequest? request = await showDialog<ReloadRequest>(
      context: context,
      builder: (BuildContext dialogContext) => const _ReloadAllDialog(),
    );
    if (request == null || !mounted) {
      return;
    }

    final ScaffoldMessengerState messenger = ScaffoldMessenger.of(context);
    try {
      final CommandAck ack = await widget.reloadAll!(request);
      if (!mounted) {
        return;
      }
      messenger.showSnackBar(SnackBar(content: Text(describeCommandAck(ack))));
    } on ApiException catch (failure) {
      if (!mounted) {
        return;
      }
      messenger.showSnackBar(SnackBar(content: Text('下发失败：${failure.code}')));
    } catch (error) {
      if (!mounted) {
        return;
      }
      messenger.showSnackBar(SnackBar(content: Text('下发失败：$error')));
    }
  }

  /// 段 47：单元行——注入 [OverallSituationPage.onCommand] 时可点（进单兵指令页），
  /// 未注入时渲染与行为**完全不变**（既有只读用例仍按 `overallSituationUnitRowKey` 断言）。
  Widget _unitRow(UnitState unit) {
    final Widget label = Text(
      _describeUnit(unit),
      key: overallSituationUnitRowKey(unit.vestNum),
    );
    final void Function(int)? onCommand = widget.onCommand;
    if (onCommand == null) {
      return Padding(padding: const EdgeInsets.symmetric(vertical: 4), child: label);
    }
    return InkWell(
      key: overallSituationUnitTapKey(unit.vestNum),
      onTap: () => onCommand(unit.vestNum),
      child: Padding(padding: const EdgeInsets.symmetric(vertical: 12), child: label),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('全体态势'),
        actions: <Widget>[
          if (widget.reloadAll != null)
            TextButton(
              key: kOverallSituationReloadAllButtonKey,
              onPressed: _openReloadAll,
              child: const Text('全体充弹'),
            ),
          TextButton(
            key: kOverallSituationResyncButtonKey,
            onPressed: _feed.requestResync,
            child: const Text('重新同步'),
          ),
        ],
      ),
      body: SafeArea(
        child: ListenableBuilder(
          listenable: _feed,
          builder: (BuildContext context, Widget? child) {
            final SituationCache cache = _feed.cache;
            final List<UnitState> units = cache.units;
            // 「首帧」指任一通道**被采纳**过：在此之前空列表不代表「没有单元」。
            final bool gotFirstFrame = _feed.pollSuccessCount > 0 || _feed.wsFrameCount > 0;
            return ListView(
              padding: const EdgeInsets.all(16),
              children: <Widget>[
                Text(
                  _describeAccount(widget.session),
                  key: kOverallSituationAccountKey,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 4),
                Text(_describeChannel(_feed.wsConnected), key: kOverallSituationChannelKey),
                const SizedBox(height: 4),
                Text(_describeCounts(units.length, _feed), key: kOverallSituationCountsKey),
                if (_feed.lastError != null) ...<Widget>[
                  const SizedBox(height: 8),
                  Text(
                    _feed.lastError!,
                    key: kOverallSituationErrorKey,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ],
                const Divider(height: 32),
                if (!gotFirstFrame && _feed.lastError == null)
                  const Text('等待首帧…', key: kOverallSituationWaitingKey)
                else if (units.isEmpty)
                  const Text('本场没有参演单元', key: kOverallSituationEmptyKey)
                else
                  ...units.map((UnitState unit) => _unitRow(unit)),
              ],
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

/// 通道状态：WS 主通道是否已连；未连时是 2s 轮询档，已连时是 ≤10s 兜底档。
String _describeChannel(bool wsConnected) =>
    wsConnected ? '实时（WS 已连，兜底轮询 ≤10s）' : '轮询中（WS 未连，每 2s）';

String _describeCounts(int unitCount, SituationFeed feed) =>
    '单元 $unitCount｜拉取 ${feed.pollSuccessCount} 次｜WS 帧 ${feed.wsFrameCount}';

/// 单元行只做呈现：状态取契约字面量，HP 缺失时如实显示破折号。
String _describeUnit(UnitState unit) => '#${unit.vestNum} ${unit.status.wire}｜HP ${unit.hp ?? '—'}';

/// 段 47：全体充弹（广播）参数对话框——**只收参**，下发与反馈留在「全体态势」页。
///
/// 校验通过才 `pop(ReloadRequest)`，非法输入行内显示 [kReloadAllErrorKey] 且不关闭对话框。
class _ReloadAllDialog extends StatefulWidget {
  const _ReloadAllDialog();

  @override
  State<_ReloadAllDialog> createState() => _ReloadAllDialogState();
}

class _ReloadAllDialogState extends State<_ReloadAllDialog> {
  final TextEditingController _ammo = TextEditingController();
  WeaponSlot _weapon = WeaponSlot.primary;
  String? _error;

  @override
  void dispose() {
    _ammo.dispose();
    super.dispose();
  }

  void _confirm() {
    final int? ammo = int.tryParse(_ammo.text.trim());
    if (ammo == null || ammo < 0 || ammo > 65535) {
      setState(() => _error = '弹量必须是 0..65535 的整数');
      return;
    }
    Navigator.of(context).pop(ReloadRequest(ammo: ammo, weapon: _weapon));
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('全体充弹'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          TextField(
            key: kReloadAllAmmoFieldKey,
            controller: _ammo,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: '弹量 0..65535'),
          ),
          const SizedBox(height: 12),
          InputDecorator(
            decoration: const InputDecoration(labelText: '武器槽位'),
            child: DropdownButtonHideUnderline(
              child: DropdownButton<WeaponSlot>(
                key: kReloadAllWeaponKey,
                value: _weapon,
                isExpanded: true,
                items: WeaponSlot.values
                    .map(
                      (WeaponSlot slot) =>
                          DropdownMenuItem<WeaponSlot>(value: slot, child: Text(slot.wire)),
                    )
                    .toList(),
                onChanged: (WeaponSlot? value) =>
                    setState(() => _weapon = value ?? WeaponSlot.primary),
              ),
            ),
          ),
          if (_error != null) ...<Widget>[
            const SizedBox(height: 8),
            Text(
              _error!,
              key: kReloadAllErrorKey,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ],
        ],
      ),
      actions: <Widget>[
        TextButton(
          key: kReloadAllCancelKey,
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: kReloadAllConfirmKey,
          onPressed: _confirm,
          child: const Text('下发'),
        ),
      ],
    );
  }
}
