import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/command_models.dart' show VestMode;
import '../../shared/api/exercise_models.dart';
import '../../shared/api/situation_models.dart' show Team;
import '../../shared/auth/session_store.dart';

/// 供测试与后续集成定位的控件键。
const Key kRosterEditorAccountKey = Key('rosterEditor.account');
const Key kRosterEditorPlanKey = Key('rosterEditor.plan');
const Key kRosterEditorRefreshButtonKey = Key('rosterEditor.refreshButton');
const Key kRosterEditorAddButtonKey = Key('rosterEditor.addButton');
const Key kRosterEditorSaveButtonKey = Key('rosterEditor.saveButton');
const Key kRosterEditorLoadingKey = Key('rosterEditor.loading');
const Key kRosterEditorEmptyKey = Key('rosterEditor.empty');
const Key kRosterEditorErrorKey = Key('rosterEditor.error');

/// 按马甲编号取行键与行内动作键。
Key rosterEditorRowKey(int vestNum) => Key('rosterEditor.row.$vestNum');
Key rosterEditorEditKey(int vestNum) => Key('rosterEditor.edit.$vestNum');
Key rosterEditorRemoveKey(int vestNum) => Key('rosterEditor.remove.$vestNum');

/// 新增 / 编辑条目的对话框控件键。
const Key kRosterEntryFormTitleKey = Key('rosterEditor.form.title');
const Key kRosterEntryVestNumKey = Key('rosterEditor.form.vestNum');
const Key kRosterEntryTeamKey = Key('rosterEditor.form.team');
const Key kRosterEntryNameKey = Key('rosterEditor.form.name');
const Key kRosterEntryHpKey = Key('rosterEditor.form.hp');
const Key kRosterEntryAmmo1Key = Key('rosterEditor.form.ammo1');
const Key kRosterEntryAmmo2Key = Key('rosterEditor.form.ammo2');
const Key kRosterEntryModeKey = Key('rosterEditor.form.mode');
const Key kRosterEntryErrorKey = Key('rosterEditor.form.error');
const Key kRosterEntrySaveKey = Key('rosterEditor.form.save');
const Key kRosterEntryCancelKey = Key('rosterEditor.form.cancel');

/// 删除条目确认对话框的控件键。
const Key kRosterRemoveConfirmKey = Key('rosterEditor.removeConfirm');
const Key kRosterRemoveCancelKey = Key('rosterEditor.removeCancel');

/// 编成拉取端口（`GET /plans/{planId}/roster`）。
typedef RosterLoader = Future<Roster> Function();

/// 编成整体替换端口（`PUT /plans/{planId}/roster`，幂等全量替换）。
typedef RosterSaver = Future<Roster> Function(Roster roster);

