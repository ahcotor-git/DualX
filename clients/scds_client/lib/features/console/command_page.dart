import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/command_models.dart';
import '../../shared/api/situation_models.dart' show Team;
import '../../shared/auth/session_store.dart';

/// 供测试与后续集成定位的控件键。
const Key kCommandTargetKey = Key('command.target');
const Key kCommandKillButtonKey = Key('command.killButton');
const Key kCommandReviveButtonKey = Key('command.reviveButton');
const Key kCommandInjureButtonKey = Key('command.injureButton');
const Key kCommandOutOfRangeButtonKey = Key('command.outOfRangeButton');
const Key kCommandReloadAmmoFieldKey = Key('command.reloadAmmoField');
const Key kCommandReloadWeaponKey = Key('command.reloadWeapon');
const Key kCommandReloadButtonKey = Key('command.reloadButton');
const Key kCommandConfigModeKey = Key('command.configMode');
const Key kCommandConfigHpFieldKey = Key('command.configHpField');
const Key kCommandConfigAmmo1FieldKey = Key('command.configAmmo1Field');
const Key kCommandConfigAmmo2FieldKey = Key('command.configAmmo2Field');
const Key kCommandConfigTeamKey = Key('command.configTeam');
const Key kCommandConfigSaveButtonKey = Key('command.configSaveButton');
const Key kCommandConfigCancelButtonKey = Key('command.configCancelButton');
const Key kCommandMoveFloorFieldKey = Key('command.moveFloorField');
const Key kCommandMovePosFieldKey = Key('command.movePosField');
const Key kCommandMoveButtonKey = Key('command.moveButton');
const Key kCommandAckKey = Key('command.ack');
const Key kCommandErrorKey = Key('command.error');

/// 无请求体的单兵指令端口（判死 / 判活 / 判伤 / 超范围）。
typedef VestCommand = Future<CommandAck> Function();

/// 带请求体的单兵充弹端口。
typedef ReloadCommand = Future<CommandAck> Function(ReloadRequest request);

/// 带请求体的覆盖配置端口。
typedef ConfigCommand = Future<CommandAck> Function(VestConfigRequest request);

/// 带请求体的手动置位端口（联调专用，`moveVest`）。
typedef MoveCommand = Future<CommandAck> Function(MoveVestRequest request);

/// 指挥指令受理回执的统一文案（本页与「全体态势」页的广播反馈共用）。
///
/// **受理 ≠ 下发**：`accepted=false` 是「已受理但未入队」（马甲离线 / 队列满），
/// 现场应看到「未下发」提示而不是报错；广播回执 `vestNum` 固定为 0，显示为「全体」。
/// `accepted=true` 但 `downlinkHex` 为空表示**本条指令不下发任何字节**（`moveVest` 手动置位）。
String describeCommandAck(CommandAck ack) {
  final String target = ack.isBroadcast ? '全体' : '#${ack.vestNum}';
  final String outcome = ack.accepted
      ? (ack.downlinkHex == null ? '已受理（无下发字节）' : '已下发：${ack.downlinkHex}')
      : '未下发（马甲离线或队列满）';
  return '${ack.command} → $target｜$outcome';
}

/// 单兵指挥指令页（M6 CLIENT-WINDOWS 续段·段 47）。
///
/// 从「全体态势」页点某个单元行进入，目标即该马甲——**不需要手输编号**。
/// 覆盖 6 个单兵写端点：判死 / 判活 / 判伤 / 超范围 / 单兵充弹 / 覆盖配置
/// （全体充弹是广播指令，入口在「全体态势」页 AppBar，不在本页）。
/// 段 66 追加**联调专用**的手动置位（`moveVest`，契约 `x-internal`）——[move] 为 `null` 时
/// 该区块**不渲染**（正式部署可只装配前 6 个端口）。
///
/// 口径：
/// - **HTTP 200 只表示「已受理」**——[CommandAck.accepted] 为 `false` 时提示「未下发」，
///   **不当错误**（马甲离线 / 队列满时服务端仍成功受理）；
/// - 覆盖配置是**覆盖语义**：未填字段保持原值，故 [VestConfigRequest.toJson] 只发非 `null` 键；
///   全空则不发请求，行内提示至少填一项；
/// - 手动置位**不下发任何字节**：回执 [CommandAck.downlinkHex] 恒为 `null`，坐标由服务端
///   按本场底图楼层点位表置位；正式演习服务端拒 403 `COMMAND_MOVE_FORBIDDEN`；
/// - 下发期间**单飞去重**（按钮禁用）：指令是写操作，重复点击会重复下发字节。
class CommandPage extends StatefulWidget {
  const CommandPage({
    super.key,
    required this.vestNum,
    required this.session,
    required this.kill,
    required this.revive,
    required this.injure,
    required this.outOfRange,
    required this.reload,
    required this.setConfig,
    this.move,
  });

