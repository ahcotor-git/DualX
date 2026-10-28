import 'dart:async';

import 'package:flutter/foundation.dart';

import '../api/api_problem.dart';

/// 通用单路读轮询器：按固定周期拉取一个**只读**数据源（段 4c 抽到共享层供双端复用）。
///
/// 零依赖手写（`Timer.periodic`），口径：
/// - **至多一个在途请求**：上一拍未回则跳过本拍，慢网络下不叠加请求；
/// - 一次失败**不清空**上次成功数据，只置 [lastError]——现场观感是「数据陈旧 + 明示失败」，
///   比清空成空白更可判读；
/// - 不做本地 revision 归并、不连统一态势 WS——双通道调度与缓存聚合属后续段
///   （`/ws/v1/situation` 只认账号令牌，单兵端按设计 03 §4.7 不接）。
///
/// 本类**不认识任何具体契约模型**：拉什么、怎么翻错误文案都由调用方注入。
class PollingFeed<T> extends ChangeNotifier {
  PollingFeed({
    required this.fetch,
    this.interval = const Duration(seconds: 1),
    this.describeError,
  });

  /// 单次拉取（生产接线见 `app/*_app.dart`：`SituationApi.*`）。
  final Future<T> Function() fetch;

  /// 轮询周期；各页面按自己的通道语义注入（不在这里定死契约周期）。
  final Duration interval;

  /// 把契约错误码翻成现场可读文案；缺省为「拉取失败：`<code>`」。
  final String Function(ApiException failure)? describeError;

  Timer? _timer;
  bool _inFlight = false;
  bool _disposed = false;

  /// 最近一次成功拉取到的数据；从未成功过则为 `null`。
  T? latest;

  /// 最近一次失败的可读描述；成功后清空。
  String? lastError;

  /// 成功拉取次数（现场自测时可作「是否真的在轮询」的证据）。
  int successCount = 0;

  /// 是否处于轮询中。
  bool get isPolling => _timer != null;

  /// 立刻拉一次，随后按 [interval] 周期轮询；重复调用无副作用。
  void start() {
    if (_timer != null) {
      return;
    }
    _timer = Timer.periodic(interval, (Timer _) => _tick());
    _tick();
  }

  /// 停止轮询（可再次 [start]）。
  void stop() {
    _timer?.cancel();
    _timer = null;
    if (!_disposed) {
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _timer?.cancel();
    _timer = null;
    super.dispose();
  }

  Future<void> _tick() async {
    if (_inFlight) {
      return;
    }
    _inFlight = true;
    try {
      final T value = await fetch();
      if (_disposed) {
        return;
      }
      latest = value;
      lastError = null;
      successCount++;
      notifyListeners();
    } on ApiException catch (failure) {
      if (_disposed) {
        return;
      }
      lastError = (describeError ?? _defaultDescribe)(failure);
      notifyListeners();
    } catch (error) {
      // 网络不可达等非契约异常：必须变成可读状态，不能冒泡成红屏。
      if (_disposed) {
        return;
      }
      lastError = '拉取失败：$error';
      notifyListeners();
    } finally {
      _inFlight = false;
    }
  }
}

String _defaultDescribe(ApiException failure) => '拉取失败：${failure.code}';
