import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../../shared/api/api_client.dart';
import '../../shared/api/api_contract.dart';
import '../../shared/api/api_problem.dart';
import '../../shared/api/map_models.dart';
import '../../shared/auth/session_store.dart';

/// 供测试与后续集成定位的控件键。
const Key kMapManagerAccountKey = Key('mapManager.account');
const Key kMapManagerRefreshButtonKey = Key('mapManager.refreshButton');
const Key kMapManagerCreateButtonKey = Key('mapManager.createButton');
const Key kMapManagerLoadingKey = Key('mapManager.loading');
const Key kMapManagerEmptyKey = Key('mapManager.empty');
const Key kMapManagerErrorKey = Key('mapManager.error');

/// 按底图编号取行键与行内动作键。
Key mapManagerRowKey(String mapId) => Key('mapManager.row.$mapId');
Key mapManagerEditKey(String mapId) => Key('mapManager.edit.$mapId');
Key mapManagerDeleteKey(String mapId) => Key('mapManager.delete.$mapId');

/// 行内「上传图片」/「预览」入口（未注入对应端口时不渲染该按钮）。
Key mapManagerUploadKey(String mapId) => Key('mapManager.upload.$mapId');
Key mapManagerPreviewKey(String mapId) => Key('mapManager.preview.$mapId');

/// 行内「标定」入口（未注入 [MapManagerPage.onOpenCalibration] 时不渲染）。
Key mapManagerCalibrationKey(String mapId) => Key('mapManager.calibration.$mapId');

/// 新建 / 编辑底图对话框的控件键。
const Key kMapFormTitleKey = Key('mapManager.form.title');
const Key kMapFormNameFieldKey = Key('mapManager.form.name');
const Key kMapFormTypeKey = Key('mapManager.form.type');
const Key kMapFormErrorKey = Key('mapManager.form.error');
const Key kMapFormSaveKey = Key('mapManager.form.save');
const Key kMapFormCancelKey = Key('mapManager.form.cancel');

/// 删除确认对话框的控件键。
const Key kMapDeleteConfirmKey = Key('mapManager.deleteConfirm');
const Key kMapDeleteCancelKey = Key('mapManager.deleteCancel');

/// 图片预览对话框的控件键。
const Key kMapImagePreviewKey = Key('mapManager.imagePreview');
const Key kMapImagePreviewInfoKey = Key('mapManager.imagePreviewInfo');
const Key kMapImagePreviewCloseKey = Key('mapManager.imagePreviewClose');

/// 底图列表端口（`GET /maps`）。
typedef MapLister = Future<MapPage> Function();

/// 新建底图端口（`POST /maps`）。
typedef MapCreator = Future<MapDetail> Function(MapUpsertRequest request);

/// 修改底图端口（`PUT /maps/{mapId}`，**整体替换**）。
typedef MapUpdater = Future<MapDetail> Function(String mapId, MapUpsertRequest request);

/// 删除底图端口（`DELETE /maps/{mapId}`）。
typedef MapRemover = Future<void> Function(String mapId);

/// 上传底图图片端口（`POST /maps/{mapId}/image`，`multipart/form-data`）。
typedef MapImageUploader = Future<MapDetail> Function(String mapId, PickedImage image);

/// 下载底图图片端口（`GET /maps/{mapId}/image`，按上传原格式回二进制）。
typedef MapImageDownloader = Future<ApiResponse> Function(String mapId);

/// 选文件端口：返回 `null` 表示用户取消。**本页不依赖任何平台选文件 API**，
/// 由调用方注入（生产接线见 `app/console_app.dart`，测试注入假件）。
typedef MapImagePicker = Future<PickedImage?> Function();

/// 打开某底图的 UWB 标定面板端口（段 54）。
///
/// 标定页由调用方（`app/console_app.dart`）构造并 push，本页只负责给出行内入口。
typedef MapCalibrationOpener = void Function(MapDetail map);

/// 待上传的底图图片：文件名 + 原始字节 + 声明的内容类型。
///
/// 服务端按**真实图片头**判定类型（仅 PNG / JPEG / WebP），[contentType] 只是如实声明，
/// 不参与判型；单图上限 20MB。
class PickedImage {
  const PickedImage({
    required this.filename,
    required this.bytes,
    this.contentType = 'application/octet-stream',
  });

