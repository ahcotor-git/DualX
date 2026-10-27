package com.dualx.core.state;

import com.dualx.solver.algorithm.UwbFilter;
import com.dualx.solver.algorithm.UwbSolver;
import com.dualx.solver.config.ScdsUwbProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * UWB 室内坐标解算（设计 06 §4.1 的室内定位支路）。把一条 41B 测距帧解成**整图百分比坐标**，
 * 供 {@link SituationStateService} 写入 {@code GeoPoint(INDOOR, ...)}。
 *
 * <p><b>为什么单独成类</b>：解算需要「地图域 {@code uwb_meta} + 每马甲解算器/滤波器」这套有状态资源，
 * 与态势的状态机不是一回事。装在与态势写手同一个线程上调用，但状态自持——地图一换就整体重建，
 * 不必让态势服务知道 {@link UwbSolver}/{@link UwbFilter} 的存在。</p>
 *
 * <p><b>逐段口径与旧实现一致</b>（旧 {@code BattleService#solveVestUwbOnce} 的 UWB 分支）：</p>
 * <ol>
 *   <li>取上次滤波态当先验；<b>仅当上次不是 {@code LOST} 且坐标有限</b>时把 {@code (xPct,yPct)} 传进解算器
 *       （先验只用于两锚点这种欠定几何，见 {@code UwbSolver} 的 {@code priorWeight}）。</li>
 *   <li>解算 → 送滤波器（帧号严格递增；时间倒灌由滤波器判 {@code DUPLICATE_OR_OUT_OF_ORDER} 拦下）。</li>
 *   <li>读滤波后的**物理解算态**，经 {@link UwbSolver#toImagePct} 映射为整图 0~1。</li>
 * </ol>
 *
 * <p><b>注意两个坐标空间不是一回事</b>：{@code Solve2DResult.xPct/yPct} 是 **BS1~BS4 外接矩形内**的
 * 百分比；底图上看到的整图百分比必须再走一次 {@code toImagePct}。少做这一步会让坐标整体偏到图角。</p>
 *
 * <p><b>降级口径：只退不给错</b>——meta 不可解析（{@code HELD_META_INVALID}）、几何不足
 * （{@code HELD_INSUFFICIENT_GEOMETRY}）、质量不达标（{@code HELD_QUALITY}）、显示层映射失败
 * （缺图片尺寸 / meta 残缺）一律 {@link Optional#empty()}，<b>不抛异常、不造坐标</b>；上层据此维持
 * 「只要没有坐标就不改马甲位置」。</p>
 *
 * <p><b>不注入 {@code Clock}</b>：与 {@code SituationStateService} 的其它 core 组件同约定——core 测试
 * 上下文没有 {@code Clock} Bean，且本类的时间戳一律来自调用方给的帧时刻（{@code rxTs}），不自取当前时间。</p>
 */
public final class IndoorPositionResolver {

    private static final Logger log = LoggerFactory.getLogger(IndoorPositionResolver.class);

    /** 与 {@code MapLedgerService} 同一份四角清单：{@code uwb_meta} 里有这四角才谈得上标定。 */
    private static final List<String> CORNERS = List.of("bs1", "bs2", "bs3", "bs4");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一次成功的室内定位：整图 0~1 百分比坐标 + 本次解算实际用上的基站数。 */
    public record Fix(double xPct, double yPct, int usedCount) {
    }

    private final ScdsUwbProperties props;
    /** 每马甲的测量滤波态；地图/元数据一换即整体重建。 */
    private final Map<Integer, UwbFilter> filters = new HashMap<>();
    /** 每马甲的解算器（内含测距中值窗与 Huber 状态）。 */
    private final Map<Integer, UwbSolver> solvers = new HashMap<>();

    private String mapKey = "";
    private String uwbMeta;
    private Integer imageWidthPx;
    private Integer imageHeightPx;
    /** {@link #uwbMeta} 是否齐备（装载时判一次，口径见 {@link #calibrated}）。 */
    private boolean calibrated;

    public IndoorPositionResolver(ScdsUwbProperties props) {
        this.props = Objects.requireNonNull(props, "props");
    }

    /**
     * 装载一张底图的解算参数（开演时按 {@code exercise.mapId} 取 {@code t_map} 的 {@code uwb_meta} 与图片像素尺寸）。
     *
     * <p>重复装载同一张图也照常重建状态：切换场次后上一场的滤波先验对新场次是脏数据，宁可多丢几帧。</p>
     *
     * @return 参数是否齐备（等价于紧接着的 {@link #loaded()}）
     */
    public synchronized boolean load(String mapKey, String uwbMetaJson, Integer imageWidthPx, Integer imageHeightPx) {
        this.mapKey = mapKey == null ? "" : mapKey;
        this.uwbMeta = uwbMetaJson;
        this.imageWidthPx = imageWidthPx;
        this.imageHeightPx = imageHeightPx;
        dropState();
        this.calibrated = calibrated(uwbMetaJson);
        return loaded();
    }

    /** 清空解算参数与全部马甲状态（停演、或该场次没有底图时）。 */
    public synchronized void clear() {
        this.mapKey = "";
        this.uwbMeta = null;
        this.imageWidthPx = null;
        this.imageHeightPx = null;
        this.calibrated = false;
        dropState();
    }

    /** 解算参数是否齐备：{@code uwb_meta} 齐备（见 {@link #calibrated}），且图片像素尺寸为正。 */
    public synchronized boolean loaded() {
        return calibrated && positive(imageWidthPx) && positive(imageHeightPx);
    }

    /** UWB 模式开关（{@code scds.uwb.mode}）：关闭时本类不产出任何坐标。 */
    public boolean uwbMode() {
        return props.isMode();
    }

    /**
     * 解一条测距帧并给出整图坐标。
     *
     * @param vestNum  马甲编号
     * @param rangesMm 8 路基站测距（毫米，{@code bs(i+1) ↔ ranges[i]}），无测距的路填 {@code <=0}
     * @param rxTs     本帧时刻（毫秒，单调递增；用于测距窗过期与在线超时判定）
     * @return 解出且能映射到整图时为坐标；任何降级情形返回 {@link Optional#empty()}（不抛异常）
     */
    public synchronized Optional<Fix> resolve(int vestNum, int[] rangesMm, long rxTs) {
        if (!uwbMode() || !loaded()) {
            return Optional.empty();
        }
        UwbFilter filter = filters.computeIfAbsent(vestNum, num -> {
            UwbFilter created = new UwbFilter(props, num);
            created.setMapKey(mapKey);
            return created;
        });
        UwbSolver solver = solvers.computeIfAbsent(vestNum, num -> new UwbSolver(props));

        UwbFilter.State prior = filter.getState(rxTs);
        boolean trusted = !UwbFilter.STATUS_LOST.equals(prior.status);
        Double px = trusted && Double.isFinite(prior.xPct) ? prior.xPct : null;
        Double py = trusted && Double.isFinite(prior.yPct) ? prior.yPct : null;

        long frameId = filter.getLastFrameId() + 1;
        UwbSolver.Solve2DResult solution =
                solver.solve2D(vestNum, uwbMeta, rangesMm, px, py, rxTs, frameId, prior.ts);
        filter.push(frameId, rxTs, solution);

        // 读的是滤波后的物理解算态：被拒绝的帧在这里仍是上一帧的「保持位」（坐标有限但状态为 HELD/LOST），
        // 旧实现同样按保持位下发布局，不因单帧抖动把马甲从图上抹掉。
        UwbFilter.State physical = filter.getState(rxTs);
        if (!Double.isFinite(physical.xPct) || !Double.isFinite(physical.yPct)) {
            return Optional.empty();
        }
        try {
            double[] xy = UwbSolver.toImagePct(uwbMeta, physical.xPct, physical.yPct,
                    imageWidthPx, imageHeightPx);
            return Optional.of(new Fix(xy[0], xy[1], solution.usedCount));
        } catch (RuntimeException ex) {
            // 显示层元数据不可用（缺图片尺寸、锚点被删改）：不给坐标，也不让一帧脏数据打断写手线程。
            log.debug("UWB 显示映射失败，本帧不给坐标：vestNum={}，cause={}", vestNum, ex.toString());
            return Optional.empty();
        }
    }

    /** 丢弃全部马甲的滤波器与解算器状态（换图 / 清空）。 */
    private void dropState() {
        filters.values().forEach(UwbFilter::close);
        filters.clear();
        solvers.clear();
    }

    /**
     * {@code uwb_meta} 是否齐备——口径与 {@code MapLedgerService.calibrated} 一致：
     * {@code bs1~bs4} 的 {@code xPx/yPx} 都是数值，且 {@code realLengthCm / realWidthCm} 都是**正**数值
     * （保存侧 {@code requirePositiveWholeCm} 已挡住 0 与负数，这里只是不让脏行在解算端反复失败）。</p>
     *
     * <p><b>这里只判「有没有标定参数」，不判「几何解不解得开」</b>。两个理由：一是同一件事
     * （这张底图标定好了没）在库里只能有一个判据，检测端口径比解算端更严会出现「接口说已标定、
     * 定位却永远不出点」这种自相矛盾；二是严格反解只有 {@code UwbSolver.Geometry} 会做，在这里再
     * 写一遍必然与它漂移。真正解不开的 meta 会在逐帧解算时以 {@code HELD_META_INVALID} 退化为
     * 「本帧不给坐标」——不抛异常、不造坐标，见类注释的降级口径。</p>
     *
     * <p>文本损坏（手工改库、旧格式残留）按「不齐备」处理而不是抛异常：一张标定读不出来的底图
     * 不该把开演流程打断，现场需要的是「这批马甲没有室内坐标」而不是异常。</p>
     */
    private static boolean calibrated(String uwbMeta) {
        if (uwbMeta == null || uwbMeta.isBlank()) {
            return false;
        }
        JsonNode root;
        try {
            root = JSON.readTree(uwbMeta);
        } catch (JsonProcessingException broken) {
            return false;
        }
        if (root == null || !root.isObject()) {
            return false;
        }
        for (String corner : CORNERS) {
            JsonNode point = root.get(corner);
            if (point == null || !point.isObject() || !isNumber(point.get("xPx")) || !isNumber(point.get("yPx"))) {
                return false;
            }
        }
        return isPositiveNumber(root.get("realLengthCm")) && isPositiveNumber(root.get("realWidthCm"));
    }

    private static boolean isNumber(JsonNode node) {
        return node != null && node.isNumber();
    }

    private static boolean isPositiveNumber(JsonNode node) {
        return isNumber(node) && node.doubleValue() > 0;
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }
}
