package com.dualx.core.map;

import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.solver.config.ScdsUwbProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAL-2a 单测：UWB 中心基准校准状态机（{@link UwbCalibrationService}）。
 *
 * <p>纯单测，不起线程、不依赖 Spring、不连库：{@link MapLedgerService} 用 Mockito 替身，
 * 这样「写回 {@code uwb_meta} 的内容」可以被逐字段断言，而不必真起一个 MySQL。</p>
 *
 * <p>几何沿用 {@code IndoorPositionResolverTest} 的同一张可解夹具（2000×1600 像素、现场
 * 30m×18m、四角等距），但标定校验的是**测距的静止性**而非解算坐标，故这里只用到「四角都有值」
 * 这一点：刻意用等距测距，避免把几何误差混进静止判定的断言里。</p>
 */
class UwbCalibrationServiceTest {

    private static final UUID MAP_ID = UUID.fromString("3f1a2b4c-5d6e-4f70-8192-a3b4c5d6e7f8");

    /** 四角齐全 + 两个实测长宽，与 {@code MapLedgerService.calibrated} 的齐备判据一致。 */
    private static final String META = """
            {"bs1":{"xPx":120,"yPx":1565},"bs2":{"xPx":1980,"yPx":1565},\
            "bs3":{"xPx":1980,"yPx":240},"bs4":{"xPx":120,"yPx":240},\
            "realLengthCm":3000,"realWidthCm":1800}""";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 静止窗口：四角等距 √306 m ≈ 17.493 m，逐帧一字不差。 */
    private static final int[] STILL_RANGES = {17493, 17493, 17493, 17493, 0, 0, 0, 0};

    /** 「人动了」：bs1 多出 200mm，远超 5.0cm → 50mm 的静止阈值。 */
    private static final int[] DRIFTING_RANGES = {17693, 17493, 17493, 17493, 0, 0, 0, 0};

    /** 采样窗口取小值即可覆盖状态机全部转移；默认 200 帧只让单测变慢。 */
    private static final int TARGET_FRAMES = 4;

    private MapLedgerService ledger;
    private ScdsUwbProperties uwb;
    private Clock clock;
    private UwbCalibrationService service;

    /** 链路快照的可变桩：默认「UWB TCP 服务端已开」，只有链路判据那条测试会改它。 */
    private List<LinkStatus> linkSnapshot;

