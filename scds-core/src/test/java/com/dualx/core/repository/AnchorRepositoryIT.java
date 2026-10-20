package com.dualx.core.repository;

import com.dualx.core.domain.Anchor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class AnchorRepositoryIT {

    @Autowired
    private AnchorRepository anchors;

    private static Anchor anchor(int anchorId) {
        return new Anchor(anchorId, null, null, null, true, null, null);
    }

    @Test
    void insertThenFindByAnchorId_roundTripsEveryColumn() {
        Anchor drafted = new Anchor(1, "西北角", 0.1, 0.2, false, null, null);

        assertThat(anchors.insert(drafted)).isEqualTo(1);

        Anchor loaded = anchors.findByAnchorId(1).orElseThrow();
        assertThat(loaded.anchorId()).isEqualTo(1);
        assertThat(loaded.name()).isEqualTo("西北角");
        assertThat(loaded.xPx()).isEqualTo(0.1);
        assertThat(loaded.yPx()).isEqualTo(0.2);
        assertThat(loaded.enabled()).isFalse();
        assertThat(loaded.createdAt()).isNotNull();
        assertThat(loaded.updatedAt()).isNotNull();
    }

    @Test
    void coordinates_mayBeNullBeforeCalibration() {
        assertThat(anchors.insert(anchor(2))).isEqualTo(1);

        Anchor loaded = anchors.findByAnchorId(2).orElseThrow();
        assertThat(loaded.name()).isNull();
        assertThat(loaded.xPx()).isNull();
        assertThat(loaded.yPx()).isNull();
        assertThat(loaded.enabled()).isTrue();
    }

    @Test
    void findByAnchorId_returnsEmptyWhenAbsent() {
        assertThat(anchors.findByAnchorId(8)).isEmpty();
        assertThat(anchors.existsByAnchorId(8)).isFalse();
    }

    @Test
    void findAll_ordersByAnchorId() {
        anchors.insert(anchor(4));
        anchors.insert(anchor(1));
        anchors.insert(anchor(8));

        List<Anchor> all = anchors.findAll();

        assertThat(all).extracting(Anchor::anchorId).containsExactly(1, 4, 8);
        assertThat(anchors.count()).isEqualTo(3);
    }

    @Test
    void duplicateAnchorId_isRejectedByPrimaryKey() {
        anchors.insert(anchor(5));

        assertThatThrownBy(() -> anchors.insert(anchor(5)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anchorIdOutside1To8_isRejectedByCheck() {
        // 41B 帧只有 ranges[0..7]，即 bs1~bs8
        assertThatThrownBy(() -> anchors.insert(anchor(9)))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_anchor_id");
    }

    @Test
    void update_changesMutableColumnsOnly() {
        anchors.insert(new Anchor(3, "旧名", 0.1, 0.1, true, null, null));

        assertThat(anchors.update(new Anchor(3, "东南角", 0.9, 0.9, false, null, null))).isEqualTo(1);

        Anchor loaded = anchors.findByAnchorId(3).orElseThrow();
        assertThat(loaded.name()).isEqualTo("东南角");
        assertThat(loaded.xPx()).isEqualTo(0.9);
        assertThat(loaded.yPx()).isEqualTo(0.9);
        assertThat(loaded.enabled()).isFalse();
    }

    @Test
    void deleteByAnchorId_removesRow() {
        anchors.insert(anchor(6));

        assertThat(anchors.deleteByAnchorId(6)).isEqualTo(1);
        assertThat(anchors.findByAnchorId(6)).isEmpty();
        assertThat(anchors.deleteByAnchorId(6)).isZero();
    }
}
