package com.dualx.core.state;

import com.dualx.core.domain.BaseMap;
import com.dualx.core.domain.CoordinateSystem;
import com.dualx.core.domain.GeoPoint;
import com.dualx.core.domain.HitEvent;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.MotionState;
import com.dualx.core.domain.PositionSource;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.port.HardwareEventSink;
import com.dualx.core.port.SituationPushPort;
import com.dualx.core.push.CombatRole;
import com.dualx.core.push.SituationEnvelope;
import com.dualx.core.push.TeamSummary;
import com.dualx.core.push.UnitState;
import com.dualx.core.repository.HitEventRepository;
import com.dualx.protocol.HitPart;
import com.dualx.protocol.ProtocolConstants;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import com.dualx.protocol.WeaponKind;
import com.dualx.protocol.serial.SerialFrame;
import com.dualx.protocol.uwb.UwbFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 态势的**单一状态所有者**（设计 06 §4.1）：内存里只有一份态势态，所有变更都在唯一写手
 * 线程上串行完成。
 *
 * <p>生产者（网关读线程）只调用 {@link #onSerialFrame}/{@link #onUwbFrame}/{@link #onLinkStateChanged}，
 * 它们把事件塞进 {@link StateEventQueue} 就立刻返回，<b>绝不阻塞读线程</b>；消费者
 * （{@link #start()} 起的 {@code scds-state-writer} 平台线程）逐条 {@link #dispatch(StateEvent)}
 * 处理。查询侧读的是各单元已发布的不可变 {@link UnitState} 视图（volatile 发布），无需加锁。</p>
 *
 * <p><b>已交付</b>：在线/离线判定（5000ms 窗口）、阵亡冻结与判活、命中落库与致死回填、每马甲命中/击杀
 * 计数、revision 单写（S3）；并把 S4/S5 的三个零件**接线**到状态变化点——轨迹采样
 * （{@link TrackSampler} + {@link TrackBatchWriter}）与增量环形窗口（{@link SituationRevisionLog}）。
 * 室内坐标由 41B UWB 帧经 {@link IndoorPositionResolver} 解算后写入，解算几何随本场底图装载
 * （{@link #loadIndoorMap(com.dualx.core.domain.BaseMap)}）。与 LIFE 的装载/停演编排属 S6；
 * core 读侧查询属 S7。</p>
 *
 * <p><b>下行推送</b>（设计 02 §4.1 第三条边）：每次单元变化（含命中/阵亡/判活）推一帧增量、开演与停演
 * 各推一帧全量，全部走 {@link SituationPushPort}；端口实现方负责非阻塞与吞异常（§4.7 约定 1），
 * 本类只保证「推什么」——「怎么发」不在这里。心跳值未变的刷新**不推**：它不计 revision，
 * 推了会让客户端的连续性检查把正常的 200ms 心跳误判成丢帧。</p>
 *
 * <p><b>采样口径</b>（设计 06 §4.4）：每次单元变化都过一次观察点判定
 * （{@code STATE_CHANGE > DISPLACEMENT > TIMER}，见 {@link TrackSampler#onObservation}）；命中、阵亡、
 * 判活另记一条必记的 {@code EVENT}。心跳值未变时走 {@code refresh}——它仍过一次观察点（否则静止单元
 * 永远拿不到 {@code TIMER} 采样），但**不计 revision、不进环形窗口**。</p>
 *
 * <p><b>本段明确不做的边界</b>（不得当成已实现）：</p>
 * <ul>
 *   <li>{@code ff06} 室内定位点仍未采用——室内坐标一律由 41B UWB 帧解算（{@link IndoorPositionResolver}），
 *       而「{@code ff06} 在 UWB 生效时丢弃」这个开关属标定分段（协议 §1.4④），本段仍只记 DEBUG。</li>
 *   <li>GPS 只落协议自带的 WGS-84 值；BD-09（{@code GeoPoint.lng/lat}）的换算规则是
 *       {@code docs/requirements.md} 里未闭合的 Q-LEGACY-RULE，本段不臆造。</li>
 *   <li>{@code combatRole} 的编号区间划分未裁定（设计 06 §6），故 {@link UnitState#combatRole()}
 *       当前恒为 {@code null}。</li>
 * </ul>
 */
public final class SituationStateService implements HardwareEventSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SituationStateService.class);

    /** 在线窗口：距最后心跳超过该值即判离线（PROTOCOL.md §5.1，**冻结**）。 */
    private static final Duration ONLINE_WINDOW =
            Duration.ofMillis(ProtocolConstants.HEARTBEAT_ONLINE_WINDOW_MS);

    private final StateEventQueue queue;
    private final HitEventRepository hitEvents;
    private final Clock clock;
    /** 轨迹降采样判定（S4），只由写手线程使用。 */
    private final TrackSampler trackSampler;
    /** 轨迹批量落库（S4）：采样点先进缓冲，够批/到间隔/停演时落库。 */
    private final TrackBatchWriter trackWriter;
    /** 增量环形窗口（S5）：{@code publish} 时记一条单元级变化，供读侧按 {@code sinceRevision} 补齐。 */
    private final SituationRevisionLog revisionLog;
    /** 态势下行端口（设计 02 §4.1 第三条边）：每次变化、开演、停演各推一帧。 */
    private final SituationPushPort push;
    /** 室内解算（41B UWB 帧 → 整图百分比坐标）；几何随本场底图装载，见 {@link #loadIndoorMap(BaseMap)}。 */
    private final IndoorPositionResolver indoor;

    /** 全部参演单元；结构性变更只在装载/停演时发生，值变更只在写手线程。 */
    private final Map<Integer, Unit> units = new ConcurrentHashMap<>();
    /** 每马甲累计命中数（写手线程维护，读侧可读）。 */
    private final Map<Integer, Integer> hitsDealt = new ConcurrentHashMap<>();
    /** 每马甲累计击杀数（致死回填时累加）。 */
    private final Map<Integer, Integer> kills = new ConcurrentHashMap<>();
    /** 链路开关状态（{@code getVestLink} 的权威来源，属 M4 接线）。 */
    private final Map<LinkKind, LinkState> linkStates = new EnumMap<>(LinkKind.class);

    /** 无演习进行中却到达的业务帧数（可观测：说明网关在上报，但没人开演）。 */
    private final AtomicLong framesWithoutExercise = new AtomicLong();
    /** 未能解出整图坐标的 UWB 帧数（UWB 未开、本场未装载底图几何，或该帧解算降级）。 */
    private final AtomicLong uwbFramesWithoutFix = new AtomicLong();
    /** 不在编成内的马甲帧数（可观测：马甲未被编入却在上报）。 */
    private final AtomicLong framesFromUnknownVest = new AtomicLong();

    private volatile UUID exerciseId;
    private volatile Instant startedAt;
    private volatile long revision;

    /**
     * 进程代标识（设计 02 §6 Q8）：进程启动时生成一次，重启必变。
     *
     * <p>它<b>不参与业务</b>，只作「服务端是否已换代」的判据——{@link #revision} 只在单代内单调，
     * 重启归零后与旧进程的数值存在重叠，客户端单凭 revision 无法分辨「仍是同一代的旧窗口」
     * 与「新进程的巧合数值」。随每个信封下发（HTTP Poll 与 WS 同此），客户端比对不一致即改全量。</p>
     */
    private final String epoch = UUID.randomUUID().toString();

    private volatile Thread writer;
    private volatile boolean accepting = true;

    /**
     * 无下行消费者的构造：{@link NoopSituationPushPort} 兜底。核心单测与「只要核心不要 WS」的
     * 切片走这条，不必拉起 WebSocket 上下文（设计 02 §6 Q6）。生产装配一律走下面带端口的构造。
     */
    public SituationStateService(StateEventQueue queue, HitEventRepository hitEvents, Clock clock,
                                TrackSampler trackSampler, TrackBatchWriter trackWriter,
                                SituationRevisionLog revisionLog, IndoorPositionResolver indoor) {
        this(queue, hitEvents, clock, trackSampler, trackWriter, revisionLog, indoor,
                new NoopSituationPushPort());
    }

    public SituationStateService(StateEventQueue queue, HitEventRepository hitEvents, Clock clock,
                                TrackSampler trackSampler, TrackBatchWriter trackWriter,
                                SituationRevisionLog revisionLog, IndoorPositionResolver indoor,
                                SituationPushPort push) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.hitEvents = Objects.requireNonNull(hitEvents, "hitEvents");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.trackSampler = Objects.requireNonNull(trackSampler, "trackSampler");
        this.trackWriter = Objects.requireNonNull(trackWriter, "trackWriter");
        this.revisionLog = Objects.requireNonNull(revisionLog, "revisionLog");
        this.indoor = Objects.requireNonNull(indoor, "indoor");
        this.push = Objects.requireNonNull(push, "push");
    }

    // ------------------------------------------------------------------ 生产者（网关读线程）

    @Override
    public void onSerialFrame(SerialFrame frame, LinkKind source) {
        if (!accepting) {
            return;
        }
        queue.offer(new StateEvent.Serial(frame, source));
    }

    @Override
    public void onUwbFrame(UwbFrame frame, LinkKind source) {
        if (!accepting) {
            return;
        }
        queue.offer(new StateEvent.Uwb(frame, source));
    }

    @Override
    public void onLinkStateChanged(LinkKind kind, LinkState state, String message) {
        if (!accepting) {
            return;
        }
        queue.offer(new StateEvent.LinkChanged(kind, state, message));
    }

    // ------------------------------------------------------------------ 写手线程

    /** 起唯一写手线程（平台线程，非虚拟线程：D10 要求先实测选型）。 */
    public synchronized void start() {
        if (writer != null) {
            return;
        }
        accepting = true;
        Thread t = new Thread(this::loop, "scds-state-writer");
        t.setDaemon(true);
        writer = t;
        t.start();
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                dispatch(queue.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // 单消费者循环绝不能因一条事件退出：态势后续帧必须继续被处理。
                log.error("态势事件处理失败，已跳过该条", e);
            }
        }
    }

    @Override
    public synchronized void close() {
        accepting = false;
        Thread t = writer;
        writer = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /** 处理一条事件。生产环境只由写手线程调用；单测可同步直呼。 */
    public void dispatch(StateEvent event) {
        switch (event) {
            case StateEvent.Serial s -> onSerial(s.frame());
            case StateEvent.Uwb u -> applyUwb(u.frame());
            case StateEvent.LinkChanged c -> linkStates.put(c.kind(), c.state());
            case StateEvent.OfflineScan scan -> scanOffline(scan.at());
        }
    }

    private void onSerial(SerialFrame frame) {
        switch (frame) {
            case SerialFrame.Heartbeat hb -> heartbeat(hb, clock.instant());
            case SerialFrame.Hit hit -> recordHit(hit, clock.instant());
            case SerialFrame.Gps gps -> applyGps(gps);
            case SerialFrame.IndoorLocation location -> log.debug(
                    "ff06 室内定位点未采用（室内坐标走 UWB 41B 解算帧），vestNum={}", location.vestNum());
            case SerialFrame.Mortar mortar -> log.debug("ff07 迫击炮帧不在 S3 范围，vestNum={}", mortar.vestNum());
            case SerialFrame.SimNo sim -> log.debug("ff0a SIM 帧不在 S3 范围，vestNum={}", sim.vestNum());
        }
    }

    // ------------------------------------------------------------------ 状态变迁

    private void heartbeat(SerialFrame.Heartbeat hb, Instant now) {
        Unit u = units.get(hb.vestNum());
        if (u == null) {
            framesFromUnknownVest.incrementAndGet();
            return;
        }
        if (u.status == VestStatus.DIE) {
            // 阵亡是终态：心跳不再改状态、不再覆盖坐标，只等 COMMAND 判活解除冻结。
            return;
        }
        boolean changed = false;
        if (!Objects.equals(u.hp, hb.hp())) {
            u.hp = hb.hp();
            changed = true;
        }
        if (!Objects.equals(u.ammo1, hb.ammo1())) {
            u.ammo1 = hb.ammo1();
            changed = true;
        }
        if (!Objects.equals(u.ammo2, hb.ammo2())) {
            u.ammo2 = hb.ammo2();
            changed = true;
        }
        if (u.status == VestStatus.OFFLINE) {
            u.status = VestStatus.LIVE;
            changed = true;
        }
        // 心跳刷新在线窗口基；但它本身不计入 revision——否则每 200ms 全量单元都在「变」，
        // S5 的环形窗口（1024 条）会瞬间被心跳刷空，客户端再也拿不到有意义的增量。
        u.lastHeartbeatAt = now;
        if (hb.hp() == 0) {
            die(u, now);
            return;
        }
        if (changed) {
            publish(u);
        } else {
            refresh(u);
        }
    }

    private void applyGps(SerialFrame.Gps gps) {
        Unit u = units.get(gps.vestNum());
        if (u == null) {
            framesFromUnknownVest.incrementAndGet();
            return;
        }
        if (u.status == VestStatus.DIE) {
            // 阵亡冻结：后续上报的坐标一律丢弃（openapi.yaml 硬约束）。
            return;
        }
        GeoPoint previous = u.position;
        u.position = new GeoPoint(
                CoordinateSystem.OUTDOOR,
                null, null,
                previous == null ? null : previous.floorNum(),
                previous == null ? null : previous.posNum(),
                null, null,
                gps.lng(), gps.lat());
        u.positionSource = PositionSource.GPS;
        publish(u);
    }

    /**
     * 室内定位：41B UWB 帧经 {@link IndoorPositionResolver} 解算成**整图**百分比坐标后写入。
     *
     * <p>解不出坐标（UWB 未开、本场未装载底图几何、或该帧降级）时<b>什么都不改</b>，只累加
     * {@link #uwbFramesWithoutFix()}——与 GPS「帧到即写」不同：UWB 的坐标是解算产物，
     * 解不出时把 {@code positionSource} 改成 {@code UWB} 会让客户端把上一帧的坐标当成新的可信值。</p>
     *
     * <p>{@code signalQuality} 记本帧实际用上的基站数（契约：0~8，&lt; 2 时坐标不可信）。</p>
     */
    private void applyUwb(UwbFrame frame) {
        Unit u = units.get(frame.vestNum());
        if (u == null) {
            framesFromUnknownVest.incrementAndGet();
            return;
        }
        if (u.status == VestStatus.DIE) {
            // 阵亡冻结：坐标停在最后有效值（与 GPS 同口径，D15-⑦）。
            return;
        }
        Optional<IndoorPositionResolver.Fix> resolved =
                indoor.resolve(frame.vestNum(), frame.rangesMm(), clock.instant().toEpochMilli());
        if (resolved.isEmpty()) {
            uwbFramesWithoutFix.incrementAndGet();
            return;
        }
        IndoorPositionResolver.Fix fix = resolved.get();
        GeoPoint previous = u.position;
        u.position = new GeoPoint(
                CoordinateSystem.INDOOR,
                fix.xPct(), fix.yPct(),
                previous == null ? null : previous.floorNum(),
                previous == null ? null : previous.posNum(),
                null, null,
                null, null);
        u.positionSource = PositionSource.UWB;
        u.signalQuality = fix.usedCount();
        publish(u);
    }

    private void recordHit(SerialFrame.Hit hit, Instant now) {
        if (exerciseId == null) {
            framesWithoutExercise.incrementAndGet();
            return;
        }
        Unit victim = units.get(hit.hitVestNum());
        GeoPoint position = victim == null ? null : victim.position;
        HitEvent event = new HitEvent(
                0L,
                exerciseId,
                now,
                tMs(now),
                hit.hitVestNum(),
                hit.shooterNum(),
                hit.shooterIsSoldier(),
                WeaponKind.fromNum(hit.shooterNum()),
                HitPart.fromCode(hit.hitPart()),
                false,
                position);
        hitEvents.insert(event);
        hitsDealt.merge(hit.shooterNum(), 1, Integer::sum);
        if (victim != null) {
            // 命中是离散事件点：即使被击中者的状态没变，轨迹里也要留下这一刻（设计 06 §4.4）。
            sampleEvent(victim);
        }
    }

    /** 阵亡：冻结坐标与运动、解除在线窗口，并回填最近一条未判致死的命中。 */
    private void die(Unit u, Instant now) {
        u.status = VestStatus.DIE;
        u.deadAt = now;
        u.frozenMotion = u.motion;
        u.motion = MotionState.FROZEN;
        publishEvent(u);
        if (exerciseId == null) {
            return;
        }
        hitEvents.markMostRecentLethal(exerciseId, u.vestNum)
                .ifPresent(shooter -> kills.merge(shooter, 1, Integer::sum));
    }

    /**
     * 判活（COMMAND 下发后调用）：解除冻结、还原 motion、清 deadAt。**坐标不还原**，等下一帧坐标到达。
     *
     * @return 该马甲存在且确实由阵亡状态被救活时为 true
     */
    public boolean revive(int vestNum) {
        Unit u = units.get(vestNum);
        if (u == null || u.status != VestStatus.DIE) {
            return false;
        }
        u.status = VestStatus.LIVE;
        u.deadAt = null;
        u.motion = u.frozenMotion;
        u.frozenMotion = null;
        publishEvent(u);
        return true;
    }

    /** 在线扫描：把「距最后心跳超窗」变成一次离散跳变（离线时刻必须留在轨迹里，设计 06 §4.2）。 */
    private void scanOffline(Instant at) {
        for (Unit u : units.values()) {
            if (u.status == VestStatus.LIVE
                    && (u.lastHeartbeatAt == null
                    || at.isAfter(u.lastHeartbeatAt.plus(ONLINE_WINDOW)))) {
                u.status = VestStatus.OFFLINE;
                publish(u);
            }
        }
    }

    /** 单元变化：revision 单写 +1、记入环形窗口、发布新视图，并按观察点采样。 */
    private void publish(Unit u) {
        revision = revision + 1;
        u.revision = revision;
        u.view = viewOf(u);
        revisionLog.record(u.view);
        sampleObservation(u);
        pushDelta(u);
    }

    /**
     * 离散事件点（命中/阵亡/判活）：与 {@link #publish} 同样计入 revision 与环形窗口，
     * 差别只在采样理由固定为 {@code EVENT}（必记，不受「状态没变」抑制）。
     */
    private void publishEvent(Unit u) {
        revision = revision + 1;
        u.revision = revision;
        u.view = viewOf(u);
        revisionLog.record(u.view);
        sampleEvent(u);
        pushDelta(u);
    }

    /** 只刷新视图（如心跳窗口基），不计入 revision、不进环形窗口；但仍过一次观察点以产出 TIMER 采样。 */
    private void refresh(Unit u) {
        u.view = viewOf(u);
        sampleObservation(u);
    }

    // ------------------------------------------------------------------ 下行推送（设计 02 §4.4、§4.6）

    /**
     * 推一帧增量：只带本次变化的那个单元。
     *
     * <p>{@code hits} 与 {@code teamSummary} 恒空——与 HTTP 增量帧<u>同口径</u>（{@code
     * SituationEnvelopeFactory.delta}）：命中不推 revision、{@code seq} 与 {@code revision} 互不推导，
     * 故增量帧无从定出该带哪一段命中；队伍汇总由客户端把 {@code changes} 合进本地单元表后自算。
     * 权威汇总只在全量帧里出现。</p>
     *
     * <p>端口实现方负责非阻塞与吞异常（设计 02 §4.7 约定 1），本类不在此加 I/O 等待或兜底
     * try/catch——加了会把「实现方未遵守约定」这个真问题藏起来。</p>
     */
    private void pushDelta(Unit u) {
        push.publish(new SituationEnvelope(
                epoch, revision, false, running(), exerciseIdText(), clock.instant(),
                List.of(), List.of(u.view), List.of(), List.of(), List.of()));
    }

    /**
     * 推一帧全量：开演 / 停演这类「换场」用全量帧表达最直观——战场清空或重建都不需要新增动作类型，
     * 客户端凭帧自带的 revision 直接对齐（设计 02 §4.4、§4.9）。
     */
    private void pushFull() {
        List<UnitState> snapshot = snapshot();
        push.publish(new SituationEnvelope(
                epoch, revision, true, running(), exerciseIdText(), clock.instant(),
                snapshot, List.of(), List.of(), List.of(),
                SituationQuery.teamSummaryOf(snapshot, this::hitsDealt).stream()
                        .map(SituationEnvelopeFactory::toWire).toList()));
    }

    private String exerciseIdText() {
        UUID id = exerciseId;
        return id == null ? null : id.toString();
    }

    /** 观察点采样：按 {@code STATE_CHANGE > DISPLACEMENT > TIMER} 判定，不该记时不落库。 */
    private void sampleObservation(Unit u) {
        trackSampler.onObservation(exerciseId, u.view, tMs(clock.instant()))
                .ifPresent(trackWriter::add);
    }

    /** 事件点采样：无条件记一行。 */
    private void sampleEvent(Unit u) {
        trackWriter.add(trackSampler.onEvent(exerciseId, u.view, tMs(clock.instant())));
    }

    private static UnitState viewOf(Unit u) {
        return new UnitState(
                u.vestNum, u.name, u.team, u.combatRole, u.weaponKind, u.status,
                u.hp, u.ammo1, u.ammo2, u.position, u.motion, u.signalQuality,
                u.positionSource, u.lastHeartbeatAt, u.deadAt, u.revision);
    }

    private int tMs(Instant now) {
        Instant start = startedAt;
        return start == null ? 0 : (int) Duration.between(start, now).toMillis();
    }

    // ------------------------------------------------------------------ 与 LIFE 的衔接（S6 调用）

    /**
     * 装载编成开始新演习。幂等：同一 {@code exerciseId} 重复装载直接返回。
     *
     * <p><b>revision 只增不减</b>（设计 02 §4.4）：切换本身算一次变化，装载后 revision {@code +1}，
     * 本场单元一律 stamped 该切换点，并把增量窗口下界抬到切换点——早于切换点的 {@code sinceRevision}
     * 一律超窗 → 409 → 客户端改全量。进程重启从 0 重新起算（跨代恢复属 PUSH-MODEL）。</p>
     *
     * <p><b>先把室内解算几何清空</b>：本场的几何由紧随其后的
     * {@link #loadIndoorMap(BaseMap)} 提供。留着上一场的底图会让新演习的 UWB 帧按旧场几何解算，
     * 而那是**静默的错误坐标**——比解不出来更难发现。</p>
     */
    public synchronized void beginExercise(UUID newExerciseId, Instant now, List<RosterEntry> roster) {
        Objects.requireNonNull(newExerciseId, "exerciseId");
        if (newExerciseId.equals(exerciseId)) {
            return;
        }
        indoor.clear();
        units.clear();
        hitsDealt.clear();
        kills.clear();
        units.putAll(loadRoster(roster));
        // 切换本身是一次态势变化：revision 只增不减，装载后的单元一律 stamped 切换点。
        revision = revision + 1;
        for (Unit u : units.values()) {
            u.revision = revision;
            u.view = viewOf(u);
        }
        // 跨演习必须清采样基准并抬增量窗口下界：留着上一场的条目会被当成新变化，且旧 sinceRevision 会静默失效。
        trackSampler.reset();
        revisionLog.rebase(revision);
        startedAt = now;
        exerciseId = newExerciseId;
        log.info("态势装载完成：exerciseId={}，单元数={}，切换点 revision={}", newExerciseId, units.size(), revision);
        // 换场必须让已连上的客户端立刻重画：全量帧表达「战场重建」（设计 02 §4.9）。
        pushFull();
    }

    /**
     * 装载本场底图的室内解算几何（{@code t_map} 的 {@code uwb_meta} 与图像像素尺寸）。
     *
     * <p>由 {@link SituationSessionBinder} 在 {@link #beginExercise} 之后调用；{@code map} 为
     * {@code null} 表示本场没有底图（室外纯 GPS 或想定未绑图），等价于清空。停演由
     * {@link #endExercise()} 统一清空，调用方不必再清。</p>
     *
     * @return 该图的 {@code uwb_meta} 与像素尺寸是否齐备；false 时 UWB 帧只计数不出坐标
     */
    public synchronized boolean loadIndoorMap(BaseMap map) {
        if (map == null) {
            indoor.clear();
            log.info("本场无底图：室内解算几何已清空，UWB 帧只计数不出坐标");
            return false;
        }
        boolean ready = indoor.load(map.id().toString(), map.uwbMeta(), map.imageWidth(), map.imageHeight());
        if (ready) {
            log.info("室内解算几何就绪：mapId={}，底图={}×{}", map.id(), map.imageWidth(), map.imageHeight());
        } else {
            log.warn("底图缺 UWB 标定参数或图片尺寸，室内解算不可用：mapId={}，底图={}×{}",
                    map.id(), map.imageWidth(), map.imageHeight());
        }
        return ready;
    }

    /**
     * 停演收尾：返回全体单元的终局，供 S6 用 {@code ExerciseUnitRepository.freezeOutcome} 落库
     * （必须**先**写终局再 flush 轨迹，否则 {@code freezeAndClose} 读库会读到 0 阵亡），随后清空内存态。
     *
     * @return 终局列表；当前没有演习进行中时返回空列表
     */
    public synchronized List<UnitOutcome> endExercise() {
        // 解算几何与内存态同生命周期：停演即失效，免得下一场开演前到达的 UWB 帧按本场几何解算。
        indoor.clear();
        if (exerciseId == null) {
            return List.of();
        }
        List<UnitOutcome> outcomes = new ArrayList<>(units.size());
        for (Unit u : units.values()) {
            outcomes.add(new UnitOutcome(
                    u.vestNum,
                    u.status,
                    u.deadAt,
                    kills.getOrDefault(u.vestNum, 0),
                    hitsDealt.getOrDefault(u.vestNum, 0)));
        }
        outcomes.sort(Comparator.comparingInt(UnitOutcome::vestNum));
        // 末段轨迹必须先落库再清内存态，否则留在缓冲里的采样点会随装载丢失（设计 06 §4.4）。
        int flushed = trackWriter.flush();
        log.info("态势收尾：exerciseId={}，终局数={}，补落轨迹={} 条", exerciseId, outcomes.size(), flushed);
        // 停演也算一次切换：revision 继续递增并把窗口下界抬到切换点，早于切换点的 sinceRevision →
        // 409 → 客户端改全量（此时全量为空），不会因窗口残留上一场条目而静默失效（设计 02 §4.4）。
        revision = revision + 1;
        revisionLog.rebase(revision);
        units.clear();
        exerciseId = null;
        startedAt = null;
        // 停演后的第一帧必须是全量且 running=false、units 为空——客户端据此清空战场（设计 02 §7 判据 6）。
        pushFull();
        return outcomes;
    }

    private static Map<Integer, Unit> loadRoster(List<RosterEntry> roster) {
        Map<Integer, Unit> loaded = new LinkedHashMap<>();
        if (roster == null) {
            return loaded;
        }
        for (RosterEntry entry : roster) {
            loaded.put(entry.vestNum(), new Unit(entry));
        }
        return loaded;
    }

    // ------------------------------------------------------------------ 读侧（HTTP 接线属 M4）

    /** 全量态势快照，按马甲编号升序。 */
    public List<UnitState> snapshot() {
        List<UnitState> list = new ArrayList<>(units.size());
        for (Unit u : units.values()) {
            UnitState view = u.view;
            if (view != null) {
                list.add(view);
            }
        }
        list.sort(Comparator.comparingInt(UnitState::vestNum));
        return list;
    }

    /** 单个马甲的实时状态。 */
    public Optional<UnitState> unit(int vestNum) {
        Unit u = units.get(vestNum);
        return u == null ? Optional.empty() : Optional.ofNullable(u.view);
    }

    /** 当前是否有演习进行中（无演习时 `running=false`，D15-⑥）。 */
    public boolean running() {
        return exerciseId != null;
    }

    /** 进行中的演习 ID，无则为 {@code null}。 */
    public UUID exerciseId() {
        return exerciseId;
    }

    /** 当前全局 revision（**单代**，进程重启从 0 起算；**只增不减**，开演/停演各 +1，设计 02 §4.4）。 */
    public long revision() {
        return revision;
    }

    /**
     * 当前进程代标识（设计 02 §6 Q8）。
     *
     * <p><b>为什么不放进 revision</b>：客户端拿到的 {@code revision} 只在单代内可比。进程重启后
     * revision 归零重算，与上一个进程的数值存在重叠——若只下发 revision，客户端会把「新进程的
     * 巧合数值」当成「自己还在窗口内」，静默丢掉整段状态。故另发一个换代必变的标识，
     * 客户端比对不一致即丢弃本地缓存改全量。</p>
     */
    public String epoch() {
        return epoch;
    }

    /**
     * 增量窗口读取（S7 读侧 {@code SituationQuery} 的底层原语）：{@code sinceRevision} 之后的
     * 单元级变化，按 revision 升序；{@code sinceRevision} 超出保留窗口时为空 Optional
     * （调用方据此返回 409 {@code SITUATION_REVISION_TOO_OLD}）。
     */
    public Optional<List<UnitState>> changesSince(long sinceRevision) {
        return revisionLog.changesSince(sinceRevision);
    }

    /** 某马甲的累计命中数。 */
    public int hitsDealt(int vestNum) {
        return hitsDealt.getOrDefault(vestNum, 0);
    }

    /** 某马甲的累计击杀数（致死回填计数）。 */
    public int kills(int vestNum) {
        return kills.getOrDefault(vestNum, 0);
    }

    /** 某条链路最近一次开关状态（{@code getVestLink} 的权威来源）。 */
    public Optional<LinkState> linkState(LinkKind kind) {
        return Optional.ofNullable(linkStates.get(kind));
    }

    public long framesWithoutExercise() {
        return framesWithoutExercise.get();
    }

    /** 未能解出整图坐标的 UWB 帧数（UWB 未开 / 本场无底图几何 / 该帧解算降级时的可观测计数）。 */
    public long uwbFramesWithoutFix() {
        return uwbFramesWithoutFix.get();
    }

    public long framesFromUnknownVest() {
        return framesFromUnknownVest.get();
    }

    /** 停演终局（{@code t_exercise_unit} 的冻结来源）。 */
    public record UnitOutcome(int vestNum, VestStatus finalStatus, Instant deadAt, int kills, int hitsDealt) {
    }

    /** 内存态的单个单元。只有写手线程改字段；读线程只读 volatile 的 {@link #view}。 */
    private static final class Unit {

        final int vestNum;
        final String name;
        final Team team;
        final CombatRole combatRole;
        final WeaponKind weaponKind;

        VestStatus status = VestStatus.OFFLINE;
        Integer hp;
        Integer ammo1;
        Integer ammo2;
        GeoPoint position;
        MotionState motion;
        MotionState frozenMotion;
        Integer signalQuality;
        PositionSource positionSource = PositionSource.NONE;
        Instant lastHeartbeatAt;
        Instant deadAt;
        long revision;

        volatile UnitState view;

        Unit(RosterEntry entry) {
            this.vestNum = entry.vestNum();
            this.name = entry.name();
            this.team = entry.team();
            // 编号区间→CombatRole 未裁定（设计 06 §6），故本段留空。
            this.combatRole = null;
            this.weaponKind = WeaponKind.fromNum(entry.vestNum());
            this.hp = entry.hp();
            this.ammo1 = entry.ammo1();
            this.ammo2 = entry.ammo2();
        }
    }
}
