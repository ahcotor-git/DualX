package com.dualx.api.exercise;

import com.dualx.api.dto.common.PageMeta;
import com.dualx.api.dto.exercise.Exercise;
import com.dualx.api.dto.exercise.ExerciseLogList;
import com.dualx.api.dto.exercise.Plan;
import com.dualx.api.dto.exercise.PlanPage;
import com.dualx.api.dto.exercise.PlanUpsertRequest;
import com.dualx.api.dto.exercise.Roster;
import com.dualx.api.dto.exercise.RosterEntry;
import com.dualx.api.dto.exercise.StartExerciseRequest;
import com.dualx.api.dto.exercise.StopExerciseResult;
import com.dualx.api.error.NotImplementedException;
import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.ExportFormat;
import com.dualx.core.domain.PollMode;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.plan.PlanRosterSheet;
import com.dualx.core.plan.PlanService;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

/**
 * exercise 域：想定（plan）/ 编成（roster）/ 当前演习（current）生命周期。
 *
 * <p>「进行中的演习」按契约建模为单例资源 {@code /api/v1/exercise/current}——边缘同一时刻
 * 只允许一场演习，故 GET 读 / POST 起 / DELETE 停。历史场次走 replay 域，本域不暴露 {@code /{exerciseId}}。</p>
 *
 * <p><b>已接线</b>：想定 CRUD、编成整体替换、模板下载与整文件导入共 9 个端点走
 * {@link PlanService}。本层只做契约形状的搬运（DTO ↔ {@code PlanView} / {@code RosterEntry}
 * ↔ 二进制），并在此补齐契约默认值（{@code hp}/{@code ammo1}/{@code ammo2}/{@code mode} =
 * 100/30/30/AUTO、{@code battleMode} = TRAIN）——「想定正在演习中不能改」这类判断全在服务层，
 * 写进控制器会让同一规则在 REST 与将来的 WS 入口上各写一遍。</p>
 *
 * <p><b>仍是占位（501）</b>：当前演习的 4 个端点（{@code getCurrentExercise} /
 * {@code startExercise} / {@code stopExercise} / {@code listCurrentExerciseLogs}）需接
 * {@code ExerciseLifecycleService} 与网关下行下发，属 LIFE 段落的剩余部分；
 * 本段只保证路由与 DTO 已按契约立起，响应上 501 与「已实现」可区分。</p>
 *
 * <p>不设类级路径前缀，逐方法写完整绝对路径。</p>
 */
@RestController
@Validated
public class ExerciseController {

    private final PlanService plans;

    public ExerciseController(PlanService plans) {
        this.plans = plans;
    }

    // ── 想定 ────────────────────────────────────────────────────────────────

    /** 想定列表：分页 + 名称模糊搜索。 */
    @GetMapping("/api/v1/plans")
    public PlanPage listPlans(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", required = false, defaultValue = "1") @Min(1) int page,
            @RequestParam(name = "size", required = false, defaultValue = "20")
            @Min(1) @Max(PageRequest.MAX_SIZE) int size) {
        Page<PlanService.PlanView> result = plans.list(keyword, PageRequest.of(page, size));
        return new PlanPage(result.items().stream().map(ExerciseController::toPlan).toList(),
                new PageMeta(result.page(), result.size(), result.total()));
    }

    /** 新建想定。 */
    @PostMapping("/api/v1/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public Plan createPlan(@RequestBody @Valid PlanUpsertRequest request) {
        return toPlan(plans.create(draft(request)));
    }

