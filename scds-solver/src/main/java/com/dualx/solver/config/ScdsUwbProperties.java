package com.dualx.solver.config;

/**
 * 迁移说明（scds-next）：原文件为 Spring {@code @ConfigurationProperties(prefix = "scds.uwb")}。
 * 本模块（scds-solver）刻意保持零 Spring 依赖，故移除注解与 import；
 * 绑定前缀 "scds.uwb" 由 scds-core 通过
 * {@code @Bean @ConfigurationProperties("scds.uwb")} 恢复，绑定语义不变。
 * 除上述改动外，本类所有字段、默认值、setter 夹取逻辑与旧版逐字节一致。
 */
public class ScdsUwbProperties {

    public enum UwbDataSource { SERIAL, TCP_SERVER }

    private boolean mode = false;

    private UwbDataSource dataSource = UwbDataSource.SERIAL;

    private String tcpServerHost = "0.0.0.0";

    private int tcpServerPort = 12001;

    private int tcpReadTimeoutMs = 20000;

    private int tcpIdleWarnMin = 3;

    private int maxBsCount = 8;

    /**
     * 20260908 方案B：UWB UPDATED 触发 WebSocket 推送前的合并最小间隔(毫秒)
     * UWB 硬件 50ms 一帧(20Hz)，连续的 UPDATED 会合并为 每 pushIntervalMs 一次 notice() 广播
     *   默认 125ms = 8 次/秒（跟 50ms/帧 对齐且不会打爆 CPU/网卡）
     *   现场可改 application.yml：scds.uwb.push-interval-ms: 250（4Hz/省CPU）或 62（16Hz/更实时）
     */
    private long pushIntervalMs = 125L;

    /**
     * 20260914 后端解算策略：UwbSolver.solve2D() 滤波参数（搬前端 createUwbFilter 1:1）
     * 所有参数均可 application.yml 热改
     */
    private double residualMaxMeters = 2.0d;
    private double jumpMaxMps = 6.0d;
    private long lostTimeoutMs = 5000L;
    private int recoverMinFrames = 2;
    /** 矩形BS1234边界容差(米)：默认 0.1m = 10cm ≈ UWB测距本身±10cm 误差 */
    private double rectToleranceM = 0.1d;
    /** 是否严格 REJECT 矩形外候选点；false=边界外0.15m内裁剪到边框 */
    private boolean rectStrictReject = false;
    /** 后端兜底 solve 触发周期(ms)：即使 UNCHANGED 帧也刷新 filter LOST 超时 */
    private long solveIntervalMs = 62L;
    /** 基站测距有效范围阈值下限(米) */
    private double bsValidMinMeters = 0.05d;
    /** 基站测距有效范围阈值上限(米) */
    private double bsValidMaxMeters = 50.0d;

    /**
     * @deprecated 20260915 去锁纯队列模式 LEGACY：字段仅为yml绑定兼容保留，代码不再读取使用，改值无任何效果
     * 20260914 静止防抖（方案A滞回死区）：静止判定死区（厘米，默认20cm = 现场多路径反射噪声典型上限
     *    ≤ 此值 → 认为是"原地抖动"，锁在"静止锚点"不更新，马甲完全不动
     *    现场根据基站/马甲状态：
     *      噪声大/多路径反射严重 → 25cm
     *      普通演习默认 → 20cm
     *      要求高精度（近距离射击）→ 10cm 或 5cm
     */
    @Deprecated
    private double staticDeadbandCm = 20.0d;
    /**
     * @deprecated 20260915 去锁纯队列模式 LEGACY：字段仅为yml绑定兼容保留，代码不再读取使用，改值无任何效果
     * 静止防抖释放因子（倍，默认1.5）：实际位移 > max(deadband × releaseFactor, deadband + staticReleaseMinOffsetCm) 立即解锁直通
     *    1.5 = 默认保守值；和 staticReleaseMinOffsetCm=15cm 取"两者较大"，保证死区20cm时最小释放阈值=35cm（不被31~34cm伪峰解锁）
     */
    @Deprecated
    private double staticReleaseFactor = 1.5d;
    /**
     * @deprecated 20260915 去锁纯队列模式 LEGACY：字段仅为yml绑定兼容保留，代码不再读取使用，改值无任何效果
     * 20260914 V4 静止防抖释放硬下限（厘米，默认15cm）：
     *   releaseCm = max(db × releaseFactor, db + minOffsetCm)。
     *   解决：releaseFactor=1.5 db=20cm 理论释放30cm，但硬件测距伪峰常在31~34cm刚好解锁造成"跳一下又回来"；
     *   加15cm硬下限保证 db=20cm 最小释放=35cm，伪峰区间(31~34cm)全落灰区不解锁。
     */
    @Deprecated
    private double staticReleaseMinOffsetCm = 15.0d;
    /**
     * @deprecated 20260915 去锁纯队列模式 LEGACY：字段仅为yml绑定兼容保留，代码不再读取使用，改值无任何效果
     * 静止防抖重新上锁帧数（V4默认 2 帧 ≈ 125ms）：连续 N 帧都在死区内 → 认为已停下，重新锁锚点
     *    2 = 被伪峰误解锁后快速锁回（125ms vs 旧250ms），肉眼几乎看不到"跳回来"
     *    4 = 原V2值(200ms)，对真走动上锁更稳
     */
    @Deprecated
    private int staticLockFrames = 2;

