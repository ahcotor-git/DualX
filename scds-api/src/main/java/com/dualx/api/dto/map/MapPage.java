package com.dualx.api.dto.map;

import com.dualx.api.dto.common.PageMeta;

import java.util.List;

/**
 * 底图分页结果（契约 map.yaml → {@code listMaps} 200 响应体）。
 *
 * @param items 当前页底图
 * @param meta  分页元信息
 */
public record MapPage(List<MapDetail> items, PageMeta meta) {
}
