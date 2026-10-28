package com.dualx.core.exercise;

import com.dualx.core.domain.BattleMode;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.core.repository.ExerciseCommandRepository;
import com.dualx.core.repository.ExerciseLogRepository;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.repository.ExerciseUnitRepository;
import com.dualx.core.repository.HitEventRepository;
import com.dualx.core.repository.PlanRepository;
import com.dualx.core.repository.PlanRosterRepository;
import com.dualx.core.state.SituationSessionBinder;
import com.dualx.core.support.InstanceProperties;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 恢复完成门禁（设计 01 §4.4、§7 判据 6）的**负向面**：自愈未跑完时开演必须被拒。
 *
 * <p><b>为什么不写成 {@code @SpringBootTest}</b>：就绪位是一次性的，而测试上下文在启动那一刻
 * 就跑完了 {@code ExerciseLivenessRecover}——上下文里那个 bean 永远是「已就绪」，
 * 拒绝路径在真上下文里根本走不到。这里直接 new 一个从未收到
 * {@link ExerciseLifecycleService#markRecovered()} 的实例，正是「进程已起、自愈未跑完」那一刻。</p>
 *
 * <p><b>正向面由谁覆盖</b>：{@link ExerciseLifecycleServiceIT} 的用例全跑在真实上下文里，
 * 它们能开演成功，本身就是「门禁已在启动后放行」的证据，故此处不重复。</p>
 */
class ExerciseLifecycleServiceReadinessTest {

    private final ExerciseRepository exercises = mock(ExerciseRepository.class);
    private final ExerciseUnitRepository units = mock(ExerciseUnitRepository.class);
    private final PlanRepository plans = mock(PlanRepository.class);
    private final PlanRosterRepository planRoster = mock(PlanRosterRepository.class);
    private final SituationSessionBinder sessions = mock(SituationSessionBinder.class);
    private final DeviceLinkAdmin links = mock(DeviceLinkAdmin.class);

    /** 全新实例：就绪位初值 false，等价于「启动自愈还没跑完」。 */
    private ExerciseLifecycleService notYetRecovered() {
        return new ExerciseLifecycleService(exercises, units, mock(HitEventRepository.class),
                mock(ExerciseCommandRepository.class), mock(ExerciseLogRepository.class), plans,
                planRoster, sessions, links, new InstanceProperties(null));
    }

    @Test
    void startExerciseIsRejectedWith503BeforeAnyLookupWhileRecoveryIsPending() {
        ExerciseLifecycleService lifecycle = notYetRecovered();

        assertThatThrownBy(() -> lifecycle.startExercise(UuidV7.generate(), BattleMode.TRAIN))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.INSTANCE_NOT_READY));

        // 门禁在查库之前：自愈没跑完时上一场的残留行还在，此刻报「想定不存在」只会误导现场，
        // 说好的 503（稍后重试）才是这一瞬间唯一正确的答复。
        verifyNoInteractions(plans, planRoster, exercises, units, sessions, links);
    }
}
