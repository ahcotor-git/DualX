package com.dualx.core.repository;

import com.dualx.core.domain.MapFloor;
import com.dualx.core.support.SqlTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 楼层点位表仓储（{@code t_map_floor}）。{@code id} 是自增代理键，
 * 业务唯一性由 {@code uk_map_floor (map_id, floor_num)} 保证。
 *
 * <p>契约的 {@code replaceFloorPoints} 是整体替换，故对外只提供
 * {@link #replaceByMapId} —— 不做逐行 diff。
 */
@Repository
public class MapFloorRepository {

    private static final String COLUMNS = "id, map_id, floor_num, points, created_at, updated_at";

    private final JdbcClient jdbc;

    public MapFloorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<MapFloor> findByMapId(UUID mapId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_map_floor WHERE map_id = :mapId ORDER BY floor_num")
                .param("mapId", mapId.toString())
                .query(MapFloorRepository::map)
                .list();
    }

    public Optional<MapFloor> findByMapIdAndFloorNum(UUID mapId, int floorNum) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_map_floor WHERE map_id = :mapId AND floor_num = :floorNum")
                .param("mapId", mapId.toString())
                .param("floorNum", floorNum)
                .query(MapFloorRepository::map)
                .optional();
    }

    public long countByMapId(UUID mapId) {
        return jdbc.sql("SELECT COUNT(*) FROM t_map_floor WHERE map_id = :mapId")
                .param("mapId", mapId.toString())
                .query(Long.class)
                .single();
    }

    /** @return 影响行数，正常为 1 */
    public int insert(MapFloor floor) {
        return jdbc.sql("""
                        INSERT INTO t_map_floor (map_id, floor_num, points)
                        VALUES (:mapId, :floorNum, :points)
                        """)
                .param("mapId", floor.mapId().toString())
                .param("floorNum", floor.floorNum())
                .param("points", floor.points())
                .update();
    }

    /**
     * 整体替换某张底图的楼层点位表（先删后插），与「编成整体替换」同一策略：
     * 逐行 diff 会让调用方承担状态比对，而一个楼层的点位表很小，全量重写更简单可靠。
     *
     * @return 写回的行数
     */
    @Transactional
    public int replaceByMapId(UUID mapId, List<MapFloor> floors) {
        deleteByMapId(mapId);
        int inserted = 0;
        for (MapFloor floor : floors) {
            inserted += jdbc.sql("""
                            INSERT INTO t_map_floor (map_id, floor_num, points)
                            VALUES (:mapId, :floorNum, :points)
                            """)
                    .param("mapId", mapId.toString())
                    .param("floorNum", floor.floorNum())
                    .param("points", floor.points())
                    .update();
        }
        return inserted;
    }

    public int deleteByMapId(UUID mapId) {
        return jdbc.sql("DELETE FROM t_map_floor WHERE map_id = :mapId")
                .param("mapId", mapId.toString())
                .update();
    }

    private static MapFloor map(ResultSet rs, int rowNum) throws SQLException {
        return new MapFloor(
                rs.getLong("id"),
                UUID.fromString(rs.getString("map_id")),
                rs.getInt("floor_num"),
                rs.getString("points"),
                SqlTime.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("updated_at", LocalDateTime.class)));
    }
}
