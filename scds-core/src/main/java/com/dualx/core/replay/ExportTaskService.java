package com.dualx.core.replay;

import com.dualx.core.domain.AsyncTaskState;
import com.dualx.core.domain.ExerciseUnit;
import com.dualx.core.domain.ExportFormat;
import com.dualx.core.domain.ExportTask;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.FileStore;
import com.dualx.core.repository.ExportTaskRepository;
import com.dualx.core.repository.ExerciseUnitRepository;
import com.dualx.core.support.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 复盘导出编排（契约 {@code exportReplay} / {@code getExportTask} / {@code downloadExport}）。
 *
 * <p><b>形态</b>：受理与执行分离——{@link #request} 只写一条 {@code PENDING} 行就返回 202，
 * 真正的渲染与落盘由 {@link #tick()} 扫描执行。这样接口的响应时间与演习规模无关，
 * 客户端拿到 taskId 后轮询进度即可；也让「进程在导出中途重启」变成一条会被下一拍重新拾起的
 * {@code PENDING} 行，而不是一个永远挂着的 HTTP 请求。</p>
 *
 * <p><b>为什么 {@code @Scheduled} 就在本类</b>：与 {@code ExerciseCommandExpiryTask} 同一约定——
 * {@code @EnableScheduling} 只在唯一入口 {@code ScdsNextApplication} 上开启，故本任务在生产进程里
 * 自动跑、在 {@code scds-core} 的测试上下文里不会自动跑（测试直接调 {@link #tick()} / {@link #execute}）。</p>
 *
 * <p><b>为什么 {@link #execute} 不标 {@link Transactional}</b>：它一边写库、一边渲染并写文件。
 * 把一次文件写包进数据库事务里，等于让「磁盘慢」去拉长事务、占住连接；而 {@code t_export_task}
 * 的每次状态流转本身就带「当前态」守卫（{@code markRunning}/{@code markSucceeded} 的影响行数为 0
 * 即说明别人已推进），单条 UPDATE 已足够原子。故这里只做短事务式的多次提交。</p>
 *
 * <p><b>POST 不会返 409</b>：契约给 {@code exportReplay} 写了 409 {@code REPLAY_EXPORT_FAILED}
 * （「导出失败过且未重置」），但契约没有任何重置端点，若照字面把失败态做成「必须重置才能再导」，
 * 一次瞬时故障就会永久卡死该复盘该格式的导出。故本实现的取径是：{@code request} 一律 202
 * （有活跃任务就合并复用，否则新建，新建本身就是对上一次失败的「重置」），
 * 该码的 409 只由 {@link #download} 产生（任务未完成或文件已过期）。</p>
 */
@Service
@EnableConfigurationProperties(ExportTaskService.ExportProperties.class)
public class ExportTaskService {

    private static final Logger log = LoggerFactory.getLogger(ExportTaskService.class);

    /** 扫描周期。导出不是实时路径，取 2s 让「受理→开始跑」的观感接近即时，又不至于空转太频。 */
    private static final long PERIOD_MS = 2000;

    /** 单拍最多执行多少条：渲染与写文件都要占内存，积压时排到下一拍，不把整个队列读进内存。 */
    private static final int BATCH_LIMIT = 10;

    /** 进度档位：渲染前报一次，完成时由 {@code markSucceeded} 置满。见 {@link #execute} 注释。 */
    private static final int PROGRESS_RENDERING = 50;

    /** {@code t_export_task.error_message} 是 VARCHAR(255)，超长直接截断，不让一次写入失败掩盖失败原因。 */
    private static final int MAX_ERROR_LENGTH = 255;

    /** 产物在存储侧的相对路径前缀（相对 {@code scds.files.root}）。 */
    private static final String PATH_PREFIX = "exports/replay/";

    private final ExportTaskRepository tasks;
    private final ReplayService replays;
    private final ExerciseUnitRepository units;
    private final FileStore files;
    private final ExportProperties properties;
    private final Clock clock;

    public ExportTaskService(ExportTaskRepository tasks, ReplayService replays,
                             ExerciseUnitRepository units, FileStore files,
                             ExportProperties properties, Clock clock) {
        this.tasks = tasks;
        this.replays = replays;
        this.units = units;
        this.files = files;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 受理导出：同复盘同格式**并发合并**（契约明文），其余情况新建一条 {@code PENDING}。
     *
     * <p>先 {@code replays.get} 是刻意的：它复用了复盘读侧的 404 判据（{@code REPLAY_NOT_FOUND}，
     * 且把「还在进行中」与「id 不存在」折叠在一起）。若这里另判一套，「这场演习能不能导出」
     * 会在两个入口得到不同答案。</p>
     */
    @Transactional
    public ExportTask request(UUID replayId, ExportFormat format) {
        replays.get(replayId);
        return tasks.findActive(replayId, format).orElseGet(() -> {
            ExportTask task = new ExportTask(UuidV7.generate(), replayId, format,
                    AsyncTaskState.PENDING, 0, null, null, null, null, null, null);
            tasks.insert(task);
            log.info("受理复盘导出：replay={} format={} task={}", replayId, format, task.id());
            return task;
        });
    }

    /**
     * 查询任务。任务不存在、或该 taskId 不属于这条复盘，都是 404 {@code REPLAY_NOT_FOUND}：
     * 「拿别的复盘的 taskId 来查」与「这个 taskId 根本不存在」对客户端是同一件事——
     * 路径上的两个 ID 是一对，其中一个对不上就没有这条资源。不新增错误码（沿用 R1 的 404 口径）。
     */
    @Transactional(readOnly = true)
    public ExportTask get(UUID replayId, UUID taskId) {
        return tasks.findById(taskId)
                .filter(task -> task.replayId().equals(replayId))
                .orElseThrow(() -> new BusinessException(ApiErrorCode.REPLAY_NOT_FOUND,
                        "导出任务不存在：" + taskId));
    }

    /**
     * 取下载内容。
     *
     * <p>三条 409 同码（契约 {@code downloadExport} 只写了 {@code REPLAY_EXPORT_FAILED}）：
     * 任务未完成、文件已过期、文件在存储里丢失。三者的现场处置相同——回列表重新发起一次导出；
     * 而对客户端来说靠 HTTP 状态就能分支，不必再去解析文案。过期判据以库里的 {@code expireAt}
     * 为准而不是看文件在不在：TTL 是「我们承诺的可用期」，到期即失效，与文件是否被清理无关。</p>
     */
    @Transactional(readOnly = true)
    public ReplayExportReport.Rendered download(UUID replayId, UUID taskId) {
        ExportTask task = get(replayId, taskId);
        if (task.state() != AsyncTaskState.SUCCEEDED) {
            throw new BusinessException(ApiErrorCode.REPLAY_EXPORT_FAILED,
                    "导出任务尚未完成：" + taskId + "（state=" + task.state().name() + "）");
        }
        if (task.expireAt() != null && task.expireAt().isBefore(Instant.now(clock))) {
            throw new BusinessException(ApiErrorCode.REPLAY_EXPORT_FAILED,
                    "导出文件已过期：" + taskId + "（expireAt=" + task.expireAt() + "）");
        }
        byte[] content = files.read(task.filePath())
                .orElseThrow(() -> new BusinessException(ApiErrorCode.REPLAY_EXPORT_FAILED,
                        "导出文件已丢失：" + task.filePath()));
        return new ReplayExportReport.Rendered(task.fileName(), content);
    }

    /** 导出执行器：每拍取一批待执行任务，逐条执行。 */
    @Scheduled(fixedDelay = PERIOD_MS)
    public void tick() {
        List<ExportTask> pending = tasks.findPending(BATCH_LIMIT);
        for (ExportTask task : pending) {
            execute(task.id());
        }
    }

    /**
     * 执行一条任务：{@code PENDING → RUNNING → SUCCEEDED / FAILED}。
     *
     * <p>{@code markRunning} 的影响行数是唯一的并发闸门：本进程只有执行器在推进，
     * 抢不到（返回 0）说明这条已被处理过，直接返回即可，不必再查一次状态。</p>
     *
     * <p><b>失败不外抛</b>：任务失败是这条任务自己的结果，要落在 {@code state=FAILED} +
     * {@code errorMessage} 上让客户端看到；往外抛只会让执行器日志多一行、把真正的失败原因
     * 埋在栈里。progress 只报两档也是同一取向——渲染与写盘不可切分，与其编一串假进度，
     * 不如老实说「受理了 / 好了」。</p>
     */
    public void execute(UUID taskId) {
        ExportTask task = tasks.findById(taskId).orElse(null);
        if (task == null || tasks.markRunning(taskId) == 0) {
            return;
        }
        try {
            tasks.updateProgress(taskId, PROGRESS_RENDERING);
            List<ExerciseUnit> roster = units.findByExerciseId(task.replayId());
            ReplayService.Statistics statistics =
                    replays.statistics(task.replayId(), ReplayExportReport.BUCKETS_FOR_EXPORT);
            ReplayExportReport.Rendered rendered = ReplayExportReport.build(
                    task.replayId(), roster, statistics, task.format());
            String path = pathOf(task);
            files.put(path, rendered.content());
            tasks.markSucceeded(taskId, rendered.fileName(), path,
                    Instant.now(clock).plus(properties.expireTtl()));
            log.info("复盘导出完成：task={} replay={} bytes={}",
                    taskId, task.replayId(), rendered.content().length);
        } catch (RuntimeException failure) {
            // 未知的 storage 越界（相对路径非法）也会走到这里；截断到列宽后再落库
            tasks.markFailed(taskId, message(failure));
            log.warn("复盘导出失败：task={} replay={}", taskId, task.replayId(), failure);
        }
    }

    /** 产物路径带 taskId：同一复盘可反复导出，各自的产物互不覆盖（TTL 到期前并存）。 */
    private static String pathOf(ExportTask task) {
        return PATH_PREFIX + task.replayId() + "/" + task.id()
                + (task.format() == ExportFormat.CSV ? ".csv" : ".xlsx");
    }

    /**
     * 失败原因。业务异常带上错误码：{@code AsyncTask.errorMessage} 是客户端唯一能看到的东西，
     * 只写一句中文描述会让「哪个判据否掉的」无法从客户端侧定位（契约把它定义为可展示的字符串）。
     */
    private static String message(RuntimeException failure) {
        String text = failure instanceof BusinessException business
                ? business.code().name() + ": " + business.getMessage()
                : failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

    /**
     * {@code scds.export.*} 的绑定与校验。
     *
     * <p>非法值让**启动失败**：零或负的 TTL 会让刚生成的产物在写入那一刻就已过期，
     * 而现场从配置上看不出任何异常（与 {@code scds.command.sent-ttl} 同一取向）。</p>
     */
    @ConfigurationProperties("scds.export")
    public record ExportProperties(@DefaultValue("24h") Duration expireTtl) {

        public ExportProperties {
            if (expireTtl == null || expireTtl.isZero() || expireTtl.isNegative()) {
                throw new IllegalArgumentException("scds.export.expire-ttl 必须为正的时长，实际 " + expireTtl);
            }
        }
    }
}
