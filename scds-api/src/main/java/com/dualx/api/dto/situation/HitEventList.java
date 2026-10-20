package com.dualx.api.dto.situation;

import java.util.List;

/**
 * 命中事件列表（契约 paths/situation.yaml → {@code situationHits} 200 的内联响应对象）。
 *
 * @param items     命中事件
 * @param latestSeq 本批最大序号，客户端据此做下次增量拉取；无事件时为 null
 */
public record HitEventList(List<HitEvent> items, Long latestSeq) {
}
