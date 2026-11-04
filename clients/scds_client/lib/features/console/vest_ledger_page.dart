import 'package:flutter/material.dart';

import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/device_models.dart';
import '../../shared/api/situation_models.dart' show Team;
import '../../shared/auth/session_store.dart';

/// 供测试与后续集成定位的控件键。
const Key kVestLedgerAccountKey = Key('vestLedger.account');
const Key kVestLedgerRefreshButtonKey = Key('vestLedger.refreshButton');
const Key kVestLedgerCreateButtonKey = Key('vestLedger.createButton');
const Key kVestLedgerKeywordFieldKey = Key('vestLedger.keyword');
const Key kVestLedgerSearchButtonKey = Key('vestLedger.searchButton');
const Key kVestLedgerLoadingKey = Key('vestLedger.loading');
const Key kVestLedgerEmptyKey = Key('vestLedger.empty');
const Key kVestLedgerErrorKey = Key('vestLedger.error');

/// 按马甲编号取行键与行内动作键。
Key vestLedgerRowKey(int vestNum) => Key('vestLedger.row.$vestNum');
Key vestLedgerEditKey(int vestNum) => Key('vestLedger.edit.$vestNum');
Key vestLedgerDeleteKey(int vestNum) => Key('vestLedger.delete.$vestNum');

/// 行内绑定闭环入口：未绑定行给「绑定」，已绑定行给「换绑」+「解绑」。
/// 未注入对应端口时**不渲染**（三端口各自成对判断）。
Key vestLedgerBindKey(int vestNum) => Key('vestLedger.bind.$vestNum');
Key vestLedgerRebindKey(int vestNum) => Key('vestLedger.rebind.$vestNum');
Key vestLedgerUnbindKey(int vestNum) => Key('vestLedger.unbind.$vestNum');

/// 登记 / 编辑马甲对话框的控件键。
const Key kVestFormTitleKey = Key('vestLedger.form.title');
const Key kVestFormNumFieldKey = Key('vestLedger.form.num');
const Key kVestFormNameFieldKey = Key('vestLedger.form.name');
const Key kVestFormTeamKey = Key('vestLedger.form.team');
const Key kVestFormModelFieldKey = Key('vestLedger.form.model');
const Key kVestFormRemarkFieldKey = Key('vestLedger.form.remark');
const Key kVestFormErrorKey = Key('vestLedger.form.error');
const Key kVestFormSaveKey = Key('vestLedger.form.save');
const Key kVestFormCancelKey = Key('vestLedger.form.cancel');

/// 确认对话框的控件键（注销 / 解绑两种确认共用，同时只会有一个在屏）。
const Key kVestConfirmKey = Key('vestLedger.confirm');
const Key kVestCancelKey = Key('vestLedger.cancel');

/// 绑定 / 换绑密钥对话框的控件键。
const Key kVestBindingTitleKey = Key('vestLedger.binding.title');
const Key kVestBindingSecretFieldKey = Key('vestLedger.binding.secret');
const Key kVestBindingErrorKey = Key('vestLedger.binding.error');
const Key kVestBindingSaveKey = Key('vestLedger.binding.save');
const Key kVestBindingCancelKey = Key('vestLedger.binding.cancel');

/// 马甲台账列表端口（`GET /devices/vests`）。[keyword] 为空即不传该键（走服务端默认）。
typedef VestLister = Future<VestDevicePage> Function({String? keyword});

/// 登记马甲端口（`POST /devices/vests`）。
typedef VestCreator = Future<VestDevice> Function(VestDeviceUpsertRequest request);

/// 修改马甲端口（`PUT /devices/vests/{vestNum}`，**整体替换**）。
typedef VestUpdater = Future<VestDevice> Function(int vestNum, VestDeviceUpsertRequest request);

/// 注销马甲端口（`DELETE /devices/vests/{vestNum}`，逻辑删除）。
typedef VestRemover = Future<void> Function(int vestNum);

/// 绑定 / 换绑端口（`POST` = 首次绑定；`PUT` = 换绑）。两者共用同一签名，
/// 由页面按行上 `bound` 状态二选一调用。
typedef VestBindingWriter = Future<VestDevice> Function(int vestNum, VestBindingRequest request);

/// 解绑端口（`DELETE /devices/vests/{vestNum}/binding`，**幂等**）。
typedef VestUnbinder = Future<VestDevice> Function(int vestNum);

