package com.dualx.core.repository;

import com.dualx.core.domain.GeoPoint;
import com.dualx.core.domain.MotionState;
import com.dualx.core.domain.PositionSource;
import com.dualx.core.domain.SampleReason;
import com.dualx.core.domain.TrackSample;
import com.dualx.core.support.GeoPointMapper;
import com.dualx.core.support.SqlTime;
import com.dualx.protocol.VestStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * 轨迹采样仓储（{@code t_track_sample}）。全库写入量最大的表，写入一律走
 * {@link #insertAll} 的<b>真批量</b>（JDBC batch），不逐条 update——逐帧落库会把
 * 磁盘与连接池打满。
 *
 * <p>读取按 {@code id} 游标分片：{@code id} 是自增代理键且插入序即采样序，用它分片
 * 不重不漏；{@code t_ms} 只保证时间语义，不作为游标（同毫秒多点时无法唯一确定起点）。
 */
@Repository
public class TrackSampleRepository {

    private static final String COLUMNS =
            "id, exercise_id, vest_num, t_ms, status, hp, ammo1, ammo2, motion, position_source,"
                    + " signal_quality, coordinate_system, x_pct, y_pct, floor_num, pos_num,"
                    + " lng, lat, wgs84_lng, wgs84_lat, sample_reason";

    private static final String INSERT = """
            INSERT INTO t_track_sample (exercise_id, vest_num, t_ms, status, hp, ammo1, ammo2,
                                        motion, position_source, signal_quality,
                                        coordinate_system, x_pct, y_pct, floor_num, pos_num,
                                        lng, lat, wgs84_lng, wgs84_lat, sample_reason)
            VALUES (:exerciseId, :vestNum, :tMs, :status, :hp, :ammo1, :ammo2,
                    :motion, :positionSource, :signalQuality,
                    :coordinateSystem, :xPct, :yPct, :floorNum, :posNum,
                    :lng, :lat, :wgs84Lng, :wgs84Lat, :sampleReason)
            """;

    /** 不抽稀的分片：直接命中 {@code ix_track_exercise_t}，默认路径零额外开销。 */
    private static final String CHUNK_PLAIN =
            "SELECT " + COLUMNS + " FROM t_track_sample"
                    + " WHERE exercise_id = :exerciseId AND id > :afterId%s"
                    + " ORDER BY id LIMIT :limit";

    /**
     * 抽稀分片：窗口函数按人分区编号后再取模。
     *
     * <p>窗口必须在<b>全量</b>上算（不带 {@code id > afterId}），否则每片都从 1 重新编号，
     * 抽稀相位逐片漂移，会同时出现重复与漏点。游标仍作用于抽稀结果集的 id，故分片依然不重不漏。
     */
    private static final String CHUNK_STRIDED =
            "SELECT " + COLUMNS + " FROM ("
                    + "  SELECT " + COLUMNS + ", ROW_NUMBER() OVER (PARTITION BY vest_num ORDER BY id) AS rn"
                    + "    FROM t_track_sample WHERE exercise_id = :exerciseId%s"
                    + ") AS sampled"
                    + " WHERE MOD(rn, :stride) = 0 AND id > :afterId"
                    + " ORDER BY id LIMIT :limit";

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batchJdbc;

    public TrackSampleRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batchJdbc) {
        this.jdbc = jdbc;
        this.batchJdbc = batchJdbc;
    }

    /**
     * 批量写入（一次 flush 一批）。{@code sample.id()} 被忽略，由库自增。
     *
     * @return 写入行数
     */
    public int insertAll(List<TrackSample> samples) {
        if (samples.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = samples.stream()
                .map(TrackSampleRepository::params)
                .toArray(SqlParameterSource[]::new);
        return batchJdbc.batchUpdate(INSERT, batch).length;
    }

    /**
     * 分片拉取轨迹，按 {@code id} 升序。
     *
     * @param afterId  上一片最后一条的 id；首次传 0
     * @param vestNums 只取这些马甲；null 或空表示全体
     * @param stride   抽稀步长，1 表示不抽稀
     * @param limit    本片条数上限
     */
    public List<TrackSample> findChunk(UUID exerciseId, long afterId, List<Integer> vestNums,
                                       int stride, int limit) {
        boolean filterVest = vestNums != null && !vestNums.isEmpty();
        String vestFilter = filterVest ? " AND vest_num IN (:vestNums)" : "";
        String sql = (stride <= 1 ? CHUNK_PLAIN : CHUNK_STRIDED).formatted(vestFilter);

        JdbcClient.StatementSpec spec = jdbc.sql(sql)
                .param("exerciseId", exerciseId.toString())
                .param("afterId", afterId)
                .param("limit", limit);
        if (filterVest) {
            spec = spec.param("vestNums", vestNums);
        }
        if (stride > 1) {
            spec = spec.param("stride", stride);
        }
        return spec.query(TrackSampleRepository::map).list();
    }

    public long countByExerciseId(UUID exerciseId) {
        return jdbc.sql("SELECT COUNT(*) FROM t_track_sample WHERE exercise_id = :exerciseId")
                .param("exerciseId", exerciseId.toString())
                .query(Long.class)
                .single();
    }

    /** 删除某场演习的全部轨迹（仅随整场演习删除时调用）。 */
    public int deleteByExerciseId(UUID exerciseId) {
        return jdbc.sql("DELETE FROM t_track_sample WHERE exercise_id = :exerciseId")
                .param("exerciseId", exerciseId.toString())
                .update();
    }

    private static MapSqlParameterSource params(TrackSample sample) {
        GeoPoint p = sample.position();
        return new MapSqlParameterSource()
                .addValue("exerciseId", sample.exerciseId().toString())
                .addValue("vestNum", sample.vestNum())
                .addValue("tMs", sample.tMs())
                .addValue("status", sample.status().name())
                .addValue("hp", sample.hp())
                .addValue("ammo1", sample.ammo1())
                .addValue("ammo2", sample.ammo2())
                .addValue("motion", sample.motion() == null ? null : sample.motion().name())
                .addValue("positionSource",
                        sample.positionSource() == null ? null : sample.positionSource().name())
                .addValue("signalQuality", sample.signalQuality())
                .addValue("coordinateSystem",
                        p == null || p.coordinateSystem() == null ? null : p.coordinateSystem().name())
                .addValue("xPct", p == null ? null : p.xPct())
                .addValue("yPct", p == null ? null : p.yPct())
                .addValue("floorNum", p == null ? null : p.floorNum())
                .addValue("posNum", p == null ? null : p.posNum())
                .addValue("lng", p == null ? null : p.lng())
                .addValue("lat", p == null ? null : p.lat())
                .addValue("wgs84Lng", p == null ? null : p.wgs84Lng())
                .addValue("wgs84Lat", p == null ? null : p.wgs84Lat())
                .addValue("sampleReason", sample.sampleReason().code());
    }

    private static TrackSample map(ResultSet rs, int rowNum) throws SQLException {
        String motion = rs.getString("motion");
        String positionSource = rs.getString("position_source");
        return new TrackSample(
                rs.getLong("id"),
                UUID.fromString(rs.getString("exercise_id")),
                rs.getInt("vest_num"),
                rs.getInt("t_ms"),
                VestStatus.valueOf(rs.getString("status")),
                rs.getObject("hp", Integer.class),
                rs.getObject("ammo1", Integer.class),
                rs.getObject("ammo2", Integer.class),
                motion == null ? null : MotionState.valueOf(motion),
                positionSource == null ? null : PositionSource.valueOf(positionSource),
                rs.getObject("signal_quality", Integer.class),
                GeoPointMapper.read(rs),
                SampleReason.fromCode(rs.getInt("sample_reason")));
    }
}