  /// 上传文件名（写进 `Content-Disposition` 的 `filename=`）。
  final String filename;

  /// 文件原始字节。
  final List<int> bytes;

  /// 该文件的内容类型（如实填写，服务端不信任此值）。
  final String contentType;
}

/// 底图管理页（M6 CLIENT-WINDOWS 段 53）——map 域底图子域的指挥端接线。
///
/// 覆盖底图的读 / 写：[MapLister] / [MapCreator] / [MapUpdater] / [MapRemover]，
/// 以及图片的上传（[uploadImage] + [pickImage]）与下载预览（[downloadImage]）。
/// 楼层点位编辑与 UWB 标定属同域，但在后续段接线，本页不承载。
///
/// 口径：
/// - **修改是整体替换**：契约 `MapUpsertRequest` 的 `toJson` 显式输出 `name` / `type`
///   两个键，无「不传即不改」的语义。故编辑对话框以服务端回传的 [MapDetail] 快照预填，
///   不改的字段原样回传；
/// - 底图图片**按上传原格式**回（不转码），[downloadImage] 拿到的 [ApiResponse] 保留原始
///   字节与 `contentType`，页面只负责预览，不解码 / 不落盘；
/// - 选文件走 [MapImagePicker] 端口（`null` 即用户取消）：Flutter 桌面 / Android 没有
///   内置选文件 API，本页**刻意不引第三方依赖**；
/// - 写操作**单飞去重**（按钮禁用）：重复提交会重复建底图 / 重复删。
class MapManagerPage extends StatefulWidget {
  const MapManagerPage({
    super.key,
    required this.session,
    required this.list,
    required this.create,
    required this.update,
    required this.remove,
    this.uploadImage,
    this.downloadImage,
    this.pickImage,
    this.onOpenCalibration,
  });

  /// 已登录的账号会话（提供身份快照）。
  final AccountSession session;

  final MapLister list;
  final MapCreator create;
  final MapUpdater update;
  final MapRemover remove;

  /// 上传图片端口；与 [pickImage] **成对**注入，二者缺一即不渲染行内「上传图片」。
  final MapImageUploader? uploadImage;

  /// 下载图片端口；为 `null` 时不渲染行内「预览」。
  final MapImageDownloader? downloadImage;

  /// 选文件端口；与 [uploadImage] 成对注入。
  final MapImagePicker? pickImage;

  /// 打开该底图 UWB 标定面板的入口；为 `null` 时不渲染行内「标定」。
  final MapCalibrationOpener? onOpenCalibration;

  @override
  State<MapManagerPage> createState() => _MapManagerPageState();
}

class _MapManagerPageState extends State<MapManagerPage> {
  List<MapDetail>? _maps;

  /// 读写单飞：任一请求在途时禁用按钮，避免重复新建 / 重复删除。
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// 只读刷新：重新拉列表。
  Future<void> _load() => _guard(() async {
        final MapPage page = await widget.list();
        if (!mounted) {
          return;
        }
        _maps = page.items;
      });

  Future<void> _openCreate() async {
    final MapUpsertRequest? request = await showDialog<MapUpsertRequest>(
      context: context,
      builder: (BuildContext dialogContext) => const _MapFormDialog(),
    );
    if (request == null || !mounted) {
      return;
    }
    await _guard(() async {
      await widget.create(request);
      if (!mounted) {
        return;
      }
      await _reloadAfterWrite('已新建底图「${request.name}」');
    });
  }

  Future<void> _openEdit(MapDetail map) async {
    final MapUpsertRequest? request = await showDialog<MapUpsertRequest>(
      context: context,
      builder: (BuildContext dialogContext) => _MapFormDialog(map: map),
    );
    if (request == null || !mounted) {
      return;
    }
    await _guard(() async {
      await widget.update(map.mapId, request);
      if (!mounted) {
        return;
      }
      await _reloadAfterWrite('已保存底图「${request.name}」');
    });
  }

  Future<void> _confirmRemove(MapDetail map) async {
    final bool? confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) => AlertDialog(
        title: const Text('删除底图'),
        content: Text('确定删除底图「${map.name}」？此操作不可撤销。'),
        actions: <Widget>[
          TextButton(
            key: kMapDeleteCancelKey,
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            key: kMapDeleteConfirmKey,
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
      await widget.remove(map.mapId);
      if (!mounted) {
        return;
      }
      await _reloadAfterWrite('已删除底图「${map.name}」');
    });
  }

