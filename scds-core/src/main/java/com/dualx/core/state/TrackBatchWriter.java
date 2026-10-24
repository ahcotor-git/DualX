package com.dualx.core.state;

import com.dualx.core.domain.TrackSample;
import com.dualx.core.repository.TrackSampleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 轨迹落库的<b>批量缓冲</b>（设计 06 §4.4）。采样点先进内存，攒到 {@code batchSize} 或距上次落库
 * 超过 {@code flushIntervalMs} 时，由<b>同一个态势写手线程</b>一次 {@link TrackSampleRepository#insertAll}
 * 落库——逐帧落库会把磁盘与连接池打满，这是 {@code t_track_sample} 的主写入口。
 *
 * <p><b>只由态势唯一写手线程调用</b>，因此缓冲用普通 {@link ArrayList}，不加锁。</p>
 *
 * <p><b>为什么没有后台定时线程</b>：设计 06 §4.4 要求「由事件线程一次 insertAll」。写手线程每收到
 * 一帧都会经过 {@link #add}，间隔判定在那里顺带完成即可；帧流停下来的场景由停演收尾的
 * {@link #flush()} 兜底。多一个定时线程只会多一处与写手的并发面。</p>
 *
 * <p><b>落库失败不回滚内存态</b>（设计 06 §4.4）：本类只保留缓冲、记 ERROR 与计数，态势状态照常演进；
 * 失败的一批<b>留在缓冲里等下次重试</b>，以免复盘轨迹出现空洞。缓冲有硬上限
 * {@code maxBuffered}：持续失败时丢弃<b>最旧</b>的一批并计数，保证内存有界（D10 容量未闭合，
 * 不能靠「一直堆着」来赌）。</p>
 *
 * <p><b>已知取舍</b>：{@code insertAll} 是单次 JDBC batch，若异常发生在批次中途提交，重试会产生
 * 少量重复行（{@code t_track_sample.id} 自增，不能用主键去重）。宁可偶发重复也不静默丢点，现场
 * 若证明重复不可接受，改这一处的失败策略即可。</p>
 *
 * <p><b>配置未闭合声明（D10）</b>：三个构造参数就是 {@code scds.state.track-batch-size} /
 * {@code scds.state.track-flush-interval-ms} / 缓冲硬上限的落点，取值属容量候选（由装配层绑定）。</p>
 */
public final class TrackBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(TrackBatchWriter.class);

    private final TrackSampleRepository repository;
    private final int batchSize;
    private final long flushIntervalMs;
    private final int maxBuffered;

    /** 单调时基（毫秒），用于间隔判定；不随墙上时钟跳变。 */
    private final LongSupplier tickMs;

    private final List<TrackSample> buffer = new ArrayList<>();
    private long lastFlushAtMs;

    private long flushedRows;
    private long failedFlushes;
    private long droppedSamples;

    /**
     * @param batchSize       攒够多少条落库（{@code scds.state.track-batch-size}），必须 &gt; 0
     * @param flushIntervalMs 距上次落库超过该值即落库（{@code scds.state.track-flush-interval-ms}），必须 &gt; 0
     * @param maxBuffered     缓冲硬上限，必须 &ge; {@code batchSize}
     * @param tickMs          单调毫秒时基（生产传 {@code System::currentTimeMillis} 的单调替代或注入时钟）
     */
    public TrackBatchWriter(TrackSampleRepository repository, int batchSize, long flushIntervalMs,
                            int maxBuffered, LongSupplier tickMs) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.tickMs = Objects.requireNonNull(tickMs, "tickMs");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("批量大小必须 > 0：" + batchSize);
        }
        if (flushIntervalMs <= 0) {
            throw new IllegalArgumentException("落库间隔必须 > 0：" + flushIntervalMs);
        }
        if (maxBuffered < batchSize) {
            throw new IllegalArgumentException(
                    "缓冲硬上限不得小于批量大小：maxBuffered=" + maxBuffered + ", batchSize=" + batchSize);
        }
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.maxBuffered = maxBuffered;
        this.lastFlushAtMs = tickMs.getAsLong();
    }

    /** 收下一个采样点；够一批或到间隔就顺带落库。 */
    public void add(TrackSample sample) {
        Objects.requireNonNull(sample, "sample");
        buffer.add(sample);
        if (buffer.size() >= batchSize || intervalElapsed()) {
            flush();
        }
        trimIfOverCap();
    }

    /**
     * 立即落库缓冲里的全部采样点。停演收尾<b>必须</b>调用（设计 06 §4.4），否则末段轨迹丢在缓冲里。
     *
     * @return 实际写入行数；缓冲为空或落库失败时为 0
     */
    public int flush() {
        lastFlushAtMs = tickMs.getAsLong();
        if (buffer.isEmpty()) {
            return 0;
        }
        List<TrackSample> batch = List.copyOf(buffer);
        try {
            int rows = repository.insertAll(batch);
            buffer.clear();
            flushedRows += rows;
            return rows;
        } catch (RuntimeException e) {
            // 不回滚内存态：只记错误与计数，缓冲保留待下次重试。
            failedFlushes++;
            log.error("轨迹批量落库失败，保留 {} 条待下次重试", batch.size(), e);
            return 0;
        }
    }

    /** 当前待落库条数。 */
    public int buffered() {
        return buffer.size();
    }

    /** 累计写入行数。 */
    public long flushedRows() {
        return flushedRows;
    }

    /** 累计落库失败次数。 */
    public long failedFlushes() {
        return failedFlushes;
    }

    /** 因缓冲超硬上限被丢弃的条数（持续落库失败的可观测指标）。 */
    public long droppedSamples() {
        return droppedSamples;
    }

    private boolean intervalElapsed() {
        return tickMs.getAsLong() - lastFlushAtMs >= flushIntervalMs;
    }

    private void trimIfOverCap() {
        int excess = buffer.size() - maxBuffered;
        if (excess <= 0) {
            return;
        }
        buffer.subList(0, excess).clear();
        droppedSamples += excess;
        log.error("轨迹缓冲超过硬上限 {}，已丢弃最旧 {} 条（落库持续失败？累计丢弃 {}）",
                maxBuffered, excess, droppedSamples);
    }
}
