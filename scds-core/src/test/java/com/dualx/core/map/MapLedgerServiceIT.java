package com.dualx.core.map;

import com.dualx.core.domain.BaseMap;
import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.domain.MapFloor;
import com.dualx.core.domain.MapType;
import com.dualx.core.domain.Plan;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.map.MapLedgerService.MapView;
import com.dualx.core.port.FileStore;
import com.dualx.core.repository.BaseMapRepository;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.repository.MapFloorRepository;
import com.dualx.core.repository.PlanRepository;
import com.dualx.core.storage.ImageHeaders;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    @Autowired private FileStore store;

    /**
     * 图片落盘的一次性根目录，建在本模块的 {@code target/} 下（{@code mvn clean} 顺带清掉）。
     *
     * <p>{@code scds.files.root} 的默认值是 {@code ${user.home}/.scds/files}：让 IT 往开发机主目录写
     * 文件既污染环境，也会让「上传成功」依赖开发机的目录状态。更要紧的是**事务回滚只管数据库、
     * 不会删掉已落盘的文件**，所以这个目录必须一次性且不与人共用。</p>
     */
    private static final String FILES_ROOT =
            Path.of("target", "test-files", UUID.randomUUID().toString()).toString();

    /**
     * 单图上限压到 4KB。真实默认是 20MB，而造一个 20MB 的数组只为触发一次 413 不值得；
     * 上限的**取值来源**（配置而非硬编码）正是这条用例要验证的东西，数值大小不影响结论。
     */
    private static final int MAX_IMAGE_BYTES = 4096;

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

    // ── 底图图片（契约 uploadMapImage / getMapImage）─────────────────────────

    @Test
    @DisplayName("上传图片：按真实图片头回填宽高，落盘在 maps/<mapId>/ 下且状态是「已上传」")
    void uploadImage_backfillsSizeFromRealHeaderAndLandsInTheMapDirectory() {
        UUID id = insertMap("待上传底图", null, null);

        MapView uploaded = ledger.uploadImage(id, png(4000, 3000, 64));

        String path = uploaded.map().imagePath();
        assertThat(path).startsWith("maps/" + id + "/").endsWith(".png");
        assertThat(uploaded.map().imageWidth()).isEqualTo(4000);
        assertThat(uploaded.map().imageHeight()).isEqualTo(3000);
        assertThat(store.exists(path)).isTrue();
        // 落盘的必须是上传的原字节，不做任何转码（D09-④）
        assertThat(store.read(path))
                .hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(png(4000, 3000, 64)));
    }

    @Test
    @DisplayName("换图即换新文件名并删旧文件：旧名会把上一次的扩展名带错，也留着占磁盘")
    void uploadImage_replaceDropsThePreviousFile() {
        UUID id = insertMap("换图底图", null, null);
        String first = ledger.uploadImage(id, png(4000, 3000, 32)).map().imagePath();

        String second = ledger.uploadImage(id, png(800, 600, 32)).map().imagePath();

        assertThat(second).isNotEqualTo(first);
        assertThat(store.exists(first)).isFalse();
        assertThat(store.exists(second)).isTrue();
        assertThat(ledger.get(id).map().imageWidth()).isEqualTo(800);
    }

    @Test
    @DisplayName("不是白名单内的真实图片 → UNSUPPORTED_MEDIA_TYPE，且不能把行改成「有图」")
    void uploadImage_nonImage_isUnsupportedMediaType() {
        UUID id = insertMap("非图片", null, null);

        assertCode(() -> ledger.uploadImage(id, "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8)),
                ApiErrorCode.UNSUPPORTED_MEDIA_TYPE);

        assertThat(maps.findById(id).orElseThrow().imagePath()).isNull();
        assertThat(ledger.get(id).map().imageWidth()).isNull();
    }

    @Test
    @DisplayName("超过单图上限 → PAYLOAD_TOO_LARGE：上限取自配置，不是写死的 20MB")
    void uploadImage_overMaxBytes_isPayloadTooLarge() {
        UUID id = insertMap("超大图", null, null);

        assertCode(() -> ledger.uploadImage(id, png(4000, 3000, MAX_IMAGE_BYTES + 1)),
                ApiErrorCode.PAYLOAD_TOO_LARGE);

        assertThat(maps.findById(id).orElseThrow().imagePath()).isNull();
    }

    @Test
    @DisplayName("读图片：没上传过与文件已丢折叠成同一个 MAP_IMAGE_MISSING，格式按真实字节回")
    void readImage_missingOrPresent() {
        UUID neverUploaded = insertMap("还没上传", null, null);
        assertCode(() -> ledger.readImage(neverUploaded), ApiErrorCode.MAP_IMAGE_MISSING);

        // 行里有 image_path 但文件不在磁盘（手工清理 / 跨机搬迁漏拷）：同样是「现在取不到」
        UUID staleRow = insertMap("行在文件不在", "maps/" + UUID.randomUUID() + "/gone.png", null);
        assertCode(() -> ledger.readImage(staleRow), ApiErrorCode.MAP_IMAGE_MISSING);

        UUID id = insertMap("读图片", null, null);
        ledger.uploadImage(id, png(4000, 3000, 64));

        MapLedgerService.MapImage image = ledger.readImage(id);
        assertThat(image.format()).isEqualTo(ImageHeaders.ImageFormat.PNG);
        assertThat(image.content()).isEqualTo(png(4000, 3000, 64));
    }

    @Test
    @DisplayName("删底图连带删图片：库行是唯一真相，留下没人引用的图片文件纯属占磁盘")
    void delete_removesTheImageFileToo() {
        UUID id = insertMap("删图连带", null, null);
        String path = ledger.uploadImage(id, png(4000, 3000, 64)).map().imagePath();

        ledger.delete(id);

        assertThat(store.exists(path)).isFalse();
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    /**
     * 图片相关的两条配置只在类别级覆盖，不动共享的测试配置：{@code TestCoreApplication} 的
     * 数据源守卫（只允许 {@code scds_next_test}）不该因为图片用例而放宽。
     */
    @DynamicPropertySource
    static void fileStorage(DynamicPropertyRegistry registry) {
        registry.add("scds.files.root", () -> FILES_ROOT);
        registry.add("scds.files.max-image-bytes", () -> MAX_IMAGE_BYTES);
    }

    private UUID insertMap(String name, String imagePath, String uwbMeta) {
        UUID id = UuidV7.generate();
        maps.insert(new BaseMap(id, name, MapType.INDOOR, imagePath,
                imagePath == null ? null : 4000, imagePath == null ? null : 3000,
                imagePath == null ? null : "备注", uwbMeta, null, null));
        return id;
    }

    /**
     * 造一段「PNG 头合法、总长可指定」的字节。
     *
     * <p>{@link ImageHeaders} 只看文件头、不解码像素，故这里只需要 24 字节的头正确（签名 + IHDR + 大端宽高），
     * 其余补零——用真实编码器造一张 4000×3000 的位图要 36MB 堆内存，本用例既不需要也不该为它付这个代价。</p>
     */
    private static byte[] png(int width, int height, int totalLength) {
        byte[] bytes = new byte[Math.max(totalLength, 24)];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        System.arraycopy("IHDR".getBytes(StandardCharsets.US_ASCII), 0, bytes, 12, 4);
        beInt(bytes, 16, width);
        beInt(bytes, 20, height);
        return bytes;
    }

    private static void beInt(byte[] b, int offset, int value) {
        b[offset] = (byte) (value >>> 24);
        b[offset + 1] = (byte) (value >>> 16);
        b[offset + 2] = (byte) (value >>> 8);
        b[offset + 3] = (byte) value;
    }

    /**
     * 开一场绑到 {@code mapId} 的演习。库层用 {@code running_flag} 唯一索引保证「至多一场进行中」，
     * 所以测试库若残留进行中场次，先在本用例事务内把它停掉——用例结束整体回滚，
     * 库里那行原样不变。
     */
    private void startRunningExercise(UUID mapId) {
        exercises.findRunning().ifPresent(running ->
                exercises.markEnded(running.id(), Instant.now(), running.hitCount(), running.deadCount(),
                        ExerciseEndReason.NORMAL));
        exercises.insert(new Exercise(UuidV7.generate(), UuidV7.generate(), "进行中的想定", mapId,
                BattleMode.TRAIN, Instant.now(), null, 3, 0, 0, null, null));
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ApiErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