    /**
     * 20260914 V3 EMA一阶低通参数化升级（替代固定α，按窗口帧数自动换算）：
     *   emaEnabled=true开启；
     *   emaWindowFrames=等效均值队列长度（默认10=用户方案中"缓存10帧取均值"的N，EMA响应速度是简单均值的3倍）；
     *     有效α换算公式：α = 2/(N+1)。emaWindowFrames=3 → α=0.5；=10 → α≈0.182；
     *   手填 emaAlpha > 0 时以手填为准（覆盖自动换算），=0或负数时用window换算；
     *   emaMinFrames=前N帧直通零延迟（避免启动时收敛慢）。
     */
    private boolean emaEnabled = true;
    private int emaWindowFrames = 15;
    private double emaAlpha = 0.2d;
    private int emaMinFrames = 5;

    /**
     * 20260916 P0：每路BS测距 输入层非线性中值滑窗（解决±10-20cm随机白噪声）：
     *   medianWindowFrames = N = 维护一个N长度环形缓冲，取median替代原始硬件测距值送solve2D
     *   推荐值=5（压制70%白噪声，延迟≈0.8秒/5Hz录制）；
     *   响应更快可设3（压制56%延迟0.4秒）；噪声特别大设7（压制78%延迟1.2秒）；
     *   ≤1=关闭中值滑窗，完全沿用原始测距
     */
    private int medianWindowFrames = 5;

    /**
     * 20260916 P1：Huber M-估计器损失函数（软圆环，解决测距混合高斯+NLOS多径）参数：
     *   huberSigmaFrames = N = σ计算滑动窗口帧（最近N帧求标准差，默认5=同median滑窗）
     *   huberK = k = 置信区间倍数，默认1.5（高斯假设下≈95%真实距离落在环内=二次WLS区；异常区线性惩罚）
     *   k=1.0=更窄=对异常更激进可能杀真解；k=2.0=更宽=异常容忍高抗差弱
     */
    private int huberSigmaFrames = 5;
    private double huberK = 1.5d;

    /**
     * 20260916 P0：UWB基站坐标标记偏差 -> 刚体平移自校准（Ctrl+Shift+U快捷键 / REST触发）参数：
     *   模式=A：中心基准点（马甲放场地中心，即物理坐标x=y=50%）
     *   calibCenterRefX / RefY = 参考点物理百分比坐标（模式A默认0.5/0.5即场地正中心）
     *   calibSampleFrames = 采样帧数（默认200帧/5Hz录制≈40秒，越久median越稳）
     *   calibStaticMaxDiffCm = 静止判定（采样期间EMA坐标变化≤此值认为成功，超过=人动了终止校准并WARN）
     *   calibSaveFile = 文件名（保存到用户目录 .scds 共享文件夹下）
     */
    private double calibCenterRefX = 0.5d;
    private double calibCenterRefY = 0.5d;
    private int calibSampleFrames = 200;
    private double calibStaticMaxDiffCm = 5.0d;
    private String calibSaveFile = "uwb_calib.json";

