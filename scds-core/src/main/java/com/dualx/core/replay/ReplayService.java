package com.dualx.core.replay;

import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.TrackSample;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.repository.TrackSampleRepository;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 复盘读侧编排（契约 {@code docs/api/paths/replay.yaml} 的 {@code listReplays} / {@code getReplay} /
 * {@code getReplayTimeline}）。
 *
 * <p><b>为什么没有独立 replay 仓储</b>：复盘与演习是同一张表的同一行——{@code t_exercise}
 * 写入 {@code ended_at} 后即成复盘记录，{@code replayId} 就是 {@code exerciseId}。
 * 因此本服务只读 {@link ExerciseRepository}，没有「转存 / 复制」这一步，也就不存在两份主键
 * 谁先谁后的一致性问题（见 V4 与 {@code ExerciseRepository} 类注释）。轨迹另读
 * {@link TrackSampleRepository}（{@code t_track_sample} 按 {@code exercise_id} 归属，同 ID 可分片）。</p>
 *
 * <p><b>只读</b>：复盘是既成事实，本域不产生任何写动作——统计
 * （{@code getReplayStatistics}）与导出（{@code exportReplay} 等）不在此类，
 * 按 M5 的 R3/R4 分段各自落地。</p>
 */
@Service
public class ReplayService {

    private final ExerciseRepository exercises;
    private final TrackSampleRepository tracks;

    public ReplayService(ExerciseRepository exercises, TrackSampleRepository tracks) {
        this.exercises = exercises;
        this.tracks = tracks;
    }

    /**
     * 轨迹分片（契约 {@code getReplayTimeline}）。
     *
     * <p>游标是 {@code t_track_sample.id}，不是 {@code t_ms}：{@code id} 是自增代理键且插入序即采样序，
     * 拿它分片不重不漏；{@code t_ms} 只保证时间语义，同毫秒多点时无法唯一定出下一片的起点
     * （见 {@link TrackSampleRepository#findChunk}）。</p>
     *
     * <p>{@code nextCursor} 只在「本片正好拉满」时给值：没拉满就说明库里的数据已经见底。
     * 反过来，拉满不保证一定还有下一片（可能刚好整除），此时客户端会多拉一次空片并拿到
     * {@code nextCursor=null}——用一次空请求换「不必多查一次 COUNT」。</p>
     *
     * @param cursor 客户端回传的上一片 {@code nextCursor}；首次为 null
     */
    @Transactional(readOnly = true)
    public Chunk timeline(UUID replayId, String cursor, int limit, List<Integer> vestNums, int stride) {
        requireReplay(replayId);
        List<TrackSample> samples = tracks.findChunk(replayId, afterId(cursor), vestNums, stride, limit);
        return new Chunk(samples, samples.size() < limit ? null : Long.toString(samples.getLast().id()));
    }

    /**
     * 复盘列表（契约 {@code listReplays}）：按结束时间倒序分页。
     *
     * <p>列表与总数共用 {@link ExerciseRepository} 里同一份过滤条件（{@code ended_at IS NOT NULL}
     * + 想定名模糊 + 结束时间区间），两者口径不会各自漂移——翻页时「第 N 页还有没有数据」
     * 与 {@code total} 必须说同一件事。</p>
     *
     * <p>排序末位按 {@code id} 兜底：同毫秒结束的两场若不这样排，翻页时会在页边界换位，
     * 让客户端看到重复或漏项。</p>
     */
    public Page<Exercise> list(String keyword, Instant from, Instant to, PageRequest request) {
        List<Exercise> items = exercises.findEnded(keyword, from, to, request);
        long total = exercises.countEnded(keyword, from, to);
        return Page.of(items, total, request);
    }

    /**
     * 复盘概览（契约 {@code getReplay}）。
     *
     * <p><b>404 判据</b>：{@code ended_at IS NULL} 的行虽在 {@code t_exercise} 里存在，但它
     * 描述的是「进行中的那场演习」而不是一条复盘——「还没结束」与「不存在」对复盘入口是同一件事，
     * 都返回 {@link ApiErrorCode#REPLAY_NOT_FOUND}，不区分 404 的两种成因（否则客户端还要
     * 去 current 端点反查，把一次读取变成两次）。</p>
     */
    @Transactional(readOnly = true)
    public Exercise get(UUID replayId) {
        return requireReplay(replayId);
    }

    /**
     * 复盘入口的共用 404 判据：{@code t_exercise} 里有这一行、且它已经结束。
     *
     * <p>概览与轨迹分片共用它——两条路由都以 {@code replayId} 定位一条复盘，若各自判一套，
     * 「有没有这条复盘」会在两个端点给出不同答案。</p>
     */
    private Exercise requireReplay(UUID replayId) {
        return exercises.findById(replayId)
                .filter(ended -> !ended.running())
                .orElseThrow(() -> new BusinessException(
                        ApiErrorCode.REPLAY_NOT_FOUND, "复盘不存在：" + replayId));
    }

    /**
     * 游标解析：契约把 {@code cursor} 描述成「上一片返回的 nextCursor」，对客户端是不透明串。
     *
     * <p>解析不出来按 400 {@link ApiErrorCode#VALIDATION_FAILED} 响亮失败，不静默退回 0：
     * 从 0 重新开始会让客户端每次都拿到第一片，形成「永远在拉第一页」的死循环——
     * 一个坏游标若能被容忍，故障就变成无声的性能问题而不是一次可见的报错。</p>
     */
    private static long afterId(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        try {
            long value = Long.parseLong(cursor.trim());
            if (value < 0) {
                throw new NumberFormatException("negative");
            }
            return value;
        } catch (NumberFormatException malformed) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED, "游标不合法：" + cursor);
        }
    }

    /**
     * 轨迹分片结果（契约 {@code TimelineChunk} 在 core 侧的对应物）。
     *
     * @param samples    本片采样点，按 {@code id} 升序（即采样序）
     * @param nextCursor 下一片游标；null 表示已到末尾
     */
    public record Chunk(List<TrackSample> samples, String nextCursor) {
    }
}
