package com.dualx.core.repository;

import com.dualx.core.domain.CoordinateSystem;
import com.dualx.core.domain.GeoPoint;
import com.dualx.core.domain.MotionState;
import com.dualx.core.domain.PositionSource;
import com.dualx.core.domain.SampleReason;
import com.dualx.core.domain.TrackSample;
import com.dualx.core.support.UuidV7;
import com.dualx.protocol.VestStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class TrackSampleRepositoryIT {

    @Autowired
    private TrackSampleRepository tracks;

    @Autowired
    private JdbcClient jdbc;

    private static GeoPoint indoor() {
        return new GeoPoint(CoordinateSystem.INDOOR, 0.1, 0.2, 1, 5, null, null, null, null);
    }

    private static TrackSample sample(UUID exerciseId, int vestNum, int tMs, SampleReason reason) {
        return new TrackSample(0L, exerciseId, vestNum, tMs, VestStatus.LIVE, 100, 30, 12,
                MotionState.MOVING, PositionSource.UWB, 4, indoor(), reason);
    }

    /** 按 tick 交错插入多人，还原真实上行帧的写入顺序。 */
    private static List<TrackSample> interleaved(UUID exerciseId, List<Integer> vestNums, int ticks) {
        List<TrackSample> batch = new ArrayList<>();
        for (int tick = 0; tick < ticks; tick++) {
            for (int vestNum : vestNums) {
                batch.add(sample(exerciseId, vestNum, (tick + 1) * 100, SampleReason.TIMER));
            }
        }
        return batch;
    }

    @Test
    void insertAll_roundTripsAllColumns() {
        UUID exerciseId = UuidV7.generate();
        assertThat(tracks.insertAll(List.of(sample(exerciseId, 1001, 1200, SampleReason.DISPLACEMENT))))
                .isEqualTo(1);

        TrackSample found = tracks.findChunk(exerciseId, 0L, null, 1, 10).getFirst();
        assertThat(found.id()).isPositive();
        assertThat(found.exerciseId()).isEqualTo(exerciseId);
        assertThat(found.vestNum()).isEqualTo(1001);
        assertThat(found.tMs()).isEqualTo(1200);
        assertThat(found.status()).isEqualTo(VestStatus.LIVE);
        assertThat(found.hp()).isEqualTo(100);
        assertThat(found.ammo1()).isEqualTo(30);
        assertThat(found.ammo2()).isEqualTo(12);
        assertThat(found.motion()).isEqualTo(MotionState.MOVING);
        assertThat(found.positionSource()).isEqualTo(PositionSource.UWB);
        assertThat(found.signalQuality()).isEqualTo(4);
        assertThat(found.position()).isEqualTo(indoor());
        assertThat(found.sampleReason()).isEqualTo(SampleReason.DISPLACEMENT);
    }

    @Test
    void insertAll_emptyList_writesNothing() {
        assertThat(tracks.insertAll(List.of())).isZero();
    }

    @Test
    void insertAll_writesAllRowsOfBatch() {
        UUID exerciseId = UuidV7.generate();
        assertThat(tracks.insertAll(interleaved(exerciseId, List.of(1001, 1002), 3))).isEqualTo(6);
        assertThat(tracks.countByExerciseId(exerciseId)).isEqualTo(6);
    }

    @Test
    void insertAll_nullableColumns_roundTripAsNull() {
        UUID exerciseId = UuidV7.generate();
        tracks.insertAll(List.of(new TrackSample(0L, exerciseId, 1001, 100, VestStatus.OFFLINE,
                null, null, null, null, null, null, null, SampleReason.STATE_CHANGE)));

        TrackSample found = tracks.findChunk(exerciseId, 0L, null, 1, 10).getFirst();
        assertThat(found.hp()).isNull();
        assertThat(found.ammo1()).isNull();
        assertThat(found.motion()).isNull();
        assertThat(found.positionSource()).isNull();
        assertThat(found.signalQuality()).isNull();
        assertThat(found.position()).isNull();
    }

    @Test
    void findChunk_pagesByCursorWithoutOverlapOrGap() {
        UUID exerciseId = UuidV7.generate();
        tracks.insertAll(interleaved(exerciseId, List.of(1001, 1002), 5));

        List<Long> seen = new ArrayList<>();
        long cursor = 0L;
        while (true) {
            List<TrackSample> chunk = tracks.findChunk(exerciseId, cursor, null, 1, 4);
            if (chunk.isEmpty()) {
                break;
            }
            chunk.forEach(s -> seen.add(s.id()));
            cursor = chunk.getLast().id();
            assertThat(chunk).hasSizeLessThanOrEqualTo(4);
        }

        assertThat(seen).hasSize(10).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void findChunk_filtersByVestNums() {
        UUID exerciseId = UuidV7.generate();
        tracks.insertAll(interleaved(exerciseId, List.of(1001, 1002, 1003), 2));

        List<TrackSample> onlyTwo = tracks.findChunk(exerciseId, 0L, List.of(1001, 1003), 1, 50);
        assertThat(onlyTwo).hasSize(4);
        assertThat(onlyTwo).extracting(TrackSample::vestNum).containsOnly(1001, 1003);

        assertThat(tracks.findChunk(exerciseId, 0L, List.of(), 1, 50)).hasSize(6);
    }

    @Test
    void findChunk_isScopedToExercise() {
        UUID mine = UuidV7.generate();
        UUID other = UuidV7.generate();
        tracks.insertAll(interleaved(mine, List.of(1001), 2));
        tracks.insertAll(interleaved(other, List.of(1001), 3));

        assertThat(tracks.findChunk(mine, 0L, null, 1, 50)).hasSize(2);
        assertThat(tracks.countByExerciseId(other)).isEqualTo(3);
    }

    @Test
    void findChunk_strideThinsEachPersonIndependently() {
        UUID exerciseId = UuidV7.generate();
        tracks.insertAll(interleaved(exerciseId, List.of(1001, 1002), 6));

        List<TrackSample> thinned = tracks.findChunk(exerciseId, 0L, null, 2, 50);

        // 每人各自按插入序编号，取编号为 2 / 4 / 6 的点 => tMs = 200 / 400 / 600
        assertThat(thinned).hasSize(6);
        assertThat(thinned).extracting(TrackSample::vestNum).containsOnly(1001, 1002);
        assertThat(thinned).extracting(TrackSample::tMs).containsOnly(200, 400, 600);
    }

    @Test
    void findChunk_stridedPaging_keepsPhaseAcrossChunks() {
        UUID exerciseId = UuidV7.generate();
        tracks.insertAll(interleaved(exerciseId, List.of(1001, 1002), 9));

        List<TrackSample> whole = tracks.findChunk(exerciseId, 0L, null, 3, 100);

        List<TrackSample> paged = new ArrayList<>();
        long cursor = 0L;
        while (true) {
            List<TrackSample> chunk = tracks.findChunk(exerciseId, cursor, null, 3, 2);
            if (chunk.isEmpty()) {
                break;
            }
            paged.addAll(chunk);
            cursor = chunk.getLast().id();
        }

        // 抽稀相位若逐片漂移，就会出现重复或漏点；分片结果必须与一次性拉取逐条相等
        assertThat(paged).extracting(TrackSample::id).isEqualTo(whole.stream().map(TrackSample::id).toList());
        assertThat(whole).hasSize(6);
    }

    @Test
    void deleteByExerciseId_removesOnlyThatExercise() {
        UUID mine = UuidV7.generate();
        UUID other = UuidV7.generate();
        tracks.insertAll(interleaved(mine, List.of(1001), 2));
        tracks.insertAll(interleaved(other, List.of(1002), 2));

        assertThat(tracks.deleteByExerciseId(mine)).isEqualTo(2);
        assertThat(tracks.countByExerciseId(mine)).isZero();
        assertThat(tracks.countByExerciseId(other)).isEqualTo(2);
    }

    @Test
    void sampleReasonCheck_rejectsOutOfRangeValue() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO t_track_sample (exercise_id, vest_num, t_ms, status, sample_reason)
                        VALUES (:exerciseId, 1001, 100, 'LIVE', 4)
                        """)
                .param("exerciseId", UuidV7.generate().toString())
                .update())
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_track_sample_reason");
    }

    @Test
    void statusCheck_rejectsDirtyValueWrittenAroundTheRepository() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO t_track_sample (exercise_id, vest_num, t_ms, status, sample_reason)
                        VALUES (:exerciseId, 1001, 100, 'ALIVE', 0)
                        """)
                .param("exerciseId", UuidV7.generate().toString())
                .update())
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_track_status");
    }

    @Test
    void positionSourceCheck_rejectsDirtyValueWrittenAroundTheRepository() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO t_track_sample (exercise_id, vest_num, t_ms, status, position_source,
                                                    sample_reason)
                        VALUES (:exerciseId, 1001, 100, 'LIVE', 'WIFI', 0)
                        """)
                .param("exerciseId", UuidV7.generate().toString())
                .update())
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_track_position_source");
    }
}
