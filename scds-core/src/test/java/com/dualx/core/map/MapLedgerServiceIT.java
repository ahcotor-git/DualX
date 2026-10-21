package com.dualx.core.map;

import com.dualx.core.domain.BaseMap;
import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.MapFloor;
import com.dualx.core.domain.MapType;
import com.dualx.core.domain.Plan;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.map.MapLedgerService.MapView;
import com.dualx.core.repository.BaseMapRepository;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.repository.MapFloorRepository;
import com.dualx.core.repository.PlanRepository;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.UuidV7;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 底图台账服务的真实数据库行为（契约 {@code paths/map.yaml} → maps / mapById）。
 *
 * <p>带测试外层事务：本类会往开发库写入底图、楼层点位与想定，用例结束整体回滚，
 * 不动本地已配置的底图档案。</p>
 */
@SpringBootTest
@Transactional
class MapLedgerServiceIT {

    /** 契约 UwbMeta 的冻结结构，逐字保存、逐字读回（uwb_meta 是 TEXT，库不做任何改写）。 */
    private static final String UWB_META = """
            {"bs1":{"xPx":0.1,"yPx":0.2},"bs2":{"xPx":0.9,"yPx":0.2},\
            "bs3":{"xPx":0.9,"yPx":0.8},"bs4":{"xPx":0.1,"yPx":0.8},\
            "bs5":null,"bs6":null,"bs7":null,"bs8":null,"realLengthCm":2000.0,"realWidthCm":1000.0}""";

    @Autowired private MapLedgerService ledger;
    @Autowired private BaseMapRepository maps;
    @Autowired private MapFloorRepository floors;
    @Autowired private PlanRepository plans;
    @Autowired private ExerciseRepository exercises;

    @Test
    @DisplayName("新建底图 → 建档即空图：无图片、无标定、零楼层")
    void create_thenGet_roundTripsBlankDraft() {
        MapView created = ledger.create("营区底图", MapType.INDOOR);

        assertThat(created.map().name()).isEqualTo("营区底图");
        assertThat(created.map().mapType()).isEqualTo(MapType.INDOOR);
        assertThat(created.map().imagePath()).isNull();
        assertThat(created.map().uwbMeta()).isNull();
        assertThat(created.map().createdAt()).isNotNull();
        assertThat(created.floorCount()).isZero();
        assertThat(created.calibrated()).isFalse();

        assertThat(maps.findById(created.map().id())).isPresent();
    }

    @Test
    @DisplayName("查不存在的底图 → MAP_NOT_FOUND，不返回 null 让调用方自己判")
    void get_unknownMap_isMapNotFound() {
        UUID absent = UuidV7.generate();

        assertCode(() -> ledger.get(absent), ApiErrorCode.MAP_NOT_FOUND);
    }

    @Test
    @DisplayName("改名换型不吃掉已上传的图片与标定：契约的 MapUpsertRequest 只有 name/type")
    void update_keepsImageAndCalibration() {
        UUID id = insertMap("旧名", "maps/abc.png", UWB_META);

        MapView updated = ledger.update(id, "新名", MapType.OUTDOOR);

        assertThat(updated.map().name()).isEqualTo("新名");
        assertThat(updated.map().mapType()).isEqualTo(MapType.OUTDOOR);
        assertThat(updated.map().imagePath()).isEqualTo("maps/abc.png");
        assertThat(updated.map().imageWidth()).isEqualTo(4000);
        assertThat(updated.map().imageHeight()).isEqualTo(3000);
        assertThat(updated.map().uwbMeta()).isEqualTo(UWB_META);
        assertThat(updated.calibrated()).isTrue();
    }

    @Test
    @DisplayName("被想定引用的底图不可删：409 判据是 t_plan.map_id，且原行必须还在")
    void delete_referencedByPlan_isMapInUseAndRowSurvives() {
        UUID id = insertMap("被引用的底图", null, null);
        plans.insert(new Plan(UuidV7.generate(), "引用该底图的想定", id, BattleMode.TRAIN, null, null, null));

        assertCode(() -> ledger.delete(id), ApiErrorCode.MAP_IN_USE);

        // 拒绝必须是「整件事没发生」：行还在，楼层点位也不该被顺手清掉
        assertThat(maps.findById(id)).isPresent();
    }

    @Test
    @DisplayName("进行中的演习正用这张底图 → 同样拒绝，t_exercise.map_id 是开演快照")
    void delete_snapshotOfRunningExercise_isMapInUse() {
        UUID id = insertMap("演习在用底图", null, null);
        startRunningExercise(id);

        assertCode(() -> ledger.delete(id), ApiErrorCode.MAP_IN_USE);

        assertThat(maps.findById(id)).isPresent();
    }

