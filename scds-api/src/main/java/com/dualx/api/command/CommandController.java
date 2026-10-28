package com.dualx.api.command;

import com.dualx.api.dto.command.CommandAck;
import com.dualx.api.dto.command.MoveVestRequest;
import com.dualx.api.dto.command.ReloadRequest;
import com.dualx.api.dto.command.VestConfigRequest;
import com.dualx.api.dto.command.WeaponSlot;
import com.dualx.core.command.CommandService;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.protocol.serial.DownlinkCommands;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 指挥指令下发入口（契约 paths/command.yaml，8 个写操作）。
 *
 * <p>全部指令行 POST/PUT，响应统一为 {@link CommandAck}；路径不与类级前缀拼装，
 * 每个方法写完整绝对路径，便于与 openapi.yaml 逐条对照。</p>
 *
 * <p><b>已落地 8 条</b>（判死 / 判活 / 判伤 / 超范围 / 单兵充弹 / 全体充弹 / 覆盖配置 / 手动置位），
 * 编排在 {@link CommandService}。</p>
 *
 * <p>失败不经控制器判定：语境（无演习 → 409）、编成（马甲不在编成 → 404）与下行失败都在
 * {@link CommandService} 里抛 {@link BusinessException}，由全局异常出口统一映射——控制器里再判
 * 一次会让 REST 与其它入口各持一份口径。</p>
 */
@RestController
@Validated
public class CommandController {

    private final CommandService commands;

    public CommandController(CommandService commands) {
        this.commands = commands;
    }

    /** 全体充弹：对当前编成内所有马甲下发 11B 充弹帧。 */
    @PostMapping("/api/v1/command/reload-all")
    public CommandAck reloadAllVests(@RequestBody @Valid ReloadRequest request,
                                     @AuthenticationPrincipal Jwt jwt) {
        int weapon = request.weapon() == WeaponSlot.SECONDARY
                ? DownlinkCommands.WEAPON_SECONDARY : DownlinkCommands.WEAPON_PRIMARY;
        return toAck(commands.reloadAll(request.ammo(), weapon, operatorId(jwt)));
    }

    /** 判死：下发 8B 判死帧，阵亡后坐标冻结。 */
    @PostMapping("/api/v1/command/vests/{vestNum}/kill")
    public CommandAck killVest(@PathVariable int vestNum, @AuthenticationPrincipal Jwt jwt) {
        return toAck(commands.kill(vestNum, operatorId(jwt)));
    }

    /** 判活 / 复活：下发 8B 判活帧，解除冻结。 */
    @PostMapping("/api/v1/command/vests/{vestNum}/revive")
    public CommandAck reviveVest(@PathVariable int vestNum, @AuthenticationPrincipal Jwt jwt) {
        return toAck(commands.revive(vestNum, operatorId(jwt)));
    }

    /** 判伤：下发 8B 判伤帧。 */
    @PostMapping("/api/v1/command/vests/{vestNum}/injure")
    public CommandAck injureVest(@PathVariable int vestNum, @AuthenticationPrincipal Jwt jwt) {
        return toAck(commands.injure(vestNum, operatorId(jwt)));
    }

    /** 超范围预警：下发 8B 超范围帧，提示单兵脱离演习区域。 */
    @PostMapping("/api/v1/command/vests/{vestNum}/out-of-range")
    public CommandAck outOfRangeVest(@PathVariable int vestNum, @AuthenticationPrincipal Jwt jwt) {
        return toAck(commands.outOfRange(vestNum, operatorId(jwt)));
    }

    /** 单兵充弹：下发 11B 单兵充弹帧。 */
    @PostMapping("/api/v1/command/vests/{vestNum}/reload")
    public CommandAck reloadVest(@PathVariable int vestNum,
                                 @RequestBody @Valid ReloadRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        int weapon = request.weapon() == WeaponSlot.SECONDARY
                ? DownlinkCommands.WEAPON_SECONDARY : DownlinkCommands.WEAPON_PRIMARY;
        return toAck(commands.reload(vestNum, request.ammo(), weapon, operatorId(jwt)));
    }

    /** 修改马甲运行参数（队伍 / HP / 弹药 / 轮询模式），覆盖配置语义。 */
    @PutMapping("/api/v1/command/vests/{vestNum}/config")
    public CommandAck setVestConfig(@PathVariable int vestNum,
                                    @RequestBody @Valid VestConfigRequest request,
                                    @AuthenticationPrincipal Jwt jwt) {
        return toAck(commands.setVestConfig(vestNum,
                new CommandService.ConfigPatch(request.mode(), request.hp(), request.ammo1(),
                        request.ammo2(), request.team()),
                operatorId(jwt)));
    }

    /**
     * 手动置位（联调专用）：把马甲坐标置为本场底图指定楼层/点位表里的那个点。
     *
     * <p>正式演习中由 {@link CommandService} 按本场模式拒绝（403 {@code COMMAND_MOVE_FORBIDDEN}），
     * 控制器只做 DTO 搬运。</p>
     */
    @PostMapping("/api/v1/command/vests/{vestNum}/move")
    public CommandAck moveVest(@PathVariable int vestNum, @RequestBody @Valid MoveVestRequest request) {
        return toAck(commands.moveVest(vestNum, request.floorNum(), request.posNum()));
    }

    private static CommandAck toAck(CommandService.Ack ack) {
        return new CommandAck(ack.vestNum(), ack.command(), ack.accepted(), ack.queuedAt(),
                ack.downlinkHex());
    }

    /**
     * 从已认证令牌取操作者账号主键，用于落 {@code operator_name}。
     *
     * <p>{@code sub} 必须能还原成账号 UUID：本实例签发的账号令牌里它就是 {@code accountId}。
     * 解析不出来说明令牌不是本实例签发的账号令牌——归为令牌不成立（401），不当作服务端缺陷。
     * 与 {@code AuthController.accountId} 同口径；那边是包私有静态方法，跨包不可直接调用。</p>
     */
    private static UUID operatorId(Jwt jwt) {
        String subject = jwt == null ? null : jwt.getSubject();
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BusinessException(ApiErrorCode.AUTH_TOKEN_INVALID, "令牌主体不是有效账号");
        }
    }
}
