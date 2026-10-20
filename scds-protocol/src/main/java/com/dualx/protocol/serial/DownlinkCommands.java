package com.dualx.protocol.serial;

import com.dualx.protocol.Team;

/**
 * 协议 A（马甲串口）<b>下行指令</b>编码器 —— 纯字节 API。
 *
 * <p>注意方向差异：下行是<b>二进制</b>，上行是 ASCII 文本 HEX（PROTOCOL.md §2）。</p>
 *
 * <p>通用结构：{@code 命令载荷 | 校验 1B | 0xAF | 0xFA}，
 * 校验 = {@code sum(前面所有字节) & 0xFF}。</p>
 *
 * <p>与旧 {@code DataFrameUtils} 的对应关系：字节序列逐字节一致；
 * 差异仅在于入口不再是领域模型（{@code ShootingVestModel} / {@code Constant.TEAM_MAP}），
 * 而是原始数值 + {@link Team} 枚举。</p>
 *
 * <p><b>实现范围</b>：PROTOCOL.md §2 表末 3 条刺杀（Bayonet）指令不在 C 端业务范围内，
 * 本类不提供；字节定义仍完整保留在协议文档中以便将来恢复。</p>
 */
public final class DownlinkCommands {

    /** 武器槽位：主武器。 */
    public static final int WEAPON_PRIMARY = 2;
    /** 武器槽位：副武器。 */
    public static final int WEAPON_SECONDARY = 3;

    /** 轮询模式：自动。 */
    public static final int MODE_AUTO = 0;
    /** 轮询模式：手动。 */
    public static final int MODE_MANUAL = 1;

    /** 演习模式：训练。 */
    public static final int BATTLE_MODE_TRAIN = 0;
    /** 演习模式：演习。 */
    public static final int BATTLE_MODE_BATTLE = 1;

    private static final byte TAIL_0 = (byte) 0xAF;
    private static final byte TAIL_1 = (byte) 0xFA;

    private DownlinkCommands() {
    }

    /**
     * 轮询配置帧（14B）：每轮下发配置（编号 / 模式 / HP / 弹药 / 队伍）。
     *
     * <pre>02 00 &lt;num:2BE&gt; &lt;mode&gt; &lt;hp&gt; &lt;ammo1:2BE&gt; &lt;ammo2:2BE&gt; &lt;team&gt; &lt;sum&gt; AF FA</pre>
     */
    public static byte[] pollConfig(int vestNum, int mode, int hp, int ammo1, int ammo2, Team team) {
        byte[] d = new byte[14];
        d[0] = (byte) 0x02;
        d[1] = (byte) 0x00;
        d[2] = (byte) (vestNum >> 8);
        d[3] = (byte) vestNum;
        d[4] = (byte) mode;
        d[5] = (byte) hp;
        d[6] = (byte) (ammo1 >> 8);
        d[7] = (byte) ammo1;
        d[8] = (byte) (ammo2 >> 8);
        d[9] = (byte) ammo2;
        d[10] = (byte) team.code();
        return seal(d);
    }

    /** 演习开始（7B）：{@code 00 06 FF <battleMode> <sum> AF FA}。 */
    public static byte[] startBattle(int battleMode) {
        byte[] d = new byte[7];
        d[0] = (byte) 0x00;
        d[1] = (byte) 0x06;
        d[2] = (byte) 0xFF;
        d[3] = (byte) battleMode;
        return seal(d);
    }

    /** 演习结束（7B）：{@code 00 0B FF FF <sum> AF FA}。 */
    public static byte[] finishBattle() {
        byte[] d = new byte[7];
        d[0] = (byte) 0x00;
        d[1] = (byte) 0x0B;
        d[2] = (byte) 0xFF;
        d[3] = (byte) 0xFF;
        return seal(d);
    }

    /** 判死（8B）：{@code 00 09 FF <num:2BE> <sum> AF FA}。 */
    public static byte[] die(int vestNum) {
        return withVestNum((byte) 0x09, vestNum);
    }

    /** 判活 / 复活（8B）：{@code 00 08 FF <num:2BE> <sum> AF FA}。 */
    public static byte[] live(int vestNum) {
        return withVestNum((byte) 0x08, vestNum);
    }

    /** 判伤（8B）：{@code 00 A1 FF <num:2BE> <sum> AF FA}。 */
    public static byte[] injure(int vestNum) {
        return withVestNum((byte) 0xA1, vestNum);
    }

    /** 超出范围预警（8B）：{@code 00 0D FF <num:2BE> <sum> AF FA}。 */
    public static byte[] outOfRange(int vestNum) {
        return withVestNum((byte) 0x0D, vestNum);
    }

    /**
     * 全体充弹（11B）：{@code 01 14 FF FF FF <ammo:2BE> <weapon> <sum> AF FA}。
     *
     * @param ammo   弹药数
     * @param weapon {@link #WEAPON_PRIMARY} / {@link #WEAPON_SECONDARY}
     */
    public static byte[] reloadAll(int ammo, int weapon) {
        byte[] d = new byte[11];
        d[0] = (byte) 0x01;
        d[1] = (byte) 0x14;
        d[2] = (byte) 0xFF;
        d[3] = (byte) 0xFF;
        d[4] = (byte) 0xFF;
        d[5] = (byte) (ammo >> 8);
        d[6] = (byte) ammo;
        d[7] = (byte) weapon;
        return seal(d);
    }

    /**
     * 单兵充弹（11B）：{@code 01 14 FF <num:2BE> <ammo:2BE> <weapon> <sum> AF FA}。
     *
     * @param vestNum 目标马甲编号
     * @param ammo    弹药数
     * @param weapon  {@link #WEAPON_PRIMARY} / {@link #WEAPON_SECONDARY}
     */
    public static byte[] reloadOne(int vestNum, int ammo, int weapon) {
        byte[] d = new byte[11];
        d[0] = (byte) 0x01;
        d[1] = (byte) 0x14;
        d[2] = (byte) 0xFF;
        d[3] = (byte) (vestNum >> 8);
        d[4] = (byte) vestNum;
        d[5] = (byte) (ammo >> 8);
        d[6] = (byte) ammo;
        d[7] = (byte) weapon;
        return seal(d);
    }

    // ==================== 内部 ====================

    /** 构造 {@code 00 <cmd> FF <num:2BE> <sum> AF FA} 形式的 8B 单兵指令。 */
    private static byte[] withVestNum(byte cmd, int vestNum) {
        byte[] d = new byte[8];
        d[0] = (byte) 0x00;
        d[1] = cmd;
        d[2] = (byte) 0xFF;
        d[3] = (byte) (vestNum >> 8);
        d[4] = (byte) vestNum;
        return seal(d);
    }

    /**
     * 落校验位与帧尾。
     *
     * <p>与旧实现等价：旧代码先置校验位为 0，再 {@code data[ckPos] += data[i]}（i 取 0..ckPos-1），
     * 结果同为 {@code sum(data[0..ckPos-1]) & 0xFF}。此处统一按 {@code ckPos = length - 3} 处理，
     * 对 7/8/11/14B 全部成立。</p>
     */
    private static byte[] seal(byte[] d) {
        int ckPos = d.length - 3;
        int sum = 0;
        for (int i = 0; i < ckPos; i++) {
            sum += d[i] & 0xFF;
        }
        d[ckPos] = (byte) (sum & 0xFF);
        d[d.length - 2] = TAIL_0;
        d[d.length - 1] = TAIL_1;
        return d;
    }
}