/// 编成编辑页（M6 CLIENT-WINDOWS 段 50）——想定编成的指挥端接线。
///
/// 就地编辑一份**本地条目清单**，`保存`时整体替换（`PUT` 幂等全量）：
/// 新增 / 编辑 / 删除都只改本地列表，最后一次性提交，「撤销」即返回上一页。
///
/// 口径：
/// - **整体替换**：契约 `Roster` 是幂等全量，空列表即清空；这一点与想定 `PUT` 一致，
///   但 `RosterEntry.toJson` 只发非 null 键（缺席由服务端补契约默认），故条目对话框
///   以服务端快照预填，[hp] / [ammo1] / [ammo2] / [mode] 留空即**不发该键**（吃服务端默认）；
/// - 客户端**只做能本地判定的校验**（编号为正整数、不重复）；设备台账存在性、不跨队等
///   由服务端判定并按 400 `VALIDATION_FAILED` 回绝（**不产生部分写入**），本页只把错误透上去；
/// - 写操作**单飞去重**（保存按钮禁用）：重复提交会重复整体替换。
class RosterEditorPage extends StatefulWidget {
  const RosterEditorPage({
    super.key,
    required this.session,
    required this.planId,
    required this.planName,
    required this.load,
    required this.save,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  /// 编成所属想定编号（整体替换时回填进 `Roster.planId`）。
  final String planId;

  /// 想定名（仅展示）。
  final String planName;

  final RosterLoader load;
  final RosterSaver save;

  @override
  State<RosterEditorPage> createState() => _RosterEditorPageState();
}

class _RosterEditorPageState extends State<RosterEditorPage> {
  /// 本地条目清单；`null` 表示尚未加载完成。
  List<RosterEntry>? _entries;

  /// 读写单飞：任一请求在途时禁用按钮，避免重复保存。
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// 只读刷新：丢弃本地未保存改动，重新拉服务端那一份。
  Future<void> _load() => _guard(() async {
        final Roster roster = await widget.load();
        if (!mounted) {
          return;
        }
        _entries = List<RosterEntry>.of(roster.entries);
      });

  Future<void> _openAdd() async {
    final List<RosterEntry> entries = _entries ?? const <RosterEntry>[];
    final RosterEntry? entry = await showDialog<RosterEntry>(
      context: context,
      builder: (BuildContext dialogContext) => _RosterEntryFormDialog(
        takenNums: entries.map((RosterEntry e) => e.vestNum).toSet(),
      ),
    );
    if (entry == null || !mounted) {
      return;
    }
    setState(() => _entries = <RosterEntry>[...entries, entry]..sort(_byVestNum));
  }

  Future<void> _openEdit(RosterEntry target) async {
    final List<RosterEntry> entries = _entries ?? const <RosterEntry>[];
    final RosterEntry? entry = await showDialog<RosterEntry>(
      context: context,
      builder: (BuildContext dialogContext) => _RosterEntryFormDialog(
        entry: target,
        takenNums: entries
            .where((RosterEntry e) => e.vestNum != target.vestNum)
            .map((RosterEntry e) => e.vestNum)
            .toSet(),
      ),
    );
    if (entry == null || !mounted) {
      return;
    }
    setState(() {
      _entries = entries
          .map((RosterEntry e) => e.vestNum == target.vestNum ? entry : e)
          .toList(growable: false)
        ..sort(_byVestNum);
    });
  }

  Future<void> _confirmRemove(RosterEntry entry) async {
    final bool? confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) => AlertDialog(
        title: const Text('移除编成成员'),
        content: Text('确定把马甲 #${entry.vestNum} 移出本编成？保存后生效。'),
        actions: <Widget>[
          TextButton(
            key: kRosterRemoveCancelKey,
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            key: kRosterRemoveConfirmKey,
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('移除'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }
    setState(() {
      _entries = (_entries ?? const <RosterEntry>[])
          .where((RosterEntry e) => e.vestNum != entry.vestNum)
          .toList(growable: false);
    });
  }

  /// 一次性整体替换：把本地清单提交给服务端，并以服务端回传的那份为准。
  Future<void> _save() => _guard(() async {
        final Roster saved = await widget.save(
          Roster(planId: widget.planId, entries: _entries ?? const <RosterEntry>[]),
        );
        if (!mounted) {
          return;
        }
        _entries = List<RosterEntry>.of(saved.entries)..sort(_byVestNum);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('已保存编成（${saved.entries.length} 名成员）')),
        );
      });

  /// 单飞执行：置忙 → 执行 → 释放；异常统一落到 [_error]（页面顶部提示），不冒泡成红屏。
  Future<void> _guard(Future<void> Function() action) async {
    if (_busy) {
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });

    try {
      await action();
    } on ApiException catch (failure) {
      if (!mounted) {
        return;
      }
      setState(() => _error = _describeFailure(failure));
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _error = '操作失败：$error');
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  String _describeFailure(ApiException failure) => switch (failure.code) {
        ApiErrorCode.validationFailed =>
          '编成校验失败：编号需在设备台账内、不重复且不跨队（${ApiErrorCode.validationFailed}）',
        ApiErrorCode.planNotFound => '想定不存在（${ApiErrorCode.planNotFound}）',
        ApiErrorCode.planInUse =>
          '想定正在演习中，不能修改编成（${ApiErrorCode.planInUse}）',
        ApiErrorCode.instanceNotReady =>
          '实例尚未就绪，请稍后重试（${ApiErrorCode.instanceNotReady}）',
        _ => '操作失败：${failure.code}',
      };

  Widget _entryRow(RosterEntry entry) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: <Widget>[
            Expanded(
              child: Text(_describeEntry(entry), key: rosterEditorRowKey(entry.vestNum)),
            ),
            TextButton(
              key: rosterEditorEditKey(entry.vestNum),
              onPressed: _busy ? null : () => _openEdit(entry),
              child: const Text('编辑'),
            ),
            TextButton(
              key: rosterEditorRemoveKey(entry.vestNum),
              onPressed: _busy ? null : () => _confirmRemove(entry),
              child: const Text('移除'),
            ),
          ],
        ),
      );

