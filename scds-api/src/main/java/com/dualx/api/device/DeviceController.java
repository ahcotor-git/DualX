package com.dualx.api.device;

import com.dualx.api.dto.common.PageMeta;
import com.dualx.api.dto.device.Anchor;
import com.dualx.api.dto.device.AnchorUpsertRequest;
import com.dualx.api.dto.device.LinkOpenSerialRequest;
import com.dualx.api.dto.device.LinkOpenUwbRequest;
import com.dualx.api.dto.device.VestBindingRequest;
import com.dualx.api.dto.device.VestDevice;
import com.dualx.api.dto.device.VestDevicePage;
import com.dualx.api.dto.device.VestDeviceUpsertRequest;
import com.dualx.api.dto.device.VestLinkStatus;
import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.domain.Vest;
import com.dualx.core.ledger.AnchorLedgerService;
import com.dualx.core.ledger.VestLedgerService;
import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.core.port.SerialPortInfo;
import com.dualx.core.state.SituationQuery;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备台账与硬件链路 REST（契约 docs/api/paths/device.yaml）。
 *
 * <p>马甲为全局台账（不再按演习重复配置），演习侧只做编排引用。链路开关统一为
 * POST/DELETE，替代旧系统 {@code /serialPort/open} 等 GET 写操作。</p>
 *
 * <p><b>已接线</b>：马甲台账与绑定闭环走 {@link VestLedgerService}，基站台账走
 * {@link AnchorLedgerService}。本层只做契约形状的搬运（DTO ↔ 领域对象），
 * 业务判断全在服务层——把「能不能绑」写进控制器，会让同一规则在 REST 与 WS 两条入口上各写一遍。</p>
 *
 * <p><b>链路运行态</b>：{@code GET /links}、{@code GET /links/serial-ports} 与串口/UWB 开关
 * 走领域端口 {@link DeviceLinkAdmin}（实现在 {@code scds-gateway}）。真值在网关进程里——
 * 串口独占、UWB 两种接入方式互斥、端口硬约束 12001、重开冲突 409 全部由网关判定，
 * 本层只做「请求 → {@link LinkConfig}」的搬运与分流，<b>不复制任何一条链路规则</b>：
 * 在控制器里再判一次「能不能开」，会让 REST 与后续 WS 两条入口各持一份口径。</p>
 *
 * <p><b>{@code GET .../link} 已接线</b>：它返回的是<b>每马甲</b>在线态
 * （{@code lastHeartbeatAt}/{@code lastFrameType}/{@code framesTotal}），权威来源是 core 的状态机
 * （{@code SituationQuery}，设计 06 §S7），<b>不是</b>本控制器的其余链路端点所用的链路级计数——
 * 两者口径不同，不得互相冒充（设计 05 §5）。404 判据是设备台账（与 {@code getVest} 同口径）；
 * 台账有、但当前不在本场编成内（未开演 / 非本场）时回 {@code online=false} 与零计数，而非 404。</p>
 *
 * <p>链路状态推送走 WebSocket {@code /ws/v1/devices/links}（见 {@code com.dualx.api.ws}），
 * 与 {@code GET /api/v1/devices/links} 构成双通道，不在本控制器内重复实现。</p>
 */
@RestController
@Validated
public class DeviceController {

    private final VestLedgerService vests;
    private final AnchorLedgerService anchors;
    private final DeviceLinkAdmin links;
    /** 每马甲链路状态的读侧（{@code getVestLink}）：真值在 core 状态机，不在链路级快照里。 */
    private final SituationQuery situations;

    public DeviceController(VestLedgerService vests, AnchorLedgerService anchors, DeviceLinkAdmin links,
                            SituationQuery situations) {
        this.vests = vests;
        this.anchors = anchors;
        this.links = links;
        this.situations = situations;
    }

    // ── 马甲台账 ────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/devices/vests")
    public VestDevicePage listVests(@RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Team team,
                                    @RequestParam(required = false) VestStatus status,
                                    @RequestParam(required = false) Boolean bound,
                                    @RequestParam(defaultValue = "1") @Min(1) int page,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(PageRequest.MAX_SIZE) int size) {
        Page<Vest> result = vests.list(keyword, team, bound, status, PageRequest.of(page, size));
        return new VestDevicePage(result.items().stream().map(DeviceController::toDevice).toList(),
                new PageMeta(result.page(), result.size(), result.total()));
    }

