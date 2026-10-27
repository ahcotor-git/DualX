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
import org.springframework.beans.factory.DisposableBean;
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

    /**
     * 停机时释放全部链路（{@code INV-STOP} / T-STOP）：马甲串口、UWB TCP 监听、UWB 串口
     * 都要在进程退出前交还。
     *
     * <p><b>为什么要在装配层补这一刀</b>：三种链路句柄各自的 {@code close()} 早已实现
     * （设计 05 §4.2 要求句柄自持 Netty / 读线程并能在关闭时中断它们），但 {@link DeviceLinkAdmin}
     * 这个端口上并没有「把三条一起关掉」的方法，于是「谁在停机时调用 close」一直没有归属。
     * 实测：只调 {@code ConfigurableApplicationContext.close()}，12001 会被本进程占到 JVM 退出为止，
     * 于是 {@code server.shutdown=graceful} 承诺的「串口链路在退出前释放」实际没有发生。</p>
     *
     * <p><b>为什么不能省</b>：Netty 事件循环与链路读线程都是守护线程，端口没放掉 JVM 照样能退出，
     * 所以「停机没报错」不等于「资源已还」。UWB 监听口与 Windows 上的 COM 口尤其明显——
     * {@code application.yml} 已写明强杀会让下一次启动抢不到串口。</p>
     *
     * <p>只依赖端口方法，因此真链路与仿真共用同一份释放逻辑；三条链路各自兜异常，
     * 一条失败不影响其余两条被释放。</p>
     */
    @Bean
    public LinkShutdown linkShutdown(DeviceLinkAdmin deviceLinkAdmin) {
        return new LinkShutdown(deviceLinkAdmin);
    }

    /** 见 {@link #linkShutdown(DeviceLinkAdmin)}。 */
    static final class LinkShutdown implements DisposableBean {

        private final DeviceLinkAdmin admin;

        LinkShutdown(DeviceLinkAdmin admin) {
            this.admin = admin;
        }

        @Override
        public void destroy() {
            release("马甲串口", admin::closeSerial);
            release("UWB TCP Server", admin::closeUwbTcpServer);
            release("UWB 串口", admin::closeUwbSerial);
        }

        private static void release(String what, Runnable close) {
            try {
                close.run();
            } catch (RuntimeException failure) {
                log.warn("停机释放{}失败（继续释放其余链路）：{}", what, failure.toString());
            }
        }
    }
}
