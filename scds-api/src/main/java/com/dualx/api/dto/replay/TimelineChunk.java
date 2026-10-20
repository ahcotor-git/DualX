package com.dualx.api.dto.replay;

import java.util.List;

/**
 * 轨迹回放分片（契约 domain.yaml → {@code TimelineChunk}），
 * {@code GET /api/v1/replays/{replayId}/timeline} 的响应体。
 *
 * <p>客户端按 {@code nextCursor} 连续拉取，不要一次要全量。</p>
 *
 * @param samples    本片采样点
 * @param nextCursor 下一页游标，为 null 表示已到末尾
 */
public record TimelineChunk(List<TrackSample> samples, String nextCursor) {
}