    @PostMapping("/api/v1/devices/vests")
    @ResponseStatus(HttpStatus.CREATED)
    public VestDevice createVest(@RequestBody @Valid VestDeviceUpsertRequest request) {
        return toDevice(vests.create(toDraft(request)));
    }

    @GetMapping("/api/v1/devices/vests/{vestNum}")
    public VestDevice getVest(@PathVariable int vestNum) {
        return toDevice(vests.get(vestNum));
    }

    @PutMapping("/api/v1/devices/vests/{vestNum}")
    public VestDevice updateVest(@PathVariable int vestNum,
                                 @RequestBody @Valid VestDeviceUpsertRequest request) {
        return toDevice(vests.update(vestNum, toDraft(request)));
    }

    @DeleteMapping("/api/v1/devices/vests/{vestNum}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVest(@PathVariable int vestNum) {
        vests.retire(vestNum);
    }

    // ── 绑定闭环（决策 04 D03-4）────────────────────────────────────────────

    @PostMapping("/api/v1/devices/vests/{vestNum}/binding")
    @ResponseStatus(HttpStatus.CREATED)
    public VestDevice bindVest(@PathVariable int vestNum, @RequestBody @Valid VestBindingRequest request) {
        return toDevice(vests.bind(vestNum, request.secret()));
    }

    @PutMapping("/api/v1/devices/vests/{vestNum}/binding")
    public VestDevice rebindVest(@PathVariable int vestNum, @RequestBody @Valid VestBindingRequest request) {
        return toDevice(vests.rebind(vestNum, request.secret()));
    }

    @DeleteMapping("/api/v1/devices/vests/{vestNum}/binding")
    public VestDevice unbindVest(@PathVariable int vestNum) {
        return toDevice(vests.unbind(vestNum));
    }

    // ── 单个马甲链路状态 ────────────────────────────────────────────────────

    /**
     * 单个马甲的链路状态。
     *
     * <p>404 判据是**设备台账**（与 {@link #getVest(int)} 同口径）：台账里没有这个马甲，
     * 「链路」也就无从谈起。台账有、但当前不在本场编成内（未开演 / 非本场）时回
     * {@code online=false} 与零计数，而不是 404——「能不能查这个马甲」由台账决定，
     * 「它此刻在不在上报」由态势决定，两个问题不合并成同一个状态码。</p>
     */
    @GetMapping("/api/v1/devices/vests/{vestNum}/link")
    public VestLinkStatus getVestLink(@PathVariable int vestNum) {
        vests.get(vestNum);
        return situations.vestLink(vestNum)
                .map(link -> new VestLinkStatus(link.vestNum(), link.online(), link.lastHeartbeatAt(),
                        link.lastFrameType(), link.framesTotal(), link.droppedTotal()))
                .orElseGet(() -> new VestLinkStatus(vestNum, false, null, null, 0L, 0L));
    }

    // ── UWB 基站台账 ───────────────────────────────────────────────────────

    @GetMapping("/api/v1/devices/anchors")
    public List<Anchor> listAnchors() {
        return anchors.list().stream().map(DeviceController::toAnchor).toList();
    }

    @PutMapping("/api/v1/devices/anchors/{anchorId}")
    public Anchor upsertAnchor(@PathVariable @Min(1) @Max(8) int anchorId,
                               @RequestBody @Valid AnchorUpsertRequest request) {
        return toAnchor(anchors.upsert(anchorId, new AnchorLedgerService.AnchorDraft(
                request.name(), request.xPx(), request.yPx(), request.enabled())));
    }

    // ── 链路状态与开关 ─────────────────────────────────────────────────────

    @GetMapping("/api/v1/devices/links")
    public List<LinkStatus> getLinks() {
        return links.snapshot();
    }

    @GetMapping("/api/v1/devices/links/serial-ports")
    public List<SerialPortInfo> listSerialPorts() {
        return links.listSerialPorts();
    }

    @PostMapping("/api/v1/devices/links/serial")
    public LinkStatus openVestSerial(@RequestBody @Valid LinkOpenSerialRequest request) {
        return links.openSerial(toSerialConfig(request));
    }

    @DeleteMapping("/api/v1/devices/links/serial")
    public LinkStatus closeVestSerial() {
        return links.closeSerial();
    }

