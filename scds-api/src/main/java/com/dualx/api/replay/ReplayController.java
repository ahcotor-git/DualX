package com.dualx.api.replay;

import com.dualx.api.dto.common.AsyncTask;
import com.dualx.api.dto.common.PageMeta;
import com.dualx.api.dto.replay.ExportRequest;
import com.dualx.api.dto.replay.Replay;
import com.dualx.api.dto.replay.ReplayPage;
import com.dualx.api.dto.replay.ReplayStatistics;
import com.dualx.api.dto.replay.TimelineChunk;
import com.dualx.api.error.NotImplementedException;
import com.dualx.core.domain.Exercise;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.replay.ReplayService;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * replay 域接口：复盘列表 / 概览、轨迹分片、统计、异步导出与下载。
 *
 * <p>已定决策：{@code replayId} 与 {@code exerciseId} 同值，本域不建独立 replay 表——
 * 停演时把本场态势落库为一条与演习同 ID 的复盘记录，故路径里的 {@code replayId}
 * 就是演习 ID。</p>
 *
 * <p><b>已接线</b>：{@code listReplays} 与 {@code getReplay} 走 {@link ReplayService}
 * （R1 复盘读侧），本层只做契约形状的搬运（领域 {@link Exercise} → DTO），分页元信息
 * 与 {@link PageRequest} 的换算在此完成。轨迹分片（R2）、统计（R3）与导出（R4）仍抛
 * {@link NotImplementedException}，按 M5 的分段逐步落地。</p>
 */
@RestController
@Validated
public class ReplayController {

    private final ReplayService replays;

    public ReplayController(ReplayService replays) {
        this.replays = replays;
    }

    /** 复盘列表：按结束时间倒序，支持想定名模糊搜索与结束时间区间过滤。 */
    @GetMapping("/api/v1/replays")
    public ReplayPage listReplays(@RequestParam(name = "keyword", required = false) String keyword,
                                  @RequestParam(name = "from", required = false) Instant from,
                                  @RequestParam(name = "to", required = false) Instant to,
                                  @RequestParam(name = "page", required = false, defaultValue = "1")
                                  @Min(1) int page,
                                  @RequestParam(name = "size", required = false, defaultValue = "20")
                                  @Min(1) @Max(PageRequest.MAX_SIZE) int size) {
        Page<Exercise> result = replays.list(keyword, from, to, PageRequest.of(page, size));
        return new ReplayPage(result.items().stream().map(ReplayController::toReplay).toList(),
                new PageMeta(result.page(), result.size(), result.total()));
    }

    /** 复盘概览：返回场次元信息，不含轨迹。 */
    @GetMapping("/api/v1/replays/{replayId}")
    public Replay getReplay(@PathVariable("replayId") String replayId) {
        return toReplay(replays.get(replayId(replayId)));
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

    // ── 契约形状的搬运 ──────────────────────────────────────────────────────

    /**
     * 路径参数 {@code replayId} 转 UUID。解析失败按「复盘不存在」处理而不是 400：一个拼不出来的
     * id 必然不对应 {@code t_exercise} 里的任何一行，语义上就是不存在（与
     * {@code ExerciseController.planId} / {@code MapController.mapId} 同一取向）。
     */
    private static UUID replayId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ApiErrorCode.REPLAY_NOT_FOUND, "复盘不存在：" + raw);
        }
    }

    /**
     * 领域演习行 → 契约 DTO。
     *
     * <p>{@code durationSeconds} 取 {@code endedAt - startedAt} 这个**定值**，而不是像当前演习
     * （{@code ExerciseController.toExercise}）那样用 {@code Instant.now()} 清零头：复盘是既成事实，
     * 同一条记录反复读到的时长必须一致，否则进度条与统计会随读取时刻漂移。</p>
     */
    private static Replay toReplay(Exercise exercise) {
        return new Replay(exercise.id().toString(), exercise.planId().toString(), exercise.planName(),
                exercise.mapId() == null ? null : exercise.mapId().toString(), exercise.battleMode(),
                exercise.startedAt(), exercise.endedAt(),
                exercise.endedAt() == null ? null
                        : Duration.between(exercise.startedAt(), exercise.endedAt()).toSeconds(),
                exercise.rosterCount(), exercise.hitCount(), exercise.deadCount());
    }
}
