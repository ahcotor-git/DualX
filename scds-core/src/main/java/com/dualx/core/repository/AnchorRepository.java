package com.dualx.core.repository;

import com.dualx.core.domain.Anchor;
import com.dualx.core.support.SqlTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * UWB 基站台账仓储（{@code t_anchor}）。主键是 1~8 的基线号，与 41B 帧
 * {@code ranges[i]} 一一对应，因此没有 UUID 形态的查询入口。
 */
@Repository
public class AnchorRepository {

    private static final String COLUMNS = "anchor_id, name, x_px, y_px, enabled, created_at, updated_at";

    private final JdbcClient jdbc;

    public AnchorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Anchor> findByAnchorId(int anchorId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_anchor WHERE anchor_id = :anchorId")
                .param("anchorId", anchorId)
                .query(AnchorRepository::map)
                .optional();
    }

    public List<Anchor> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM t_anchor ORDER BY anchor_id")
                .query(AnchorRepository::map)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM t_anchor").query(Long.class).single();
    }

    public boolean existsByAnchorId(int anchorId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM t_anchor WHERE anchor_id = :anchorId)")
                .param("anchorId", anchorId)
                .query(Boolean.class)
                .single();
    }

    /** @return 影响行数，正常为 1 */
    public int insert(Anchor anchor) {
        return jdbc.sql("""
                        INSERT INTO t_anchor (anchor_id, name, x_px, y_px, enabled)
                        VALUES (:anchorId, :name, :xPx, :yPx, :enabled)
                        """)
                .param("anchorId", anchor.anchorId())
                .param("name", anchor.name())
                .param("xPx", anchor.xPx())
                .param("yPx", anchor.yPx())
                .param("enabled", anchor.enabled() ? 1 : 0)
                .update();
    }

    /** 更新可变字段；{@code anchor_id} / {@code created_at} 不可改。 */
    public int update(Anchor anchor) {
        return jdbc.sql("""
                        UPDATE t_anchor
                           SET name = :name,
                               x_px = :xPx,
                               y_px = :yPx,
                               enabled = :enabled
                         WHERE anchor_id = :anchorId
                        """)
                .param("anchorId", anchor.anchorId())
                .param("name", anchor.name())
                .param("xPx", anchor.xPx())
                .param("yPx", anchor.yPx())
                .param("enabled", anchor.enabled() ? 1 : 0)
                .update();
    }

    public int deleteByAnchorId(int anchorId) {
        return jdbc.sql("DELETE FROM t_anchor WHERE anchor_id = :anchorId")
                .param("anchorId", anchorId)
                .update();
    }

    private static Anchor map(ResultSet rs, int rowNum) throws SQLException {
        return new Anchor(
                rs.getInt("anchor_id"),
                rs.getString("name"),
                rs.getObject("x_px", Double.class),
                rs.getObject("y_px", Double.class),
                rs.getBoolean("enabled"),
                SqlTime.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                SqlTime.fromDb(rs.getObject("updated_at", LocalDateTime.class)));
    }
}
