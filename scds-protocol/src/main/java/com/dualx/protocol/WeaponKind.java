package com.dualx.protocol;

/**
 * 重武器 / 载具编号段（PROTOCOL.md §5.5）。
 *
 * <p>{@code ff05} 击中帧的「开枪者/武器编号」可能落在这些区间；&lt;= 1800 视为单兵。</p>
 */
public enum WeaponKind {

    HOWITZER_35(1801, 2000, "35榴弹"),
    ROCKET_80(2201, 2400, "80火"),
    ROCKET_120(2401, 2600, "120火"),
    IFV(3001, 3200, "步战车"),
    ASSAULT_VEHICLE(3201, 3400, "突击车"),
    APC(3801, 4000, "装甲输送车"),
    HJ73(4201, 4400, "红箭73"),
    MORTAR(4401, 4600, "迫击炮"),
    FIRE_POINT(4601, 4800, "火力点"),
    MINE(ProtocolConstants.NUM_MINE, ProtocolConstants.NUM_MINE, "地雷"),
    EXPLOSIVE(ProtocolConstants.NUM_EXPLOSIVE, ProtocolConstants.NUM_EXPLOSIVE, "爆炸物"),
    GRENADE(ProtocolConstants.NUM_GRENADE, ProtocolConstants.NUM_GRENADE, "手雷");

    private final int minNum;
    private final int maxNum;
    private final String label;

    WeaponKind(int minNum, int maxNum, String label) {
        this.minNum = minNum;
        this.maxNum = maxNum;
        this.label = label;
    }

    public int minNum() {
        return minNum;
    }

    public int maxNum() {
        return maxNum;
    }

    public String label() {
        return label;
    }

    /** 编号是否属于单兵（&lt;= 1800）。 */
    public static boolean isSoldier(int num) {
        return num <= ProtocolConstants.PEOPLE_OR_OBJECT_TYPE;
    }

    /** 按编号匹配武器/载具；属于单兵或未匹配区间时返回 {@code null}。 */
    public static WeaponKind fromNum(int num) {
        if (isSoldier(num)) {
            return null;
        }
        for (WeaponKind k : values()) {
            if (num >= k.minNum && num <= k.maxNum) {
                return k;
            }
        }
        return null;
    }
}
