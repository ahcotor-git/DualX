package com.dualx.core.state;

import com.dualx.core.push.UnitState;
import com.dualx.protocol.VestStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S5 单测：增量环形窗口（{@link SituationRevisionLog}）。纯单测，不起线程、不依赖 Spring。
 *
 * <p>重点在<b>超窗边界</b>与 revision 的<b>稀疏</b>性——revision 只在单元变化时递增，
 * 因此「窗口里最旧的 revision」不等于「已淘汰的最新 revision」，二者混用会误判超窗。</p>
 */
class SituationRevisionLogTest {

    @Test
    void emptyLogHasNoChangesAndZeroRevision() {
        SituationRevisionLog log = new SituationRevisionLog(4);

        assertThat(log.latestRevision()).isZero();
        assertThat(log.oldestRetainedRevision()).isZero();
        assertThat(log.lastEvictedRevision()).isZero();
        assertThat(log.size()).isZero();
        // 空窗口不能判任何 since 为超窗，否则新连接会被无理由拒绝
        assertThat(log.tooOld(0)).isFalse();
        assertThat(log.changesSince(0)).contains(List.of());
    }

    @Test
    void changesSinceReturnsOnlyNewerInRevisionOrder() {
        SituationRevisionLog log = new SituationRevisionLog(8);
        log.record(view(1, 3));
        log.record(view(2, 5));
        log.record(view(3, 9));

        assertThat(log.latestRevision()).isEqualTo(9);
        assertThat(log.changesSince(5).orElseThrow())
                .extracting(UnitState::vestNum).containsExactly(3);
        assertThat(log.changesSince(2).orElseThrow())
                .extracting(UnitState::vestNum).containsExactly(1, 2, 3);
    }

    @Test
    void changesSinceLatestRevisionYieldsEmptyListNotTooOld() {
        SituationRevisionLog log = new SituationRevisionLog(4);
        log.record(view(1, 7));

        // 「已是最新」与「超窗」必须可区分：前者是空列表，后者是空 Optional
        assertThat(log.changesSince(7)).contains(List.of());
        assertThat(log.tooOld(7)).isFalse();
    }

    @Test
    void evictionIsBoundedAndSetsTooOldBoundary() {
        SituationRevisionLog log = new SituationRevisionLog(3);
        for (int revision = 1; revision <= 5; revision++) {
            log.record(view(revision, revision));
        }

        assertThat(log.size()).isEqualTo(3);
        assertThat(log.evictedCount()).isEqualTo(2);
        assertThat(log.oldestRetainedRevision()).isEqualTo(3);
        assertThat(log.lastEvictedRevision()).isEqualTo(2);

        assertThat(log.changesSince(2).orElseThrow())
                .extracting(UnitState::revision).containsExactly(3L, 4L, 5L);
        // since 恰为已淘汰的最新 revision：它要的正是 >2 的部分，没有漏，故不算超窗
        assertThat(log.tooOld(2)).isFalse();
        assertThat(log.tooOld(1)).isTrue();
        assertThat(log.changesSince(1)).isEmpty();
    }

    @Test
    void sparseRevisionsDoNotFalselyReportTooOld() {
        // revision 只在单元变化时递增，窗口内最旧的条目可能远大于 since
        SituationRevisionLog log = new SituationRevisionLog(2);
        log.record(view(1, 3));
        log.record(view(2, 10));
        log.record(view(3, 11));

        assertThat(log.lastEvictedRevision()).isEqualTo(3);
        assertThat(log.tooOld(3)).isFalse();
        assertThat(log.changesSince(3).orElseThrow())
                .extracting(UnitState::revision).containsExactly(10L, 11L);
        // 需要 revision 3 而它已被淘汰
        assertThat(log.tooOld(2)).isTrue();
    }

    @Test
    void rebaseDropsWindowAndRaisesLowerBoundToSwitchRevision() {
        SituationRevisionLog log = new SituationRevisionLog(2);
        log.record(view(1, 1));
        log.record(view(2, 2));
        log.record(view(3, 3));

        // 切换点：清窗并把下界抬到 7（演习切换当次递增后的 revision）
        log.rebase(7);

        assertThat(log.size()).isZero();
        assertThat(log.latestRevision()).isZero();
        assertThat(log.oldestRetainedRevision()).isZero();
        assertThat(log.lastEvictedRevision()).isEqualTo(7);
        assertThat(log.evictedCount()).isZero();
        // 早于切换点的一律超窗 → 客户端改全量；恰为切换点表示「已是最新」
        assertThat(log.tooOld(6)).isTrue();
        assertThat(log.changesSince(6)).isEmpty();
        assertThat(log.tooOld(7)).isFalse();
        assertThat(log.changesSince(7)).contains(List.of());
    }

    @Test
    void rebaseThenNewChangesAreRetrievableFromSwitchPoint() {
        SituationRevisionLog log = new SituationRevisionLog(8);
        log.record(view(1, 3));
        log.rebase(4);          // 开演/停演切换点
        log.record(view(2, 5)); // 切换后的首个变化

        // 客户端持有切换点：只补到切换后的变化
        assertThat(log.changesSince(4).orElseThrow())
                .extracting(UnitState::vestNum).containsExactly(2);
        // 持有切换前的 revision：超窗（下界已被抬到切换点）
        assertThat(log.tooOld(3)).isTrue();
    }

    @Test
    void windowOfOneKeepsOnlyLatest() {
        SituationRevisionLog log = new SituationRevisionLog(1);
        log.record(view(1, 4));
        log.record(view(2, 6));

        assertThat(log.size()).isEqualTo(1);
        assertThat(log.changesSince(4).orElseThrow())
                .extracting(UnitState::vestNum).containsExactly(2);
        assertThat(log.tooOld(4)).isFalse();
        assertThat(log.tooOld(3)).isTrue();
    }

    @Test
    void rejectsNonPositiveWindow() {
        assertThatThrownBy(() -> new SituationRevisionLog(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SituationRevisionLog(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultWindowMatchesDesignValue() {
        // 设计 02 §5 与设计 06 §4.7 均定该键默认 1024（此处只钉住常量，真值由装配层绑定）
        assertThat(SituationRevisionLog.DEFAULT_WINDOW).isEqualTo(1024);
        assertThat(new SituationRevisionLog(SituationRevisionLog.DEFAULT_WINDOW).window()).isEqualTo(1024);
    }

    private static UnitState view(int vestNum, long revision) {
        return new UnitState(vestNum, null, null, null, null, VestStatus.LIVE, 100, 30, 30,
                null, null, null, null, null, null, revision);
    }
}
