package com.dualx.core.state;

import com.dualx.core.domain.CoordinateSystem;
import com.dualx.core.domain.GeoPoint;
import com.dualx.core.domain.MotionState;
import com.dualx.core.domain.PositionSource;
import com.dualx.core.domain.SampleReason;
import com.dualx.core.domain.TrackSample;
import com.dualx.core.push.UnitState;
import com.dualx.core.repository.TrackSampleRepository;
import com.dualx.protocol.VestStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4 单测：采样判定（{@link TrackSampler}）与批量落库缓冲（{@link TrackBatchWriter}）。
 *
 * <p>两个类都是纯 Java、无 Spring、无库依赖，故全部用可控入参（时间、时钟、仓储替身）断言，
 * 不引入 {@code @SpringBootTest}。</p>
 */
class TrackSamplingTest {

    private static final UUID EXERCISE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    // ---------------------------------------------------------------- 采样判定

    @Nested
    class Sampler {

        /** 定时 1000ms；室内位移阈值 0.05（归一化）；室外 100m。 */
        private final TrackSampler sampler = new TrackSampler(1000, 0.05, 100);

        @Test
        void eventIsAlwaysRecordedRegardlessOfChange() {
            UnitState u = unit(VestStatus.LIVE, MotionState.STATIONARY, indoor(0.1, 0.2));

            // 连续两个事件点之间状态完全相同，仍必须各记一行（EVENT 不受抑制）。
            TrackSample first = sampler.onEvent(EXERCISE, u, 0);
            TrackSample second = sampler.onEvent(EXERCISE, u, 1);

            assertThat(first.sampleReason()).isEqualTo(SampleReason.EVENT);
            assertThat(second.sampleReason()).isEqualTo(SampleReason.EVENT);
            assertThat(first.id()).isZero();
            assertThat(first.exerciseId()).isEqualTo(EXERCISE);
            assertThat(first.tMs()).isZero();
            assertThat(second.tMs()).isEqualTo(1);
        }

        @Test
        void sampleCarriesWholeView() {
            UnitState u = new UnitState(7, "甲", null, null, null, VestStatus.LIVE, 80, 12, 3,
                    indoor(0.25, 0.5), MotionState.MOVING, 6, PositionSource.UWB,
                    Instant.parse("2026-01-01T00:00:00Z"), null, 9L);

            TrackSample s = sampler.onEvent(EXERCISE, u, 250);

            assertThat(s.vestNum()).isEqualTo(7);
            assertThat(s.status()).isEqualTo(VestStatus.LIVE);
            assertThat(s.hp()).isEqualTo(80);
            assertThat(s.ammo1()).isEqualTo(12);
            assertThat(s.ammo2()).isEqualTo(3);
            assertThat(s.motion()).isEqualTo(MotionState.MOVING);
            assertThat(s.positionSource()).isEqualTo(PositionSource.UWB);
            assertThat(s.signalQuality()).isEqualTo(6);
            assertThat(s.position()).isEqualTo(u.position());
            assertThat(s.sampleReason()).isEqualTo(SampleReason.EVENT);
        }

        @Test
        void firstObservationIsStateChange() {
            Optional<TrackSample> s = sampler.onObservation(EXERCISE,
                    unit(VestStatus.LIVE, MotionState.MOVING, indoor(0.1, 0.2)), 0);

            assertThat(s).isPresent();
            assertThat(s.orElseThrow().sampleReason()).isEqualTo(SampleReason.STATE_CHANGE);
            assertThat(sampler.tracked(1)).isTrue();
        }

        @Test
        void eventRefreshesBaselineSoFollowingIdenticalObservationIsSuppressed() {
            UnitState u = unit(VestStatus.LIVE, MotionState.MOVING, indoor(0.1, 0.2));
            sampler.onEvent(EXERCISE, u, 0);

            assertThat(sampler.onObservation(EXERCISE, u, 1)).isEmpty();
        }

