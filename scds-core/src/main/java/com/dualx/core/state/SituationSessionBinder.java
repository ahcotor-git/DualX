package com.dualx.core.state;

import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.repository.BaseMapRepository;
import com.dualx.core.repository.ExerciseUnitRepository;
import com.dualx.core.repository.PlanRosterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * LIFE 与 STATE 之间的<b>会话编排</b>（设计 06 §4.6）：把「开演装载编成」与「停演写终局」两个
 * 跨包动作收在一处，使 {@link SituationStateService} 保持纯状态机、不感知演习生命周期。
 *
 * <p>调用纪律（顺序错会读到错数据）：</p>
 * <ol>
 *   <li>开演：LIFE 的 {@code startExercise} 建好演习行后，调 {@link #onExerciseStarted}；</li>
 *   <li>停演：LIFE 调 {@code freezeAndClose} <b>之前</b>，先调 {@link #onExerciseStopping}——
 *       后者先把全体单位终局写进 {@code t_exercise_unit}，而 {@code freezeAndClose} 读的正是库里的
 *       {@code final_status}（{@code countDeadByExerciseId}），顺序反了会把它记成 0 阵亡。</li>
 * </ol>
 *
 * <p><b>开演读想定编成而非演习快照</b>：{@code t_exercise_unit} 是终局台账，只有
 * {@code vest_num/name/team/final_status/...}，<b>没有</b>初始 {@code hp/ammo/mode} 三列；
 * 而 {@link SituationStateService#beginExercise} 要的正是带这三项的 {@link RosterEntry}。
 * 两份编成的人员集合在开演时是同一份（演习快照由 {@code startExercise} 从想定编成而来），
 * 故取字段更全的一方读，不新造默认值。</p>
 *
 * <p><b>装配未落地</b>：本类与 {@link SituationStateService} 都还没有 Spring 装配点，
 * 由后续装配段（S8 或 M4）绑定；在此之前它只在单测中被驱动。</p>
 */
public final class SituationSessionBinder {

    private static final Logger log = LoggerFactory.getLogger(SituationSessionBinder.class);

    private final SituationStateService state;
    private final PlanRosterRepository planRoster;
    private final ExerciseUnitRepository exerciseUnits;
    private final BaseMapRepository baseMaps;
    private final Clock clock;

    public SituationSessionBinder(SituationStateService state, PlanRosterRepository planRoster,
                                  ExerciseUnitRepository exerciseUnits, BaseMapRepository baseMaps,
                                  Clock clock) {
        this.state = Objects.requireNonNull(state, "state");
        this.planRoster = Objects.requireNonNull(planRoster, "planRoster");
        this.exerciseUnits = Objects.requireNonNull(exerciseUnits, "exerciseUnits");
        this.baseMaps = Objects.requireNonNull(baseMaps, "baseMaps");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 开演装载：按 {@code exercise.planId()} 读想定编成，装进态势内存态（初始 {@code OFFLINE}、
     * {@code position=null}、{@code positionSource=NONE}、{@code revision=0}），并把装载时刻记为
     * {@code tMs} 原点。幂等：同一演习重复装载直接返回（{@link SituationStateService#beginExercise}）。
     *
     * <p>不复核编成是否为空：空编成的拒绝是 {@code startExercise} 的
     * {@code 400 EXERCISE_ROSTER_EMPTY}，不在这里重复判。</p>
     *
     * @return 装载进内存态的人数
     */
    public int onExerciseStarted(Exercise exercise) {
        Objects.requireNonNull(exercise, "exercise");
        List<RosterEntry> roster = planRoster.findByPlanId(exercise.planId());
        state.beginExercise(exercise.id(), clock.instant(), roster);
        loadIndoorGeometry(exercise);
        log.info("态势装载：exerciseId={}，想定={}，单元数={}", exercise.id(), exercise.planId(), roster.size());
        return roster.size();
    }

    /**
     * 装载本场底图的室内解算几何（{@code beginExercise} 之后调用）。
     *
     * <p>{@code exercise.mapId} 为 null（室外纯 GPS）或底图行已删时显式传 null，让停在场内的
     * 上一场几何被清掉——留着它会让新演习的 UWB 帧按旧图解算，产出**静默的错误坐标**
     * （见 {@link SituationStateService#beginExercise}）。</p>
     */
    private void loadIndoorGeometry(Exercise exercise) {
        UUID mapId = exercise.mapId();
        if (mapId == null) {
            state.loadIndoorMap(null);
            return;
        }
        baseMaps.findById(mapId).ifPresentOrElse(
                state::loadIndoorMap,
                () -> {
                    log.warn("本场底图不存在，室内解算不可用：exerciseId={}，mapId={}", exercise.id(), mapId);
                    state.loadIndoorMap(null);
                });
    }

    /**
     * 停演收尾：写全体单位终局到 {@code t_exercise_unit}，并落掉轨迹缓冲（{@code endExercise} 内部完成），
     * 最后清空内存态。**必须在 {@code freezeAndClose} 之前调用**（见类注释）。
     *
     * <p>轨迹 flush 与终局的先后：设计 06 §4.6 写的是「先写终局、再 flush 轨迹」，其理由是
     * {@code freezeAndClose} 不能早于写终局；而 flush 的位置不影响任何读者（{@code freezeAndClose}
     * 读的是 {@code t_exercise_unit} 与 {@code t_hit_event}，不读 {@code t_track_sample}）。因此本实现把
     * flush 收进 {@link SituationStateService#endExercise()}，让「停演必 flush」不依赖调用方纪律，
     * 两者仍然都在 {@code freezeAndClose} 之前完成。</p>
     *
     * @param exerciseId 正在收尾的演习；必须与态势内存态里装载的那一场一致，
     *                   否则不写库（避免把 A 场的终局写到 B 场名下）
     * @return 冻结成功的行数；无演习进行中、或 id 不匹配时为 0
     */
    public int onExerciseStopping(UUID exerciseId) {
        Objects.requireNonNull(exerciseId, "exerciseId");
        UUID current = state.exerciseId();
        if (current == null) {
            log.warn("态势收尾被跳过：当前没有装载中的演习（请求 exerciseId={}）", exerciseId);
            return 0;
        }
        if (!current.equals(exerciseId)) {
            log.warn("态势收尾被跳过：请求 exerciseId={} 与装载中的 {} 不一致", exerciseId, current);
            return 0;
        }
        List<SituationStateService.UnitOutcome> outcomes = state.endExercise();
        int frozen = 0;
        for (SituationStateService.UnitOutcome outcome : outcomes) {
            frozen += exerciseUnits.freezeOutcome(
                    exerciseId,
                    outcome.vestNum(),
                    outcome.finalStatus(),
                    outcome.deadAt(),
                    outcome.kills(),
                    outcome.hitsDealt());
        }
        log.info("态势收尾：exerciseId={}，终局已冻结 {} 行", exerciseId, frozen);
        return frozen;
    }
}
