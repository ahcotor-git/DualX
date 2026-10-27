package com.dualx;

import com.dualx.config.SituationStateConfig;
import com.dualx.core.domain.CoordinateSystem;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.HitEvent;
import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.MotionState;
import com.dualx.core.domain.PositionSource;
import com.dualx.core.domain.SampleReason;
import com.dualx.core.domain.TrackSample;
import com.dualx.core.domain.BaseMap;
import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.MapType;
import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.core.port.HardwareEventSink;
import com.dualx.core.push.UnitState;
import com.dualx.core.repository.BaseMapRepository;
import com.dualx.core.repository.ExerciseUnitRepository;
import com.dualx.core.repository.HitEventRepository;
import com.dualx.core.repository.TrackSampleRepository;
import com.dualx.core.state.SituationQuery;
import com.dualx.core.state.SituationSessionBinder;
import com.dualx.core.state.SituationStateService;
import com.dualx.core.support.UuidV7;
import com.dualx.gateway.sim.SimulatedLinkAdapter;
import com.dualx.protocol.Hex;
import com.dualx.protocol.HitPart;
import com.dualx.protocol.ProtocolConstants;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import com.dualx.protocol.serial.SerialFrame;
import com.dualx.protocol.serial.SerialFramer;
import com.dualx.protocol.uwb.UwbFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.security.KeyPair;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 态势状态机（STATE，设计 [06-state](../../../../docs/design/06-state.md) §5 S8）的端到端验收：
 * 走完整生产入口装配，经<b>真实网关成帧</b>注入协议黄金样本帧，再断言<b>core 读侧</b>。
 *
 * <p><b>它比单测多证明了什么</b>：S2~S7 的每个零件都有单测，但单测里没有 Spring、没有真 MySQL、
 * 没有真写手线程。本类证明三件只有装配起来才看得见的事：</p>
 * <ol>
 *   <li>唯一的 {@link HardwareEventSink} bean <b>就是</b> {@link SituationStateService}
 *       （占位 {@code LoggingHardwareEventSink} 已被替换），且 {@link SituationStateConfig.OfflineScanJob}
 *       的 {@code @Scheduled} 生产者已注册；</li>
 *   <li>上行帧走的是<b>真成帧器</b>：注入黄金样本 hex，网关切出的帧类型与字段自洽，状态机按语义演进；</li>
 *   <li>落库侧真写到了真库：命中进 {@code t_hit_event}、轨迹 flush 进 {@code t_track_sample}、
 *       停演终局冻结进 {@code t_exercise_unit}。</li>
 * </ol>
 *
 * <p><b>帧字节取自协议黄金样本</b>（{@code scds-protocol/src/test/resources/samples/golden_samples.json}），
 * 不自造字节——自算校验和等于自证。唯一例外是 CAL-1 的 UWB 41B 帧：库里的 {@code uwb41} 样本是近场
 * 毫米级测距、也没有本场编成编号（1/2/4/5）对应的样本，故按冻结成帧规则拼写（见 {@link #uwbFrameHex}），
 * 并把「能否被真成帧器切出」写进断言。<b>这不是现场验收</b>：真实串口 115200 时序、UWB 盒子经 12001 的
 * 连续坐标、容量与长稳无法由仿真证明（设计 06 §7「现场待验收」）。</p>
 *
 * <p><b>刻意不覆盖的两条（已由 core 单测证明）</b>：</p>
 * <ul>
 *   <li><b>致死回填 {@code kills}</b>：黄金样本里 {@code ff05} 的被击中者只有 4/5，而 {@code ff03}
 *       的 hp=0 只有 1/2，两者没有交集，用黄金样本拼不出「先命中、后阵亡」的同一马甲；
 *       该口径由 {@code SituationStateServiceTest} 证明。</li>
 *   <li><b>增量超窗 409</b>：装配的窗口默认 1024 条，端到端刷满不现实；语义由
 *       {@code SituationQueryTest} 证明，HTTP 状态码映射属 M4。</li>
 * </ul>
 *
 * <p>不写成 {@code @Transactional}：请求走应用自己的线程与连接，测试事务帮不上忙。写入用随机
 * 想定名与专属演习 id，并在 {@link #cleanUp()} 里按 id 精确回收。</p>
 */
class SituationStateIT {

    // ── 协议黄金样本（协议 A，bytes 与校验和均由样本自带，未复算） ──────────────────

    /** {@code ff03} 短版心跳：马甲 2 / 主弹药 30 / HP 100 / 副弹药 30。 */
    private static final String HEARTBEAT_VEST2_LIVE = "ff03000002001e64001ea4affa";

    /** {@code ff03} 短版心跳：马甲 2 / HP 0（马甲自报阵亡，服务端据此判死）。 */
    private static final String HEARTBEAT_VEST2_DIE = "ff03000002000000000004affa";

    /** {@code ff04} 室外 GPS：马甲 2，WGS-84 经纬度随帧携带。 */
    private static final String GPS_VEST2 = "ff0400000207136d12017e8822c7affa";

    /** {@code ff05} 击中记录：被击中者 5、开枪者 4、部位 6（背部）。 */
    private static final String HIT_VEST5_BY_4 = "ff0500000500040613affa";

    /** {@code ff03} 短版心跳：马甲 7（<b>不在</b>本场编成内）。 */
    private static final String HEARTBEAT_VEST7 = "ff03000007001e64001ea9affa";

    /** 本场编成：1/2 为红军，4/5 为蓝军（与黄金样本的编号对齐）。 */
    private static final List<RosterRow> ROSTER = List.of(
            new RosterRow(1, "红军-01", Team.RED),
            new RosterRow(2, "红军-02", Team.RED),
            new RosterRow(4, "蓝军-04", Team.BLUE),
            new RosterRow(5, "蓝军-05", Team.BLUE));

    private static final UUID PLAN_ID = UuidV7.generate();
    private static final String PORT_NAME = "COM7";

    // ── CAL-1 室内解算（UWB 41B）夹具 ────────────────────────────────────────

    /** 本场室内底图 id（专属，{@link #cleanUp()} 按 id 精确回收）。 */
    private static final UUID MAP_ID = UUID.fromString("3f1a2b4c-5d6e-4f70-8192-a3b4c5d6e7f8");

    /**
     * BS1~BS4 四角像素坐标 + 现场实测 30m×18m（与 {@code IndoorPositionResolverTest} 同一份可解几何）。
     *
     * <p>编号照 {@code UwbSolver.Geometry} 的口径：{@code bs1→bs2} 为长度基准、{@code bs1→bs4} 为宽度
     * 基准，两基准须近似正交，故四角按「左下 → 右下 → 右上 → 左上」。</p>
     */
    private static final String UWB_META = """
            {"bs1":{"xPx":120,"yPx":1565},"bs2":{"xPx":1980,"yPx":1565},\
            "bs3":{"xPx":1980,"yPx":240},"bs4":{"xPx":120,"yPx":240},\
            "realLengthCm":3000,"realWidthCm":1800}""";

    /** 物理矩形正中心的一帧测距：四角等距 √306 m ≈ 17.493 m。 */
    private static final int[] RANGES_AT_CENTRE = {17493, 17493, 17493, 17493, 0, 0, 0, 0};

    /** 整图 2000×1600 上，物理中心对应 (1050/2000, 902.5/1600)。 */
    private static final double CENTRE_IMAGE_X = 0.525;
    private static final double CENTRE_IMAGE_Y = 0.5640625;

    private static ConfigurableApplicationContext context;
    private static SimulatedLinkAdapter links;
    private static SituationStateService state;
    private static SituationQuery query;
    private static SituationSessionBinder sessions;
    private static HitEventRepository hitEvents;
    private static TrackSampleRepository trackSamples;
    private static ExerciseUnitRepository exerciseUnits;
    private static BaseMapRepository baseMaps;
    private static JdbcClient jdbc;

    private static final List<UUID> createdExercises = new ArrayList<>();

    @BeforeAll
    static void bootFullApplication() throws Exception {
        KeyPair key = ApplicationStartupSupport.newKeyPair();
        ConfigurableEnvironment environment = ApplicationStartupSupport.environment(true, key);
        environment.getPropertySources().addFirst(new MapPropertySource(
                "situation-state-it", Map.of("scds.gateway.mode", "simulator")));
        context = ApplicationStartupSupport.start(environment);

        assertThat(context.getBean(HardwareEventSink.class))
                .as("唯一的上行消费者必须是真的态势状态机，而不是让边界可见的占位 sink")
                .isInstanceOf(SituationStateService.class);

        links = (SimulatedLinkAdapter) context.getBean(DeviceLinkAdmin.class);
        state = context.getBean(SituationStateService.class);
        query = context.getBean(SituationQuery.class);
        sessions = context.getBean(SituationSessionBinder.class);
        hitEvents = context.getBean(HitEventRepository.class);
        trackSamples = context.getBean(TrackSampleRepository.class);
        exerciseUnits = context.getBean(ExerciseUnitRepository.class);
        baseMaps = context.getBean(BaseMapRepository.class);
        jdbc = context.getBean(JdbcClient.class);

        seedPlanAndRoster();
        // 注入上行帧要求链路已开（仿真下不开链路投喂是非法用法）。
        links.openSerial(new LinkConfig(LinkKind.SERIAL_VEST, PORT_NAME, null,
                false, null, null, false, null, null, null));
        // UWB 帧经 12001 接入（仿真下只登记链路状态，不真绑口）；CAL-1 的解算帧从这里注入。
        links.openUwbTcpServer(new LinkConfig(LinkKind.UWB_TCP_SERVER, null, null,
                false, "127.0.0.1", ProtocolConstants.UWB_TCP_SERVER_PORT, false, null, null, null));
    }

    @AfterAll
    static void cleanUp() {
        if (context != null) {
            for (UUID exerciseId : createdExercises) {
                hitEvents.deleteByExerciseId(exerciseId);
                trackSamples.deleteByExerciseId(exerciseId);
                exerciseUnits.deleteByExerciseId(exerciseId);
            }
            jdbc.sql("DELETE FROM t_plan_roster WHERE plan_id = :planId")
                    .param("planId", PLAN_ID.toString()).update();
            jdbc.sql("DELETE FROM t_plan WHERE id = :planId")
                    .param("planId", PLAN_ID.toString()).update();
            jdbc.sql("DELETE FROM t_map WHERE id = :id")
                    .param("id", MAP_ID.toString()).update();
        }
        if (context != null) {
            context.close();
        }
    }

    // ── 用例 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("装配：唯一 sink 是状态机，读侧门面与在线扫描生产者都已就绪")
    void assemblyExposesTheStateMachineOnceAndItsReadSide() {
        assertThat(context.getBean(SituationStateService.class)).isSameAs(context.getBean(HardwareEventSink.class));
        assertThat(query).isNotNull();
        assertThat(context.getBean(SituationStateConfig.OfflineScanJob.class))
                .as("离线跳变必须有节拍来源，否则 5000ms 窗口永远不会有跳变事件")
                .isNotNull();
        assertThat(links).isInstanceOf(SimulatedLinkAdapter.class);
    }

    @Test
    @DisplayName("无演习时不产生单元、不落库；编成外的马甲帧只计数不改状态")
    void framesWithoutExerciseAndFromUnknownVestsAreIgnored() {
        state.endExercise();
        long revisionAtStop = query.revision();

        links.injectSerial(HEARTBEAT_VEST2_LIVE);
        await("无演习时的心跳被写手线程消费", () -> state.framesFromUnknownVest() > 0);

        assertThat(query.running()).isFalse();
        assertThat(query.exerciseId()).isNull();
        assertThat(query.snapshot()).isEmpty();
        assertThat(query.revision())
                .as("无演习时的心跳不产生单元变化（revision 是运行代内只增不减的计数器）")
                .isEqualTo(revisionAtStop);

        UUID exerciseId = loadExercise();
        long revisionAfterLoad = query.revision();

        long before = state.framesFromUnknownVest();
        links.injectSerial(HEARTBEAT_VEST7);
        await("编成外马甲的帧被消费", () -> state.framesFromUnknownVest() > before);

        assertThat(query.snapshot()).extracting(UnitState::vestNum).containsExactly(1, 2, 4, 5);
        assertThat(query.unit(7)).as("编成外的马甲不进态势").isEmpty();
        assertThat(query.revision())
                .as("编成外马甲的帧不产生单元变化（基线即装载切换点）")
                .isEqualTo(revisionAfterLoad);
        assertThat(exerciseId).isEqualTo(query.exerciseId());
    }

    @Test
    @DisplayName("黄金样本驱动读侧：心跳→LIVE、GPS→定位、命中→落库与计数、hp=0→阵亡冻结")
    void goldenFramesDriveTheReadSide() {
        UUID exerciseId = loadExercise();
        long switchRevision = query.revision();

        assertThat(query.running()).isTrue();
        assertThat(query.snapshot()).allSatisfy(unit -> {
            assertThat(unit.status()).as("装载即离线").isEqualTo(VestStatus.OFFLINE);
            assertThat(unit.position()).isNull();
            assertThat(unit.positionSource()).isEqualTo(PositionSource.NONE);
            assertThat(unit.revision())
                    .as("装载后的单元 stamped 切换点 revision").isEqualTo(switchRevision);
        });
        assertThat(query.changesSince(switchRevision)).as("装载后尚无单元变化")
                .contains(List.of());

        // ① 心跳 → LIVE（网关成帧器切出的帧类型自洽，这一条也证明黄金样本没被误帧）
        List<SerialFrame> framed = links.injectSerial(HEARTBEAT_VEST2_LIVE);
        assertThat(framed).hasSize(1);
        assertThat(framed.getFirst()).isInstanceOf(SerialFrame.Heartbeat.class);
        await("马甲 2 转 LIVE", () -> statusOf(2) == VestStatus.LIVE);

        UnitState live = unit(2);
        assertThat(live.hp()).isEqualTo(100);
        assertThat(live.ammo1()).isEqualTo(30);
        assertThat(live.ammo2()).isEqualTo(30);
        assertThat(live.lastHeartbeatAt()).as("在线窗口的基时刻").isNotNull();
        assertThat(live.position()).as("离线且从未定位").isNull();
        assertThat(live.positionSource()).isEqualTo(PositionSource.NONE);

        long revisionAfterHeartbeat = query.revision();
        assertThat(revisionAfterHeartbeat).isPositive();
        assertThat(query.changesSince(switchRevision).orElseThrow())
                .as("增量窗口按 revision 记单元级变化")
                .extracting(UnitState::vestNum).containsOnly(2);
        assertThat(query.changesSince(revisionAfterHeartbeat).orElseThrow())
                .as("已是最新时返回空列表，而不是超窗").isEmpty();

        // ② GPS → 定位（期望值由协议库独立复算该帧得出，不手抄经纬度）
        // 串口上行是 ASCII HEX 文本，成帧器吃的是字符字节而非二进制（ISO_8859_1 与网关注入同源）。
        SerialFrame.Gps expected = (SerialFrame.Gps) SerialFramer.split(
                GPS_VEST2.getBytes(StandardCharsets.ISO_8859_1)).getFirst();
        links.injectSerial(GPS_VEST2);
        await("马甲 2 拿到坐标", () -> positionOf(2) != null);

        UnitState located = unit(2);
        assertThat(located.position().coordinateSystem()).isEqualTo(CoordinateSystem.OUTDOOR);
        assertThat(located.position().wgs84Lng()).isEqualTo(expected.lng());
        assertThat(located.position().wgs84Lat()).isEqualTo(expected.lat());
        assertThat(located.positionSource()).isEqualTo(PositionSource.GPS);
        assertThat(located.position().lng())
                .as("BD-09 换算规则未闭合（Q-LEGACY-RULE），本段不臆造").isNull();
        assertThat(query.changesSince(revisionAfterHeartbeat).orElseThrow())
                .as("定位是第二次单元变化").extracting(UnitState::vestNum).containsOnly(2);

        // ③ 命中 → 落库 + 每马甲计数 + 队伍汇总（与命中表同口径）
        links.injectSerial(HIT_VEST5_BY_4);
        await("命中被写手线程落库", () -> state.hitsDealt(4) == 1);

        List<HitEvent> hits = query.hitsSince(null, 10);
        assertThat(hits).hasSize(1);
        HitEvent hit = hits.getFirst();
        assertThat(hit.exerciseId()).isEqualTo(exerciseId);
        assertThat(hit.hitVestNum()).isEqualTo(5);
        assertThat(hit.shooterNum()).isEqualTo(4);
        assertThat(hit.shooterIsSoldier()).as("编号 <= 1800 为单兵").isTrue();
        assertThat(hit.shooterWeaponKind()).as("单兵命中不带武器种类").isNull();
        assertThat(hit.hitPart()).isEqualTo(HitPart.BACK);
        assertThat(hit.lethal()).as("致死只能由心跳倒推，命中先落 false").isFalse();
        assertThat(state.hitsDealt(4)).isEqualTo((int) hitEvents.countByShooter(exerciseId, 4));

        SituationQuery.TeamCounts red = teamCounts(Team.RED);
        SituationQuery.TeamCounts blue = teamCounts(Team.BLUE);
        assertThat(red).isEqualTo(new SituationQuery.TeamCounts(Team.RED, 2, 1, 0, 1, 0));
        assertThat(blue).as("命中记在开枪者所在的队伍").isEqualTo(
                new SituationQuery.TeamCounts(Team.BLUE, 2, 0, 0, 2, 1));

        // ④ hp=0 → 阵亡冻结（坐标停在最后有效值，运动状态冻结）
        links.injectSerial(HEARTBEAT_VEST2_DIE);
        await("马甲 2 转 DIE", () -> statusOf(2) == VestStatus.DIE);

        UnitState dead = unit(2);
        assertThat(dead.hp()).isZero();
        assertThat(dead.deadAt()).isNotNull();
        assertThat(dead.motion()).isEqualTo(MotionState.FROZEN);
        assertThat(dead.position().wgs84Lng()).as("阵亡后坐标冻结").isEqualTo(expected.lng());
        assertThat(teamCounts(Team.RED)).isEqualTo(new SituationQuery.TeamCounts(Team.RED, 2, 0, 1, 1, 0));

        // 阵亡是终态：后续心跳不得复活。用同队列 FIFO 的编成外帧当屏障，避免 sleep 赌时序。
        links.injectSerial(HEARTBEAT_VEST2_LIVE);
        long barrier = state.framesFromUnknownVest();
        links.injectSerial(HEARTBEAT_VEST7);
        await("屏障帧被消费（证明上一帧也已处理）", () -> state.framesFromUnknownVest() > barrier);
        assertThat(unit(2).status()).isEqualTo(VestStatus.DIE);
        assertThat(unit(2).hp()).isZero();
    }

    @Test
    @DisplayName("在线扫描：超 5000ms 无心跳转 OFFLINE 并留下 STATE_CHANGE 采样；停演收尾写终局与轨迹")
    void offlineScanFlipsToOfflineAndStoppingFreezesOutcomes() throws Exception {
        UUID exerciseId = loadExercise();
        // 停演冻结要 UPDATE 已有行，先铺开演快照（真实 LIFE 由 startExercise 写这里）。
        for (RosterRow row : ROSTER) {
            jdbc.sql("INSERT INTO t_exercise_unit (exercise_id, vest_num, name, team)"
                            + " VALUES (:exerciseId, :vestNum, :name, :team)")
                    .param("exerciseId", exerciseId.toString())
                    .param("vestNum", row.vestNum()).param("name", row.name())
                    .param("team", row.team().name()).update();
        }

        links.injectSerial(HEARTBEAT_VEST2_LIVE);
        await("马甲 2 转 LIVE", () -> statusOf(2) == VestStatus.LIVE);

        // 5000ms 在线窗口是冻结口径（PROTOCOL.md §5.1），只能真等扫描节拍把它翻成离散跳变。
        await("在线扫描把马甲 2 翻成 OFFLINE", 20, () -> statusOf(2) == VestStatus.OFFLINE);
        assertThat(query.unit(2).orElseThrow().lastHeartbeatAt()).isNotNull();

        int frozen = sessions.onExerciseStopping(exerciseId);

        assertThat(frozen).as("四行开演快照都应被冻结").isEqualTo(ROSTER.size());
        assertThat(query.running()).isFalse();
        assertThat(query.snapshot()).isEmpty();
        assertThat(exerciseUnits.countByExerciseId(exerciseId)).isEqualTo(ROSTER.size());
        assertThat(exerciseUnits.countDeadByExerciseId(exerciseId)).isZero();
        assertThat(exerciseUnits.findByExerciseId(exerciseId))
                .allSatisfy(unit -> assertThat(unit.finalStatus())
                        .as("从未上线的心跳窗口早已过期，终局应为离线")
                        .isEqualTo(VestStatus.OFFLINE));

        List<TrackSample> flushed = trackSamples.findChunk(exerciseId, 0, List.of(2), 1, 500);
        assertThat(flushed).as("停演收尾必须把末段轨迹落库").isNotEmpty();
        assertThat(flushed).extracting(TrackSample::sampleReason)
                .as("在线建立与离线跳变各留一条状态跳变采样")
                .contains(SampleReason.STATE_CHANGE);
    }

    @Test
    @DisplayName("CAL-1：UWB 41B 帧走真成帧器与真库底图，解算写入整图室内坐标与可用基站数")
    void uwbFrameResolvesIntoWholeImageIndoorCoordinate() {
        seedIndoorMap();
        UUID exerciseId = loadExerciseWithMap(MAP_ID);
        long switchRevision = query.revision();
        long withoutFixBefore = state.uwbFramesWithoutFix();

        assertThat(positionOf(2)).as("开演只装载几何，不产生坐标").isNull();

        List<UwbFrame> framed = links.injectUwb(uwbFrameHex(2, RANGES_AT_CENTRE));
        assertThat(framed).as("自拼帧必须被真成帧器切出（帧头 / 帧尾 / 校验自洽）").hasSize(1);
        assertThat(framed.getFirst().vestNum()).isEqualTo(2);
        assertThat(framed.getFirst().rangesMm())
                .as("成帧器解出的测距与拼入的字节一致")
                .containsExactly(17493, 17493, 17493, 17493, 0, 0, 0, 0);

        await("马甲 2 拿到整图室内坐标", () -> positionOf(2) != null);

        UnitState located = unit(2);
        assertThat(located.position().coordinateSystem()).isEqualTo(CoordinateSystem.INDOOR);
        assertThat(located.position().xPct()).isCloseTo(CENTRE_IMAGE_X, within(0.002));
        assertThat(located.position().yPct()).isCloseTo(CENTRE_IMAGE_Y, within(0.002));
        assertThat(located.position().wgs84Lng()).as("室内坐标不带 WGS-84").isNull();
        assertThat(located.positionSource()).isEqualTo(PositionSource.UWB);
        assertThat(located.signalQuality()).as("本帧实际用上的基站数").isEqualTo(4);
        assertThat(state.uwbFramesWithoutFix()).as("本帧解算成功，不进「无解」计数").isEqualTo(withoutFixBefore);
        assertThat(query.changesSince(switchRevision).orElseThrow())
                .as("解算是装载后的第一次单元变化").extracting(UnitState::vestNum).containsOnly(2);
        assertThat(query.exerciseId()).isEqualTo(exerciseId);
    }

    @Test
    @DisplayName("CAL-1：本场无底图时不臆造坐标，UWB 帧只进「无解」计数")
    void uwbFramesWithoutLoadedGeometryAreCountedNotSolved() {
        UUID exerciseId = loadExercise();   // mapId = null → 开演即清空解算几何
        long withoutFixBefore = state.uwbFramesWithoutFix();

        links.injectUwb(uwbFrameHex(2, RANGES_AT_CENTRE));
        await("无几何的 UWB 帧被写手线程消费", () -> state.uwbFramesWithoutFix() > withoutFixBefore);

        assertThat(state.uwbFramesWithoutFix()).isEqualTo(withoutFixBefore + 1);
        assertThat(positionOf(2)).as("解不出坐标时什么都不改").isNull();
        assertThat(unit(2).positionSource()).isEqualTo(PositionSource.NONE);
        assertThat(query.exerciseId()).isEqualTo(exerciseId);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 装载一场新演习（编成来自真库 {@code t_plan_roster}，走真 {@link SituationSessionBinder}）。 */
    private static UUID loadExercise() {
        return loadExerciseWithMap(null);
    }

    /** 装载一场带本场底图的演习：开演时 binder 会把 {@code uwb_meta} 几何交给室内解算器。 */
    private static UUID loadExerciseWithMap(UUID mapId) {
        UUID exerciseId = UuidV7.generate();
        createdExercises.add(exerciseId);
        Instant now = Instant.now();
        sessions.onExerciseStarted(new Exercise(exerciseId, PLAN_ID, "态势IT想定", mapId, BattleMode.TRAIN,
                now, null, ROSTER.size(), 0, 0, now, now));
        assertThat(query.exerciseId()).isEqualTo(exerciseId);
        return exerciseId;
    }

    private static void seedPlanAndRoster() {
        jdbc.sql("INSERT INTO t_plan (id, name, map_id, battle_mode, remark)"
                        + " VALUES (:id, :name, NULL, 'TRAIN', NULL)")
                .param("id", PLAN_ID.toString())
                .param("name", "态势IT想定-" + PLAN_ID).update();
        for (RosterRow row : ROSTER) {
            jdbc.sql("INSERT INTO t_plan_roster (plan_id, vest_num, name, team, hp, ammo1, ammo2, mode)"
                            + " VALUES (:planId, :vestNum, :name, :team, 100, 30, 30, 'AUTO')")
                    .param("planId", PLAN_ID.toString())
                    .param("vestNum", row.vestNum()).param("name", row.name())
                    .param("team", row.team().name()).update();
        }
    }

    /** 铺一张带 {@code uwb_meta} 的可解室内底图；先删后插，重跑不撞主键。 */
    private static void seedIndoorMap() {
        baseMaps.deleteById(MAP_ID);
        baseMaps.insert(new BaseMap(MAP_ID, "态势IT室内底图", MapType.INDOOR, "maps/state-it.png",
                2000, 1600, null, UWB_META, Instant.now(), Instant.now()));
    }

    /**
     * 按协议 B 冻结成帧规则拼一帧 41B：帧头 {@code AA 29}、帧尾 {@code 55}、
     * 校验 {@code sum(byte[0..38]) & 0xFF == byte[39]}（PROTOCOL.md §6.5）。
     *
     * <p><b>为什么自拼而不是取黄金样本</b>：{@code uwb41} 的样本测距只有几十~几百毫米（近场），解不出
     * 30m×18m 的场；也没有本场编成编号（1/2/4/5）对应的样本。这里只按冻结规则写出字节，
     * 「能否被真成帧器切出并交给状态机」由用例断言；UWB 成帧对真实捕获字节的验证由
     * {@code UwbLinkTest} 覆盖，本类不重复。</p>
     */
    private static String uwbFrameHex(int vestNum, int[] rangesMm) {
        byte[] frame = new byte[ProtocolConstants.UWB_FRAME_LEN];
        frame[0] = (byte) 0xAA;
        frame[1] = 0x29;
        frame[2] = 0;                       // 保留字节，解算链路不读
        for (int b = 0; b < 4; b++) {       // vestNum 小端 int32
            frame[3 + b] = (byte) (vestNum >>> (8 * b));
        }
        for (int i = 0; i < UwbFrame.RANGE_COUNT; i++) {
            for (int b = 0; b < UwbFrame.RANGE_BYTES; b++) {
                frame[UwbFrame.RANGE_BASE + UwbFrame.RANGE_BYTES * i + b] =
                        (byte) (rangesMm[i] >>> (8 * b));
            }
        }
        int sum = 0;
        for (int i = 0; i < ProtocolConstants.UWB_FRAME_LEN - 2; i++) {
            sum += frame[i] & 0xFF;
        }
        frame[ProtocolConstants.UWB_FRAME_LEN - 2] = (byte) (sum & 0xFF);
        frame[ProtocolConstants.UWB_FRAME_LEN - 1] = 0x55;
        return Hex.encode(frame);
    }

    private static UnitState unit(int vestNum) {
        return query.unit(vestNum).orElseThrow(() -> new AssertionError("态势里没有马甲 " + vestNum));
    }

    private static VestStatus statusOf(int vestNum) {
        return query.unit(vestNum).map(UnitState::status).orElse(null);
    }

    private static com.dualx.core.domain.GeoPoint positionOf(int vestNum) {
        return query.unit(vestNum).map(UnitState::position).orElse(null);
    }

    private static SituationQuery.TeamCounts teamCounts(Team team) {
        return query.teamSummary().stream()
                .filter(counts -> counts.team() == team)
                .findFirst()
                .orElseThrow(() -> new AssertionError("队伍汇总里没有 " + team));
    }

    private static void await(String what, BooleanSupplier condition) {
        await(what, 10, condition);
    }

    /** 轮询到条件成立或超时：写手线程是异步消费者，断言前必须等它把事件处理完。 */
    private static void await(String what, long seconds, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断：" + what, e);
            }
        }
        throw new AssertionError("等待超时（" + seconds + "s）：" + what);
    }

    /** 编成里的一行（只用于铺库，真值仍以库读回的 {@code RosterEntry} 为准）。 */
    private record RosterRow(int vestNum, String name, Team team) {
    }
}
