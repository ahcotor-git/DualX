import 'package:flutter/material.dart';

import '../../shared/api/api_client.dart';
import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/exercise_models.dart';
import '../../shared/auth/session_store.dart';

/// 供测试与后续集成定位的控件键。
const Key kPlanManagerAccountKey = Key('planManager.account');
const Key kPlanManagerKeywordFieldKey = Key('planManager.keywordField');
const Key kPlanManagerSearchButtonKey = Key('planManager.searchButton');
const Key kPlanManagerRefreshButtonKey = Key('planManager.refreshButton');
const Key kPlanManagerCreateButtonKey = Key('planManager.createButton');
const Key kPlanManagerLoadingKey = Key('planManager.loading');
const Key kPlanManagerEmptyKey = Key('planManager.empty');
const Key kPlanManagerErrorKey = Key('planManager.error');

/// 按想定编号取行键与行内动作键。
Key planManagerRowKey(String planId) => Key('planManager.row.$planId');
Key planManagerEditKey(String planId) => Key('planManager.edit.$planId');
Key planManagerDeleteKey(String planId) => Key('planManager.delete.$planId');

/// 段 50 新增：进入该想定「编成编辑」页的行内入口（未注入回调时不渲染该按钮）。
Key planManagerRosterKey(String planId) => Key('planManager.roster.$planId');

/// 段 59 新增：想定导入 / 模板下载的控件键。
///
/// 「下载模板」需同时注入 [PlanTemplateDownloader] + [PlanTemplateSaver] 才渲染；
/// 「导入想定」需同时注入 [PlanImporter] + [PlanFilePicker] 才渲染。
const Key kPlanManagerTemplateFormatKey = Key('planManager.templateFormat');
const Key kPlanManagerTemplateButtonKey = Key('planManager.templateButton');
const Key kPlanManagerImportButtonKey = Key('planManager.importButton');

/// 新建 / 编辑想定对话框的控件键。
const Key kPlanFormTitleKey = Key('planManager.form.title');
const Key kPlanFormNameFieldKey = Key('planManager.form.name');
const Key kPlanFormMapIdFieldKey = Key('planManager.form.mapId');
const Key kPlanFormBattleModeKey = Key('planManager.form.battleMode');
const Key kPlanFormRemarkFieldKey = Key('planManager.form.remark');
const Key kPlanFormErrorKey = Key('planManager.form.error');
const Key kPlanFormSaveKey = Key('planManager.form.save');
const Key kPlanFormCancelKey = Key('planManager.form.cancel');

/// 删除确认对话框的控件键。
const Key kPlanDeleteConfirmKey = Key('planManager.deleteConfirm');
const Key kPlanDeleteCancelKey = Key('planManager.deleteCancel');

/// 想定列表端口（`GET /plans`）；[keyword] 为空即列出全部。
typedef PlanLister = Future<PlanPage> Function({String? keyword});

/// 新建想定端口（`POST /plans`）。
typedef PlanCreator = Future<Plan> Function(PlanUpsertRequest request);

/// 修改想定端口（`PUT /plans/{planId}`，**整体替换**）。
typedef PlanUpdater = Future<Plan> Function(String planId, PlanUpsertRequest request);

/// 删除想定端口（`DELETE /plans/{planId}`）。
typedef PlanRemover = Future<void> Function(String planId);

/// 段 59 新增：下载想定导入模板端口（`GET /plans/template?format=`，按格式回**二进制**）。
///
/// 保原始 [ApiResponse]（字节 + [ApiResponse.contentType]），本页不解码，直接交 [PlanTemplateSaver]。
typedef PlanTemplateDownloader = Future<ApiResponse> Function(PlanTemplateFormat format);

/// 段 59 新增：从模板文件导入想定端口（`POST /plans/import`，`multipart/form-data`，201）。
///
/// [name] 为 `null` 时不带该字段（服务端取文件名）。
typedef PlanImporter = Future<Plan> Function(String filename, List<int> bytes, String? name);

/// 段 59 新增：选文件端口：返回 `null` 表示用户取消。
///
/// **本页不依赖任何平台选文件 API**，由调用方注入（生产接线见 `app/console_app.dart`，
/// 测试注入假件）；为 `null` 时**不渲染「导入想定」按钮**。
typedef PlanFilePicker = Future<PickedPlanFile?> Function();

