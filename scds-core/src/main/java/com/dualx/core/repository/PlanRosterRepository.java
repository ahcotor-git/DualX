package com.dualx.core.repository;

import com.dualx.core.domain.PollMode;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.support.SqlTime;
import com.dualx.protocol.Team;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 想定编成仓储（{@code t_plan_roster}）。{@code id} 是自增代理键，
 * 业务唯一性由 {@code uk_plan_roster (plan_id, vest_num)} 保证。
 *
 * <p>契约的 {@code PUT /roster} 是整体替换，故对外只提供 {@link #replaceByPlanId}
 * —— 区间式/列表式两种导入写法由 API 层展开后再落库，库里只保留「逐人一行」一种形态。
 *
 * <p>可空性：{@code hp} / {@code ammo1} / {@code ammo2} / {@code mode} 在库里有默认值，
 * 但契约同样给了默认值（100 / 30 / 30 / AUTO），由 API 层补齐后显式传入，
 * 避免「库默认」与「契约默认」两个来源。
 */
@Repository
public class PlanRosterRepository {

    private static final String COLUMNS =
            "id, plan_id, vest_num, name, team, hp, ammo1, ammo2, mode, created_at, updated_at";

    private final JdbcClient jdbc;

    public PlanRosterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 返回某想定的整份编成，按「队伍 → 马甲编号」排序，保证顺序稳定。 */
    public List<RosterEntry> findByPlanId(UUID planId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_plan_roster WHERE plan_id = :planId"
                        + " ORDER BY team, vest_num")
                .param("planId", planId.toString())
                .query(PlanRosterRepository::map)
                .list();
    }

    public long countByPlanId(UUID planId) {
        return jdbc.sql("SELECT COUNT(*) FROM t_plan_roster WHERE plan_id = :planId")
                .param("planId", planId.toString())
                .query(Long.class)
                .single();
    }

    /**
     * 一次取回多份想定的人数，供想定列表组装 {@code rosterCount}。
     *
     * <p>刻意成批：列表页逐行 {@code COUNT} 就是 N+1，而每页最多 200 行（契约
     * {@code SizeParam}）。入参为空时不发查询，直接回空表——空 {@code IN ()} 是语法错误。
     *
     * @return planId → 人数；没有编成的想定**不出现在结果里**，由调用方补 0
     */
    public Map<UUID, Long> countByPlanIds(List<UUID> planIds) {
        if (planIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT plan_id, COUNT(*) AS roster_count FROM t_plan_roster"
                        + " WHERE plan_id IN (:planIds) GROUP BY plan_id")
                .param("planIds", planIds.stream().map(UUID::toString).toList())
                .query((rs, rowNum) -> Map.entry(UUID.fromString(rs.getString("plan_id")),
                        rs.getLong("roster_count")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** @return 影响行数，正常为 1 */
    public int insert(RosterEntry entry) {
        return jdbc.sql("""
                        INSERT INTO t_plan_roster (plan_id, vest_num, name, team, hp, ammo1, ammo2, mode)
                        VALUES (:planId, :vestNum, :name, :team, :hp, :ammo1, :ammo2, :mode)
                        """)
                .param("planId", entry.planId().toString())
                .param("vestNum", entry.vestNum())
                .param("name", entry.name())
                .param("team", entry.team().name())
                .param("hp", entry.hp())
                .param("ammo1", entry.ammo1())
                .param("ammo2", entry.ammo2())
                .param("mode", entry.mode().name())
                .update();
    }

    /**
     * 整体替换某想定的编成（先删后插），与角色集合、楼层点位表同一策略。
     *
     * @return 写回的行数
     */
    @Transactional
    public int replaceByPlanId(UUID planId, List<RosterEntry> entries) {
        deleteByPlanId(planId);
        int inserted = 0;
        for (RosterEntry entry : entries) {
            inserted += jdbc.sql("""
                            INSERT INTO t_plan_roster (plan_id, vest_num, name, team, hp, ammo1, ammo2, mode)
                            VALUES (:planId, :vestNum, :name, :team, :hp, :ammo1, :ammo2, :mode)
                            """)
                    .param("planId", planId.toString())
                    .param("vestNum", entry.vestNum())
                    .param("name", entry.name())
                    .param("team", entry.team().name())
                    .param("hp", entry.hp())
                    .param("ammo1", entry.ammo1())
                    .param("ammo2", entry.ammo2())
                    .param("mode", entry.mode().name())
                    .update();
        }
        return inserted;
    }

    public int deleteByPlanId(UUID planId) {
        return jdbc.sql("DELETE FROM t_plan_roster WHERE plan_id = :planId")
                .param("planId", planId.toString())
                .update();
    }

    private static RosterEntry map(ResultSet rs, int rowNum) throws SQLException {
        return new RosterEntry(
                rs.getLong("id"),
                UUID.fromString(rs.getString("plan_id")),
                rs.getInt("vest_num"),
                rs.getString("name"),
                Team.valueOf(rs.getString("team")),
                rs.getInt("hp"),
                rs.getInt("ammo1"),
                rs.getInt("ammo2"),
                PollMode.valueOf(rs.getString("mode")),
                SqlTime.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("updated_at", LocalDateTime.class)));
    }
}
