package com.dualx.api.replay;

import com.dualx.api.dto.common.AsyncTask;
import com.dualx.api.dto.common.PageMeta;
import com.dualx.api.dto.replay.ExportRequest;
import com.dualx.api.dto.replay.Replay;
import com.dualx.api.dto.replay.ReplayPage;
import com.dualx.api.dto.replay.ReplayStatistics;
import com.dualx.api.dto.replay.TimelineChunk;
import com.dualx.api.dto.replay.TrackSample;
import com.dualx.core.domain.AsyncTaskState;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExportTask;
import com.dualx.core.domain.GeoPoint;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.FileUrlSigner;
import com.dualx.core.replay.ExportTaskService;
import com.dualx.core.replay.ReplayExportReport;
import com.dualx.core.replay.ReplayService;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * （R1 复盘读侧），{@code getReplayTimeline} 亦走 {@link ReplayService#timeline}
 * （R2 轨迹分片），{@code getReplayStatistics} 走 {@link ReplayService#statistics}
 * （R3 统计），导出三端点（R4）走 {@link ExportTaskService}。本层只做契约形状的搬运
 * （领域 {@link Exercise} / 领域 {@code TrackSample} / core 统计结果 / 导出任务 → DTO），
 * 分页元信息与 {@link PageRequest} 的换算、以及签名下载 URL 的组装在此完成。</p>
 */
@RestController
@Validated
public class ReplayController {

    private final ReplayService replays;

    /** 导出受理 / 查询 / 下载的编排（R4）。受理与执行分离的形态见该类注释。 */
    private final ExportTaskService exports;

    /**
     * 把产物路径签成 {@code AsyncTask.downloadUrl}。
     *
     * <p>签名在 core（密钥与 TTL 属存储域），URL 形状属接口层——与
     * {@code MapController.imageUrl} 同一取向、同一组查询参数名。</p>
     */
    private final FileUrlSigner signer;

    /** 契约 {@code downloadExport} 的两种 200 内容类型。xlsx 那个是 OOXML 的冻结 MIME。 */
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String CSV_MIME = "text/csv";

    /** 产物扩展名，用来在下载时还原内容类型（见 {@link #mediaTypeOf}）。 */
    private static final String CSV_SUFFIX = ".csv";

    public ReplayController(ReplayService replays, ExportTaskService exports, FileUrlSigner signer) {
        this.replays = replays;
        this.exports = exports;
        this.signer = signer;
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
        ReplayService.Chunk chunk = replays.timeline(replayId(replayId), cursor, limit, vestNums, stride);
        return new TimelineChunk(
                chunk.samples().stream().map(ReplayController::toTrackSample).toList(),
                chunk.nextCursor());
    }

    /** 复盘统计：队伍战损、命中排行、命中部位分布、存活人数曲线。 */
    @GetMapping("/api/v1/replays/{replayId}/statistics")
    public ReplayStatistics getReplayStatistics(@PathVariable String replayId,
                                                @RequestParam(required = false, defaultValue = "120")
                                                @Min(10) @Max(500) int timelineBuckets) {
        UUID id = replayId(replayId);
        ReplayService.Statistics statistics = replays.statistics(id, timelineBuckets);
        return new ReplayStatistics(id.toString(), statistics.teams(),
                statistics.topShooters().stream()
                        .map(s -> new ReplayStatistics.TopShooter(
                                s.vestNum(), s.name(), s.hitsDealt(), s.kills()))
                        .toList(),
                statistics.hitPartDistribution().stream()
                        .map(p -> new ReplayStatistics.HitPartCount(p.hitPart(), p.count()))
                        .toList(),
                statistics.timeline().stream()
                        .map(b -> new ReplayStatistics.TimelineBucket(b.t(), b.liveByTeam()))
                        .toList());
    }

    /**
     * 发起复盘导出（异步）。
     *
     * <p>状态码固定 202：{@code request} 要么新建一条 {@code PENDING}，要么复用同一复盘同一格式
     * 的活跃任务，两者对客户端都是「已受理，去轮询」——不会出现 200/202 两种成功码。
     * 契约给本操作列的 409 落在 {@link #downloadExport}（见 {@code ExportTaskService} 类注释）。</p>
     */
    @PostMapping("/api/v1/replays/{replayId}/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AsyncTask exportReplay(@PathVariable String replayId, @RequestBody @Valid ExportRequest body) {
        return toAsyncTask(exports.request(replayId(replayId), body.format()));
    }

    /** 查询导出任务进度。 */
    @GetMapping("/api/v1/replays/{replayId}/exports/{taskId}")
    public AsyncTask getExportTask(@PathVariable String replayId, @PathVariable String taskId) {
        return toAsyncTask(exports.get(replayId(replayId), taskId(taskId)));
    }

    /**
     * 下载导出文件：xlsx/csv 二进制，仅任务 SUCCEEDED 且未过期时可下载。
     *
     * <p>{@code Content-Type} 按产物格式给：契约列了 xlsx 与 csv 两种 200 内容类型。</p>
     */
    @GetMapping("/api/v1/replays/{replayId}/exports/{taskId}/download")
    public ResponseEntity<byte[]> downloadExport(@PathVariable String replayId, @PathVariable String taskId) {
        ReplayExportReport.Rendered rendered = exports.download(replayId(replayId), taskId(taskId));
        return ResponseEntity.ok()
                .contentType(mediaTypeOf(rendered.fileName()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(rendered.fileName()).build().toString())
                .body(rendered.content());
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
     * 路径参数 {@code taskId} 转 UUID，理由同 {@link #replayId(String)}：拼不出来的 taskId
     * 必然不对应 {@code t_export_task} 里的任何一行，语义上就是不存在（服务层的 404 口径一致）。
     */
    private static UUID taskId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ApiErrorCode.REPLAY_NOT_FOUND, "导出任务不存在：" + raw);
        }
    }

    /**
     * 导出任务 → 契约 {@code AsyncTask}。
     *
     * <p>{@code downloadUrl} 只在 {@code SUCCEEDED} 且已有产物路径时给（契约：「state=SUCCEEDED
     * 时有效」）。每次查询都**重新签发**而不是把 URL 存库：签名 TTL 只有 15 分钟，缓存下来的 URL
     * 会在客户端手里悄悄过期，而重新签发只是一次 HMAC 计算（与 {@code MapController.imageUrl}
     * 同一取向）。</p>
     */
    private AsyncTask toAsyncTask(ExportTask task) {
        String downloadUrl = task.state() == AsyncTaskState.SUCCEEDED && task.filePath() != null
                ? signedFileUrl(task.filePath())
                : null;
        return new AsyncTask(task.id().toString(), task.state(), task.progressPct(), task.fileName(),
                downloadUrl, task.expireAt(), task.errorMessage());
    }

    /**
     * 为产物路径签发短时效访问 URL。
     *
     * <p>返回**根相对路径**，不带 scheme/host：服务端不猜部署形态下的对外地址（本地实例 +
     * 云端反向代理）。三个查询参数的名字属于接口层，必须与 {@code paths/files.yaml} 逐字一致
     * ——客户端只会原样转发，它无法自己纠正。</p>
     */
    private String signedFileUrl(String path) {
        FileUrlSigner.SignedFileUrl signed = signer.sign(path);
        return "/api/v1/files?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8)
                + "&exp=" + URLEncoder.encode(signed.exp(), StandardCharsets.UTF_8)
                + "&signature=" + URLEncoder.encode(signed.signature(), StandardCharsets.UTF_8);
    }

    /**
     * 产物文件名 → {@code Content-Type}。
     *
     * <p>按扩展名判定而不是把 MIME 随任务存库：产物只有 {@code .xlsx} / {@code .csv} 两种，
     * 而扩展名由生成侧（{@code ReplayExportReport}）与内容同步决定，不会单独漂移。</p>
     */
    private static MediaType mediaTypeOf(String fileName) {
        return MediaType.parseMediaType(fileName.endsWith(CSV_SUFFIX) ? CSV_MIME : XLSX_MIME);
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

    /**
     * 领域采样点 → 契约 DTO。
     *
     * <p>四个坐标字段按原样透出、不用 {@code coordinateSystem} 做取舍：库里哪一套有效就只写哪一套，
     * 另一套本就是 null；在读取侧再判一次等于把「同时存两套坐标」的约定重新解释一遍，
     * 而设计已把 Y 轴映射放在写入前完成，客户端不得自行换算。</p>
     *
     * <p>{@code t} 用 {@code tMs} 原值——它本来就是「相对开演的毫秒偏移」，这正是契约要的语义，
     * 不能在这里加上 {@code startedAt} 变成绝对时间。</p>
     */
    private static TrackSample toTrackSample(com.dualx.core.domain.TrackSample sample) {
        GeoPoint position = sample.position();
        return new TrackSample(sample.tMs(), sample.vestNum(),
                position == null ? null : position.xPct(),
                position == null ? null : position.yPct(),
                position == null ? null : position.lng(),
                position == null ? null : position.lat(),
                sample.hp(), sample.status());
    }
}
