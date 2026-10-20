package com.dualx.api.dto.exercise;

import com.dualx.api.dto.situation.ExerciseLogEntry;

import java.util.List;

/**
 * 当前演习日志流水的**增量**响应
 * （契约 paths/exercise.yaml → {@code listCurrentExerciseLogs} 的内联对象，契约未给它起名）。
 *
 * <p>刻意不复用 situation.yaml 里具名的 {@code ExerciseLogPage}：那条契约是
 * {@code {items, meta}} 的分页语义，而这里是「按 seq 续拉」的游标语义——客户端把
 * {@code latestSeq} 作为下次的 {@code sinceSeq}，一个字段就能去重与续拉，不需要 total。</p>
 *
 * @param items     日志条目
 * @param latestSeq 本批最大 seq，客户端下次请求作为 sinceSeq
 */
public record ExerciseLogList(List<ExerciseLogEntry> items, Long latestSeq) {
}
