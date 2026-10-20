package com.dualx.core.repository;

import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.support.SqlTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 链路配置仓储（{@code t_link_config}）。{@code link_kind} 单列主键，全库最多三行。
 *
 * <p>有意不提供任何写「运行态」的方法：{@code LinkStatus} 是进程内状态，不落库。
 */
@Repository
public class LinkConfigRepository {

    private static final String COLUMNS =
            "link_kind, serial_port_name, serial_baud_rate, serial_flow_control_rts_cts, "
                    + "uwb_bind_host, uwb_bind_port, enabled, remark, created_at, updated_at";

    private final JdbcClient jdbc;

    public LinkConfigRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LinkConfig> findByKind(LinkKind kind) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_link_config WHERE link_kind = :kind")
                .param("kind", kind.name())
                .query(LinkConfigRepository::map)
                .optional();
    }

    public List<LinkConfig> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_link_config ORDER BY link_kind")
                .query(LinkConfigRepository::map)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM t_link_config").query(Long.class).single();
    }

    /** @return 影响行数，正常为 1；同一 link_kind 重复插入由主键拒绝 */
    public int insert(LinkConfig config) {
        return jdbc.sql("""
                        INSERT INTO t_link_config (link_kind, serial_port_name, serial_baud_rate,
                                                   serial_flow_control_rts_cts, uwb_bind_host,
                                                   uwb_bind_port, enabled, remark)
                        VALUES (:kind, :serialPortName, :serialBaudRate, :flowControl,
                                :uwbBindHost, :uwbBindPort, :enabled, :remark)
                        """)
                .param("kind", config.linkKind().name())
                .param("serialPortName", config.serialPortName())
                .param("serialBaudRate", config.serialBaudRate())
                .param("flowControl", config.serialFlowControlRtsCts() ? 1 : 0)
                .param("uwbBindHost", config.uwbBindHost())
                .param("uwbBindPort", config.uwbBindPort())
                .param("enabled", config.enabled() ? 1 : 0)
                .param("remark", config.remark())
                .update();
    }

    /** 更新可变字段；{@code link_kind} / {@code created_at} 不可改。 */
    public int update(LinkConfig config) {
        return jdbc.sql("""
                        UPDATE t_link_config
                           SET serial_port_name = :serialPortName,
                               serial_baud_rate = :serialBaudRate,
                               serial_flow_control_rts_cts = :flowControl,
                               uwb_bind_host = :uwbBindHost,
                               uwb_bind_port = :uwbBindPort,
                               enabled = :enabled,
                               remark = :remark
                         WHERE link_kind = :kind
                        """)
                .param("kind", config.linkKind().name())
                .param("serialPortName", config.serialPortName())
                .param("serialBaudRate", config.serialBaudRate())
                .param("flowControl", config.serialFlowControlRtsCts() ? 1 : 0)
                .param("uwbBindHost", config.uwbBindHost())
                .param("uwbBindPort", config.uwbBindPort())
                .param("enabled", config.enabled() ? 1 : 0)
                .param("remark", config.remark())
                .update();
    }

    public int deleteByKind(LinkKind kind) {
        return jdbc.sql("DELETE FROM t_link_config WHERE link_kind = :kind")
                .param("kind", kind.name())
                .update();
    }

    private static LinkConfig map(ResultSet rs, int rowNum) throws SQLException {
        return new LinkConfig(
                LinkKind.valueOf(rs.getString("link_kind")),
                rs.getString("serial_port_name"),
                rs.getObject("serial_baud_rate", Integer.class),
                rs.getBoolean("serial_flow_control_rts_cts"),
                rs.getString("uwb_bind_host"),
                rs.getObject("uwb_bind_port", Integer.class),
                rs.getBoolean("enabled"),
                rs.getString("remark"),
                SqlTime.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("updated_at", LocalDateTime.class)));
    }
}
