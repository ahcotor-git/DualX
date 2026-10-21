package com.dualx.api.map;

import com.dualx.api.dto.common.PageMeta;
import com.dualx.api.dto.map.FloorPoint;
import com.dualx.api.dto.map.FloorPointsRequest;
import com.dualx.api.dto.map.FloorPointsResponse;
import com.dualx.api.dto.map.MapDetail;
import com.dualx.api.dto.map.MapPage;
import com.dualx.api.dto.map.MapUpsertRequest;
import com.dualx.api.dto.map.UwbCalibrationRequest;
import com.dualx.api.dto.map.UwbCalibrationStatus;
import com.dualx.api.dto.map.UwbMeta;
import com.dualx.api.error.NotImplementedException;
import com.dualx.core.domain.BaseMap;
import com.dualx.core.domain.MapType;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.map.MapLedgerService;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * map 域：底图 / 楼层点位表 / UWB 坐标标定（契约 docs/api/paths/map.yaml）。
 *
 * <p><b>已接线</b>：底图台账 CRUD（{@code listMaps} / {@code createMap} / {@code getMap} /
 * {@code updateMap} / {@code deleteMap}）、楼层点位表（{@code listFloorPoints} /
 * {@code replaceFloorPoints}）与 UWB 标定参数（{@code getUwbMeta} / {@code putUwbMeta}）
 * 走 {@link MapLedgerService}。本层只做契约形状的搬运（DTO ↔ 读取模型 ↔ 冻结 JSON），
 * 「能不能删」「标定参数自不自洽」这类判断全在服务层——写进控制器会让同一规则在 REST 与
 * 将来的 WS 入口上各写一遍。</p>
 *
 * <p><b>仍是占位（501）</b>：图片上传 / 下载（{@code uploadMapImage} / {@code getMapImage}，
 * 需 {@code FileStore} 与真实图片头校验）与 UWB 校准三端点（{@code startUwbCalibration} /
 * {@code getUwbCalibration} / {@code clearUwbCalibration}，需 {@code scds-gateway} 的采样端口
 * 与校准状态存储，本仓库尚不具备）。它们各自属后续段落，本段只保证路由与 DTO 已按契约立起。</p>
 *
 * <p>不设类级路径前缀，逐方法写完整绝对路径。</p>
 */
@RestController
@Validated
public class MapController {

    private final MapLedgerService ledger;

    /**
     * 冻结 JSON（{@code t_map.uwb_meta} 文本列与楼层点位数组）在这层的进出靠它：
     * 契约 DTO 与库内文本因此共用一份结构定义，不需要手写字符串拼接。
     */
    private final ObjectMapper json;

    public MapController(MapLedgerService ledger, ObjectMapper json) {
        this.ledger = ledger;
        this.json = json;
    }

    // ── 底图台账 ────────────────────────────────────────────────────────────

    /** 底图列表。 */
    @GetMapping("/api/v1/maps")
    public MapPage listMaps(@RequestParam(required = false) MapType type,
                            @RequestParam(required = false, defaultValue = "1") @Min(1) int page,
                            @RequestParam(required = false, defaultValue = "20")
                            @Min(1) @Max(PageRequest.MAX_SIZE) int size) {
        Page<MapLedgerService.MapView> result = ledger.list(type, PageRequest.of(page, size));
        return new MapPage(result.items().stream().map(MapController::toDetail).toList(),
                new PageMeta(result.page(), result.size(), result.total()));
    }

    /** 新建底图。 */
    @PostMapping("/api/v1/maps")
    @ResponseStatus(HttpStatus.CREATED)
    public MapDetail createMap(@RequestBody @Valid MapUpsertRequest body) {
        return toDetail(ledger.create(body.name(), body.type()));
    }

    /** 底图详情。 */
    @GetMapping("/api/v1/maps/{mapId}")
    public MapDetail getMap(@PathVariable String mapId) {
        return toDetail(ledger.get(mapId(mapId)));
    }

    /** 修改底图。 */
    @PutMapping("/api/v1/maps/{mapId}")
    public MapDetail updateMap(@PathVariable String mapId, @RequestBody @Valid MapUpsertRequest body) {
        return toDetail(ledger.update(mapId(mapId), body.name(), body.type()));
    }

    /** 删除底图；已被想定引用时返回 409，不做级联删除。 */
    @DeleteMapping("/api/v1/maps/{mapId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMap(@PathVariable String mapId) {
        ledger.delete(mapId(mapId));
    }

    // ── 底图图片（占位：随图片段落落地）──────────────────────────────────────

    /** 上传底图图片（multipart/form-data，文件字段名 file）。 */
    @PostMapping("/api/v1/maps/{mapId}/image")
    public MapDetail uploadMapImage(@PathVariable String mapId, @RequestParam("file") MultipartFile file) {
        throw new NotImplementedException("uploadMapImage");
    }

    /** 下载底图图片（image/png 或 image/jpeg 二进制）。 */
    @GetMapping("/api/v1/maps/{mapId}/image")
    public ResponseEntity<byte[]> getMapImage(@PathVariable String mapId) {
        throw new NotImplementedException("getMapImage");
    }

    // ── 楼层点位表 ──────────────────────────────────────────────────────────

