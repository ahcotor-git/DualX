package com.dualx.core.domain;

/**
 * 统一坐标载体（契约 {@code GeoPoint}）。
 *
 * <p>在 {@code t_hit_event} 与 {@code t_track_sample} 里都以同名的 8 个列展开，
 * 且两表对同名列的定义完全一致，故共用本记录；读取由
 * {@code com.dualx.core.support.GeoPointMapper} 统一完成。
 *
 * <p><b>两套坐标同时存</b>，前端不得自行换算：室内 Y 轴映射已由服务端在写入前完成。
 *
 * @param coordinateSystem 哪一套坐标有效；未定位时为 {@code null}
 * @param xPct             室内归一化横坐标 0~1（原点左上）
 * @param yPct             室内归一化纵坐标 0~1
 * @param floorNum         楼层号（来自 ff03 长版 15B）
 * @param posNum           楼层内点位号
 * @param lng              BD-09 经度
 * @param lat              BD-09 纬度
 * @param wgs84Lng         WGS-84 经度
 * @param wgs84Lat         WGS-84 纬度
 */
public record GeoPoint(
        CoordinateSystem coordinateSystem,
        Double xPct,
        Double yPct,
        Integer floorNum,
        Integer posNum,
        Double lng,
        Double lat,
        Double wgs84Lng,
        Double wgs84Lat) {
}
