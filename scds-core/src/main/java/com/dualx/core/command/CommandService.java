package com.dualx.core.command;

import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseCommand;
import com.dualx.core.domain.ExerciseCommandState;
import com.dualx.core.domain.ExerciseLogEntry;
import com.dualx.core.domain.ExerciseLogType;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.domain.PollMode;
import com.dualx.core.domain.RosterEntry;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.exercise.ExerciseLifecycleService;
import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.core.repository.AccountRepository;
import com.dualx.core.repository.ExerciseCommandRepository;
import com.dualx.core.repository.ExerciseLogRepository;
import com.dualx.core.repository.PlanRosterRepository;
import com.dualx.core.state.SituationQuery;
import com.dualx.protocol.Hex;
import com.dualx.protocol.Team;
import com.dualx.protocol.serial.DownlinkCommands;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 单兵即时指令的编排（M3 COMMAND，契约 {@code docs/api/paths/command.yaml}）。
 *
 * <p><b>本段做 7 条</b>：判死 / 判活 / 判伤 / 超范围 / 单兵充弹 / 全体充弹 / 覆盖配置。手动置位
 * （{@code move}）依赖地图置位写入口，另行单列任务。</p>
 *
 * <p><b>顺序即纪律：先发字节，成功后落库</b>。契约把「受理」定义为「指令进入发送队列」，
 * 而物理下发一旦发生就无法回滚（决策 D11：不得把 Accepted 当设备执行成功）。因此本段不写
 * {@code PENDING} 再走 {@code markSent} 两段式，而是把已发事实<b>一次性</b>落成
 * {@code state=SENT} 的队列行——库里的行因此恒为「真的发过」，不会出现「发了但库说是 PENDING」
 * 或「库里说 SENT 但字节没出去」两种自相矛盾。</p>
 *
 * <p><b>{@code accepted=false} 只有两种成因</b>（用户裁定）：链路未开、串口队列满。两者都
 * <b>不落库、不写日志</b>——契约 D11 的裁定是「false = 未入队」，而不是「已受理待重连」。
 * 马甲心跳离线<b>不拦</b>：离线只说明网关这一侧没收到心跳，判死/判活这类指令正是发给它的。</p>
 *
 * <p><b>为什么在 core 而不是 api</b>：编排要用仓储与硬件下行端口，api 只做 DTO 搬运。本类与
 * {@link SituationQuery} 同属<b>普通类</b>（不加 {@code @Service}）：core 的测试上下文由
 * {@code TestCoreApplication} 装配，看不到 {@code scds-app} 的装配层，若在这里挂
 * {@code @Service} 并强依赖 {@link DeviceLinkAdmin} 等端口，全部 core 集成测试都会因为找不到
 * bean 而起不来。装配点在 {@code scds-app} 的 {@code CommandConfig}。</p>
 *
 * <p><b>不做 {@code @Transactional}</b>：物理下发已不可回滚，只把两个库写包成事务会给出一种
 * 并不存在的「整体回滚」错觉；而本段既没有跨表的删改、也没有「先删后记」这类一旦失败就永久
 * 丢字节的动作，故按最小改动处理。</p>
 */
public final class CommandService {

    /**
     * 「全体」指令下发给网关时用的马甲编号。
     *
     * <p>广播帧的目标字段固定为 {@code FF FF FF}，编号参数在帧里并不参与编码；这里传 0 是给
     * {@code sendVestCommand} 与 {@code t_exercise_command.vest_num} 一个占位——真实马甲编号
     * 下界为 1（契约 {@code minimum: 1}），0 不会与任何真实编号混淆。</p>
     */
    private static final int BROADCAST_VEST_NUM = 0;

    /** core 侧不依赖 Spring 注入的 ObjectMapper（与 {@code MapLedgerService} 同一范式）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExerciseLifecycleService lifecycle;
    private final SituationQuery situations;
    private final PlanRosterRepository roster;
    private final DeviceLinkAdmin links;
    private final ExerciseCommandRepository commands;
    private final ExerciseLogRepository logs;
    private final AccountRepository accounts;
    private final Clock clock;

    public CommandService(ExerciseLifecycleService lifecycle,
                          SituationQuery situations,
                          PlanRosterRepository roster,
                          DeviceLinkAdmin links,
                          ExerciseCommandRepository commands,
                          ExerciseLogRepository logs,
                          AccountRepository accounts,
                          Clock clock) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.situations = Objects.requireNonNull(situations, "situations");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.links = Objects.requireNonNull(links, "links");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.logs = Objects.requireNonNull(logs, "logs");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------ 单兵即时指令

    /** 判死：8B 判死帧，阵亡后坐标冻结。 */
    public Ack kill(int vestNum, UUID operatorId) {
        return dispatch(vestNum, "DIE", DownlinkCommands.die(vestNum), "{}", operatorId,
                ExerciseLogType.DIE, "人工下发：判死");
    }