  /// 选文件 → 上传。选文件返回 `null` 即用户取消，静默返回（同系统文件对话框）。
  Future<void> _openUpload(MapDetail map) async {
    final MapImagePicker? pick = widget.pickImage;
    final MapImageUploader? upload = widget.uploadImage;
    if (pick == null || upload == null) {
      return;
    }
    final PickedImage? image = await pick();
    if (image == null || !mounted) {
      return;
    }
    await _guard(() async {
      await upload(map.mapId, image);
      if (!mounted) {
        return;
      }
      await _reloadAfterWrite('已上传底图「${map.name}」的图片');
    });
  }

  /// 下载图片（按原格式回二进制）后在对话框里预览；页面只展示，不解码 / 不落盘。
  Future<void> _openPreview(MapDetail map) async {
    final MapImageDownloader? download = widget.downloadImage;
    if (download == null) {
      return;
    }
    ApiResponse? response;
    await _guard(() async {
      response = await download(map.mapId);
    });
    final ApiResponse? image = response;
    if (!mounted || image == null) {
      return;
    }
    await showDialog<void>(
      context: context,
      builder: (BuildContext dialogContext) => AlertDialog(
        title: Text('底图预览｜${map.name}'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Image.memory(
                Uint8List.fromList(image.body),
                key: kMapImagePreviewKey,
                errorBuilder: (BuildContext context, Object error, StackTrace? stack) =>
                    const Text('图片无法解码'),
              ),
              const SizedBox(height: 8),
              Text(
                '类型 ${image.contentType ?? '—'}｜${image.body.length} 字节',
                key: kMapImagePreviewInfoKey,
              ),
            ],
          ),
        ),
        actions: <Widget>[
          TextButton(
            key: kMapImagePreviewCloseKey,
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('关闭'),
          ),
        ],
      ),
    );
  }

  /// 写成功后重新拉列表并以 SnackBar 回执（写端点的返回体只在契约层用，不落到列表状态）。
  Future<void> _reloadAfterWrite(String notice) async {
    final MapPage page = await widget.list();
    if (!mounted) {
      return;
    }
    _maps = page.items;
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
        ApiErrorCode.mapNotFound => '底图不存在（${ApiErrorCode.mapNotFound}）',
        ApiErrorCode.mapInUse =>
          '底图已被想定引用，不能修改 / 删除（${ApiErrorCode.mapInUse}）',
        ApiErrorCode.mapImageMissing => '该底图还没有图片（${ApiErrorCode.mapImageMissing}）',
        ApiErrorCode.unsupportedMediaType =>
          '图片类型不支持（仅 PNG / JPEG / WebP，${ApiErrorCode.unsupportedMediaType}）',
        ApiErrorCode.payloadTooLarge =>
          '图片超过 20MB 上限（${ApiErrorCode.payloadTooLarge}）',
        ApiErrorCode.validationFailed => '参数校验失败（${ApiErrorCode.validationFailed}）',
        ApiErrorCode.instanceNotReady =>
          '实例尚未就绪，请稍后重试（${ApiErrorCode.instanceNotReady}）',
        _ => '操作失败：${failure.code}',
      };

  Widget _mapRow(MapDetail map) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: <Widget>[
            Expanded(
              child: Text(_describeMap(map), key: mapManagerRowKey(map.mapId)),
            ),
            if (widget.uploadImage != null && widget.pickImage != null)
              TextButton(
                key: mapManagerUploadKey(map.mapId),
                onPressed: _busy ? null : () => _openUpload(map),
                child: const Text('上传图片'),
              ),
            if (widget.downloadImage != null)
              TextButton(
                key: mapManagerPreviewKey(map.mapId),
                onPressed: _busy ? null : () => _openPreview(map),
                child: const Text('预览'),
              ),
            if (widget.onOpenCalibration != null)
              TextButton(
                key: mapManagerCalibrationKey(map.mapId),
                onPressed: _busy ? null : () => widget.onOpenCalibration!(map),
                child: const Text('标定'),
              ),
            TextButton(
              key: mapManagerEditKey(map.mapId),
              onPressed: _busy ? null : () => _openEdit(map),
              child: const Text('编辑'),
            ),
            TextButton(
              key: mapManagerDeleteKey(map.mapId),
              onPressed: _busy ? null : () => _confirmRemove(map),
              child: const Text('删除'),
            ),
          ],
        ),
      );

  @override
  Widget build(BuildContext context) {
    final List<MapDetail>? maps = _maps;
    return Scaffold(
      appBar: AppBar(
        title: const Text('底图管理'),
        actions: <Widget>[
          TextButton(
            key: kMapManagerRefreshButtonKey,
            onPressed: _busy ? null : _load,
            child: const Text('刷新'),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        key: kMapManagerCreateButtonKey,
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
              key: kMapManagerAccountKey,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kMapManagerErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            const Divider(height: 32),
            if (maps == null)
              const Text('加载中…', key: kMapManagerLoadingKey)
            else if (maps.isEmpty)
              const Text('还没有底图', key: kMapManagerEmptyKey)
            else
              ...maps.map(_mapRow),
          ],
        ),
      ),
    );
  }
}