    /**
     * 20260914 V3 EMA输出前二次JUMP_REJECT强制校验（防闪现最后一道保险）：
     *   emaJumpRejectEnforced=true时，对EMA后候选点 vs 上一次EMA输出点做【速度 或 位移】任一超限→拦截。
     *   解决：原速度阈值6m/s × 周期62ms=允许跳37cm，刚好比伪峰30~35cm大导致漏网；加位移35cm兜底全堵。
     */
    private boolean emaJumpRejectEnforced = true;
    private double emaJumpRejectMps = 6.0d;
    /**
     * 20260914 V4 二次JUMP_REJECT位移硬上限兜底（厘米，默认35cm=死区20+offset15）：
     *   EMA后候选点 vs 上一输出点位移超过此值→无论速度是否达标都REJECT沿用上一帧。
     *   0=关位移兜底，只按速度判断；默认和释放硬下限对齐=死区+offset。
     */
    private double emaJumpRejectDistCm = 200.0d;

    /**
     * 20260914 UWB测距未变阈值(毫米，默认30mm = UWB硬件典型白噪声)：
     *   判断「基站测距8维向量是否显著变化」的容差：全部8基站差值<=此值→打UNCHANGED标记(跳过解算+沿用distMm)；
     *   任1基站差值>此值→打UPDATED标记。
     *   注意：此阈值仅影响 UPDATED/UNCHANGED 日志标记 + 是否进入下一层解算；
     *         最终马甲位置仍由 staticDeadbandCm(20cm)滞回锁 + EMA控制，与此值无直接关系。
     *   现场建议：
     *     基站抗干扰强/追求解算高灵敏度 → 设20mm（更频繁UPDATED标记）
     *     多路径反射严重/日志UPDATED/UNCHANGED切换太频繁观感差 → 设50~80mm（减少标记切换，节省CPU/日志IO）
     */
    private int staticUnchangedTolMm = 30;

    /**
     * 20260914 UWB解算最小间隔（毫秒，默认 100ms = 10Hz）：
     *   UWB硬件上报约20Hz(50ms)，但人眼10Hz(100ms)完全够用；小于此间隔的UPDATED帧直接跳过后端解算，节省CPU。
     *   注意：UNCHANGED帧永远不解算（本来就跳过）。
     *   0=关闭限频（按原始硬件频率解算）。
     */
    private long minSolveIntervalMs = 100L;

    /**
     * 20260914 UWB定位逐帧日志降频：每 N 帧才打印1次 [UWB定位]#X号背心(UPDATED/UNCHANGED) INFO日志。
     *   默认 20 = 50ms一帧≈每秒打1次（现场INFO级不刷屏）；1=关闭降频每帧都打；0=完全关闭该日志（推荐生产0关闭）。
     */
    private int locationLogSampleN = 20;

    public boolean isMode() {
        return mode;
    }

    public void setMode(boolean mode) {
        this.mode = mode;
    }

    public int getMaxBsCount() {
        return maxBsCount;
    }

    public void setMaxBsCount(int maxBsCount) {
        this.maxBsCount = maxBsCount;
    }

    public long getPushIntervalMs() {
        return pushIntervalMs;
    }

    public void setPushIntervalMs(long pushIntervalMs) {
        this.pushIntervalMs = pushIntervalMs;
    }

    public double getResidualMaxMeters() {
        return residualMaxMeters;
    }

    public void setResidualMaxMeters(double residualMaxMeters) {
        this.residualMaxMeters = residualMaxMeters;
    }

    public double getJumpMaxMps() {
        return jumpMaxMps;
    }

    public void setJumpMaxMps(double jumpMaxMps) {
        this.jumpMaxMps = jumpMaxMps;
    }

    public long getLostTimeoutMs() {
        return lostTimeoutMs;
    }

    public void setLostTimeoutMs(long lostTimeoutMs) {
        this.lostTimeoutMs = lostTimeoutMs;
    }

    public int getRecoverMinFrames() {
        return recoverMinFrames;
    }

    public void setRecoverMinFrames(int recoverMinFrames) {
        this.recoverMinFrames = recoverMinFrames;
    }

    public double getRectToleranceM() {
        return rectToleranceM;
    }

    public void setRectToleranceM(double rectToleranceM) {
        this.rectToleranceM = rectToleranceM;
    }

