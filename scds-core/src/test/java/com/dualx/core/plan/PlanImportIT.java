package com.dualx.core.plan;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.ExportFormat;
import com.dualx.core.domain.PollMode;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.domain.Vest;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.repository.PlanRepository;
import com.dualx.core.repository.VestRepository;
import com.dualx.protocol.Team;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 想定模板下载与整文件导入的验收（契约 {@code downloadPlanTemplate} / {@code importPlan}）。
 *
 * <p>真库真事务：导入要过编成的三条语义校验（编号在台账、不重复、不跨队），那三条读的是
 * {@code t_vest} 与 {@code t_plan_roster}，替掉任何一边都会把「整文件校验通过才落库」这件事
 * 本身验掉。{@code @Transactional} 让每个用例的想定、编成与台账行一起回滚。</p>
 */
@SpringBootTest
@Transactional
class PlanImportIT {

    @Autowired
    private PlanService service;

    @Autowired
    private PlanRepository plans;

    @Autowired
    private VestRepository vests;

    private static final List<String> HEADERS =
            List.of("马甲编号", "姓名", "队伍", "血量", "弹夹1", "弹夹2", "轮询模式");

    /** 与模板里的示例行同值：导入时必须被跳过。 */
    private static final List<String> EXAMPLE =
            List.of("1001", "张三", "RED", "100", "30", "30", "AUTO");

    // ── 模板下载 ────────────────────────────────────────────────────────────

