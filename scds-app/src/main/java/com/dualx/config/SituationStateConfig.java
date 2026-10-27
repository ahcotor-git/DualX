package com.dualx.config;

import com.dualx.core.port.SituationPushPort;
import com.dualx.core.repository.BaseMapRepository;
import com.dualx.core.repository.ExerciseUnitRepository;
import com.dualx.core.repository.HitEventRepository;
import com.dualx.core.repository.PlanRosterRepository;
import com.dualx.core.repository.TrackSampleRepository;
import com.dualx.core.state.IndoorPositionResolver;
import com.dualx.core.state.NoopSituationPushPort;
import com.dualx.core.state.SituationEnvelopeFactory;
import com.dualx.core.state.SituationQuery;
import com.dualx.core.state.SituationRevisionLog;
import com.dualx.core.state.SituationSessionBinder;
import com.dualx.core.state.SituationStateService;
import com.dualx.core.state.StateEvent;
import com.dualx.core.state.StateEventQueue;
import com.dualx.core.state.TrackBatchWriter;
import com.dualx.core.state.TrackSampler;
import com.dualx.protocol.ProtocolConstants;
import com.dualx.solver.config.ScdsUwbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.util.Locale;

/**
 * 态势状态机的装配层（设计 [06-state](../../../../docs/design/06-state.md) §4.1、§4.7、§5 S8）。
 *
 * <p><b>为什么在 scds-app</b>：{@code SituationStateService} 要落库（`HitEventRepository` /
 * `TrackSampleRepository`）且要起后台写手线程，这两件事都不是纯状态机该关心的；与
 * {@link GatewayConfig} 同理，装配只在能同时看见「端口」与「实现」的这里做。</p>
 *
 * <p><b>本段接通了两条此前没有生产者的路径</b>：</p>
 * <ol>
 *   <li>{@link SituationStateService} 现在是唯一的 {@link com.dualx.core.port.HardwareEventSink} bean
 *       ——网关解出的上行帧第一次进入真正的业务消费者（此前的 {@code LoggingHardwareEventSink} 只是
 *       让边界可见的占位）。写手线程随上下文启动（{@code initMethod="start"}）、随停机收尾
 *       （{@code destroyMethod="close"}，配合 {@code server.shutdown=graceful}）。</li>
 *   <li>在线扫描有了节拍来源：{@link OfflineScanJob} 按 {@code scds.state.offline-scan-interval-ms}
 *       往**同一队列**投递 {@link StateEvent.OfflineScan}，离线跳变因此仍由唯一写手线程产生，
 *       不新增第二个状态所有者。</li>
 * </ol>
 *
 * <p><b>未接线（不得当成已实现）</b>：{@link SituationQuery} 与 {@link SituationSessionBinder}
 * 目前只是待注入的 bean，还没有调用方——态势四个 HTTP 端点、`getVestLink` 与 WS 通道属
 * M4 PUSH-MODEL / PUSH-WS；开演/停演调用编排放到 LIFE 的流程里。</p>
 *
 * <p><b>⚠ D10 未闭合</b>：{@code scds.state.*} 与 {@code scds.situation.revision-window} 的默认值是
 * **设计候选**，不是容量结论。现场人数/基站/客户端/帧率/SLA 与 RPO/RTO 数据到位前，不得据其宣称
 * 容量达标（设计 06 §4.7）。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SituationStateConfig.SituationStateProperties.class)
public class SituationStateConfig {

    private static final Logger log = LoggerFactory.getLogger(SituationStateConfig.class);

    /** 全进程共用一个 UTC 时钟：态势时间戳、开演基准与扫描节拍必须同源，否则 tMs 原点会漂。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public StateEventQueue stateEventQueue(SituationStateProperties properties) {
        StateEventQueue queue = new StateEventQueue(properties.eventQueueCapacity(), properties.overflowPolicy());
        log.info("态势事件队列就绪：容量={}，溢出策略={}（D10 候选值，非生产容量结论）",
                queue.capacity(), properties.overflowPolicy());
        return queue;
    }

    @Bean
    public TrackSampler trackSampler(SituationStateProperties properties) {
        return new TrackSampler(properties.sampleIntervalMs(),
                properties.sampleDisplacementIndoor(), properties.sampleDisplacementOutdoor());
    }

    /**
     * {@code tickMs} 用墙钟毫秒：它只服务于「距上次落库过了多久」这一个判断，与演习时间轴
     * （{@code tMs}，以开演为原点）无关，二者混用会让间隔随演习时长漂移。
     */
    @Bean
    public TrackBatchWriter trackBatchWriter(TrackSampleRepository trackSamples, SituationStateProperties properties) {
        return new TrackBatchWriter(trackSamples, properties.trackBatchSize(), properties.trackFlushIntervalMs(),
                properties.trackMaxBuffered(), System::currentTimeMillis);
    }

    @Bean
    public SituationRevisionLog situationRevisionLog(
            @Value("${scds.situation.revision-window:" + SituationRevisionLog.DEFAULT_WINDOW + "}") int window) {
        return new SituationRevisionLog(window);
    }

    /**
     * UWB 解算参数（{@code scds.uwb.*}）。{@link ScdsUwbProperties} 刻意不带 Spring 注解
     * （{@code scds-solver} 零 Spring 依赖，见其模块说明），绑定前缀由装配层恢复。
     */
    @Bean
    @ConfigurationProperties("scds.uwb")
    public ScdsUwbProperties scdsUwbProperties() {
        return new ScdsUwbProperties();
    }

    /**
     * 室内解算器（41B UWB 帧 → 整图百分比坐标）。它是无状态配置 + 有状态滤波器，故必须是
     * **单例**：每马甲的滤波先验就存在实例里，多份实例等于同一马甲有多套先验。
     * 解算几何由开演时的 {@code SituationStateService.loadIndoorMap} 装载。
     */
    @Bean
    public IndoorPositionResolver indoorPositionResolver(ScdsUwbProperties uwbProperties) {
        return new IndoorPositionResolver(uwbProperties);
    }

    /**
     * 唯一的态势状态所有者，同时是唯一的 {@link com.dualx.core.port.HardwareEventSink} bean。
     *
     * <p>{@code initMethod="start"} 起唯一写手线程（守护线程）；{@code destroyMethod="close"} 在停机
     * 阶段置 {@code accepting=false} 并中断写手，避免停机时还在往库里写轨迹。</p>
     *
     * <p><b>为什么要 {@link ObjectProvider} 而不是 {@code @ConditionalOnMissingBean}</b>：设计 02 §5/§6 Q6
     * 要的是「没有 WS 实现时用 no-op」，而 {@code @ConditionalOnMissingBean} 的语义只在**自动配置**里
     * 有确定的求值顺序——写在普通 {@code @Configuration} 里时，它是否生效取决于组件的扫描顺序
     * （{@code SituationBroadcaster} 与本就同属被扫描的组件），会出现「加了个实现却被 no-op 顶掉」的
     * 静默故障。{@code getIfAvailable} 在**装配时**看容器实际有什么，与顺序无关，语义也正是 Q6 想要的那条。</p>
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    public SituationStateService situationStateService(StateEventQueue queue,
                                                       HitEventRepository hitEvents,
                                                       Clock clock,
                                                       TrackSampler trackSampler,
                                                       TrackBatchWriter trackBatchWriter,
                                                       SituationRevisionLog revisionLog,
                                                       IndoorPositionResolver indoorPositionResolver,
                                                       ObjectProvider<SituationPushPort> push) {
        return new SituationStateService(queue, hitEvents, clock, trackSampler, trackBatchWriter, revisionLog,
                indoorPositionResolver, push.getIfAvailable(NoopSituationPushPort::new));
    }

    /** 态势读侧门面：M4 的四个 Poll 端点与 WS 补齐都从这里取，不各自拼一遍。 */
    @Bean
    public SituationQuery situationQuery(SituationStateService state, HitEventRepository hitEvents) {
        return new SituationQuery(state, hitEvents);
    }

    /**
     * 线载荷组装器：把读侧原语拼成 {@code SituationEnvelope}，Poll 与 WS 共用同一份组装逻辑
     * （「同构」的落点）。落在 scds-app 而非 core，是因为它是纯编排对象、不持有状态也不必是
     * Spring 组件；core 侧生产环境对它的唯一需求是 {@code SituationPushPort} 推送时段（PUSH-WS）。
     */
    @Bean
    public SituationEnvelopeFactory situationEnvelopeFactory(SituationQuery query, Clock clock) {
        return new SituationEnvelopeFactory(query, clock);
    }

    /** 开演装载编成 / 停演写终局的编排；调用点属 LIFE（停演必须在 {@code freezeAndClose} 之前调）。 */
    @Bean
    public SituationSessionBinder situationSessionBinder(SituationStateService state,
                                                         PlanRosterRepository planRoster,
                                                         ExerciseUnitRepository exerciseUnits,
                                                         BaseMapRepository baseMaps,
                                                         Clock clock) {
        return new SituationSessionBinder(state, planRoster, exerciseUnits, baseMaps, clock);
    }

    /**
     * 在线扫描节拍：投递进**状态机自己的队列**，而不是直接改状态——扫描与上行帧争抢的是
     * 同一份内存态，绕开队列就等于又出现了一个状态所有者（设计 06 §4.2）。
     */
    @Bean
    public OfflineScanJob offlineScanJob(StateEventQueue queue, Clock clock) {
        return new OfflineScanJob(queue, clock);
    }

    /**
     * 扫描任务本体。{@code fixedDelay}（而非 {@code fixedRate}）保证上一拍没处理完时不会堆叠节拍。
     */
    public static final class OfflineScanJob {

        private final StateEventQueue queue;
        private final Clock clock;

        OfflineScanJob(StateEventQueue queue, Clock clock) {
            this.queue = queue;
            this.clock = clock;
        }

        @Scheduled(fixedDelayString = "${scds.state.offline-scan-interval-ms}")
        public void scan() {
            queue.offer(new StateEvent.OfflineScan(clock.instant()));
        }
    }

    /**
     * {@code scds.state.*} 的绑定与校验（设计 06 §4.7）。
     *
     * <p>校验放在绑定期：非法容量/周期会让**启动失败**，而不是让状态机带着一个静默的坏值跑起来
     * ——现场最怕的不是起不来，而是「起来了但离线判定与配置说的不一样」。</p>
     */
    @ConfigurationProperties("scds.state")
    public record SituationStateProperties(
            @DefaultValue("1024") int eventQueueCapacity,
            @DefaultValue("REJECT") String eventQueueOverflow,
            @DefaultValue("1000") long offlineScanIntervalMs,
            @DefaultValue("1000") long sampleIntervalMs,
            @DefaultValue("0.05") double sampleDisplacementIndoor,
            @DefaultValue("100") double sampleDisplacementOutdoor,
            @DefaultValue("500") int trackBatchSize,
            @DefaultValue("1000") long trackFlushIntervalMs,
            @DefaultValue("1000000") int trackMaxBuffered) {

        public SituationStateProperties {
            if (eventQueueCapacity <= 0) {
                throw new IllegalArgumentException(
                        "scds.state.event-queue-capacity 必须 > 0，实际 " + eventQueueCapacity);
            }
            if (offlineScanIntervalMs <= 0
                    || offlineScanIntervalMs >= ProtocolConstants.HEARTBEAT_ONLINE_WINDOW_MS) {
                throw new IllegalArgumentException("scds.state.offline-scan-interval-ms 必须落在 (0, "
                        + ProtocolConstants.HEARTBEAT_ONLINE_WINDOW_MS + ") ms："
                        + "扫描周期不小于 5000ms 的在线窗口时，离线跳变必然晚于窗口本身。实际 "
                        + offlineScanIntervalMs);
            }
            if (sampleIntervalMs <= 0) {
                throw new IllegalArgumentException("scds.state.sample-interval-ms 必须 > 0，实际 " + sampleIntervalMs);
            }
            if (sampleDisplacementIndoor < 0 || sampleDisplacementOutdoor < 0) {
                throw new IllegalArgumentException("scds.state.sample-displacement-* 不得为负：indoor="
                        + sampleDisplacementIndoor + ", outdoor=" + sampleDisplacementOutdoor);
            }
            if (trackBatchSize <= 0 || trackFlushIntervalMs <= 0) {
                throw new IllegalArgumentException("scds.state.track-batch-size / track-flush-interval-ms 必须 > 0："
                        + trackBatchSize + " / " + trackFlushIntervalMs);
            }
            if (trackMaxBuffered < trackBatchSize) {
                throw new IllegalArgumentException("scds.state.track-max-buffered 不得小于 track-batch-size："
                        + trackMaxBuffered + " < " + trackBatchSize);
            }
        }

        public StateEventQueue.OverflowPolicy overflowPolicy() {
            try {
                return StateEventQueue.OverflowPolicy.valueOf(eventQueueOverflow.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("scds.state.event-queue-overflow 只能是 REJECT 或 "
                        + "DROP_LOW_PRIORITY，实际 " + eventQueueOverflow, e);
            }
        }
    }
}