    public boolean isRectStrictReject() {
        return rectStrictReject;
    }

    public void setRectStrictReject(boolean rectStrictReject) {
        this.rectStrictReject = rectStrictReject;
    }

    public long getSolveIntervalMs() {
        return solveIntervalMs;
    }

    public void setSolveIntervalMs(long solveIntervalMs) {
        this.solveIntervalMs = solveIntervalMs;
    }

    public double getBsValidMinMeters() {
        return bsValidMinMeters;
    }

    public void setBsValidMinMeters(double bsValidMinMeters) {
        this.bsValidMinMeters = bsValidMinMeters;
    }

    public double getBsValidMaxMeters() {
        return bsValidMaxMeters;
    }

    public void setBsValidMaxMeters(double bsValidMaxMeters) {
        this.bsValidMaxMeters = bsValidMaxMeters;
    }

    public double getStaticDeadbandCm() {
        return staticDeadbandCm;
    }

    public void setStaticDeadbandCm(double staticDeadbandCm) {
        this.staticDeadbandCm = staticDeadbandCm;
    }

    public double getStaticReleaseFactor() {
        return staticReleaseFactor;
    }

    public void setStaticReleaseFactor(double staticReleaseFactor) {
        this.staticReleaseFactor = staticReleaseFactor;
    }

    public double getStaticReleaseMinOffsetCm() {
        return staticReleaseMinOffsetCm;
    }

    public void setStaticReleaseMinOffsetCm(double staticReleaseMinOffsetCm) {
        this.staticReleaseMinOffsetCm = staticReleaseMinOffsetCm;
    }

    public int getStaticLockFrames() {
        return staticLockFrames;
    }

    public void setStaticLockFrames(int staticLockFrames) {
        this.staticLockFrames = staticLockFrames;
    }

    public boolean isEmaEnabled() {
        return emaEnabled;
    }

    public void setEmaEnabled(boolean emaEnabled) {
        this.emaEnabled = emaEnabled;
    }

    public int getEmaWindowFrames() {
        return emaWindowFrames;
    }

    public void setEmaWindowFrames(int emaWindowFrames) {
        this.emaWindowFrames = emaWindowFrames;
    }

    public double getEmaAlpha() {
        return emaAlpha;
    }

    public void setEmaAlpha(double emaAlpha) {
        this.emaAlpha = emaAlpha;
    }

    /**
     * 20260914 V3 智能推导实际EMA α：
     *   手填emaAlpha>0时=手填优先（支持现场覆盖）；
     *   否则=2/(emaWindowFrames+1)标准指数移动平均换算；
     *   保证结果落在[0.01, 0.99]区间
     */
    public double getEffectiveEmaAlpha() {
        if (emaAlpha > 0.0d) {
            return Math.max(0.01d, Math.min(0.99d, emaAlpha));
        }
        final int n = Math.max(1, emaWindowFrames);
        final double byWindow = 2.0d / (double) (n + 1);
        return Math.max(0.01d, Math.min(0.99d, byWindow));
    }

    public int getEmaMinFrames() {
        return emaMinFrames;
    }

    public void setEmaMinFrames(int emaMinFrames) {
        this.emaMinFrames = emaMinFrames;
    }

    public boolean isEmaJumpRejectEnforced() {
        return emaJumpRejectEnforced;
    }

    public void setEmaJumpRejectEnforced(boolean emaJumpRejectEnforced) {
        this.emaJumpRejectEnforced = emaJumpRejectEnforced;
    }

    public double getEmaJumpRejectMps() {
        return emaJumpRejectMps;
    }

    public void setEmaJumpRejectMps(double emaJumpRejectMps) {
        this.emaJumpRejectMps = emaJumpRejectMps;
    }

    public double getEmaJumpRejectDistCm() {
        return emaJumpRejectDistCm;
    }

    public void setEmaJumpRejectDistCm(double emaJumpRejectDistCm) {
        this.emaJumpRejectDistCm = emaJumpRejectDistCm;
    }

    public int getStaticUnchangedTolMm() {
        return staticUnchangedTolMm;
    }

    public void setStaticUnchangedTolMm(int staticUnchangedTolMm) {
        this.staticUnchangedTolMm = staticUnchangedTolMm;
    }

    public long getMinSolveIntervalMs() {
        return minSolveIntervalMs;
    }