    /** 判活 / 复活：8B 判活帧，解除冻结。 */
    public Ack revive(int vestNum, UUID operatorId) {
        return dispatch(vestNum, "LIVE", DownlinkCommands.live(vestNum), "{}", operatorId,
                ExerciseLogType.LIVE, "人工下发：判活");
    }

    /** 判伤：8B 判伤帧。 */
    public Ack injure(int vestNum, UUID operatorId) {
        return dispatch(vestNum, "INJURE", DownlinkCommands.injure(vestNum), "{}", operatorId,
                ExerciseLogType.INJURE, "人工下发：判伤");
    }

    /**
     * 超范围预警：8B 超范围帧。
     *
     * <p><b>不写 {@code t_exercise_log}</b>：{@link ExerciseLogType} 里没有对应条目
     * （协议 {@code LogType} 是冻结区、不新增值），而超范围是提示性动作，无需进复盘日志。
     * 下发事实仍留在 {@code t_exercise_command} 队列行里（含下行原文）。</p>
     */
    public Ack outOfRange(int vestNum, UUID operatorId) {
        return dispatch(vestNum, "OUT_OF_RANGE", DownlinkCommands.outOfRange(vestNum), "{}", operatorId,
                null, null);
    }

    /**
     * 单兵充弹：11B 单兵充弹帧。
     *
     * @param weapon {@link DownlinkCommands#WEAPON_PRIMARY} / {@link DownlinkCommands#WEAPON_SECONDARY}
     */
    public Ack reload(int vestNum, int ammo, int weapon, UUID operatorId) {
        String slot = weapon == DownlinkCommands.WEAPON_SECONDARY ? "SECONDARY" : "PRIMARY";
        return dispatch(vestNum, "RELOAD", DownlinkCommands.reloadOne(vestNum, ammo, weapon),
                reloadPayloadJson(ammo, slot), operatorId, ExerciseLogType.RELOAD,
                "人工下发：单兵充弹 " + ammo + " 发（" + slot + "）");
    }

    /**
     * 全体充弹：11B 广播帧（目标字段全 {@code FF}），一次下发覆盖编成内所有马甲。
     *
     * <p>用 {@link #BROADCAST_VEST_NUM} 作为队列行的马甲编号：广播帧里没有「发给谁」这个
     * 变量，但 {@code t_exercise_command.vest_num} 是 {@code NOT NULL}，需要一个能表达
     * 「全体」而非某个真实编号的占位（真实编号下界为 1，0 不与之冲突）。</p>
     *
     * @param weapon {@link DownlinkCommands#WEAPON_PRIMARY} / {@link DownlinkCommands#WEAPON_SECONDARY}
     */
    public Ack reloadAll(int ammo, int weapon, UUID operatorId) {
        String slot = weapon == DownlinkCommands.WEAPON_SECONDARY ? "SECONDARY" : "PRIMARY";
        return broadcast("RELOAD_ALL", DownlinkCommands.reloadAll(ammo, weapon),
                reloadPayloadJson(ammo, slot), operatorId, ExerciseLogType.RELOAD,
                "人工下发：全体充弹 " + ammo + " 发（" + slot + "）");
    }

    // ------------------------------------------------------------------ 覆盖配置

