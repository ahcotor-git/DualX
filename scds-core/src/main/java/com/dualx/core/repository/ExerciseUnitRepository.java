package com.dualx.core.repository;

import com.dualx.core.domain.ExerciseUnit;
import com.dualx.core.support.SqlTime;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 演习编成快照仓储（{@code t_exercise_unit}）。{@code id} 是自增代理键，
 * 业务唯一性由 {@code uk_exercise_unit (exercise_id, vest_num)} 保证。
 *
 * <p>整体替换策略与想定编成、角色集合、楼层点位一致：开演时一次性写入，不做逐行 diff。
 *
 * <p>终局统计（{@code final_status} / {@code dead_at} / {@code kills} / {@code hits_dealt}）
 * 不参与开演快照，由 {@link #freezeOutcome} 在停演时写入。
 */
@Repository
public class ExerciseUnitRepository {

    private static final String COLUMNS =
            "id, exercise_id, vest_num, name, team, final_status, dead_at, kills, hits_dealt,"
                    + " created_at, updated_at";

    private final JdbcClient jdbc;

    public ExerciseUnitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 某场演习的编成快照，按「队伍 → 马甲编号」排序——与想定编成同一口径，复盘界面对得上。 */
    public List<ExerciseUnit> findByExerciseId(UUID exerciseId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_exercise_unit WHERE exercise_id = :exerciseId"
                        + " ORDER BY team, vest_num")
                .param("exerciseId", exerciseId.toString())
                .query(ExerciseUnitRepository::map)
                .list();
    }

    public long countByExerciseId(UUID exerciseId) {
        return jdbc.sql("SELECT COUNT(*) FROM t_exercise_unit WHERE exercise_id = :exerciseId")
                .param("exerciseId", exerciseId.toString())
                .query(Long.class)
                .single();
    }

    /** 开演时快照一行。终局列留空（final_status 为 NULL，计数为库默认 0），停演时冻结。 */
    public int insert(ExerciseUnit unit) {
        return jdbc.sql("""
                        INSERT INTO t_exercise_unit (exercise_id, vest_num, name, team)
                        VALUES (:exerciseId, :vestNum, :name, :team)
                        """)
                .param("exerciseId", unit.exerciseId().toString())
                .param("vestNum", unit.vestNum())
                .param("name", unit.name())
                .param("team", unit.team().name())
                .update();
    }

    /**
     * 整体替换某场演习的编成快照（先删后插）。
     *
     * @return 写回的行数
     */
    @Transactional
    public int replaceByExerciseId(UUID exerciseId, List<ExerciseUnit> units) {
        deleteByExerciseId(exerciseId);
        int inserted = 0;
        for (ExerciseUnit unit : units) {
            inserted += jdbc.sql("""
                            INSERT INTO t_exercise_unit (exercise_id, vest_num, name, team)
                            VALUES (:exerciseId, :vestNum, :name, :team)
                            """)
                    .param("exerciseId", exerciseId.toString())
                    .param("vestNum", unit.vestNum())
                    .param("name", unit.name())
                    .param("team", unit.team().name())
                    .update();
        }
        return inserted;
    }

    public int deleteByExerciseId(UUID exerciseId) {
        return jdbc.sql("DELETE FROM t_exercise_unit WHERE exercise_id = :exerciseId")
                .param("exerciseId", exerciseId.toString())
                .update();
    }

    /**
     * 停演时冻结某人的终局。
     *
     * <p>按业务键 {@code (exercise_id, vest_num)} 定位而非代理键：调用方手里是态势里的马甲编号，
     * 让它先记下自增 id 只是多一次查询。
     *
     * <p>幂等：重复冻结以最后一次为准（例如先判阵亡、后人工改判存活）。
     *
     * @return 影响行数，正常为 1
     */
    public int freezeOutcome(UUID exerciseId, int vestNum, VestStatus finalStatus,
                             Instant deadAt, int kills, int hitsDealt) {
        return jdbc.sql("""
                        UPDATE t_exercise_unit
                           SET final_status = :finalStatus, dead_at = :deadAt,
                               kills = :kills, hits_dealt = :hitsDealt
                         WHERE exercise_id = :exerciseId AND vest_num = :vestNum
                        """)
                .param("finalStatus", finalStatus == null ? null : finalStatus.name())
                .param("deadAt", SqlTime.toDb(deadAt))
                .param("kills", kills)
                .param("hitsDealt", hitsDealt)
                .param("exerciseId", exerciseId.toString())
                .param("vestNum", vestNum)
                .update();
    }

    private static ExerciseUnit map(ResultSet rs, int rowNum) throws SQLException {
        String finalStatus = rs.getString("final_status");
        return new ExerciseUnit(
                rs.getLong("id"),
                UUID.fromString(rs.getString("exercise_id")),
                rs.getInt("vest_num"),
                rs.getString("name"),
                Team.valueOf(rs.getString("team")),
                finalStatus == null ? null : VestStatus.valueOf(finalStatus),
                SqlTime.fromDb(rs.getObject("dead_at", LocalDateTime.class)),
                rs.getInt("kills"),
                rs.getInt("hits_dealt"),
                SqlTime.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("updated_at", LocalDateTime.class)));
    }
}