        @Test
        void statusChangeIsStateChange() {
            sampler.onObservation(EXERCISE, unit(VestStatus.LIVE, MotionState.MOVING, indoor(0.1, 0.2)), 0);

            Optional<TrackSample> s = sampler.onObservation(EXERCISE,
                    unit(VestStatus.OFFLINE, MotionState.MOVING, indoor(0.1, 0.2)), 1);

            assertThat(s).isPresent();
            assertThat(s.orElseThrow().sampleReason()).isEqualTo(SampleReason.STATE_CHANGE);
        }

        @Test
        void motionChangeIsStateChange() {
            sampler.onObservation(EXERCISE, unit(VestStatus.LIVE, MotionState.MOVING, indoor(0.1, 0.2)), 0);

            Optional<TrackSample> s = sampler.onObservation(EXERCISE,
                    unit(VestStatus.LIVE, MotionState.FROZEN, indoor(0.1, 0.2)), 1);

            assertThat(s).isPresent();
            assertThat(s.orElseThrow().sampleReason()).isEqualTo(SampleReason.STATE_CHANGE);
        }

        @Test
        void indoorDisplacementUsesNormalizedPlaneDistance() {
            sampler.onObservation(EXERCISE, unit(VestStatus.LIVE, MotionState.MOVING, indoor(0.1, 0.2)), 0);

            // 位移 0.04 < 0.05：不记点
            assertThat(sampler.onObservation(EXERCISE,
                    unit(VestStatus.LIVE, MotionState.MOVING, indoor(0.14, 0.2)), 1)).isEmpty();
            // 位移 0.06 ≥ 0.05：记 DISPLACEMENT
            Optional<TrackSample> s = sampler.onObservation(EXERCISE,
                    unit(VestStatus.LIVE, MotionState.MOVING, indoor(0.16, 0.2)), 2);

            assertThat(s).isPresent();
            assertThat(s.orElseThrow().sampleReason()).isEqualTo(SampleReason.DISPLACEMENT);
        }

        @Test
        void indoorDisplacementThresholdIsInclusive() {
            // 判定用 >=；取二进制可精确表示的 0.25 验证「恰达阈值也记点」（0.1→0.15 会因浮点误差不可靠）。
            TrackSampler exact = new TrackSampler(1000, 0.25, 100);
            exact.onObservation(EXERCISE, unit(VestStatus.LIVE, MotionState.MOVING, indoor(0.0, 0.0)), 0);

            Optional<TrackSample> s = exact.onObservation(EXERCISE,
                    unit(VestStatus.LIVE, MotionState.MOVING, indoor(0.25, 0.0)), 1);

            assertThat(s).isPresent();
            assertThat(s.orElseThrow().sampleReason()).isEqualTo(SampleReason.DISPLACEMENT);
        }

        @Test
        void outdoorDisplacementUsesWgs84Meters() {
            GeoPoint base = outdoor(116.397, 39.908);
            sampler.onObservation(EXERCISE, unit(VestStatus.LIVE, MotionState.MOVING, base), 0);

            // 纬度 +0.0001° ≈ 11m < 100m：不记点
            assertThat(sampler.onObservation(EXERCISE,
                    unit(VestStatus.LIVE, MotionState.MOVING, outdoor(116.397, 39.9081)), 1)).isEmpty();
            // 纬度 +0.01° ≈ 1112m ≥ 100m：记 DISPLACEMENT
            Optional<TrackSample> s = sampler.onObservation(EXERCISE,
                    unit(VestStatus.LIVE, MotionState.MOVING, outdoor(116.397, 39.918)), 2);

            assertThat(s).isPresent();
            assertThat(s.orElseThrow().sampleReason()).isEqualTo(SampleReason.DISPLACEMENT);
        }

        @Test
        void positionAppearingCountsAsDisplacement() {
            sampler.onObservation(EXERCISE, unit(VestStatus.LIVE, MotionState.MOVING, null), 0);

            Optional<TrackSample> s = sampler.onObservation(EXERCISE,
                    unit(VestStatus.LIVE, MotionState.MOVING, indoor(0.1, 0.2)), 1);

            assertThat(s).isPresent();
            assertThat(s.orElseThrow().sampleReason()).isEqualTo(SampleReason.DISPLACEMENT);
        }

