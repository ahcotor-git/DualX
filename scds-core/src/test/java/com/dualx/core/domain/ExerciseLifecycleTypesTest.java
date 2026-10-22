package com.dualx.core.domain;

import com.dualx.protocol.LogType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非协议日志类型与结束原因两个领域枚举（V10 / D05）。
 *
 * <p>这里守的是**冻结边界**：{@code scds-protocol.LogType} 是带 code 0~5 的协议帧类型，
 * 不能因为「日志需要记系统事件」就给它加第 7 个值。测试用两个方向钉住这条边界——
 * 协议侧取值集合不变，且 {@code SYSTEM} 既换不出协议类型，也不算来自协议。</p>
 */
class ExerciseLifecycleTypesTest {

    @Test
    @DisplayName("协议侧冻结：取值仍严格是 6 个帧类型")
    void protocolLogTypeStaysFrozen() {
        assertThat(Arrays.stream(LogType.values()).map(Enum::name))
                .containsExactly("START_BATTLE", "SHOT", "DIE", "LIVE", "INJURE", "RELOAD");
    }

    @Test
    @DisplayName("协议帧类型可双向映射，且名字与领域类型一一对应")
    void protocolValuesRoundTrip() {
        for (LogType protocol : LogType.values()) {
            ExerciseLogType domain = ExerciseLogType.of(protocol);

            assertThat(domain.name()).isEqualTo(protocol.name());
            assertThat(domain.protocol()).isEqualTo(protocol);
            assertThat(domain.fromProtocol()).isTrue();
        }
    }

    @Test
    @DisplayName("SYSTEM 只存在于领域侧：无协议对应帧，且不算来自协议")
    void systemHasNoProtocolCounterpart() {
        assertThat(ExerciseLogType.SYSTEM.fromProtocol()).isFalse();
        assertThatThrownBy(ExerciseLogType.SYSTEM::protocol)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SYSTEM");
    }

    @Test
    @DisplayName("领域日志类型比协议多且只多一个 SYSTEM")
    void domainLogTypeAddsExactlySystem() {
        assertThat(ExerciseLogType.values()).hasSize(LogType.values().length + 1);
        assertThat(ExerciseLogType.valueOf("SYSTEM")).isEqualTo(ExerciseLogType.SYSTEM);
    }

    @Test
    @DisplayName("结束原因：只有正常停演与自愈收尾两种，「进行中」不占枚举值")
    void endReasonHasExactlyTwoValues() {
        assertThat(Arrays.stream(ExerciseEndReason.values()).map(Enum::name))
                .containsExactly("NORMAL", "CRASH_RECOVERED");
    }
}