/// 马甲台账页（M6 CLIENT-WINDOWS 段 61）——device 域马甲台账与绑定闭环的指挥端接线。
///
/// 覆盖台账的读 / 写（[list] / [create] / [update] / [remove]）与绑定闭环
/// （[bind] / [rebind] / [unbind]）。范围＝ `docs/api/paths/device.yaml` 17 端点中的
/// **前 8 个（台账 5 + 绑定 3）**；每马甲链路状态、UWB 基站、链路状态与开关
/// （含 `watchLinks` WS 状态层）**另开段**。
///
/// 口径：
/// - **修改是整体替换**：契约 `VestDeviceUpsertRequest.toJson` 恒输出
///   `vestNum`/`name`/`team`/`model`/`remark` 五键，无「不传即不改」的语义。
///   故编辑对话框以服务端回传的 [VestDevice] 快照预填，不改的字段原样回传；
///   表单留空即**显式传 `null`**（= 清空该字段），不靠「省略」表达保留；
/// - **绑定的 POST / PUT 由行上 `bound` 决定**：`bound == true` → 换绑（PUT）+ 解绑（DELETE）；
///   否则 → 绑定（POST）。`bound == null`（未知）按「未绑定」处理，只给「绑定」，
///   被服务端以 409 `DEVICE_VEST_ALREADY_BOUND` 拒时按错误码如实提示；
/// - **密钥只上行一次**：`secret` 明文只进请求体，页面**不落日志、不本地存储**，
///   对话框不回显、列表不显示；
/// - 写操作**单飞去重**（按钮禁用）：重复提交会重复登记 / 重复注销。
class VestLedgerPage extends StatefulWidget {
  const VestLedgerPage({
    super.key,
    required this.session,
    required this.list,
    required this.create,
    required this.update,
    required this.remove,
    this.bind,
    this.rebind,
    this.unbind,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  final VestLister list;
  final VestCreator create;
  final VestUpdater update;
  final VestRemover remove;

  /// 首次绑定端口（`POST .../binding`）；为 `null` 时不渲染未绑定行的「绑定」。
  final VestBindingWriter? bind;

  /// 换绑端口（`PUT .../binding`）；为 `null` 时不渲染已绑定行的「换绑」。
  final VestBindingWriter? rebind;

  /// 解绑端口（`DELETE .../binding`）；为 `null` 时不渲染已绑定行的「解绑」。
  final VestUnbinder? unbind;

  @override
  State<VestLedgerPage> createState() => _VestLedgerPageState();
}

class _VestLedgerPageState extends State<VestLedgerPage> {
  final TextEditingController _keyword = TextEditingController();

  List<VestDevice>? _vests;

  /// 读写单飞：任一请求在途时禁用按钮，避免重复登记 / 重复注销。
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _keyword.dispose();
    super.dispose();
  }

  /// 按当前关键字重拉列表（空关键字不传该键，走服务端默认）。
  Future<void> _load() => _guard(() async {
        final VestDevicePage page = await widget.list(keyword: _keywordOrNull());
        if (!mounted) {
          return;
        }
        _vests = page.items;
      });

  String? _keywordOrNull() {
    final String keyword = _keyword.text.trim();
    return keyword.isEmpty ? null : keyword;
  }

  Future<void> _openCreate() async {
    final VestDeviceUpsertRequest? request = await showDialog<VestDeviceUpsertRequest>(
      context: context,
      builder: (BuildContext dialogContext) => const _VestFormDialog(),
    );
    if (request == null || !mounted) {
      return;
    }
    await _guard(() async {
      await widget.create(request);
      if (!mounted) {
        return;
      }
      await _reloadAfterWrite('已登记马甲 ${request.vestNum}');
    });
  }

  Future<void> _openEdit(VestDevice vest) async {
    final VestDeviceUpsertRequest? request = await showDialog<VestDeviceUpsertRequest>(
      context: context,
      builder: (BuildContext dialogContext) => _VestFormDialog(vest: vest),
    );
    if (request == null || !mounted) {
      return;
    }
    await _guard(() async {
      await widget.update(vest.vestNum, request);
      if (!mounted) {
        return;
      }
      await _reloadAfterWrite('已保存马甲 ${vest.vestNum}');
    });
  }

  Future<void> _confirmRemove(VestDevice vest) async {
    final bool? confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) => AlertDialog(
        title: const Text('注销马甲'),
        content: Text('确定注销马甲 ${vest.vestNum}（${vest.name ?? '未实名'}）？'
            '这是**逻辑注销**（`enabled=false`），演习中该设备将被忽略。'),
        actions: <Widget>[
          TextButton(
            key: kVestCancelKey,
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            key: kVestConfirmKey,
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('注销'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }
    await _guard(() async {
      await widget.remove(vest.vestNum);
      if (!mounted) {
        return;
      }
      await _reloadAfterWrite('已注销马甲 ${vest.vestNum}');
    });
  }

  /// 绑定闭环：按行上 `bound` 决定 `POST`（首次绑定）还是 `PUT`（换绑）。
  ///
  /// 密钥由对话框收集（`null` 即取消，静默返回）；对话框内已做 8..128 校验，
  /// 此处只负责把明文交出去，**不打印、不缓存**。
  Future<void> _openBinding(VestDevice vest, {required bool rebind}) async {
    final VestBindingWriter? writer = rebind ? widget.rebind : widget.bind;
    if (writer == null) {
      return;
    }
    final String? secret = await showDialog<String>(
      context: context,
      builder: (BuildContext dialogContext) =>
          _VestBindingDialog(vestNum: vest.vestNum, rebind: rebind),
    );
    if (secret == null || !mounted) {
      return;
    }
    await _guard(() async {
      await writer(vest.vestNum, VestBindingRequest(secret: secret));
      if (!mounted) {
        return;
      }
      await _reloadAfterWrite(
        rebind ? '已换绑马甲 ${vest.vestNum}' : '已绑定马甲 ${vest.vestNum}',
      );
    });
  }

  Future<void> _confirmUnbind(VestDevice vest) async {
    final VestUnbinder? unbind = widget.unbind;
    if (unbind == null) {
      return;
    }
    final bool? confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) => AlertDialog(
        title: const Text('解绑马甲'),
        content: Text('确定解绑马甲 ${vest.vestNum}（${vest.name ?? '未实名'}）？'
            '解绑只撤销「认领」，**保留绑定密钥**，可再次绑定。'),
        actions: <Widget>[
          TextButton(
            key: kVestCancelKey,
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            key: kVestConfirmKey,
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('解绑'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }
    await _guard(() async {
      await unbind(vest.vestNum);
      if (!mounted) {
        return;
      }
      await _reloadAfterWrite('已解绑马甲 ${vest.vestNum}');
    });
  }

  /// 写成功后按当前关键字重新拉列表并以 SnackBar 回执。
  Future<void> _reloadAfterWrite(String notice) async {
    final VestDevicePage page = await widget.list(keyword: _keywordOrNull());
    if (!mounted) {
      return;
    }
    _vests = page.items;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(notice)));
  }

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
        ApiErrorCode.deviceVestNotFound => '马甲不存在（${ApiErrorCode.deviceVestNotFound}）',
        ApiErrorCode.deviceVestNumConflict =>
          '该编号已被占用（${ApiErrorCode.deviceVestNumConflict}）',
        ApiErrorCode.deviceVestAlreadyBound =>
          '该马甲已绑定，请改用「换绑」（${ApiErrorCode.deviceVestAlreadyBound}）',
        ApiErrorCode.deviceVestNotBound =>
          '该马甲尚未绑定，请改用「绑定」（${ApiErrorCode.deviceVestNotBound}）',
        ApiErrorCode.exerciseAlreadyRunning =>
          '演习进行中，不能注销马甲（${ApiErrorCode.exerciseAlreadyRunning}）',
        ApiErrorCode.validationFailed => '参数校验失败（${ApiErrorCode.validationFailed}）',
        ApiErrorCode.instanceNotReady =>
          '实例尚未就绪，请稍后重试（${ApiErrorCode.instanceNotReady}）',
        _ => '操作失败：${failure.code}',
      };

  Widget _vestRow(VestDevice vest) {
    final bool bound = vest.bound == true;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: <Widget>[
          Expanded(
            child: Text(_describeVest(vest), key: vestLedgerRowKey(vest.vestNum)),
          ),
          if (bound && widget.rebind != null)
            TextButton(
              key: vestLedgerRebindKey(vest.vestNum),
              onPressed: _busy ? null : () => _openBinding(vest, rebind: true),
              child: const Text('换绑'),
            ),
          if (!bound && widget.bind != null)
            TextButton(
              key: vestLedgerBindKey(vest.vestNum),
              onPressed: _busy ? null : () => _openBinding(vest, rebind: false),
              child: const Text('绑定'),
            ),
          if (bound && widget.unbind != null)
            TextButton(
              key: vestLedgerUnbindKey(vest.vestNum),
              onPressed: _busy ? null : () => _confirmUnbind(vest),
              child: const Text('解绑'),
            ),
          TextButton(
            key: vestLedgerEditKey(vest.vestNum),
            onPressed: _busy ? null : () => _openEdit(vest),
            child: const Text('编辑'),
          ),
          TextButton(
            key: vestLedgerDeleteKey(vest.vestNum),
            onPressed: _busy ? null : () => _confirmRemove(vest),
            child: const Text('注销'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final List<VestDevice>? vests = _vests;
    return Scaffold(
      appBar: AppBar(
        title: const Text('马甲台账'),
        actions: <Widget>[
          TextButton(
            key: kVestLedgerRefreshButtonKey,
            onPressed: _busy ? null : _load,
            child: const Text('刷新'),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        key: kVestLedgerCreateButtonKey,
        onPressed: _busy ? null : _openCreate,
        icon: const Icon(Icons.add),
        label: const Text('登记'),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: <Widget>[
            Text(
              _describeAccount(widget.session),
              key: kVestLedgerAccountKey,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            Row(
              children: <Widget>[
                Expanded(
                  child: TextField(
                    key: kVestLedgerKeywordFieldKey,
                    controller: _keyword,
                    decoration: const InputDecoration(
                      labelText: '按编号 / 姓名搜索',
                      isDense: true,
                    ),
                    onSubmitted: (_) => _load(),
                  ),
                ),
                const SizedBox(width: 8),
                TextButton(
                  key: kVestLedgerSearchButtonKey,
                  onPressed: _busy ? null : _load,
                  child: const Text('搜索'),
                ),
              ],
            ),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kVestLedgerErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            const Divider(height: 32),
            if (vests == null)
              const Text('加载中…', key: kVestLedgerLoadingKey)
            else if (vests.isEmpty)
              const Text('还没有登记的马甲', key: kVestLedgerEmptyKey)
            else
              ...vests.map(_vestRow),
          ],
        ),
      ),
    );
  }
}

/// 登记 / 编辑马甲的参数对话框——**只收参**，请求与反馈留在「马甲台账」页。
///
/// 编辑时以 [vest] 的服务端快照预填五键：`PUT` 是**整体替换**，五键全发，不预填就会把
/// 没改的字段冲掉（典型误伤：只改姓名，结果阵营被清空）。编号在编辑时**只读**
/// （契约要求路径编号与请求体 `vestNum` 一致）。校验通过才 `pop(VestDeviceUpsertRequest)`。
class _VestFormDialog extends StatefulWidget {
  const _VestFormDialog({this.vest});

  /// 编辑目标；`null` 表示登记。
  final VestDevice? vest;

  @override
  State<_VestFormDialog> createState() => _VestFormDialogState();
}

class _VestFormDialogState extends State<_VestFormDialog> {
  late final TextEditingController _num;
  late final TextEditingController _name;
  late final TextEditingController _model;
  late final TextEditingController _remark;

  /// 阵营字面量；`''` 表示未设置（下拉里显式的一项，转请求时落 `null`）。
  late String _team;

  String? _error;

  @override
  void initState() {
    super.initState();
    final VestDevice? vest = widget.vest;
    _num = TextEditingController(text: vest == null ? '' : '${vest.vestNum}');
    _name = TextEditingController(text: vest?.name ?? '');
    _model = TextEditingController(text: vest?.model ?? '');
    _remark = TextEditingController(text: vest?.remark ?? '');
    _team = vest?.team?.wire ?? '';
  }

  @override
  void dispose() {
    _num.dispose();
    _name.dispose();
    _model.dispose();
    _remark.dispose();
    super.dispose();
  }

  void _save() {
    final int? vestNum = int.tryParse(_num.text.trim());
    if (vestNum == null || vestNum < 1 || vestNum > 65532) {
      setState(() => _error = '马甲编号需为 1..65532 的整数');
      return;
    }
    final String name = _name.text.trim();
    if (name.length > 64) {
      setState(() => _error = '姓名最长 64 个字符');
      return;
    }
    final String model = _model.text.trim();
    final String remark = _remark.text.trim();
    // 整体替换：五键全部提交；留空即**显式清除**该字段（传 null，不靠省略表达保留）。
    Navigator.of(context).pop(VestDeviceUpsertRequest(
      vestNum: vestNum,
      name: name.isEmpty ? null : name,
      team: _team.isEmpty ? null : Team.fromWire(_team),
      model: model.isEmpty ? null : model,
      remark: remark.isEmpty ? null : remark,
    ));
  }

  @override
  Widget build(BuildContext context) {
    final bool editing = widget.vest != null;
    return AlertDialog(
      title: Text(editing ? '编辑马甲' : '登记马甲', key: kVestFormTitleKey),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            TextField(
              key: kVestFormNumFieldKey,
              controller: _num,
              enabled: !editing,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: '马甲编号（1..65532）'),
            ),
            const SizedBox(height: 12),
            TextField(
              key: kVestFormNameFieldKey,
              controller: _name,
              decoration: const InputDecoration(labelText: '使用人姓名（最长 64）'),
            ),
            const SizedBox(height: 12),
            InputDecorator(
              decoration: const InputDecoration(labelText: '阵营'),
              child: DropdownButtonHideUnderline(
                child: DropdownButton<String>(
                  key: kVestFormTeamKey,
                  value: _team,
                  isExpanded: true,
                  items: <DropdownMenuItem<String>>[
                    const DropdownMenuItem<String>(value: '', child: Text('（未设置）')),
                    ...Team.values.map(
                      (Team team) => DropdownMenuItem<String>(
                        value: team.wire,
                        child: Text(team.wire),
                      ),
                    ),
                  ],
                  onChanged: (String? value) => setState(() => _team = value ?? ''),
                ),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              key: kVestFormModelFieldKey,
              controller: _model,
              decoration: const InputDecoration(labelText: '型号'),
            ),
            const SizedBox(height: 12),
            TextField(
              key: kVestFormRemarkFieldKey,
              controller: _remark,
              decoration: const InputDecoration(labelText: '备注'),
            ),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kVestFormErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: <Widget>[
        TextButton(
          key: kVestFormCancelKey,
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: kVestFormSaveKey,
          onPressed: _save,
          child: const Text('保存'),
        ),
      ],
    );
  }
}

/// 绑定 / 换绑密钥对话框——**只收密钥明文**，校验 8..128 后 `pop(secret)`。
///
/// 密钥在此**不回显到页面、不落日志**：校验不过则留对话框并显示 [kVestBindingErrorKey]。
class _VestBindingDialog extends StatefulWidget {
  const _VestBindingDialog({required this.vestNum, required this.rebind});

  final int vestNum;

  /// `true` = 换绑（PUT）；`false` = 首次绑定（POST）。
  final bool rebind;

  @override
  State<_VestBindingDialog> createState() => _VestBindingDialogState();
}

class _VestBindingDialogState extends State<_VestBindingDialog> {
  final TextEditingController _secret = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _secret.dispose();
    super.dispose();
  }

  void _save() {
    final String secret = _secret.text;
    if (secret.length < 8 || secret.length > 128) {
      setState(() => _error = '绑定密钥需为 8..128 个字符');
      return;
    }
    Navigator.of(context).pop(secret);
  }

  @override
  Widget build(BuildContext context) {
    final bool rebind = widget.rebind;
    return AlertDialog(
      title: Text(rebind ? '换绑马甲' : '绑定马甲', key: kVestBindingTitleKey),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Text(
              rebind
                  ? '用新密钥替换马甲 ${widget.vestNum} 的旧摘要；换绑后旧设备令牌立即失效。'
                  : '为马甲 ${widget.vestNum} 设置绑定密钥；设置后该马甲可被一台单兵终端认领。',
            ),
            const SizedBox(height: 12),
            TextField(
              key: kVestBindingSecretFieldKey,
              controller: _secret,
              decoration: const InputDecoration(
                labelText: '绑定密钥（8..128）',
                helperText: '仅上行一次，服务端只存摘要、不回显',
              ),
            ),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kVestBindingErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: <Widget>[
        TextButton(
          key: kVestBindingCancelKey,
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: kVestBindingSaveKey,
          onPressed: _save,
          child: const Text('确定'),
        ),
      ],
    );
  }
}

String _describeAccount(AccountSession session) {
  final String name = session.user?.displayName ?? session.user?.username ?? '（未知账号）';
  final List<String> roles = session.user?.roles ?? const <String>[];
  return roles.isEmpty ? name : '$name（${roles.join(' / ')}）';
}

/// 契约里除 `vestNum` 外都可选——缺失时如实显示破折号，不臆造默认值。
String _describeVest(VestDevice vest) => '编号 ${vest.vestNum}'
    '｜姓名 ${vest.name ?? '—'}'
    '｜阵营 ${vest.team?.wire ?? '—'}'
    '｜SIM ${vest.simNo ?? '—'}'
    '｜绑定 ${_tri(vest.bound, '已绑定', '未绑定')}'
    '｜启用 ${_tri(vest.enabled, '启用', '停用')}';

String _tri(bool? value, String whenTrue, String whenFalse) => switch (value) {
      true => whenTrue,
      false => whenFalse,
      null => '—',
    };
