package com.dualx.core.domain;

/**
 * 轨迹采样的落点原因（{@code t_track_sample.sample_reason}）。
 *
 * <p>库里存 TINYINT，故本枚举带字节码而非按名存储——这与全库「枚举列存契约英文名」
 * 的口径不同，是该列在 DDL 中即定义为数值的既定事实（0~3 有 CHECK 兜底）。
 *
 * <p>复盘抽稀与排障时靠它辨认哪些点不可省略：{@link #EVENT} 与 {@link #STATE_CHANGE}
 * 是语义点，抽稀时不能丢。
 */
public enum SampleReason {

    /** 定时采样（距上一条 ≥1s）。 */
    TIMER(0, "定时"),
    /** 位移超阈值。 */
    DISPLACEMENT(1, "位移超阈值"),
    /** 事件点（命中 / 阵亡等）。 */
    EVENT(2, "事件点"),
    /** 状态跳变（在线状态或运动状态变化）。 */
    STATE_CHANGE(3, "状态跳变");

    private final int code;
    private final String label;

    SampleReason(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static SampleReason fromCode(int code) {
        for (SampleReason r : values()) {
            if (r.code == code) {
                return r;
            }
        }
        throw new IllegalArgumentException("未知的采样原因码：" + code);
    }
}
