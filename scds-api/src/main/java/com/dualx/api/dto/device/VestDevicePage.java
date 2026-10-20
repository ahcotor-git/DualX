package com.dualx.api.dto.device;

import com.dualx.api.dto.common.PageMeta;

import java.util.List;

/**
 * 马甲台账分页结果（契约 domain.yaml → {@code VestDevicePage}）。
 *
 * @param items 当前页数据
 * @param meta  分页元信息
 */
public record VestDevicePage(List<VestDevice> items, PageMeta meta) {
}
