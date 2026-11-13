import 'package:flutter/material.dart';

/// 指挥端共用的「顶部错误行」——一行间距 + 一枚错误色 [Text]。
///
/// 各页原本各自重复：
///
/// ```dart
/// if (err != null) ...<Widget>[
///   const SizedBox(height: 8),
///   Text(err!, key: kXxx, style: TextStyle(color: Theme.of(context).colorScheme.error)),
/// ],
/// ```
///
/// 抽成一个组件可统一视觉与间距，减少样板。
///
/// 口径：
/// - [textKey] 施加在**内层 [Text]** 上（而非本组件），以保持既有测试
///   `tester.widget<Text>(find.byKey(kXxx)).data` 的取用方式不变；
/// - [spacing] 为与上方内容的间距（原 `SizedBox(height: …)`），默认 8，紧凑处可用 4；
/// - 仅在调用方判定「有错误」后渲染（保持既有 `if (err != null)` 的语义，组件不做判空）。
class ConsoleErrorLine extends StatelessWidget {
  const ConsoleErrorLine({
    super.key,
    required this.text,
    this.textKey,
    this.spacing = 8,
  });

  /// 错误文案。
  final String text;

  /// 施加到内层 [Text] 的键（供测试与集成定位）。
  final Key? textKey;

  /// 与上方内容的间距；默认 8。
  final double spacing;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(top: spacing),
      child: Text(
        text,
        key: textKey,
        style: TextStyle(color: Theme.of(context).colorScheme.error),
      ),
    );
  }
}