  /// 指令目标马甲编号（来自态势页所选单元行）。
  final int vestNum;

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  final VestCommand kill;
  final VestCommand revive;
  final VestCommand injure;
  final VestCommand outOfRange;
  final ReloadCommand reload;
  final ConfigCommand setConfig;

  /// 手动置位端口（联调专用）；`null` 时手动置位区块不渲染。
  final MoveCommand? move;

  @override
  State<CommandPage> createState() => _CommandPageState();
}

class _CommandPageState extends State<CommandPage> {
  final TextEditingController _reloadAmmo = TextEditingController();
  WeaponSlot _reloadWeapon = WeaponSlot.primary;

  final TextEditingController _hp = TextEditingController();
  final TextEditingController _ammo1 = TextEditingController();
  final TextEditingController _ammo2 = TextEditingController();
  VestMode? _mode;
  Team? _team;

  final TextEditingController _moveFloor = TextEditingController();
  final TextEditingController _movePos = TextEditingController();

  /// 下发单飞（写操作不可重复点击）。
  bool _busy = false;
  CommandAck? _ack;
  String? _error;

  @override
  void dispose() {
    _reloadAmmo.dispose();
    _hp.dispose();
    _ammo1.dispose();
    _ammo2.dispose();
    _moveFloor.dispose();
    _movePos.dispose();
    super.dispose();
  }

  Future<void> _run(Future<CommandAck> Function() action) async {
    if (_busy) {
      return;
    }
    setState(() {
      _busy = true;
      _ack = null;
      _error = null;
    });

    try {
      final CommandAck ack = await action();
      if (!mounted) {
        return;
      }
      setState(() {
        _busy = false;
        _ack = ack;
      });
    } on ApiException catch (failure) {
      if (!mounted) {
        return;
      }
      setState(() {
        _busy = false;
        _error = _describeFailure(failure);
      });
    } catch (error) {
      // 网络不可达等非契约异常：UI 边界必须给出可读结果，不能让异常冒泡成红屏。
      if (!mounted) {
        return;
      }
      setState(() {
        _busy = false;
        _error = '下发失败：$error';
      });
    }
  }

  Future<void> _reload() async {
    final int? ammo = int.tryParse(_reloadAmmo.text.trim());
    if (ammo == null || ammo < 0 || ammo > 65535) {
      _fail('弹量必须是 0..65535 的整数');
      return;
    }
    await _run(() => widget.reload(ReloadRequest(ammo: ammo, weapon: _reloadWeapon)));
  }

  Future<void> _saveConfig() async {
    final VestConfigRequest request;
    try {
      request = VestConfigRequest(
        mode: _mode,
        hp: _optionalInt('生命值（hp）', _hp.text, 0, 255),
        ammo1: _optionalInt('主武器弹量（ammo1）', _ammo1.text, 0, 65535),
        ammo2: _optionalInt('副武器弹量（ammo2）', _ammo2.text, 0, 65535),
        team: _team,
      );
    } on FormatException catch (invalid) {
      _fail(invalid.message);
      return;
    }

    if (request.toJson().isEmpty) {
      _fail('覆盖配置至少要填写一项（未填字段保持原值）');
      return;
    }
    await _run(() => widget.setConfig(request));
  }

  void _cancelConfig() {
    setState(() {
      _hp.clear();
      _ammo1.clear();
      _ammo2.clear();
      _mode = null;
      _team = null;
      _error = null;
      _ack = null;
    });
  }