    @PostMapping("/api/v1/devices/links/uwb")
    public LinkStatus openUwbLink(@RequestBody @Valid LinkOpenUwbRequest request) {
        // 只做分流：两种接入方式互斥（另一种已开 → 409 DEVICE_LINK_ALREADY_OPEN）与
        // 端口硬约束 12001（给了别的端口 → 400 VALIDATION_FAILED）都由网关判定。
        // SERIAL 缺 serialPortName 同样由网关折成 400，本层不复制这条校验。
        return switch (request.dataSource()) {
            case TCP_SERVER -> links.openUwbTcpServer(toUwbTcpConfig(request));
            case SERIAL -> links.openUwbSerial(toUwbSerialConfig(request));
        };
    }

    @DeleteMapping("/api/v1/devices/links/uwb")
    public LinkStatus closeUwbLink() {
        // 契约的关闭端点不带 dataSource，而两种 UWB 模式互斥（至多一条处于 OPENING/OPEN）。
        // 因此取当前 active 的那条关闭并返回；两条都未开时按 TCP_SERVER 返回 CLOSED 状态。
        // 刻意不去猜「上次是哪条」——运行态本就只有一条能开，猜需要额外状态且不改变结果。
        return isSerialActive() ? links.closeUwbSerial() : links.closeUwbTcpServer();
    }

    // ── 契约形状的搬运 ──────────────────────────────────────────────────────

    private static VestDevice toDevice(Vest vest) {
        return new VestDevice(vest.vestNum(), vest.name(), vest.team(), vest.simNo(), vest.model(),
                vest.firmwareVersion(), vest.remark(), vest.bound(), vest.enabled(), vest.lastSeenAt(),
                vest.createdAt(), vest.updatedAt());
    }

    private static VestLedgerService.VestDraft toDraft(VestDeviceUpsertRequest request) {
        return new VestLedgerService.VestDraft(request.vestNum(), request.name(), request.team(),
                request.model(), request.remark());
    }

    /** 领域 {@code Anchor} 带创建/修改时刻，契约的 {@code Anchor} 不带——只搬契约里有的字段。 */
    private static Anchor toAnchor(com.dualx.core.domain.Anchor anchor) {
        return new Anchor(anchor.anchorId(), anchor.name(), anchor.xPx(), anchor.yPx(), anchor.enabled());
    }

    /**
     * 串口开关请求 → 链路配置。
     *
     * <p>{@code enabled=false} 刻意写死：这是「本次 HTTP 手动开链路」，与「按
     * {@code t_link_config.enabled} 开机自动打开」是两件事；{@code createdAt/updatedAt}
     * 留空同理——运行态开关不落库，链路配置的持久化归配置接口，不归开关端点。</p>
     */
    private static LinkConfig toSerialConfig(LinkOpenSerialRequest request) {
        return new LinkConfig(LinkKind.SERIAL_VEST, request.portName(), request.baudRate(),
                Boolean.TRUE.equals(request.flowControlRtsCts()), null, null, false, null, null, null);
    }

    /** UWB TCP_SERVER：{@code host/port} 可空（网关按 0.0.0.0 / 12001 兜底），端口硬约束由网关判。 */
    private static LinkConfig toUwbTcpConfig(LinkOpenUwbRequest request) {
        return new LinkConfig(LinkKind.UWB_TCP_SERVER, null, null, false,
                request.host(), request.port(), false, null, null, null);
    }

    /** UWB SERIAL：只用 {@code serialPortName}；波特率走网关默认 460800（与马甲串口 115200 不同）。 */
    private static LinkConfig toUwbSerialConfig(LinkOpenUwbRequest request) {
        return new LinkConfig(LinkKind.UWB_SERIAL, request.serialPortName(), null, false,
                null, null, false, null, null, null);
    }

    /**
     * 当前是否 UWB 串口模式处于 {@code OPENING}/{@code OPEN}。
     *
     * <p>{@code ERROR} 不计入 active——与 {@code LinkSlot.active()} 同一判据，
     * 否则「打开失败留下的 ERROR」会被误判成「正在用串口模式」。</p>
     */
    private boolean isSerialActive() {
        return links.snapshot().stream()
                .anyMatch(status -> status.kind() == LinkKind.UWB_SERIAL
                        && (status.state() == LinkState.OPENING || status.state() == LinkState.OPEN));
    }
}
