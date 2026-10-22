package com.dualx.core.repository;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.SqlTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 演习场次仓储（{@code t_exercise}）。
 *
 * <p>本表一行两用：进行中的那一行是「当前演习」，写入 {@code ended_at} 后即成复盘记录。
 * 因此这里既有「查当前」的 {@link #findRunning()}，也有「查历史」的 {@link #findEnded}，
 * 但没有任何「转存 / 复制」方法——没有第二份主键要维护。
 *
 * <p>{@code running_flag} 是生成列，只服务于唯一索引 {@code uk_exercise_running}
 * （库层保证同一时刻至多一场演习进行中），读写一律绕开它。
 */
@Repository
public class ExerciseRepository {

    private static final String COLUMNS =
            "id, plan_id, plan_name, map_id, battle_mode, started_at, ended_at,"
                    + " roster_count, hit_count, dead_count, created_at, updated_at";

    /** 复盘列表按结束时间倒序；末位再按 id 兜底，同毫秒结束的两场才不会在翻页时换位。 */
    private static final String ENDED_ORDER = " ORDER BY ended_at DESC, id DESC";

    private static final String ENDED_LIMIT = " LIMIT :limit OFFSET :offset";

    private final JdbcClient jdbc;

    public ExerciseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Exercise> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_exercise WHERE id = :id")
                .param("id", id.toString())
                .query(ExerciseRepository::map)
                .optional();
    }

    /** 进行中的演习。至多一行由库层唯一索引保证，故无需排序。 */
    public Optional<Exercise> findRunning() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_exercise WHERE ended_at IS NULL")
                .query(ExerciseRepository::map)
                .optional();
    }

    /**
     * 已结束的场次，即复盘列表。
     *
     * @param keyword 想定名快照的模糊匹配，null 表示不过滤
     * @param from    结束时间下界（含），null 表示不限
     * @param to      结束时间上界（含），null 表示不限
     */
    public List<Exercise> findEnded(String keyword, Instant from, Instant to, PageRequest request) {
        StatementSpec spec = bind(
                jdbc.sql("SELECT " + COLUMNS + " FROM t_exercise" + endedFilter(keyword, from, to)
                        + ENDED_ORDER + ENDED_LIMIT),
                keyword, from, to);
        return spec.param("limit", request.size())
                .param("offset", request.offset())
                .query(ExerciseRepository::map)
                .list();
    }

    public long countEnded(String keyword, Instant from, Instant to) {
        return bind(jdbc.sql("SELECT COUNT(*) FROM t_exercise" + endedFilter(keyword, from, to)),
                keyword, from, to)
                .query(Long.class)
                .single();
    }

    /**
     * 开演落库：{@code ended_at}（默认 NULL，即进行中）与命中/阵亡计数（默认 0）不在本方法
     * 职责内——前者由 {@link #markEnded} 置入，后者同为停演时冻结。
     *
     * <p>{@code roster_count} 例外地在此写入：它是开演瞬间从想定拷来的快照，演中不再变，
     * 且「当前演习」接口要报这个数，不能等到停演才有值。
     */
    public int insert(Exercise exercise) {
        return jdbc.sql("""
                        INSERT INTO t_exercise (id, plan_id, plan_name, map_id, battle_mode, started_at, roster_count)
                        VALUES (:id, :planId, :planName, :mapId, :battleMode, :startedAt, :rosterCount)
                        """)
                .param("id", exercise.id().toString())
                .param("planId", exercise.planId().toString())
                .param("planName", exercise.planName())
                .param("mapId", exercise.mapId() == null ? null : exercise.mapId().toString())
                .param("battleMode", exercise.battleMode().name())
                .param("startedAt", SqlTime.toDb(exercise.startedAt()))
                .param("rosterCount", exercise.rosterCount())
                .update();
    }

    /**
     * 停演：置 {@code ended_at}、冻结结局统计并记下结束原因。
     *
     * <p>守卫 {@code ended_at IS NULL} 一举两得：重复停演幂等返回 0；并发停演只有一个赢家。
     * 若换成「先查是否进行中、再更新」，两个请求之间就有竞态窗口。
     *
     * <p>正常停演与启动自愈的收尾**必须共用本方法**（后者传
     * {@link ExerciseEndReason#CRASH_RECOVERED}），否则两套冻结逻辑会各自漂移。
     *
     * @param reason 结束原因。调用方须显式给出——留默认值等于让「异常中断」悄悄记成「正常停演」
     * @return 影响行数。1 = 本次停演生效；0 = 场次不存在或已结束
     */
    public int markEnded(UUID id, Instant endedAt, int hitCount, int deadCount, ExerciseEndReason reason) {
        return jdbc.sql("""
                        UPDATE t_exercise
                           SET ended_at = :endedAt, hit_count = :hitCount, dead_count = :deadCount,
                               end_reason = :endReason
                         WHERE id = :id AND ended_at IS NULL
                        """)
                .param("endedAt", SqlTime.toDb(endedAt))
                .param("hitCount", hitCount)
                .param("deadCount", deadCount)
                .param("endReason", reason.name())
                .param("id", id.toString())
                .update();
    }

    /**
     * 占用本场运行权并落下首个心跳（开演时调用）。
     *
     * <p>{@code owner_instance} 是「这行还有没有人在管」的唯一凭据，与
     * {@code uk_exercise_running}（至多一场）正交：后者管不住进程崩了却没写 {@code ended_at} 的行。
     *
     * <p>同一实例重复调用是幂等的（只是刷新心跳）；跨实例抢同一行也会「成功」，
     * 因为此时应当由**启动自愈**先收尾旧场次，而不是靠这里静默拒绝——
     * 静默拒绝只会让两个实例都以为自己持权。
     *
     * @return 影响行数。0 = 场次不存在或已结束，调用方应视为异常（刚插入就查不到，说明前提已破）
     */
    public int claim(UUID id, String instanceId, Instant at) {
        return jdbc.sql("""
                        UPDATE t_exercise
                           SET owner_instance = :ownerInstance, heartbeat_at = :heartbeatAt
                         WHERE id = :id AND ended_at IS NULL
                        """)
                .param("ownerInstance", instanceId)
                .param("heartbeatAt", SqlTime.toDb(at))
                .param("id", id.toString())
                .update();
    }

    /**
     * 续心跳。守卫带 {@code owner_instance}，不可省：少了它，一个已被自愈收尾（或已换手）的实例
     * 仍能靠心跳把行「续活」，活跃性判定就失去意义。
     *
     * @return 影响行数。0 = 这已不是本实例进行中的场次
     */
    public int heartbeat(UUID id, String instanceId, Instant at) {
        return jdbc.sql("""
                        UPDATE t_exercise
                           SET heartbeat_at = :heartbeatAt
                         WHERE id = :id AND ended_at IS NULL AND owner_instance = :ownerInstance
                        """)
                .param("heartbeatAt", SqlTime.toDb(at))
                .param("id", id.toString())
                .param("ownerInstance", instanceId)
                .update();
    }

    /** findEnded / countEnded 共用同一份过滤条件，避免两者的口径各自漂移。 */
    private static String endedFilter(String keyword, Instant from, Instant to) {
        StringBuilder filter = new StringBuilder(" WHERE ended_at IS NOT NULL");
        if (keyword != null) {
            filter.append(" AND plan_name LIKE :keyword ESCAPE '\\\\'");
        }
        if (from != null) {
            filter.append(" AND ended_at >= :fromAt");
        }
        if (to != null) {
            filter.append(" AND ended_at <= :toAt");
        }
        return filter.toString();
    }

    private static StatementSpec bind(StatementSpec spec, String keyword, Instant from, Instant to) {
        StatementSpec bound = spec;
        if (keyword != null) {
            bound = bound.param("keyword", likePattern(keyword));
        }
        if (from != null) {
            bound = bound.param("fromAt", SqlTime.toDb(from));
        }
        if (to != null) {
            bound = bound.param("toAt", SqlTime.toDb(to));
        }
        return bound;
    }

    /** keyword 来自用户输入，须转义 LIKE 通配符，否则输入一个 {@code %} 就会命中全表。 */
    private static String likePattern(String keyword) {
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static Exercise map(ResultSet rs, int rowNum) throws SQLException {
        String mapId = rs.getString("map_id");
        return new Exercise(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("plan_id")),
                rs.getString("plan_name"),
                mapId == null ? null : UUID.fromString(mapId),
                BattleMode.valueOf(rs.getString("battle_mode")),
                SqlTime.fromDb(rs.getObject("started_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("ended_at", LocalDateTime.class)),
                rs.getInt("roster_count"),
                rs.getInt("hit_count"),
                rs.getInt("dead_count"),
                SqlTime.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("updated_at", LocalDateTime.class)));
    }
}