  /// 手动置位（联调专用）：楼层号 / 点位号**都必填**（契约判据：任一为 null → 400）。
  Future<void> _move() async {
    final MoveCommand? move = widget.move;
    if (move == null) {
      return;
    }
    final MoveVestRequest request;
    try {
      request = MoveVestRequest(
        floorNum: _requiredPositiveInt('楼层号', _moveFloor.text),
        posNum: _requiredPositiveInt('点位号', _movePos.text),
      );
    } on FormatException catch (invalid) {
      _fail(invalid.message);
      return;
    }
    await _run(() => move(request));
  }

  void _fail(String message) {
    setState(() {
      _error = message;
      _ack = null;
    });
  }

  /// 空串 / 非整数 / 小于 1 → 抛 [FormatException]（消息即现场文案）。
  ///
  /// 契约 `MoveVestRequest.floorNum`/`posNum` 只声明 `int32`、未给上下界，
  /// 故这里**只挡「非整数」与「0 及负数」**（楼层号 / 点位号按定义为 1 起）；
  /// 点位是否真在本场底图楼层点位表里，交服务端判定（404 `MAP_FLOOR_POINT_NOT_FOUND`）。
  int _requiredPositiveInt(String label, String text) {
    final String trimmed = text.trim();
    if (trimmed.isEmpty) {
      throw FormatException('$label 必填');
    }
    final int? value = int.tryParse(trimmed);
    if (value == null || value < 1) {
      throw FormatException('$label 需为 ≥1 的整数');
    }
    return value;
  }

  /// 空串 → `null`（表示不修改）；非整数或越界 → 抛 [FormatException]（消息即现场文案）。
  int? _optionalInt(String label, String text, int min, int max) {
    final String trimmed = text.trim();
    if (trimmed.isEmpty) {
      return null;
    }
    final int? value = int.tryParse(trimmed);
    if (value == null || value < min || value > max) {
      throw FormatException('$label 需为 $min..$max 的整数');
    }
    return value;
  }

