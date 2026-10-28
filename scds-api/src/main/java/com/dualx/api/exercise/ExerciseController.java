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
import com.dualx.api.dto.situation.ExerciseLogEntry;
import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.ExportFormat;
import com.dualx.core.domain.PollMode;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.exercise.ExerciseLifecycleService;
import com.dualx.core.plan.PlanRosterSheet;
import com.dualx.core.plan.PlanService;
import com.dualx.core.state.SituationQuery;
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
import java.time.Duration;
import java.time.Instant;
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
 * <p><b>当前演习三端点均已接线</b>：{@code getCurrentExercise} 行数据读库、在线数读态势读侧，
 * 无演习时按契约返回 200 + {@code running=false}；{@code listCurrentExerciseLogs} 的游标口径
 * （{@code sinceSeq} → {@code latestSeq} 的推进）接在
 * {@link ExerciseLifecycleService#currentLogs}，无演习时返回空页。</p>
 *
 * <p>不设类级路径前缀，逐方法写完整绝对路径。</p>
 */
@RestController
@Validated
public class ExerciseController {

    private final PlanService plans;
    private final ExerciseLifecycleService lifecycle;
    private final SituationQuery situation;

    public ExerciseController(PlanService plans, ExerciseLifecycleService lifecycle, SituationQuery situation) {
        this.plans = plans;
        this.lifecycle = lifecycle;
        this.situation = situation;
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

    // ── 当前演习 ────────────────────────────────────────────────────────────

    /**
     * 当前演习状态；无演习进行中时 200 + {@code running=false}（契约明确不是 404）。
     *
     * <p>行数据读库、在线数读态势：库里的 {@code ended_at IS NULL} 是「有一场未结束的场次」的权威
     * ——进程刚重启、自愈尚未收尾时态势内存态是空的，而这一行正是要让操作员看见并去停掉的事实。</p>
     */
    @GetMapping("/api/v1/exercise/current")
    public Exercise getCurrentExercise() {
        return lifecycle.currentExercise()
                .map(running -> toExercise(running, situation))
                .orElseGet(ExerciseController::noExerciseRunning);
    }

    /**
     * 开始演习（单例资源：同一时刻至多一场）。
     *
     * <p>只做契约形状的搬运：{@code planId} 解析、{@code battleMode} 为 null 时由服务层沿用想定，
     * 单实例保护与「想定/编成是否可用」全在 {@link ExerciseLifecycleService} 里判。
     * 契约步骤 ②③（14B 轮询配置帧、7B 开始帧）属网关下行，尚未接线。</p>
     */
    @PostMapping("/api/v1/exercise/current")
    @ResponseStatus(HttpStatus.CREATED)
    public Exercise startExercise(@RequestBody @Valid StartExerciseRequest request) {
        return toExercise(lifecycle.startExercise(exercisePlanId(request.planId()), request.battleMode()), situation);
    }

    /** 结束演习；当前没有演习进行中时 409。 */
    @DeleteMapping("/api/v1/exercise/current")
    public StopExerciseResult stopExercise() {
        ExerciseLifecycleService.StopOutcome outcome = lifecycle.stopExercise();
        // replayId 为 null 是契约允许的形态（复盘落库失败也走这条路）；复盘本体归 M5 REPLAY
        return new StopExerciseResult(outcome.exerciseId().toString(), outcome.endedAt(), null,
                outcome.endReason());
    }

    /**
     * 当前演习日志流水：按 seq 增量拉取。
     *
     * <p>契约没有 404：无演习进行中时是 200 + 空页（{@code items=[]}、{@code latestSeq=null}）。
     * {@code latestSeq} 取本批**末条**（列表已按 seq 升序）——客户端把它作为下次的 {@code sinceSeq}；
     * 本层不判「游标推没推到末尾」，那由客户端拿 {@code latestSeq} 自己比。</p>
     */
    @GetMapping("/api/v1/exercise/current/logs")
    public ExerciseLogList listCurrentExerciseLogs(
            @RequestParam(name = "sinceSeq", required = false) Long sinceSeq,
            @RequestParam(name = "limit", required = false, defaultValue = "100")
            @Min(1) @Max(500) int limit) {
        List<ExerciseLogEntry> items = lifecycle.currentLogs(sinceSeq, limit).stream()
                .map(ExerciseController::toLogEntry)
                .toList();
        Long latestSeq = items.isEmpty() ? null : items.get(items.size() - 1).seq();
        return new ExerciseLogList(items, latestSeq);
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
     * 开演请求体里的 {@code planId} 转 UUID。
     *
     * <p>与路径参数的 {@link #planId(String)} 同一取向（拼不出来就是不存在），但用开演语境的码：
     * 本端点的 404 描述的正是「这次开演引用的想定不存在」。</p>
     */
    private static UUID exercisePlanId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ApiErrorCode.EXERCISE_PLAN_NOT_FOUND, "开演所引用的想定不存在：" + raw);
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

    /**
     * 领域日志行 → 契约 DTO。
     *
     * <p>两侧的 {@code ExerciseLogEntry} 同名不同形状：领域侧多一个 {@code exerciseId}
     * （请求问的就是「当前场次」，不进契约），字段名是 {@code logType}/{@code operatorName}。
     * 故这里写全名，避免与导入的契约 DTO 撞名（与 {@link #toEntry} 同一处理）。</p>
     */
    private static ExerciseLogEntry toLogEntry(com.dualx.core.domain.ExerciseLogEntry entry) {
        return new ExerciseLogEntry(entry.seq(), entry.at(), entry.logType(), entry.vestNum(),
                entry.operatorName(), entry.detail(), entry.downlinkHex());
    }

    /**
     * 领域演习行 → 契约 DTO。
     *
     * <p>{@code onlineCount} 取态势读侧而不是库：库里根本没有这一列，在线与否是内存态的事。
     * 开演那一刻全体刚被装载成 {@code OFFLINE}，此时算出来就是 0。</p>
     */
    private static Exercise toExercise(com.dualx.core.domain.Exercise exercise, SituationQuery situation) {
        return new Exercise(exercise.id().toString(), exercise.planId().toString(), exercise.planName(),
                exercise.mapId() == null ? null : exercise.mapId().toString(), exercise.battleMode(),
                exercise.running(), exercise.startedAt(), exercise.endedAt(),
                exercise.running() ? Duration.between(exercise.startedAt(), Instant.now()).toSeconds() : null,
                exercise.rosterCount(), onlineCount(situation));
    }

    /**
     * 在线马甲数：全体减离线。
     *
     * <p>{@code OFFLINE} 是**唯一的链路离线标记**（由态势状态机的 5000ms 心跳窗口扫描判出），
     * {@code DIE} 则是马甲自报阵亡、与链路无关——阵亡马甲仍在线上报，故仍计在线。
     * 「参演 10 / 在线 8」说的是有几台还在上报，而不是有几台还活着（存活/阵亡的分解在队伍汇总里）。</p>
     */
    private static int onlineCount(SituationQuery situation) {
        int total = 0;
        int offline = 0;
        for (SituationQuery.TeamCounts counts : situation.teamSummary()) {
            total += counts.total();
            offline += counts.offline();
        }
        return total - offline;
    }

    /**
     * 无演习进行中：除 {@code running} 外一律留空。
     *
     * <p>契约明确这是 200 + {@code running=false} 而**不是 404**，且不得编造场次 ID 或时间
     * （D15-⑥）；客户端必须先判 {@code running} 再解引用其余字段。</p>
     */
    private static Exercise noExerciseRunning() {
        return new Exercise(null, null, null, null, null, false, null, null, null, null, null);
    }
}
