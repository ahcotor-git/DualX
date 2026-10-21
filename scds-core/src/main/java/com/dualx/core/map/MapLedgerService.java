package com.dualx.core.map;

import com.dualx.core.domain.BaseMap;
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
 * <p><b>本段只做台账</b>：底图图片的上传 / 下载 / 签名 URL 与「换图即删旧文件」不在本类，
 * 属图片段落（需 {@code FileStore} + {@code FileUrlSigner} 与图片头校验）；
 * 因此 {@link #delete} 当前只删行、不删盘上的图片文件——留下的是孤立文件而非错误数据，
 * 图片段落落地时在此处补一次 {@code FileStore.delete(imagePath)} 即可。记为已知缺口。</p>
 *
 * <p><b>状态映射缺口</b>：{@link ApiErrorCode#MAP_NOT_FOUND} 与
 * {@link ApiErrorCode#MAP_IN_USE} 尚未登记到 {@code ApiExceptionHandler} 的状态表
 * （该表刻意不按前缀猜），接线段落必须补齐，否则本类抛出的业务失败会以 500 露出。</p>
 */
@Service
public class MapLedgerService {

    /** {@code uwb_meta} 是冻结的扁平 JSON（见 {@code BaseMap}），四角与两个长宽基准缺一不可。 */
    private static final List<String> REQUIRED_CORNERS = List.of("bs1", "bs2", "bs3", "bs4");

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

    /**
     * {@code MapDetail} 组装所需的读取模型：底图本体 + 楼层数 + 标定齐备标志。
     *
     * <p>三个值一次读完，是为了让控制器不必为同一行反复回库（列表页每行一次
     * {@code count} 会变成 N+1）。</p>
     *
     * @param calibrated UWB 标定是否齐备。**只判「有没有」，不判「对不对」**：
     *                   {@code bs1~bs4} 与 {@code realLengthCm/realWidthCm} 全部有值即为齐备；
     *                   几何自洽（矩形是否退化成线、实测长宽是否与坐标比例相符）属
     *                   {@code putUwbMeta} 的保存校验，在标定段落落地
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
}