/// 新建 / 编辑底图的参数对话框——**只收参**，请求与反馈留在「底图管理」页。
///
/// 编辑时以 [map] 的服务端快照预填：`PUT` 是**整体替换**，两个键都必填，不预填就会把
/// 没改的字段冲掉（典型误伤：只改底图名，结果类型被重置）。校验通过才 `pop(MapUpsertRequest)`，
/// 非法输入行内显示 [kMapFormErrorKey] 且不关闭对话框。
class _MapFormDialog extends StatefulWidget {
  const _MapFormDialog({this.map});

  /// 编辑目标；`null` 表示新建。
  final MapDetail? map;

  @override
  State<_MapFormDialog> createState() => _MapFormDialogState();
}

class _MapFormDialogState extends State<_MapFormDialog> {
  late final TextEditingController _name;
  late MapType _type;
  String? _error;

  @override
  void initState() {
    super.initState();
    final MapDetail? map = widget.map;
    _name = TextEditingController(text: map?.name ?? '');
    _type = map?.type ?? MapType.indoor;
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  void _save() {
    final String name = _name.text.trim();
    if (name.isEmpty || name.length > 128) {
      setState(() => _error = '底图名需为 1..128 个字符');
      return;
    }
    // 整体替换：两个键全部提交。
    Navigator.of(context).pop(MapUpsertRequest(name: name, type: _type));
  }

  @override
  Widget build(BuildContext context) {
    final bool editing = widget.map != null;
    return AlertDialog(
      title: Text(editing ? '编辑底图' : '新建底图', key: kMapFormTitleKey),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            TextField(
              key: kMapFormNameFieldKey,
              controller: _name,
              decoration: const InputDecoration(labelText: '底图名（1..128）'),
            ),
            const SizedBox(height: 12),
            InputDecorator(
              decoration: const InputDecoration(labelText: '类型'),
              child: DropdownButtonHideUnderline(
                child: DropdownButton<MapType>(
                  key: kMapFormTypeKey,
                  value: _type,
                  isExpanded: true,
                  items: MapType.values
                      .map(
                        (MapType type) =>
                            DropdownMenuItem<MapType>(value: type, child: Text(type.wire)),
                      )
                      .toList(),
                  onChanged: (MapType? value) =>
                      setState(() => _type = value ?? MapType.indoor),
                ),
              ),
            ),
            if (_error != null) ...<Widget>[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: kMapFormErrorKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: <Widget>[
        TextButton(
          key: kMapFormCancelKey,
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: kMapFormSaveKey,
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
String _describeMap(MapDetail map) => '${map.name}'
    '｜类型 ${map.type.wire}'
    '｜图片 ${_describeImage(map)}'
    '｜标定 ${_describeCalibrated(map)}';

String _describeImage(MapDetail map) {
  if (map.imageWidth != null && map.imageHeight != null) {
    return '${map.imageWidth}×${map.imageHeight}';
  }
  return map.imageUrl == null ? '无' : '有';
}

String _describeCalibrated(MapDetail map) => switch (map.calibrated) {
      true => '已标定',
      false => '未标定',
      null => '—',
    };
