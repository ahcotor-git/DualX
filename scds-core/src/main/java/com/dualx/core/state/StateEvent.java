package com.dualx.core.state;

import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.protocol.serial.SerialFrame;
import com.dualx.protocol.uwb.UwbFrame;

import java.time.Instant;

/**
 * 态势状态机的事件：一条「已经发生的事实」，进入 {@link StateEventQueue} 后由**唯一消费者**
 * 按到达顺序串行处理（设计 06 §4.1）。
 *
 * <p>为什么要把『链路状态变化』和『扫描节拍』也算事件：它们与上行帧争抢的是同一份内存态
 * （在线判定、阵亡冻结、revision）。若它们绕开队列各写各的，就回到了本工作包要消灭的
 * 「多处状态所有者」。</p>
 *
 * <p>{@link #droppable()} 只描述「队列溢出时能否丢这一条」，不是业务优先级：
 * 判据是**下一帧会不会重报同一事实**。因此只有周期性状态刷新才是 true——丢一条心跳，
 * 200ms 后同一马甲会重报同样的 hp/弹药；而命中等离散事件丢了就永久缺失。</p>
 */
public sealed interface StateEvent
        permits StateEvent.Serial, StateEvent.Uwb, StateEvent.LinkChanged, StateEvent.OfflineScan,
                StateEvent.ManualPosition {

    /** 队列满时是否可以丢这一条（见类型注释：唯一判据是「下一帧会重报」）。 */
    boolean droppable();

    /** 一条协议 A 上行帧（心跳 / GPS / 命中 / 室内定位 / 迫击炮 / SIM）。 */
    record Serial(SerialFrame frame, LinkKind source) implements StateEvent {

        @Override
        public boolean droppable() {
            // 心跳是纯状态刷新且 200ms 一轮（PROTOCOL.md §5.2 轮询周期），可丢；
            // 命中、GPS、SIM 等离散事实一律不可丢。
            return frame instanceof SerialFrame.Heartbeat;
        }
    }

    /** 一条 UWB 二进制帧（41B，8 路测距）。坐标解算结果不可重来，故不可丢。 */
    record Uwb(UwbFrame frame, LinkKind source) implements StateEvent {

        @Override
        public boolean droppable() {
            return false;
        }
    }

    /** 链路开关状态变化（OPENING / OPEN / CLOSED / ERROR）。 */
    record LinkChanged(LinkKind kind, LinkState state, String message) implements StateEvent {

        @Override
        public boolean droppable() {
            return false;
        }
    }

    /**
     * 在线扫描节拍：把「距最后心跳超过 5000ms」变成一次**离散跳变**，而不是读取时惰性判定
     * （设计 06 §4.2；轨迹里必须留下离线时刻）。
     */
    record OfflineScan(Instant at) implements StateEvent {

        @Override
        public boolean droppable() {
            // 扫描是幂等的（下一拍会重算），但丢它会让离线跳变推迟一个周期，收益不抵代价。
            return false;
        }
    }

    /**
     * 手动置位（COMMAND {@code moveVest}，联调专用）：操作员把某马甲的坐标直接置为指定楼层的点位。
     *
     * <p>它不是硬件上报（坐标来自本场底图的点位表，由 COMMAND 的编排层查好后传进来），但争抢的
     * 仍是同一份内存态（坐标、revision、轨迹采样、下行推送），因此照样走这条队列、由同一个写手
     * 线程处理——操作员直写就会多出一个状态所有者（设计 06 §4.1）。</p>
     *
     * <p><b>不可丢</b>：人工置位是一次显式动作，不像心跳那样 200ms 后重报；丢了它，操作员已经拿到
     * 200 受理回执，客户端却永远看不到这次跳变。</p>
     *
     * @param floorNum 楼层号（来自请求，与点位表同层）
     * @param posNum   楼层内点位号
     * @param xPct     底图归一化横坐标 0~1（原点左上，点位表原值）
     * @param yPct     底图归一化纵坐标 0~1
     */
    record ManualPosition(int vestNum, int floorNum, int posNum, double xPct, double yPct)
            implements StateEvent {

        @Override
        public boolean droppable() {
            return false;
        }
    }
}