        @Test
        void timerFiresOnlyAfterIntervalSinceLastSample() {
            UnitState u = unit(VestStatus.LIVE, MotionState.MOVING, indoor(0.1, 0.2));
            sampler.onObservation(EXERCISE, u, 0);

            // 未到周期不记点
            assertThat(sampler.onObservation(EXERCISE, u, 999)).isEmpty();
            Optional<TrackSample> s = sampler.onObservation(EXERCISE, u, 1000);
            assertThat(s).isPresent();
            assertThat(s.orElseThrow().sampleReason()).isEqualTo(SampleReason.TIMER);
            // 记点后基准前移，下一轮重新计时
            assertThat(sampler.onObservation(EXERCISE, u, 1500)).isEmpty();
        }

        @Test
        void resetDropsBaseline() {
            sampler.onEvent(EXERCISE, unit(VestStatus.LIVE, MotionState.MOVING, indoor(0.1, 0.2)), 0);
            sampler.reset();

            assertThat(sampler.tracked(1)).isFalse();
            Optional<TrackSample> s = sampler.onObservation(EXERCISE,
                    unit(VestStatus.LIVE, MotionState.MOVING, indoor(0.1, 0.2)), 0);
            assertThat(s).isPresent();
            assertThat(s.orElseThrow().sampleReason()).isEqualTo(SampleReason.STATE_CHANGE);
        }

