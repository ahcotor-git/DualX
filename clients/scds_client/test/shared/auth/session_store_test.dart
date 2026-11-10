import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/auth_models.dart';
import 'package:scds_client/shared/auth/secure_session_store.dart';
import 'package:scds_client/shared/auth/session_store.dart';
import 'package:scds_client/shared/io/secure_kv_store.dart';

/// 段 2b：会话派生口径与内存会话存储。
///
/// 判据是「相对秒数在**客户端**换算为绝对时刻」与「账号 / 设备两类会话分开存」，
/// 且 `clearAll` 一次清空。
void main() {
  group('AccountSession.fromTokenPair', () {
    test('注入 now：expiresAt = now + expiresIn（UTC）', () {
      final DateTime now = DateTime.utc(2026, 10, 28, 12, 0, 0);
      const TokenPair pair = TokenPair(
        accessToken: 'access-1',
        expiresIn: 900,
        refreshToken: 'refresh-1',
      );

      final AccountSession session = AccountSession.fromTokenPair(pair, now: now);

      expect(session.accessToken, 'access-1');
      expect(session.refreshToken, 'refresh-1');
      expect(session.expiresAt, DateTime.utc(2026, 10, 28, 12, 15, 0));
    });

    test('不注入 now：以当前 UTC 时刻起算', () {
      const TokenPair pair = TokenPair(accessToken: 'access-2', expiresIn: 600);
      final DateTime before = DateTime.now().toUtc();

      final AccountSession session = AccountSession.fromTokenPair(pair);

      final DateTime after = DateTime.now().toUtc();
      expect(
        session.expiresAt.isBefore(before.add(const Duration(seconds: 600))),
        isFalse,
      );
      expect(
        session.expiresAt.isAfter(after.add(const Duration(seconds: 600))),
        isFalse,
      );
    });
  });

  group('DeviceSession.fromTokenPair', () {
    test('保留 vestNum 与 deviceToken，expiresAt 按 now 换算', () {
      final DateTime now = DateTime.utc(2026, 10, 28, 12, 0, 0);
      const DeviceTokenPair pair = DeviceTokenPair(deviceToken: 'dev-1', expiresIn: 7200);

      final DeviceSession session = DeviceSession.fromTokenPair(14, pair, now: now);

      expect(session.vestNum, 14);
      expect(session.deviceToken, 'dev-1');
      expect(session.expiresAt, DateTime.utc(2026, 10, 28, 14, 0, 0));
    });
  });

  group('InMemorySessionStore', () {
    late InMemorySessionStore store;

    setUp(() => store = InMemorySessionStore());

    test('初始两类会话均为 null', () async {
      expect(await store.readAccount(), isNull);
      expect(await store.readDevice(), isNull);
    });

    test('写入后可读回，两类互不覆盖', () async {
      final AccountSession account = AccountSession.fromTokenPair(
        const TokenPair(accessToken: 'access-1', expiresIn: 900),
        now: DateTime.utc(2026, 10, 28, 12, 0, 0),
      );
      final DeviceSession device = DeviceSession.fromTokenPair(
        14,
        const DeviceTokenPair(deviceToken: 'dev-1', expiresIn: 600),
        now: DateTime.utc(2026, 10, 28, 12, 0, 0),
      );

      await store.writeAccount(account);
      await store.writeDevice(device);

      expect((await store.readAccount())!.accessToken, 'access-1');
      expect((await store.readDevice())!.vestNum, 14);
    });

    test('clearAll 一次清空两类会话', () async {
      await store.writeAccount(AccountSession.fromTokenPair(
        const TokenPair(accessToken: 'access-1', expiresIn: 900),
      ));
      await store.writeDevice(DeviceSession.fromTokenPair(
        14,
        const DeviceTokenPair(deviceToken: 'dev-1', expiresIn: 600),
      ));

      await store.clearAll();

      expect(await store.readAccount(), isNull);
      expect(await store.readDevice(), isNull);
    });
  });

  group('SecureSessionStore（段 71：平台安全存储，注入假件）', () {
    late _FakeSecureKv kv;
    late SecureSessionStore store;

    setUp(() {
      kv = _FakeSecureKv();
      store = SecureSessionStore(kv);
    });

    test('初始两类会话均为 null，且底层无键', () async {
      expect(await store.readAccount(), isNull);
      expect(await store.readDevice(), isNull);
      expect(kv.values, isEmpty);
    });

    test('账号会话写入后可原样读回（令牌 / 过期时刻 / 身份全字段往返）', () async {
      final AccountSession session = AccountSession(
        accessToken: 'access-1',
        refreshToken: 'refresh-1',
        expiresAt: DateTime.utc(2026, 11, 10, 6, 10, 8, 693),
        user: AuthUser(
          userId: 'u-1',
          username: 'admin',
          displayName: 'Dev Admin',
          roles: const <String>['ADMIN'],
          permissions: const <String>['*'],
          lastLoginAt: DateTime.utc(2026, 11, 10, 5, 55),
        ),
      );

      await store.writeAccount(session);

      final AccountSession read = (await store.readAccount())!;
      expect(read.accessToken, 'access-1');
      expect(read.refreshToken, 'refresh-1');
      expect(read.expiresAt, DateTime.utc(2026, 11, 10, 6, 10, 8, 693));
      expect(read.user!.userId, 'u-1');
      expect(read.user!.username, 'admin');
      expect(read.user!.displayName, 'Dev Admin');
      expect(read.user!.roles, <String>['ADMIN']);
      expect(read.user!.permissions, <String>['*']);
      expect(read.user!.lastLoginAt, DateTime.utc(2026, 11, 10, 5, 55));
    });

    test('可选字段缺席时读回为 null / 空列表（不臆造）', () async {
      await store.writeAccount(AccountSession(
        accessToken: 'access-2',
        expiresAt: DateTime.utc(2026, 11, 10, 12, 0),
      ));

      final AccountSession read = (await store.readAccount())!;
      expect(read.refreshToken, isNull);
      expect(read.user, isNull);
    });

    test('设备会话写入后可原样读回，且与账号会话互不覆盖', () async {
      await store.writeAccount(AccountSession(
        accessToken: 'access-1',
        expiresAt: DateTime.utc(2026, 11, 10, 12, 0),
      ));
      await store.writeDevice(DeviceSession(
        vestNum: 62600,
        deviceToken: 'device-1',
        expiresAt: DateTime.utc(2026, 11, 10, 14, 0),
      ));

      final DeviceSession read = (await store.readDevice())!;
      expect(read.vestNum, 62600);
      expect(read.deviceToken, 'device-1');
      expect(read.expiresAt, DateTime.utc(2026, 11, 10, 14, 0));
      expect((await store.readAccount())!.accessToken, 'access-1');
      expect(kv.values.keys, containsAll(<String>[
        SecureSessionStore.accountKey,
        SecureSessionStore.deviceKey,
      ]));
    });

    test('clearAll 清空两类会话并删掉底层两个键', () async {
      await store.writeAccount(AccountSession(
        accessToken: 'access-1',
        expiresAt: DateTime.utc(2026, 11, 10, 12, 0),
      ));
      await store.writeDevice(DeviceSession(
        vestNum: 62600,
        deviceToken: 'device-1',
        expiresAt: DateTime.utc(2026, 11, 10, 14, 0),
      ));

      await store.clearAll();

      expect(await store.readAccount(), isNull);
      expect(await store.readDevice(), isNull);
      expect(kv.values, isEmpty);
    });

    test('损坏条目降级为 null（坏 JSON 不抛、不让启动砖住）', () async {
      await kv.write(SecureSessionStore.accountKey, '{不是 JSON');
      await kv.write(SecureSessionStore.deviceKey, '[]');

      expect(await store.readAccount(), isNull);
      expect(await store.readDevice(), isNull);
    });

    test('形状不符 / 缺必填字段降级为 null', () async {
      await kv.write(SecureSessionStore.accountKey, '{"accessToken":"a"}');
      await kv.write(SecureSessionStore.deviceKey, '{"vestNum":"not-a-number"}');

      expect(await store.readAccount(), isNull);
      expect(await store.readDevice(), isNull);
    });
  });

  group('PlatformSecureKvStore（段 71：经插件测试平台的端口↔插件映射）', () {
    test('写入后读回；删除后回到 null', () async {
      FlutterSecureStorage.setMockInitialValues(<String, String>{});
      const SecureKvStore kv = PlatformSecureKvStore();

      expect(await kv.read('k'), isNull);
      await kv.write('k', 'v');
      expect(await kv.read('k'), 'v');
      await kv.delete('k');
      expect(await kv.read('k'), isNull);
    });
  });
}

/// 假件：内存 map 模拟平台安全存储，**不触碰真实平台通道**（段 71）。
class _FakeSecureKv implements SecureKvStore {
  final Map<String, String> values = <String, String>{};

  @override
  Future<String?> read(String key) async => values[key];

  @override
  Future<void> write(String key, String value) async {
    values[key] = value;
  }

  @override
  Future<void> delete(String key) async {
    values.remove(key);
  }
}
