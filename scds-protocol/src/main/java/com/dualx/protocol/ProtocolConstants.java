package com.dualx.protocol;

/**
 * 协议与链路常量。
 *
 * <p>数值来源：PROTOCOL.md §1.1 / §3.2 / §5 / §6。此处是**唯一出处**，业务层不得再散落硬编码。</p>
 */
public final class ProtocolConstants {

    private ProtocolConstants() {
    }

    // ===== 硬约束端口（违反即事故） =====
    /** HTTP 业务端口。 */
    public static final int HTTP_PORT = 11001;
    /** UWB TCP Server 监听端口。 */
    public static final int UWB_TCP_SERVER_PORT = 12001;

    // ===== 协议 A：马甲串口链路参数（§1.1） =====
    public static final String SERIAL_DEFAULT_PORT_NAME = "COM7";
    public static final int SERIAL_BAUD_RATE = 115200;
    public static final boolean SERIAL_FLOW_CONTROL_RTS_CTS = false;
    /** 轮询周期（ms）。 */
    public static final int POLLING_INTERVAL_MS = 200;
    public static final int POLLING_TIMEOUT_MS = 200;
    public static final int SEND_QUEUE_LIMIT = 10;
    public static final int RECEIVE_RETRY_LIMIT = 10;

    // ===== 协议 B：UWB（§3.2 / §3.1） =====
    public static final int UWB_FRAME_LEN = 41;
    public static final String UWB_TCP_SERVER_HOST = "0.0.0.0";
    public static final int UWB_TCP_READ_TIMEOUT_MS = 20000;
    public static final int UWB_TCP_IDLE_WARN_MINUTES = 3;

    // ===== §5.1 马甲在线判定 =====
    /** 心跳窗口：now - lastHeartbeatMs &lt;= 5000ms 判为在线。 */
    public static final long HEARTBEAT_ONLINE_WINDOW_MS = 5000L;

    // ===== §5.5 单兵 / 武器分界 =====
    /** 编号 &gt; 1800 视为武器/载具，&lt;= 1800 视为单兵。 */
    public static final int PEOPLE_OR_OBJECT_TYPE = 1800;
    /** 手雷（0xFFFF）。 */
    public static final int NUM_GRENADE = 65535;
    /** 爆炸物（0xFFFE）。 */
    public static final int NUM_EXPLOSIVE = 65534;
    /** 地雷（0xFFFD）。 */
    public static final int NUM_MINE = 65533;

    // ===== §5.6 战斗数值 =====
    /** 迫击炮致死半径（米）。 */
    public static final int MORTAR_DEAD_DISTANCE_M = 20;
    /** 串口接收缓冲。 */
    public static final int BUFFER_SIZE = 5000;
    /** 高优先级指令（判死等）。 */
    public static final int HIGH_PRIORITY = 0;

    // ===== 协议 A 下行通用帧尾（§2.1） =====
    public static final byte FRAME_TAIL_0 = (byte) 0xAF;
    public static final byte FRAME_TAIL_1 = (byte) 0xFA;
}
