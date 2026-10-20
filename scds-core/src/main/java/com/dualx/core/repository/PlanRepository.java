package com.dualx.core.repository;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Plan;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.SqlTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 想定仓储（{@code t_plan}）。列表按「最近改动倒序」返回。
 *
 * <p>编成在 {@code t_plan_roster}，删除想定时**不会**自动清除——无外键，一致性由应用层
 * 保证，服务层需显式调用 {@link PlanRosterRepository#deleteByPlanId}。
 */
@Repository
public class PlanRepository {

    private static final String COLUMNS = "id, name, map_id, battle_mode, remark, created_at, updated_at";

    /** 列表排序：最近改动倒序；同毫秒时用 UUIDv7 主键兜底保证确定性。 */
    private static final String ORDER = " ORDER BY updated_at DESC, id DESC";

    private final JdbcClient jdbc;

    public PlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Plan> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_plan WHERE id = :id")
                .param("id", id.toString())
                .query(PlanRepository::map)
                .optional();
    }

    public List<Plan> findAll(PageRequest request) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_plan" + ORDER + " LIMIT :limit OFFSET :offset")
                .param("limit", request.size())
                .param("offset", request.offset())
                .query(PlanRepository::map)
                .list();
    }

    /** 按想定名模糊搜索（契约 {@code listPlans} 的 keyword 参数）。 */
    public List<Plan> findByKeyword(String keyword, PageRequest request) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_plan WHERE name LIKE :keyword ESCAPE '\\\\'"
                        + ORDER + " LIMIT :limit OFFSET :offset")
                .param("keyword", likePattern(keyword))
                .param("limit", request.size())
                .param("offset", request.offset())
                .query(PlanRepository::map)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM t_plan").query(Long.class).single();
    }

    public long countByKeyword(String keyword) {
        return jdbc.sql("SELECT COUNT(*) FROM t_plan WHERE name LIKE :keyword ESCAPE '\\\\'")
                .param("keyword", likePattern(keyword))
                .query(Long.class)
                .single();
    }

    public boolean existsById(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM t_plan WHERE id = :id)")
                .param("id", id.toString())
                .query(Boolean.class)
                .single();
    }

    /** @return 影响行数，正常为 1 */
    public int insert(Plan plan) {
        return jdbc.sql("""
                        INSERT INTO t_plan (id, name, map_id, battle_mode, remark)
                        VALUES (:id, :name, :mapId, :battleMode, :remark)
                        """)
                .param("id", plan.id().toString())
                .param("name", plan.name())
                .param("mapId", mapId(plan))
                .param("battleMode", plan.battleMode().name())
                .param("remark", plan.remark())
                .update();
    }

    /** 更新可变字段；{@code id} / {@code created_at} 不可改。 */
    public int update(Plan plan) {
        return jdbc.sql("""
                        UPDATE t_plan
                           SET name = :name,
                               map_id = :mapId,
                               battle_mode = :battleMode,
                               remark = :remark
                         WHERE id = :id
                        """)
                .param("id", plan.id().toString())
                .param("name", plan.name())
                .param("mapId", mapId(plan))
                .param("battleMode", plan.battleMode().name())
                .param("remark", plan.remark())
                .update();
    }

    public int deleteById(UUID id) {
        return jdbc.sql("DELETE FROM t_plan WHERE id = :id")
                .param("id", id.toString())
                .update();
    }

    /** keyword 来自用户输入，须转义 LIKE 通配符，否则输入一个 {@code %} 就会命中全表。 */
    private static String likePattern(String keyword) {
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static String mapId(Plan plan) {
        return plan.mapId() == null ? null : plan.mapId().toString();
    }

    private static Plan map(ResultSet rs, int rowNum) throws SQLException {
        String mapId = rs.getString("map_id");
        return new Plan(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                mapId == null ? null : UUID.fromString(mapId),
                BattleMode.valueOf(rs.getString("battle_mode")),
                rs.getString("remark"),
                SqlTime.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("updated_at", LocalDateTime.class)));
    }
}
