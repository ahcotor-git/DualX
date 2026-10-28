import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/auth_models.dart';
import 'package:scds_client/shared/auth/session_store.dart';

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
}
