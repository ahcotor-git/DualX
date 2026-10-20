package com.dualx.protocol.uwb;

import java.util.Arrays;

/**
 * 协议 B：UWB 定位帧（固定 41 字节）的解析结果。
 *
 * <pre>
 * 偏移  0    1    2      3  4  5  6    7 ...................... 38   39     40
 *     ┌────┬────┬────┬──────────────┬──────────────────────────┬──────┬──────┐
 *     │ AA │ 29 │ ？？│  vestNum LE  │  8 × range(mm) LE int32   │ 校验 │  55  │
 *     └────┴────┴────┴──────────────┴──────────────────────────┴──────┴──────┘
 * </pre>
 *
 * <p>第 i 路基站测距的字节偏移 = {@code 7 + 4*i}，与 {@code bs(i+1)} 固定一一对应
 * （PROTOCOL.md §6.5）。</p>
 */
public record UwbFrame(int reserved, int vestNum, int[] rangesMm, byte[] raw) {

    /** 8 路基站测距的字节偏移基数。 */
    public static final int RANGE_BASE = 7;
    /** 单路测距占用字节数。 */
    public static final int RANGE_BYTES = 4;
    /** 基站路数。 */
    public static final int RANGE_COUNT = 8;

    /**
     * 由 41 字节原始帧解析。
     *
     * <p>调用方必须已确认帧头 / 帧尾 / 校验，本方法不做重复校验。</p>
     *
     * @param frame 长度必须为 41
     */
    public static UwbFrame decode(byte[] frame) {
        if (frame == null || frame.length != com.dualx.protocol.ProtocolConstants.UWB_FRAME_LEN) {
            throw new IllegalArgumentException("UWB frame length must be 41");
        }
        int reserved = frame[2] & 0xFF;
        int vestNum = (frame[3] & 0xFF)
                | ((frame[4] & 0xFF) << 8)
                | ((frame[5] & 0xFF) << 16)
                | ((frame[6] & 0xFF) << 24);
        int[] ranges = new int[RANGE_COUNT];
        for (int i = 0; i < RANGE_COUNT; i++) {
            int o = RANGE_BASE + RANGE_BYTES * i;
            ranges[i] = (frame[o] & 0xFF)
                    | ((frame[o + 1] & 0xFF) << 8)
                    | ((frame[o + 2] & 0xFF) << 16)
                    | ((frame[o + 3] & 0xFF) << 24);
        }
        return new UwbFrame(reserved, vestNum, ranges, Arrays.copyOf(frame, frame.length));
    }

    /** 第 {@code i} 路（0-based）测距，单位毫米；对应基站 {@code bs(i+1)}。 */
    public int rangeMm(int i) {
        return rangesMm[i];
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UwbFrame other)) {
            return false;
        }
        return reserved == other.reserved && vestNum == other.vestNum
                && Arrays.equals(rangesMm, other.rangesMm) && Arrays.equals(raw, other.raw);
    }

    @Override
    public int hashCode() {
        int h = 31 * reserved + vestNum;
        h = h * 31 + Arrays.hashCode(rangesMm);
        return h * 31 + Arrays.hashCode(raw);
    }

    @Override
    public String toString() {
        return "UwbFrame{vestNum=" + vestNum + ", rangesMm=" + Arrays.toString(rangesMm) + '}';
    }
}
