import '../api/auth_models.dart';

/// 账号会话：登录成功后的令牌与身份（客户端持有）。
///
/// [expiresAt] 由 `TokenPair.expiresIn`（秒）在**客户端**换算为绝对时刻，
/// 服务端只给相对剩余秒数——客户端不得据此猜测服务端时钟。
class AccountSession {
  const AccountSession({
    required this.accessToken,
    required this.expiresAt,
    this.refreshToken,
    this.user,
  });

  final String accessToken;
  final String? refreshToken;
  final DateTime expiresAt;
  final AuthUser? user;

  /// 由登录/刷新的 [TokenPair] 派生；[now] 仅测试注入，生产用当前时刻（UTC）。
  factory AccountSession.fromTokenPair(TokenPair pair, {DateTime? now}) => AccountSession(
        accessToken: pair.accessToken,
        refreshToken: pair.refreshToken,
        expiresAt: (now ?? DateTime.now().toUtc()).add(Duration(seconds: pair.expiresIn)),
        user: pair.user,
      );
}

/// 设备会话：单兵端登录后的设备令牌，与马甲编号成对（设备端不持账号身份）。
class DeviceSession {
  const DeviceSession({
    required this.vestNum,
    required this.deviceToken,
    required this.expiresAt,
  });

  final int vestNum;
  final String deviceToken;
  final DateTime expiresAt;

  factory DeviceSession.fromTokenPair(int vestNum, DeviceTokenPair pair, {DateTime? now}) =>
      DeviceSession(
        vestNum: vestNum,
        deviceToken: pair.deviceToken,
        expiresAt: (now ?? DateTime.now().toUtc()).add(Duration(seconds: pair.expiresIn)),
      );
}

/// 会话存储端口：账号与设备两类会话**分开存**，切换实例/账号时一次清空。
///
/// 端口与实现分离，是为了让后续「平台安全存储」（Android Keystore / Windows DPAPI）
/// 只换实现、不动调用方；本抽象**不暴露令牌的明文读取以外语义**。
abstract interface class SessionStore {
  Future<AccountSession?> readAccount();

  Future<void> writeAccount(AccountSession session);

  Future<DeviceSession?> readDevice();

  Future<void> writeDevice(DeviceSession session);

  /// 清空两类会话（切换实例、退出登录、顶替提示后调用）。
  Future<void> clearAll();
}

/// 进程内实现：重启即丢、**不落盘**。
///
/// 平台安全存储落地前，本实现只供业务接线与测试使用——**不用于现场长期登录**
/// （进程重启后需重新登录），也**不得**把令牌写进日志。
class InMemorySessionStore implements SessionStore {
  AccountSession? _account;
  DeviceSession? _device;

  @override
  Future<AccountSession?> readAccount() async => _account;

  @override
  Future<void> writeAccount(AccountSession session) async {
    _account = session;
  }

  @override
  Future<DeviceSession?> readDevice() async => _device;

  @override
  Future<void> writeDevice(DeviceSession session) async {
    _device = session;
  }

  @override
  Future<void> clearAll() async {
    _account = null;
    _device = null;
  }
}
