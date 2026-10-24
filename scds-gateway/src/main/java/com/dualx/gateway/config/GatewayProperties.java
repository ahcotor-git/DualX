package com.dualx.gateway.config;

import com.dualx.protocol.ProtocolConstants;

/**
 * 网关配置（设计 docs/design/05-hw-link.md §4.6，D10 口径）。前缀 {@code scds.gateway.*}，
 * 字段名即 yml 键名。
 *
 * <p><b>为什么是可变 JavaBean 而不是 {@code record}</b>：scds-gateway 刻意保持零 Spring 依赖
 * （见模块 pom 说明；同类做法见 scds-solver 的 {@code ScdsUwbProperties}），因此本类不挂
 * {@code @ConfigurationProperties}。绑定由装配层用
 * {@code @Bean @ConfigurationProperties("scds.gateway")} 完成，而那条路径走 JavaBean 的
 * setter，record 绑不上。</p>
 *
 * <p><b>⚠ D10 未闭合声明</b>：本类所有容量与线程数都是<b>设计候选值</b>，不是容量结论。
 * 现场人数 / 基站数 / 客户端数 / 帧率 / SLA 与备份 RPO/RTO 未给出前，
 * <b>不得据本类默认值宣称容量达标</b>；仿真模式只能证明链路与端点接线正确，
 * 不能替代现场对拍。</p>
 *
 * <p><b>已生效 vs 未接入</b>：{@code serial.send-queue-capacity} 由装配层传给
 * {@code SerialVestLink} 构造器，改它即改实际队列；{@code uwb.tcp-backlog} 与
 * {@code uwb.worker-threads} 目前<b>尚未接入</b> Netty 启动参数（见各自字段说明），
 * 待 D10 容量验证后再接，避免把未验证值写进启动路径。</p>
 */
public class GatewayProperties {

    /** 运行模式：{@code real} 用真硬件链路，{@code simulator} 用进程内仿真链路。 */
    public enum Mode {
        /** 生产默认：真串口 + 真 Netty 监听。 */
        REAL,
        /** 仅供开发与自动测试；**生产必须显式切回 {@link #REAL}**。 */
        SIMULATOR
    }

    private Mode mode = Mode.REAL;

    private final Serial serial = new Serial();

    private final Uwb uwb = new Uwb();

    private boolean autoOpenOnStartup = false;

    /** 仿真模式判定：装配层据此在真链路与仿真链路之间二选一。 */
    public boolean isSimulator() {
        return mode == Mode.SIMULATOR;
    }

    public Mode getMode() {
        return mode;
    }

    /** 未配置（yml 里写空值）时落到 {@link Mode#REAL}：漏配必须是「走硬件」，不能静默进仿真。 */
    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.REAL : mode;
    }

    /** 是否按 {@code t_link_config.enabled} 在启动时自动开链路；与 HTTP 手动开链路并存。 */
    public boolean isAutoOpenOnStartup() {
        return autoOpenOnStartup;
    }

    public void setAutoOpenOnStartup(boolean autoOpenOnStartup) {
        this.autoOpenOnStartup = autoOpenOnStartup;
    }

    public Serial getSerial() {
        return serial;
    }

    public Uwb getUwb() {
        return uwb;
    }

    /** 马甲串口链路（协议 A）容量项。 */
    public static class Serial {

        /** 发送队列溢出策略。 */
        public enum Overflow {
            /** 队列满即拒绝并把失败返回调用方（默认）：判死/判活/充弹这类指令丢一条就是现场事故。 */
            REJECT,
            /** 队列满时丢轮询、状态类低优先帧，给高优先指令让路。 */
            DROP_LOW_PRIORITY
        }

        private int sendQueueCapacity = ProtocolConstants.SEND_QUEUE_LIMIT;

        private Overflow sendQueueOverflow = Overflow.REJECT;

        /**
         * 发送队列容量。默认沿用旧系统上限（{@link ProtocolConstants#SEND_QUEUE_LIMIT}），
         * <b>不是生产容量结论</b>。
         */
        public int getSendQueueCapacity() {
            return sendQueueCapacity;
        }

        /** 小于 1 直接启动失败：静默改成一个更小的值会让现场看到的队列行为与配置不符。 */
        public void setSendQueueCapacity(int sendQueueCapacity) {
            if (sendQueueCapacity < 1) {
                throw new IllegalArgumentException(
                        "scds.gateway.serial.send-queue-capacity 必须 >= 1，实际 " + sendQueueCapacity);
            }
            this.sendQueueCapacity = sendQueueCapacity;
        }

        public Overflow getSendQueueOverflow() {
            return sendQueueOverflow;
        }

        /** {@link Overflow#REJECT} 与 {@link Overflow#DROP_LOW_PRIORITY} 的取舍需现场操作口径确认（§6 待裁定）。 */
        public void setSendQueueOverflow(Overflow sendQueueOverflow) {
            this.sendQueueOverflow = sendQueueOverflow == null ? Overflow.REJECT : sendQueueOverflow;
        }
    }

    /** UWB 链路（协议 B）容量项。 */
    public static class Uwb {

        private int tcpBacklog = 4;

        private int workerThreads = 0;

        /**
         * Netty 监听积压队列长度。默认 4：只有一个数据源，不需要大 backlog。
         *
         * <p>⚠ 目前<b>尚未接入</b> {@code UwbTcpServerLink} 的启动参数（它仍用固定的 4）。</p>
         */
        public int getTcpBacklog() {
            return tcpBacklog;
        }

        public void setTcpBacklog(int tcpBacklog) {
            if (tcpBacklog < 1) {
                throw new IllegalArgumentException("scds.gateway.uwb.tcp-backlog 必须 >= 1，实际 " + tcpBacklog);
            }
            this.tcpBacklog = tcpBacklog;
        }

        /**
         * Netty worker 线程数；0 表示用 Netty 默认（约 2 × CPU）。
         *
         * <p>⚠ 目前<b>尚未接入</b> {@code UwbTcpServerLink} 的启动参数（它仍用 Netty 默认值）；
         * 且本工作包不引入虚拟线程（§4.2），线程选型需 D10 实测后再定。</p>
         */
        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            if (workerThreads < 0) {
                throw new IllegalArgumentException("scds.gateway.uwb.worker-threads 必须 >= 0，实际 " + workerThreads);
            }
            this.workerThreads = workerThreads;
        }
    }
}
