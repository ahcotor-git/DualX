package com.dualx.core.ledger;

import com.dualx.core.domain.Anchor;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.repository.AnchorRepository;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基站台账服务的真实数据库行为（契约 {@code paths/device.yaml} → anchors / anchorById）。
 *
 * <p>带测试外层事务：本类会改写 {@code t_anchor} 里可能已存在的 1~8 号档案，
 * 用例结束整体回滚，不碰本地已配置的基站坐标。</p>
 */
@SpringBootTest
@Transactional
class AnchorLedgerServiceIT {

    /** V2 只建表、不种行，但开发库里可能已被手工配过：建档用例据此判断，断言不假设初始为空。 */
    @Autowired private AnchorLedgerService ledger;
    @Autowired private AnchorRepository anchors;

    @Test
    @DisplayName("配置不存在的基站 → 建档，created_at 由库填")
    void upsert_absentAnchor_createsIt() {
        int anchorId = absentAnchorId();

        Anchor saved = ledger.upsert(anchorId, new AnchorLedgerService.AnchorDraft("北角", 0.2, 0.15, true));

        assertThat(saved.anchorId()).isEqualTo(anchorId);
        assertThat(saved.name()).isEqualTo("北角");
        assertThat(saved.xPx()).isEqualTo(0.2);
        assertThat(saved.yPx()).isEqualTo(0.15);
        assertThat(saved.enabled()).isTrue();
        assertThat(saved.createdAt()).isNotNull();
        assertThat(anchors.findByAnchorId(anchorId)).isPresent();
    }

    @Test
    @DisplayName("再次配置同一编号 → 就地改写，created_at 不动")
    void upsert_existingAnchor_updatesInPlace() {
        int anchorId = absentAnchorId();
        Anchor first = ledger.upsert(anchorId, new AnchorLedgerService.AnchorDraft("旧名", 0.1, 0.1, true));
        long rowsAfterInsert = anchors.count();

        Anchor second = ledger.upsert(anchorId, new AnchorLedgerService.AnchorDraft("新名", 0.9, 0.8, true));

        assertThat(second.name()).isEqualTo("新名");
        assertThat(second.xPx()).isEqualTo(0.9);
        // 已存在就地改写，不该多出一行
        assertThat(anchors.count()).isEqualTo(rowsAfterInsert);
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
    }

    @Test
    @DisplayName("bs5~bs8 可关闭：现场只布四角时不该被拦")
    void upsert_optionalAnchor_canBeDisabled() {
        int anchorId = 8;

        Anchor saved = ledger.upsert(anchorId, new AnchorLedgerService.AnchorDraft("备用", 0.5, 0.5, false));

        assertThat(saved.enabled()).isFalse();
    }

    @Test
    @DisplayName("bs1~bs4 关闭被拒：四角缺一，解算错位且现场只表现为位置漂移")
    void upsert_disablingRectangleCorner_isValidationFailed() {
        for (int corner = 1; corner <= 4; corner++) {
            int anchorId = corner;
            assertCode(() -> ledger.upsert(anchorId,
                    new AnchorLedgerService.AnchorDraft("角", 0.1, 0.1, false)), ApiErrorCode.VALIDATION_FAILED);
        }
    }

    @Test
    void list_isOrderedByAnchorId() {
        List<Integer> ids = ledger.list().stream().map(Anchor::anchorId).toList();

        assertThat(ids).isSorted();
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    /** 取一个当前没有档案的编号：索引列表最短、行为最可预期。 */
    private int absentAnchorId() {
        return IntStream.rangeClosed(1, 8)
                .filter(id -> anchors.findByAnchorId(id).isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("1~8 号基站都已有档案，用例需要先清库"));
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ApiErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