    /**
     * 楼层点位表。
     *
     * <p>该楼层还没录过点位表时返回**空列表**而不是 404：「这一层还没标点」是正常状态，
     * 前端拿到的就是「零个点位」。</p>
     */
    @GetMapping("/api/v1/maps/{mapId}/floors/{floorNum}/points")
    public FloorPointsResponse listFloorPoints(@PathVariable String mapId, @PathVariable int floorNum) {
        String points = ledger.floorPoints(mapId(mapId), floorNum);
        List<FloorPoint> parsed = points == null || points.isBlank()
                ? List.of() : read(points, new TypeReference<List<FloorPoint>>() {
                });
        return new FloorPointsResponse(parsed);
    }

    /**
     * 整体替换楼层点位表（**单楼层**语义：别的楼层不受影响）。
     *
     * <p>回显请求体而不是回读库：这是一次全量替换，响应就是替换后的点位集合；回读只会多一次
     * 往返，还要面对 MySQL 对 JSON 列的键序重排（值相同、文本不同），徒增无意义的比对成本。</p>
     */
    @PutMapping("/api/v1/maps/{mapId}/floors/{floorNum}/points")
    public FloorPointsRequest replaceFloorPoints(@PathVariable String mapId, @PathVariable int floorNum,
                                                 @RequestBody @Valid FloorPointsRequest body) {
        ledger.replaceFloorPoints(mapId(mapId), floorNum, write(body));
        return body;
    }

    // ── UWB 坐标标定 ────────────────────────────────────────────────────────

    /** 获取 UWB 坐标标定参数（旧 uwb_meta.json）；尚未标定时 404 MAP_NOT_CALIBRATED。 */
    @GetMapping("/api/v1/maps/{mapId}/uwb-meta")
    public UwbMeta getUwbMeta(@PathVariable String mapId) {
        return read(ledger.uwbMeta(mapId(mapId)), UwbMeta.class);
    }

    /** 保存 UWB 坐标标定参数；基站缺项或几何不自洽时 400 MAP_UWB_META_INCOMPLETE。 */
    @PutMapping("/api/v1/maps/{mapId}/uwb-meta")
    public UwbMeta putUwbMeta(@PathVariable String mapId, @RequestBody @Valid UwbMeta body) {
        ledger.saveUwbMeta(mapId(mapId), write(body));
        return body;
    }

    /** 触发 UWB 中心基准校准（异步，用 GET 查进度）。 */
    @PostMapping("/api/v1/maps/{mapId}/uwb-calibration")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UwbCalibrationStatus startUwbCalibration(
            @PathVariable String mapId, @RequestBody(required = false) @Valid UwbCalibrationRequest body) {
        throw new NotImplementedException("startUwbCalibration");
    }

    /** 查询 UWB 校准状态。 */
    @GetMapping("/api/v1/maps/{mapId}/uwb-calibration")
    public UwbCalibrationStatus getUwbCalibration(@PathVariable String mapId) {
        throw new NotImplementedException("getUwbCalibration");
    }

    /** 清除 UWB 校准结果。 */
    @DeleteMapping("/api/v1/maps/{mapId}/uwb-calibration")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearUwbCalibration(@PathVariable String mapId) {
        throw new NotImplementedException("clearUwbCalibration");
    }

    // ── 契约形状的搬运 ──────────────────────────────────────────────────────

    /**
     * 路径参数转 UUID。解析失败按「底图不存在」处理而不是 400：一个拼不出来的 id 必然
     * 不对应任何一行，语义上就是不存在；契约的 map 域也只有 {@code MAP_NOT_FOUND} 这一个
     * id 失败口径，为它新增「id 格式错」会逼前端多写一条与 404 完全同分支的处理。
     */
    private static UUID mapId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ApiErrorCode.MAP_NOT_FOUND, "底图不存在：" + raw);
        }
    }

    /**
     * 库内冻结 JSON → DTO。
     *
     * <p>读不出来说明库里存了不是本服务写入的内容（手工改库、历史残留），属服务端数据缺陷：
     * 让它走 500 并在日志里留下 ERROR，比返回一个半空对象更容易在联调期被抓到。</p>
     */
    private <T> T read(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException("底图冻结 JSON 读不出来", broken);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException("底图冻结 JSON 读不出来", broken);
        }
    }

    /** DTO → 库内冻结 JSON。序列化的是刚过 Bean Validation 的契约形状，正常不可能失败。 */
    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException("底图冻结 JSON 写不出去", broken);
        }
    }

    /**
     * {@code MapDetail} 组装。
     *
     * <p>{@code imageUrl} 本段恒为 null：D09-② 只定了「短时效、可重复签名、不带
     * Authorization」，**取文件的路径形状与查询参数名还没在契约里定下**，而图片上传接口
     * 本身也是 501——本段所有底图的 {@code image_path} 必为空。图片段落接线时在这里接
     * {@code FileUrlSigner}；先编一个 URL 形状会让契约、前端与后续实现三处跟着改。</p>
     */
    private static MapDetail toDetail(MapLedgerService.MapView view) {
        BaseMap map = view.map();
        return new MapDetail(map.id().toString(), map.name(), map.mapType(), null,
                map.imageWidth(), map.imageHeight(), (int) view.floorCount(), view.calibrated(),
                map.createdAt(), map.updatedAt());
    }
}
