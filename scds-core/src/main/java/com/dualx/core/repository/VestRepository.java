package com.dualx.core.repository;

import com.dualx.core.domain.Vest;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.SqlTime;
import com.dualx.protocol.Team;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
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
