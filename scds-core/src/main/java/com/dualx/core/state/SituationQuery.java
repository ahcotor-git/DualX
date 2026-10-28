package com.dualx.core.state;

import com.dualx.core.domain.HitEvent;
import com.dualx.core.push.UnitState;
import com.dualx.core.repository.HitEventRepository;
import com.dualx.protocol.Team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * 态势的<b>读侧</b>（设计 06 §5 S7 / [设计 02](../../../../docs/design/02-push-ports.md) §5）：
 * 把「内存态快照」「增量窗口」「命中流水」「队伍汇总」四类读取收在一处，供
 * {@code SituationController} 与 WS 补齐逻辑共用，避免两处各拼一次。
 *
 * <p><b>本类不组装信封</b>：{@code SituationEnvelope}（线载荷）的组装与 {@code full/增量} 判定属
 * M4 PUSH-MODEL / PUSH-WS；本类只提供原语。特别地，契约里信封的 {@code hits} 该取哪一段区间
 * 需要客户端已交付的 {@code seq}，而 HTTP `GET /situation` 只有 {@code sinceRevision}
 * （{@code seq} 与 {@code revision} **互不推导**，设计 02 §4.4），故该口径留 M4 收口——
 * 单马甲的命中补拉走契约已有的 {@code GET /situation/hits?sinceSeq=}。</p>
 *
 * <p><b>未装配</b>：本类与 {@link SituationStateService} 同属未落地 Spring 装配点的对象
 * （装配随 S8 落地），故此处不加 {@code @Component}——现在加了会因为找不到
 * {@link SituationStateService} bean 而让整个应用上下文起不来。</p>
 */
public final class SituationQuery {

    private final SituationStateService state;
    private final HitEventRepository hitEvents;

    public SituationQuery(SituationStateService state, HitEventRepository hitEvents) {
        this.state = Objects.requireNonNull(state, "state");
        this.hitEvents = Objects.requireNonNull(hitEvents, "hitEvents");
    }

    // ------------------------------------------------------------------ 状态本身

    /** 当前是否有演习进行中（无演习时 `running=false`，D15-⑥）。 */
    public boolean running() {
        return state.running();
    }

    /** 进行中的演习 ID，无则为 `null`。 */
    public UUID exerciseId() {
        return state.exerciseId();
    }

    /** 当前全局 revision（**单代**，进程重启从 1 重新开始）。 */
    public long revision() {
        return state.revision();
    }

    /**
     * 当前进程代标识（设计 02 §6 Q8）：进程启动时生成、重启必变，随信封下发。
     *
     * <p>{@link #revision()} 只在单代内可比；客户端必须同时比对本值，不一致即视为服务端已重启、
     * 丢弃本地缓存改全量拉取。</p>
     */
    public String epoch() {
        return state.epoch();
    }

    // ------------------------------------------------------------------ 全量与单马甲

    /** 全量快照，按马甲编号升序（无演习时为空列表）。 */
    public List<UnitState> snapshot() {
        return state.snapshot();
    }

    /** 单个马甲的实时状态；不在本场编成内时为空（M4 映射 404）。 */
    public Optional<UnitState> unit(int vestNum) {
        return state.unit(vestNum);
    }

    /**
     * 单个马甲的链路状态（契约 {@code getVestLink}）；不在本场编成内时为空。
     *
     * <p>与 {@link #unit(int)} 的空值口径不同：{@code getVestLink} 的 404 判据是**设备台账**是否存在
     * （{@code VestLedgerService}），编成与否不影响「能不能查这个马甲」——不在编成时应回
     * {@code online=false} 与零计数，而非 404。故本方法返回空 Optional 由调用方决定怎么降级。</p>
     */
    public Optional<SituationStateService.VestLink> vestLink(int vestNum) {
        return state.vestLink(vestNum);
    }

    // ------------------------------------------------------------------ 增量