    public void setMinSolveIntervalMs(long minSolveIntervalMs) {
        this.minSolveIntervalMs = minSolveIntervalMs;
    }

    public int getLocationLogSampleN() {
        return locationLogSampleN;
    }

    public void setLocationLogSampleN(int locationLogSampleN) {
        this.locationLogSampleN = locationLogSampleN;
    }

    /**
     * 20260915 方案B：测距单帧跳变过滤阈值(米，默认2.0m)
     *   当前BS测距 vs 上一次同BS测距 绝对差>此值 → 判INVALID不计入有效S集合
     *   解决：硬件偶发"跳5米"粗差(bs1:9050→5320mm=3.73m/帧)导致used=3 GDOP外解anchDist=703cm问题
     *   现场建议：
     *     基站密集/人移动慢 → 1.0~1.5m（更严格）
     *     人跑动/电梯/上下楼 → 2.5~3.0m（更松）
     *   0=关闭此过滤
     */
    private double distJumpFilterM = 2.0d;

    /**
     * 20260915 方案B：入状态机最小有效基站数(默认3)
     *   本次solve2D最终有效BS数 < 此值 → 返回HELD_USED_LOW 不把候选解送入UwbFilter状态机（直接沿用priorGuess上一帧）
     *   解决：usedCount=2时反比加权兜底虽然不飞解，但GDOP≈30噪声放大30倍，进入状态机会污染锚点+PushGate基准漂移；
     *         usedCount=1同理；直接HELD让"五道防错"中的lastValidTsMillis超时前一直沿用上一帧=坐标稳定不跳
     *   现场建议：
     *     4基站及以上稳定覆盖 → 保持3（严格，推荐）
     *     3基站常掉1个 → 设2（放宽但used2仍有GDOP风险）
     *   0=关闭此降级，used1/2也正常入状态机
     */
    private int usedMinCountStatus = 2;

    public double getDistJumpFilterM() {
        return distJumpFilterM;
    }

    public void setDistJumpFilterM(double distJumpFilterM) {
        this.distJumpFilterM = distJumpFilterM;
    }

    public int getUsedMinCountStatus() {
        return usedMinCountStatus;
    }

    public void setUsedMinCountStatus(int usedMinCountStatus) {
        this.usedMinCountStatus = usedMinCountStatus;
    }

    public int getMedianWindowFrames() {
        return medianWindowFrames;
    }

    public void setMedianWindowFrames(int medianWindowFrames) {
        this.medianWindowFrames = medianWindowFrames;
    }

    public int getHuberSigmaFrames() {
        return huberSigmaFrames;
    }

    public void setHuberSigmaFrames(int huberSigmaFrames) {
        this.huberSigmaFrames = huberSigmaFrames;
    }

    public double getHuberK() {
        return huberK;
    }

    public void setHuberK(double huberK) {
        this.huberK = huberK;
    }

    public double getCalibCenterRefX() {
        return calibCenterRefX;
    }

    public void setCalibCenterRefX(double calibCenterRefX) {
        this.calibCenterRefX = calibCenterRefX;
    }

    public double getCalibCenterRefY() {
        return calibCenterRefY;
    }

    public void setCalibCenterRefY(double calibCenterRefY) {
        this.calibCenterRefY = calibCenterRefY;
    }

    public int getCalibSampleFrames() {
        return calibSampleFrames;
    }

    public void setCalibSampleFrames(int calibSampleFrames) {
        this.calibSampleFrames = calibSampleFrames;
    }

    public double getCalibStaticMaxDiffCm() {
        return calibStaticMaxDiffCm;
    }

    public void setCalibStaticMaxDiffCm(double calibStaticMaxDiffCm) {
        this.calibStaticMaxDiffCm = calibStaticMaxDiffCm;
    }

    public String getCalibSaveFile() {
        return calibSaveFile;
    }

    public void setCalibSaveFile(String calibSaveFile) {
        this.calibSaveFile = calibSaveFile;
    }