/// 段 59 新增：落盘模板端口：把下载到的原始字节按 [filename] 交给调用方保存。
///
/// Flutter 桌面 / Android **没有内置写文件对话框**，本页**刻意不引第三方依赖**——
/// 为 `null` 时**不渲染「下载模板」按钮**（模板下载须能落盘才有意义）。
typedef PlanTemplateSaver = Future<void> Function(
  String filename,
  List<int> bytes,
  String? contentType,
);

/// 段 59 新增：待导入的想定模板文件——文件名 + 原始字节。
///
/// 服务端按**整文件内容**校验（表头 / 列 / 行），[filename] 只写进 `Content-Disposition`
/// 的 `filename=`，服务端不信任它。
class PickedPlanFile {
  const PickedPlanFile({required this.filename, required this.bytes});

  /// 模板文件名。
  final String filename;

  /// 文件原始字节。
  final List<int> bytes;
}

/// 想定管理页（M6 CLIENT-WINDOWS 段 49；段 50 增「编成」入口；段 59 增模板下载 / 导入）——exercise 域写端点的指挥端接线。
///
/// 覆盖「想定」子域的读/写：[PlanLister] / [PlanCreator] / [PlanUpdater] / [PlanRemover]，
/// 以及行内「编成」入口（[onOpenRoster]，段 50 接 [RosterEditorPage]）、
/// 「下载模板」（[downloadTemplate] + [saveBytes]，段 59）与「导入想定」
/// （[importPlan] + [pickFile]，段 59）。
/// 开演 / 停演属同一域，但在后续段接线，本页不承载。
///
/// 口径：
/// - **修改是整体替换**：契约 `PlanUpsertRequest` 的 `toJson` 显式输出全部四个键，
///   `null` 即「置空」。故编辑对话框**以服务端回传的 [Plan] 快照预填**——不改的字段
///   原样回传，避免「只改想定名却把底图解绑了」这类误覆盖；
/// - 底图 / 备注留空即置 `null`（解绑底图 / 清空备注），这是**有意**的替换语义，不是缺省；
/// - 写操作**单飞去重**（按钮禁用）：重复提交会重复建想定 / 重复删。
class PlanManagerPage extends StatefulWidget {
  const PlanManagerPage({
    super.key,
    required this.session,
    required this.list,
    required this.create,
    required this.update,
    required this.remove,
    this.onOpenRoster,
    this.downloadTemplate,
    this.importPlan,
    this.pickFile,
    this.saveBytes,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  final PlanLister list;
  final PlanCreator create;
  final PlanUpdater update;
  final PlanRemover remove;

  /// 段 50 新增：打开该想定的「编成编辑」页；为 `null` 时不渲染行内「编成」按钮。
  final void Function(Plan plan)? onOpenRoster;

  /// 段 59 新增：下载模板端口；与 [saveBytes] **成对**——缺一即不渲染「下载模板」。
  final PlanTemplateDownloader? downloadTemplate;

  /// 段 59 新增：导入想定端口；与 [pickFile] **成对**——缺一即不渲染「导入想定」。
  final PlanImporter? importPlan;

  /// 段 59 新增：选文件端口；为 `null` 时不渲染「导入想定」按钮。
  final PlanFilePicker? pickFile;

  /// 段 59 新增：落盘端口；为 `null` 时不渲染「下载模板」按钮。
  final PlanTemplateSaver? saveBytes;

  @override
  State<PlanManagerPage> createState() => _PlanManagerPageState();
}

class _PlanManagerPageState extends State<PlanManagerPage> {
  final TextEditingController _keyword = TextEditingController();

  List<Plan>? _plans;

  /// 段 59：模板下载格式（默认 XLSX，与契约 `format` 默认一致）。
  PlanTemplateFormat _format = PlanTemplateFormat.xlsx;

  /// 读写单飞：任一请求在途时禁用按钮，避免重复新建 / 重复删除。
  bool _busy = false;
  String? _error;

  /// 段 59：两对端口都齐备才渲染对应入口（缺一即视为该能力不可用）。
  bool get _showTemplate =>
      widget.downloadTemplate != null && widget.saveBytes != null;
  bool get _showImport => widget.importPlan != null && widget.pickFile != null;

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

  /// 只读刷新：按当前关键字重新拉列表。
  Future<void> _load() => _guard(() async {
        final PlanPage page = await widget.list(keyword: _keywordOf());
        if (!mounted) {
          return;
        }
        _plans = page.items;
      });

  Future<void> _openCreate() async {
    final PlanUpsertRequest? request = await showDialog<PlanUpsertRequest>(
      context: context,
      builder: (BuildContext dialogContext) => const _PlanFormDialog(),
    );
    if (request == null || !mounted) {
      return;
    }
    await _guard(() async {
      await widget.create(request);
      if (!mounted) {
        return;
      }
      await _reloadAfterWrite('已新建想定「${request.name}」');
    });
  }

  Future<void> _openEdit(Plan plan) async {
    final PlanUpsertRequest? request = await showDialog<PlanUpsertRequest>(
      context: context,
      builder: (BuildContext dialogContext) => _PlanFormDialog(plan: plan),
    );
    if (request == null || !mounted) {
      return;
    }
    await _guard(() async {
      await widget.update(plan.planId, request);
      if (!mounted) {
        return;
      }
      await _reloadAfterWrite('已保存想定「${request.name}」');
    });
  }

  Future<void> _confirmRemove(Plan plan) async {
    final bool? confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) => AlertDialog(
        title: const Text('删除想定'),
        content: Text('确定删除想定「${plan.name}」？此操作不可撤销。'),
        actions: <Widget>[
          TextButton(
            key: kPlanDeleteCancelKey,
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            key: kPlanDeleteConfirmKey,
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }
    await _guard(() async {
      await widget.remove(plan.planId);
      if (!mounted) {
        return;
      }
      await _reloadAfterWrite('已删除想定「${plan.name}」');
    });
  }

  /// 段 59：下载导入模板（按所选格式取二进制）并交落盘端口保存。
  ///
  /// 模板须能落盘才有意义，故 [_showTemplate] 已保证两个端口都在位；本方法再取一次是
  /// 为了让类型收窄（`null` 检查即早退）。
  Future<void> _downloadTemplate() async {
    final PlanTemplateDownloader? download = widget.downloadTemplate;
    final PlanTemplateSaver? saver = widget.saveBytes;
    if (download == null || saver == null) {
      return;
    }
    await _guard(() async {
      final ApiResponse response = await download(_format);
      final String filename = _templateFileName(_format);
      await saver(filename, response.body, response.contentType);
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('已保存模板 $filename')));
    });
  }

  /// 段 59：选文件 → 走 multipart 导入；未选择（`null`）即止步，**不发请求**。
  ///
  /// 文件名交服务端，`name` 不传即由服务端取文件名（契约口径）。
  Future<void> _importPlan() async {
    final PlanFilePicker? picker = widget.pickFile;
    final PlanImporter? importer = widget.importPlan;
    if (picker == null || importer == null) {
      return;
    }
    final PickedPlanFile? file = await picker();
    if (!mounted) {
      return;
    }
    if (file == null) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('未选择文件')));
      return;
    }
    await _guard(() async {
      final Plan plan = await importer(file.filename, file.bytes, null);
      if (!mounted) {
        return;
      }
      await _reloadAfterWrite('已导入想定「${plan.name}」');
    });
  }

  /// 写成功后重新拉列表并以 SnackBar 回执（写端点的返回体只在契约层用，不落到列表状态）。
  Future<void> _reloadAfterWrite(String notice) async {
    final PlanPage page = await widget.list(keyword: _keywordOf());
    if (!mounted) {
      return;
    }
    _plans = page.items;
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

  /// 空关键字 → `null`（走服务端「列出全部」口径）。
  String? _keywordOf() {
    final String trimmed = _keyword.text.trim();
    return trimmed.isEmpty ? null : trimmed;
  }

  String _describeFailure(ApiException failure) => switch (failure.code) {
        ApiErrorCode.planNotFound => '想定不存在（${ApiErrorCode.planNotFound}）',
        ApiErrorCode.planInUse =>
          '想定正在演习中或已被引用，不能修改 / 删除（${ApiErrorCode.planInUse}）',
        ApiErrorCode.validationFailed => '参数校验失败（${ApiErrorCode.validationFailed}）',
        ApiErrorCode.instanceNotReady =>
          '实例尚未就绪，请稍后重试（${ApiErrorCode.instanceNotReady}）',
        _ => '操作失败：${failure.code}',
      };

  Widget _planRow(Plan plan) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: <Widget>[
            Expanded(
              child: Text(_describePlan(plan), key: planManagerRowKey(plan.planId)),
            ),
            if (widget.onOpenRoster != null)
              TextButton(
                key: planManagerRosterKey(plan.planId),
                onPressed: _busy ? null : () => widget.onOpenRoster!(plan),
                child: const Text('编成'),
              ),
            TextButton(
              key: planManagerEditKey(plan.planId),
              onPressed: _busy ? null : () => _openEdit(plan),
              child: const Text('编辑'),
            ),
            TextButton(
              key: planManagerDeleteKey(plan.planId),
              onPressed: _busy ? null : () => _confirmRemove(plan),
              child: const Text('删除'),
            ),
          ],
        ),
      );

  @override
  Widget build(BuildContext context) {
    final List<Plan>? plans = _plans;
    return Scaffold(
      appBar: AppBar(
        title: const Text('想定管理'),
        actions: <Widget>[
          TextButton(
            key: kPlanManagerRefreshButtonKey,
            onPressed: _busy ? null : _load,
            child: const Text('刷新'),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        key: kPlanManagerCreateButtonKey,
        onPressed: _busy ? null : _openCreate,
        icon: const Icon(Icons.add),
        label: const Text('新建'),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: <Widget>[
            Text(
              _describeAccount(widget.session),
              key: kPlanManagerAccountKey,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 12),
            Row(
              children: <Widget>[
                Expanded(
                  child: TextField(
                    key: kPlanManagerKeywordFieldKey,
                    controller: _keyword,
                    decoration: const InputDecoration(
                      labelText: '按名称搜索',
                      hintText: '留空列出全部',
                    ),
                    onSubmitted: (_) => _load(),
                  ),
                ),
                const SizedBox(width: 12),
                FilledButton(
                  key: kPlanManagerSearchButtonKey,
                  onPressed: _busy ? null : _load,
                  child: const Text('搜索'),
                ),
              ],
            ),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kPlanManagerErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            if (_showTemplate || _showImport) ...<Widget>[
              const SizedBox(height: 12),
              Wrap(
                spacing: 12,
                runSpacing: 8,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: <Widget>[
                  if (_showTemplate)
                    InputDecorator(
                      decoration: const InputDecoration(labelText: '模板格式'),
                      child: DropdownButtonHideUnderline(
                        child: DropdownButton<PlanTemplateFormat>(
                          key: kPlanManagerTemplateFormatKey,
                          value: _format,
                          items: PlanTemplateFormat.values
                              .map(
                                (PlanTemplateFormat format) =>
                                    DropdownMenuItem<PlanTemplateFormat>(
                                  value: format,
                                  child: Text(format.wire),
                                ),
                              )
                              .toList(),
                          onChanged: (PlanTemplateFormat? value) =>
                              setState(() => _format = value ?? PlanTemplateFormat.xlsx),
                        ),
                      ),
                    ),
                  if (_showTemplate)
                    FilledButton(
                      key: kPlanManagerTemplateButtonKey,
                      onPressed: _busy ? null : _downloadTemplate,
                      child: const Text('下载模板'),
                    ),
                  if (_showImport)
                    FilledButton(
                      key: kPlanManagerImportButtonKey,
                      onPressed: _busy ? null : _importPlan,
                      child: const Text('导入想定'),
                    ),
                ],
              ),
            ],
            const Divider(height: 32),
            if (plans == null)
              const Text('加载中…', key: kPlanManagerLoadingKey)
            else if (plans.isEmpty)
              const Text('没有匹配的想定', key: kPlanManagerEmptyKey)
            else
              ...plans.map(_planRow),
          ],
        ),
      ),
    );
  }
}

