import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/situation_models.dart';
import 'package:scds_client/shared/state/situation_cache.dart';

/// 段 A：态势本地缓存与归并（双通道同源归并状态机）。
///
/// 判据是「换代清空、全量替换、增量按 revision 去旧、命中按 seq 去重、缓存不跨会话泄漏」，
/// 即两条通道（HTTP Poll / WS）**谁先到都不让旧帧覆盖新状态**。
void main() {
  const String epoch = 'e-2026-10-28T12:00:00Z';

  UnitState unitOf(int vestNum, {VestStatus status = VestStatus.live, int revision = 1}) =>
      UnitState(vestNum: vestNum, status: status, revision: revision);

  HitEvent hitOf(int seq) => HitEvent(seq: seq, at: DateTime.utc(2026, 10, 28, 12, 0, 0));

  SituationEnvelope full({
    required int revision,
    String epochId = epoch,
    List<UnitState> units = const <UnitState>[],
    bool running = true,
    String? exerciseId = 'ex-1',
    List<HitEvent> hits = const <HitEvent>[],
    List<TeamSummary> teamSummary = const <TeamSummary>[],
  }) =>
      SituationEnvelope(
        epoch: epochId,
        revision: revision,
        full: true,
        running: running,
        serverTime: DateTime.utc(2026, 10, 28, 12, 0, 0),
        exerciseId: exerciseId,
        units: units,
        hits: hits,
        teamSummary: teamSummary,
      );

  SituationEnvelope increment({
    required int revision,
    String epochId = epoch,
    List<UnitState> changes = const <UnitState>[],
    List<int> removedVestNums = const <int>[],
    List<HitEvent> hits = const <HitEvent>[],
    List<TeamSummary> teamSummary = const <TeamSummary>[],
    bool running = true,
  }) =>
      SituationEnvelope(
        epoch: epochId,
        revision: revision,
        full: false,
        running: running,
        serverTime: DateTime.utc(2026, 10, 28, 12, 0, 0),
        exerciseId: 'ex-1',
        changes: changes,
        removedVestNums: removedVestNums,
        hits: hits,
        teamSummary: teamSummary,
      );

  group('初始态与续传参数', () {
    test('新建缓存未对齐：needsFullResync、sinceRevision 为 null、无数据', () {
      final SituationCache cache = SituationCache();

      expect(cache.needsFullResync, isTrue);
      expect(cache.sinceRevision, isNull);
      expect(cache.epoch, isNull);
      expect(cache.units, isEmpty);
      expect(cache.appliedFrames, 0);
      expect(cache.discardedFrames, 0);
    });

    test('全量帧后对齐：sinceRevision 转为本地 revision', () {
      final SituationCache cache = SituationCache();

      expect(cache.apply(full(revision: 42, units: <UnitState>[unitOf(14)])), isTrue);

      expect(cache.needsFullResync, isFalse);
      expect(cache.sinceRevision, 42);
      expect(cache.epoch, epoch);
      expect(cache.appliedFrames, 1);
    });
  });

  group('全量帧', () {
    test('整体替换 units 并更新 running / exerciseId / serverTime', () {
      final SituationCache cache = SituationCache();
      cache.apply(full(revision: 1, units: <UnitState>[unitOf(14), unitOf(21)]));

      cache.apply(full(
        revision: 50,
        units: <UnitState>[unitOf(21, status: VestStatus.die)],
        running: false,
        exerciseId: null,
      ));

      expect(cache.revision, 50);
      expect(cache.running, isFalse);
      expect(cache.exerciseId, isNull);
      expect(cache.units.length, 1);
      expect(cache.unit(21)!.status, VestStatus.die);
      expect(cache.unit(14), isNull);
    });

    test('units 按 vestNum 升序', () {
      final SituationCache cache = SituationCache();
      cache.apply(full(revision: 1, units: <UnitState>[unitOf(30), unitOf(5), unitOf(14)]));

      expect(cache.units.map((UnitState u) => u.vestNum), <int>[5, 14, 30]);
    });
  });

  group('增量帧', () {
    test('upsert changes 并按 removedVestNums 删除', () {
      final SituationCache cache = SituationCache();
      cache.apply(full(revision: 10, units: <UnitState>[unitOf(14), unitOf(21), unitOf(30)]));

      final bool applied = cache.apply(increment(
        revision: 11,
        changes: <UnitState>[unitOf(21, status: VestStatus.die, revision: 11), unitOf(40)],
        removedVestNums: <int>[30],
      ));

      expect(applied, isTrue);
      expect(cache.revision, 11);
      expect(cache.units.map((UnitState u) => u.vestNum), <int>[14, 21, 40]);
      expect(cache.unit(21)!.status, VestStatus.die);
      expect(cache.unit(30), isNull);
    });

    test('陈旧增量（revision <= 本地）被丢弃、状态不变', () {
      final SituationCache cache = SituationCache();
      cache.apply(full(revision: 20, units: <UnitState>[unitOf(14)]));

      final bool applied = cache.apply(increment(
        revision: 20,
        changes: <UnitState>[unitOf(99)],
      ));

      expect(applied, isFalse);
      expect(cache.revision, 20);
      expect(cache.unit(99), isNull);
      expect(cache.discardedFrames, 1);
      expect(cache.appliedFrames, 1);
    });

    test('重复帧（双通道同源同 revision）只采纳一次', () {
      final SituationCache cache = SituationCache();
      cache.apply(full(revision: 5, units: <UnitState>[unitOf(14)]));
      cache.apply(increment(revision: 6, changes: <UnitState>[unitOf(21)]));

      final bool applied = cache.apply(increment(revision: 6, changes: <UnitState>[unitOf(21)]));

      expect(applied, isFalse);
      expect(cache.appliedFrames, 2);
      expect(cache.discardedFrames, 1);
    });

    test('未对齐时（首个帧即增量）丢弃并保持 needsFullResync', () {
      final SituationCache cache = SituationCache();

      final bool applied = cache.apply(increment(revision: 9, changes: <UnitState>[unitOf(14)]));

      expect(applied, isFalse);
      expect(cache.needsFullResync, isTrue);
      expect(cache.sinceRevision, isNull);
      expect(cache.units, isEmpty);
      expect(cache.discardedFrames, 1);
    });
  });

  group('revision 过旧（409）后改拉全量', () {
    test('markFullResync 使 sinceRevision 复位为 null，全量帧后恢复', () {
      final SituationCache cache = SituationCache();
      cache.apply(full(revision: 100, units: <UnitState>[unitOf(14)]));

      cache.markFullResync();
      expect(cache.needsFullResync, isTrue);
      expect(cache.sinceRevision, isNull);

      // 复位期间到达的增量按「等全量」丢弃。
      expect(cache.apply(increment(revision: 101, changes: <UnitState>[unitOf(21)])), isFalse);

      expect(cache.apply(full(revision: 102, units: <UnitState>[unitOf(21)])), isTrue);
      expect(cache.needsFullResync, isFalse);
      expect(cache.sinceRevision, 102);
    });
  });

  group('换代（epoch 变化）', () {
    test('epoch 不一致即清空本地状态并置 needsFullResync', () {
      final SituationCache cache = SituationCache();
      cache.apply(full(revision: 7, units: <UnitState>[unitOf(14), unitOf(21)], hits: <HitEvent>[hitOf(3)]));

      // 换代帧若是增量：本地已清空，本帧也不可信 → 丢弃、等全量。
      final bool applied = cache.apply(increment(
        revision: 1,
        epochId: 'e-2',
        changes: <UnitState>[unitOf(99)],
      ));

      expect(applied, isFalse);
      expect(cache.epoch, 'e-2');
      expect(cache.needsFullResync, isTrue);
      expect(cache.units, isEmpty);
      expect(cache.hits, isEmpty);
      expect(cache.revision, 0);
      expect(cache.latestHitSeq, 0);
    });

    test('换代后紧跟全量帧即可恢复（新 epoch、新内容）', () {
      final SituationCache cache = SituationCache();
      cache.apply(full(revision: 7, units: <UnitState>[unitOf(14)]));

      cache.apply(increment(revision: 1, epochId: 'e-2', changes: <UnitState>[unitOf(99)]));
      final bool applied = cache.apply(full(
        revision: 3,
        epochId: 'e-2',
        units: <UnitState>[unitOf(40)],
      ));

      expect(applied, isTrue);
      expect(cache.epoch, 'e-2');
      expect(cache.needsFullResync, isFalse);
      expect(cache.revision, 3);
      expect(cache.units.map((UnitState u) => u.vestNum), <int>[40]);
    });
  });

  group('命中与队伍汇总', () {
    test('命中按 seq 去重、升序、只收更新的序号', () {
      final SituationCache cache = SituationCache();
      cache.apply(full(revision: 1, units: <UnitState>[unitOf(14)], hits: <HitEvent>[hitOf(1), hitOf(2)]));

      cache.apply(increment(revision: 2, hits: <HitEvent>[hitOf(2), hitOf(3), hitOf(1)]));

      expect(cache.hits.map((HitEvent h) => h.seq), <int>[1, 2, 3]);
      expect(cache.latestHitSeq, 3);
    });

    test('命中超过上限时裁掉最旧的（缓存隔离）', () {
      final SituationCache cache = SituationCache();
      final List<HitEvent> many =
          List<HitEvent>.generate(kMaxCachedHits + 10, (int i) => hitOf(i + 1));
      cache.apply(full(revision: 1, hits: many));

      expect(cache.hits.length, kMaxCachedHits);
      expect(cache.hits.first.seq, 11);
      expect(cache.latestHitSeq, kMaxCachedHits + 10);
    });

    test('teamSummary 只保留最近一次非空', () {
      final SituationCache cache = SituationCache();
      cache.apply(full(
        revision: 1,
        teamSummary: <TeamSummary>[const TeamSummary(team: Team.red, total: 8, live: 7)],
      ));

      // 增量帧的 teamSummary 恒为空：不得用它抹掉已有的汇总。
      cache.apply(increment(revision: 2));
      expect(cache.teamSummary.single.team, Team.red);

      cache.apply(increment(
        revision: 3,
        teamSummary: <TeamSummary>[const TeamSummary(team: Team.blue, total: 6, live: 6)],
      ));
      expect(cache.teamSummary.single.team, Team.blue);
    });
  });

  group('reset（缓存不跨会话泄漏）', () {
    test('清空一切并回到未对齐初始态', () {
      final SituationCache cache = SituationCache();
      cache.apply(full(
        revision: 9,
        units: <UnitState>[unitOf(14)],
        hits: <HitEvent>[hitOf(1)],
        teamSummary: <TeamSummary>[const TeamSummary(team: Team.red, total: 1)],
      ));

      cache.reset();

      expect(cache.epoch, isNull);
      expect(cache.revision, 0);
      expect(cache.units, isEmpty);
      expect(cache.hits, isEmpty);
      expect(cache.teamSummary, isEmpty);
      expect(cache.latestHitSeq, 0);
      expect(cache.needsFullResync, isTrue);
      expect(cache.sinceRevision, isNull);
      expect(cache.appliedFrames, 0);
      expect(cache.discardedFrames, 0);
    });
  });
}