    /**
     * 20260918 Spec V2 质量优化开关 1/5：UNCHANGED 帧空跑 getState() 累积 emaFrameCount
     *   默认 true = 静止测距未变化帧也空跑一次 Filter 只读累积计数，解决 UNCHANGED 跳过 solve 导致 emaFrameCount 1~2Hz 累积过慢 = 直通窗口拉长问题；
     *   关 false = 回退 BUG10-11FIX 旧行为，UNCHANGED 帧完全不碰 Filter。
     */
    private boolean unchangedAccumulateEnabled = true;
    /**
     * 20260918 Spec V2 质量优化开关 2/5：Solver 候选池 GDOP 二阶排序权重（0 = 关 = 旧纯残差排序零回归）
     *   默认 0.3 = 70% 残差 + 30% 几何精度因子 GDOP，解决 U≤3 帧时几何镜像假解胜出问题；
     *   现场建议：残差敏感 → 0.1；几何退化严重 → 0.5；0 = 完全关闭（旧行为零回归）。
     */
    private double solverGdopWeight = 0.3d;
    /**
     * 20260918 Spec V2 质量优化开关 3/5：EMA 输出单帧最大位移硬上限（厘米，0 = 关 = 旧行为）
     *   默认 20cm = JUMP 兜底后，单帧用户可见位移再压一层 20cm 上限，连续同向真实大位移会被拆成 N 帧 20cm 平滑折线输出；
     *   现场建议：跟随慢 → 30/40cm；关 → 0 直接回退旧行为。
     */
    private double emaStepLimitCm = 35.0d;
    /**
     * 20260918 Spec V2 质量优化开关 4/5：U≤3 几何退化帧 lastValidXY 加权混合（false = 关 = 旧直接用 raw 解）
     *   默认 true = U=3 0.7raw+0.3lastValid(残差>0.3m权重翻转)；U=2 0.2raw+0.8lastValid(残差>0.5m直接lastValid)；
     *   关 false = U≤3 帧完全沿用旧行为直接输出 raw 几何解，回退 BUG10-11FIX 基线。
     */
    private boolean useDegradeMixEnabled = true;
    /**
     * 20260918 Spec V2 质量优化开关 5/5：CSV 全帧诊断导出（默认 false = 零 I/O，调试时才开）
     *   开启后每帧追加写 logs/uwb_qa_{马甲号}_{YYYYMMDD}.csv 共 11 列，便于离线 Python 分析跳变分布；
     *   默认 false = 不打开文件句柄，完全零 I/O 开销。
     */
    private boolean diagnosticCsvEnabled = false;

    public boolean isUnchangedAccumulateEnabled() {
        return unchangedAccumulateEnabled;
    }

    public void setUnchangedAccumulateEnabled(boolean unchangedAccumulateEnabled) {
        this.unchangedAccumulateEnabled = unchangedAccumulateEnabled;
    }

    public double getSolverGdopWeight() {
        return solverGdopWeight;
    }

    public void setSolverGdopWeight(double solverGdopWeight) {
        this.solverGdopWeight = solverGdopWeight;
    }

    public double getEmaStepLimitCm() {
        return emaStepLimitCm;
    }

    public void setEmaStepLimitCm(double emaStepLimitCm) {
        this.emaStepLimitCm = emaStepLimitCm;
    }

    public boolean isUseDegradeMixEnabled() {
        return useDegradeMixEnabled;
    }

    public void setUseDegradeMixEnabled(boolean useDegradeMixEnabled) {
        this.useDegradeMixEnabled = useDegradeMixEnabled;
    }

    public boolean isDiagnosticCsvEnabled() {
        return diagnosticCsvEnabled;
    }

    public void setDiagnosticCsvEnabled(boolean diagnosticCsvEnabled) {
        this.diagnosticCsvEnabled = diagnosticCsvEnabled;
    }

    /**
     * 20260917 版本追溯：UWB后端解算代码包构建版本标签（每次打BUGFIX/新功能包时手动递增，和交付目录后缀保持一致）
     *   规则：SCDS-UWB-v[版本号]-[YYYYMMDD]-[HHMM]-[简述标签]
     *   读取时机：① @PostConstruct 启动 Banner 打印 ② 每背心第1帧定位 Banner 打印 → 日志grep "VERSION-BANNER" 立即100%确认现场跑的是哪一版
     */
    private String buildVersion = "SCDS-UWB-v1.5-20261007-P6-3";

