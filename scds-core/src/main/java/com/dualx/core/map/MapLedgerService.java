package com.dualx.core.map;

import com.dualx.core.domain.BaseMap;
import com.dualx.core.domain.MapFloor;
import com.dualx.core.domain.MapType;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.repository.BaseMapRepository;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.repository.MapFloorRepository;
import com.dualx.core.repository.PlanRepository;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.UuidV7;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 底图台账服务（契约 {@code docs/api/paths/map.yaml}，需求 FR-MAP）。
 *
 * <p><b>为什么必须有这一层</b>：{@code deleteMap} 的 409 判据（是否被想定引用、是否正被
 * 进行中的演习使用）需要同时读 {@code t_plan} / {@code t_exercise} 与 {@code t_map}，
 * 而库层没有外键；{@code MapDetail} 需要的
 * {@code floorCount} / {@code calibrated} 也不是单表能算出来的。控制器一律调本类，
 * 不碰仓储（评审 R22 的同一取向）。</p>
 *
 * <p><b>已落地</b>：底图台账 CRUD、楼层点位表读写（单楼层语义）与 {@code uwb_meta} 读写
 * （含保存前的结构性与边界性校验）。抛出的四张码
 * （{@code MAP_NOT_FOUND} / {@code MAP_IN_USE} / {@code MAP_NOT_CALIBRATED} /
 * {@code MAP_UWB_META_INCOMPLETE}）都已登记到 {@code ApiExceptionHandler} 的「一码一状态」表。</p>
 *
 * <p><b>不在本类</b>：底图图片的上传 / 下载 / 签名 URL 与「换图即删旧文件」属图片段落
 * （需 {@code FileStore} + {@code FileUrlSigner} 与图片头校验）；UWB 校准三端点需
 * {@code scds-gateway} 的采样端口与校准状态存储，本仓库尚不具备，仍为 501。
 * 因此 {@link #delete} 当前只删行、不删盘上的图片文件——留下的是孤立文件而非错误数据，
 * 图片段落落地时在此处补一次 {@code FileStore.delete(imagePath)} 即可。记为已知缺口。</p>
 */
@Service
public class MapLedgerService {

    /** {@code uwb_meta} 是冻结的扁平 JSON（见 {@code BaseMap}），四角与两个长宽基准缺一不可。 */
    private static final List<String> REQUIRED_CORNERS = List.of("bs1", "bs2", "bs3", "bs4");

    /**
     * 四角围出的平行四边形面积下限（px²）：小于它即视为「退化成一条线」。
     *
     * <p>取 1 px² 而非严格的 0，是为了拦住「两角只差半个像素」这类现场手抖输入——那种数据能存进库，
     * 但解算时反解出的尺度会变成天文数字。</p>
     */
    private static final double DEGENERATE_AREA_PX = 1.0;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BaseMapRepository maps;
    private final MapFloorRepository floors;
    private final PlanRepository plans;
    private final ExerciseRepository exercises;

    public MapLedgerService(BaseMapRepository maps, MapFloorRepository floors, PlanRepository plans,
                            ExerciseRepository exercises) {
        this.maps = maps;
        this.floors = floors;
        this.plans = plans;
        this.exercises = exercises;
    }

    /**
     * 底图列表。{@code type} 为空即不过滤——契约把 {@code type} 定义成可选，
     * 前端「全部 / 只室内 / 只室外」三个页签共用同一条查询。
     */
    public Page<MapView> list(MapType type, PageRequest request) {
        List<BaseMap> rows = type == null ? maps.findAll(request) : maps.findByType(type, request);
        long total = type == null ? maps.count() : maps.countByType(type);
        return Page.of(rows.stream().map(this::view).toList(), total, request);
    }

    /** 单张底图；不存在即 404，不做「返回 null 让调用方自己判」。 */
    public MapView get(UUID mapId) {
        return view(require(mapId));
    }

    /**
     * 新建底图。新行的 {@code image_path} / {@code uwb_meta} 一律为空——
     * 图片走上传接口、标定走 {@code putUwbMeta}，建档时没有可以填的来源。
     */
    public MapView create(String name, MapType type) {
        BaseMap drafted = new BaseMap(UuidV7.generate(), name, type, null, null, null, null, null, null, null);
        maps.insert(drafted);
        return get(drafted.id());
    }

    /**
     * 修改底图名称与类型。
     *
     * <p>图片与标定从当前行原样带回：契约的 {@code MapUpsertRequest} 只有 name/type，
     * 若在此处 new 一个「干净」的行，一次改名就会把已上传的底图与标定一起抹掉。</p>
     */
    public MapView update(UUID mapId, String name, MapType type) {
        BaseMap current = require(mapId);
        maps.update(new BaseMap(current.id(), name, type, current.imagePath(), current.imageWidth(),
                current.imageHeight(), current.remark(), current.uwbMeta(),
                current.createdAt(), current.updatedAt()));
        return get(mapId);
    }

    /**
     * 删除底图（契约 {@code deleteMap}）。被引用时 409，不做级联删除。
     *
     * <p>两道拒绝判据都归 {@link ApiErrorCode#MAP_IN_USE}：{@code t_plan.map_id} 是「将来要用」，
     * {@code t_exercise.map_id} 是「现在正在用」。对删除方来说应对动作不同（前者去解绑想定、
     * 后者先停演习），但都属于「这张图有人占着，现在不能删」，拆成两个码只会让前端多写一条
     * 分支而行为完全一致——拒绝文案已把区别说清。演习那条不能省：{@code t_exercise.map_id}
     * 是开演瞬间的快照，删掉后现场指挥端会立刻拉不到底图，而演习还在跑。</p>
     *
     * <p>同事务顺带清 {@code t_map_floor}：无外键，不清就会留下指向死地图的点位表，
     * 而那张表没有任何入口能再被读到，属于纯垃圾。反向不做——不因楼层点位存在而拒绝删除，
     * 点位表是底图的附属物，不是独立资源。</p>
     */
    @Transactional
    public void delete(UUID mapId) {
        require(mapId);
        if (plans.existsByMapId(mapId)) {
            throw new BusinessException(ApiErrorCode.MAP_IN_USE, "底图已被想定引用，不能删除：" + mapId);
        }
        if (exercises.findRunning().filter(running -> mapId.equals(running.mapId())).isPresent()) {
            throw new BusinessException(ApiErrorCode.MAP_IN_USE, "底图正被进行中的演习使用，不能删除：" + mapId);
        }
        floors.deleteByMapId(mapId);
        maps.deleteById(mapId);
    }

    // ── 楼层点位表（契约 listFloorPoints / replaceFloorPoints）──────────────────

    /**
     * 读取某楼层的点位表 JSON。
     *
     * <p>该楼层还没录过点位表时返回 {@code null}，由调用方折叠成空列表——「这张图这一层还没标点」
     * 是正常状态，不是错误；契约的 404 留给「底图不存在」。</p>
     */
    public String floorPoints(UUID mapId, int floorNum) {
        require(mapId);
        return floors.findByMapIdAndFloorNum(mapId, floorNum).map(MapFloor::points).orElse(null);
    }

    /**
     * 整体替换某楼层的点位表（契约 {@code replaceFloorPoints}）。
     *
     * <p><b>单楼层语义</b>：替换的是这个楼层的点位集合，别的楼层不受影响。楼层行不存在时直接建，
     * 因此「先 PUT 一个从没录过的楼层」不需要额外的建档接口。</p>
     *
     * <p>点位内容本身（{@code posNum} / {@code xPct} / {@code yPct} 的范围）由 API 层的 Bean
     * Validation 把关——那一层才知道契约的字段级约束，本层只保证「这张底图存在」与「写进去的是
     * 一份 JSON」。</p>
     */
    @Transactional
    public void replaceFloorPoints(UUID mapId, int floorNum, String pointsJson) {
        require(mapId);
        floors.upsertByMapIdAndFloorNum(mapId, floorNum, pointsJson);
    }

    // ── UWB 标定（契约 getUwbMeta / putUwbMeta）──────────────────────────────

    /**
     * 读取冻结的 {@code uwb_meta} JSON 文本。
     *
     * <p>尚未标定、或库里的文本已损坏（手工改库、旧格式残留）时一律 404
     * {@link ApiErrorCode#MAP_NOT_CALIBRATED}：现场要的是「这张图还没标定好，去标定」，
     * 而不是 500。</p>
     */
    public String uwbMeta(UUID mapId) {
        BaseMap map = require(mapId);
        String meta = map.uwbMeta();
        if (!calibrated(meta)) {
            throw new BusinessException(ApiErrorCode.MAP_NOT_CALIBRATED, "底图尚未做 UWB 标定：" + mapId);
        }
        return meta;
    }

    /**
     * 保存 {@code uwb_meta}（契约 {@code putUwbMeta}）。校验不通过抛 400
     * {@link ApiErrorCode#MAP_UWB_META_INCOMPLETE}。
     *
     * <p>只改 {@code uwb_meta} 一列，其余字段从当前行原样带回——与 {@link #update} 同一理由：
     * 标定不该顺手把底图名字或图片抹掉。</p>
     */
    @Transactional
    public void saveUwbMeta(UUID mapId, String uwbMetaJson) {
        BaseMap current = require(mapId);
        validateUwbMeta(uwbMetaJson, current.imageWidth(), current.imageHeight());
        maps.update(new BaseMap(current.id(), current.name(), current.mapType(), current.imagePath(),
                current.imageWidth(), current.imageHeight(), current.remark(), uwbMetaJson,
                current.createdAt(), current.updatedAt()));
    }

    // ── 读取模型 ────────────────────────────────────────────────────────────

    /**
     * {@code MapDetail} 组装所需的读取模型：底图本体 + 楼层数 + 标定齐备标志。
     *
     * <p>三个值一次读完，是为了让控制器不必为同一行反复回库（列表页每行一次
     * {@code count} 会变成 N+1）。</p>
     *
     * @param calibrated UWB 标定是否齐备。**只判「有没有」，不判「对不对」**：
     *                   {@code bs1~bs4} 与 {@code realLengthCm/realWidthCm} 全部有值即为齐备；
     *                   几何自洽的拒绝只发生在保存那一刻（{@link #saveUwbMeta}），
     *                   已经存进库的行不在读取时反悔——否则一次升级就会让既有底图突然读不出来
     */
    public record MapView(BaseMap map, long floorCount, boolean calibrated) {
    }

    private MapView view(BaseMap map) {
        return new MapView(map, floors.countByMapId(map.id()), calibrated(map.uwbMeta()));
    }

    private BaseMap require(UUID mapId) {
        return maps.findById(mapId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.MAP_NOT_FOUND, "底图不存在：" + mapId));
    }

    /**
     * {@code uwb_meta} 是否齐备。文本损坏（手工改库、旧格式残留）时按「不齐备」处理而不是抛异常：
     * 一张标定读不出来的底图不该让整个列表接口失败，现场需要的是「这张图还没标定好」而不是 500。
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
        for (String corner : REQUIRED_CORNERS) {
            JsonNode point = root.get(corner);
            if (point == null || !point.isObject() || !isNumber(point.get("xPx")) || !isNumber(point.get("yPx"))) {
                return false;
            }
        }
        return isNumber(root.get("realLengthCm")) && isNumber(root.get("realWidthCm"));
    }

    private static boolean isNumber(JsonNode node) {
        return node != null && node.isNumber();
    }

    /**
     * 保存前的 {@code uwb_meta} 校验（契约 {@code putUwbMeta} 的 400）。
     *
     * <p>只拦**结构与边界**：四角是否齐全、写了的可选基站是否成形、坐标是否落在底图宽高之内、
     * 四角是否退化成一条线、两个长宽基准是否为正整数厘米。</p>
     *
     * <p><b>刻意不做「实测长宽 vs 像素比例」的自洽校验</b>：{@code PROTOCOL.md} §6.6 那份**定稿示例**
     * 本身就不满足该比例（按像素包围盒换算出的两个方向尺度相差约 15%），拿它当判据会把文档给出的
     * 合法样例拒掉。严格反解仍归 {@code scds-solver} 的 {@code Geometry}——只有真正解算时才需要那份
     * 精度，保存侧替它预判只会多出第二套必然与解算器漂移的几何实现。</p>
     *
     * @param imageWidth  底图像素宽；未上传图片时为 {@code null}，此时不做横向越界校验
     * @param imageHeight 底图像素高；未上传图片时为 {@code null}，此时不做纵向越界校验
     */
    private static void validateUwbMeta(String uwbMeta, Integer imageWidth, Integer imageHeight) {
        JsonNode root = parseMeta(uwbMeta);
        double[] xs = new double[4];
        double[] ys = new double[4];
        for (int i = 0; i < 4; i++) {
            String name = "bs" + (i + 1);
            JsonNode point = root.get(name);
            if (!isPoint(point)) {
                throw incomplete(name + " 缺失或坐标不是数值");
            }
            checkInside(point, imageWidth, imageHeight, name);
            xs[i] = point.get("xPx").doubleValue();
            ys[i] = point.get("yPx").doubleValue();
        }
        for (int i = 5; i <= 8; i++) {
            String name = "bs" + i;
            JsonNode point = root.get(name);
            if (point == null || point.isNull()) {
                continue;
            }
            if (!isPoint(point)) {
                throw incomplete(name + " 写了但坐标不是数值");
            }
            checkInside(point, imageWidth, imageHeight, name);
        }
        // bs1↔bs2 是长度基准、bs1↔bs3 是宽度基准（PROTOCOL §6.6）：两向量的叉积就是这两条基准
        // 张成的平行四边形面积，四点共线时为 0。
        double cross = (xs[1] - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (ys[1] - ys[0]);
        if (Math.abs(cross) < DEGENERATE_AREA_PX) {
            throw incomplete("四角退化共线，提供不了长度/宽度基准");
        }
        requirePositiveWholeCm(root, "realLengthCm");
        requirePositiveWholeCm(root, "realWidthCm");
    }

    private static JsonNode parseMeta(String uwbMeta) {
        if (uwbMeta == null || uwbMeta.isBlank()) {
            throw incomplete("内容为空");
        }
        JsonNode root;
        try {
            root = JSON.readTree(uwbMeta);
        } catch (JsonProcessingException broken) {
            throw incomplete("不是合法 JSON 文本");
        }
        if (root == null || !root.isObject()) {
            throw incomplete("顶层必须是 JSON 对象");
        }
        return root;
    }

    private static boolean isPoint(JsonNode node) {
        return node != null && node.isObject() && isNumber(node.get("xPx")) && isNumber(node.get("yPx"));
    }

    /** 坐标越界只在知道底图宽高时才判：图片还没上传时没有可比的边界，此时放行。 */
    private static void checkInside(JsonNode point, Integer imageWidth, Integer imageHeight, String name) {
        double x = point.get("xPx").doubleValue();
        double y = point.get("yPx").doubleValue();
        if (imageWidth != null && (x < 0 || x > imageWidth)) {
            throw incomplete(name + " 的横坐标 " + x + " 越出底图宽度 " + imageWidth + " 像素");
        }
        if (imageHeight != null && (y < 0 || y > imageHeight)) {
            throw incomplete(name + " 的纵坐标 " + y + " 越出底图高度 " + imageHeight + " 像素");
        }
    }

    /** 契约与 §6.6 都要求现场实测长宽是**正整数厘米**：写成 0、负数或 25.4 都不收。 */
    private static void requirePositiveWholeCm(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (!isNumber(node)) {
            throw incomplete(field + " 缺失或不是数值");
        }
        double cm = node.doubleValue();
        if (cm <= 0 || cm != Math.rint(cm)) {
            throw incomplete(field + " 必须是正整数厘米（> 0），当前为 " + cm);
        }
    }

    private static BusinessException incomplete(String reason) {
        return new BusinessException(ApiErrorCode.MAP_UWB_META_INCOMPLETE, "UWB 标定参数不完整：" + reason);
    }
}
