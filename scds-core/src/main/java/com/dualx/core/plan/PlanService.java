package com.dualx.core.plan;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.ExportFormat;
import com.dualx.core.domain.Plan;
import com.dualx.core.domain.PollMode;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.repository.PlanRepository;
import com.dualx.core.repository.PlanRosterRepository;
import com.dualx.core.repository.VestRepository;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.UuidV7;
import com.dualx.protocol.Team;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 想定台账服务（契约 {@code docs/api/paths/exercise.yaml} 的 plan / planRoster 段，需求 FR-PLAN）。
 *
 * <p><b>为什么必须经过这一层</b>：契约里 {@code updatePlan} / {@code deletePlan} /
 * {@code replaceRoster} 的 409 判据是「该想定正在演习中」，要读 {@code t_exercise}；
 * {@code replaceRoster} 的三条校验要读 {@code t_vest} 台账；{@code Plan.rosterCount}
 * 又是跨表派生值。这些都不是单表能算出来的，库层也没有外键（契约 V3 注释明说一致性由应用层保证）。
 * 控制器一律调本类，不碰仓储。</p>
 *
 * <p><b>默认值不在这里</b>：{@code hp}/{@code ammo1}/{@code ammo2}/{@code mode}/{@code battleMode}
 * 的契约默认（100/30/30/AUTO/TRAIN）在 API 层补齐后显式传入，避免「库默认」与「契约默认」
 * 两个来源（见 {@link PlanRosterRepository} 类注释）。</p>
 *
 * <p><b>事务边界</b>：只有「删想定连带删编成」、「整体替换编成」与「导入落库」写两处以上，
 * 故只有这三处加 {@code @Transactional}；单语句的登记 / 修改不加，与 {@code VestLedgerService}
 * 同一取向。</p>
 */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final PlanRosterRepository rosters;
    private final VestRepository vests;
    private final ExerciseRepository exercises;

    public PlanService(PlanRepository plans, PlanRosterRepository rosters, VestRepository vests,
                       ExerciseRepository exercises) {
        this.plans = plans;
        this.rosters = rosters;
        this.vests = vests;
        this.exercises = exercises;
    }

    /**
     * 想定列表：分页 + 名称模糊搜索（契约 {@code listPlans}）。
     *
     * <p>{@code keyword} 空串与 null 同义：{@code ?keyword=} 是「没填」，不是「搜空字符串」。
     * 人数用一次成批查询补齐，避免逐行 COUNT 的 N+1。
     */
    public Page<PlanView> list(String keyword, PageRequest request) {
        String filter = blankToNull(keyword);
        List<Plan> rows = filter == null ? plans.findAll(request) : plans.findByKeyword(filter, request);
        long total = filter == null ? plans.count() : plans.countByKeyword(filter);
        Map<UUID, Long> counts = rosters.countByPlanIds(rows.stream().map(Plan::id).toList());
        return Page.of(rows.stream()
                .map(plan -> new PlanView(plan, counts.getOrDefault(plan.id(), 0L)))
                .toList(), total, request);
    }

    /** 想定详情；不存在即 404，不做「返回 null 让调用方自己判」。 */
    public PlanView get(UUID planId) {
        Plan plan = require(planId);
        return new PlanView(plan, rosters.countByPlanId(planId));
    }

    /** 新建想定。新行没有编成，{@code rosterCount} 从 0 开始。 */
    public PlanView create(PlanDraft draft) {
        Plan drafted = new Plan(UuidV7.generate(), draft.name(), draft.mapId(), draft.battleMode(),
                draft.remark(), null, null);
        plans.insert(drafted);
        return get(drafted.id());
    }

    /**
     * 修改想定（契约 {@code updatePlan}）。正在演习中的想定拒绝修改（409）——
     * {@code t_exercise} 里的名/图是开演瞬间的快照，改了想定会让两处数据互相打脸。
     *
     * <p>{@code createdAt} / {@code updatedAt} 从当前行原样带回：它们由库默认值维护，
     * 传 null 进去只会让 {@code update} 语句把 {@code updated_at} 显式写成旧值。
     */
    public PlanView update(UUID planId, PlanDraft draft) {
        Plan current = require(planId);
        requireNotRunning(planId, "修改");
        plans.update(new Plan(current.id(), draft.name(), draft.mapId(), draft.battleMode(),
                draft.remark(), current.createdAt(), current.updatedAt()));
        return get(planId);
    }

    /**
     * 删除想定（契约 {@code deletePlan}）。正在演习中的想定拒绝删除（409）。
     *
     * <p>同事务顺带清 {@code t_plan_roster}：无外键，不清就会留下指向死想定的编成行，
     * 而那张表没有任何入口能再被读到（与 {@code MapLedgerService.delete} 清楼层点位同一取向）。
     * 反向不做——不因编成存在而拒绝删除想定，编成是想定的附属物，不是独立资源。</p>
     */
    @Transactional
    public void delete(UUID planId) {
        require(planId);
        requireNotRunning(planId, "删除");
        rosters.deleteByPlanId(planId);
        plans.deleteById(planId);
    }

    /** 读取编成（契约 {@code getRoster}）；想定不存在即 404，空编成回空列表而不是 404。 */
    public List<RosterEntry> roster(UUID planId) {
        require(planId);
        return rosters.findByPlanId(planId);
    }

    // ── 模板与导入 ──────────────────────────────────────────────────────────

    /**
     * 模板下载（契约 {@code downloadPlanTemplate}）。编解码全在 {@link PlanRosterSheet}，
     * 这里只是给控制器一个「只跟服务层说话」的入口。
     */
    public PlanRosterSheet.TemplateFile downloadTemplate(ExportFormat format) {
        return PlanRosterSheet.template(format);
    }

    /**
     * 整文件导入（契约 {@code importPlan}）。
     *
     * <p><b>先整份解析校验，再落库</b>：契约要求「整文件校验通过才落库」。解析与编成的三条
     * 语义校验（编号在台账、不重复、不跨队）都排在 {@code plans.insert} 之前，且整体一个事务——
     * 中途失败既不留半份想定，也不留半份编成。</p>
     *
     * <p>模板只承载编成：{@code battleMode} 取契约默认 TRAIN，{@code mapId} / {@code remark}
     * 留空，导入后在编辑页补。想定名优先用表单里的 {@code name}，没给才退回文件名。</p>
     *
     * @param fileName 上传时的原始文件名（可能含路径，仅用于取默认想定名）
     * @param name     表单里的想定名；空白视为没给
     * @param content  文件字节；格式按字节嗅探（PK 开头即 XLSX，否则 CSV）
     * @throws BusinessException {@code VALIDATION_FAILED} 文件不合法 / 编成不合法 / 想定名定不出来
     */
    @Transactional
    public PlanView importPlan(String fileName, String name, byte[] content) {
        List<PlanRosterSheet.Row> rows = PlanRosterSheet.parse(content);
        List<RosterDraft> entries = rows.stream()
                .map(row -> new RosterDraft(row.vestNum(), row.name(), row.team(), row.hp(),
                        row.ammo1(), row.ammo2(), row.mode()))
                .toList();
        validate(entries);

        Plan plan = new Plan(UuidV7.generate(), planName(fileName, name), null, BattleMode.TRAIN,
                null, null, null);
        plans.insert(plan);
        rosters.replaceByPlanId(plan.id(), entries.stream().map(entry -> drafted(plan.id(), entry)).toList());
        return get(plan.id());
    }

    /** 想定名：表单值优先，其次文件名去路径去扩展名；两者都空或超长即拒（契约上限 128 字符）。 */
    private static String planName(String fileName, String requested) {
        String candidate = blankToNull(requested);
        if (candidate == null) {
            String base = fileName == null ? "" : fileName;
            base = base.substring(Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\')) + 1);
            int dot = base.lastIndexOf('.');
            candidate = blankToNull(dot > 0 ? base.substring(0, dot) : base);
        }
        if (candidate == null) {
            throw invalid("无法确定想定名：请在表单里填写 name，或上传带文件名的模板文件");
        }
        if (candidate.length() > 128) {
            throw invalid("想定名过长（最多 128 字符）：" + candidate);
        }
        return candidate;
    }

    /**
     * 整体替换编成（契约 {@code replaceRoster}，幂等全量语义）。
     *
     * <p>三条校验挨个做完**才**落库，且整体在一个事务里：契约要求「任一不满足返回 400
     * {@code VALIDATION_FAILED}，不产生部分写入」。校验放在仓储的
     * {@link PlanRosterRepository#replaceByPlanId} 之前，就不会出现「删了旧的、新的没过校验」，
     * 那种中间态在现场表现为「编成忽然空了」。</p>
     *
     * <p>校验顺序：先查重（纯内存、不落库），再逐条查台账。查重要放在前面——重号必然伴随
     * 同一条 {@code uk_plan_roster} 冲突，先报重号才能给出「哪一号重复」这种可处置的信息。</p>
     *
     * @throws BusinessException {@code PLAN_NOT_FOUND} 想定不存在；
     *                           {@code PLAN_IN_USE} 该想定正在演习中；
     *                           {@code VALIDATION_FAILED} 编号不在台账 / 重复 / 跨队
     */
    @Transactional
    public List<RosterEntry> replaceRoster(UUID planId, List<RosterDraft> entries) {
        require(planId);
        requireNotRunning(planId, "修改编成");
        validate(entries);
        rosters.replaceByPlanId(planId, entries.stream().map(entry -> drafted(planId, entry)).toList());
        return rosters.findByPlanId(planId);
    }

    // ── 校验 ────────────────────────────────────────────────────────────────

    /**
     * 编成的三条契约校验：编号必须存在于设备台账、同编号不得重复、同编号不得跨队。
     *
     * <p>「重复」与「跨队」在库里是同一件事（{@code uk_plan_roster (plan_id, vest_num)} 全局唯一），
     * 但报错文案分开：跨队重号是操作员排编成时最容易犯的错，说清「101 同时出现在 RED 和 BLUE」
     * 比只说「101 重复」少一轮排查。</p>
     */
    private void validate(List<RosterDraft> entries) {
        Map<Integer, Team> seen = new LinkedHashMap<>();
        for (RosterDraft entry : entries) {
            Team previous = seen.putIfAbsent(entry.vestNum(), entry.team());
            if (previous != null) {
                throw invalid(previous == entry.team()
                        ? "编成内马甲编号重复：" + entry.vestNum()
                        : "编成内同一马甲编号跨队：" + entry.vestNum()
                        + "（" + previous + " / " + entry.team() + "）");
            }
            if (!vests.existsByVestNum(entry.vestNum())) {
                throw invalid("马甲编号不在设备台账中：" + entry.vestNum() + "（请先在台账登记该设备）");
            }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private Plan require(UUID planId) {
        return plans.findById(planId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.PLAN_NOT_FOUND,
                        "想定不存在：" + planId));
    }

    /** 进行中的演习若引用的就是这份想定，则改 / 删 / 替编成一律拒绝（409）。 */
    private void requireNotRunning(UUID planId, String action) {
        exercises.findRunning()
                .filter(running -> planId.equals(running.planId()))
                .ifPresent(running -> {
                    throw new BusinessException(ApiErrorCode.PLAN_IN_USE,
                            "该想定正在演习中，禁止" + action + "：" + planId);
                });
    }

    private static RosterEntry drafted(UUID planId, RosterDraft entry) {
        return new RosterEntry(0L, planId, entry.vestNum(), entry.name(), entry.team(),
                entry.hp(), entry.ammo1(), entry.ammo2(), entry.mode(), null, null);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ApiErrorCode.VALIDATION_FAILED, message);
    }

    /** 空串与 null 同义：{@code ?keyword=} 是「没填」，不是「搜空字符串」。 */
    private static String blankToNull(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword;
    }

    // ── 入参 / 读取模型 ─────────────────────────────────────────────────────

    /**
     * 登记 / 修改想定的入参。
     *
     * <p>{@code battleMode} 由调用方补齐契约默认（TRAIN）后传入：本类只负责写，
     * 不猜「没传」是什么意思——那是接口层读请求体时才知道的事。</p>
     */
    public record PlanDraft(String name, UUID mapId, BattleMode battleMode, String remark) {
    }

    /**
     * 编成里单条记录的入参。刻意不含 {@code id} / {@code planId} / 时间戳：
     * 前三者是库层的派生结果，调用方给什么都不该被采纳。
     */
    public record RosterDraft(int vestNum, String name, Team team, int hp, int ammo1, int ammo2,
                              PollMode mode) {
    }

    /**
     * 想定的读取模型：本体 + 派生的人数（契约 {@code Plan.rosterCount}）。
     *
     * <p>两个值一次读完，让控制器不必为同一行回库第二次。</p>
     */
    public record PlanView(Plan plan, long rosterCount) {
    }
}
