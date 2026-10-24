package com.dualx.core.state;

import com.dualx.core.push.UnitState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 态势增量的<b>环形窗口</b>（设计 06 §4.5 / [设计 02](../../../../docs/design/02-push-ports.md) §4.4）。
 * 保留最近 {@code scds.situation.revision-window} 条<b>单元级</b>变化，使客户端能凭
 * {@code sinceRevision} 补齐断线期间的变化，而不是每次重拉全量。
 *
 * <p><b>只由态势唯一写手线程写</b>（{@code SituationStateService.publish()} 处），读侧（{@code SituationQuery}）
 * 可并发读——写入是尾插 + 满则去头，读取只是遍历。窗口条数远小于数据结构开销阈值，故不加锁；
 * 若要跨线程保证一致性，应由调用方在读侧同一线程上取快照。</p>
 *
 * <p><b>超窗判定的精确形式</b>：不是「比窗口内最旧的 revision 小就算超窗」，而是
 * 「{@code sinceRevision} 小于<b>已淘汰</b>的最新一条 revision」。因为 revision 只在单元变化时递增，
 * 是<b>稀疏</b>的：窗口里最旧的条目可能远大于 {@code sinceRevision}，而中间被淘汰的条目若都不大于
 * {@code sinceRevision}，客户端并没有漏掉任何它需要的东西。<br>
 * 例：窗口容量 3，已淘汰 revision 5、保留 8/9/12；客户端 {@code since=5} → 可补齐（{@code 5 ≥ 5}）；
 * {@code since=4} → 超窗（需要 5，而 5 已被淘汰）。</p>
 *
 * <p><b>不做去重</b>：窗口按「条」保留单元级变化，同一个马甲在窗口内可以出现多条（它在不同 revision 上
 * 各变化过一次）。增量载荷要不要按 {@code vestNum} 压成最后一条，属组装增量信封的口径（S7），
 * 本类不预设——压不压都能让客户端收敛到同一个终态。</p>
 *
 * <p><b>演习切换要清窗</b>：装载新演习时 revision 归零重新爬升（设计 06 §4.5），若不 {@link #clear()}，
 * 上一场的条目会以更大的 revision 留在窗口里，被客户端当成「更新的变化」。该场次的切换本身由
 * {@code full=true} 帧表达（设计 02 §4.4）。</p>
 */
public final class SituationRevisionLog {

    /** 窗口条数的设计默认值（配置键 {@code scds.situation.revision-window}，设计 02 §5）。 */
    public static final int DEFAULT_WINDOW = 1024;

    private final int window;
    private final Deque<UnitState> ring = new ArrayDeque<>();

    /** 已被淘汰的最新一条 revision；从未淘汰过为 0——超窗判定的唯一依据。 */
    private long lastEvictedRevision;
    private long evictedCount;

    /**
     * @param window 保留条数（配置键 {@code scds.situation.revision-window}），必须 &gt; 0
     */
    public SituationRevisionLog(int window) {
        if (window <= 0) {
            throw new IllegalArgumentException("增量窗口条数必须 > 0：" + window);
        }
        this.window = window;
    }

    /**
     * 记一次单元变化。条目的 revision 取 {@code state.revision()}（即该单元最后一次变化的全局 revision，
     * 由 {@code SituationStateService} 在发布视图<b>之前</b>写好）。
     */
    public void record(UnitState state) {
        Objects.requireNonNull(state, "state");
        if (ring.size() >= window) {
            UnitState evicted = ring.removeFirst();
            lastEvictedRevision = evicted.revision();
            evictedCount++;
        }
        ring.addLast(state);
    }

    /**
     * 取 {@code sinceRevision} 之后的全部变化，按 revision 升序。
     *
     * @return 可补齐时为变化列表（可能为空列表，表示「已是最新」）；
     *         {@code sinceRevision} 已超窗时为空 Optional，调用方据此返回
     *         409 {@code SITUATION_REVISION_TOO_OLD}（HTTP 状态码由 M4 映射）
     */
    public Optional<List<UnitState>> changesSince(long sinceRevision) {
        if (tooOld(sinceRevision)) {
            return Optional.empty();
        }
        List<UnitState> changes = new ArrayList<>();
        for (UnitState state : ring) {   // ArrayDeque 迭代顺序为队首（最旧）到队尾（最新）
            if (state.revision() > sinceRevision) {
                changes.add(state);
            }
        }
        return Optional.of(changes);
    }

    /** {@code sinceRevision} 是否已超出保留窗口（需要改全量）。 */
    public boolean tooOld(long sinceRevision) {
        return sinceRevision < lastEvictedRevision;
    }

    /** 窗口内最新一条变化的 revision；窗口为空时为 0。 */
    public long latestRevision() {
        UnitState last = ring.peekLast();
        return last == null ? 0L : last.revision();
    }

    /** 窗口内最旧一条变化的 revision；窗口为空时为 0（诊断用）。 */
    public long oldestRetainedRevision() {
        UnitState first = ring.peekFirst();
        return first == null ? 0L : first.revision();
    }

    /** 已被淘汰的最新 revision（超窗判定的下界）。 */
    public long lastEvictedRevision() {
        return lastEvictedRevision;
    }

    /** 清空窗口与淘汰记录（装载新演习时调用，见类注释）。 */
    public void clear() {
        ring.clear();
        lastEvictedRevision = 0L;
        evictedCount = 0L;
    }

    /** 当前窗口内条数。 */
    public int size() {
        return ring.size();
    }

    /** 窗口容量。 */
    public int window() {
        return window;
    }

    /** 累计淘汰条数（可观测：判断窗口是否被高频变化刷满）。 */
    public long evictedCount() {
        return evictedCount;
    }
}
