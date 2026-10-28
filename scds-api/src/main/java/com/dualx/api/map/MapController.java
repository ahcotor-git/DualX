package com.dualx.api.map;

import com.dualx.api.dto.common.PageMeta;
import com.dualx.api.dto.map.FloorPoint;
import com.dualx.api.dto.map.FloorPointsRequest;
import com.dualx.api.dto.map.FloorPointsResponse;
import com.dualx.api.dto.map.MapDetail;
import com.dualx.api.dto.map.MapPage;
import com.dualx.api.dto.map.MapUpsertRequest;
import com.dualx.api.dto.map.UwbCalibrationRequest;
import com.dualx.api.dto.map.UwbCalibrationState;
import com.dualx.api.dto.map.UwbCalibrationStatus;
import com.dualx.api.dto.map.UwbMeta;
import com.dualx.core.domain.BaseMap;
import com.dualx.core.domain.MapType;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.map.MapLedgerService;
import com.dualx.core.map.UwbCalibrationService;
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
 * 与底图图片（{@code uploadMapImage} / {@code getMapImage}）走 {@link MapLedgerService}；
 * UWB 中心基准校准三端点（{@code startUwbCalibration} / {@code getUwbCalibration} /
 * {@code clearUwbCalibration}）走 {@link UwbCalibrationService}。
 * 本层只做契约形状的搬运（DTO ↔ 读取模型 ↔ 冻结 JSON ↔ 图片字节 ↔ 校准状态快照），
 * 「能不能删」「标定参数自不自洽」「这张图是不是真图片」「链路开没开」这类判断全在服务层
 * ——写进控制器会让同一规则在 REST 与将来的 WS 入口上各写一遍。</p>
 *
 * <p><b>校准状态机的存活期</b>：{@link UwbCalibrationService} 是进程内单例，它同时在
 * HTTP 请求线程（{@code start}/{@code status}/{@code clear}）与态势写手线程
 * （逐帧采样）上被调用；本层不做任何加锁或轮询，只把一次调用搬到一次响应上——
 * {@code startUwbCalibration} 是 202 语义（受理即返回，进度靠 {@code getUwbCalibration} 轮询），
 * 在这里等采样完成会把一个 HTTP 线程按采样帧数挂住。</p>
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

    /**
     * UWB 中心基准校准的进程内状态机（CAL-2）。
     *
     * <p>它同时被态势写手线程逐帧喂采样，因此**必须与本进程的那一份是同一实例**——
     * 装配在 {@code scds-app} 的 {@code SituationStateConfig}，这里只消费。</p>
     */
    private final UwbCalibrationService calibration;

    public MapController(MapLedgerService ledger, UwbCalibrationService calibration, ObjectMapper json,
                         FileUrlSigner signer) {
        this.ledger = ledger;
        this.calibration = calibration;
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
     *
     * <p><b>落库的是裸数组 {@code body.points()}，不是请求包体</b>：{@code FloorPointsRequest}
     * 只是这一层的内外包装，而 {@code t_map_floor.points} 的冻结结构就是
     * {@code [{posNum,xPct,yPct,label}]}。存成 {@code {"points":[…]}} 会让读回路径
     * （{@link #listFloorPoints}）按裸数组解析时直接失败——API 与库必须同形。</p>
     */
    @PutMapping("/api/v1/maps/{mapId}/floors/{floorNum}/points")
    public FloorPointsRequest replaceFloorPoints(@PathVariable String mapId, @PathVariable int floorNum,
                                                 @RequestBody @Valid FloorPointsRequest body) {
        ledger.replaceFloorPoints(mapId(mapId), floorNum, write(body.points()));
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

    /**
     * 触发 UWB 中心基准校准（异步：202 受理，进度靠 {@link #getUwbCalibration} 轮询）。
     *
     * <p>请求体整段可省（{@code @RequestBody(required = false)}）：不带体即「用配置的采样帧数、
     * 不改任何实测长宽」，但服务层会因为没有实测值而回 400 {@code MAP_UWB_META_INCOMPLETE}——
     * 契约要的是「至少一个卷尺实测值才能写回」，这个判据在服务层，本层不重写一遍。</p>
     *
     * <p>回 202 而不是 200：响应体里那份 {@code RUNNING} 是**受理时刻**的快照，采样在
     * 态势写手线程上继续跑。前端不该把它当作终态——终态只能靠轮询或下一次读状态拿到。</p>
     */
    @PostMapping("/api/v1/maps/{mapId}/uwb-calibration")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UwbCalibrationStatus startUwbCalibration(
            @PathVariable String mapId, @RequestBody(required = false) @Valid UwbCalibrationRequest body) {
        UwbCalibrationRequest request = body == null ? new UwbCalibrationRequest(null, null, null) : body;
        return toStatus(calibration.start(mapId(mapId), request.sampleFrames(),
                request.bs1ToBs2Cm(), request.bs1ToBs3Cm()));
    }

    /**
     * 查询 UWB 校准状态。
     *
     * <p>这张图从没标过时回 200 + {@code IDLE}，不是 404：与 {@link #getUwbMeta} 的
     * {@code MAP_NOT_CALIBRATED} 不同——那里「没有四角骨架」是缺资源，这里「还没开始标定」
     * 是正常状态，前端拿到 {@code IDLE} 就知道可以发起下一次。</p>
     */
    @GetMapping("/api/v1/maps/{mapId}/uwb-calibration")
    public UwbCalibrationStatus getUwbCalibration(@PathVariable String mapId) {
        return toStatus(calibration.status(mapId(mapId)));
    }

    /**
     * 清除 UWB 校准状态（**也承担取消**：正在跑的那次会被一并丢弃）。
     *
     * <p>只清进程内状态。已被写回 {@code uwb_meta} 的实测长宽是现场原创数据，清除标定不等于
     * 把它们改回去——要改回去就再标一次或走 {@code putUwbMeta}。</p>
     */
    @DeleteMapping("/api/v1/maps/{mapId}/uwb-calibration")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearUwbCalibration(@PathVariable String mapId) {
        calibration.clear(mapId(mapId));
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
     * core 的标定状态快照 → 契约 {@code UwbCalibrationStatus}。
     *
     * <p>状态枚举用 {@code switch} 逐项映射而不是 {@code valueOf(name())}：两侧枚举目前逐字同名，
     * 但它们是两层各自的定义，{@code valueOf} 会在某天只改一边时**运行期**才炸；{@code switch}
     * 缺项直接编译不过。</p>
     *
     * <p>{@code sampledFrames} 从 core 的 {@code int} 装箱成契约的 {@code Integer}——
     * 契约里这个字段可空（从来没有过标定时给 0 更直白，故实际恒非 null）。</p>
     */
    private static UwbCalibrationStatus toStatus(UwbCalibrationService.Status status) {
        UwbCalibrationState state = switch (status.state()) {
            case IDLE -> UwbCalibrationState.IDLE;
            case RUNNING -> UwbCalibrationState.RUNNING;
            case SUCCEEDED -> UwbCalibrationState.SUCCEEDED;
            case FAILED -> UwbCalibrationState.FAILED;
        };
        return new UwbCalibrationStatus(state, status.progressPct(), status.sampledFrames(),
                status.appliedAt(), status.message());
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
                view.calibratedAt(), map.createdAt(), map.updatedAt());
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
