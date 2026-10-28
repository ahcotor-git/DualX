package com.dualx.core.replay;

import com.dualx.core.domain.AsyncTaskState;
import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.CoordinateSystem;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseEndReason;
import com.dualx.core.domain.ExerciseUnit;
import com.dualx.core.domain.ExportFormat;
import com.dualx.core.domain.ExportTask;
import com.dualx.core.domain.GeoPoint;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.FileStore;
import com.dualx.core.push.TeamSummary;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.repository.ExerciseUnitRepository;
import com.dualx.core.repository.ExportTaskRepository;
import com.dualx.core.support.SqlTime;
import com.dualx.core.support.UuidV7;
import com.dualx.protocol.HitPart;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 复盘导出的验收（契约 {@code docs/api/paths/replay.yaml} 的 {@code exportReplay} /
 * {@code getExportTask} / {@code downloadExport}）。
 *
 * <p>真库真事务真落盘：受理 → 扫描执行 → 下载这条链上的每一环都是「库里的状态 + 存储里的字节」，
 * 用替身替掉任一侧都等于把要验的那件事本身验掉。执行器在测试上下文里不会自动跑
 * （{@code @EnableScheduling} 只在 {@code ScdsNextApplication} 上开启），故这里的用例直接调
 * {@link ExportTaskService#tick()} / {@link ExportTaskService#execute}。
 *
 * <p>报表内容的断言用手工构造的统计与编成，不经数据库——那是纯函数，值域断言比走一遍库更准。
 */
@SpringBootTest
@Transactional
class ExportTaskServiceIT {

    /**
     * 产物落盘的一次性根目录，建在本模块的 {@code target/} 下。
     *
     * <p>{@code scds.files.root} 默认落 {@code ${user.home}/.scds/files}：事务回滚只管数据库、
     * 不会删已落盘的文件，让 IT 往开发机主目录写就会污染环境并留下垃圾。
     */
    private static final String FILES_ROOT =
            Path.of("target", "test-files", UUID.randomUUID().toString()).toString();

    private static final Instant START = Instant.parse("2026-10-20T08:00:00.000Z");
    private static final Instant END = Instant.parse("2026-10-20T09:00:00.000Z");

    @Autowired
    private ExportTaskService exports;

    @Autowired
    private ExportTaskRepository tasks;

    @Autowired
    private ExerciseRepository exercises;

    @Autowired
    private ExerciseUnitRepository units;

    @Autowired
    private FileStore store;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void fileStorage(DynamicPropertyRegistry registry) {
        registry.add("scds.files.root", () -> FILES_ROOT);
    }

    // ── 受理 ────────────────────────────────────────────────────────────────

    @Test
    void request_createsPendingTask() {
        UUID replayId = seedReplay();

        ExportTask task = exports.request(replayId, ExportFormat.XLSX);

        assertThat(task.state()).isEqualTo(AsyncTaskState.PENDING);
        assertThat(task.progressPct()).isZero();
        assertThat(task.fileName()).isNull();
        assertThat(tasks.findById(task.id())).isPresent();
    }

    @Test
    void request_secondCallMergesIntoTheActiveTask() {
        UUID replayId = seedReplay();

        ExportTask first = exports.request(replayId, ExportFormat.XLSX);
        ExportTask second = exports.request(replayId, ExportFormat.XLSX);

        assertThat(second.id()).isEqualTo(first.id());
    }

    /** 格式不同不该被合并：同一个复盘的 XLSX 与 CSV 是两份产物。 */
    @Test
    void request_differentFormatIsNotMerged() {
        UUID replayId = seedReplay();

        ExportTask xlsx = exports.request(replayId, ExportFormat.XLSX);
        ExportTask csv = exports.request(replayId, ExportFormat.CSV);

        assertThat(csv.id()).isNotEqualTo(xlsx.id());
    }

    @Test
    void request_runningExercise_isNotFound() {
        UUID running = seedRunning();

        assertCode(() -> exports.request(running, ExportFormat.XLSX), ApiErrorCode.REPLAY_NOT_FOUND);
    }

    // ── 执行 ────────────────────────────────────────────────────────────────

    @Test
    void tick_rendersXlsxAndStoresIt() {
        UUID replayId = seedReplay();
        UUID taskId = exports.request(replayId, ExportFormat.XLSX).id();

        exports.tick();

        ExportTask done = tasks.findById(taskId).orElseThrow();
        assertThat(done.state()).isEqualTo(AsyncTaskState.SUCCEEDED);
        assertThat(done.progressPct()).isEqualTo(100);
        assertThat(done.fileName()).endsWith(".xlsx");
        byte[] content = store.read(done.filePath()).orElseThrow();
        assertThat(content).startsWith('P', 'K');
    }

    @Test
    void tick_rendersCsvWithBom() {
        UUID replayId = seedReplay();
        UUID taskId = exports.request(replayId, ExportFormat.CSV).id();

        exports.tick();

        ExportTask done = tasks.findById(taskId).orElseThrow();
        assertThat(done.state()).isEqualTo(AsyncTaskState.SUCCEEDED);
        assertThat(done.fileName()).endsWith(".csv");
        byte[] content = store.read(done.filePath()).orElseThrow();
        assertThat(content).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
    }

    /**
     * 已完成的任务再被扫到不该重做：{@code markRunning} 的「当前态」守卫返回 0，
     * 执行器直接返回。产物的 {@code filePath} 保持不变就是「没有第二次落盘」的判据。
     */
    @Test
    void execute_isSingleShot() {
        UUID replayId = seedReplay();
        UUID taskId = exports.request(replayId, ExportFormat.XLSX).id();
        exports.execute(taskId);
        String path = tasks.findById(taskId).orElseThrow().filePath();

        exports.execute(taskId);

        ExportTask after = tasks.findById(taskId).orElseThrow();
        assertThat(after.state()).isEqualTo(AsyncTaskState.SUCCEEDED);
        assertThat(after.filePath()).isEqualTo(path);
    }

    /**
     * 受理后那条复盘被删掉了（真库允许删，服务层不做乐观假设）：执行器在 {@code replays.get} 上
     * 拿到 404，把任务判失败并留下错误码，而不是把异常冒出去打断整批扫描。
     *
     * <p>删的是「库里的那一行」——{@code ExerciseRepository} 只提供正常生命周期方法、
     * 没有删除入口（生产也不该有），故编成走仓储的 {@code deleteByExerciseId}，
     * 演习主表用 {@code JdbcClient} 直删；外层事务结束会整体回滚。</p>
     */
    @Test
    void tick_skipsReplaysThatNoLongerExist() {
        UUID replayId = seedReplay();
        UUID taskId = exports.request(replayId, ExportFormat.XLSX).id();
        units.deleteByExerciseId(replayId);
        jdbc.sql("DELETE FROM t_exercise WHERE id = :id")
                .param("id", replayId.toString()).update();

        exports.tick();

        ExportTask failed = tasks.findById(taskId).orElseThrow();
        assertThat(failed.state()).isEqualTo(AsyncTaskState.FAILED);
        assertThat(failed.errorMessage()).contains(ApiErrorCode.REPLAY_NOT_FOUND.name());
    }

    // ── 查询与下载 ───────────────────────────────────────────────────────────

    @Test
    void get_unknownTask_isNotFound() {
        UUID replayId = seedReplay();

        assertCode(() -> exports.get(replayId, UuidV7.generate()), ApiErrorCode.REPLAY_NOT_FOUND);
    }

    /** taskId 存在但属于另一条复盘：路径上的两个 ID 是一对，对不上就是没有这条资源。 */
    @Test
    void get_taskOfAnotherReplay_isNotFound() {
        UUID replayId = seedReplay();
        UUID otherReplay = seedReplay();
        UUID taskId = exports.request(replayId, ExportFormat.XLSX).id();

        assertCode(() -> exports.get(otherReplay, taskId), ApiErrorCode.REPLAY_NOT_FOUND);
    }

    @Test
    void download_beforeCompletion_isConflict() {
        UUID replayId = seedReplay();
        UUID taskId = exports.request(replayId, ExportFormat.XLSX).id();

        assertCode(() -> exports.download(replayId, taskId), ApiErrorCode.REPLAY_EXPORT_FAILED);
    }

    @Test
    void download_expiredFile_isConflict() {
        UUID replayId = seedReplay();
        UUID taskId = exports.request(replayId, ExportFormat.XLSX).id();
        exports.execute(taskId);
        // TTL 到期判据取库里的 expire_at；把它改到过去即等价于「已过期」，不必等真实 24 小时。
        // 任务此刻已是 SUCCEEDED，markSucceeded 的「当前态」守卫会拒绝再写，故直接改这一列。
        jdbc.sql("UPDATE t_export_task SET expire_at = :expiredAt WHERE id = :id")
                .param("expiredAt", SqlTime.toDb(Instant.now().minusSeconds(60)))
                .param("id", taskId.toString())
                .update();

        assertCode(() -> exports.download(replayId, taskId), ApiErrorCode.REPLAY_EXPORT_FAILED);
    }

    /** 库说成功、存储里却没有这个文件（被手工删了）：同样是「重新导一次」，不抛 500。 */
    @Test
    void download_missingFile_isConflict() {
        UUID replayId = seedReplay();
        UUID taskId = exports.request(replayId, ExportFormat.XLSX).id();
        exports.execute(taskId);
        store.delete(tasks.findById(taskId).orElseThrow().filePath());

        assertCode(() -> exports.download(replayId, taskId), ApiErrorCode.REPLAY_EXPORT_FAILED);
    }

    // ── 报表内容（纯函数，不经库）────────────────────────────────────────────

    @Test
    void report_csvIsOneLongTableWithTableTypeColumn() {
        ReplayExportReport.Rendered rendered =
                ReplayExportReport.build(UUID.randomUUID(), roster(), statistics(), ExportFormat.CSV);

        String csv = new String(rendered.content(), StandardCharsets.UTF_8);
        assertThat(rendered.fileName()).endsWith(".csv");
        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).contains("表类型,队伍,总人数,存活,阵亡,掉线,命中");
        assertThat(csv).contains("队伍战损,RED,2,1,1,0,7");
        assertThat(csv).contains("表类型,马甲编号,姓名,命中,击杀");
        assertThat(csv).contains("命中排行,1002,李四,7,3");
        assertThat(csv).contains("表类型,部位,次数");
        assertThat(csv).contains("命中部位分布,头部,4");
        assertThat(csv).contains("编成台账,1001,张三,RED,阵亡,");
    }

    @Test
    void report_xlsxHasFourNamedSheets() throws IOException {
        ReplayExportReport.Rendered rendered =
                ReplayExportReport.build(UUID.randomUUID(), roster(), statistics(), ExportFormat.XLSX);

        assertThat(rendered.fileName()).endsWith(".xlsx");
        assertThat(rendered.fileName()).contains("scds-replay-");
        String workbook = part(rendered.content(), "xl/workbook.xml");
        assertThat(workbook).contains("name=\"队伍战损\"", "name=\"命中排行\"",
                "name=\"命中部位分布\"", "name=\"编成台账\"");
    }

    /** 台账按编号升序，且缺姓名/未阵亡的格子留空而不是写 {@code null}。 */
    @Test
    void report_rosterIsSortedAndBlanksAreEmpty() {
        List<ExerciseUnit> roster = new ArrayList<>();
        roster.add(unit(1002, "李四", Team.BLUE, VestStatus.LIVE, null, 3, 7));
        roster.add(unit(1001, null, Team.RED, VestStatus.DIE, END, 0, 0));

        ReplayExportReport.Rendered rendered =
                ReplayExportReport.build(UUID.randomUUID(), roster,
                        new ReplayService.Statistics(List.of(), List.of(), List.of(), List.of()),
                        ExportFormat.CSV);

        String csv = new String(rendered.content(), StandardCharsets.UTF_8);
        assertThat(csv).contains("编成台账,1001,,RED,阵亡,2026-10-20T09:00:00Z,0,0");
        assertThat(csv).contains("编成台账,1002,李四,BLUE,存活,,3,7");
        assertThat(csv.indexOf("编成台账,1001")).isLessThan(csv.indexOf("编成台账,1002"));
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    private List<ExerciseUnit> roster() {
        List<ExerciseUnit> roster = new ArrayList<>();
        roster.add(unit(1001, "张三", Team.RED, VestStatus.DIE, END, 0, 0));
        roster.add(unit(1002, "李四", Team.RED, VestStatus.LIVE, null, 3, 7));
        return roster;
    }

    private static ReplayService.Statistics statistics() {
        return new ReplayService.Statistics(
                List.of(new TeamSummary(Team.RED, 2, 1, 1, 0, 7)),
                List.of(new ReplayService.ShooterStat(1002, "李四", 7, 3)),
                List.of(new ReplayService.HitPartStat(HitPart.HEAD, 4)),
                List.of());
    }

    private static ExerciseUnit unit(int vestNum, String name, Team team, VestStatus status,
                                     Instant deadAt, int kills, int hitsDealt) {
        return new ExerciseUnit(0L, UUID.randomUUID(), vestNum, name, team, status, deadAt,
                kills, hitsDealt, null, null);
    }

    private UUID seedReplay() {
        UUID id = UuidV7.generate();
        exercises.insert(new Exercise(id, UuidV7.generate(), "it_ex_导出", null, BattleMode.BATTLE,
                START, null, 2, 0, 0, null, null));
        exercises.markEnded(id, END, 1, 1, ExerciseEndReason.NORMAL);
        units.insert(new ExerciseUnit(0L, id, 1001, "张三", Team.RED, null, null, 0, 0, null, null));
        units.freezeOutcome(id, 1001, VestStatus.DIE, END, 0, 0);
        return id;
    }

    private UUID seedRunning() {
        UUID id = UuidV7.generate();
        exercises.insert(new Exercise(id, UuidV7.generate(), "it_ex_进行中", null, BattleMode.BATTLE,
                START, null, 2, 0, 0, null, null));
        return id;
    }

    private static String part(byte[] xlsx, String name) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("XLSX 里没有部件：" + name);
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ApiErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
