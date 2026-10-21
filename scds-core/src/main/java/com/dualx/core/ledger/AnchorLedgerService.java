package com.dualx.core.ledger;

import com.dualx.core.domain.Anchor;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.repository.AnchorRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * UWB 基站台账（契约 {@code /api/v1/devices/anchors}，V2 文件头）。
 *
 * <p>只写 {@code t_anchor}。**不**在这里改 {@code t_map.uwb_meta}：那张表的
 * {@code bsN} 是「某一张底图」自己的标定，而本表是全局设备台账，两者不是同一维度的对象
 * ——「当前在用底图」在库里没有定义（{@code t_map} 无「在用」标志，演习只是快照了 mapId）。
 * V2 文件头把同步写在了服务层，但落点必须等地图域（{@code PUT /maps/{id}/uwb-meta} 与
 * {@code calibrated} 判定）落地时一并定，否则这里只能猜一个目标图。此处记为已知缺口。</p>
 */
@Service
public class AnchorLedgerService {

    /** {@code bs1}~{@code bs4} 是矩形四角：解算的长度/宽度基准就从它们两两之间取值（契约 UwbMeta）。 */
    private static final int REQUIRED_CORNER_COUNT = 4;

    private final AnchorRepository anchors;

    public AnchorLedgerService(AnchorRepository anchors) {
        this.anchors = anchors;
    }

    /** 台账全量，按基线编号升序（与 41B 帧 ranges 的下标顺序一致，前端不必再排）。 */
    public List<Anchor> list() {
        return anchors.findAll();
    }

    /**
     * 配置一个基站：不存在则建档，存在则就地改写（契约的 PUT 语义即「全量替换该资源」）。
     *
     * <p>拒绝关闭 {@code bs1}~{@code bs4}。契约把「四角必开」写进了字段说明，却又把
     * {@code enabled} 定义成可自由提交的布尔量——两者只能由服务端兜住：四角关掉一个，
     * 测距仍在、解算必然错位，且现场表现为「位置整体漂移」而不是报错，极难回溯到配置。
     * 用 {@code VALIDATION_FAILED}（400）而不是新错误码：契约里本接口只有 400，
     * 且这确实是请求体自身的取值问题。</p>
     */
    public Anchor upsert(int anchorId, AnchorDraft draft) {
        if (anchorId <= REQUIRED_CORNER_COUNT && !draft.enabled()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED,
                    "bs1~bs4 为矩形四角，必须保持启用：" + anchorId);
        }
        // created_at 由库的 DEFAULT 负责，改写时不回写，避免把建档时间刷成修改时间
        anchors.findByAnchorId(anchorId)
                .ifPresentOrElse(
                        existing -> anchors.update(new Anchor(anchorId, draft.name(), draft.xPx(), draft.yPx(),
                                draft.enabled(), existing.createdAt(), existing.updatedAt())),
                        () -> anchors.insert(new Anchor(anchorId, draft.name(), draft.xPx(), draft.yPx(),
                                draft.enabled(), null, null)));
        return anchors.findByAnchorId(anchorId).orElseThrow(() -> new IllegalStateException(
                "基站写入后读不回，t_anchor 状态异常：" + anchorId));
    }

    /**
     * @param xPx 底图归一化横坐标 0~1（契约 AnchorUpsertRequest）
     * @param yPx 底图归一化纵坐标 0~1
     */
    public record AnchorDraft(String name, double xPx, double yPx, boolean enabled) {
    }
}
