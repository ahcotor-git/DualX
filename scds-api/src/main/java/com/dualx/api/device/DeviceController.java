package com.dualx.api.device;

import com.dualx.api.dto.device.Anchor;
import com.dualx.api.dto.device.AnchorUpsertRequest;
import com.dualx.api.dto.device.LinkOpenSerialRequest;
import com.dualx.api.dto.device.LinkOpenUwbRequest;
import com.dualx.api.dto.device.VestDevice;
import com.dualx.api.dto.device.VestDevicePage;
import com.dualx.api.dto.device.VestDeviceUpsertRequest;
import com.dualx.api.dto.device.VestLinkStatus;
import com.dualx.api.error.NotImplementedException;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.port.SerialPortInfo;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备台账与硬件链路 REST 骨架（契约 docs/api/paths/device.yaml）。
 *
 * <p>马甲为全局台账（不再按演习重复配置），演习侧只做编排引用。链路开关统一为
 * POST/DELETE，替代旧系统 {@code /serialPort/open} 等 GET 写操作。</p>
 *
 * <p>串口与 UWB 的端口/协议硬约束（马甲串口默认 115200 波特率、协议 A 为 ASCII 文本 HEX、
 * UWB TCP Server 固定监听 12001、UWB 生效后丢弃 ff06 室内定位帧）全部归属网关层
 * （scds-gateway）实现，本层只登记路由与契约参数。</p>
 *
 * <p>链路状态推送走 WebSocket {@code /ws/v1/devices/links}（见 {@code com.dualx.api.ws}），
 * 与 {@code GET /api/v1/devices/links} 构成双通道，不在本控制器内重复实现。</p>
 */
@RestController
@Validated
public class DeviceController {

    // ── 马甲台账 ────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/devices/vests")
    public VestDevicePage listVests(@RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Team team,
                                    @RequestParam(required = false) VestStatus status,
                                    @RequestParam(required = false) Boolean bound,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        throw new NotImplementedException("listVests");
    }

    @PostMapping("/api/v1/devices/vests")
    public VestDevice createVest(@RequestBody @Valid VestDeviceUpsertRequest request) {
        throw new NotImplementedException("createVest");
    }

    @GetMapping("/api/v1/devices/vests/{vestNum}")
    public VestDevice getVest(@PathVariable int vestNum) {
        throw new NotImplementedException("getVest");
    }

    @PutMapping("/api/v1/devices/vests/{vestNum}")
    public VestDevice updateVest(@PathVariable int vestNum,
                                 @RequestBody @Valid VestDeviceUpsertRequest request) {
        throw new NotImplementedException("updateVest");
    }

    @DeleteMapping("/api/v1/devices/vests/{vestNum}")
    public void deleteVest(@PathVariable int vestNum) {
        throw new NotImplementedException("deleteVest");
    }

    // ── 单个马甲链路状态 ────────────────────────────────────────────────────

    @GetMapping("/api/v1/devices/vests/{vestNum}/link")
    public VestLinkStatus getVestLink(@PathVariable int vestNum) {
        throw new NotImplementedException("getVestLink");
    }

    // ── UWB 基站台账 ───────────────────────────────────────────────────────

    @GetMapping("/api/v1/devices/anchors")
    public List<Anchor> listAnchors() {
        throw new NotImplementedException("listAnchors");
    }

    @PutMapping("/api/v1/devices/anchors/{anchorId}")
    public Anchor upsertAnchor(@PathVariable @Min(1) @Max(8) int anchorId,
                               @RequestBody @Valid AnchorUpsertRequest request) {
        throw new NotImplementedException("upsertAnchor");
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
}
