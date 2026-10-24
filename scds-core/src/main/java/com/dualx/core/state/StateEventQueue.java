package com.dualx.core.state;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 态势状态机的**有界事件队列**（设计 06 §4.1）。
 *
 * <p>网关读线程只调用 {@link #offer(StateEvent)}——它是<b>非阻塞</b>的，绝不等待：
 * 队列满时按 {@link OverflowPolicy} 处置并计数，然后把控制权立刻还给读线程。
 * 阻塞只发生在消费侧的 {@link #take()} 上（唯一写手线程）。</p>
 *
 * <p>溢出策略：</p>
 * <ul>
 *   <li>{@link OverflowPolicy#REJECT}：拒收新事件并计入 {@link #rejectedCount()}。</li>
 *   <li>{@link OverflowPolicy#DROP_LOW_PRIORITY}：用 {@link StateEvent#droppable()} 换空间——
 *       入队的是不可丢事件时，先淘汰队内<b>最旧</b>的可丢事件（心跳刷新）；入队者本身就是可丢事件
 *       时直接丢弃它。若队内一个可丢事件都没有，则退化为拒收。</li>
 * </ul>
 *
 * <p>丢弃与拒收计数是<b>可观测指标</b>：它们是判断「容量是否够」的唯一现场证据（D10 未闭合），
 * 不能只记日志了事。</p>
 */
public final class StateEventQueue {

    /** 队列满时的处置方式（配置键 {@code scds.state.event-queue-overflow}）。 */
    public enum OverflowPolicy {
        /** 拒收新事件并计数。 */
        REJECT,
        /** 优先丢弃可丢事件（心跳刷新），保住命中/GPS/UWB 等离散事实。 */
        DROP_LOW_PRIORITY
    }

    private final int capacity;
    private final OverflowPolicy policy;
    private final Deque<StateEvent> deque;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    private long rejectedCount;
    private long droppedCount;

    /**
     * @param capacity 队列容量（配置键 {@code scds.state.event-queue-capacity}），必须 &gt; 0
     * @param policy   溢出策略，不是可空配置项
     */
    public StateEventQueue(int capacity, OverflowPolicy policy) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须 > 0，实际 " + capacity);
        }
        this.capacity = capacity;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.deque = new ArrayDeque<>(Math.min(capacity, 1 << 12));
    }

    /**
     * 非阻塞入队。
     *
     * @return true 表示事件已进入队列；false 表示被丢弃或拒收（调用方<b>不得</b>重试等待）
     */
    public boolean offer(StateEvent event) {
        Objects.requireNonNull(event, "event");
        lock.lock();
        try {
            if (deque.size() < capacity) {
                deque.addLast(event);
                notEmpty.signal();
                return true;
            }
            if (policy == OverflowPolicy.REJECT) {
                rejectedCount++;
                return false;
            }
            // DROP_LOW_PRIORITY：入队者本身可丢 → 直接丢它，给不可丢事件留位（下次心跳会重报）。
            if (event.droppable()) {
                droppedCount++;
                return false;
            }
            // 入队者不可丢 → 淘汰队内最旧的可丢事件（心跳刷新）腾位。
            if (evictOldestDroppableLocked()) {
                deque.addLast(event);
                droppedCount++;
                return true;
            }
            // 队内没有可丢事件，无处腾位。
            rejectedCount++;
            return false;
        } finally {
            lock.unlock();
        }
    }

    private boolean evictOldestDroppableLocked() {
        var it = deque.iterator();
        while (it.hasNext()) {
            if (it.next().droppable()) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    /** 阻塞取出下一条事件，供唯一写手线程驱动事件循环。 */
    public StateEvent take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (deque.isEmpty()) {
                notEmpty.await();
            }
            return deque.removeFirst();
        } finally {
            lock.unlock();
        }
    }

    /** 限时取出；超时返回 {@code null}（供需要兼顾周期任务的事件循环使用）。 */
    public StateEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (deque.isEmpty()) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            return deque.removeFirst();
        } finally {
            lock.unlock();
        }
    }

    /** 当前积压条数（可观测指标）。 */
    public int size() {
        lock.lock();
        try {
            return deque.size();
        } finally {
            lock.unlock();
        }
    }

    /** 容量（可观测指标）。 */
    public int capacity() {
        return capacity;
    }

    /** 累计拒收条数（队列满且无可丢事件）。 */
    public long rejectedCount() {
        lock.lock();
        try {
            return rejectedCount;
        } finally {
            lock.unlock();
        }
    }

    /** 累计丢弃条数（含入队者被丢与被淘汰的旧心跳）。 */
    public long droppedCount() {
        lock.lock();
        try {
            return droppedCount;
        } finally {
            lock.unlock();
        }
    }
}
