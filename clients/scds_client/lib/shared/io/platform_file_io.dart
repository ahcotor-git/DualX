import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';

/// 一次「选中文件」的结果：文件名 + 原始字节（段 69）。
class PickedFileData {
  const PickedFileData({required this.filename, required this.bytes});

  /// 被选文件的**基本名**（含扩展名），原样交服务端作 multipart 的 `filename`。
  final String filename;

  /// 文件原始字节，**不解码**。
  final Uint8List bytes;
}

/// 平台文件能力窄端口（段 69）：选文件（读）+ 落盘（写）。
///
/// 指挥端三处文件能力都只依赖本端口，不各自引平台代码：
/// - 段 59 导入想定 → [pickFile]；
/// - 段 53 上传底图 → [pickFile]；
/// - 段 58 导出下载 / 段 59 模板下载 → [saveFile]。
///
/// 生产实现是 [PlatformFileIo]（`file_picker` + 系统对话框）；测试注入假件，
/// **不触碰真实平台通道**。
abstract interface class FileIo {
  /// 走系统文件对话框选**单个**文件；用户取消回 `null`。
  ///
  /// [allowedExtensions] 为 `null` 时不限类型；否则按扩展名（**小写、不带点**）过滤。
  Future<PickedFileData?> pickFile({List<String>? allowedExtensions});

  /// 走系统「另存为」对话框把 [bytes] 以 [filename] 写出；用户取消回 `false`。
  ///
  /// [contentType] 如实声明产物 MIME（平台可能忽略）。
  Future<bool> saveFile({
    required String filename,
    required List<int> bytes,
    String? contentType,
  });
}

/// [FileIo] 的 `file_picker` 实现。
///
/// 落盘的字节写出由插件在其平台实现内完成（Windows / Android 均如此），本类**不碰 `dart:io`**。
class PlatformFileIo implements FileIo {
  const PlatformFileIo();

  @override
  Future<PickedFileData?> pickFile({List<String>? allowedExtensions}) async {
    final PlatformFile? picked = await FilePicker.pickFile(
      type: allowedExtensions == null ? FileType.any : FileType.custom,
      allowedExtensions: allowedExtensions,
    );
    if (picked == null) {
      return null;
    }
    return PickedFileData(filename: picked.name, bytes: await picked.readAsBytes());
  }

  @override
  Future<bool> saveFile({
    required String filename,
    required List<int> bytes,
    String? contentType,
  }) async {
    final Uri? saved = await FilePicker.saveFile(
      fileName: filename,
      bytes: Uint8List.fromList(bytes),
      mimeType: contentType ?? 'application/octet-stream',
    );
    return saved != null;
  }
}

/// 按扩展名**如实声明**图片 MIME（段 53）。
///
/// 只做本地声明，**不作业务判定**——服务端仍按真实图片头判型（仅认 PNG / JPEG / WebP，
/// 单图上限 20MB），名字与内容不符时由服务端回绝。
String contentTypeForFilename(String filename) {
  final int dot = filename.lastIndexOf('.');
  switch (dot < 0 ? '' : filename.substring(dot + 1).toLowerCase()) {
    case 'png':
      return 'image/png';
    case 'jpg':
    case 'jpeg':
      return 'image/jpeg';
    case 'webp':
      return 'image/webp';
    default:
      return 'application/octet-stream';
  }
}
