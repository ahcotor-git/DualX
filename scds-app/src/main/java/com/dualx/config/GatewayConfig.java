package com.dualx.config;

import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.core.port.HardwareEventSink;
import com.dualx.core.port.LinkStatusPushPort;
import com.dualx.gateway.config.GatewayProperties;
import com.dualx.gateway.link.LinkRegistry;
import com.dualx.gateway.link.RealDeviceLinkAdmin;
import com.dualx.gateway.sim.SimulatedLinkAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 硬件链路装配层（设计 docs/design/05-hw-link.md §4.1、§4.6）。
 *
 * <p><b>为什么必须在 scds-app 而不是 scds-api / scds-gateway</b>：{@code scds-api} 不依赖
 * {@code scds-gateway}（控制器只注入领域端口 {@link DeviceLinkAdmin}），{@code scds-gateway}
 * 又刻意保持零 Spring 依赖（{@code GatewayProperties} 因此是可变 JavaBean 而非 {@code record}）。
 * 两条约束合起来只剩一个位置能同时看见「端口定义」与「真链路实现」——就是这里。</p>
 *
 * <p><b>真链路与仿真二选一</b>：按 {@code scds.gateway.mode} 装配。两者共用同一个
 * {@link LinkRegistry} 与同一套状态机语义，因此仿真下通过的端点接线、成帧与冲突判据，
 * 与真链路是同一套；仿真只少了物理动作。</p>
 *
 * <p><b>⚠ 仿真不是现场通过的替代</b>：真实串口 115200 的时序、UWB 盒子经 12001 的连续坐标、
 * 容量与长稳都无法由仿真证明（§7）。</p>
 *
 * <p><b>尚未接入的配置</b>：{@code scds.gateway.auto-open-on-startup} 目前无消费者
 * （开机按 {@code t_link_config.enabled} 自动开链路随配置接口/LIFE 段落地），
 * {@code scds.gateway.uwb.tcp-backlog} / {@code worker-threads} 亦未接入 Netty 启动参数
 * ——不把未验证的容量值写进启动路径。</p>
 */
@Configuration(proxyBeanMethods = false)
public class GatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);

    /**
     * {@code GatewayProperties} 刻意不挂 {@code @ConfigurationProperties}（那会把 Spring 依赖
     * 带进零依赖的 {@code scds-gateway}），绑定改由这里完成；该路径走 JavaBean 的 setter，
     * 所以属性类必须是可变类而不是 {@code record}。
     */
    @Bean
    @ConfigurationProperties("scds.gateway")
    public GatewayProperties gatewayProperties() {
        return new GatewayProperties();
    }

    /**
     * 三种链路共用的状态机与串口独占表；真链路与仿真必须是同一实例才能对拍。
     *
     * <p>同时把自己注册为链路状态推送的扇出点（设计 02 §4.2）：注册表是链路状态的真源，
     * 推送端口由 {@code scds-api} 的 {@code LinkStatusBroadcaster} 实现。端口缺席
     * （只要网关不要 WS 的切片）时注册表照常工作，只是不广播——{@code GET /api/v1/devices/links}
     * 仍是唯一真源，不引入 {@code Noop*} 类的意义也仅此一处。</p>
     */
    @Bean
    public LinkRegistry linkRegistry(ObjectProvider<LinkStatusPushPort> linkPush) {
        LinkStatusPushPort push = linkPush.getIfAvailable();
        if (push == null) {
            log.info("链路状态推送端口未装配（本进程无 WS 消费者）：链路变化只更新注册表，不广播。");
        }
        return new LinkRegistry(push);
    }

    @Bean
    public DeviceLinkAdmin deviceLinkAdmin(GatewayProperties properties,
                                          LinkRegistry linkRegistry,
                                          HardwareEventSink hardwareEventSink) {
        if (properties.isSimulator()) {
            log.warn("scds.gateway.mode=simulator：装配进程内仿真链路，不占有任何真实串口、不监听 12001。"
                    + "**生产必须显式切回 real**；仿真通过不得写成现场通过。");
            return new SimulatedLinkAdapter(linkRegistry, hardwareEventSink);
        }
        log.info("scds.gateway.mode=real：装配真实硬件链路（串口发送队列容量 {}）。",
                properties.getSerial().getSendQueueCapacity());
        return new RealDeviceLinkAdmin(linkRegistry, hardwareEventSink,
                properties.getSerial().getSendQueueCapacity());
    }
}
