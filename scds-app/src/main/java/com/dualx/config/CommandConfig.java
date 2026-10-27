package com.dualx.config;

import com.dualx.core.command.CommandService;
import com.dualx.core.exercise.ExerciseLifecycleService;
import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.core.repository.AccountRepository;
import com.dualx.core.repository.ExerciseCommandRepository;
import com.dualx.core.repository.ExerciseLogRepository;
import com.dualx.core.repository.PlanRosterRepository;
import com.dualx.core.state.SituationQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 指挥指令下发的装配层（M3 COMMAND）。
 *
 * <p><b>为什么在 scds-app</b>：{@link CommandService} 要同时看见「端口」（{@link DeviceLinkAdmin}）
 * 与「实现」，还要落库（三个仓储）。core 的测试上下文（{@code TestCoreApplication}）不扫描本模块，
 * 因此服务本身是普通类、只在这里出 bean——与 {@code SituationQuery} /
 * {@code SituationEnvelopeFactory} 同一处置（见 {@code SituationStateConfig} 的同名说明）。</p>
 */
@Configuration(proxyBeanMethods = false)
public class CommandConfig {

    @Bean
    public CommandService commandService(ExerciseLifecycleService lifecycle,
                                         SituationQuery situations,
                                         PlanRosterRepository planRoster,
                                         DeviceLinkAdmin deviceLinkAdmin,
                                         ExerciseCommandRepository commands,
                                         ExerciseLogRepository logs,
                                         AccountRepository accounts,
                                         Clock clock) {
        return new CommandService(lifecycle, situations, planRoster, deviceLinkAdmin, commands, logs,
                accounts, clock);
    }
}