/// 新建 / 编辑想定的参数对话框——**只收参**，请求与反馈留在「想定管理」页。
///
/// 编辑时以 [plan] 的服务端快照预填：`PUT` 是**整体替换**，不预填就会把没改的字段置空
/// （典型误伤：只改想定名，结果底图被解绑）。校验通过才 `pop(PlanUpsertRequest)`，
/// 非法输入行内显示 [kPlanFormErrorKey] 且不关闭对话框。
class _PlanFormDialog extends StatefulWidget {
  const _PlanFormDialog({this.plan});

  /// 编辑目标；`null` 表示新建。
  final Plan? plan;

  @override
  State<_PlanFormDialog> createState() => _PlanFormDialogState();
}

class _PlanFormDialogState extends State<_PlanFormDialog> {
  late final TextEditingController _name;
  late final TextEditingController _mapId;
  late final TextEditingController _remark;
  late BattleMode _battleMode;
  String? _error;

  @override
  void initState() {
    super.initState();
    final Plan? plan = widget.plan;
    _name = TextEditingController(text: plan?.name ?? '');
    _mapId = TextEditingController(text: plan?.mapId ?? '');
    _remark = TextEditingController(text: plan?.remark ?? '');
    _battleMode = plan?.battleMode ?? BattleMode.train;
  }