    /**
     * 下载想定导入模板（xlsx / csv 二进制）。
     *
     * <p>文件名是纯 ASCII（{@code scds-plan-template.xlsx}）：中文名要走 RFC 5987 的
     * {@code filename*=UTF-8''…}，而这份模板的用途是「下载后填完再传回来」，
     * 名字里写什么对操作员没有差别。</p>
     */
    @GetMapping("/api/v1/plans/template")
    public ResponseEntity<byte[]> downloadPlanTemplate(
            @RequestParam(name = "format", required = false, defaultValue = "XLSX") ExportFormat format) {
        PlanRosterSheet.TemplateFile template = plans.downloadTemplate(format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(template.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(template.fileName()).build().toString())
                .body(template.content());
    }

    /** 从模板文件导入想定；整文件校验通过才落库。 */
    @PostMapping("/api/v1/plans/import")
    @ResponseStatus(HttpStatus.CREATED)
    public Plan importPlan(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "name", required = false) String name) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            // 客户端中断或临时文件被清理：请求体已经收不全，这不是「模板不合法」，属服务端无法完成
            throw new UncheckedIOException("读取上传的想定模板失败", e);
        }
        return toPlan(plans.importPlan(file.getOriginalFilename(), name, content));
    }

    /** 想定详情。 */
    @GetMapping("/api/v1/plans/{planId}")
    public Plan getPlan(@PathVariable("planId") String planId) {
        return toPlan(plans.get(planId(planId)));
    }

    /** 修改想定；该想定正在演习中时 409。 */
    @PutMapping("/api/v1/plans/{planId}")
    public Plan updatePlan(@PathVariable("planId") String planId,
                           @RequestBody @Valid PlanUpsertRequest request) {
        return toPlan(plans.update(planId(planId), draft(request)));
    }

    /** 删除想定（连带其编成）；该想定正在演习中时 409。 */
    @DeleteMapping("/api/v1/plans/{planId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlan(@PathVariable("planId") String planId) {
        plans.delete(planId(planId));
    }

    // ── 编成 ────────────────────────────────────────────────────────────────

    /** 获取想定编成。 */
    @GetMapping("/api/v1/plans/{planId}/roster")
    public Roster getRoster(@PathVariable("planId") String planId) {
        return new Roster(planId, plans.roster(planId(planId)).stream()
                .map(ExerciseController::toEntry)
                .toList());
    }

    /**
     * 整体替换想定编成（全量替换语义）。
     *
     * <p>回显的是服务层读回的编成而不是请求体：服务层按「队伍 → 马甲编号」排序返回，
     * 客户端拿到的是稳定顺序，可以直接用来渲染。</p>
     */
    @PutMapping("/api/v1/plans/{planId}/roster")
    public Roster replaceRoster(@PathVariable("planId") String planId,
                                @RequestBody @Valid Roster request) {
        List<PlanService.RosterDraft> entries = request.entries().stream()
                .map(ExerciseController::toDraft)
                .toList();
        return new Roster(planId, plans.replaceRoster(planId(planId), entries).stream()
                .map(ExerciseController::toEntry)
                .toList());
    }

    // ── 当前演习（占位）─────────────────────────────────────────────────────

    /** 当前演习状态；无演习进行中时返回 running=false。 */
    @GetMapping("/api/v1/exercise/current")
    public Exercise getCurrentExercise() {
        throw new NotImplementedException("getCurrentExercise");
    }

    /** 开始演习。 */
    @PostMapping("/api/v1/exercise/current")
    public Exercise startExercise(@RequestBody @Valid StartExerciseRequest request) {
        throw new NotImplementedException("startExercise");
    }

    /** 结束演习。 */
    @DeleteMapping("/api/v1/exercise/current")
    public StopExerciseResult stopExercise() {
        throw new NotImplementedException("stopExercise");
    }

    /** 当前演习日志流水：按 seq 增量拉取。 */
    @GetMapping("/api/v1/exercise/current/logs")
    public ExerciseLogList listCurrentExerciseLogs(
            @RequestParam(name = "sinceSeq", required = false) Long sinceSeq,
            @RequestParam(name = "limit", required = false, defaultValue = "100") int limit) {
        throw new NotImplementedException("listCurrentExerciseLogs");
    }

    // ── 契约形状的搬运 ──────────────────────────────────────────────────────

    /**
     * 路径参数转 UUID。解析失败按「想定不存在」处理而不是 400：一个拼不出来的 id 必然
     * 不对应任何一行，语义上就是不存在（与 {@code MapController.mapId} 同一取向）。
     */
    private static UUID planId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ApiErrorCode.PLAN_NOT_FOUND, "想定不存在：" + raw);
        }
    }

    /**
     * 请求体里的 {@code mapId} 转 UUID。
     *
     * <p>与路径参数相反，这里格式错是 **400** 而不是 404：它是客户端刚发来的一个字段值，
     * 说「你想定的底图不存在」会让人去查库，而实际要改的是这次请求本身。</p>
     */
    private static UUID mapId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED, "mapId 不是合法 UUID：" + raw);
        }
    }

    /** 请求体 → 服务层入参，并在此补齐契约默认值（{@code battleMode} 不传即 TRAIN）。 */
    private static PlanService.PlanDraft draft(PlanUpsertRequest request) {
        return new PlanService.PlanDraft(request.name(),
                request.mapId() == null ? null : mapId(request.mapId()),
                request.battleMode() == null ? BattleMode.TRAIN : request.battleMode(),
                request.remark());
    }

    /** 编成单条 → 服务层入参，补齐契约默认值（100/30/30/AUTO）。 */
    private static PlanService.RosterDraft toDraft(RosterEntry entry) {
        return new PlanService.RosterDraft(entry.vestNum(), entry.name(), entry.team(),
                entry.hp() == null ? 100 : entry.hp(),
                entry.ammo1() == null ? 30 : entry.ammo1(),
                entry.ammo2() == null ? 30 : entry.ammo2(),
                entry.mode() == null ? PollMode.AUTO : entry.mode());
    }

    /**
     * 服务层读取模型 → 契约 DTO。
     *
     * <p>{@code Plan} 与 {@code RosterEntry} 在 API 与领域两侧各有一份同名类型（一侧是契约形状、
     * 一侧是库表形状），故领域侧在下面两处写全名，避免 import 撞名。</p>
     */
    private static Plan toPlan(PlanService.PlanView view) {
        var plan = view.plan();
        return new Plan(plan.id().toString(), plan.name(),
                plan.mapId() == null ? null : plan.mapId().toString(),
                plan.battleMode(), plan.remark(), (int) view.rosterCount(),
                plan.createdAt(), plan.updatedAt());
    }

    /** 领域编成行 → 契约 DTO；{@code id} / {@code planId} / 时间戳不进契约，故不搬。 */
    private static RosterEntry toEntry(com.dualx.core.domain.RosterEntry entry) {
        return new RosterEntry(entry.vestNum(), entry.name(), entry.team(), entry.hp(),
                entry.ammo1(), entry.ammo2(), entry.mode());
    }
}
