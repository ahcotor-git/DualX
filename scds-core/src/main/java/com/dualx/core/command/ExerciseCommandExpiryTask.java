package com.dualx.core.command;

import com.dualx.core.domain.ExerciseCommand;
import com.dualx.core.domain.ExerciseCommandState;
import com.dualx.core.domain.ExerciseLogEntry;
import com.dualx.core.domain.ExerciseLogType;
import com.dualx.core.repository.ExerciseCommandRepository;
import com.dualx.core.repository.ExerciseLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 指令队列过期扫描：把「已下发、但超过时限仍未拿到回执」的指令判为 {@code EXPIRED}
 * ——设计 01 §4.4 第 2 步的运行期版本（那一步只覆盖「启动时」）。
 *
 * <p><b>为什么需要它</b>：{@code t_exercise_command} 是「已发未确认」的工作队列，{@code SENT} 行的
 * 含义是「字节发出去了，但还不知道马甲收没收到」。没有这一步，这些行会一直挂在队列里直到停演，
 * 期间的「在途」既不收敛也不可解释——操作台看到的是一条永不结束的指令。
 *
 * <p><b>为什么只扫 {@code SENT}</b>：{@code CommandService} 走的是「先发字节、成功后一次性落
 * {@code SENT}」，本实现下不会产生 {@code PENDING} 行；{@code PENDING} 是「还没发出去、等重连」
 * 的派发语义，属尚未接通的设计，这里不为它造路径。{@code markFinished} 的守卫本就接受
 * {@code PENDING / SENT}，将来派发接通后这里的方法签名无需改动。
 *
 * <p><b>顺序：先写 SYSTEM 存证 → 再作废 → 再清理终态行</b>。与收尾路径同源的理由：队列表不保留
 * 历史，永久证据只有 {@code t_exercise_log}，先删后记一旦失败那些字节就永久消失。把存证放在最前，
 * 即便进程崩在中间也只多出一条日志（下一拍会把仍为 {@code SENT} 的行再处理一次），不会丢证据。
 * 方法同时标了 {@link Transactional}，正常路径下三者同一事务，不必依赖崩溃语义。
 *
 * <p><b>何时触发</b>：{@code @EnableScheduling} 只在唯一入口 {@code ScdsNextApplication} 上开启，
 * 故本任务在应用内自动运行、在 {@code scds-core} 的测试上下文里不会自动跑（测试直接调 {@link #tick()}），
 * 与 {@code ExerciseHeartbeatTask} 同一约定。
 */
@Component
@EnableConfigurationProperties(ExerciseCommandExpiryTask.CommandProperties.class)
public class ExerciseCommandExpiryTask {

    private static final Logger log = LoggerFactory.getLogger(ExerciseCommandExpiryTask.class);

    /** 扫描周期。取超时窗的 1/6 量级，判定误差不超过一拍；不随 TTL 配置联动，避免两个旋钮互相牵制。 */
    private static final long PERIOD_MS = 5000;

    /** 单拍最多处理多少条，避免积压时把整个队列读进内存。 */
    private static final int BATCH_LIMIT = 200;

    /**
     * 作废原因，写进 {@code t_exercise_command.fail_reason} 与 SYSTEM 存证。
     *
     * <p>与自愈收尾的 {@code INSTANCE_RESTART} 并列：现场据此区分「进程重启丢的」与「发下去没有回音」。
     */
    public static final String REASON_SENT_TIMEOUT = "SENT_TIMEOUT";

    private final ExerciseCommandRepository commands;
    private final ExerciseLogRepository logs;
    private final CommandProperties properties;

    public ExerciseCommandExpiryTask(ExerciseCommandRepository commands, ExerciseLogRepository logs,
                                     CommandProperties properties) {
        this.commands = commands;
        this.logs = logs;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = PERIOD_MS)
    @Transactional
    public void tick() {
        Instant now = Instant.now();
        List<ExerciseCommand> overdue = commands.findSentBefore(now.minus(properties.sentTtl()), BATCH_LIMIT);
        if (overdue.isEmpty()) {
            // 无逾期指令时只做这一次查询即返回，不写库——本任务常态下就是「没有可做的」
            return;
        }

        int expired = 0;
        for (ExerciseCommand command : overdue) {
            logs.append(new ExerciseLogEntry(0L, command.exerciseId(), now, ExerciseLogType.SYSTEM,
                    command.vestNum(), null, evidence(command), command.downlinkHex()));
            // 影响 0 行不是故障：这一条刚被别的路径（如停演收尾）推进过，它的证据由那条路径负责
            expired += commands.markFinished(command.id(), ExerciseCommandState.EXPIRED, now, REASON_SENT_TIMEOUT);
        }

        int deleted = commands.deleteFinished();
        log.warn("指令过期扫描：判定 {} 条为 {}（超时 {}），清理终态行 {} 条。",
                expired, REASON_SENT_TIMEOUT, properties.sentTtl(), deleted);
    }

    /**
     * 一条超时指令的存证描述。要能脱离队列表独立读懂：队列行随后就被清理了，
     * 复盘时只剩这条日志，故把队列主键、指令类型与下发时刻一并写进 detail。
     */
    private static String evidence(ExerciseCommand command) {
        return "超时指令已作废：id=" + command.id() + " " + command.command()
                + " 马甲" + command.vestNum() + " 原状态" + command.state().name()
                + " 原因" + REASON_SENT_TIMEOUT + " 下发于" + command.sentAt();
    }

    /**
     * {@code scds.command.*} 的绑定与校验。
     *
     * <p>校验放在绑定期：非法值让**启动失败**，而不是让扫描带着一个静默的坏值跑起来——
     * 零或负的超时窗会把每一条刚发出的指令在下一拍就判死，且现场从配置上看不出异常。
     */
    @ConfigurationProperties("scds.command")
    public record CommandProperties(@DefaultValue("30s") Duration sentTtl) {

        public CommandProperties {
            if (sentTtl == null || sentTtl.isZero() || sentTtl.isNegative()) {
                throw new IllegalArgumentException("scds.command.sent-ttl 必须为正的时长，实际 " + sentTtl);
            }
        }
    }
}
