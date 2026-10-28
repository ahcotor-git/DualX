package com.dualx.core.plan;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.PollMode;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.domain.Vest;
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
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 想定台账服务的验收（契约 {@code docs/api/paths/exercise.yaml} 的 plan / planRoster 段）。
 *
 * <p>真库真事务：想定的 409 判据要读 {@code t_exercise}、编成校验要读 {@code t_vest}，
 * 用 mock 替掉任何一边都会把「跨表判据」这件事本身验掉。
 *
 * <p>{@code @Transactional} 让每个用例的想定、编成与演习行一起回滚——{@code t_exercise}
 * 有「至多一场进行中」的唯一索引，不隔离的话一条用例就会挡住其它用例造演习行。
 */
@SpringBootTest
@Transactional
class PlanServiceIT {

    @Autowired
    private PlanService service;

    @Autowired
    private PlanRepository plans;

    @Autowired
    private PlanRosterRepository rosters;

    @Autowired
    private VestRepository vests;

    @Autowired
    private ExerciseRepository exercises;

    private static final Instant START = Instant.parse("2026-10-20T08:00:00.000Z");

    // ── 想定 CRUD ───────────────────────────────────────────────────────────

    @Test
    void create_thenGet_showsUpWithEmptyRoster() {
        UUID planId = service.create(draft("it_plan_新建")).plan().id();

        PlanService.PlanView view = service.get(planId);

        assertThat(view.plan().name()).isEqualTo("it_plan_新建");
        assertThat(view.plan().battleMode()).isEqualTo(BattleMode.TRAIN);
        assertThat(view.plan().mapId()).isNull();
        assertThat(view.rosterCount()).isZero();
    }

