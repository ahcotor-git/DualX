package com.dualx.gateway.link;

import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单条链路的运行态槽位：状态机 + 计数，是 {@link LinkRegistry} 汇总 {@code snapshot()} 的真源。
 *
 * <p>{@code LinkStatus} 落库即失真（它是进程内事实），因此**整体不落库**；
 * 计数自本次开启起累计，关闭后归零——「本次开启以来」是契约里写明的口径。</p>
 *
 * <p>线程安全：状态迁移与计数会被串口读线程、Netty worker 与 HTTP 线程同时访问。
 * 状态用同一把锁保护（迁移不频繁），计数用 {@link AtomicLong} 以免热路径每次都抢锁。</p>
 *
 * <p>本类型只管「状态与计数」，不含任何物理资源。打开/关闭的冲突判定在
 * {@link LinkRegistry}：那里才有三种链路之间的互斥与串口独占关系。</p>
 *
 * <p><b>变化扇出</b>：状态迁移与接入数变化后触发 {@link #onChange} 注册的钩子（由
 * {@link LinkRegistry} 装上），把「变化 → 通知」收在一个出口，而不是在每个适配器的每个
 * 变化点上各写一次。{@link #recordFrame()} 一类每帧都走的<b>热路径刻意不触发</b>：
 * 契约只要求链路状态变化时推送，逐帧重推会淹没真正的变化、浪费带宽，并掩盖真正的推送问题。</p>
 */
public final class LinkSlot {

    private final LinkKind kind;
    private final AtomicLong framesTotal = new AtomicLong();
    private final AtomicLong droppedTotal = new AtomicLong();
    private final AtomicLong crcErrorTotal = new AtomicLong();
    private final Object lock = new Object();

    private LinkState state = LinkState.CLOSED;
    private String target;
    private Integer baudRate;
    private Integer fdCount;
    private String errorMessage;
    private volatile Instant lastFrameAt;

    /** 状态/接入数变化后的扇出钩子；未注册时为 {@code null}（纯单测不需要推送）。 */
    private volatile Runnable changeHook;

    LinkSlot(LinkKind kind) {
        this.kind = kind;
    }

    /** 由 {@link LinkRegistry} 在构造时注册；钩子必须非阻塞且自行吞掉异常。 */
    void onChange(Runnable hook) {
        this.changeHook = hook;
    }

    private void fireChanged() {
        Runnable hook = changeHook;
        if (hook != null) {
            hook.run();
        }
    }

    public LinkKind kind() {
        return kind;
    }

    /** 是否已进入「开启中」或「已开启」——这是「重开冲突」的唯一判据。 */
    public boolean active() {
        synchronized (lock) {
            return state == LinkState.OPENING || state == LinkState.OPEN;
        }
    }

    /** 由 {@link LinkRegistry#beginOpen} 调用；同时清零上一次开启留下的计数。 */
    public void markOpening(String target, Integer baudRate) {
        synchronized (lock) {
            this.state = LinkState.OPENING;
            this.target = target;
            this.baudRate = baudRate;
            this.fdCount = null;
            this.errorMessage = null;
            this.lastFrameAt = null;
            framesTotal.set(0L);
            droppedTotal.set(0L);
            crcErrorTotal.set(0L);
        }
        fireChanged();
    }

    /** 由 {@link LinkRegistry#finishOpen} 调用。 */
    public void markOpen() {
        synchronized (lock) {
            this.state = LinkState.OPEN;
        }
        fireChanged();
    }

    /** 由 {@link LinkRegistry#abortOpen} 调用：物理打开失败，保留原因供排障。 */
    public void markError(String message) {
        synchronized (lock) {
            this.state = LinkState.ERROR;
            this.errorMessage = message;
        }
        fireChanged();
    }

    /** 由 {@link LinkRegistry#close} 调用：回到 CLOSED 并清空本次开启的全部痕迹。 */
    public void markClosed() {
        synchronized (lock) {
            this.state = LinkState.CLOSED;
            this.target = null;
            this.baudRate = null;
            this.fdCount = null;
            this.errorMessage = null;
            this.lastFrameAt = null;
            framesTotal.set(0L);
            droppedTotal.set(0L);
            crcErrorTotal.set(0L);
        }
        fireChanged();
    }

    /** 收到一整帧合法帧。适配器在读线程直接调用。 */
    public void recordFrame() {
        framesTotal.incrementAndGet();
        lastFrameAt = Instant.now();
    }

    /** 丢弃一帧坏帧（长度非法、成帧失败）。 */
    public void recordDropped() {
        droppedTotal.incrementAndGet();
    }

    /** 校验失败单独计数，便于现场区分「干扰」与「协议不匹配」。 */
    public void recordCrcError() {
        crcErrorTotal.incrementAndGet();
    }

    /** 仅 UWB TCP Server 有意义：当前接入连接数。 */
    public void updateConnections(int count) {
        synchronized (lock) {
            this.fdCount = count;
        }
        fireChanged();
    }

    public LinkStatus status() {
        synchronized (lock) {
            return new LinkStatus(kind, state, target, baudRate, fdCount, lastFrameAt,
                    framesTotal.get(), droppedTotal.get(), crcErrorTotal.get(), errorMessage);
        }
    }
}
