package com.dualx.core.repository;

import com.dualx.core.domain.MapFloor;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class MapFloorRepositoryIT {

    @Autowired
    private MapFloorRepository floors;

    private static MapFloor floor(UUID mapId, int floorNum, String points) {
        return new MapFloor(0L, mapId, floorNum, points, null, null);
    }

    @Test
    void insertThenFind_roundTripsNormalizedJson() {
        UUID mapId = UuidV7.generate();
        // 刻意用与库输出不一致的键序，以证明规范化确实发生在库侧
        assertThat(floors.insert(floor(mapId, 1, """
                [{"posNum":1,"xPct":0.25,"yPct":0.5,"label":"A"}]"""))).isEqualTo(1);

        MapFloor loaded = floors.findByMapIdAndFloorNum(mapId, 1).orElseThrow();
        assertThat(loaded.id()).isPositive();
        assertThat(loaded.mapId()).isEqualTo(mapId);
        assertThat(loaded.floorNum()).isEqualTo(1);
        // MySQL 的 JSON 列会把对象键按「键长优先、再按字节序」重排，并统一为 ": " / ", " 分隔
        assertThat(loaded.points()).isEqualTo("""
                [{"xPct": 0.25, "yPct": 0.5, "label": "A", "posNum": 1}]""");
        assertThat(loaded.createdAt()).isNotNull();
        assertThat(loaded.updatedAt()).isNotNull();
    }

    @Test
    void points_mayBeNull() {
        UUID mapId = UuidV7.generate();
        assertThat(floors.insert(floor(mapId, 1, null))).isEqualTo(1);

        assertThat(floors.findByMapIdAndFloorNum(mapId, 1).orElseThrow().points()).isNull();
    }

    @Test
    void findByMapId_ordersByFloorNum() {
        UUID mapId = UuidV7.generate();
        floors.insert(floor(mapId, 3, "[{\"posNum\":3,\"xPct\":0.3,\"yPct\":0.3}]"));
        floors.insert(floor(mapId, 1, "[{\"posNum\":1,\"xPct\":0.1,\"yPct\":0.1}]"));
        floors.insert(floor(mapId, 2, "[{\"posNum\":2,\"xPct\":0.2,\"yPct\":0.2}]"));

        assertThat(floors.findByMapId(mapId)).extracting(MapFloor::floorNum)
                .containsExactly(1, 2, 3);
        assertThat(floors.countByMapId(mapId)).isEqualTo(3);
    }

    @Test
    void findByMapIdAndFloorNum_returnsEmptyWhenAbsent() {
        assertThat(floors.findByMapIdAndFloorNum(UuidV7.generate(), 1)).isEmpty();
    }

    @Test
    void duplicateMapIdFloorNum_isRejectedByUniqueKey() {
        UUID mapId = UuidV7.generate();
        floors.insert(floor(mapId, 1, null));

        assertThatThrownBy(() -> floors.insert(floor(mapId, 1, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameFloorNumOnDifferentMaps_isAllowed() {
        UUID mapA = UuidV7.generate();
        UUID mapB = UuidV7.generate();

        assertThat(floors.insert(floor(mapA, 1, null))).isEqualTo(1);
        assertThat(floors.insert(floor(mapB, 1, null))).isEqualTo(1);

        assertThat(floors.findByMapIdAndFloorNum(mapA, 1)).isPresent();
        assertThat(floors.findByMapIdAndFloorNum(mapB, 1)).isPresent();
    }

    @Test
    void replaceByMapId_swapsWholeSet() {
        UUID mapId = UuidV7.generate();
        floors.insert(floor(mapId, 1, null));
        floors.insert(floor(mapId, 2, null));
        long oldId = floors.findByMapIdAndFloorNum(mapId, 1).orElseThrow().id();

        int written = floors.replaceByMapId(mapId, List.of(
                floor(mapId, 5, "[{\"posNum\":5,\"xPct\":0.5,\"yPct\":0.5}]"),
                floor(mapId, 6, null),
                floor(mapId, 7, null)));

        assertThat(written).isEqualTo(3);
        assertThat(floors.countByMapId(mapId)).isEqualTo(3);
        assertThat(floors.findByMapId(mapId)).extracting(MapFloor::floorNum)
                .containsExactly(5, 6, 7);
        // 先删后插：旧行的代理主键不复用
        assertThat(floors.findByMapIdAndFloorNum(mapId, 5).orElseThrow().id()).isNotEqualTo(oldId);
    }

    @Test
    void replaceByMapId_withEmptyList_clearsTheMap() {
        UUID mapId = UuidV7.generate();
        floors.insert(floor(mapId, 1, null));

        assertThat(floors.replaceByMapId(mapId, List.of())).isZero();
        assertThat(floors.findByMapId(mapId)).isEmpty();
    }

    @Test
    void replaceByMapId_leavesOtherMapsUntouched() {
        UUID target = UuidV7.generate();
        UUID other = UuidV7.generate();
        floors.insert(floor(target, 1, null));
        floors.insert(floor(other, 1, null));

        floors.replaceByMapId(target, List.of(floor(target, 9, null)));

        assertThat(floors.findByMapId(target)).extracting(MapFloor::floorNum).containsExactly(9);
        assertThat(floors.findByMapId(other)).extracting(MapFloor::floorNum).containsExactly(1);
    }

    @Test
    void deleteByMapId_removesAllFloorsOfThatMap() {
        UUID mapId = UuidV7.generate();
        floors.insert(floor(mapId, 1, null));
        floors.insert(floor(mapId, 2, null));

        assertThat(floors.deleteByMapId(mapId)).isEqualTo(2);
        assertThat(floors.findByMapId(mapId)).isEmpty();
        assertThat(floors.deleteByMapId(mapId)).isZero();
    }
}
