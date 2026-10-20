package com.dualx.core.repository;

import com.dualx.core.domain.AsyncTaskState;
import com.dualx.core.domain.ExportFormat;
import com.dualx.core.domain.ExportTask;
import com.dualx.core.support.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ExportTaskRepositoryIT {

    @Autowired
    private ExportTaskRepository exports;

    private static final Instant EXPIRE = Instant.parse("2026-10-27T08:00:00.000Z");

    /** {@code replayId} 即 {@code t_exercise.id}；建任务不需要演习行存在（无外键）。 */
    private static ExportTask pending(UUID replayId, ExportFormat format) {
        return new ExportTask(UuidV7.generate(), replayId, format, AsyncTaskState.PENDING, 0,
                null, null, null, null, null, null);
    }

    private static UUID newPending(ExportTaskRepository exports, ExportFormat format) {
        ExportTask task = pending(UuidV7.generate(), format);
        exports.insert(task);
        return task.id();
    }

    @Test
    void insert_startsAsPendingWithZeroProgress() {
        UUID replayId = UuidV7.generate();
        ExportTask task = pending(replayId, ExportFormat.XLSX);
        assertThat(exports.insert(task)).isEqualTo(1);

        ExportTask found = exports.findById(task.id()).orElseThrow();
        assertThat(found.replayId()).isEqualTo(replayId);
        assertThat(found.format()).isEqualTo(ExportFormat.XLSX);
        assertThat(found.state()).isEqualTo(AsyncTaskState.PENDING);
        assertThat(found.progressPct()).isZero();
        assertThat(found.fileName()).isNull();
        assertThat(found.filePath()).isNull();
        assertThat(found.errorMessage()).isNull();
        assertThat(found.expireAt()).isNull();
        assertThat(found.createdAt()).isNotNull();
        assertThat(found.updatedAt()).isNotNull();
    }

    @Test
    void findById_unknown_returnsEmpty() {
        assertThat(exports.findById(UuidV7.generate())).isEmpty();
    }

    @Test
    void markRunning_movesPendingToRunning() {
        UUID id = newPending(exports, ExportFormat.CSV);

        assertThat(exports.markRunning(id)).isEqualTo(1);
        assertThat(exports.findById(id).orElseThrow().state()).isEqualTo(AsyncTaskState.RUNNING);
    }

    @Test
    void markRunning_whenAlreadyRunning_affectsNoRow() {
        UUID id = newPending(exports, ExportFormat.CSV);
        assertThat(exports.markRunning(id)).isEqualTo(1);

        assertThat(exports.markRunning(id)).isZero();
    }

    @Test
    void updateProgress_whileActive_keepsStateUnchanged() {
        UUID id = newPending(exports, ExportFormat.XLSX);
        assertThat(exports.updateProgress(id, 40)).isEqualTo(1);

        ExportTask found = exports.findById(id).orElseThrow();
        assertThat(found.progressPct()).isEqualTo(40);
        assertThat(found.state()).isEqualTo(AsyncTaskState.PENDING);

        assertThat(exports.markRunning(id)).isEqualTo(1);
        assertThat(exports.updateProgress(id, 70)).isEqualTo(1);
        assertThat(exports.findById(id).orElseThrow().progressPct()).isEqualTo(70);
    }

    @Test
    void markSucceeded_recordsArtifactAndFullProgress() {
        UUID id = newPending(exports, ExportFormat.XLSX);
        exports.markRunning(id);

        assertThat(exports.markSucceeded(id, "复盘-城市攻防.xlsx", "export/2026/10/a.xlsx", EXPIRE))
                .isEqualTo(1);

        ExportTask found = exports.findById(id).orElseThrow();
        assertThat(found.state()).isEqualTo(AsyncTaskState.SUCCEEDED);
        assertThat(found.progressPct()).isEqualTo(100);
        assertThat(found.fileName()).isEqualTo("复盘-城市攻防.xlsx");
        assertThat(found.filePath()).isEqualTo("export/2026/10/a.xlsx");
        assertThat(found.expireAt()).isEqualTo(EXPIRE);
    }

    @Test
    void markSucceeded_whenAlreadySucceeded_affectsNoRow() {
        UUID id = newPending(exports, ExportFormat.CSV);
        assertThat(exports.markSucceeded(id, "b.csv", "export/b.csv", EXPIRE)).isEqualTo(1);

        assertThat(exports.markSucceeded(id, "c.csv", "export/c.csv", EXPIRE)).isZero();
        assertThat(exports.markFailed(id, "晚了")).isZero();
        assertThat(exports.updateProgress(id, 50)).isZero();
        assertThat(exports.findById(id).orElseThrow().fileName()).isEqualTo("b.csv");
    }

    @Test
    void markFailed_recordsReason() {
        UUID id = newPending(exports, ExportFormat.CSV);
        exports.markRunning(id);

        assertThat(exports.markFailed(id, "轨迹表过大")).isEqualTo(1);

        ExportTask found = exports.findById(id).orElseThrow();
        assertThat(found.state()).isEqualTo(AsyncTaskState.FAILED);
        assertThat(found.errorMessage()).isEqualTo("轨迹表过大");
        assertThat(exports.markRunning(id)).isZero();
    }

    @Test
    void findActive_matchesPendingAndRunningOnly() {
        UUID replayId = UuidV7.generate();
        ExportTask task = pending(replayId, ExportFormat.XLSX);
        exports.insert(task);

        assertThat(exports.findActive(replayId, ExportFormat.XLSX)).isPresent();

        exports.markRunning(task.id());
        assertThat(exports.findActive(replayId, ExportFormat.XLSX).orElseThrow().state())
                .isEqualTo(AsyncTaskState.RUNNING);

        exports.markSucceeded(task.id(), "a.xlsx", "export/a.xlsx", EXPIRE);
        assertThat(exports.findActive(replayId, ExportFormat.XLSX)).isEmpty();
    }

    @Test
    void findActive_ignoresOtherFormatAndOtherReplay() {
        UUID replayId = UuidV7.generate();
        exports.insert(pending(replayId, ExportFormat.XLSX));

        assertThat(exports.findActive(replayId, ExportFormat.CSV)).isEmpty();
        assertThat(exports.findActive(UuidV7.generate(), ExportFormat.XLSX)).isEmpty();
    }

    @Test
    void progressCheck_rejectsOutOfRangeValue() {
        UUID id = newPending(exports, ExportFormat.CSV);

        assertThatThrownBy(() -> exports.updateProgress(id, 101))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_export_task_progress");
    }
}
