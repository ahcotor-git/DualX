package com.dualx.api.map;

import com.dualx.api.dto.common.PageMeta;
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

import java.util.UUID;

/**
 * map 域：底图 / 楼层点位表 / UWB 坐标标定（契约 docs/api/paths/map.yaml）。
 *
 * <p><b>已接线</b>：底图台账 CRUD（{@code listMaps} / {@code createMap} / {@code getMap} /
 * {@code updateMap} / {@code deleteMap}）走 {@link MapLedgerService}。本层只做契约形状的搬运
 * （DTO ↔ 读取模型），「能不能删」这类判断全在服务层——写进控制器会让同一规则在 REST 与
 * 将来的 WS 入口上各写一遍。</p>
 *
 * <p><b>仍是占位（501）</b>：图片上传 / 下载（{@code uploadMapImage} / {@code getMapImage}，
 * 需 {@code FileStore} 与真实图片头校验）、楼层点位表、UWB 标定四个端点。它们各自
 * 属后续段落，本段只保证路由与 DTO 已按契约立起。</p>
 *
 * <p>不设类级路径前缀，逐方法写完整绝对路径。</p>
 */
@RestController
@Validated
public class MapController {

    private final MapLedgerService ledger;

    public MapController(MapLedgerService ledger) {
        this.ledger = ledger;
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

    // ── 楼层点位表（占位：随楼层段落落地）────────────────────────────────────

    /** 楼层点位表。 */
    @GetMapping("/api/v1/maps/{mapId}/floors/{floorNum}/points")
    public FloorPointsResponse listFloorPoints(@PathVariable String mapId, @PathVariable int floorNum) {
        throw new NotImplementedException("listFloorPoints");
    }

    /** 整体替换楼层点位表。 */
    @PutMapping("/api/v1/maps/{mapId}/floors/{floorNum}/points")
    public FloorPointsRequest replaceFloorPoints(@PathVariable String mapId, @PathVariable int floorNum,
                                                 @RequestBody @Valid FloorPointsRequest body) {
        throw new NotImplementedException("replaceFloorPoints");
    }

    // ── UWB 坐标标定（占位：随标定段落落地）──────────────────────────────────

    /** 获取 UWB 坐标标定参数（旧 uwb_meta.json）。 */
    @GetMapping("/api/v1/maps/{mapId}/uwb-meta")
    public UwbMeta getUwbMeta(@PathVariable String mapId) {
        throw new NotImplementedException("getUwbMeta");
    }

    /** 保存 UWB 坐标标定参数。 */
    @PutMapping("/api/v1/maps/{mapId}/uwb-meta")
    public UwbMeta putUwbMeta(@PathVariable String mapId, @RequestBody @Valid UwbMeta body) {
        throw new NotImplementedException("putUwbMeta");
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