    /**
     * 20260922 20马甲延迟修复开关 P0-1/2/3/4：
     *   开关1=optimizeUwbOuterNestedLock （P0-1）true=删除 UwbLocationHandler.handle 外层 UWB_PIPELINE_LOCK 嵌套，保留 vest 细粒度锁
     *   开关2=optimizeReadLockSnapshotOnly（P0-2）true=getNowBattleDetail 锁内仅做 vest 引用 toArray 快照，反射拷贝/DB查询/Map 查询全部移到锁外
     *   开关3=optimizeWriteLockMinimal（P0-4）   true=solveVestUwbLocked 解算主体(UwbSolver/UwbFilter/UwbCalib)移锁外，仅 4 个 set 字段包写锁
     *   开关4=optimizeHeartbeatNoticeDebounce（P0-3）true=HandleService 所有帧 notice 走 CAS 合并 125ms 窗口（UWB模式下自动生效）
     *   全部默认 true = 优化全开；任意一项设置 false = 立即回退旧行为，不影响 P4-3a 其他修复。
     */
    private boolean optimizeUwbOuterNestedLock = true;
    private boolean optimizeReadLockSnapshotOnly = true;
    private boolean optimizeWriteLockMinimal = true;
    private boolean optimizeHeartbeatNoticeDebounce = true;

    public boolean isOptimizeUwbOuterNestedLock() { return optimizeUwbOuterNestedLock; }
    public void setOptimizeUwbOuterNestedLock(boolean v) { optimizeUwbOuterNestedLock = v; }
    public boolean isOptimizeReadLockSnapshotOnly() { return optimizeReadLockSnapshotOnly; }
    public void setOptimizeReadLockSnapshotOnly(boolean v) { optimizeReadLockSnapshotOnly = v; }
    public boolean isOptimizeWriteLockMinimal() { return optimizeWriteLockMinimal; }
    public void setOptimizeWriteLockMinimal(boolean v) { optimizeWriteLockMinimal = v; }
    public boolean isOptimizeHeartbeatNoticeDebounce() { return optimizeHeartbeatNoticeDebounce; }
    public void setOptimizeHeartbeatNoticeDebounce(boolean v) { optimizeHeartbeatNoticeDebounce = v; }

    private long rangeWindowMaxAgeMs = 500L;
    private double rangeSigmaMinM = 0.05d;
    private double rangeSigmaMaxM = 1.0d;
    private double twoAnchorPriorSigmaM = 0.5d;
    private double solverMaxGdop = 20.0d;
    private int solverMaxIterations = 30;
    private int reacquireMinFrames = 5;
    private double reacquireResidualMaxM = 0.75d;

    public long getRangeWindowMaxAgeMs() { return rangeWindowMaxAgeMs; }
    public void setRangeWindowMaxAgeMs(long v) { rangeWindowMaxAgeMs = Math.max(1L, v); }
    public double getRangeSigmaMinM() { return rangeSigmaMinM; }
    public void setRangeSigmaMinM(double v) { rangeSigmaMinM = Math.max(0.005d, v); }
    public double getRangeSigmaMaxM() { return Math.max(rangeSigmaMinM, rangeSigmaMaxM); }
    public void setRangeSigmaMaxM(double v) { rangeSigmaMaxM = Math.max(0.005d, v); }
    public double getTwoAnchorPriorSigmaM() { return twoAnchorPriorSigmaM; }
    public void setTwoAnchorPriorSigmaM(double v) { twoAnchorPriorSigmaM = Math.max(0.05d, v); }
    public double getSolverMaxGdop() { return solverMaxGdop; }
    public void setSolverMaxGdop(double v) { solverMaxGdop = Math.max(1d, v); }
    public int getSolverMaxIterations() { return solverMaxIterations; }
    public void setSolverMaxIterations(int v) { solverMaxIterations = Math.max(5, Math.min(100, v)); }
    public int getReacquireMinFrames() { return reacquireMinFrames; }
    public void setReacquireMinFrames(int v) { reacquireMinFrames = Math.max(2, v); }
    public double getReacquireResidualMaxM() { return reacquireResidualMaxM; }
    public void setReacquireResidualMaxM(double v) { reacquireResidualMaxM = Math.max(0.01d, v); }

