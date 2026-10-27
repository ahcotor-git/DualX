package com.dualx.core.state;

import com.dualx.solver.config.ScdsUwbProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * CAL-1 单测：UWB 室内解算（{@link IndoorPositionResolver}）。纯单测，不起线程、不依赖 Spring。
 *
 * <p>用一张 2000×1600 像素、现场实测 30m×18m 的底图，测距值按该几何**反算**得到，
 * 因此「解出来的位置」有唯一正确答案，可断言到具体百分比，而不是只看「不炸」。</p>
 */
class IndoorPositionResolverTest {

    private static final String MAP_KEY = "map-under-test";

    /**
     * BS1~BS4 四角像素坐标 + 现场实测 30m×18m。
     *
     * <p><b>编号约定照 {@code UwbSolver.Geometry} 的实际口径</b>：它以 {@code bs1→bs2} 为长度基准、
     * {@code bs1→bs4} 为宽度基准，且这两条基准必须近似正交。因此四角按「左下 → 右下 → 右上 → 左上」
     * 编号（与旧 {@code UwbV15RegressionTest} 那份可解样例同一约定）。若照
     * {@code PROTOCOL.md} §6.6 的示例编号（左上/右上/左下/右下），{@code bs1→bs4} 会变成对角线，
     * 与 {@code bs1→bs2} 近似同向，反解直接判 {@code HELD_META_INVALID}。</p>
     */
    private static final String META = """
            {"bs1":{"xPx":120,"yPx":1565},"bs2":{"xPx":1980,"yPx":1565},\
            "bs3":{"xPx":1980,"yPx":240},"bs4":{"xPx":120,"yPx":240},\
            "realLengthCm":3000,"realWidthCm":1800}""";

    private static final int IMAGE_WIDTH = 2000;
    private static final int IMAGE_HEIGHT = 1600;

    /** 物理矩形（BS1~BS4 外接框）正中心：四角等距 √306 m ≈ 17.493 m。 */
    private static final int[] RANGES_AT_CENTRE = {17493, 17493, 17493, 17493, 0, 0, 0, 0};

    /** 自中心向 +X/+Y 各 1m（对角位移 √2 m，仍在过滤器的 2m 跳变门限内）。 */
    private static final int[] RANGES_MOVED_BY_ONE_METRE = {17889, 16125, 17205, 18868, 0, 0, 0, 0};

    /** 整图 2000×1600 上，物理中心对应 (1050/2000, 902.5/1600)。 */
    private static final double CENTRE_IMAGE_X = 0.525;
    private static final double CENTRE_IMAGE_Y = 0.5640625;

    private static IndoorPositionResolver resolver() {
        ScdsUwbProperties props = new ScdsUwbProperties();
        props.setMode(true);
        IndoorPositionResolver resolver = new IndoorPositionResolver(props);
        resolver.load(MAP_KEY, META, IMAGE_WIDTH, IMAGE_HEIGHT);
        return resolver;
    }

    @Test
    void freshResolverHasNothingLoaded() {
        ScdsUwbProperties props = new ScdsUwbProperties();
        props.setMode(true);
        IndoorPositionResolver resolver = new IndoorPositionResolver(props);

        assertThat(resolver.loaded()).isFalse();
        assertThat(resolver.uwbMode()).isTrue();
        assertThat(resolver.resolve(1001, RANGES_AT_CENTRE, 1_000L)).isEmpty();
    }

    @Test
    void resolveYieldsNothingWhenUwbModeIsOff() {
        ScdsUwbProperties props = new ScdsUwbProperties();
        props.setMode(false);
        IndoorPositionResolver resolver = new IndoorPositionResolver(props);
        resolver.load(MAP_KEY, META, IMAGE_WIDTH, IMAGE_HEIGHT);

        assertThat(resolver.uwbMode()).isFalse();
        assertThat(resolver.loaded()).isTrue();
        assertThat(resolver.resolve(1001, RANGES_AT_CENTRE, 1_000L)).isEmpty();
    }

    @Test
    void loadRejectsMetaWithoutAllFourCorners() {
        IndoorPositionResolver resolver = new IndoorPositionResolver(new ScdsUwbProperties());
        String missingBs2 = """
                {"bs1":{"xPx":120,"yPx":1565},"bs3":{"xPx":1980,"yPx":240},\
                "bs4":{"xPx":120,"yPx":240},"realLengthCm":3000,"realWidthCm":1800}""";

        assertThat(resolver.load(MAP_KEY, missingBs2, IMAGE_WIDTH, IMAGE_HEIGHT)).isFalse();
        assertThat(resolver.loaded()).isFalse();
        assertThat(resolver.resolve(1001, RANGES_AT_CENTRE, 1_000L)).isEmpty();
        // 锚点缺失必须走「装载即判否」，而不是留给逐帧解算反复失败
        assertThat(resolver.load(MAP_KEY, "not-json", IMAGE_WIDTH, IMAGE_HEIGHT)).isFalse();
        assertThat(resolver.loaded()).isFalse();
    }

    @Test
    void loadRejectsNonPositiveFieldSize() {
        IndoorPositionResolver resolver = new IndoorPositionResolver(new ScdsUwbProperties());
        String zeroWidth = """
                {"bs1":{"xPx":120,"yPx":1565},"bs2":{"xPx":1980,"yPx":1565},\
                "bs3":{"xPx":1980,"yPx":240},"bs4":{"xPx":120,"yPx":240},\
                "realLengthCm":3000,"realWidthCm":0}""";

        assertThat(resolver.load(MAP_KEY, zeroWidth, IMAGE_WIDTH, IMAGE_HEIGHT)).isFalse();
        assertThat(resolver.loaded()).isFalse();
        assertThat(resolver.resolve(1001, RANGES_AT_CENTRE, 1_000L)).isEmpty();
    }