    /**
     * 覆盖配置：14B 轮询配置帧（{@code 02 00 <num:2BE> <mode> <hp> <ammo1:2BE> <ammo2:2BE> <team> <sum> AF FA}）。
     *
     * <p><b>未传字段保持原值，原值取自本场想定编成</b>（用户裁定）：{@code t_plan_roster} 里该马甲那一行
     * 是服务端唯一持久化的「这个马甲该配成什么」，且恰好五字段齐全——其中 {@code mode} 在态势内存态里
     * 根本没有对应字段，若拿实时态势当原值，{@code mode} 仍要回落编成，等于两个来源混用。
     * 想定在被演习占用期间禁止改动（{@code PLAN_IN_USE}），因此编成与开演快照不会打架。</p>
     *
     * <p><b>不是即时指令</b>：帧进的是网关的轮询缓存，下一轮轮询（默认 200ms）才实际发出，
     * 此后每个周期重发——所以「覆盖」是配置语义，不是「发一次就完」。回执里的 {@code accepted}
     * 只表示这一帧有没有进网关的发送队列，不表示马甲已经按新参数运行。</p>
     *
     * <p><b>不写 {@code t_exercise_log}</b>：{@link ExerciseLogType} 没有 {@code CONFIG}
     * （协议 {@code LogType} 是冻结区、不新增值），改配置的事实留在 {@code t_exercise_command}
     * 的队列行里（含下行原文与合并后的参数快照）。</p>
     *
     * <p><b>已知缺口</b>（用户裁定单列 PLAN 任务，本段不拦）：编成里的 {@code hp} / {@code ammo1} /
     * {@code ammo2} 在库层与 PLAN 写入侧都没有范围校验，若原值越过协议字节范围（{@code hp} &gt; 255 等），
     * 组帧会按字节静默截断。真正产生脏数据的是编成写入侧，校验补在那里，而不是在指挥域给控制台数据打补丁。</p>
     */
    public Ack setVestConfig(int vestNum, ConfigPatch patch, UUID operatorId) {
        Exercise exercise = requireRunningExercise();
        RosterEntry current = rosterEntry(exercise, vestNum);

        PollMode mode = patch.mode() == null ? current.mode() : patch.mode();
        int hp = patch.hp() == null ? current.hp() : patch.hp();
        int ammo1 = patch.ammo1() == null ? current.ammo1() : patch.ammo1();
        int ammo2 = patch.ammo2() == null ? current.ammo2() : patch.ammo2();
        Team team = patch.team() == null ? current.team() : patch.team();

        return deliver(exercise, vestNum, "CONFIG",
                DownlinkCommands.pollConfig(vestNum, modeCode(mode), hp, ammo1, ammo2, team),
                configPayloadJson(mode, hp, ammo1, ammo2, team), operatorId, null, null);
    }

    // ------------------------------------------------------------------ 编排本体

    /**
     * 单兵指令的完整路径：判语境 → 判编成 → 判链路 → 发字节 → 落队列 → 记日志。
     *
     * @param auditType 审计日志类型；{@code null} 表示该指令不写 {@code t_exercise_log}
     */
    private Ack dispatch(int vestNum, String command, byte[] frame, String payloadJson,
                         UUID operatorId, ExerciseLogType auditType, String detail) {
        Exercise exercise = requireRunningExercise();
        requireInRoster(vestNum);
        return deliver(exercise, vestNum, command, frame, payloadJson, operatorId, auditType, detail);
    }

    /**
     * 全体指令的路径：判语境 → 判链路 → 发字节 → 落队列 → 记日志。
     *
     * <p><b>刻意不判编成</b>：契约 {@code reloadAllVests} 只登记 400 与 409、没有 404——
     * 「全体」本就不指向某个马甲，拿编成去卡它等于凭空造一条契约没有的失败路径。</p>
     */
    private Ack broadcast(String command, byte[] frame, String payloadJson,
                          UUID operatorId, ExerciseLogType auditType, String detail) {
        Exercise exercise = requireRunningExercise();
        return deliver(exercise, BROADCAST_VEST_NUM, command, frame, payloadJson, operatorId,
                auditType, detail);
    }

    /** 下发 + 落队列 + 记日志（{@link #dispatch} 与 {@link #broadcast} 共用的尾段）。 */
    private Ack deliver(Exercise exercise, int vestNum, String command, byte[] frame, String payloadJson,
                        UUID operatorId, ExerciseLogType auditType, String detail) {
        Instant now = clock.instant();
        String downlinkHex = Hex.encode(frame);
        if (!vestLinkOpen()) {
            return new Ack(vestNum, command, false, null, downlinkHex);
        }
        try {
            links.sendVestCommand(vestNum, frame);
        } catch (BusinessException ex) {
            if (ex.code() == ApiErrorCode.COMMAND_QUEUE_FULL) {
                return new Ack(vestNum, command, false, null, downlinkHex);
            }
            throw ex;
        }

        String operatorName = accounts.findById(operatorId).map(account -> account.username()).orElse(null);
        commands.enqueue(new ExerciseCommand(0L, exercise.id(), vestNum, command,
                ExerciseCommandState.SENT, payloadJson, downlinkHex, operatorName,
                now, null, null, null));
        if (auditType != null) {
            // 广播指令不指向具体马甲：日志的 vest_num 记 NULL（与建表注释「系统级日志为 NULL」一致）
            logs.append(new ExerciseLogEntry(0L, exercise.id(), now, auditType,
                    vestNum == BROADCAST_VEST_NUM ? null : vestNum,
                    operatorName, detail, downlinkHex));
        }
        return new Ack(vestNum, command, true, now, downlinkHex);
    }

    /**
     * 契约 {@code killVest} 的 409：当前无演习进行中。
     *
     * <p>读库（{@code t_exercise.ended_at IS NULL}）而非态势内存态——进程刚重启、自愈未收尾时
     * 内存态是空的，而库里的「未结束场次」才是现场要看见的事实。</p>
     */
    private Exercise requireRunningExercise() {
        return lifecycle.currentExercise().orElseThrow(() -> new BusinessException(
                ApiErrorCode.EXERCISE_NOT_RUNNING, "当前没有演习进行中，无法下发指令"));
    }

