package com.dualx.core.replay;

import com.dualx.core.domain.ExerciseUnit;
import com.dualx.core.domain.ExportFormat;
import com.dualx.core.push.TeamSummary;
import com.dualx.core.support.SheetWriter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 复盘导出报表的内容生成（契约 {@code downloadExport} 的产物）：把统计与编成台账渲染成
 * XLSX（每张表一个标签页）或 CSV（纵向拼长表）。
 *
 * <p><b>内容范围</b>：统计三表（队伍战损 / 命中排行 / 命中部位分布）+ 编成台账，
 * <b>不含逐点轨迹</b>——轨迹是数十万行的时序流，塞进表格既读不动也不比 API 更好用。
 * 存活曲线同理不入表：它是给客户端画图的序列，导出的三表是「可以拿去核对与归档的汇总」。</p>
 *
 * <p><b>CSV 为什么加「表类型」列</b>：CSV 只有一张表，四张表要落进同一个文件就只能纵向拼。
 * 每张表先写一行列头（首格固定为 {@code 表类型}），其后是该表的数据行（首格为该表名），
 * 表与表之间空一行。这样人（Excel 里直接看）与机（扫到 {@code 表类型} 就知道下一段换表）
 * 都读得懂，不必约定列宽或位置。</p>
 *
 * <p><b>为什么统计取 {@code buckets=1}</b>：口径必须与 {@code getReplayStatistics} 逐字同源
 * （战损与排行取停演冻结列、部位分布去 {@code t_hit_event} 分组），故仍走
 * {@link ReplayService#statistics}；只是它顺带算出的存活曲线本报表不用，
 * 采样段数取最小以免白算——曲线是 {@code buckets} 段、每段一遍编成，取 1 即只算一段。</p>
 *
 * <p><b>纯函数</b>：不碰磁盘、不读时钟。时刻一律以 {@link Instant#toString()} 的 ISO-8601
 * 原样写出，不做时区渲染——报表是归档件，能无歧义还原比「显示成本地时间」重要。</p>
 */
public final class ReplayExportReport {

    /** 统计口径与 {@code getReplayStatistics} 同源，详见类注释。 */
    public static final int BUCKETS_FOR_EXPORT = 1;

    private static final String SHEET_TEAMS = "队伍战损";
    private static final String SHEET_SHOOTERS = "命中排行";
    private static final String SHEET_HIT_PARTS = "命中部位分布";
    private static final String SHEET_ROSTER = "编成台账";

    /** CSV 长表首列。列头行写它，数据行写表名（见类注释）。 */
    private static final String COLUMN_TABLE_TYPE = "表类型";

    private ReplayExportReport() {
    }

    /**
     * 渲染产物。
     *
     * @param replayId 复盘 ID；只用于产物文件名（前 8 位十六进制），内容不含它
     * @param roster   停演冻结的编成台账（{@code t_exercise_unit}）
     * @param statistics 统计（口径见类注释）
     * @param format   XLSX 或 CSV
     */
    public static Rendered build(UUID replayId, List<ExerciseUnit> roster,
                                 ReplayService.Statistics statistics, ExportFormat format) {
        List<SheetWriter.Sheet> sheets = sheets(roster, statistics);
        String base = "scds-replay-" + replayId.toString().substring(0, 8);
        return format == ExportFormat.CSV
                ? new Rendered(base + ".csv", SheetWriter.csv(longTable(sheets)))
                : new Rendered(base + ".xlsx", SheetWriter.xlsx(sheets));
    }

    /**
     * 产物文件名与字节。{@code fileName} 走 ASCII：中文名要另做 RFC 5987 编码，
     * 而这份文件的用途是「下载后打开」，名字里写什么对操作员没有差别
     * （与 {@code scds-plan-template.xlsx} 同一取向）。
     */
    public record Rendered(String fileName, byte[] content) {
    }

    private static List<SheetWriter.Sheet> sheets(List<ExerciseUnit> roster,
                                                  ReplayService.Statistics statistics) {
        return List.of(
                new SheetWriter.Sheet(SHEET_TEAMS, teamSheet(statistics.teams())),
                new SheetWriter.Sheet(SHEET_SHOOTERS, shooterSheet(statistics.topShooters())),
                new SheetWriter.Sheet(SHEET_HIT_PARTS, hitPartSheet(statistics.hitPartDistribution())),
                new SheetWriter.Sheet(SHEET_ROSTER, rosterSheet(roster)));
    }

    private static List<List<String>> teamSheet(List<TeamSummary> teams) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("队伍", "总人数", "存活", "阵亡", "掉线", "命中"));
        for (TeamSummary team : teams) {
            rows.add(List.of(team.team().name(), value(team.total()), value(team.live()),
                    value(team.dead()), value(team.offline()), value(team.hitsDealt())));
        }
        return rows;
    }

    private static List<List<String>> shooterSheet(List<ReplayService.ShooterStat> shooters) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("马甲编号", "姓名", "命中", "击杀"));
        for (ReplayService.ShooterStat shooter : shooters) {
            rows.add(List.of(Integer.toString(shooter.vestNum()), orEmpty(shooter.name()),
                    Integer.toString(shooter.hitsDealt()), Integer.toString(shooter.kills())));
        }
        return rows;
    }

    private static List<List<String>> hitPartSheet(List<ReplayService.HitPartStat> parts) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("部位", "次数"));
        for (ReplayService.HitPartStat part : parts) {
            rows.add(List.of(part.hitPart().label(), Integer.toString(part.count())));
        }
        return rows;
    }

    /** 台账按编号升序：库里的序是 {@code ORDER BY team, vest_num}，报表里按编号找一行更顺手。 */
    private static List<List<String>> rosterSheet(List<ExerciseUnit> roster) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("马甲编号", "姓名", "队伍", "终局", "阵亡时刻", "击杀", "命中"));
        roster.stream()
                .sorted(Comparator.comparingInt(ExerciseUnit::vestNum))
                .forEach(unit -> rows.add(List.of(
                        Integer.toString(unit.vestNum()),
                        orEmpty(unit.name()),
                        unit.team().name(),
                        unit.finalStatus() == null ? "" : unit.finalStatus().label(),
                        unit.deadAt() == null ? "" : unit.deadAt().toString(),
                        Integer.toString(unit.kills()),
                        Integer.toString(unit.hitsDealt()))));
        return rows;
    }

    /** 四张表纵向拼成一张长表，见类注释里「表类型」列的说明。 */
    private static List<List<String>> longTable(List<SheetWriter.Sheet> sheets) {
        List<List<String>> rows = new ArrayList<>();
        for (SheetWriter.Sheet sheet : sheets) {
            if (!rows.isEmpty()) {
                rows.add(List.of());
            }
            List<List<String>> data = sheet.rows();
            rows.add(prepend(COLUMN_TABLE_TYPE, data.get(0)));
            for (int index = 1; index < data.size(); index++) {
                rows.add(prepend(sheet.name(), data.get(index)));
            }
        }
        return rows;
    }

    private static List<String> prepend(String first, List<String> cells) {
        List<String> row = new ArrayList<>(cells.size() + 1);
        row.add(first);
        row.addAll(cells);
        return row;
    }

    private static String value(Integer number) {
        return number == null ? "" : number.toString();
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }
}