        @Test
        void rejectsInvalidThresholds() {
            assertThatThrownBy(() -> new TrackSampler(0, 0.1, 10))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TrackSampler(1000, -0.1, 10))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TrackSampler(1000, 0.1, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---------------------------------------------------------------- 批量落库

    @Nested
    class Writer {

        @Test
        void bufferIsNotWrittenBelowBatchSize() {
            TrackSampleRepository repo = mock(TrackSampleRepository.class);
            TrackBatchWriter writer = writer(repo, 5, 1_000_000, 20, new AtomicLong(0));

            writer.add(sample(1, 0));
            writer.add(sample(2, 1));

            verify(repo, never()).insertAll(anyList());
            assertThat(writer.buffered()).isEqualTo(2);
            assertThat(writer.flushedRows()).isZero();
        }

        @Test
        void reachingBatchSizeWritesWholeBatchInOrder() {
            TrackSampleRepository repo = mock(TrackSampleRepository.class);
            when(repo.insertAll(anyList())).thenReturn(3);
            TrackBatchWriter writer = writer(repo, 3, 1_000_000, 20, new AtomicLong(0));

            writer.add(sample(1, 0));
            writer.add(sample(2, 1));
            writer.add(sample(3, 2));

            List<TrackSample> written = capture(repo, 1);
            assertThat(written).extracting(TrackSample::vestNum).containsExactly(1, 2, 3);
            assertThat(writer.buffered()).isZero();
            assertThat(writer.flushedRows()).isEqualTo(3);
        }

        @Test
        void flushIntervalWritesPartialBatch() {
            TrackSampleRepository repo = mock(TrackSampleRepository.class);
            when(repo.insertAll(anyList())).thenReturn(2);
            AtomicLong tick = new AtomicLong(0);
            TrackBatchWriter writer = writer(repo, 100, 500, 200, tick);

            writer.add(sample(1, 0));
            verify(repo, never()).insertAll(anyList());

            tick.set(500);
            writer.add(sample(2, 1));

            assertThat(capture(repo, 1)).hasSize(2);
            assertThat(writer.buffered()).isZero();
        }

        @Test
        void flushOnEmptyBufferIsNoop() {
            TrackSampleRepository repo = mock(TrackSampleRepository.class);
            TrackBatchWriter writer = writer(repo, 5, 1_000_000, 20, new AtomicLong(0));

            assertThat(writer.flush()).isZero();
            verify(repo, never()).insertAll(anyList());
        }

        @Test
        void failedFlushKeepsBufferAndRetries() {
            TrackSampleRepository repo = mock(TrackSampleRepository.class);
            when(repo.insertAll(anyList()))
                    .thenThrow(new IllegalStateException("库不可用"))
                    .thenReturn(3);
            TrackBatchWriter writer = writer(repo, 3, 1_000_000, 20, new AtomicLong(0));

            writer.add(sample(1, 0));
            writer.add(sample(2, 1));
            writer.add(sample(3, 2));

            assertThat(writer.failedFlushes()).isEqualTo(1);
            assertThat(writer.buffered()).isEqualTo(3);
            assertThat(writer.flushedRows()).isZero();

            // 下一帧到达即重试，失败的 3 条与新到的 1 条一并落库
            writer.add(sample(4, 3));

            assertThat(capture(repo, 2)).extracting(TrackSample::vestNum).containsExactly(1, 2, 3, 4);
            assertThat(writer.buffered()).isZero();
            assertThat(writer.flushedRows()).isEqualTo(3);
        }

        @Test
        void dropsOldestWhenBufferExceedsHardCap() {
            TrackSampleRepository repo = mock(TrackSampleRepository.class);
            when(repo.insertAll(anyList())).thenThrow(new IllegalStateException("库不可用"));
            TrackBatchWriter writer = writer(repo, 5, 1_000_000, 5, new AtomicLong(0));

            for (int i = 0; i < 6; i++) {
                writer.add(sample(10 + i, i));
            }

            assertThat(writer.buffered()).isEqualTo(5);
            assertThat(writer.droppedSamples()).isEqualTo(1);
        }

        @Test
        void forcedFlushWritesRemainder() {
            TrackSampleRepository repo = mock(TrackSampleRepository.class);
            when(repo.insertAll(anyList())).thenReturn(2);
            TrackBatchWriter writer = writer(repo, 10, 1_000_000, 50, new AtomicLong(0));

            writer.add(sample(1, 0));
            writer.add(sample(2, 1));

            assertThat(writer.flush()).isEqualTo(2);
            assertThat(capture(repo, 1)).extracting(TrackSample::vestNum).containsExactly(1, 2);
            assertThat(writer.buffered()).isZero();
        }

        @Test
        void rejectsInvalidSizing() {
            TrackSampleRepository repo = mock(TrackSampleRepository.class);
            assertThatThrownBy(() -> writer(repo, 0, 100, 10, new AtomicLong(0)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> writer(repo, 5, 0, 10, new AtomicLong(0)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> writer(repo, 10, 100, 5, new AtomicLong(0)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---------------------------------------------------------------- 测试夹具

    @SuppressWarnings("unchecked")
    private static List<TrackSample> capture(TrackSampleRepository repo, int expectedInvocations) {
        ArgumentCaptor<List<TrackSample>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo, times(expectedInvocations)).insertAll(captor.capture());
        return captor.getValue();
    }

    private static TrackBatchWriter writer(TrackSampleRepository repo, int batchSize, long intervalMs,
                                           int maxBuffered, AtomicLong tick) {
        return new TrackBatchWriter(repo, batchSize, intervalMs, maxBuffered, tick::get);
    }

    private static TrackSample sample(int vestNum, int tMs) {
        return new TrackSample(0L, EXERCISE, vestNum, tMs, VestStatus.LIVE, 100, 30, 30,
                MotionState.MOVING, PositionSource.NONE, null, null, SampleReason.TIMER);
    }

    private static UnitState unit(VestStatus status, MotionState motion, GeoPoint position) {
        return new UnitState(1, null, null, null, null, status, 100, 30, 30, position, motion, null,
                PositionSource.NONE, null, null, 0L);
    }

    private static GeoPoint indoor(double x, double y) {
        return new GeoPoint(CoordinateSystem.INDOOR, x, y, 1, 3, null, null, null, null);
    }

    private static GeoPoint outdoor(double lng, double lat) {
        return new GeoPoint(CoordinateSystem.OUTDOOR, null, null, null, null, null, null, lng, lat);
    }
}
