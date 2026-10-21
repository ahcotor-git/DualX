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
import com.dualx.core.port.FileUrlSigner;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * map 域：底图 / 楼层点位表 / UWB 坐标标定 / 底图图片（契约 docs/api/paths/map.yaml）。
 *
 * <p><b>已接线</b>：底图台账 CRUD（{@code listMaps} / {@code createMap} / {@code getMap} /
 * {@code updateMap} / {@code deleteMap}）、楼层点位表（{@code listFloorPoints} /
 * {@code replaceFloorPoints}）、UWB 标定参数（{@code getUwbMeta} / {@code putUwbMeta}）
 * 与底图图片（{@code uploadMapImage} / {@code getMapImage}）走 {@link MapLedgerService}。
 * 本层只做契约形状的搬运（DTO ↔ 读取模型 ↔ 冻结 JSON ↔ 图片字节），「能不能删」
 * 「标定参数自不自洽」「这张图是不是真图片」这类判断全在服务层——写进控制器会让同一规则
 * 在 REST 与将来的 WS 入口上各写一遍。</p>
 *
 * <p><b>仍是占位（501）</b>：UWB 校准三端点（{@code startUwbCalibration} /
 * {@code getUwbCalibration} / {@code clearUwbCalibration}，需 {@code scds-gateway} 的采样端口
 * 与校准状态存储，本仓库尚不具备）。它们属后续段落，本段只保证路由与 DTO 已按契约立起。</p>
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

    /**
     * 把 {@code t_map.image_path} 签成 {@code MapDetail.imageUrl}。
     *
     * <p>签名本身在 core（两个信任域用不同密钥，D09-⑤），URL 形状属接口层——
     * 端口只管「签什么、验得过不过」，前缀与查询参数名归这里（见 {@code FileUrlSigner} 注释）。</p>
     */
    private final FileUrlSigner signer;

    public MapController(MapLedgerService ledger, ObjectMapper json, FileUrlSigner signer) {
        this.ledger = ledger;
        this.json = json;
        this.signer = signer;
    }

    // ── 底图台账 ────────────────────────────────────────────────────────────

    /** 底图列表。 */
    @GetMapping("/api/v1/maps")
    public MapPage listMaps(@RequestParam(required = false) MapType type,
                            @RequestParam(required = false, defaultValue = "1") @Min(1) int page,
                            @RequestParam(required = false, defaultValue = "20")
                            @Min(1) @Max(PageRequest.MAX_SIZE) int size) {
        Page<MapLedgerService.MapView> result = ledger.list(type, PageRequest.of(page, size));
        return new MapPage(result.items().stream().map(this::toDetail).toList(),
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

    // ── 底图图片 ────────────────────────────────────────────────────────────

    /**
     * 上传底图图片（multipart/form-data，文件字段名 file）。
     *
     * <p>回 200 而不是 201：契约把本操作定义成「同一底图重复上传即替换」，
     * 资源本身（底图）早就存在，变的只是它的一张图。</p>
     */
    @PostMapping("/api/v1/maps/{mapId}/image")
    public MapDetail uploadMapImage(@PathVariable String mapId, @RequestParam("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            // 客户端中断或临时文件被清理：请求体已经收不全，这不是「图片不合法」，属服务端无法完成。
            throw new UncheckedIOException("读取上传的底图文件失败", e);
        }
        return toDetail(ledger.uploadImage(mapId(mapId), content));
    }

    /**
     * 下载底图图片（image/png、image/jpeg 或 image/webp 二进制）。
     *
     * <p>Content-Type 取自服务层按真实文件头判定的格式，不信 {@code image_path} 的扩展名。
     * 本路径要 Bearer；供 {@code <img>} 直接加载的是 {@code MapDetail.imageUrl} 里的签名 URL。</p>
     */
    @GetMapping("/api/v1/maps/{mapId}/image")
    public ResponseEntity<byte[]> getMapImage(@PathVariable String mapId) {
        MapLedgerService.MapImage image = ledger.readImage(mapId(mapId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.format().contentType()))
                .body(image.content());
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
     * <p>{@code imageUrl} 每次读详情都**重新签发**而不是缓存：TTL 只有 15 分钟（D09-②），
     * 缓存下来的 URL 会在客户端手里悄悄过期，而重新签发只是一次 HMAC 计算。</p>
     */
    private MapDetail toDetail(MapLedgerService.MapView view) {
        BaseMap map = view.map();
        return new MapDetail(map.id().toString(), map.name(), map.mapType(), imageUrl(map),
                map.imageWidth(), map.imageHeight(), (int) view.floorCount(), view.calibrated(),
                map.createdAt(), map.updatedAt());
    }

    /**
     * 为底图图片签发短时效访问 URL（D09-②）。
     *
     * <p>返回**根相对路径**，不带 scheme/host：服务端不猜部署形态下的对外地址（本地实例 +
     * 云端反向代理，见契约 {@code MapDetail.imageUrl}）。三个查询参数的名字属于接口层，
     * 必须与 {@code paths/files.yaml} 逐字一致——客户端只会原样转发，它无法自己纠正。</p>
     *
     * @return 尚未上传图片时为 {@code null}
     */
    private String imageUrl(BaseMap map) {
        String path = map.imagePath();
        if (path == null) {
            return null;
        }
        FileUrlSigner.SignedFileUrl signed = signer.sign(path);
        // path 与 signature 都过一遍编码：前者含 '/'，后者是 Base64 URL-safe（含 '-'、'_'），
        // 虽不解码也能用，但编码后才算一个任何浏览器与代理都不会改写的合法查询串。
        return "/api/v1/files?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8)
                + "&exp=" + URLEncoder.encode(signed.exp(), StandardCharsets.UTF_8)
                + "&signature=" + URLEncoder.encode(signed.signature(), StandardCharsets.UTF_8);
    }
}
