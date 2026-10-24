package com.dualx.core.repository;

import com.dualx.core.domain.GeoPoint;
import com.dualx.core.domain.HitEvent;
import com.dualx.core.support.GeoPointMapper;
import com.dualx.core.support.SqlTime;
import com.dualx.protocol.HitPart;
import com.dualx.protocol.WeaponKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 命中事件仓储（{@code t_hit_event}）。高频写入，故 {@code seq} 由库自增并直接对外充当
 * 契约的 {@code HitEvent.seq}（全局单调，供去重与增量拉取）。
 *
 * <p>与 {@code t_exercise_log} 同样只增不改不删；靠 {@link #deleteByExerciseId} 随演习一并清退
 * （仅在删除整场演习时使用，复盘数据本身不单独清理）。
 */
@Repository
public class HitEventRepository {

    private static final String COLUMNS =
            "seq, exercise_id, at, t_ms, hit_vest_num, shooter_num, shooter_is_soldier,"
                    + " shooter_weapon_kind, hit_part, lethal, coordinate_system, x_pct, y_pct,"
                    + " floor_num, pos_num, lng, lat, wgs84_lng, wgs84_lat";

    private static final String INSERT = """
            INSERT INTO t_hit_event (exercise_id, at, t_ms, hit_vest_num, shooter_num,
                                     shooter_is_soldier, shooter_weapon_kind, hit_part, lethal,
                                     coordinate_system, x_pct, y_pct, floor_num, pos_num,
                                     lng, lat, wgs84_lng, wgs84_lat)
            VALUES (:exerciseId, :at, :tMs, :hitVestNum, :shooterNum,
                    :shooterIsSoldier, :shooterWeaponKind, :hitPart, :lethal,
                    :coordinateSystem, :xPct, :yPct, :floorNum, :posNum,
                    :lng, :lat, :wgs84Lng, :wgs84Lat)
            """;

    private final JdbcClient jdbc;

    public HitEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 记一次命中。{@code event.seq()} 被忽略，由库自增。 */
    public int insert(HitEvent event) {
        GeoPoint p = event.position();
        return jdbc.sql(INSERT)
                .param("exerciseId", event.exerciseId().toString())
                .param("at", SqlTime.toDb(event.at()))
                .param("tMs", event.tMs())
                .param("hitVestNum", event.hitVestNum())
                .param("shooterNum", event.shooterNum())
                .param("shooterIsSoldier", event.shooterIsSoldier())
                .param("shooterWeaponKind",
                        event.shooterWeaponKind() == null ? null : event.shooterWeaponKind().name())
                .param("hitPart", event.hitPart().name())
                .param("lethal", event.lethal())
                .param("coordinateSystem",
                        p == null || p.coordinateSystem() == null ? null : p.coordinateSystem().name())
                .param("xPct", p == null ? null : p.xPct())
                .param("yPct", p == null ? null : p.yPct())
                .param("floorNum", p == null ? null : p.floorNum())
                .param("posNum", p == null ? null : p.posNum())
                .param("lng", p == null ? null : p.lng())
                .param("lat", p == null ? null : p.lat())
                .param("wgs84Lng", p == null ? null : p.wgs84Lng())
                .param("wgs84Lat", p == null ? null : p.wgs84Lat())
                .update();
    }

    /**
     * 增量拉取：{@code seq > sinceSeq} 的命中，按 seq 升序（即时间顺序），至多 {@code limit} 条。
     * 客户端保存最后一条的 seq 作为下次的 sinceSeq。
     *
     * @param sinceSeq null 表示从头拉取
     */
    public List<HitEvent> findByExerciseIdSince(UUID exerciseId, Long sinceSeq, int limit) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM t_hit_event WHERE exercise_id = :exerciseId AND seq > :sinceSeq"
                        + " ORDER BY seq LIMIT :limit")
                .param("exerciseId", exerciseId.toString())
                .param("sinceSeq", sinceSeq == null ? 0L : sinceSeq)
                .param("limit", limit)
                .query(HitEventRepository::map)
                .list();
    }

    public long countByExerciseId(UUID exerciseId) {
        return jdbc.sql("SELECT COUNT(*) FROM t_hit_event WHERE exercise_id = :exerciseId")
                .param("exerciseId", exerciseId.toString())
                .query(Long.class)
                .single();
    }

    /** 某人打出的命中数（停演冻结 {@code t_exercise_unit.hits_dealt} 用）。 */
    public long countByShooter(UUID exerciseId, int shooterNum) {
        return jdbc.sql("SELECT COUNT(*) FROM t_hit_event"
                        + " WHERE exercise_id = :exerciseId AND shooter_num = :shooterNum")
                .param("exerciseId", exerciseId.toString())
                .param("shooterNum", shooterNum)
                .query(Long.class)
                .single();
    }

    /** 某人打出的致命命中数（停演冻结 {@code t_exercise_unit.kills} 用）。 */
    public long countLethalByShooter(UUID exerciseId, int shooterNum) {
        return jdbc.sql("SELECT COUNT(*) FROM t_hit_event"
                        + " WHERE exercise_id = :exerciseId AND shooter_num = :shooterNum AND lethal = 1")
                .param("exerciseId", exerciseId.toString())
                .param("shooterNum", shooterNum)
                .query(Long.class)
                .single();
    }

    /**
     * 致死**回填**（设计 06 §4.3）：把某马甲最近一条尚未判致死的命中改成 {@code lethal = 1}。
     *
     * <p>{@code ff05} 不带击中强度（PROTOCOL.md §6.7 已作废），致死只能在**心跳**判出 hp=0 时倒推：
     * 命中先落库 {@code lethal=false}；同一演习内该马甲转入阵亡时，回填它最近一条
     * {@code lethal=false} 的命中，使「击杀数」与「命中数」在同一口径上（同一事务由调用方保证）。</p>
     *
     * @return 被回填那条命中的 {@code shooterNum}；该马甲没有待判命中时为 {@link Optional#empty()}。
     *         调用方据此给开枪者记一次击杀。
     */
    public Optional<Integer> markMostRecentLethal(UUID exerciseId, int hitVestNum) {
        Optional<PendingLethal> pending = jdbc.sql(
                        "SELECT seq, shooter_num FROM t_hit_event"
                                + " WHERE exercise_id = :exerciseId AND hit_vest_num = :hitVestNum"
                                + " AND lethal = 0 ORDER BY seq DESC LIMIT 1")
                .param("exerciseId", exerciseId.toString())
                .param("hitVestNum", hitVestNum)
                .query((rs, rowNum) -> new PendingLethal(rs.getLong("seq"), rs.getInt("shooter_num")))
                .optional();
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        PendingLethal row = pending.get();
        jdbc.sql("UPDATE t_hit_event SET lethal = 1 WHERE seq = :seq")
                .param("seq", row.seq())
                .update();
        return Optional.of(row.shooterNum());
    }

    /** 删除某场演习的全部命中（仅随整场演习删除时调用）。 */
    public int deleteByExerciseId(UUID exerciseId) {
        return jdbc.sql("DELETE FROM t_hit_event WHERE exercise_id = :exerciseId")
                .param("exerciseId", exerciseId.toString())
                .update();
    }

    /** {@link #markMostRecentLethal} 的一次定位结果：待回填行的 seq 与其开枪者。 */
    private record PendingLethal(long seq, int shooterNum) {
    }

    private static HitEvent map(ResultSet rs, int rowNum) throws SQLException {
        String weaponKind = rs.getString("shooter_weapon_kind");
        return new HitEvent(
                rs.getLong("seq"),
                UUID.fromString(rs.getString("exercise_id")),
                SqlTime.fromDb(rs.getObject("at", LocalDateTime.class)),
                rs.getInt("t_ms"),
                rs.getInt("hit_vest_num"),
                rs.getInt("shooter_num"),
                rs.getBoolean("shooter_is_soldier"),
                weaponKind == null ? null : WeaponKind.valueOf(weaponKind),
                HitPart.valueOf(rs.getString("hit_part")),
                rs.getBoolean("lethal"),
                GeoPointMapper.read(rs));
    }
}