    /**
     * {@code sinceRevision} 之后的单元级变化，按 revision 升序。
     *
     * <p><b>不按 {@code vestNum} 去重</b>：同一个马甲在窗口内可能有多个 revision 的条目
     * （它在不同时刻各变化过一次）。设计 02 §4.4 的口径是**客户端按 revision 去重**，
     * 而 WS 的「从 N+1 起逐帧补齐」也需要这条逐 revision 的原文，压掉就无法逐帧推进。
     * 因此本方法原样返回窗口内容，去重留给消费方按需处理。</p>
     *
     * @return 可补齐时为变化列表（**可能为空列表**，表示已是最新）；
     *         {@code sinceRevision} 已超出保留窗口时为空 Optional——调用方据此返回
     *         409 {@code SITUATION_REVISION_TOO_OLD}（HTTP 状态码由 M4 映射）
     */
    public Optional<List<UnitState>> changesSince(long sinceRevision) {
        return state.changesSince(sinceRevision);
    }

    // ------------------------------------------------------------------ 队伍汇总

    /**
     * 各队伍存活/阵亡汇总（契约 {@code TeamSummary}），按 {@link Team} 的声明序、只含有编成的队伍。
     *
     * <p>{@code hitsDealt} 是该队成员**打出的**命中数之和（不是被命中数），与
     * {@code t_exercise_unit.hits_dealt} 同口径（设计 06 §4.3）。</p>
     */
    public List<TeamCounts> teamSummary() {
        return teamSummaryOf(state.snapshot(), state::hitsDealt);
    }

    /**
     * 聚合本体：从单元视图与「某马甲打出的命中数」函数算出队伍汇总。
     *
     * <p><b>为什么是静态方法</b>：写侧（{@code SituationStateService}）推全量帧时也要一份**权威**汇总，
     * 而它不能反向依赖本类（本类依赖它）。队伍汇总的口径（含 {@code hitsDealt} 是「打出数」而非
     * 「被命中数」）只能有一处，故抽成不依赖状态的静态实现，供读侧与写侧共用。</p>
     *
     * @param units     参与统计的单元视图（写侧传本场快照，读侧传 {@code state.snapshot()}）
     * @param hitsDealt 取某马甲累计打出命中数的函数
     */
    public static List<TeamCounts> teamSummaryOf(Collection<UnitState> units, ToIntFunction<Integer> hitsDealt) {
        Map<Team, int[]> acc = new EnumMap<>(Team.class);
        for (UnitState u : units) {
            // [total, live, dead, offline, hitsDealt]
            int[] c = acc.computeIfAbsent(u.team(), k -> new int[5]);
            c[0]++;
            switch (u.status()) {
                case LIVE -> c[1]++;
                case DIE -> c[2]++;
                case OFFLINE -> c[3]++;
            }
            c[4] += hitsDealt.applyAsInt(u.vestNum());
        }
        List<TeamCounts> out = new ArrayList<>(acc.size());
        for (Map.Entry<Team, int[]> e : acc.entrySet()) {
            int[] c = e.getValue();
            out.add(new TeamCounts(e.getKey(), c[0], c[1], c[2], c[3], c[4]));
        }
        return out;
    }

    /** 一个队伍的人数与战损（契约 {@code TeamSummary} 的读侧载体）。 */
    public record TeamCounts(Team team, int total, int live, int dead, int offline, int hitsDealt) {
    }

    // ------------------------------------------------------------------ 命中流水

    /**
     * 命中流水增量：{@code seq > sinceSeq} 的命中，按 seq 升序（即时间顺序），至多 {@code limit} 条。
     *
     * <p>客户端把返回的**末条** {@code seq} 作为下次的 {@code sinceSeq}（与 api
     * {@code HitEventList.latestSeq} 的口径一致）；无演习进行中时返回空列表，不查库。</p>
     *
     * @param sinceSeq null 表示从头拉取
     */
    public List<HitEvent> hitsSince(Long sinceSeq, int limit) {
        UUID exerciseId = state.exerciseId();
        if (exerciseId == null) {
            return List.of();
        }
        return hitEvents.findByExerciseIdSince(exerciseId, sinceSeq, limit);
    }
}