  @override
  void dispose() {
    _name.dispose();
    _mapId.dispose();
    _remark.dispose();
    super.dispose();
  }

  void _save() {
    final String name = _name.text.trim();
    if (name.isEmpty || name.length > 128) {
      setState(() => _error = '想定名需为 1..128 个字符');
      return;
    }
    // 整体替换：四个键全部提交；底图 / 备注留空即置 null（解绑 / 清空）。
    Navigator.of(context).pop(
      PlanUpsertRequest(
        name: name,
        mapId: _blankToNull(_mapId.text),
        battleMode: _battleMode,
        remark: _blankToNull(_remark.text),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final bool editing = widget.plan != null;
    return AlertDialog(
      title: Text(editing ? '编辑想定' : '新建想定', key: kPlanFormTitleKey),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            TextField(
              key: kPlanFormNameFieldKey,
              controller: _name,
              decoration: const InputDecoration(labelText: '想定名（1..128）'),
            ),
            const SizedBox(height: 12),
            TextField(
              key: kPlanFormMapIdFieldKey,
              controller: _mapId,
              decoration: const InputDecoration(labelText: '底图编号（留空即不绑定）'),
            ),
            const SizedBox(height: 12),
            InputDecorator(
              decoration: const InputDecoration(labelText: '演习模式'),
              child: DropdownButtonHideUnderline(
                child: DropdownButton<BattleMode>(
                  key: kPlanFormBattleModeKey,
                  value: _battleMode,
                  isExpanded: true,
                  items: BattleMode.values
                      .map(
                        (BattleMode mode) =>
                            DropdownMenuItem<BattleMode>(value: mode, child: Text(mode.wire)),
                      )
                      .toList(),
                  onChanged: (BattleMode? value) =>
                      setState(() => _battleMode = value ?? BattleMode.train),
                ),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              key: kPlanFormRemarkFieldKey,
              controller: _remark,
              decoration: const InputDecoration(labelText: '备注（可空）'),
            ),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kPlanFormErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: <Widget>[
        TextButton(
          key: kPlanFormCancelKey,
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: kPlanFormSaveKey,
          onPressed: _save,
          child: const Text('保存'),
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

/// 契约里这些字段都可选——缺失时如实显示破折号，不臆造默认值。
String _describePlan(Plan plan) => '${plan.name}'
    '｜模式 ${plan.battleMode?.wire ?? '—'}'
    '｜底图 ${plan.mapId ?? '—'}'
    '｜编成 ${plan.rosterCount ?? '—'}';

String? _blankToNull(String text) {
  final String trimmed = text.trim();
  return trimmed.isEmpty ? null : trimmed;
}

/// 段 59：模板本地文件名——契约只回格式，文件名由客户端按格式约定拼，不臆造服务端字段。
String _templateFileName(PlanTemplateFormat format) =>
    'plan-template.${format == PlanTemplateFormat.xlsx ? 'xlsx' : 'csv'}';
