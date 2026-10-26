package com.dualx.gateway.link;

import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.LinkStatusPushPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 链路注册表：三种 {@link LinkKind} 各一个 {@link LinkSlot}，外加串口独占归属表。
 *
 * <p>把「谁能开、什么情况算冲突、串口被谁占着」集中在这一处，适配器（串口 / Netty）
 * 只管物理动作，不再各自判重。开启语义与两种 409 的区别见
 * {@code com.dualx.core.port.DeviceLinkAdmin} 的类注释。</p>
 *
 * <p>典型调用序列：{@link #beginOpen}（占坑并判冲突）→ 物理打开 → 成功 {@link #finishOpen}
 * / 失败 {@link #abortOpen}；关闭一律走 {@link #close}（幂等）。</p>
 *
 * <p><b>本类同时是链路状态推送的扇出点</b>（设计 [02](../../../../../docs/design/02-push-ports.md)
 * §4.2「端口调用方必须是该状态的唯一所有者」）：链路状态的真源就在这里，因此推送也由这里发出，
 * 而不是让四个适配器各自在各自的开关分支里调一次端口。每个 {@link LinkSlot} 的状态迁移与
 * 接入数变化都回打到 {@link #fanOut()}，于是 {@code snapshot()}（Poll）与推送（Push）必然读同一份数据——
 * 同构不是靠人工校准，而是靠「只有一个出口」。</p>
 */
public final class LinkRegistry {

    private static final Logger log = LoggerFactory.getLogger(LinkRegistry.class);

    private final Map<LinkKind, LinkSlot> slots = new EnumMap<>(LinkKind.class);

    /** 串口名 → 持有者。同一物理串口同一时刻只允许被一条链路持有。 */
    private final Map<String, LinkKind> serialOwners = new HashMap<>();

    /**
     * 客户端推送端口；{@code null} 表示本进程没有 WS 消费者（纯单测、或只要网关不要 WS 的切片），
     * 此时链路状态只更新注册表，{@code GET /api/v1/devices/links} 仍是唯一真源。
     */
    private final LinkStatusPushPort push;

    /** 无推送的注册表：状态机行为与带推送时完全一致，只是不广播。 */
    public LinkRegistry() {
        this(null);
    }

    /**
     * @param push 变化后广播完整快照的端口；{@code null} 表示不广播。
     *             端口方法在链路状态迁移路径上被同步调用，因此约定它非阻塞、不抛异常
     *             （见 {@link LinkStatusPushPort}），本类仍会兜一层异常保护：
     *             推送失败绝不能让「链路已经开了」这件事对调用方表现为失败。
     */
    public LinkRegistry(LinkStatusPushPort push) {
        this.push = push;
        for (LinkKind kind : LinkKind.values()) {
            LinkSlot slot = new LinkSlot(kind);
            if (push != null) {
                slot.onChange(this::fanOut);
            }
            slots.put(kind, slot);
        }
    }

    /**
     * 进入「开启中」：判重、判 UWB 互斥、占串口，全部在一个临界区内完成，
     * 避免「检查通过但占坑失败」的竞态。
     *
     * @param kind     目标链路
     * @param portName 需要独占的串口名；{@code UWB_TCP_SERVER} 传 {@code null}
     * @param target   写进 {@link LinkStatus} 的目标描述（串口名或 {@code host:port}）
     * @param baudRate 串口波特率，非串口传 {@code null}
     * @return 已置为 {@code OPENING} 的槽位，供适配器后续上报计数
     * @throws BusinessException {@code DEVICE_LINK_ALREADY_OPEN}（本链路或另一种 UWB 模式已开）
     *                           或 {@code DEVICE_LINK_SERIAL_BUSY}（串口被别人占着）
     */
    public LinkSlot beginOpen(LinkKind kind, String portName, String target, Integer baudRate) {
        synchronized (this) {
            LinkSlot slot = slots.get(kind);
            if (slot.active()) {
                throw new BusinessException(ApiErrorCode.DEVICE_LINK_ALREADY_OPEN,
                        "链路已开启：" + kind);
            }
            LinkKind twin = uwbTwin(kind);
            if (twin != null && slots.get(twin).active()) {
                throw new BusinessException(ApiErrorCode.DEVICE_LINK_ALREADY_OPEN,
                        "另一种 UWB 接入方式已开启：" + twin + "，切换前必须先关闭");
            }
            if (portName != null) {
                LinkKind owner = serialOwners.get(portName);
                if (owner != null && owner != kind) {
                    throw new BusinessException(ApiErrorCode.DEVICE_LINK_SERIAL_BUSY,
                            "串口已被 " + owner + " 占用：" + portName);
                }
                serialOwners.put(portName, kind);
            }
            slot.markOpening(target, baudRate);
            return slot;
        }
    }

    /** 物理打开成功。 */
    public void finishOpen(LinkKind kind) {
        slots.get(kind).markOpen();
    }

    /** 物理打开失败：释放已占的串口并落到 {@code ERROR}，不留半开状态。 */
    public void abortOpen(LinkKind kind, String message) {
        synchronized (this) {
            serialOwners.values().removeIf(owner -> owner == kind);
            slots.get(kind).markError(message);
        }
    }

    /** 关闭链路：幂等，释放串口归属并把状态与计数整体复位。 */
    public void close(LinkKind kind) {
        synchronized (this) {
            serialOwners.values().removeIf(owner -> owner == kind);
            slots.get(kind).markClosed();
        }
    }

    public LinkSlot slot(LinkKind kind) {
        return slots.get(kind);
    }

    /**
     * HTTP {@code GET /api/v1/devices/links} 与 WebSocket 推送共用的同一份真源：
     * 恒按声明顺序返回三种 {@link LinkKind} 各一条，未配置过即 {@code CLOSED}。
     */
    public List<LinkStatus> snapshot() {
        LinkKind[] kinds = LinkKind.values();
        List<LinkStatus> out = new ArrayList<>(kinds.length);
        for (LinkKind kind : kinds) {
            out.add(slots.get(kind).status());
        }
        return List.copyOf(out);
    }

    /**
     * 向客户端广播当前完整快照（设计 02 §4.2「变化时写它并扇出」）。
     *
     * <p><b>为什么扇出整份 {@link #snapshot()} 而不是「变化的那一条」</b>：契约 {@code wsLinks}
     * 的根类型与 HTTP {@code getLinks} 完全一致，客户端两条通道因此共用同一套合并逻辑；
     * 只推单条会让 Push 与 Poll 出现两套根类型，同构就只能靠人工校准。</p>
     *
     * <p>本方法会在链路状态迁移路径上（含 {@code beginOpen} 的临界区内）被调用，因此只做
     * 「取快照 + 投递」：真正的写与背压由 api 侧会话表下沉到各会话自己的发送线程（设计 02 §4.7）。</p>
     */
    private void fanOut() {
        LinkStatusPushPort port = push;
        if (port == null) {
            return;
        }
        try {
            port.publish(snapshot());
        } catch (RuntimeException failure) {
            // 端口约定「不得抛异常」，这里仍兜一层：状态已经迁移，把异常抛回 HTTP/Netty 线程
            // 会让调用方看到失败、而链路其实已经开了——那是最坏的一种不一致。
            log.warn("链路状态推送失败（已忽略，不影响链路状态机）：{}", failure.toString());
        }
    }

    /** UWB 两种接入方式互斥；串口链路没有「孪生」。 */
    private static LinkKind uwbTwin(LinkKind kind) {
        return switch (kind) {
            case UWB_TCP_SERVER -> LinkKind.UWB_SERIAL;
            case UWB_SERIAL -> LinkKind.UWB_TCP_SERVER;
            default -> null;
        };
    }
}
