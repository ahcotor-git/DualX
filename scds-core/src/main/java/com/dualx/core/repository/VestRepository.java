package com.dualx.core.repository;

import com.dualx.core.domain.Vest;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.SqlTime;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 马甲设备台账仓储（{@code t_vest}）。主键是自然主键 {@code vest_num}
 * （协议 uint16 编号），因此没有 {@code UUID} 形态的查询入口。
 *
 * <p>{@code created_at} / {@code updated_at} 由库默认值维护，INSERT 不写这两列。
 */
@Repository
public class VestRepository {

    private static final String COLUMNS =
            "vest_num, name, team, sim_no, model, firmware_version, bind_secret_hash, "
                    + "bound, enabled, last_seen_at, remark, created_at, updated_at";

    private final JdbcClient jdbc;

    public VestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Vest> findByVestNum(int vestNum) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_vest WHERE vest_num = :vestNum")
                .param("vestNum", vestNum)
                .query(VestRepository::map)
                .optional();
    }

    public List<Vest> findAll(PageRequest request) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_vest ORDER BY vest_num LIMIT :limit OFFSET :offset")
                .param("limit", request.size())
                .param("offset", request.offset())
                .query(VestRepository::map)
                .list();
    }

    /**
     * 台账列表的过滤条件。
     *
     * @param keyword     编号或姓名的模糊匹配，null 表示不过滤
     * @param status      契约 {@code VestStatus}；三个取值的口径见 {@link #search}
     * @param onlineSince 「在线」判定的下界，{@code status} 非空时调用方必须给出
     */
    public record VestFilter(String keyword, Team team, Boolean bound, VestStatus status, Instant onlineSince) {
    }

    /**
     * 按条件分页查询，恒以 {@code vest_num} 升序（台账的稳定顺序就是编号顺序）。
     *
     * <p><b>{@code status} 的口径</b>：它是运行态，而 {@code t_vest} 只存台账。本表唯一的
     * 运行痕迹是 {@code last_seen_at}（最后一次合法帧时刻），因此按契约
     * {@code x-scds-invariants.vestOnline}（距今 {@code <= 5000ms} 为在线）把它当在线判定的
     * 唯一依据：{@code LIVE} 取在线、{@code OFFLINE} 取非在线。{@code DIE}（阵亡）属演习
     * 运行态，落在 {@code t_exercise_unit.final_status}，台账层没有这个数据源——用「离线」
     * 冒充「阵亡」会把两种完全不同的现场状况混成一种，故显式返回空集。</p>
     */
    public List<Vest> search(VestFilter filter, PageRequest request) {
        return bind(jdbc.sql("SELECT " + COLUMNS + " FROM t_vest" + filterClause(filter)
                        + " ORDER BY vest_num LIMIT :limit OFFSET :offset"), filter)
                .param("limit", request.size())
                .param("offset", request.offset())
                .query(VestRepository::map)
                .list();
    }

    public long count(VestFilter filter) {
        return bind(jdbc.sql("SELECT COUNT(*) FROM t_vest" + filterClause(filter)), filter)
                .query(Long.class)
                .single();
    }

    /** search / count 共用同一份条件，避免两处的口径各自漂移。 */
    private static String filterClause(VestFilter filter) {
        List<String> conditions = new ArrayList<>();
        if (filter.keyword() != null) {
            conditions.add("(CAST(vest_num AS CHAR) LIKE :keyword ESCAPE '\\\\'"
                    + " OR name LIKE :keyword ESCAPE '\\\\')");
        }
        if (filter.team() != null) {
            conditions.add("team = :team");
        }
        if (filter.bound() != null) {
            conditions.add("bound = :bound");
        }
        if (filter.status() == VestStatus.LIVE) {
            conditions.add("last_seen_at IS NOT NULL AND last_seen_at > :onlineSince");
        } else if (filter.status() == VestStatus.OFFLINE) {
            conditions.add("(last_seen_at IS NULL OR last_seen_at <= :onlineSince)");
        } else if (filter.status() == VestStatus.DIE) {
            conditions.add("1 = 0");
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private static StatementSpec bind(StatementSpec spec, VestFilter filter) {
        StatementSpec bound = spec;
        if (filter.keyword() != null) {
            bound = bound.param("keyword", likePattern(filter.keyword()));
        }
        if (filter.team() != null) {
            bound = bound.param("team", filter.team().name());
        }
        if (filter.bound() != null) {
            bound = bound.param("bound", filter.bound() ? 1 : 0);
        }
        if (filter.onlineSince() != null) {
            bound = bound.param("onlineSince", SqlTime.toDb(filter.onlineSince()));
        }
        return bound;
    }

    /** keyword 来自用户输入，须转义 LIKE 通配符，否则输入一个 {@code %} 就会命中全表。 */
    private static String likePattern(String keyword) {
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM t_vest").query(Long.class).single();
    }

    public boolean existsByVestNum(int vestNum) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM t_vest WHERE vest_num = :vestNum)")
                .param("vestNum", vestNum)
                .query(Boolean.class)
                .single();
    }

    /** @return 影响行数，正常为 1；同号重复插入由主键拒绝（DataIntegrityViolationException） */
    public int insert(Vest vest) {
        return jdbc.sql("""
                        INSERT INTO t_vest (vest_num, name, team, sim_no, model, firmware_version,
                                            bind_secret_hash, bound, enabled, last_seen_at, remark)
                        VALUES (:vestNum, :name, :team, :simNo, :model, :firmwareVersion,
                                :bindSecretHash, :bound, :enabled, :lastSeenAt, :remark)
                        """)
                .param("vestNum", vest.vestNum())
                .param("name", vest.name())
                .param("team", teamName(vest.team()))
                .param("simNo", vest.simNo())
                .param("model", vest.model())
                .param("firmwareVersion", vest.firmwareVersion())
                .param("bindSecretHash", vest.bindSecretHash())
                .param("bound", vest.bound() ? 1 : 0)
                .param("enabled", vest.enabled() ? 1 : 0)
                .param("lastSeenAt", SqlTime.toDb(vest.lastSeenAt()))
                .param("remark", vest.remark())
                .update();
    }

    /** 更新可变字段；{@code vest_num} / {@code created_at} 不可改。 */
    public int update(Vest vest) {
        return jdbc.sql("""
                        UPDATE t_vest
                           SET name = :name,
                               team = :team,
                               sim_no = :simNo,
                               model = :model,
                               firmware_version = :firmwareVersion,
                               bind_secret_hash = :bindSecretHash,
                               bound = :bound,
                               enabled = :enabled,
                               last_seen_at = :lastSeenAt,
                               remark = :remark
                         WHERE vest_num = :vestNum
                        """)
                .param("vestNum", vest.vestNum())
                .param("name", vest.name())
                .param("team", teamName(vest.team()))
                .param("simNo", vest.simNo())
                .param("model", vest.model())
                .param("firmwareVersion", vest.firmwareVersion())
                .param("bindSecretHash", vest.bindSecretHash())
                .param("bound", vest.bound() ? 1 : 0)
                .param("enabled", vest.enabled() ? 1 : 0)
                .param("lastSeenAt", SqlTime.toDb(vest.lastSeenAt()))
                .param("remark", vest.remark())
                .update();
    }

    public int deleteByVestNum(int vestNum) {
        return jdbc.sql("DELETE FROM t_vest WHERE vest_num = :vestNum")
                .param("vestNum", vestNum)
                .update();
    }

    /** 库里存契约枚举名（CHECK 约束限定 RED/BLUE/ORANGE/YELLOW）。 */
    private static String teamName(Team team) {
        return team == null ? null : team.name();
    }

    private static Vest map(ResultSet rs, int rowNum) throws SQLException {
        String team = rs.getString("team");
        return new Vest(
                rs.getInt("vest_num"),
                rs.getString("name"),
                team == null ? null : Team.valueOf(team),
                rs.getString("sim_no"),
                rs.getString("model"),
                rs.getString("firmware_version"),
                rs.getString("bind_secret_hash"),
                rs.getBoolean("bound"),
                rs.getBoolean("enabled"),
                SqlTime.fromDb(rs.getObject("last_seen_at", LocalDateTime.class)),
                rs.getString("remark"),
                SqlTime.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("updated_at", LocalDateTime.class)));
    }
}
