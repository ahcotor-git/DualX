import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/auth_models.dart';

/// 段 2b：auth 域契约模型的解析口径（逐字对齐 `docs/api/schemas/domain.yaml`）。
///
/// 判据是**不臆造缺省值**——必填缺失或形状不符即抛，可选缺失为 `null`。
void main() {
  group('AuthUser.fromJson', () {
    test('全字段：roles / permissions / displayName / lastLoginAt 均解析', () {
      final AuthUser user = AuthUser.fromJson(<String, dynamic>{
        'userId': 'u-1',
        'username': 'commander',
        'displayName': '指挥员甲',
        'roles': <String>['DIRECTOR', 'OPERATOR'],
        'permissions': <String>['exercise:start', 'command:kill'],
        'lastLoginAt': '2026-10-28T11:22:33Z',
      });

      expect(user.userId, 'u-1');
      expect(user.username, 'commander');
      expect(user.displayName, '指挥员甲');
      expect(user.roles, <String>['DIRECTOR', 'OPERATOR']);
      expect(user.permissions, <String>['exercise:start', 'command:kill']);
      expect(user.lastLoginAt, DateTime.utc(2026, 10, 28, 11, 22, 33));
    });

    test('可选字段缺失：permissions 按空列表、displayName / lastLoginAt 为 null', () {
      final AuthUser user = AuthUser.fromJson(<String, dynamic>{
        'userId': 'u-2',
        'username': 'viewer',
        'roles': <String>['VIEWER'],
      });

      expect(user.permissions, isEmpty);
      expect(user.displayName, isNull);
      expect(user.lastLoginAt, isNull);
    });

    test('roles 非数组形状 → FormatException', () {
      expect(
        () => AuthUser.fromJson(<String, dynamic>{
          'userId': 'u-3',
          'username': 'x',
          'roles': 'ADMIN',
        }),
        throwsA(isA<FormatException>()),
      );
    });

    test('缺必填 userId → 抛错（不补空串）', () {
      expect(
        () => AuthUser.fromJson(<String, dynamic>{'username': 'x', 'roles': <String>[]}),
        throwsA(isA<TypeError>()),
      );
    });
  });

  group('TokenPair.fromJson', () {
    test('必填 + refreshToken + 嵌套 user 解析', () {
      final TokenPair pair = TokenPair.fromJson(<String, dynamic>{
        'accessToken': 'access-1',
        'expiresIn': 900,
        'refreshToken': 'refresh-1',
        'user': <String, dynamic>{
          'userId': 'u-1',
          'username': 'commander',
          'roles': <String>['DIRECTOR'],
        },
      });

      expect(pair.accessToken, 'access-1');
      expect(pair.expiresIn, 900);
      expect(pair.refreshToken, 'refresh-1');
      expect(pair.user!.username, 'commander');
    });

    test('可选字段缺失：refreshToken / user 为 null', () {
      final TokenPair pair = TokenPair.fromJson(<String, dynamic>{
        'accessToken': 'access-2',
        'expiresIn': 300,
      });

      expect(pair.refreshToken, isNull);
      expect(pair.user, isNull);
    });

    test('expiresIn 为 JSON 小数时收成 int', () {
      final TokenPair pair = TokenPair.fromJson(<String, dynamic>{
        'accessToken': 'access-3',
        'expiresIn': 1800.0,
      });

      expect(pair.expiresIn, 1800);
      expect(pair.expiresIn, isA<int>());
    });

    test('缺必填 expiresIn → 抛错', () {
      expect(
        () => TokenPair.fromJson(<String, dynamic>{'accessToken': 'access-4'}),
        throwsA(isA<TypeError>()),
      );
    });
  });

  group('DeviceTokenPair.fromJson', () {
    test('必填解析', () {
      final DeviceTokenPair pair = DeviceTokenPair.fromJson(<String, dynamic>{
        'deviceToken': 'dev-1',
        'expiresIn': 600,
      });

      expect(pair.deviceToken, 'dev-1');
      expect(pair.expiresIn, 600);
    });

    test('缺必填 deviceToken → 抛错', () {
      expect(
        () => DeviceTokenPair.fromJson(<String, dynamic>{'expiresIn': 600}),
        throwsA(isA<TypeError>()),
      );
    });
  });
}