    @Test
    @DisplayName("演习用的是别的底图 → 不构成删除阻碍，判据必须落在 map_id 上")
    void delete_mapUntouchedByRunningExercise_isAllowed() {
        UUID id = insertMap("演习没用这张", null, null);
        startRunningExercise(insertMap("演习真用的那张", null, null));

        ledger.delete(id);

        assertThat(maps.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("删除底图顺带清楼层点位：无外键，不清就是一张谁也读不到的死表")
    void delete_removesMapAndItsFloorPoints() {
        UUID id = insertMap("待删底图", null, null);
        floors.insert(new MapFloor(0L, id, 1, "[{\"posNum\":1,\"xPct\":0.5,\"yPct\":0.5,\"label\":\"A\"}]", null, null));
        floors.insert(new MapFloor(0L, id, 2, "[]", null, null));
        assertThat(ledger.get(id).floorCount()).isEqualTo(2);

        ledger.delete(id);

        assertThat(maps.findById(id)).isEmpty();
        assertThat(floors.countByMapId(id)).isZero();
    }

    @ParameterizedTest(name = "uwb_meta 齐备性 → {1}")
    @CsvSource(delimiter = '|', value = {
            "{\"bs1\":{\"xPx\":0.1,\"yPx\":0.2},\"bs2\":{\"xPx\":0.9,\"yPx\":0.2},\"bs3\":{\"xPx\":0.9,\"yPx\":0.8},\"bs4\":{\"xPx\":0.1,\"yPx\":0.8},\"realLengthCm\":2000.0,\"realWidthCm\":1000.0}|true",
            "{\"bs1\":{\"xPx\":0.1,\"yPx\":0.2},\"bs2\":{\"xPx\":0.9,\"yPx\":0.2},\"bs3\":{\"xPx\":0.9,\"yPx\":0.8},\"realLengthCm\":2000.0,\"realWidthCm\":1000.0}|false",
            "{\"bs1\":{\"xPx\":0.1,\"yPx\":0.2},\"bs2\":{\"xPx\":0.9,\"yPx\":0.2},\"bs3\":{\"xPx\":0.9,\"yPx\":0.8},\"bs4\":{\"xPx\":0.1,\"yPx\":0.8},\"realWidthCm\":1000.0}|false",
            "{\"bs1\":null,\"bs2\":{\"xPx\":0.9,\"yPx\":0.2},\"bs3\":{\"xPx\":0.9,\"yPx\":0.8},\"bs4\":{\"xPx\":0.1,\"yPx\":0.8},\"realLengthCm\":2000.0,\"realWidthCm\":1000.0}|false",
            "{\"bs1\":{\"xPx\":\"0.1\",\"yPx\":0.2},\"bs2\":{\"xPx\":0.9,\"yPx\":0.2},\"bs3\":{\"xPx\":0.9,\"yPx\":0.8},\"bs4\":{\"xPx\":0.1,\"yPx\":0.8},\"realLengthCm\":2000.0,\"realWidthCm\":1000.0}|false",
            "[{\"bs1\":{\"xPx\":0.1,\"yPx\":0.2}}]|false",
            "{不是合法 JSON|false",
    })
    @DisplayName("calibrated 只判「有没有」不判「对不对」：文本损坏按未标定处理，不让列表 500")
    void calibrated_isPresenceOnlyJudge(String uwbMeta, boolean expected) {
        UUID id = insertMap("标定判定", null, uwbMeta);

        assertThat(ledger.get(id).calibrated()).isEqualTo(expected);
    }

    @Test
    @DisplayName("没有标定（null / 空白）一律未标定")
    void calibrated_isFalseWhenUwbMetaAbsent() {
        UUID blank = insertMap("空白标定", null, "   ");

        assertThat(ledger.get(blank).calibrated()).isFalse();
    }

    @Test
    @DisplayName("列表：type 为空即不过滤，三个页签共用一条查询；分页取 total 不走 current 页")
    void list_filtersByTypeAndReportsTotalIndependentOfPage() {
        ledger.create("列表室内甲", MapType.INDOOR);
        ledger.create("列表室内乙", MapType.INDOOR);
        ledger.create("列表室外甲", MapType.OUTDOOR);

        Page<MapView> indoor = ledger.list(MapType.INDOOR, PageRequest.of(1, 1));

        assertThat(indoor.items()).hasSize(1);
        assertThat(indoor.items().getFirst().map().mapType()).isEqualTo(MapType.INDOOR);
        assertThat(indoor.total()).isEqualTo(maps.countByType(MapType.INDOOR));
        assertThat(indoor.total()).isGreaterThanOrEqualTo(2);

        Page<MapView> all = ledger.list(null, PageRequest.of(1, PageRequest.MAX_SIZE));

        assertThat(all.total()).isEqualTo(maps.count());
        assertThat(all.total()).isEqualTo(maps.countByType(MapType.INDOOR) + maps.countByType(MapType.OUTDOOR));
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    private UUID insertMap(String name, String imagePath, String uwbMeta) {
        UUID id = UuidV7.generate();
        maps.insert(new BaseMap(id, name, MapType.INDOOR, imagePath,
                imagePath == null ? null : 4000, imagePath == null ? null : 3000,
                imagePath == null ? null : "备注", uwbMeta, null, null));
        return id;
    }

    /**
     * 开一场绑到 {@code mapId} 的演习。库层用 {@code running_flag} 唯一索引保证「至多一场进行中」，
     * 所以测试库若残留进行中场次，先在本用例事务内把它停掉——用例结束整体回滚，
     * 库里那行原样不变。
     */
    private void startRunningExercise(UUID mapId) {
        exercises.findRunning().ifPresent(running ->
                exercises.markEnded(running.id(), Instant.now(), running.hitCount(), running.deadCount()));
        exercises.insert(new Exercise(UuidV7.generate(), UuidV7.generate(), "进行中的想定", mapId,
                BattleMode.TRAIN, Instant.now(), null, 3, 0, 0, null, null));
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ApiErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