    @Test
    void loadRejectsMissingImageSize() {
        IndoorPositionResolver resolver = new IndoorPositionResolver(new ScdsUwbProperties());

        assertThat(resolver.load(MAP_KEY, META, null, IMAGE_HEIGHT)).isFalse();
        assertThat(resolver.loaded()).isFalse();
        assertThat(resolver.resolve(1001, RANGES_AT_CENTRE, 1_000L)).isEmpty();
    }

    @Test
    void resolveMapsFieldCentreIntoWholeImagePercentages() {
        IndoorPositionResolver resolver = resolver();

        IndoorPositionResolver.Fix fix = resolver.resolve(1001, RANGES_AT_CENTRE, 1_000L).orElseThrow();

        // 解算态是 BS 外接框内的 0.5，整图坐标要按「外接框在底图里的位置」再映射一次：
        // x=(120+0.5×1860)/2000、y=(240+0.5×1325)/1600
        assertThat(fix.xPct()).isCloseTo(CENTRE_IMAGE_X, within(0.002));
        assertThat(fix.yPct()).isCloseTo(CENTRE_IMAGE_Y, within(0.002));
        assertThat(fix.usedCount()).isEqualTo(4);
    }

    @Test
    void resolveWithoutImageSizeNeverProducesCoordinates() {
        // 图片尺寸缺失时必须退回「只计数」：底图上没地方放这个点，给 0 会把马甲画到左上角
        ScdsUwbProperties props = new ScdsUwbProperties();
        props.setMode(true);
        IndoorPositionResolver resolver = new IndoorPositionResolver(props);
        resolver.load(MAP_KEY, META, null, null);

        assertThat(resolver.loaded()).isFalse();
        assertThat(resolver.resolve(1001, RANGES_AT_CENTRE, 1_000L)).isEmpty();
    }

    @Test
    void resolveTracksSmallMovementAcrossFrames() {
        IndoorPositionResolver resolver = resolver();

        IndoorPositionResolver.Fix centre = resolver.resolve(1001, RANGES_AT_CENTRE, 1_000L).orElseThrow();
        IndoorPositionResolver.Fix moved = resolver.resolve(1001, RANGES_MOVED_BY_ONE_METRE, 1_050L).orElseThrow();

        assertThat(moved.xPct()).isGreaterThan(centre.xPct());
        assertThat(moved.yPct()).isGreaterThan(centre.yPct());
        assertThat(moved.xPct()).isBetween(0.0, 1.0);
        assertThat(moved.yPct()).isBetween(0.0, 1.0);
    }

    @Test
    void resolveKeepsLastFixWhenFrameArrivesOutOfOrder() {
        IndoorPositionResolver resolver = resolver();

        IndoorPositionResolver.Fix first = resolver.resolve(1001, RANGES_AT_CENTRE, 1_000L).orElseThrow();
        IndoorPositionResolver.Fix late = resolver.resolve(1001, RANGES_MOVED_BY_ONE_METRE, 900L).orElseThrow();

        // 时序倒灌的帧既不推进状态也不倒灌坐标：拿到的是上一帧的保持位
        assertThat(late.xPct()).isEqualTo(first.xPct());
        assertThat(late.yPct()).isEqualTo(first.yPct());
    }

    @Test
    void resolveKeepsEveryUnitOnItsOwnState() {
        IndoorPositionResolver resolver = resolver();

        IndoorPositionResolver.Fix first = resolver.resolve(1001, RANGES_AT_CENTRE, 1_000L).orElseThrow();
        // 另一马甲此时才上第一帧：不能因为共用解算器而带着 1001 的先验（1001 的结果也不受影响）
        IndoorPositionResolver.Fix other = resolver.resolve(2002, RANGES_MOVED_BY_ONE_METRE, 1_000L).orElseThrow();

        assertThat(other.xPct()).isNotEqualTo(first.xPct());
        assertThat(resolver.resolve(1001, RANGES_AT_CENTRE, 1_050L).orElseThrow().xPct())
                .isCloseTo(first.xPct(), within(0.002));
    }

    @Test
    void clearForgetsLoadedMapAndUnits() {
        IndoorPositionResolver resolver = resolver();
        assertThat(resolver.resolve(1001, RANGES_AT_CENTRE, 1_000L)).isPresent();

        resolver.clear();

        assertThat(resolver.loaded()).isFalse();
        assertThat(resolver.resolve(1001, RANGES_AT_CENTRE, 1_050L)).isEmpty();
    }

    @Test
    void loadRebuildsStateForTheNewMap() {
        IndoorPositionResolver resolver = resolver();
        resolver.resolve(1001, RANGES_AT_CENTRE, 1_000L);

        assertThat(resolver.load("another-map", META, IMAGE_WIDTH, IMAGE_HEIGHT)).isTrue();

        // 换图后是全新的状态：同一马甲的第一帧照常给出中心坐标，而不是沿用旧图先验
        IndoorPositionResolver.Fix fix = resolver.resolve(1001, RANGES_AT_CENTRE, 1_010L).orElseThrow();
        assertThat(fix.xPct()).isCloseTo(CENTRE_IMAGE_X, within(0.002));
        assertThat(fix.yPct()).isCloseTo(CENTRE_IMAGE_Y, within(0.002));
    }
}
