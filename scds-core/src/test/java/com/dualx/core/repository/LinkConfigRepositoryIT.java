package com.dualx.core.repository;

import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class LinkConfigRepositoryIT {

    @Autowired
    private LinkConfigRepository links;

    /** UWB TCP 监听链路（端口硬约束 12001）。 */
    private static LinkConfig uwbTcp() {
        return new LinkConfig(LinkKind.UWB_TCP_SERVER, null, null, false,
                "0.0.0.0", 12001, true, "UWB 基站", null, null);
    }

    /** 马甲串口链路。 */
    private static LinkConfig serialVest() {
        return new LinkConfig(LinkKind.SERIAL_VEST, "COM7", 115200, true,
                null, null, true, "马甲串口", null, null);
    }

    @Test
    void insertThenFindByKind_roundTripsEveryColumn() {
        assertThat(links.insert(uwbTcp())).isEqualTo(1);

        LinkConfig config = links.findByKind(LinkKind.UWB_TCP_SERVER).orElseThrow();
        assertThat(config.serialPortName()).isNull();
        assertThat(config.serialBaudRate()).isNull();
        assertThat(config.serialFlowControlRtsCts()).isFalse();
        assertThat(config.uwbBindHost()).isEqualTo("0.0.0.0");
        assertThat(config.uwbBindPort()).isEqualTo(12001);
        assertThat(config.enabled()).isTrue();
        assertThat(config.remark()).isEqualTo("UWB 基站");
        assertThat(config.createdAt()).isNotNull();
        assertThat(config.updatedAt()).isNotNull();
    }

    @Test
    void insertThenFindByKind_roundTripsSerialColumns() {
        assertThat(links.insert(serialVest())).isEqualTo(1);

        LinkConfig config = links.findByKind(LinkKind.SERIAL_VEST).orElseThrow();
        assertThat(config.serialPortName()).isEqualTo("COM7");
        assertThat(config.serialBaudRate()).isEqualTo(115200);
        assertThat(config.serialFlowControlRtsCts()).isTrue();
        assertThat(config.uwbBindHost()).isNull();
        assertThat(config.uwbBindPort()).isNull();
    }

    @Test
    void findByKind_returnsEmptyWhenAbsent() {
        assertThat(links.findByKind(LinkKind.UWB_SERIAL)).isEmpty();
    }

    @Test
    void duplicateKind_isRejectedByPrimaryKey() {
        links.insert(uwbTcp());

        assertThatThrownBy(() -> links.insert(uwbTcp()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void update_changesMutableColumnsOnly() {
        links.insert(serialVest());

        LinkConfig changed = new LinkConfig(LinkKind.SERIAL_VEST, "COM9", 9600, false,
                null, null, false, "换口", null, null);
        assertThat(links.update(changed)).isEqualTo(1);

        LinkConfig config = links.findByKind(LinkKind.SERIAL_VEST).orElseThrow();
        assertThat(config.serialPortName()).isEqualTo("COM9");
        assertThat(config.serialBaudRate()).isEqualTo(9600);
        assertThat(config.serialFlowControlRtsCts()).isFalse();
        assertThat(config.enabled()).isFalse();
        assertThat(config.remark()).isEqualTo("换口");
    }

    @Test
    void update_canSwitchKindFromSerialToUwbTcp() {
        links.insert(serialVest());

        // 同一行改成 UWB TCP 形态：串口列清空，监听列填上
        LinkConfig switched = new LinkConfig(LinkKind.SERIAL_VEST, null, null, false,
                "0.0.0.0", 12001, true, null, null, null);
        assertThat(links.update(switched)).isEqualTo(1);

        LinkConfig config = links.findByKind(LinkKind.SERIAL_VEST).orElseThrow();
        assertThat(config.serialPortName()).isNull();
        assertThat(config.serialBaudRate()).isNull();
        assertThat(config.uwbBindHost()).isEqualTo("0.0.0.0");
        assertThat(config.uwbBindPort()).isEqualTo(12001);
    }

    @Test
    void deleteByKind_removesRow() {
        links.insert(serialVest());

        assertThat(links.deleteByKind(LinkKind.SERIAL_VEST)).isEqualTo(1);
        assertThat(links.findByKind(LinkKind.SERIAL_VEST)).isEmpty();
        assertThat(links.deleteByKind(LinkKind.SERIAL_VEST)).isZero();
    }

    @Test
    void findAll_returnsEveryRowInKindOrder() {
        links.insert(serialVest());
        links.insert(uwbTcp());
        links.insert(new LinkConfig(LinkKind.UWB_SERIAL, "COM3", 460800, false,
                null, null, false, null, null, null));

        List<LinkConfig> all = links.findAll();

        assertThat(all).hasSize(3);
        assertThat(all).extracting(LinkConfig::linkKind)
                .containsExactly(LinkKind.SERIAL_VEST, LinkKind.UWB_SERIAL, LinkKind.UWB_TCP_SERVER);
        assertThat(links.count()).isEqualTo(3);
    }
}
