package com.dualx.core.support;

import com.dualx.core.domain.CoordinateSystem;
import com.dualx.core.domain.GeoPoint;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 坐标列（{@code GeoPoint} 的列式展开）读取。
 *
 * <p>{@code t_hit_event} 与 {@code t_track_sample} 对这 8 个列的命名与语义完全一致
 * （见 V4 / V5 迁移文件），故两处共用本读取器；写入侧因各表列序不同，仍由各自仓储绑定。
 */
public final class GeoPointMapper {

    private GeoPointMapper() {
    }

    /**
     * 读取 {@code coordinate_system} / {@code x_pct} / {@code y_pct} / {@code floor_num} /
     * {@code pos_num} / {@code lng} / {@code lat} / {@code wgs84_lng} / {@code wgs84_lat}。
     *
     * <p>九个列全空时返回 {@code null} 而非「字段全为 null 的 GeoPoint」，以保证
     * 未定位（写入 null）读回仍是 null——否则上层拿到一个非 null 的空对象，
     * 与契约里 {@code position} 可为 null 的语义不符。
     */
    public static GeoPoint read(ResultSet rs) throws SQLException {
        String coordinateSystem = rs.getString("coordinate_system");
        Double xPct = rs.getObject("x_pct", Double.class);
        Double yPct = rs.getObject("y_pct", Double.class);
        Integer floorNum = rs.getObject("floor_num", Integer.class);
        Integer posNum = rs.getObject("pos_num", Integer.class);
        Double lng = rs.getObject("lng", Double.class);
        Double lat = rs.getObject("lat", Double.class);
        Double wgs84Lng = rs.getObject("wgs84_lng", Double.class);
        Double wgs84Lat = rs.getObject("wgs84_lat", Double.class);
        if (coordinateSystem == null && xPct == null && yPct == null && floorNum == null
                && posNum == null && lng == null && lat == null && wgs84Lng == null && wgs84Lat == null) {
            return null;
        }
        return new GeoPoint(CoordinateSystem.valueOf(coordinateSystem), xPct, yPct, floorNum, posNum,
                lng, lat, wgs84Lng, wgs84Lat);
    }
}
