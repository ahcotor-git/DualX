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

    /**
     * 契约 UwbMeta 的冻结结构，逐字保存、逐字读回（uwb_meta 是 TEXT，库不做任何改写）。
     *
     * <p>取值即 {@code PROTOCOL.md} §6.6 的定稿示例：**整数像素坐标**（原点左上），
     * 落在夹具的 4000×3000 底图之内。</p>
     */
    private static final String UWB_META = """
            {"bs1":{"xPx":120,"yPx":240},"bs2":{"xPx":1980,"yPx":245},\
            "bs3":{"xPx":118,"yPx":1560},"bs4":{"xPx":1975,"yPx":1565},\
            "bs5":{"xPx":1050,"yPx":240},"bs6":null,"bs7":null,"bs8":null,\
            "realLengthCm":3000,"realWidthCm":1800}""";

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
            "{\"bs1\":{\"xPx\":120,\"yPx\":240},\"bs2\":{\"xPx\":1980,\"yPx\":245},\"bs3\":{\"xPx\":118,\"yPx\":1560},\"bs4\":{\"xPx\":1975,\"yPx\":1565},\"realLengthCm\":3000,\"realWidthCm\":1800}|true",
            "{\"bs1\":{\"xPx\":120,\"yPx\":240},\"bs2\":{\"xPx\":1980,\"yPx\":245},\"bs3\":{\"xPx\":118,\"yPx\":1560},\"realLengthCm\":3000,\"realWidthCm\":1800}|false",
            "{\"bs1\":{\"xPx\":120,\"yPx\":240},\"bs2\":{\"xPx\":1980,\"yPx\":245},\"bs3\":{\"xPx\":118,\"yPx\":1560},\"bs4\":{\"xPx\":1975,\"yPx\":1565},\"realWidthCm\":1800}|false",
            "{\"bs1\":null,\"bs2\":{\"xPx\":1980,\"yPx\":245},\"bs3\":{\"xPx\":118,\"yPx\":1560},\"bs4\":{\"xPx\":1975,\"yPx\":1565},\"realLengthCm\":3000,\"realWidthCm\":1800}|false",
            "{\"bs1\":{\"xPx\":\"120\",\"yPx\":240},\"bs2\":{\"xPx\":1980,\"yPx\":245},\"bs3\":{\"xPx\":118,\"yPx\":1560},\"bs4\":{\"xPx\":1975,\"yPx\":1565},\"realLengthCm\":3000,\"realWidthCm\":1800}|false",
            "[{\"bs1\":{\"xPx\":120,\"yPx\":240}}]|false",
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

    // ── 楼层点位表（契约 listFloorPoints / replaceFloorPoints）────────────────

    @Test
    @DisplayName("楼层点位：没录过的楼层读回 null（控制器折叠成空列表），第一次 PUT 即建档")
    void floorPoints_absentIsNullAndFirstPutCreatesTheFloor() {
        UUID id = insertMap("点位读写", null, null);

        assertThat(ledger.floorPoints(id, 1)).isNull();

        ledger.replaceFloorPoints(id, 1, "[{\"posNum\":1,\"xPct\":0.5,\"yPct\":0.5}]");

        // points 是 MySQL JSON 列：键序被重排为「键长优先、再按字节序」，文本不是逐字回读
        assertThat(ledger.floorPoints(id, 1)).isEqualTo("[{\"xPct\": 0.5, \"yPct\": 0.5, \"posNum\": 1}]");
        assertThat(floors.countByMapId(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("楼层点位是单楼层替换：改 2 楼不动 1 楼，也不影响别的底图")
    void replaceFloorPoints_touchesOnlyThatFloorOfThatMap() {
        UUID id = insertMap("单层替换", null, null);
        UUID other = insertMap("另一张图", null, null);
        ledger.replaceFloorPoints(id, 1, "[{\"posNum\":1,\"xPct\":0.1,\"yPct\":0.1}]");
        ledger.replaceFloorPoints(id, 2, "[{\"posNum\":2,\"xPct\":0.2,\"yPct\":0.2}]");
        ledger.replaceFloorPoints(other, 1, "[{\"posNum\":1,\"xPct\":0.3,\"yPct\":0.3}]");

        ledger.replaceFloorPoints(id, 2, "[{\"posNum\":9,\"xPct\":0.9,\"yPct\":0.9}]");

        assertThat(ledger.floorPoints(id, 1)).isEqualTo("[{\"xPct\": 0.1, \"yPct\": 0.1, \"posNum\": 1}]");
        assertThat(ledger.floorPoints(id, 2)).isEqualTo("[{\"xPct\": 0.9, \"yPct\": 0.9, \"posNum\": 9}]");
        assertThat(ledger.floorPoints(other, 1)).isEqualTo("[{\"xPct\": 0.3, \"yPct\": 0.3, \"posNum\": 1}]");
        assertThat(floors.countByMapId(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("楼层点位挂在底图上：底图不存在时读写都是 MAP_NOT_FOUND，不静默留下孤儿点位")
    void floorPoints_unknownMap_isMapNotFound() {
        UUID absent = UuidV7.generate();

        assertCode(() -> ledger.floorPoints(absent, 1), ApiErrorCode.MAP_NOT_FOUND);
        assertCode(() -> ledger.replaceFloorPoints(absent, 1, "[]"), ApiErrorCode.MAP_NOT_FOUND);
    }

    // ── UWB 标定（契约 getUwbMeta / putUwbMeta）─────────────────────────────

    @Test
    @DisplayName("未标定的底图读 uwb-meta → MAP_NOT_CALIBRATED：图在、只是还没标定，与「id 打错了」的 404 分码")
    void uwbMeta_uncalibrated_isMapNotCalibrated() {
        UUID id = insertMap("还没标定", null, null);

        assertCode(() -> ledger.uwbMeta(id), ApiErrorCode.MAP_NOT_CALIBRATED);
    }

    @Test
    @DisplayName("底图不存在仍是 MAP_NOT_FOUND：先判图在不在，再判标没标定")
    void uwbMeta_unknownMap_isMapNotFound() {
        assertCode(() -> ledger.uwbMeta(UuidV7.generate()), ApiErrorCode.MAP_NOT_FOUND);
    }

    @Test
    @DisplayName("保存标定只改 uwb_meta 一列：名字/图片/备注原样，随后可原样读回")
    void saveUwbMeta_keepsOtherColumnsAndBecomesReadable() {
        UUID id = insertMap("待标定", "maps/abc.png", null);

        ledger.saveUwbMeta(id, UWB_META);

        assertThat(ledger.uwbMeta(id)).isEqualTo(UWB_META);
        MapView view = ledger.get(id);
        assertThat(view.calibrated()).isTrue();
        assertThat(view.map().name()).isEqualTo("待标定");
        assertThat(view.map().imagePath()).isEqualTo("maps/abc.png");
        assertThat(view.map().imageWidth()).isEqualTo(4000);
        assertThat(view.map().remark()).isEqualTo("备注");
    }

    @ParameterizedTest(name = "putUwbMeta 拒绝 → {1}")
    @CsvSource(delimiter = '|', value = {
            "{\"bs1\":{\"xPx\":120,\"yPx\":240},\"bs2\":{\"xPx\":1980,\"yPx\":245},\"bs3\":{\"xPx\":118,\"yPx\":1560},\"realLengthCm\":3000,\"realWidthCm\":1800}|四角缺 bs4",
            "{\"bs1\":{\"xPx\":120,\"yPx\":240},\"bs2\":{\"xPx\":5000,\"yPx\":245},\"bs3\":{\"xPx\":118,\"yPx\":1560},\"bs4\":{\"xPx\":1975,\"yPx\":1565},\"realLengthCm\":3000,\"realWidthCm\":1800}|坐标越出底图宽 4000",
            "{\"bs1\":{\"xPx\":120,\"yPx\":240},\"bs2\":{\"xPx\":1980,\"yPx\":240},\"bs3\":{\"xPx\":118,\"yPx\":240},\"bs4\":{\"xPx\":1975,\"yPx\":240},\"realLengthCm\":3000,\"realWidthCm\":1800}|四角共线退化",
            "{\"bs1\":{\"xPx\":120,\"yPx\":240},\"bs2\":{\"xPx\":1980,\"yPx\":245},\"bs3\":{\"xPx\":118,\"yPx\":1560},\"bs4\":{\"xPx\":1975,\"yPx\":1565},\"bs5\":{\"xPx\":1050},\"realLengthCm\":3000,\"realWidthCm\":1800}|bs5 写了但缺 yPx",
            "{\"bs1\":{\"xPx\":120,\"yPx\":240},\"bs2\":{\"xPx\":1980,\"yPx\":245},\"bs3\":{\"xPx\":118,\"yPx\":1560},\"bs4\":{\"xPx\":1975,\"yPx\":1565},\"realLengthCm\":2500.5,\"realWidthCm\":1800}|长不是整数厘米",
            "{\"bs1\":{\"xPx\":120,\"yPx\":240},\"bs2\":{\"xPx\":1980,\"yPx\":245},\"bs3\":{\"xPx\":118,\"yPx\":1560},\"bs4\":{\"xPx\":1975,\"yPx\":1565},\"realLengthCm\":3000,\"realWidthCm\":0}|宽非正",
    })
    @DisplayName("putUwbMeta 的 400 判据：结构缺项与边界不自洽一律 MAP_UWB_META_INCOMPLETE，拒绝时不留半份标定")
    void saveUwbMeta_invalid_isUwbMetaIncomplete(String uwbMeta, String reason) {
        UUID id = insertMap("标定校验", "maps/abc.png", null);

        assertCode(() -> ledger.saveUwbMeta(id, uwbMeta), ApiErrorCode.MAP_UWB_META_INCOMPLETE);

        assertThat(maps.findById(id).orElseThrow().uwbMeta()).isNull();
    }

    @Test
    @DisplayName("图片还没上传时没有可比的边界：越界校验跳过，标定照常保存")
    void saveUwbMeta_withoutImageSkipsBoundsCheck() {
        UUID id = insertMap("还没上传图片", null, null);

        ledger.saveUwbMeta(id, UWB_META);

        assertThat(ledger.get(id).calibrated()).isTrue();
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
