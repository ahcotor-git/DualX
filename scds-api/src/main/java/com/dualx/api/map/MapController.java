package com.dualx.api.map;

import com.dualx.api.dto.map.FloorPointsRequest;
import com.dualx.api.dto.map.FloorPointsResponse;
import com.dualx.api.dto.map.MapDetail;
import com.dualx.api.dto.map.MapPage;
import com.dualx.api.dto.map.MapUpsertRequest;
import com.dualx.api.dto.map.UwbCalibrationRequest;
import com.dualx.api.dto.map.UwbCalibrationStatus;
import com.dualx.api.dto.map.UwbMeta;
import com.dualx.api.error.NotImplementedException;
import com.dualx.core.domain.MapType;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/**
 * map 域：底图 / 楼层点位表 / UWB 坐标标定。
 *
 * <p>骨架期只登记契约路由（见 docs/api/paths/map.yaml）：每个方法体统一抛
 * {@link NotImplementedException}，由全局异常出口折叠成 501。
 * 不设类级路径前缀，逐方法写完整绝对路径。</p>
 */
@RestController
public class MapController {

    /** 底图列表。 */
    @GetMapping("/api/v1/maps")
    public MapPage listMaps(@RequestParam(required = false) MapType type,
                            @RequestParam(required = false, defaultValue = "1") int page,
                            @RequestParam(required = false, defaultValue = "20") int size) {
        throw new NotImplementedException("listMaps");
    }

    /** 新建底图。 */
    @PostMapping("/api/v1/maps")
    @ResponseStatus(HttpStatus.CREATED)
    public MapDetail createMap(@RequestBody @Valid MapUpsertRequest body) {
        throw new NotImplementedException("createMap");
    }

    /** 底图详情。 */
    @GetMapping("/api/v1/maps/{mapId}")
    public MapDetail getMap(@PathVariable String mapId) {
        throw new NotImplementedException("getMap");
    }

    /** 修改底图。 */
    @PutMapping("/api/v1/maps/{mapId}")
    public MapDetail updateMap(@PathVariable String mapId, @RequestBody @Valid MapUpsertRequest body) {
        throw new NotImplementedException("updateMap");
    }

    /** 删除底图；已被想定引用时返回 409，不做级联删除。 */
    @DeleteMapping("/api/v1/maps/{mapId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMap(@PathVariable String mapId) {
        throw new NotImplementedException("deleteMap");
    }

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
}
