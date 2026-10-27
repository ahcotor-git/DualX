package com.dualx.core.command;

import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.ExerciseCommand;
import com.dualx.core.domain.ExerciseCommandState;
import com.dualx.core.domain.ExerciseLogEntry;
import com.dualx.core.domain.ExerciseLogType;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.exercise.ExerciseLifecycleService;
import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.core.repository.AccountRepository;
import com.dualx.core.repository.ExerciseCommandRepository;
import com.dualx.core.repository.ExerciseLogRepository;
import com.dualx.core.state.SituationQuery;
import com.dualx.protocol.Hex;
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
 * <p><b>本段只做 5 条即时指令</b>：判死 / 判活 / 判伤 / 超范围 / 单兵充弹。全体充弹与覆盖配置
 * （{@code setVestConfig}）属 M3 的第二段，手动置位依赖地图置位写入口、另行单列任务。</p>
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

    /** core 侧不依赖 Spring 注入的 ObjectMapper（与 {@code MapLedgerService} 同一范式）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExerciseLifecycleService lifecycle;
    private final SituationQuery situations;
    private final DeviceLinkAdmin links;
    private final ExerciseCommandRepository commands;
    private final ExerciseLogRepository logs;
    private final AccountRepository accounts;
    private final Clock clock;

    public CommandService(ExerciseLifecycleService lifecycle,
                          SituationQuery situations,
                          DeviceLinkAdmin links,
                          ExerciseCommandRepository commands,
                          ExerciseLogRepository logs,
                          AccountRepository accounts,
                          Clock clock) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.situations = Objects.requireNonNull(situations, "situations");
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

    // ------------------------------------------------------------------ 编排本体

    /**
     * 一次下发的完整路径：判语境 → 判编成 → 判链路 → 发字节 → 落队列 → 记日志。
     *
     * @param auditType 审计日志类型；{@code null} 表示该指令不写 {@code t_exercise_log}
     */
    private Ack dispatch(int vestNum, String command, byte[] frame, String payloadJson,
                         UUID operatorId, ExerciseLogType auditType, String detail) {
        Exercise exercise = requireRunningExercise();
        requireInRoster(vestNum);

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
            logs.append(new ExerciseLogEntry(0L, exercise.id(), now, auditType, vestNum,
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