  @override
  Widget build(BuildContext context) {
    final List<RosterEntry>? entries = _entries;
    return Scaffold(
      appBar: AppBar(
        title: const Text('编成编辑'),
        actions: <Widget>[
          TextButton(
            key: kRosterEditorAddButtonKey,
            onPressed: _busy || entries == null ? null : _openAdd,
            child: const Text('添加'),
          ),
          TextButton(
            key: kRosterEditorRefreshButtonKey,
            onPressed: _busy ? null : _load,
            child: const Text('刷新'),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        key: kRosterEditorSaveButtonKey,
        onPressed: _busy || entries == null ? null : _save,
        icon: const Icon(Icons.save),
        label: const Text('保存编成'),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: <Widget>[
            Text(
              _describeAccount(widget.session),
              key: kRosterEditorAccountKey,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 4),
            Text('想定：${widget.planName}', key: kRosterEditorPlanKey),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kRosterEditorErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            const Divider(height: 32),
            if (entries == null)
              const Text('加载中…', key: kRosterEditorLoadingKey)
            else if (entries.isEmpty)
              const Text('本编成暂无成员', key: kRosterEditorEmptyKey)
            else
              ...entries.map(_entryRow),
          ],
        ),
      ),
    );
  }
}

/// 新增 / 编辑编成条目的对话框——**只收参**，请求与反馈留在「编成编辑」页。
///
/// [takenNums] 为**除本条之外**已占用的马甲编号（编辑时排除自己），用于本地判重。
/// 校验通过才 `pop(RosterEntry)`，非法输入行内显示 [kRosterEntryErrorKey] 且不关闭对话框。
class _RosterEntryFormDialog extends StatefulWidget {
  const _RosterEntryFormDialog({this.entry, required this.takenNums});

  /// 编辑目标；`null` 表示新增。
  final RosterEntry? entry;

  /// 除本条外已占用的马甲编号。
  final Set<int> takenNums;

  @override
  State<_RosterEntryFormDialog> createState() => _RosterEntryFormDialogState();
}

class _RosterEntryFormDialogState extends State<_RosterEntryFormDialog> {
  late final TextEditingController _vestNum;
  late final TextEditingController _name;
  late final TextEditingController _hp;
  late final TextEditingController _ammo1;
  late final TextEditingController _ammo2;
  late Team _team;
  late VestMode? _mode;
  String? _error;

  @override
  void initState() {
    super.initState();
    final RosterEntry? entry = widget.entry;
    _vestNum = TextEditingController(text: entry == null ? '' : '${entry.vestNum}');
    _name = TextEditingController(text: entry?.name ?? '');
    // 可空数值字段：留空即不发该键（吃服务端默认），故空值显示为空串而非预填默认。
    _hp = TextEditingController(text: entry?.hp == null ? '' : '${entry!.hp}');
    _ammo1 = TextEditingController(text: entry?.ammo1 == null ? '' : '${entry!.ammo1}');
    _ammo2 = TextEditingController(text: entry?.ammo2 == null ? '' : '${entry!.ammo2}');
    _team = entry?.team ?? Team.red;
    _mode = entry?.mode;
  }

  @override
  void dispose() {
    _vestNum.dispose();
    _name.dispose();
    _hp.dispose();
    _ammo1.dispose();
    _ammo2.dispose();
    super.dispose();
  }

  /// 校验并构造条目；任一非法即设行内错误并返回 `null`（不关闭对话框）。
  RosterEntry? _validated() {
    try {
      final int? vestNum = int.tryParse(_vestNum.text.trim());
      if (vestNum == null || vestNum <= 0) {
        setState(() => _error = '马甲编号需为正整数');
        return null;
      }
      if (widget.takenNums.contains(vestNum)) {
        setState(() => _error = '该马甲已在编成中：#$vestNum');
        return null;
      }
      return RosterEntry(
        vestNum: vestNum,
        team: _team,
        name: _blankToNull(_name.text),
        hp: _optionalInt(_hp.text, label: '生命值', max: 255),
        ammo1: _optionalInt(_ammo1.text, label: '主武器弹药', max: 65535),
        ammo2: _optionalInt(_ammo2.text, label: '副武器弹药', max: 65535),
        mode: _mode,
      );
    } on FormatException catch (error) {
      setState(() => _error = error.message);
      return null;
    }
  }

