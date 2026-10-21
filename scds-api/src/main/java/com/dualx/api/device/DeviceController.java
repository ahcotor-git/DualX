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
import com.dualx.api.error.NotImplementedException;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.domain.Vest;
import com.dualx.core.ledger.AnchorLedgerService;
import com.dualx.core.ledger.VestLedgerService;
import com.dualx.core.port.SerialPortInfo;
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
 * <p><b>仍是占位（501）</b>：{@code GET .../link}、{@code GET /links}、
 * {@code GET /links/serial-ports} 与串口/UWB 开关。这些是链路运行态，真值在 gateway 进程里
 * （串口与 UWB 的端口/协议硬约束也在那里），而 gateway 目前尚无源码，没有可调用的端口——
 * 现在填返回值只能造假。开关的交付范围本身还是开放问题（requirements Q-LINK-LIFECYCLE）。</p>
 *
 * <p>链路状态推送走 WebSocket {@code /ws/v1/devices/links}（见 {@code com.dualx.api.ws}），
 * 与 {@code GET /api/v1/devices/links} 构成双通道，不在本控制器内重复实现。</p>
 */
@RestController
@Validated
public class DeviceController {

    private final VestLedgerService vests;
    private final AnchorLedgerService anchors;

    public DeviceController(VestLedgerService vests, AnchorLedgerService anchors) {
        this.vests = vests;
        this.anchors = anchors;
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

    @GetMapping("/api/v1/devices/vests/{vestNum}/link")
    public VestLinkStatus getVestLink(@PathVariable int vestNum) {
        throw new NotImplementedException("getVestLink");
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
        throw new NotImplementedException("getLinks");
    }

    @GetMapping("/api/v1/devices/links/serial-ports")
    public List<SerialPortInfo> listSerialPorts() {
        throw new NotImplementedException("listSerialPorts");
    }

    @PostMapping("/api/v1/devices/links/serial")
    public LinkStatus openVestSerial(@RequestBody @Valid LinkOpenSerialRequest request) {
        throw new NotImplementedException("openVestSerial");
    }

    @DeleteMapping("/api/v1/devices/links/serial")
    public LinkStatus closeVestSerial() {
        throw new NotImplementedException("closeVestSerial");
    }

    @PostMapping("/api/v1/devices/links/uwb")
    public LinkStatus openUwbLink(@RequestBody @Valid LinkOpenUwbRequest request) {
        throw new NotImplementedException("openUwbLink");
    }

    @DeleteMapping("/api/v1/devices/links/uwb")
    public LinkStatus closeUwbLink() {
        throw new NotImplementedException("closeUwbLink");
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
}