    @Test
    void templateXlsx_isAWorkbookCarryingTheHeaderAndTheExampleRow() {
        PlanRosterSheet.TemplateFile xlsx = service.downloadTemplate(ExportFormat.XLSX);

        assertThat(xlsx.fileName()).endsWith(".xlsx");
        assertThat(xlsx.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        // PK\x03\x04：XLSX 就是个 zip，写出来的字节必须真的是它
        assertThat(xlsx.content()).startsWith((byte) 'P', (byte) 'K', (byte) 3, (byte) 4);
        assertThat(PlanRosterSheet.grid(xlsx.content())).containsExactly(HEADERS, EXAMPLE);
    }

    @Test
    void templateCsv_carriesTheSameSheetAsXlsxAndOpensInExcelWithChineseHeaders() {
        byte[] csv = service.downloadTemplate(ExportFormat.CSV).content();

        assertThat(service.downloadTemplate(ExportFormat.CSV).contentType()).isEqualTo("text/csv");
        // 带 UTF-8 BOM，否则 Windows 版 Excel 按 GBK 打开会看到乱码表头
        assertThat(csv).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(PlanRosterSheet.grid(csv))
                .isEqualTo(PlanRosterSheet.grid(service.downloadTemplate(ExportFormat.XLSX).content()));
    }

    @Test
    void template_withoutFormat_fallsBackToTheContractDefault() {
        assertThat(service.downloadTemplate(null).fileName()).endsWith(".xlsx");
    }

    // ── 导入 ────────────────────────────────────────────────────────────────

    @Test
    void importCsv_createsThePlanTogetherWithItsRoster() {
        seedVest(101);
        seedVest(102);
        byte[] file = csv("马甲编号,姓名,队伍,血量,弹夹1,弹夹2,轮询模式",
                "101,李四,红,80,20,15,手动",
                "102,王五,BLUE,100,30,30,AUTO");

        PlanService.PlanView view = service.importPlan("营区对抗.csv", null, file);

        assertThat(view.plan().name()).isEqualTo("营区对抗");
        assertThat(view.plan().battleMode()).isEqualTo(BattleMode.TRAIN);
        assertThat(view.plan().mapId()).isNull();
        assertThat(view.rosterCount()).isEqualTo(2);

        RosterEntry li = entryOf(view.plan().id(), 101);
        assertThat(li.name()).isEqualTo("李四");
        assertThat(li.team()).isEqualTo(Team.RED);
        assertThat(li.hp()).isEqualTo(80);
        assertThat(li.ammo1()).isEqualTo(20);
        assertThat(li.ammo2()).isEqualTo(15);
        assertThat(li.mode()).isEqualTo(PollMode.MANUAL);
        assertThat(entryOf(view.plan().id(), 102).team()).isEqualTo(Team.BLUE);
    }

    @Test
    void importXlsx_readsTheSameWayAsCsv() {
        seedVest(201);
        byte[] file = PlanRosterSheet.write(List.of(HEADERS,
                List.of("201", "赵六", "橙", "70", "10", "5", "AUTO")), ExportFormat.XLSX);

        PlanService.PlanView view = service.importPlan("兵力表.xlsx", "导入想定", file);

        assertThat(view.plan().name()).isEqualTo("导入想定");
        assertThat(view.rosterCount()).isEqualTo(1);
        RosterEntry entry = entryOf(view.plan().id(), 201);
        assertThat(entry.team()).isEqualTo(Team.ORANGE);
        assertThat(entry.hp()).isEqualTo(70);
        assertThat(entry.ammo2()).isEqualTo(5);
    }

    @Test
    void import_skipsTheTemplateExampleRowAndKeepsTheRealOnes() {
        seedVest(301);
        byte[] file = PlanRosterSheet.write(List.of(HEADERS, EXAMPLE,
                List.of("301", "真人", "黄", "100", "30", "30", "AUTO")), ExportFormat.XLSX);

        PlanService.PlanView view = service.importPlan("带示例行.xlsx", null, file);

        assertThat(view.rosterCount()).isEqualTo(1);
        assertThat(entryOf(view.plan().id(), 301).name()).isEqualTo("真人");
    }

    @Test
    void import_withOutTheOptionalColumns_appliesTheContractDefaults() {
        seedVest(401);
        byte[] file = csv("马甲编号,队伍", "401,红");

        PlanService.PlanView view = service.importPlan("两列.csv", null, file);

        RosterEntry entry = entryOf(view.plan().id(), 401);
        assertThat(entry.name()).isNull();
        assertThat(entry.team()).isEqualTo(Team.RED);
        assertThat(entry.hp()).isEqualTo(100);
        assertThat(entry.ammo1()).isEqualTo(30);
        assertThat(entry.ammo2()).isEqualTo(30);
        assertThat(entry.mode()).isEqualTo(PollMode.AUTO);
    }

    @Test
    void import_withoutName_takesTheNameFromTheFileNameAndDropsThePath() {
        seedVest(601);
        byte[] file = csv("马甲编号,队伍", "601,BLUE");

        PlanService.PlanView view = service.importPlan("C:\\tmp\\城市攻防.csv", "  ", file);

        assertThat(view.plan().name()).isEqualTo("城市攻防");
    }

    // ── 导入的拒绝路径 ──────────────────────────────────────────────────────

    @Test
    void import_withAnUnknownTeamValue_isRejectedWithItsLineNumber() {
        byte[] file = csv("马甲编号,队伍", "101,PURPLE");

        assertThatThrownBy(() -> service.importPlan("a.csv", "想定", file))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getMessage()).contains("第 2 行").contains("队伍");
                });
    }

    @Test
    void import_withAMissingRequiredColumn_isRejected() {
        byte[] file = csv("马甲编号,姓名", "401,张三");

        assertThatThrownBy(() -> service.importPlan("a.csv", "想定", file))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getMessage()).contains("队伍"));
    }

    @Test
    void import_ofTheTemplateItself_isRejectedBecauseItOnlyHasTheExampleRow() {
        byte[] template = service.downloadTemplate(ExportFormat.CSV).content();

        assertCode(() -> service.importPlan("模板.csv", "想定", template));
    }

    @Test
    void import_withAVestNumMissingFromTheLedger_isRejectedAndWritesNothing() {
        seedVest(501);
        long before = plans.count();
        byte[] file = csv("马甲编号,队伍", "501,RED", "999,RED");

        assertCode(() -> service.importPlan("a.csv", "想定", file));

        assertThat(plans.count()).isEqualTo(before);
    }

    @Test
    void import_withTheSameVestNumTwice_isRejected() {
        seedVest(701);
        byte[] file = csv("马甲编号,队伍", "701,RED", "701,RED");

        assertCode(() -> service.importPlan("a.csv", "想定", file));
    }

    @Test
    void import_withAVestNumOutOfTheLedgerRange_isRejectedBeforeTouchingTheDatabase() {
        byte[] file = csv("马甲编号,队伍", "70000,RED");

        assertThatThrownBy(() -> service.importPlan("a.csv", "想定", file))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getMessage()).contains("超出范围"));
    }

    @Test
    void import_ofAnEmptyFile_isRejected() {
        assertCode(() -> service.importPlan("a.csv", "想定", new byte[0]));
    }

    @Test
    void import_ofALegacyXlsBinary_isRejectedWithAHint() {
        // 旧版 .xls 的 OLE2 魔数：不是 zip，也不该被当 CSV 硬解
        byte[] xls = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0, 0};

        assertThatThrownBy(() -> service.importPlan("旧表.xls", "想定", xls))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getMessage()).contains("只支持 .xlsx 与 .csv"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static byte[] csv(String... lines) {
        return String.join("\r\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    private RosterEntry entryOf(UUID planId, int vestNum) {
        return service.roster(planId).stream()
                .filter(entry -> entry.vestNum() == vestNum)
                .findFirst()
                .orElseThrow(() -> new AssertionError("编成里没有马甲 " + vestNum));
    }

    /** 直接落一行台账：编成校验只要求编号存在于 {@code t_vest}，故其余列给最小值。 */
    private void seedVest(int vestNum) {
        vests.insert(new Vest(vestNum, null, Team.RED, null, null, null, null, false, true,
                null, null, null, null));
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
    }
}
