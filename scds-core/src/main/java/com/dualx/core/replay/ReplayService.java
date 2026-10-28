package com.dualx.core.replay;

import com.dualx.core.domain.Exercise;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 复盘读侧编排（契约 {@code docs/api/paths/replay.yaml} 的 {@code listReplays} / {@code getReplay}）。
 *
 * <p><b>为什么没有独立 replay 仓储</b>：复盘与演习是同一张表的同一行——{@code t_exercise}
 * 写入 {@code ended_at} 后即成复盘记录，{@code replayId} 就是 {@code exerciseId}。
 * 因此本服务只读 {@link ExerciseRepository}，没有「转存 / 复制」这一步，也就不存在两份主键
 * 谁先谁后的一致性问题（见 V4 与 {@code ExerciseRepository} 类注释）。</p>
 *
 * <p><b>只读</b>：复盘是既成事实，本域不产生任何写动作——轨迹分片
 * （{@code getReplayTimeline}）、统计（{@code getReplayStatistics}）与导出
 * （{@code exportReplay} 等）不在此类，按 M5 的 R2/R3/R4 分段各自落地。</p>
 */
@Service
public class ReplayService {

    private final ExerciseRepository exercises;

    public ReplayService(ExerciseRepository exercises) {
        this.exercises = exercises;
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
        return exercises.findById(replayId)
                .filter(ended -> !ended.running())
                .orElseThrow(() -> new BusinessException(
                        ApiErrorCode.REPLAY_NOT_FOUND, "复盘不存在：" + replayId));
    }
}
