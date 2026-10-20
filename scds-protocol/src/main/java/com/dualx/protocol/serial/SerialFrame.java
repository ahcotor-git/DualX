package com.dualx.protocol.serial;

import java.util.Arrays;

/**
 * 协议 A 上行帧的解析结果。
 *
 * <p>全部字段命名与 PROTOCOL.md §1.4 一一对应。所有数值都是**已解码的语义值**，
 * 不再是 ASCII 文本。</p>
 *
 * <p>{@code raw()} 保存该帧<b>解码后</b>的协议字节（长度 = 帧总长 11~18B，与 PROTOCOL.md §1.3
 * 速查表一致）；即 {@code Hex.encode(raw())} 等于Golden 样本中的 hex 字符串。
 * 用于日志、排障与「逐字节」断言。</p>
 */
public sealed interface SerialFrame
        permits SerialFrame.Heartbeat, SerialFrame.Gps, SerialFrame.Hit,
        SerialFrame.IndoorLocation, SerialFrame.Mortar, SerialFrame.SimNo {

    SerialFrameType type();

    /** 帧在 ASCII 流中的原始字节（含帧头与 AF FA 帧尾）。 */
    byte[] raw();

    /** 帧总字节数。 */
    default int byteLength() {
        return raw().length;
    }

    /** ① {@code ff03} 心跳/状态帧。 */
    record Heartbeat(int vestNum, int ammo1, int hp, int ammo2,
                     Integer floorNum, Integer posNum, byte[] raw) implements SerialFrame {

        @Override
        public SerialFrameType type() {
            return SerialFrameType.HEARTBEAT;
        }

        /** 长版（15B）才携带楼层/点位；短版（13B）为 {@code null}，由业务层沿用上一帧旧值。 */
        public boolean longVersion() {
            return floorNum != null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Heartbeat that)) {
                return false;
            }
            return vestNum == that.vestNum && ammo1 == that.ammo1 && hp == that.hp && ammo2 == that.ammo2
                    && java.util.Objects.equals(floorNum, that.floorNum)
                    && java.util.Objects.equals(posNum, that.posNum)
                    && Arrays.equals(raw, that.raw);
        }

        @Override
        public int hashCode() {
            int r = java.util.Objects.hash(vestNum, ammo1, hp, ammo2, floorNum, posNum);
            return r * 31 + Arrays.hashCode(raw);
        }
    }

    /** ② {@code ff04} 室外 GPS 帧（原始 WGS-84，度 = raw / 1000000）。 */
    record Gps(int vestNum, long lngRaw, long latRaw, double lng, double lat, byte[] raw) implements SerialFrame {

        @Override
        public SerialFrameType type() {
            return SerialFrameType.GPS;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Gps that)) {
                return false;
            }
            return vestNum == that.vestNum && lngRaw == that.lngRaw && latRaw == that.latRaw
                    && Double.compare(lng, that.lng) == 0 && Double.compare(lat, that.lat) == 0
                    && Arrays.equals(raw, that.raw);
        }

        @Override
        public int hashCode() {
            int r = java.util.Objects.hash(vestNum, lngRaw, latRaw, lng, lat);
            return r * 31 + Arrays.hashCode(raw);
        }
    }

    /** ③ {@code ff05} 击中记录帧。 */
    record Hit(int hitVestNum, int shooterNum, int hitPart, byte[] raw) implements SerialFrame {

        @Override
        public SerialFrameType type() {
            return SerialFrameType.HIT;
        }

        /** 开枪者是否为单兵（&lt;= 1800）。 */
        public boolean shooterIsSoldier() {
            return shooterNum <= com.dualx.protocol.ProtocolConstants.PEOPLE_OR_OBJECT_TYPE;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Hit that)) {
                return false;
            }
            return hitVestNum == that.hitVestNum && shooterNum == that.shooterNum && hitPart == that.hitPart
                    && Arrays.equals(raw, that.raw);
        }

        @Override
        public int hashCode() {
            int r = java.util.Objects.hash(hitVestNum, shooterNum, hitPart);
            return r * 31 + Arrays.hashCode(raw);
        }
    }

    /**
     * ④ {@code ff06} 旧室内定位帧。
     *
     * <p>协议层照常解析；是否使用由业务层决定 —— 当 {@code UWB_MODE=enabled} 时必须丢弃本帧（PROTOCOL.md §1.4④）。</p>
     */
    record IndoorLocation(int vestNum, long width, long height, byte[] raw) implements SerialFrame {

        @Override
        public SerialFrameType type() {
            return SerialFrameType.INDOOR_LOCATION;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof IndoorLocation that)) {
                return false;
            }
            return vestNum == that.vestNum && width == that.width && height == that.height
                    && Arrays.equals(raw, that.raw);
        }

        @Override
        public int hashCode() {
            int r = java.util.Objects.hash(vestNum, width, height);
            return r * 31 + Arrays.hashCode(raw);
        }
    }

    /** ⑤ {@code ff07} 迫击炮/曲射火力帧（方位角 度 / 射程 米）。 */
    record Mortar(int vestNum, int angle, int distance, byte[] raw) implements SerialFrame {

        @Override
        public SerialFrameType type() {
            return SerialFrameType.MORTAR;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Mortar that)) {
                return false;
            }
            return vestNum == that.vestNum && angle == that.angle && distance == that.distance
                    && Arrays.equals(raw, that.raw);
        }

        @Override
        public int hashCode() {
            int r = java.util.Objects.hash(vestNum, angle, distance);
            return r * 31 + Arrays.hashCode(raw);
        }
    }

    /** ⑥ {@code ff0a} SIM 号上报帧；{@code simNo} 为帧内 10 字节的十六进制文本（20 字符）。 */
    record SimNo(int vestNum, String simNo, byte[] raw) implements SerialFrame {

        @Override
        public SerialFrameType type() {
            return SerialFrameType.SIM_NO;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SimNo that)) {
                return false;
            }
            return vestNum == that.vestNum && java.util.Objects.equals(simNo, that.simNo)
                    && Arrays.equals(raw, that.raw);
        }

        @Override
        public int hashCode() {
            int r = java.util.Objects.hash(vestNum, simNo);
            return r * 31 + Arrays.hashCode(raw);
        }
    }
}
