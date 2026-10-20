package com.dualx.api.replay;

import com.dualx.api.dto.common.AsyncTask;
import com.dualx.api.dto.replay.ExportRequest;
import com.dualx.api.dto.replay.Replay;
import com.dualx.api.dto.replay.ReplayPage;
import com.dualx.api.dto.replay.ReplayStatistics;
import com.dualx.api.dto.replay.TimelineChunk;
import com.dualx.api.error.NotImplementedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * replay 域接口骨架：复盘列表 / 概览、轨迹分片、统计、异步导出与下载。
 *
 * <p>已定决策：{@code replayId} 与 {@code exerciseId} 同值，本域不建独立 replay 表——
 * 停演时把本场态势落库为一条与演习同 ID 的复盘记录，故路径里的 {@code replayId}
 * 就是演习 ID。</p>
 *
 * <p>本类只登记路由、参数与 DTO（契约见 docs/api/paths/replay.yaml），
 * 方法体一律抛 {@link NotImplementedException}，业务实现归属服务层。</p>
 */
@RestController
@Validated
public class ReplayController {

    /** 复盘列表：按结束时间倒序，支持想定名模糊搜索与结束时间区间过滤。 */
    @GetMapping("/api/v1/replays")
    public ReplayPage listReplays(@RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) Instant from,
                                  @RequestParam(required = false) Instant to,
                                  @RequestParam(required = false, defaultValue = "1") @Min(1) int page,
                                  @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(200) int size) {
        throw new NotImplementedException("listReplays");
    }

    /** 复盘概览：返回场次元信息，不含轨迹。 */
    @GetMapping("/api/v1/replays/{replayId}")
    public Replay getReplay(@PathVariable String replayId) {
        throw new NotImplementedException("getReplay");
    }

    /** 轨迹分片拉取：按 cursor 分片，响应 nextCursor 为 null 表示到末尾。 */
    @GetMapping("/api/v1/replays/{replayId}/timeline")
    public TimelineChunk getReplayTimeline(@PathVariable String replayId,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false, defaultValue = "5000")
                                           @Min(1) @Max(20000) int limit,
                                           @RequestParam(required = false) @Size(max = 200) List<Integer> vestNums,
                                           @RequestParam(required = false, defaultValue = "1")
                                           @Min(1) @Max(60) int stride) {
        throw new NotImplementedException("getReplayTimeline");
    }

    /** 复盘统计：队伍战损、命中排行、命中部位分布、存活人数曲线。 */
    @GetMapping("/api/v1/replays/{replayId}/statistics")
    public ReplayStatistics getReplayStatistics(@PathVariable String replayId,
                                                @RequestParam(required = false, defaultValue = "120")
                                                @Min(10) @Max(500) int timelineBuckets) {
        throw new NotImplementedException("getReplayStatistics");
    }

    /** 发起复盘导出（异步）：返回 202 + AsyncTask，客户端轮询进度。 */
    @PostMapping("/api/v1/replays/{replayId}/exports")
    public AsyncTask exportReplay(@PathVariable String replayId, @RequestBody @Valid ExportRequest body) {
        throw new NotImplementedException("exportReplay");
    }

    /** 查询导出任务进度。 */
    @GetMapping("/api/v1/replays/{replayId}/exports/{taskId}")
    public AsyncTask getExportTask(@PathVariable String replayId, @PathVariable String taskId) {
        throw new NotImplementedException("getExportTask");
    }

    /** 下载导出文件：xlsx/csv 二进制，仅任务 SUCCEEDED 且未过期时可下载。 */
    @GetMapping("/api/v1/replays/{replayId}/exports/{taskId}/download")
    public ResponseEntity<byte[]> downloadExport(@PathVariable String replayId, @PathVariable String taskId) {
        throw new NotImplementedException("downloadExport");
    }
}