    /** 契约的 404：马甲不在当前编成中（与 {@code GET /situation/vests/{n}} 同口径同码）。 */
    private void requireInRoster(int vestNum) {
        situations.unit(vestNum).orElseThrow(() -> new BusinessException(
                ApiErrorCode.DEVICE_VEST_NOT_FOUND, "本场编成内没有该马甲：" + vestNum));
    }

    /**
     * 本场想定编成里该马甲那一行，取自 {@code t_plan_roster}。
     *
     * <p>404 用与态势读侧同一个码 {@code DEVICE_VEST_NOT_FOUND}：对客户端而言「这个编号不在本场编成里」
     * 只有一种含义，不该因为服务端内部走了编成表还是态势内存态而换码。想定在演习占用期间禁止改动
     * （{@code PLAN_IN_USE}），所以库里的编成与开演快照必然一致。</p>
     */
    private RosterEntry rosterEntry(Exercise exercise, int vestNum) {
        return roster.findByPlanId(exercise.planId()).stream()
                .filter(entry -> entry.vestNum() == vestNum)
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ApiErrorCode.DEVICE_VEST_NOT_FOUND, "本场编成内没有该马甲：" + vestNum));
    }

    /** {@link PollMode} → 14B 帧的 mode 字节。显式映射，不依赖枚举声明序。 */
    private static int modeCode(PollMode mode) {
        return mode == PollMode.MANUAL ? DownlinkCommands.MODE_MANUAL : DownlinkCommands.MODE_AUTO;
    }

    /**
     * 马甲串口链路是否已开。
     *
     * <p>链路未开时 {@code SerialVestLink.send} 会把帧留在无人消费的发送队列里，调用方却收到
     * 「成功」——那是假成功（用户裁定：只有链路未开/队列满算未入队）。这里在下发前先读
     * {@link DeviceLinkAdmin#snapshot()} 的 {@link LinkKind#SERIAL_VEST} 条目，链路非
     * {@link LinkState#OPEN} 即按未入队处理。</p>
     */
    private boolean vestLinkOpen() {
        return links.snapshot().stream()
                .filter(status -> status.kind() == LinkKind.SERIAL_VEST)
                .anyMatch(status -> status.state() == LinkState.OPEN);
    }

    /** 充弹参数的结构化快照（{@code t_exercise_command.payload_json}）。 */
    private static String reloadPayloadJson(int ammo, String slot) {
        ObjectNode node = JSON.createObjectNode();
        node.put("ammo", ammo);
        node.put("weapon", slot);
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("指令参数快照序列化失败", e);
        }
    }

    /**
     * 覆盖配置的<b>合并后</b>参数快照（{@code t_exercise_command.payload_json}）。
     *
     * <p>记合并结果而非原始请求：队列行的 {@code downlink_hex} 是「实际发了什么字节」，
     * 参数快照就该回答「那些字节代表什么配置」。请求里没传、由编成补上的字段若被略去，
     * 事后就无从知道当时生效的是哪一组值。</p>
     */
    private static String configPayloadJson(PollMode mode, int hp, int ammo1, int ammo2, Team team) {
        ObjectNode node = JSON.createObjectNode();
        node.put("mode", mode.name());
        node.put("hp", hp);
        node.put("ammo1", ammo1);
        node.put("ammo2", ammo2);
        node.put("team", team.name());
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("指令参数快照序列化失败", e);
        }
    }

    /**
     * 覆盖配置的补丁（api 层由 {@code VestConfigRequest} 映射而来）。
     *
     * <p>定义在 core 而不是复用 api 的 DTO：api 依赖 core，反向依赖会让编排层被迫知道 HTTP 层类型。
     * 全部字段可空，{@code null} 即「未传、保持原值」——与契约 {@code VestConfigRequest} 的可空性一一对应。</p>
     */
    public record ConfigPatch(PollMode mode, Integer hp, Integer ammo1, Integer ammo2, Team team) {
    }

    /**
     * 一次下发的受理回执（api 层映射为契约 {@code CommandAck}）。
     *
     * @param accepted    {@code true} 表示字节已下发<b>且</b>队列行已落库；{@code false} 表示
     *                    未入队（链路未开 / 队列满），此时 {@code queuedAt} 为 {@code null}
     * @param queuedAt   入队时刻；未入队时为 {@code null}
     * @param downlinkHex 组装出的下行原文。无论是否入队都返回——排障时最要紧的正是
     *                    「本来要发什么字节」这个事实
     */
    public record Ack(int vestNum, String command, boolean accepted, Instant queuedAt, String downlinkHex) {
    }
}