  String _describeFailure(ApiException failure) => switch (failure.code) {
        ApiErrorCode.exerciseNotRunning => '当前没有进行中的演习（${ApiErrorCode.exerciseNotRunning}）',
        ApiErrorCode.deviceVestNotFound => '本场编成中没有该马甲（${ApiErrorCode.deviceVestNotFound}）',
        ApiErrorCode.commandVestOffline => '马甲离线（${ApiErrorCode.commandVestOffline}）',
        ApiErrorCode.commandInvalidWeaponSlot => '武器槽位非法（${ApiErrorCode.commandInvalidWeaponSlot}）',
        ApiErrorCode.exerciseAlreadyRunning => '演习已在进行中（${ApiErrorCode.exerciseAlreadyRunning}）',
        ApiErrorCode.commandMoveForbidden =>
          '本功能仅联调/训练可用，正式演习禁止手动置位（${ApiErrorCode.commandMoveForbidden}）',
        ApiErrorCode.mapNotFound => '本场未绑定底图（${ApiErrorCode.mapNotFound}）',
        ApiErrorCode.mapFloorPointNotFound =>
          '本场底图楼层点位表里没有该点（${ApiErrorCode.mapFloorPointNotFound}）',
        ApiErrorCode.validationFailed => '参数不合法（${ApiErrorCode.validationFailed}）',
        _ => '下发失败：${failure.code}',
      };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('指挥指令 · #${widget.vestNum}')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: <Widget>[
            Text(
              '目标 #${widget.vestNum}｜${_describeAccount(widget.session)}',
              key: kCommandTargetKey,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const Divider(height: 32),
            Text('单兵指令', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: <Widget>[
                OutlinedButton(
                  key: kCommandKillButtonKey,
                  onPressed: _busy ? null : () => _run(widget.kill),
                  child: const Text('判死'),
                ),
                OutlinedButton(
                  key: kCommandReviveButtonKey,
                  onPressed: _busy ? null : () => _run(widget.revive),
                  child: const Text('判活'),
                ),
                OutlinedButton(
                  key: kCommandInjureButtonKey,
                  onPressed: _busy ? null : () => _run(widget.injure),
                  child: const Text('判伤'),
                ),
                OutlinedButton(
                  key: kCommandOutOfRangeButtonKey,
                  onPressed: _busy ? null : () => _run(widget.outOfRange),
                  child: const Text('超范围'),
                ),
              ],
            ),
            const Divider(height: 32),
            Text('单兵充弹', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 8),
            Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: <Widget>[
                Expanded(
                  child: TextField(
                    key: kCommandReloadAmmoFieldKey,
                    controller: _reloadAmmo,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: '弹量 0..65535'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: InputDecorator(
                    decoration: const InputDecoration(labelText: '武器槽位'),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<WeaponSlot>(
                        key: kCommandReloadWeaponKey,
                        value: _reloadWeapon,
                        isExpanded: true,
                        items: WeaponSlot.values
                            .map(
                              (WeaponSlot slot) => DropdownMenuItem<WeaponSlot>(
                                value: slot,
                                child: Text(slot.wire),
                              ),
                            )
                            .toList(),
                        onChanged: (WeaponSlot? value) =>
                            setState(() => _reloadWeapon = value ?? WeaponSlot.primary),
                      ),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            FilledButton(
              key: kCommandReloadButtonKey,
              onPressed: _busy ? null : _reload,
              child: const Text('充弹'),
            ),
            const Divider(height: 32),
            Text('覆盖配置（未填字段保持原值）', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 8),
            Row(
              children: <Widget>[
                Expanded(
                  child: InputDecorator(
                    decoration: const InputDecoration(labelText: '轮询模式'),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<VestMode?>(
                        key: kCommandConfigModeKey,
                        value: _mode,
                        isExpanded: true,
                        items: <DropdownMenuItem<VestMode?>>[
                          const DropdownMenuItem<VestMode?>(child: Text('不修改')),
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
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: InputDecorator(
                    decoration: const InputDecoration(labelText: '队伍'),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<Team?>(
                        key: kCommandConfigTeamKey,
                        value: _team,
                        isExpanded: true,
                        items: <DropdownMenuItem<Team?>>[
                          const DropdownMenuItem<Team?>(child: Text('不修改')),
                          ...Team.values.map(
                            (Team team) =>
                                DropdownMenuItem<Team?>(value: team, child: Text(team.wire)),
                          ),
                        ],
                        onChanged: (Team? value) => setState(() => _team = value),
                      ),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: <Widget>[
                Expanded(
                  child: TextField(
                    key: kCommandConfigHpFieldKey,
                    controller: _hp,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: '生命值 0..255'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    key: kCommandConfigAmmo1FieldKey,
                    controller: _ammo1,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: '主武器弹量'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    key: kCommandConfigAmmo2FieldKey,
                    controller: _ammo2,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: '副武器弹量'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: <Widget>[
                FilledButton(
                  key: kCommandConfigSaveButtonKey,
                  onPressed: _busy ? null : _saveConfig,
                  child: const Text('保存配置'),
                ),
                const SizedBox(width: 12),
                OutlinedButton(
                  key: kCommandConfigCancelButtonKey,
                  onPressed: _busy ? null : _cancelConfig,
                  child: const Text('取消'),
                ),
              ],
            ),
            if (widget.move != null) ...<Widget>[
              const Divider(height: 32),
              Text('手动置位（联调专用）', style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 4),
              Text(
                '把 #${widget.vestNum} 的坐标直接置为本场底图楼层点位表里的点；'
                '正式演习服务端会拒绝（403）。',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 8),
              Row(
                children: <Widget>[
                  Expanded(
                    child: TextField(
                      key: kCommandMoveFloorFieldKey,
                      controller: _moveFloor,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(labelText: '楼层号'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextField(
                      key: kCommandMovePosFieldKey,
                      controller: _movePos,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(labelText: '点位号'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              FilledButton(
                key: kCommandMoveButtonKey,
                onPressed: _busy ? null : _move,
                child: const Text('置位'),
              ),
            ],
            const Divider(height: 32),
            if (_error != null)
              Text(
                _error!,
                key: kCommandErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              )
            else if (_ack != null)
              Text(describeCommandAck(_ack!), key: kCommandAckKey)
            else
              const Text('尚未下发指令'),
          ],
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
