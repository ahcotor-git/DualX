package com.dualx.gateway.link;

import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;

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
 */
public final class LinkRegistry {

    private final Map<LinkKind, LinkSlot> slots = new EnumMap<>(LinkKind.class);

    /** 串口名 → 持有者。同一物理串口同一时刻只允许被一条链路持有。 */
    private final Map<String, LinkKind> serialOwners = new HashMap<>();

    public LinkRegistry() {
        for (LinkKind kind : LinkKind.values()) {
            slots.put(kind, new LinkSlot(kind));
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

    /** UWB 两种接入方式互斥；串口链路没有「孪生」。 */
    private static LinkKind uwbTwin(LinkKind kind) {
        return switch (kind) {
            case UWB_TCP_SERVER -> LinkKind.UWB_SERIAL;
            case UWB_SERIAL -> LinkKind.UWB_TCP_SERVER;
            default -> null;
        };
    }
}
