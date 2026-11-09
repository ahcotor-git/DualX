package com.dualx.core.command;

import com.dualx.core.domain.ExerciseCommand;
import com.dualx.core.repository.ExerciseCommandRepository;
import com.dualx.core.state.SituationStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 指令的**链路级确认**：把「已发送」推进到「已确认」（{@code SENT → ACKED}）。
 *
 * <p><b>口径（本段裁定）</b>：协议 A <b>没有回执帧</b>（{@code PROTOCOL.md §6.3} 只实现 6 种上行帧），
 * 因此「设备执行成功」在这一层<b>不可观测、也不臆造</b>。本任务定义的 {@code ACKED} 是
 * <b>链路级确认</b>——指令下发（{@code sent_at}）之后，目标马甲在链路上<b>再次上报过心跳</b>
 * （{@code ff03} 轮询应答），即认为字节确已进入一条双向可用、且马甲仍在应答的链路。
 * 它<b>不等于</b>马甲已按指令执行；契约层面把它标注为「确认」时也必须带上这条限定。</p>
 *
 * <p><b>为什么这条判据成立</b>：后端每 200ms 向马甲下发一次轮询指令，在线马甲必回一帧 {@code ff03}
 * （协议 §1.4①）。所以「下发后收到该马甲心跳」= 「该马甲在本次下发前后仍在链路上应答」。
 * 与 {@link ExerciseCommandExpiryTask} 恰成互补：确认走 ACKED，彻底失联的那部分在超时窗后走 EXPIRED。
 * 在此之前没有任何路径写 {@code ACKED}，于是<b>每条指令都被超时扫描记成「无回音」</b>——本任务修掉这一误导。</p>
 *
 * <p><b>不确认的两类</b>：</p>
 * <ul>
 *   <li><b>广播指令</b>（{@code vest_num=0}，如全体充弹）：不指向任何单一马甲，链路级确认无从归属。
 *       刻意不用「任一马甲应答」充当「广播已送达」——那会把「链路可用」冒充成「广播被收到」，
 *       口径反而更弱。它们按超时窗走 {@code EXPIRED}。</li>
 *   <li><b>{@code sent_at} 缺失的行</b>：没有下发时刻就无从判定「之后」是否收到心跳，留给超时扫描。</li>
 * </ul>
 *
 * <p><b>为什么在 core 而不在网关</b>：网关按设计是哑管道（{@code HardwareEventSink} 约定「帧到业务事件
 * 的翻译一律留核心侧」），而「哪些指令待确认」是业务上下文、只有核心侧看得见。态势侧已经把「每马甲最近
 * 一次心跳」记在 {@link SituationStateService#vestLink(int)} 里，本任务只做「队列 × 心跳」的比对，
 * 不新增状态所有者。</p>
 *
 * <p><b>为什么不做成 {@code @Component}</b>：本类依赖 {@link SituationStateService}，而 core 的测试
 * 上下文（{@code TestCoreApplication}）里没有该 bean。与 {@link CommandService} 同一处置——普通类，
 * 装配点在 {@code scds-app} 的 {@code CommandConfig}；{@code @Scheduled} 对 {@code @Bean} 产出的对象
 * 同样生效（{@code ScdsNextApplication} 上开着 {@code @EnableScheduling}）。</p>
 */
public final class CommandAckTask {

    private static final Logger log = LoggerFactory.getLogger(CommandAckTask.class);

    /**
     * 扫描周期。取轮询周期（200ms）的量级之上、超时窗（{@code scds.command.sent-ttl}，默认 30s）之下：
     * 确认必须显著早于作废，否则一条「其实有应答」的指令会在被判 ACKED 之前先被判 EXPIRED。
     */
    private static final long PERIOD_MS = 1000;

    /** 单拍最多处理多少条，避免积压时把整个队列读进内存。 */
    private static final int BATCH_LIMIT = 200;

    /**
     * 广播指令的占位马甲编号，与 {@code CommandService.BROADCAST_VEST_NUM} 同源（真实编号下界为 1）。
     * 广播无单一马甲可确认，见类注释。
     */
    private static final int BROADCAST_VEST_NUM = 0;

    private final ExerciseCommandRepository commands;
    private final SituationStateService state;
    private final Clock clock;

    public CommandAckTask(ExerciseCommandRepository commands, SituationStateService state, Clock clock) {
        this.commands = commands;
        this.state = state;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = PERIOD_MS)
    @Transactional
    public void tick() {
        List<ExerciseCommand> inFlight = commands.findSent(BATCH_LIMIT);
        if (inFlight.isEmpty()) {
            // 无在途指令时只做这一次查询即返回，不写库——本任务常态下就是「没有可做的」
            return;
        }

        Instant now = clock.instant();
        int acked = 0;
        for (ExerciseCommand command : inFlight) {
            if (confirmed(command)) {
                // 影响 0 行不是故障：这一条刚被别的路径（超时作废 / 停演收尾）推进过
                acked += commands.markAcked(command.id(), now);
            }
        }

        if (acked > 0) {
            log.debug("指令链路级确认：本拍 {} 条 SENT → ACKED。", acked);
        }
    }

    /**
     * 该指令是否已获链路级确认：目标马甲在 {@code sent_at} 之后仍上报过心跳。
     * 广播（无单一马甲）与 {@code sent_at} 缺失的行恒为 {@code false}。
     */
    private boolean confirmed(ExerciseCommand command) {
        if (command.vestNum() == BROADCAST_VEST_NUM) {
            return false;
        }
        Instant sentAt = command.sentAt();
        if (sentAt == null) {
            return false;
        }
        Optional<SituationStateService.VestLink> link = state.vestLink(command.vestNum());
        return link.map(SituationStateService.VestLink::lastHeartbeatAt)
                .filter(last -> !last.isBefore(sentAt))
                .isPresent();
    }
}
