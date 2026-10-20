package com.dualx.api.command;

import com.dualx.api.dto.command.CommandAck;
import com.dualx.api.dto.command.MoveVestRequest;
import com.dualx.api.dto.command.ReloadRequest;
import com.dualx.api.dto.command.VestConfigRequest;
import com.dualx.api.error.NotImplementedException;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 指挥指令下发入口（契约 paths/command.yaml，8 个写操作）。
 *
 * <p>全部指令行 POST/PUT，响应统一为 {@link CommandAck}；路径不与类级前缀拼装，
 * 每个方法写完整绝对路径，便于与 openapi.yaml 逐条对照。骨架期一律抛
 * {@link NotImplementedException}，由全局异常出口转成 501。</p>
 */
@RestController
public class CommandController {

    /** 全体充弹：对当前编成内所有马甲下发 11B 充弹帧。 */
    @PostMapping("/api/v1/command/reload-all")
    public CommandAck reloadAllVests(@RequestBody @Valid ReloadRequest request) {
        throw new NotImplementedException("reloadAllVests");
    }

    /** 判死：下发 8B 判死帧，阵亡后坐标冻结。 */
    @PostMapping("/api/v1/command/vests/{vestNum}/kill")
    public CommandAck killVest(@PathVariable int vestNum) {
        throw new NotImplementedException("killVest");
    }

    /** 判活 / 复活：下发 8B 判活帧，解除冻结。 */
    @PostMapping("/api/v1/command/vests/{vestNum}/revive")
    public CommandAck reviveVest(@PathVariable int vestNum) {
        throw new NotImplementedException("reviveVest");
    }

    /** 判伤：下发 8B 判伤帧。 */
    @PostMapping("/api/v1/command/vests/{vestNum}/injure")
    public CommandAck injureVest(@PathVariable int vestNum) {
        throw new NotImplementedException("injureVest");
    }

    /** 超范围预警：下发 8B 超范围帧，提示单兵脱离演习区域。 */
    @PostMapping("/api/v1/command/vests/{vestNum}/out-of-range")
    public CommandAck outOfRangeVest(@PathVariable int vestNum) {
        throw new NotImplementedException("outOfRangeVest");
    }

    /** 单兵充弹：下发 11B 单兵充弹帧。 */
    @PostMapping("/api/v1/command/vests/{vestNum}/reload")
    public CommandAck reloadVest(@PathVariable int vestNum, @RequestBody @Valid ReloadRequest request) {
        throw new NotImplementedException("reloadVest");
    }

    /** 修改马甲运行参数（队伍 / HP / 弹药 / 轮询模式），覆盖配置语义。 */
    @PutMapping("/api/v1/command/vests/{vestNum}/config")
    public CommandAck setVestConfig(@PathVariable int vestNum, @RequestBody @Valid VestConfigRequest request) {
        throw new NotImplementedException("setVestConfig");
    }

    /** 手动置位（联调专用）：直接把马甲坐标置为指定楼层/点位。 */
    @PostMapping("/api/v1/command/vests/{vestNum}/move")
    public CommandAck moveVest(@PathVariable int vestNum, @RequestBody @Valid MoveVestRequest request) {
        throw new NotImplementedException("moveVest");
    }
}
