package com.dualx.core.support;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * DATETIME(3) 列与 java.time 的换算。
 *
 * <p>库内统一存 UTC 挂钟值（见 V1 迁移文件头第 2 条）。MySQL 的 DATETIME 不带时区、
 * 也不做换算，因此这里不依赖 JDBC 驱动的时区推断，而在应用侧显式完成
 * {@code Instant(UTC) ↔ LocalDateTime(UTC)} 的映射——否则一旦连接参数
 * （如 connectionTimeZone）变动，全库时间会整体漂移。
 */
public final class SqlTime {

    private SqlTime() {
    }

    /** 写入参数：Instant → 库内的 UTC 挂钟值。 */
    public static LocalDateTime toDb(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 读取结果：库内的 UTC 挂钟值 → Instant。 */
    public static Instant fromDb(LocalDateTime dbValue) {
        return dbValue == null ? null : dbValue.toInstant(ZoneOffset.UTC);
    }
}
