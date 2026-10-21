package com.dualx.api.dto.map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * UWB 解算坐标系参数（契约 domain.yaml → {@code UwbMeta}，旧系统 uwb_meta.json）。
 *
 * <p><b>本类型就是冻结结构本身</b>：{@code bs1}~{@code bs8} 与两个长宽基准都在顶层，
 * 不带 {@code anchors} 包装层。因此它的 JSON 序列化结果与库列 {@code t_map.uwb_meta}、
 * {@code scds-solver} 的 {@code Geometry} 读取口径逐字同形——API、库、解算三处零转换。</p>
 *
 * <p><b>基站编号与 41B 帧测距数组固定一一对应</b>：{@code bs(i+1)} ↔ {@code ranges[i]}，
 * 不可错位。bs1~bs4 为矩形四角，必须给全（bs1↔bs2 提供长度基准、bs1↔bs3 提供宽度基准），
 * bs5~bs8 可选。坐标为底图像素（见 {@link UwbAnchorPoint}），不是归一化 0~1。</p>
 *
 * <p>本类型同时作为 {@code putUwbMeta} 请求体与 {@code getUwbMeta}/{@code putUwbMeta} 响应体。</p>
 *
 * @param realLengthCm bs1↔bs2 现场实测长度（正整数厘米）
 * @param realWidthCm  bs1↔bs3 现场实测宽度（正整数厘米）
 */
public record UwbMeta(
        @NotNull @Valid UwbAnchorPoint bs1,
        @NotNull @Valid UwbAnchorPoint bs2,
        @NotNull @Valid UwbAnchorPoint bs3,
        @NotNull @Valid UwbAnchorPoint bs4,
        @Valid UwbAnchorPoint bs5,
        @Valid UwbAnchorPoint bs6,
        @Valid UwbAnchorPoint bs7,
        @Valid UwbAnchorPoint bs8,
        @NotNull Double realLengthCm,
        @NotNull Double realWidthCm) {
}
