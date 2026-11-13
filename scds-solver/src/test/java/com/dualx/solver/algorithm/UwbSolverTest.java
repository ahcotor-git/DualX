package com.dualx.solver.algorithm;

import com.dualx.solver.config.ScdsUwbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UWB 后端解算（Huber-LM）行为。这些用例锁死的是「入口契约 + 失败分支的 status 词表」，
 * 而不是数值细节：算法逐字照抄现场实现，数值只断言量级与位置误差。
 */
class UwbSolverTest {

    /**
     * 10m × 8m 场地，像素尺度 1px = 1cm：bs1..bs4 顺时针围成矩形（bs1 原点、bs2 沿长边、bs4 沿宽边）。
     * {@link UwbSolver.Geometry} 用 bs1-bs2 定长边、bs1-bs4 定宽边推出公制尺度。
     */
    private static final String META = """
            {"realLengthCm":1000,"realWidthCm":800,
             "bs1":{"xPx":0,"yPx":0},
             "bs2":{"xPx":1000,"yPx":0},
             "bs3":{"xPx":1000,"yPx":800},
             "bs4":{"xPx":0,"yPx":800}}
            """;

    /** 靶点 (3m, 2m) 到 bs1..bs4 的精确测距（毫米）。 */
    private static final int[] RANGES_AT_3_2 = {3606, 7280, 9220, 6708};

    private ScdsUwbProperties props;

    @BeforeEach
    void setUp() {
        props = new ScdsUwbProperties();
        props.setMode(true);
    }

    @Test
    void fourAnchorsSolveToTheKnownPoint() {
        UwbSolver solver = new UwbSolver(props);

        UwbSolver.Solve2DResult r = solver.solve2D(META, RANGES_AT_3_2, null, null);

        assertTrue(r.valid, "四条精确测距应解出可用解，status=" + r.status);
        assertEquals(4, r.usedCount);
        assertEquals(10.0, r.realLengthM, 1e-6);
        assertEquals(8.0, r.realWidthM, 1e-6);
        // xPct = 3/10 = 0.3，yPct = 2/8 = 0.25（解算坐标是归一化到场地尺寸的百分比）。
        assertEquals(0.3, r.xPct, 0.02);
        assertEquals(0.25, r.yPct, 0.02);
        assertEquals("OK_HUBER_ALL", r.status);
        assertTrue(Double.isFinite(r.gdop) && r.gdop > 0, "四锚点几何 DOP 应有限");
        assertNotNull(r.residualM);
        assertTrue(r.residualM < 0.05, "精确测距的 RMS 应接近 0");
    }

    @Test
    void sameFrameIdReturnsTheCachedResult() {
        UwbSolver solver = new UwbSolver(props);

        UwbSolver.Solve2DResult first = solver.solve2D(0, META, RANGES_AT_3_2, null, null, 1000L, 1L);
        UwbSolver.Solve2DResult again = solver.solve2D(0, META, RANGES_AT_3_2, null, null, 2000L, 1L);

        assertSame(first, again, "同一 frameId 的重复帧直接沿用缓存结果，不重复解算");
    }

    @Test
    void olderTimestampIsRejectedAsOutOfOrder() {
        UwbSolver solver = new UwbSolver(props);
        solver.solve2D(0, META, RANGES_AT_3_2, null, null, 1000L, 1L);

        UwbSolver.Solve2DResult stale = solver.solve2D(0, META, RANGES_AT_3_2, null, null, 500L, 2L);

        assertFalse(stale.valid);
        assertEquals("HELD_OUT_OF_ORDER", stale.status);
    }

    @Test
    void malformedMetaIsHeldInsteadOfThrowing() {
        UwbSolver solver = new UwbSolver(props);

        UwbSolver.Solve2DResult r = solver.solve2D("{\"bs1\":{\"xPx\":0,\"yPx\":0}}", RANGES_AT_3_2, null, null);

        assertFalse(r.valid);
        assertEquals("HELD_META_INVALID", r.status);
    }

    @Test
    void oneValidAnchorIsInsufficientGeometry() {
        UwbSolver solver = new UwbSolver(props);

        UwbSolver.Solve2DResult r = solver.solve2D(META, new int[]{3606, 0, 0, 0, 0, 0, 0, 0}, null, null);

        assertFalse(r.valid);
        assertEquals(1, r.usedCount);
        assertEquals("HELD_INSUFFICIENT_GEOMETRY", r.status);
    }

    @Test
    void twoAnchorsNeedAPositionPrior() {
        UwbSolver solver = new UwbSolver(props);
        int[] two = {3606, 7280, 0, 0, 0, 0, 0, 0};

        UwbSolver.Solve2DResult withoutPrior = solver.solve2D(META, two, null, null);
        assertFalse(withoutPrior.valid);
        assertEquals("HELD_INSUFFICIENT_GEOMETRY", withoutPrior.status);

        UwbSolver.Solve2DResult withPrior = solver.solve2D(META, two, 0.3, 0.25);
        assertEquals(2, withPrior.usedCount, "给了位置先验后两条测距即可进入解算");
        assertNotNull(withPrior.status);
    }

    @Test
    void rejectedFrameFillsOnlyTheProvidedRanges() {
        UwbSolver solver = new UwbSolver(props);

        UwbSolver.Solve2DResult r = solver.rejectedFrame(new int[]{11, 22, 33}, "BROKEN");

        assertFalse(r.valid);
        assertEquals("BROKEN", r.status);
        assertEquals(8, r.rawMm.length);
        assertEquals(11, r.rawMm[0]);
        assertEquals(0, r.rawMm[3]);
        assertEquals(0, r.usedCount);
        assertNull(r.residualM);
    }

    @Test
    void toImagePctMapsPhysicalPercentToWholeImage() {
        double[] pct = UwbSolver.toImagePct(META, 0.3, 0.25, 1000, 800);

        assertEquals(0.3, pct[0], 1e-9);
        assertEquals(0.25, pct[1], 1e-9);
    }

    @Test
    void toImagePctRejectsMissingImageDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> UwbSolver.toImagePct(META, 0.3, 0.25, 0, 800));
    }

    @Test
    void geometryDopIsFiniteForASpreadOfAnchors() {
        List<UwbSolver.BsPoint> bs = List.of(
                new UwbSolver.BsPoint(1, 0, 0, 3.6, 0.05),
                new UwbSolver.BsPoint(2, 10, 0, 7.3, 0.05),
                new UwbSolver.BsPoint(3, 10, 8, 9.2, 0.05));

        double dop = UwbSolver.geometryDop(new double[]{3, 2}, bs);

        assertTrue(Double.isFinite(dop) && dop > 0);
    }
}