  void _save() {
    final RosterEntry? entry = _validated();
    if (entry == null) {
      return;
    }
    Navigator.of(context).pop(entry);
  }

  @override
  Widget build(BuildContext context) {
    final bool editing = widget.entry != null;
    return AlertDialog(
      title: Text(editing ? '编辑编成成员' : '添加编成成员', key: kRosterEntryFormTitleKey),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            TextField(
              key: kRosterEntryVestNumKey,
              controller: _vestNum,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: '马甲编号（正整数）'),
            ),
            const SizedBox(height: 12),
            InputDecorator(
              decoration: const InputDecoration(labelText: '队伍'),
              child: DropdownButtonHideUnderline(
                child: DropdownButton<Team>(
                  key: kRosterEntryTeamKey,
                  value: _team,
                  isExpanded: true,
                  items: Team.values
                      .map(
                        (Team team) =>
                            DropdownMenuItem<Team>(value: team, child: Text(team.wire)),
                      )
                      .toList(),
                  onChanged: (Team? value) => setState(() => _team = value ?? Team.red),
                ),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              key: kRosterEntryNameKey,
              controller: _name,
              decoration: const InputDecoration(labelText: '姓名（可空）'),
            ),
            const SizedBox(height: 12),
            TextField(
              key: kRosterEntryHpKey,
              controller: _hp,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: '生命值 0..255（留空吃默认）'),
            ),
            const SizedBox(height: 12),
            TextField(
              key: kRosterEntryAmmo1Key,
              controller: _ammo1,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: '主武器弹药 0..65535（留空吃默认）'),
            ),
            const SizedBox(height: 12),
            TextField(
              key: kRosterEntryAmmo2Key,
              controller: _ammo2,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: '副武器弹药 0..65535（留空吃默认）'),
            ),
            const SizedBox(height: 12),
            InputDecorator(
              decoration: const InputDecoration(labelText: '轮询模式'),
              child: DropdownButtonHideUnderline(
                child: DropdownButton<VestMode?>(
                  key: kRosterEntryModeKey,
                  value: _mode,
                  isExpanded: true,
                  items: <DropdownMenuItem<VestMode?>>[
                    const DropdownMenuItem<VestMode?>(
                      value: null,
                      child: Text('默认（吃服务端 AUTO）'),
                    ),
                    ...VestMode.values.map(
                      (VestMode mode) => DropdownMenuItem<VestMode?>(
                        value: mode,
                        child: Text(mode.wire),
                      ),
                    ),
                  ],
                  onChanged: (VestMode? value) => setState(() => _mode = value),
                ),
              ),
            ),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kRosterEntryErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: <Widget>[
        TextButton(
          key: kRosterEntryCancelKey,
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: kRosterEntrySaveKey,
          onPressed: _save,
          child: const Text('确定'),
        ),
      ],
    );
  }
}

int _byVestNum(RosterEntry a, RosterEntry b) => a.vestNum.compareTo(b.vestNum);

/// 可空整数：留空返回 `null`（不发该键）；非法或越界抛 [FormatException]（消息即现场文案）。
int? _optionalInt(String text, {required String label, required int max}) {
  final String trimmed = text.trim();
  if (trimmed.isEmpty) {
    return null;
  }
  final int? value = int.tryParse(trimmed);
  if (value == null || value < 0 || value > max) {
    throw FormatException('$label需为 0..$max 的整数');
  }
  return value;
}

String _describeAccount(AccountSession session) {
  final String name = session.user?.displayName ?? session.user?.username ?? '（未知账号）';
  final List<String> roles = session.user?.roles ?? const <String>[];
  return roles.isEmpty ? name : '$name（${roles.join(' / ')}）';
}

/// 契约里这些字段都可选——缺失时如实显示破折号，不臆造默认值。
String _describeEntry(RosterEntry entry) => '#${entry.vestNum}'
    '｜${entry.team.wire}'
    '｜${entry.name ?? '—'}'
    '｜HP ${entry.hp?.toString() ?? '—'}'
    '｜弹 ${entry.ammo1?.toString() ?? '—'}/${entry.ammo2?.toString() ?? '—'}'
    '｜${entry.mode?.wire ?? '—'}';

String? _blankToNull(String text) {
  final String trimmed = text.trim();
  return trimmed.isEmpty ? null : trimmed;
}
