package com.dualx.core.port;

import com.dualx.core.domain.LinkStatus;

import java.util.List;

/**
 * 链路状态**推送**端口（设计 [02](../../../../docs/design/02-push-ports.md) §4.1、§4.2、§6 Q9）：
 * 网关侧链路状态（或接入数）变化时调用，由接口适配层（{@code scds-api} 的
 * {@code LinkStatusBroadcaster} → WebSocket）广播给客户端。
 *
 * <p>与 {@link SituationPushPort} 对称：端口定义在中立的 {@code scds-domain}，网关
 * （{@code scds-gateway}）是调用方、接口适配层是实现方。依赖链仍为
 * {@code scds-api → scds-core → scds-domain} 与 {@code scds-gateway → scds-domain}，无环。</p>
 *
 * <p><b>载荷是完整列表快照，与 {@code GET /api/v1/devices/links} 同构</b>：HTTP Poll 返回
 * {@link DeviceLinkAdmin#snapshot()}（三种 {@link com.dualx.core.domain.LinkKind} 恒定各一条），
 * 本端口推的是<b>同一份</b>列表——不是「某一条链路的一条变化」。设计 02 §6 Q9 裁定两端统一为
 * 完整列表快照，因此这里不存在「单条 vs 列表」两套根类型，同构是<b>类型级</b>保证。</p>
 *
 * <p><b>为什么是「完整快照」而不是「单条增量」</b>：链路只有三条、每条约十个字段，
 * 全量快照的开销远小于「客户端自行维护三条链路的局部状态」所需的对齐逻辑（漏一条、
 * 乱序一条都会让客户端显示错误的链路状态）。同时这让「值未变时重推」变得无害，
 * 也因此不需要 revision / 序号机制（设计 02 §6 Q7）。</p>
 *
 * <p><b>实现方约定</b>：本方法在网关的链路状态迁移路径（串口读线程、Netty worker、
 * HTTP 线程）上被直接调用，<b>必须非阻塞、不得抛异常</b>；任何 I/O 等待（慢客户端、背压）
 * 都要下沉到实现方自己的发送线程与有界队列里，不得阻塞上游，更不得让推送失败反过来影响
 * 链路开关的语义。</p>
 */
public interface LinkStatusPushPort {

    /**
     * 广播一份链路状态全量快照。
     *
     * @param snapshot 与 {@code GET /api/v1/devices/links} 响应体同构的列表（三种链路恒定各一条）；
     *                 调用方保证非 null、非空
     */
    void publish(List<LinkStatus> snapshot);
}
