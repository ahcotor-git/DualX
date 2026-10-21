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
 * <p>写入有两条口径：契约的 {@code replaceFloorPoints} 是**单楼层**整体替换（路径里带
 * {@code floorNum}），走 {@link #upsertByMapIdAndFloorNum}；{@link #replaceByMapId} 是
 * 整图置换（先删全图再插），留给「一次性重灌某张图的全部楼层」这类运维场景。
 * 两者都不做逐行 diff——一个楼层的点位表很小，全量重写更简单可靠。</p>
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
     * 写入某一楼层的点位表：有则改、无则建（契约 {@code replaceFloorPoints} 的单楼层语义）。
     *
     * <p>借 {@code uk_map_floor (map_id, floor_num)} 的唯一键一次说完「建或改」，调用方不必
     * 先查再判——先查后写会多一次往返，且在并发下仍有窗口。别的楼层不受影响。</p>
     *
     * @return MySQL 语义下的影响行数：新建为 1、命中唯一键改行为 2
     */
    public int upsertByMapIdAndFloorNum(UUID mapId, int floorNum, String points) {
        return jdbc.sql("""
                        INSERT INTO t_map_floor (map_id, floor_num, points)
                        VALUES (:mapId, :floorNum, :points)
                        ON DUPLICATE KEY UPDATE points = :points
                        """)
                .param("mapId", mapId.toString())
                .param("floorNum", floorNum)
                .param("points", points)
                .update();
    }

    /**
     * 整体替换某张底图的楼层点位表（先删后插），一次重灌该图的全部楼层。
     *
     * <p>「先删后插」会让被替换掉的楼层行不复用代理主键。本方法刻意不走
     * {@link #upsertByMapIdAndFloorNum}：它的语义是「这张图现在只有这些楼层」，
     * 少传的楼层就该消失。</p>
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
