package com.dualx.api.dto.command;

/**
 * 手动置位请求体（契约 schemas/domain.yaml → {@code MoveVestRequest}），对应旧系统
 * {@code /vest/manual-move}，仅供联调演示，正式演习禁用。
 *
 * <p>契约未声明必填与区间，故不加校验注解。</p>
 *
 * @param floorNum 目标楼层编号
 * @param posNum   目标点位编号
 */
public record MoveVestRequest(
        Integer floorNum,
        Integer posNum) {
}