    public UwbDataSource getDataSource() { return dataSource; }
    public void setDataSource(UwbDataSource v) { this.dataSource = v == null ? UwbDataSource.SERIAL : v; }
    public String getTcpServerHost() { return tcpServerHost; }
    public void setTcpServerHost(String v) { this.tcpServerHost = (v == null || v.isEmpty()) ? "0.0.0.0" : v; }
    public int getTcpServerPort() { return tcpServerPort; }
    public void setTcpServerPort(int v) { this.tcpServerPort = (v <= 0 || v > 65535) ? 12001 : v; }
    public int getTcpReadTimeoutMs() { return tcpReadTimeoutMs; }
    public void setTcpReadTimeoutMs(int v) { this.tcpReadTimeoutMs = Math.max(1000, v); }
    public int getTcpIdleWarnMin() { return tcpIdleWarnMin; }
    public void setTcpIdleWarnMin(int v) { this.tcpIdleWarnMin = Math.max(1, v); }

    public String getBuildVersion() {
        return buildVersion;
    }

    public void setBuildVersion(String buildVersion) {
        this.buildVersion = buildVersion;
    }

    /**
     * 20260917 版本追溯：格式化 Banner 字符串（启动Banner + 每背心首帧Banner 共用一份输出格式，避免两处不统一）
     *   输出内容（1行看完，便于 grep/文本检索）：
     *     buildVersion + 12个抗差核心参数实时值 + EMA effectiveAlpha 实际计算值 + usedMinCountStatus + distJumpFilterM
     *     全部用【KEY=VALUE】格式，分隔符 | ，grep VERSION-BANNER 复制出来直接比对 diff
     */
    public String toVersionBannerString() {
        String tcpInfo = dataSource == UwbDataSource.TCP_SERVER
                ? String.format(java.util.Locale.ROOT, "tcp=%s:%d soTimeoutMs=%d idleWarnMin=%d",
                        tcpServerHost, tcpServerPort, tcpReadTimeoutMs, tcpIdleWarnMin)
                : "serial=gnu.io.RXTX";
        // P6-2 增强诊断：启动期 TCP Server 是否已经绑定监听（GlobalTcpUwbHolder.hasInstance），
        //   便于一眼判断「到底是 yml 没读到 TCP_SERVER（= SERIAL / noListening ）还是读到了但没启动监听」
        boolean tcpListeningNow;
        try {
            Class<?> holderCls = Class.forName("com.dualx.gateway.uwb.GlobalTcpUwbHolder");
            Object holder = holderCls.getField("INSTANCE").get(null);
            tcpListeningNow = (Boolean) holderCls.getMethod("hasInstance").invoke(holder);
        }
        catch (Throwable ignore) { tcpListeningNow = false; }
        return String.format(java.util.Locale.ROOT,
                "[VERSION-BANNER] build=%s dataSrc=%s tcpListeningNow=%s modeEn=%s uwbEn=%s %s solver=ALL_FRESH_HUBER_LM frameIdDedup=true getterPure=true "
                        + "median=%d sigmaFrames=%d sigmaM=[%.3f,%.3f] rangeExpiryMs=%d huberK=%.2f "
                        + "rmsMaxM=%.2f gdopMax=%.2f iterations=%d okMinBs=%d twoPriorSigmaM=%.2f "
                        + "ema=%s alphaAt50ms=%.4f stepCmAt50ms=%.1f entryGate=%s/%.1fcm "
                        + "reacquireFrames=%d reacquireRmsM=%.2f lostMs=%d csv=%s calibration=MAP_SCOPED_ONCE",
                buildVersion, dataSource, tcpListeningNow, isMode(), isMode(), tcpInfo,
                getMedianWindowFrames(),getHuberSigmaFrames(),getRangeSigmaMinM(),getRangeSigmaMaxM(),
                getRangeWindowMaxAgeMs(),getHuberK(),getResidualMaxMeters(),getSolverMaxGdop(),getSolverMaxIterations(),
                getUsedMinCountStatus(),getTwoAnchorPriorSigmaM(),isEmaEnabled(),getEffectiveEmaAlpha(),getEmaStepLimitCm(),
                isEmaJumpRejectEnforced(),getEmaJumpRejectDistCm(),getReacquireMinFrames(),getReacquireResidualMaxM(),
                getLostTimeoutMs(),isDiagnosticCsvEnabled());
    }
}
