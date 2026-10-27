package com.dualx.core.repository;

import com.dualx.core.domain.BaseMap;
import com.dualx.core.domain.MapType;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.SqlTime;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class BaseMapRepositoryIT {

    @Autowired
    private BaseMapRepository maps;

    /**
     * 只为读 {@code calibrated_at}：该列刻意不进 {@link BaseMapRepository#COLUMNS}、
     * 也不落到 {@code BaseMap} 上（见仓储类注释），所以验证「写进去了没」只能直查库。
     */
    @Autowired
    private JdbcClient jdbc;

    /**
     * 契约 UwbMeta 的冻结结构，逐字保存、逐字读回（uwb_meta 是 TEXT，库不做任何改写）。
     *
     * <p>取值即 {@code PROTOCOL.md} §6.6 的定稿示例：**整数像素坐标**。仓储层不校验坐标范围
     * （那是 {@code MapLedgerService.saveUwbMeta} 的职责），本常量只保证是一份结构合法的冻结 JSON。</p>
     */
    private static final String UWB_META = """
            {"bs1":{"xPx":120,"yPx":240},"bs2":{"xPx":1980,"yPx":245},\
            "bs3":{"xPx":118,"yPx":1560},"bs4":{"xPx":1975,"yPx":1565},\
            "bs5":{"xPx":1050,"yPx":240},"bs6":null,"bs7":null,"bs8":null,\
            "realLengthCm":3000,"realWidthCm":1800}""";

    @Test
    void insertThenFindById_roundTripsEveryColumn() {
        UUID id = UuidV7.generate();
        BaseMap drafted = new BaseMap(id, "营区底图", MapType.INDOOR, "maps/abc.png",
                4000, 3000, "一营区", UWB_META, null, null);

        assertThat(maps.insert(drafted)).isEqualTo(1);

        BaseMap loaded = maps.findById(id).orElseThrow();
        assertThat(loaded.name()).isEqualTo("营区底图");
        assertThat(loaded.mapType()).isEqualTo(MapType.INDOOR);
        assertThat(loaded.imagePath()).isEqualTo("maps/abc.png");
        assertThat(loaded.imageWidth()).isEqualTo(4000);
        assertThat(loaded.imageHeight()).isEqualTo(3000);
        assertThat(loaded.remark()).isEqualTo("一营区");
        assertThat(loaded.uwbMeta()).isEqualTo(UWB_META);
        assertThat(loaded.createdAt()).isNotNull();
        assertThat(loaded.updatedAt()).isNotNull();
    }

    @Test
    void optionalColumns_mayAllBeNull() {
        UUID id = UuidV7.generate();
        assertThat(maps.insert(new BaseMap(id, "露天靶场", MapType.OUTDOOR,
                null, null, null, null, null, null, null))).isEqualTo(1);

        BaseMap loaded = maps.findById(id).orElseThrow();
        assertThat(loaded.imagePath()).isNull();
        assertThat(loaded.imageWidth()).isNull();
        assertThat(loaded.imageHeight()).isNull();
        assertThat(loaded.remark()).isNull();
        assertThat(loaded.uwbMeta()).isNull();
    }

    @Test
    void findByType_filtersByEnum() {
        UUID indoorId = UuidV7.generate();
        UUID outdoorId = UuidV7.generate();
        maps.insert(new BaseMap(indoorId, "室内图", MapType.INDOOR, null, null, null, null, null, null, null));
        maps.insert(new BaseMap(outdoorId, "室外图", MapType.OUTDOOR, null, null, null, null, null, null, null));

        List<BaseMap> indoor = maps.findByType(MapType.INDOOR, PageRequest.of(1, 200));

        assertThat(indoor).extracting(BaseMap::id).contains(indoorId).doesNotContain(outdoorId);
        assertThat(maps.countByType(MapType.INDOOR)).isEqualTo(1);
        assertThat(maps.count()).isEqualTo(2);
    }

    @Test
    void findByType_returnsEmptyWhenNoMatch() {
        UUID id = UuidV7.generate();
        maps.insert(new BaseMap(id, "仅室内", MapType.INDOOR, null, null, null, null, null, null, null));

        assertThat(maps.findByType(MapType.OUTDOOR, PageRequest.of(1, 10)))
                .extracting(BaseMap::id).doesNotContain(id);
        assertThat(maps.countByType(MapType.OUTDOOR)).isZero();
    }

    @Test
    void findById_returnsEmptyWhenAbsent() {
        UUID id = UuidV7.generate();
        assertThat(maps.findById(id)).isEmpty();
        assertThat(maps.existsById(id)).isFalse();
    }

    @Test
    void duplicateId_isRejectedByPrimaryKey() {
        UUID id = UuidV7.generate();
        maps.insert(new BaseMap(id, "甲图", MapType.INDOOR, null, null, null, null, null, null, null));

        assertThatThrownBy(() -> maps
                .insert(new BaseMap(id, "乙图", MapType.OUTDOOR, null, null, null, null, null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void update_changesMutableColumnsOnly() {
        UUID id = UuidV7.generate();
        maps.insert(new BaseMap(id, "旧名", MapType.OUTDOOR, null, null, null, null, null, null, null));

        assertThat(maps.update(new BaseMap(id, "新名", MapType.INDOOR, "maps/x.png",
                800, 600, "改过", UWB_META, null, null))).isEqualTo(1);

        BaseMap loaded = maps.findById(id).orElseThrow();
        assertThat(loaded.name()).isEqualTo("新名");
        assertThat(loaded.mapType()).isEqualTo(MapType.INDOOR);
        assertThat(loaded.imagePath()).isEqualTo("maps/x.png");
        assertThat(loaded.imageWidth()).isEqualTo(800);
        assertThat(loaded.imageHeight()).isEqualTo(600);
        assertThat(loaded.remark()).isEqualTo("改过");
        assertThat(loaded.uwbMeta()).isEqualTo(UWB_META);
    }

    /**
     * {@code calibrated_at}（V11）的两个关键性质：新建即空、只由 {@link BaseMapRepository#markCalibrated}
     * 前进——改名换型的 {@link BaseMapRepository#update} 不许把它带走。
     */
    @Test
    void markCalibrated_stampsOnlyItsOwnColumn() {
        UUID id = UuidV7.generate();
        maps.insert(new BaseMap(id, "待标定图", MapType.INDOOR, null, null, null, null, null, null, null));
        assertThat(calibratedAt(id)).isNull();

        Instant stamped = Instant.parse("2026-10-27T08:00:00Z");
        assertThat(maps.markCalibrated(id, stamped)).isEqualTo(1);

        assertThat(calibratedAt(id)).isEqualTo(stamped);
        maps.update(new BaseMap(id, "改过名", MapType.OUTDOOR, "maps/y.png",
                800, 600, null, UWB_META, null, null));
        assertThat(calibratedAt(id)).isEqualTo(stamped);
    }

    @Test
    void markCalibrated_unknownIdAffectsNoRow() {
        assertThat(maps.markCalibrated(UuidV7.generate(), Instant.parse("2026-10-27T08:00:00Z"))).isZero();
    }

    /**
     * 直读 {@code calibrated_at}（UTC 挂钟值 → Instant）。
     *
     * <p>用 {@code list()} 而不是 {@code optional()}：列值本身可以是 {@code NULL}，
     * 而 {@code optional()} 会把「有行、值为 NULL」折叠成 {@code Optional.empty()}，
     * 与「没有这一行」混在一起，再拿 {@code orElseThrow} 就直接抛异常了。</p>
     */
    private Instant calibratedAt(UUID id) {
        List<LocalDateTime> stored = jdbc.sql("SELECT calibrated_at FROM t_map WHERE id = :id")
                .param("id", id.toString())
                .query(LocalDateTime.class)
                .list();
        assertThat(stored).hasSize(1);
        return SqlTime.fromDb(stored.get(0));
    }

    @Test
    void deleteById_removesRow() {
        UUID id = UuidV7.generate();
        maps.insert(new BaseMap(id, "待删图", MapType.INDOOR, null, null, null, null, null, null, null));

        assertThat(maps.deleteById(id)).isEqualTo(1);
        assertThat(maps.findById(id)).isEmpty();
        assertThat(maps.deleteById(id)).isZero();
    }

    @Test
    void findAll_ordersNewestFirstAndPaginates() {
        UUID first = UuidV7.generate();
        UUID second = UuidV7.generate();
        UUID third = UuidV7.generate();
        maps.insert(new BaseMap(first, "it_map_pg_1", MapType.INDOOR, null, null, null, null, null, null, null));
        maps.insert(new BaseMap(second, "it_map_pg_2", MapType.INDOOR, null, null, null, null, null, null, null));
        maps.insert(new BaseMap(third, "it_map_pg_3", MapType.INDOOR, null, null, null, null, null, null, null));

        List<BaseMap> page1 = maps.findAll(PageRequest.of(1, 2));
        List<BaseMap> page2 = maps.findAll(PageRequest.of(2, 2));

        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(1);
        // 新建时间倒序：page1 内部不递增
        assertThat(page1).extracting(BaseMap::createdAt).isSortedAccordingTo(Comparator.reverseOrder());
        // offset 生效：两页不重叠
        assertThat(page1).extracting(BaseMap::id)
                .doesNotContainAnyElementsOf(page2.stream().map(BaseMap::id).toList());
        assertThat(page1).extracting(BaseMap::id).containsAnyOf(first, second, third);
    }
}