    @Test
    void list_searchesByNameAndFillsRosterCountsInOnePage() {
        UUID alpha = service.create(draft("it_plan_甲")).plan().id();
        service.create(draft("it_plan_乙")).plan().id();
        seedVest(101);
        seedVest(102);
        service.replaceRoster(alpha, List.of(rosterDraft(101, Team.RED), rosterDraft(102, Team.BLUE)));

        Page<PlanService.PlanView> page = service.list("it_plan_甲", PageRequest.of(1, 20));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().plan().id()).isEqualTo(alpha);
        assertThat(page.items().getFirst().rosterCount()).isEqualTo(2);
    }

    @Test
    void list_withoutKeyword_returnsEveryPlan() {
        service.create(draft("it_plan_全量一")).plan().id();
        service.create(draft("it_plan_全量二")).plan().id();

        Page<PlanService.PlanView> page = service.list("  ", PageRequest.of(1, 200));

        // 空白 keyword 与不传同义：不因「搜空字符串」而漏掉任何一行
        assertThat(page.total()).isEqualTo(plans.count());
    }

    @Test
    void update_rewritesMutableFieldsAndKeepsCreatedAt() {
        PlanService.PlanView created = service.create(draft("it_plan_改前"));
        UUID mapId = UuidV7.generate();

        PlanService.PlanView updated = service.update(created.plan().id(),
                new PlanService.PlanDraft("it_plan_改后", mapId, BattleMode.BATTLE, "备注"));

        assertThat(updated.plan().name()).isEqualTo("it_plan_改后");
        assertThat(updated.plan().mapId()).isEqualTo(mapId);
        assertThat(updated.plan().battleMode()).isEqualTo(BattleMode.BATTLE);
        assertThat(updated.plan().remark()).isEqualTo("备注");
        // created_at 不可改：改一次名就把建档时刻刷成现在，现场就分不清哪个是想定的先后顺序
        assertThat(updated.plan().createdAt()).isEqualTo(created.plan().createdAt());
    }

    @Test
    void get_ofMissingPlan_isPlanNotFound() {
        assertCode(() -> service.get(UuidV7.generate()), ApiErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void delete_removesThePlanTogetherWithItsRoster() {
        UUID planId = service.create(draft("it_plan_删")).plan().id();
        seedVest(201);
        service.replaceRoster(planId, List.of(rosterDraft(201, Team.RED)));

        service.delete(planId);

        assertCode(() -> service.get(planId), ApiErrorCode.PLAN_NOT_FOUND);
        // 库层没有外键：编成行不跟着删就会指向一份不存在的想定，且再没有任何入口能读到它
        assertThat(rosters.findByPlanId(planId)).isEmpty();
    }

    // ── 演习进行中的 409 ────────────────────────────────────────────────────

    @Test
    void update_ofTheRunningPlansTarget_isPlanInUse() {
        UUID planId = service.create(draft("it_plan_演中改")).plan().id();
        seedRunningExercise(planId);

        assertCode(() -> service.update(planId, draft("it_plan_演中改后")), ApiErrorCode.PLAN_IN_USE);
        assertThat(service.get(planId).plan().name()).isEqualTo("it_plan_演中改");
    }

    @Test
    void delete_ofTheRunningPlansTarget_isPlanInUse() {
        UUID planId = service.create(draft("it_plan_演中删")).plan().id();
        seedRunningExercise(planId);

        assertCode(() -> service.delete(planId), ApiErrorCode.PLAN_IN_USE);
        assertThat(plans.existsById(planId)).isTrue();
    }

    @Test
    void replaceRoster_ofTheRunningPlansTarget_isPlanInUse() {
        UUID planId = service.create(draft("it_plan_演中编成")).plan().id();
        seedVest(601);
        seedRunningExercise(planId);

        assertCode(() -> service.replaceRoster(planId, List.of(rosterDraft(601, Team.RED))),
                ApiErrorCode.PLAN_IN_USE);
        assertThat(rosters.findByPlanId(planId)).isEmpty();
    }

    @Test
    void update_ofAnotherPlansTarget_isNotBlockedByARunningExercise() {
        UUID running = service.create(draft("it_plan_在演")).plan().id();
        UUID idle = service.create(draft("it_plan_空闲")).plan().id();
        seedRunningExercise(running);

        // 判据是「进行中的那场演习引用的就是这份想定」，不是「有演习在跑就不许改想定」
        assertThat(service.update(idle, draft("it_plan_空闲改")).plan().name()).isEqualTo("it_plan_空闲改");
    }

    // ── 编成读写 ────────────────────────────────────────────────────────────

    @Test
    void replaceRoster_writesTheWholeSetAndReturnsItInStoredOrder() {
        UUID planId = service.create(draft("it_plan_编成")).plan().id();
        seedVest(301);
        seedVest(302);
        seedVest(303);

        List<RosterEntry> stored = service.replaceRoster(planId, List.of(
                rosterDraft(303, Team.RED), rosterDraft(301, Team.BLUE), rosterDraft(302, Team.RED)));

        // 落库顺序按「队伍 → 马甲编号」，与请求体顺序无关
        assertThat(stored).extracting(RosterEntry::vestNum).containsExactly(301, 302, 303);
        assertThat(service.get(planId).rosterCount()).isEqualTo(3);

        assertThat(service.replaceRoster(planId, List.of(rosterDraft(303, Team.RED))))
                .extracting(RosterEntry::vestNum).containsExactly(303);
        assertThat(service.get(planId).rosterCount()).isEqualTo(1);
    }

    @Test
    void roster_ofAPlanWithoutRoster_isAnEmptyListNot404() {
        UUID planId = service.create(draft("it_plan_空编成")).plan().id();

        assertThat(service.roster(planId)).isEmpty();
    }

    @Test
    void roster_ofMissingPlan_isPlanNotFound() {
        assertCode(() -> service.roster(UuidV7.generate()), ApiErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void replaceRoster_withAVestNumMissingFromTheLedger_isRejectedWithoutTouchingTheStoredRoster() {
        UUID planId = service.create(draft("it_plan_台账外")).plan().id();
        seedVest(401);
        service.replaceRoster(planId, List.of(rosterDraft(401, Team.RED)));

        assertCode(() -> service.replaceRoster(planId,
                List.of(rosterDraft(401, Team.RED), rosterDraft(499, Team.RED))),
                ApiErrorCode.VALIDATION_FAILED);

        // 「不产生部分写入」：整份替换被拒之后，库里还是上一次那份
        assertThat(rosters.findByPlanId(planId)).extracting(RosterEntry::vestNum).containsExactly(401);
    }

    @Test
    void replaceRoster_withTheSameVestNumTwice_isRejected() {
        UUID planId = service.create(draft("it_plan_重号")).plan().id();
        seedVest(501);

        assertCode(() -> service.replaceRoster(planId,
                List.of(rosterDraft(501, Team.RED), rosterDraft(501, Team.RED))),
                ApiErrorCode.VALIDATION_FAILED);
        assertThat(rosters.findByPlanId(planId)).isEmpty();
    }

    @Test
    void replaceRoster_withTheSameVestNumOnTwoTeams_isRejected() {
        UUID planId = service.create(draft("it_plan_跨队")).plan().id();
        seedVest(502);

        assertCode(() -> service.replaceRoster(planId,
                List.of(rosterDraft(502, Team.RED), rosterDraft(502, Team.BLUE))),
                ApiErrorCode.VALIDATION_FAILED);
        assertThat(rosters.findByPlanId(planId)).isEmpty();
    }

    @Test
    void replaceRoster_acceptsTheProtocolBounds() {
        UUID planId = service.create(draft("it_plan_边界")).plan().id();
        seedVest(801);
        seedVest(802);

        List<RosterEntry> stored = service.replaceRoster(planId, List.of(
                new PlanService.RosterDraft(801, null, Team.RED, 0, 0, 0, PollMode.AUTO),
                new PlanService.RosterDraft(802, null, Team.BLUE, 255, 65535, 65535, PollMode.AUTO)));

        // 0 与上限都合法：14B 轮询配置帧里 hp 占 1 字节、ammo 各占 2 字节大端，正好装得下
        // 读回顺序按「队伍 → 马甲编号」，BLUE 排在 RED 前
        assertThat(stored)
                .extracting(RosterEntry::vestNum, RosterEntry::hp, RosterEntry::ammo1, RosterEntry::ammo2)
                .containsExactly(tuple(802, 255, 65535, 65535), tuple(801, 0, 0, 0));
    }

    @Test
    void replaceRoster_withAnHpPastOneByteOrAmmoPastTwoBytes_isRejectedWithoutTouchingTheStoredRoster() {
        UUID planId = service.create(draft("it_plan_越界")).plan().id();
        seedVest(811);
        seedVest(812);
        seedVest(813);
        service.replaceRoster(planId, List.of(rosterDraft(811, Team.RED)));

        // 越界值若落库，开演时组帧会按字节静默截断（hp=256 变 0、ammo=65536 变 0），现场只见「参数没生效」
        assertCode(() -> service.replaceRoster(planId,
                List.of(new PlanService.RosterDraft(812, null, Team.RED, 256, 30, 30, PollMode.AUTO))),
                ApiErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.replaceRoster(planId,
                List.of(new PlanService.RosterDraft(813, null, Team.RED, 100, 65536, 30, PollMode.AUTO))),
                ApiErrorCode.VALIDATION_FAILED);

        // 「不产生部分写入」：两次拒绝之后，库里还是上一次那份
        assertThat(rosters.findByPlanId(planId)).extracting(RosterEntry::vestNum).containsExactly(811);
    }

    @Test
    void replaceRoster_withANegativeHp_isRejected() {
        UUID planId = service.create(draft("it_plan_负值")).plan().id();
        seedVest(821);

        assertCode(() -> service.replaceRoster(planId,
                List.of(new PlanService.RosterDraft(821, null, Team.RED, -1, 30, 30, PollMode.AUTO))),
                ApiErrorCode.VALIDATION_FAILED);
        assertThat(rosters.findByPlanId(planId)).isEmpty();
    }

    @Test
    void replaceRoster_withAnEmptyList_clearsTheRoster() {
        UUID planId = service.create(draft("it_plan_清空")).plan().id();
        seedVest(701);
        service.replaceRoster(planId, List.of(rosterDraft(701, Team.RED)));

        // 停演后才允许空编成：开演的 400 EXERCISE_ROSTER_EMPTY 归 startExercise，不归本方法
        assertThat(service.replaceRoster(planId, List.of())).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static PlanService.PlanDraft draft(String name) {
        return new PlanService.PlanDraft(name, null, BattleMode.TRAIN, null);
    }

    private static PlanService.RosterDraft rosterDraft(int vestNum, Team team) {
        return new PlanService.RosterDraft(vestNum, null, team, 100, 30, 30, PollMode.AUTO);
    }

    /** 直接落一行台账：编成校验只要求编号存在于 {@code t_vest}，故其余列给最小值。 */
    private void seedVest(int vestNum) {
        vests.insert(new Vest(vestNum, null, Team.RED, null, null, null, null, false, true,
                null, null, null, null));
    }

    /** 造一场进行中的演习，引用指定想定。 */
    private void seedRunningExercise(UUID planId) {
        exercises.insert(new Exercise(UuidV7.generate(), planId, "it_plan_演中", null,
                BattleMode.TRAIN, START, null, 1, 0, 0, null, null));
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ApiErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