    @BeforeEach
    void setUp() {
        ledger = mock(MapLedgerService.class);
        uwb = new ScdsUwbProperties();
        uwb.setCalibSampleFrames(TARGET_FRAMES);
        clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC);
        linkSnapshot = List.of(link(LinkKind.UWB_TCP_SERVER, LinkState.OPEN));
        service = new UwbCalibrationService(ledger, uwb, clock, () -> linkSnapshot);
        when(ledger.uwbMeta(MAP_ID)).thenReturn(META);
    }

    private static LinkStatus link(LinkKind kind, LinkState state) {
        return new LinkStatus(kind, state, null, null, null, null, 0L, 0L, 0L, null);
    }

    // ── start 的五条拒绝路径 ────────────────────────────────────────────────

    @Test
    void startRejectsWhenNoUwbLinkIsOpen() {
        linkSnapshot = List.of(
                link(LinkKind.SERIAL_VEST, LinkState.OPEN),
                link(LinkKind.UWB_TCP_SERVER, LinkState.CLOSED),
                link(LinkKind.UWB_SERIAL, LinkState.CLOSED));

        assertThatThrownBy(() -> service.start(MAP_ID, TARGET_FRAMES, 3000.0d, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.DEVICE_LINK_NOT_OPEN));
    }

    @Test
    void openingLinkDoesNotCountAsOpenYet() {
        // OPENING 期间接受标定会在链路就绪前丢掉开头若干帧，采样窗口前段就掺进「少帧」位移假象
        linkSnapshot = List.of(link(LinkKind.UWB_TCP_SERVER, LinkState.OPENING));

        assertThatThrownBy(() -> service.start(MAP_ID, TARGET_FRAMES, 3000.0d, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.DEVICE_LINK_NOT_OPEN));
    }

    @Test
    void uwbSerialLinkAlsoSatisfiesThePrecondition() {
        linkSnapshot = List.of(
                link(LinkKind.UWB_TCP_SERVER, LinkState.CLOSED),
                link(LinkKind.UWB_SERIAL, LinkState.OPEN));

        assertThat(service.start(MAP_ID, TARGET_FRAMES, 3000.0d, null).state())
                .isEqualTo(UwbCalibrationService.State.RUNNING);
    }

    @Test
    void startRejectsMapWithoutCalibratedGeometry() {
        when(ledger.uwbMeta(MAP_ID))
                .thenThrow(new BusinessException(ApiErrorCode.MAP_NOT_CALIBRATED, "底图尚未做 UWB 标定"));

        assertThatThrownBy(() -> service.start(MAP_ID, TARGET_FRAMES, 3000.0d, 1800.0d))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.MAP_NOT_CALIBRATED));
    }

    @Test
    void startRejectsWhenBothTapeMeasuresMissing() {
        assertThatThrownBy(() -> service.start(MAP_ID, TARGET_FRAMES, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.MAP_UWB_META_INCOMPLETE));
    }

    @Test
    void startRejectsNonPositiveSampleFrames() {
        assertThatThrownBy(() -> service.start(MAP_ID, 0, 3000.0d, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
    }

    @Test
    void startRejectsTapeMeasureThatIsNotWholeCentimetres() {
        assertThatThrownBy(() -> service.start(MAP_ID, TARGET_FRAMES, 3000.5d, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.start(MAP_ID, TARGET_FRAMES, null, -1800.0d))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
    }

    @Test
    void startFallsBackToConfiguredSampleFramesWhenRequestOmitsIt() {
        UwbCalibrationService.Status status = service.start(MAP_ID, null, 3000.0d, null);

        assertThat(status.state()).isEqualTo(UwbCalibrationService.State.RUNNING);
        assertThat(status.sampledFrames()).isZero();
        assertThat(status.appliedAt()).isNull();
        // 目标帧数取自 scds.uwb.calib-sample-frames：喂 TARGET_FRAMES 帧即应完成
        feed(1, STILL_RANGES, TARGET_FRAMES);
        assertThat(service.status(MAP_ID).state()).isEqualTo(UwbCalibrationService.State.SUCCEEDED);
    }

    @Test
    void secondStartWhileRunningIsRejectedAndKeepsOriginalSession() {
        service.start(MAP_ID, TARGET_FRAMES, 3000.0d, null);

        assertThatThrownBy(() -> service.start(MAP_ID, TARGET_FRAMES, 3050.0d, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.MAP_CALIBRATION_IN_PROGRESS));
        // 被拒的那次不得顶掉在跑的那次：采样窗口仍是原来的目标帧数
        assertThat(service.status(MAP_ID).state()).isEqualTo(UwbCalibrationService.State.RUNNING);
    }

    @Test
    void statusOfUntouchedMapIsIdle() {
        UwbCalibrationService.Status status = service.status(MAP_ID);

        assertThat(status.state()).isEqualTo(UwbCalibrationService.State.IDLE);
        assertThat(status.progressPct()).isNull();
        assertThat(status.sampledFrames()).isZero();
        assertThat(status.appliedAt()).isNull();
    }

    // ── 采样与终态 ────────────────────────────────────────────────────────

    @Test
    void framesFromOtherVestsAreIgnoredAfterTheFirstVestIsLatched() {
        service.start(MAP_ID, 10, 3000.0d, null);

        service.onFrame(7, STILL_RANGES);
        service.onFrame(8, STILL_RANGES);
        service.onFrame(8, STILL_RANGES);

        // 只认首个出现的马甲（7），其它马甲的帧既不计采样也不推进进度
        assertThat(service.status(MAP_ID).sampledFrames()).isEqualTo(1);
        assertThat(service.status(MAP_ID).state()).isEqualTo(UwbCalibrationService.State.RUNNING);
    }

    @Test
    void shortOrNullRangeArraysAreIgnored() {
        service.start(MAP_ID, 10, 3000.0d, null);

        service.onFrame(7, null);
        service.onFrame(7, new int[2]);

        assertThat(service.status(MAP_ID).sampledFrames()).isZero();
    }

    @Test
    void staticWindowSucceedsAndOverwritesTapeMeasures() throws Exception {
        service.start(MAP_ID, TARGET_FRAMES, 3050.0d, 1790.0d);

        feed(1, STILL_RANGES, TARGET_FRAMES);

        UwbCalibrationService.Status status = service.status(MAP_ID);
        assertThat(status.state()).isEqualTo(UwbCalibrationService.State.SUCCEEDED);
        assertThat(status.progressPct()).isEqualTo(100);
        assertThat(status.sampledFrames()).isEqualTo(TARGET_FRAMES);
        assertThat(status.appliedAt()).isEqualTo(clock.instant());
        assertThat(status.message()).contains("已写回");

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(ledger).saveUwbMeta(eq(MAP_ID), written.capture());
        JsonNode root = JSON.readTree(written.getValue());
        // 覆盖写回：两个实测长宽以卷尺值为准
        assertThat(root.get("realLengthCm").doubleValue()).isEqualTo(3050.0d);
        assertThat(root.get("realWidthCm").doubleValue()).isEqualTo(1790.0d);
        // 四角是骨架，不属于本次覆盖范围，必须原样保留
        assertThat(root.get("bs1").get("xPx").doubleValue()).isEqualTo(120.0d);
        assertThat(root.get("bs3").get("yPx").doubleValue()).isEqualTo(240.0d);
    }

    @Test
    void tapeMeasureLeftUnsetKeepsExistingValueInMeta() throws Exception {
        service.start(MAP_ID, TARGET_FRAMES, null, 1810.0d);

        feed(1, STILL_RANGES, TARGET_FRAMES);

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(ledger).saveUwbMeta(eq(MAP_ID), written.capture());
        JsonNode root = JSON.readTree(written.getValue());
        assertThat(root.get("realLengthCm").doubleValue()).isEqualTo(3000.0d);
        assertThat(root.get("realWidthCm").doubleValue()).isEqualTo(1810.0d);
    }

    @Test
    void driftingWindowFailsAndWritesNothingBack() {
        service.start(MAP_ID, TARGET_FRAMES, 3000.0d, null);

        service.onFrame(1, STILL_RANGES);
        service.onFrame(1, STILL_RANGES);
        service.onFrame(1, STILL_RANGES);
        service.onFrame(1, DRIFTING_RANGES);   // 越阈 → 折叠成 FAILED

        UwbCalibrationService.Status status = service.status(MAP_ID);
        assertThat(status.state()).isEqualTo(UwbCalibrationService.State.FAILED);
        assertThat(status.progressPct()).isNull();
        assertThat(status.message()).contains("移动");
        // 失败不得落库：库里的实测长宽仍是原值
        verify(ledger, never()).saveUwbMeta(eq(MAP_ID), anyString());
    }

    @Test
    void writeBackFailureIsFoldedIntoFailedStateInsteadOfThrown() {
        doThrow(new BusinessException(ApiErrorCode.MAP_NOT_FOUND, "底图不存在"))
                .when(ledger).saveUwbMeta(eq(MAP_ID), anyString());
        service.start(MAP_ID, TARGET_FRAMES, 3000.0d, null);

        // onFrame 挂在态势写手线程上，抛异常会连带丢帧，故必须折叠成 FAILED
        feed(1, STILL_RANGES, TARGET_FRAMES);

        UwbCalibrationService.Status status = service.status(MAP_ID);
        assertThat(status.state()).isEqualTo(UwbCalibrationService.State.FAILED);
        assertThat(status.message()).contains("写回");
    }

    // ── clear 兼作取消 ────────────────────────────────────────────────────

    @Test
    void clearResetsCompletedResultAndCancelsRunningCalibration() {
        service.start(MAP_ID, TARGET_FRAMES, 3000.0d, null);
        assertThat(service.status(MAP_ID).state()).isEqualTo(UwbCalibrationService.State.RUNNING);

        service.clear(MAP_ID);

        assertThat(service.status(MAP_ID).state()).isEqualTo(UwbCalibrationService.State.IDLE);
        // 取消后不再撞 MAP_CALIBRATION_IN_PROGRESS
        assertThat(service.start(MAP_ID, TARGET_FRAMES, 3010.0d, null).state())
                .isEqualTo(UwbCalibrationService.State.RUNNING);
    }

    @Test
    void onFrameWithoutAnySessionIsANoOp() {
        service.onFrame(1, STILL_RANGES);

        assertThat(service.status(MAP_ID).state()).isEqualTo(UwbCalibrationService.State.IDLE);
    }

    private void feed(int vestNum, int[] ranges, int times) {
        for (int i = 0; i < times; i++) {
            service.onFrame(vestNum, ranges);
        }
    }
}
