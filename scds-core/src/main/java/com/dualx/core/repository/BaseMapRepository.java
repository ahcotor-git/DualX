package com.dualx.core.repository;

import com.dualx.core.domain.BaseMap;
import com.dualx.core.domain.MapType;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.SqlTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 底图仓储（{@code t_map}）。列表按「新建时间倒序」返回，与前端底图列表一致。
 *
 * <p>{@code uwb_meta} 以原始 JSON 文本存取：结构由契约冻结，解析属 API 层职责。
 *
 * <p><b>{@code calibrated_at} 不进 {@link #COLUMNS}，改由 {@link #findCalibratedAt} 批量读</b>：
 * 它是可空的「标定时刻」证据列，只在 {@code MapDetail.calibratedAt} 上露面。并进 {@code COLUMNS}
 * 就得给领域记录 {@code BaseMap} 加第 11 个分量，全仓二十多处 {@code new BaseMap(...)} 都要跟着动；
 * 而在列表页逐行补一次查询又是 N+1。故取第三条路：行投影保持不动，额外开销固定为一条 SQL。
 */
@Repository
public class BaseMapRepository {

    private static final String COLUMNS =
            "id, name, map_type, image_path, image_width, image_height, remark, uwb_meta, "
                    + "created_at, updated_at";

    /** 列表排序：新建时间倒序；同毫秒时用 UUIDv7 主键兜底保证确定性。 */
    private static final String ORDER = " ORDER BY created_at DESC, id DESC";

    private final JdbcClient jdbc;

    public BaseMapRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BaseMap> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_map WHERE id = :id")
                .param("id", id.toString())
                .query(BaseMapRepository::map)
                .optional();
    }

    public List<BaseMap> findAll(PageRequest request) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_map" + ORDER + " LIMIT :limit OFFSET :offset")
                .param("limit", request.size())
                .param("offset", request.offset())
                .query(BaseMapRepository::map)
                .list();
    }

    public List<BaseMap> findByType(MapType mapType, PageRequest request) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_map WHERE map_type = :mapType"
                        + ORDER + " LIMIT :limit OFFSET :offset")
                .param("mapType", mapType.name())
                .param("limit", request.size())
                .param("offset", request.offset())
                .query(BaseMapRepository::map)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM t_map").query(Long.class).single();
    }

    public long countByType(MapType mapType) {
        return jdbc.sql("SELECT COUNT(*) FROM t_map WHERE map_type = :mapType")
                .param("mapType", mapType.name())
                .query(Long.class)
                .single();
    }

    public boolean existsById(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM t_map WHERE id = :id)")
                .param("id", id.toString())
                .query(Boolean.class)
                .single();
    }

    /** @return 影响行数，正常为 1 */
    public int insert(BaseMap map) {
        return jdbc.sql("""
                        INSERT INTO t_map (id, name, map_type, image_path, image_width, image_height,
                                           remark, uwb_meta)
                        VALUES (:id, :name, :mapType, :imagePath, :imageWidth, :imageHeight,
                                :remark, :uwbMeta)
                        """)
                .param("id", map.id().toString())
                .param("name", map.name())
                .param("mapType", map.mapType().name())
                .param("imagePath", map.imagePath())
                .param("imageWidth", map.imageWidth())
                .param("imageHeight", map.imageHeight())
                .param("remark", map.remark())
                .param("uwbMeta", map.uwbMeta())
                .update();
    }

    /** 更新可变字段；{@code id} / {@code created_at} 不可改。 */
    public int update(BaseMap map) {
        return jdbc.sql("""
                        UPDATE t_map
                           SET name = :name,
                               map_type = :mapType,
                               image_path = :imagePath,
                               image_width = :imageWidth,
                               image_height = :imageHeight,
                               remark = :remark,
                               uwb_meta = :uwbMeta
                         WHERE id = :id
                        """)
                .param("id", map.id().toString())
                .param("name", map.name())
                .param("mapType", map.mapType().name())
                .param("imagePath", map.imagePath())
                .param("imageWidth", map.imageWidth())
                .param("imageHeight", map.imageHeight())
                .param("remark", map.remark())
                .param("uwbMeta", map.uwbMeta())
                .update();
    }

    /**
     * 记录标定生效时刻；只改 {@code calibrated_at} 一列。
     *
     * <p>与 {@link #update} 分开而不是并进去，是因为语义不同：改名换型不该刷新标定时刻，
     * 而 {@code updated_at} 被 {@code ON UPDATE} 带着走，表达不了「只改标定时刻」。
     * 该列只在标定写入成功时前进（{@code MapLedgerService.saveUwbMeta}）。</p>
     *
     * @return 影响行数，正常为 1
     */
    public int markCalibrated(UUID id, Instant calibratedAt) {
        return jdbc.sql("UPDATE t_map SET calibrated_at = :calibratedAt WHERE id = :id")
                .param("calibratedAt", SqlTime.toDb(calibratedAt))
                .param("id", id.toString())
                .update();
    }

    /**
     * 一批底图的标定时刻（{@code calibrated_at}），供 {@code MapDetail.calibratedAt} 组装。
     *
     * <p><b>刻意成批</b>：列表页逐行单独回库就是 N+1，而每页最多 200 行（契约 {@code SizeParam}）。
     * 入参为空时不发查询、直接回空表——空 {@code IN ()} 是语法错误。</p>
     *
     * <p><b>从未标定的行不出现在结果里</b>（{@code calibrated_at IS NULL} 被滤掉），
     * 由调用方补 {@code null}，与 {@link PlanRosterRepository#countByPlanIds} 同一取向。
     * 这也覆盖了 V11 之前就已标定的存量行：它们的 {@code uwb_meta} 齐备但时刻为空，
     * 属于「当时没记」，不是「没标过」。</p>
     *
     * @param ids 底图 id；空集合返回空表
     * @return mapId → 标定时刻（UTC）；键缺失即该行从未标定
     */
    public Map<UUID, Instant> findCalibratedAt(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT id, calibrated_at FROM t_map"
                        + " WHERE id IN (:ids) AND calibrated_at IS NOT NULL")
                .param("ids", ids.stream().map(UUID::toString).toList())
                .query((rs, rowNum) -> Map.entry(UUID.fromString(rs.getString("id")),
                        SqlTime.fromDb(rs.getObject("calibrated_at", LocalDateTime.class))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public int deleteById(UUID id) {
        return jdbc.sql("DELETE FROM t_map WHERE id = :id")
                .param("id", id.toString())
                .update();
    }

    private static BaseMap map(ResultSet rs, int rowNum) throws SQLException {
        return new BaseMap(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                MapType.valueOf(rs.getString("map_type")),
                rs.getString("image_path"),
                rs.getObject("image_width", Integer.class),
                rs.getObject("image_height", Integer.class),
                rs.getString("remark"),
                rs.getString("uwb_meta"),
                SqlTime.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("updated_at", LocalDateTime.class)));
    }
}
