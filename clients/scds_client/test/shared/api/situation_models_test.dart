import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/situation_models.dart';

/// 段 3b：态势契约模型解析口径。
///
/// 判据是「必填缺失 → TypeError / 形状不符与未知枚举 → FormatException / 可空集合缺省 → 空列表」，
/// 即**不臆造缺省值**：宁可显式失败，也不静默落到某个默认分支。
void main() {
  group('SituationEnvelope.fromJson', () {
    test('全量快照：必填 + units + teamSummary + hits 均解析', () {
      final SituationEnvelope envelope = SituationEnvelope.fromJson(<String, dynamic>{
        'epoch': 'e-2026-10-28T12:00:00Z',
        'revision': 42,
        'full': true,
        'running': true,
        'serverTime': '2026-10-28T12:00:03Z',
        'exerciseId': 'ex-1',
        'units': <dynamic>[
          <String, dynamic>{'vestNum': 14, 'status': 'LIVE', 'revision': 40},
        ],
        'teamSummary': <dynamic>[
          <String, dynamic>{'team': 'RED', 'total': 8, 'live': 7, 'dead': 1, 'offline': 0, 'hitsDealt': 3},
        ],
        'hits': <dynamic>[
          <String, dynamic>{'seq': 7, 'at': '2026-10-28T11:59:00Z', 'hitVestNum': 21, 'lethal': true},
        ],
      });

      expect(envelope.epoch, 'e-2026-10-28T12:00:00Z');
      expect(envelope.revision, 42);
      expect(envelope.full, isTrue);
      expect(envelope.running, isTrue);
      expect(envelope.serverTime, DateTime.utc(2026, 10, 28, 12, 0, 3));
      expect(envelope.exerciseId, 'ex-1');
      expect(envelope.units.single.vestNum, 14);
      expect(envelope.teamSummary.single.team, Team.red);
      expect(envelope.hits.single.seq, 7);
    });

    test('增量：changes / removedVestNums 有效，未出现的可空集合落为空列表', () {
      final SituationEnvelope envelope = SituationEnvelope.fromJson(<String, dynamic>{
        'epoch': 'e-1',
        'revision': 43,
        'full': false,
        'running': true,
        'serverTime': '2026-10-28T12:00:05Z',
        'changes': <dynamic>[
          <String, dynamic>{'vestNum': 21, 'status': 'DIE', 'revision': 43},
        ],
        'removedVestNums': <dynamic>[99, 100],
      });

      expect(envelope.full, isFalse);
      expect(envelope.changes.single.status, VestStatus.die);
      expect(envelope.removedVestNums, <int>[99, 100]);
      expect(envelope.units, isEmpty);
      expect(envelope.hits, isEmpty);
      expect(envelope.teamSummary, isEmpty);
    });

    test('running=false 且无演习：exerciseId 为 null、units 为空数组', () {
      final SituationEnvelope envelope = SituationEnvelope.fromJson(<String, dynamic>{
        'epoch': 'e-1',
        'revision': 0,
        'full': true,
        'running': false,
        'serverTime': '2026-10-28T12:00:00Z',
        'units': <dynamic>[],
      });

      expect(envelope.running, isFalse);
      expect(envelope.exerciseId, isNull);
      expect(envelope.units, isEmpty);
    });

    test('缺必填 serverTime → TypeError', () {
      expect(
        () => SituationEnvelope.fromJson(<String, dynamic>{
          'epoch': 'e-1',
          'revision': 1,
          'full': true,
          'running': true,
        }),
        throwsA(isA<TypeError>()),
      );
    });
  });

  group('UnitState.fromJson', () {
    test('全字段：室内坐标 / 运动状态 / 心跳 / 阵亡时刻', () {
      final UnitState unit = UnitState.fromJson(<String, dynamic>{
        'vestNum': 14,
        'name': '张三',
        'team': 'BLUE',
        'combatRole': 'SOLDIER',
        'weaponKind': 'HJ73',
        'status': 'LIVE',
        'hp': 100,
        'ammo1': 30,
        'ammo2': 12,
        'position': <String, dynamic>{
          'coordinateSystem': 'INDOOR',
          'xPct': 0.25,
          'yPct': 0.75,
          'floorNum': 2,
          'posNum': 7,
        },
        'motion': 'MOVING',
        'signalQuality': 4,
        'positionSource': 'UWB',
        'lastHeartbeatAt': '2026-10-28T12:00:02Z',
        'deadAt': null,
        'revision': 41,
      });

      expect(unit.vestNum, 14);
      expect(unit.name, '张三');
      expect(unit.team, Team.blue);
      expect(unit.combatRole, CombatRole.soldier);
      expect(unit.weaponKind, WeaponKind.hj73);
      expect(unit.hp, 100);
      expect(unit.ammo1, 30);
      expect(unit.ammo2, 12);
      expect(unit.position!.coordinateSystem, CoordinateSystem.indoor);
      expect(unit.position!.xPct, 0.25);
      expect(unit.position!.yPct, 0.75);
      expect(unit.position!.floorNum, 2);
      expect(unit.motion, MotionState.moving);
      expect(unit.signalQuality, 4);
      expect(unit.positionSource, PositionSource.uwb);
      expect(unit.lastHeartbeatAt, DateTime.utc(2026, 10, 28, 12, 0, 2));
      expect(unit.deadAt, isNull);
      expect(unit.revision, 41);
    });

    test('离线且从未定位：position 显式为 null，不臆造坐标', () {
      final UnitState unit = UnitState.fromJson(<String, dynamic>{
        'vestNum': 15,
        'status': 'OFFLINE',
        'position': null,
        'positionSource': 'NONE',
        'revision': 1,
      });

      expect(unit.status, VestStatus.offline);
      expect(unit.position, isNull);
      expect(unit.positionSource, PositionSource.none);
      expect(unit.deadAt, isNull);
    });

    test('未知 status 字面量 → FormatException', () {
      expect(
        () => UnitState.fromJson(<String, dynamic>{'vestNum': 14, 'status': 'ALIVE', 'revision': 1}),
        throwsA(isA<FormatException>()),
      );
    });

    test('缺必填 revision → TypeError', () {
      expect(
        () => UnitState.fromJson(<String, dynamic>{'vestNum': 14, 'status': 'LIVE'}),
        throwsA(isA<TypeError>()),
      );
    });
  });

  group('GeoPoint.fromJson', () {
    test('契约未声明必填：空对象解析为全 null（不臆造坐标）', () {
      final GeoPoint point = GeoPoint.fromJson(<String, dynamic>{});

      expect(point.coordinateSystem, isNull);
      expect(point.xPct, isNull);
      expect(point.lng, isNull);
      expect(point.wgs84Lat, isNull);
    });

    test('室外双坐标并存：BD-09 与 WGS-84 均由服务端下发，客户端不换算', () {
      final GeoPoint point = GeoPoint.fromJson(<String, dynamic>{
        'coordinateSystem': 'OUTDOOR',
        'lng': 118.1,
        'lat': 24.9,
        'wgs84Lng': 118.09,
        'wgs84Lat': 24.89,
      });

      expect(point.coordinateSystem, CoordinateSystem.outdoor);
      expect(point.lng, 118.1);
      expect(point.wgs84Lng, 118.09);
      expect(point.xPct, isNull);
    });
  });

  group('HitEvent.fromJson', () {
    test('危险物命中：shooterNum 为危险物编号，shooterIsSoldier=false', () {
      final HitEvent hit = HitEvent.fromJson(<String, dynamic>{
        'seq': 12,
        'at': '2026-10-28T12:01:00Z',
        'hitVestNum': 21,
        'shooterNum': 65535,
        'shooterIsSoldier': false,
        'shooterWeaponKind': 'GRENADE',
        'hitPart': 'LEFT_LEG',
        'lethal': false,
        'position': <String, dynamic>{'coordinateSystem': 'INDOOR', 'xPct': 0.5, 'yPct': 0.5},
      });

      expect(hit.seq, 12);
      expect(hit.at, DateTime.utc(2026, 10, 28, 12, 1, 0));
      expect(hit.hitVestNum, 21);
      expect(hit.shooterNum, 65535);
      expect(hit.shooterIsSoldier, isFalse);
      expect(hit.shooterWeaponKind, WeaponKind.grenade);
      expect(hit.hitPart, HitPart.leftLeg);
      expect(hit.lethal, isFalse);
      expect(hit.position!.xPct, 0.5);
    });

    test('缺必填 seq → TypeError', () {
      expect(
        () => HitEvent.fromJson(<String, dynamic>{'at': '2026-10-28T12:01:00Z'}),
        throwsA(isA<TypeError>()),
      );
    });

    test('未知 hitPart 字面量 → FormatException', () {
      expect(
        () => HitEvent.fromJson(<String, dynamic>{
          'seq': 1,
          'at': '2026-10-28T12:01:00Z',
          'hitPart': 'NECK',
        }),
        throwsA(isA<FormatException>()),
      );
    });
  });

  group('TeamSummary.fromJson / HitEventPage.fromJson', () {
    test('TeamSummary 仅 team 必填，计数缺省为 null', () {
      final TeamSummary summary = TeamSummary.fromJson(<String, dynamic>{'team': 'ORANGE'});

      expect(summary.team, Team.orange);
      expect(summary.total, isNull);
      expect(summary.live, isNull);
      expect(summary.hitsDealt, isNull);
    });

    test('HitEventPage：items 与 latestSeq；items 缺省为空列表', () {
      final HitEventPage page = HitEventPage.fromJson(<String, dynamic>{
        'items': <dynamic>[
          <String, dynamic>{'seq': 3, 'at': '2026-10-28T12:02:00Z'},
        ],
        'latestSeq': 3,
      });

      expect(page.items.single.seq, 3);
      expect(page.latestSeq, 3);
      expect(HitEventPage.fromJson(<String, dynamic>{}).items, isEmpty);
    });
  });
}
