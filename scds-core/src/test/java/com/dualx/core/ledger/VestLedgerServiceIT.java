package com.dualx.core.ledger;

import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Vest;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.repository.VestDeviceSessionRepository;
import com.dualx.core.repository.VestRepository;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import com.dualx.core.support.UuidV7;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 马甲台账服务的真实数据库行为（契约 paths/device.yaml，评审 R22）。
 *
 * <p>带测试外层事务：这里要验的多是「写两处」的链路（台账行 + 设备会话），而它本身就是
 * 一次提交，事务边界与生产一致即可；测试数据整体回滚，不碰本地已有台账。</p>
 *
 * <p>注销用例需要「当前无演习进行中」，本类在事务内清场（见 {@link #clearRunningExercises()}）——
 * 回滚后真实的进行中场次原样还在。</p>
 */
@SpringBootTest
@Transactional
class VestLedgerServiceIT {

    /** BCrypt 只需一次真实编码用于断言摘要可用，其余播种用固定串，避免每条用例上百毫秒。 */
    private static final String SEED_HASH = "$2a$10$abcdefghijklmnopqrstuv";

    private static final String SECRET = "ledger-integration-secret";

    /** 与 DeviceAuthServiceIT 的 62001 段错开。 */
    private static final int FIRST_VEST_NUM = 62002;

    /** 列表用例的专属标记：列表要过滤掉库里既有的台账行，只有本用例自己播种的行才可断言。 */
    private static final String TAG = "LGRIT";

    @Autowired private VestLedgerService ledger;
    @Autowired private VestRepository vests;
    @Autowired private VestDeviceSessionRepository sessions;
    @Autowired private ExerciseRepository exercises;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JdbcClient jdbc;

    private int nextVestNum = FIRST_VEST_NUM;

    // ── 登记 / 详情 / 修改 ────────────────────────────────────────────────────

    @Test
    void create_landsAsUnboundEnabledRowWithoutAnySecret() {
        int vestNum = nextVestNum++;

        Vest created = ledger.create(new VestLedgerService.VestDraft(vestNum, "张三", Team.RED, "V2", "一营一连"));

        assertThat(created.vestNum()).isEqualTo(vestNum);
        assertThat(created.name()).isEqualTo("张三");
        assertThat(created.team()).isEqualTo(Team.RED);
        assertThat(created.model()).isEqualTo("V2");
        assertThat(created.remark()).isEqualTo("一营一连");
        // 登记只负责「台账里有这台设备」：认领与密钥是绑定闭环的事
        assertThat(created.bound()).isFalse();
        assertThat(created.enabled()).isTrue();
        assertThat(created.bindSecretHash()).isNull();
        assertThat(created.createdAt()).isNotNull();
    }

    @Test
    void create_withExistingNum_isNumConflict() {
        int vestNum = nextVestNum++;
        ledger.create(new VestLedgerService.VestDraft(vestNum, "李四", Team.RED, null, null));

        assertCode(() -> ledger.create(new VestLedgerService.VestDraft(vestNum, "另一台", Team.BLUE, null, null)),
                ApiErrorCode.DEVICE_VEST_NUM_CONFLICT);
    }

    @Test
    void get_absent_isVestNotFound() {
        assertCode(() -> ledger.get(nextVestNum++), ApiErrorCode.DEVICE_VEST_NOT_FOUND);
    }

    @Test
    void update_onlyTouchesManualColumns_keepingReportedAndCredentialOnes() {
        int vestNum = nextVestNum++;
        seed(vestNum, "旧名", Team.RED, false, SEED_HASH, null,
                "SIM-1234", "V2", "1.0.3");

        Vest updated = ledger.update(vestNum,
                new VestLedgerService.VestDraft(vestNum, "新名", Team.BLUE, "V3", "调拨"));

        assertThat(updated.name()).isEqualTo("新名");
        assertThat(updated.team()).isEqualTo(Team.BLUE);
        assertThat(updated.model()).isEqualTo("V3");
        assertThat(updated.remark()).isEqualTo("调拨");
        // 设备上报的列不在请求体里，不能被一次改名抹掉
        assertThat(updated.simNo()).isEqualTo("SIM-1234");
        assertThat(updated.firmwareVersion()).isEqualTo("1.0.3");
        // 凭据与生命周期同理
        assertThat(updated.bindSecretHash()).isEqualTo(SEED_HASH);
        assertThat(updated.bound()).isFalse();
        assertThat(updated.enabled()).isTrue();
    }

    @Test
    void update_withMismatchedBodyNum_isValidationFailed() {
        int vestNum = nextVestNum++;
        seed(vestNum, TAG + "-mismatch", Team.RED, false, null, null, null, null, null);

        assertCode(() -> ledger.update(vestNum,
                        new VestLedgerService.VestDraft(vestNum + 1, "x", null, null, null)),
                ApiErrorCode.VALIDATION_FAILED);
    }

    // ── 列表过滤 ──────────────────────────────────────────────────────────────

    @Test
    void list_filtersByKeywordTeamBoundAndHeartbeatStatus() {
        String live = TAG + "-live";
        String offline = TAG + "-offline";
        String neverSeen = TAG + "-never";
        Instant now = Instant.now();
        seed(nextVestNum++, live, Team.RED, true, SEED_HASH, now, null, null, null);
        seed(nextVestNum++, offline, Team.BLUE, false, null, now.minusSeconds(60), null, null, null);
        seed(nextVestNum++, neverSeen, Team.RED, false, null, null, null, null, null);

        assertThat(names(filter(TAG, null, null, null)))
                .containsExactlyInAnyOrder(live, offline, neverSeen);

        assertThat(names(filter(TAG, Team.RED, null, null)))
                .containsExactlyInAnyOrder(live, neverSeen);

        assertThat(names(filter(TAG, null, true, null)))
                .containsExactly(live);

        // 心跳窗口（距今 <= 5000ms）就是本表唯一能表达的「在线」依据
        assertThat(names(filter(TAG, null, null, VestStatus.LIVE))).containsExactly(live);
        assertThat(names(filter(TAG, null, null, VestStatus.OFFLINE)))
                .containsExactlyInAnyOrder(offline, neverSeen);
        // 阵亡属演习运行态（t_exercise_unit.final_status），台账层没有数据源，显式空集
        assertThat(filter(TAG, null, null, VestStatus.DIE).items()).isEmpty();
    }

    @Test
    void list_keywordIsLiteral_notALikeWildcard() {
        String plain = TAG + "-plain";
        seed(nextVestNum++, plain, Team.RED, false, null, null, null, null, null);

        // 未转义时 "LGRIT%" 会命中所有以 LGRIT 开头的行；转义后只按字面量匹配 → 一条都不该有
        assertThat(filter(TAG + "%", null, null, null).items()).isEmpty();
        assertThat(names(filter(plain, null, null, null))).containsExactly(plain);
    }

    @Test
    void list_blankKeywordMeansNoFilter() {
        // 空串是「没填」而不是「搜空字符串」：不该把条件塞进 SQL
        assertThat(filter("   ", null, null, null).total())
                .isEqualTo(ledger.list(null, null, null, null, PageRequest.of(1, 1)).total());
    }

    // ── 逻辑注销与引用保护（R22）──────────────────────────────────────────────

    @Test
    @DisplayName("注销落 enabled=0 并清会话，保留摘要——物理删除不对外暴露")
    void retire_disablesAndClearsSession_keepingTheSecret() {
        clearRunningExercises();
        int vestNum = nextVestNum++;
        seed(vestNum, TAG + "-retire", Team.RED, true, SEED_HASH, Instant.now(), null, null, null);
        sessions.openSession(vestNum, UuidV7.generate().toString(), Instant.now());
        assertThat(sessions.findSessionSid(vestNum)).isPresent();

        ledger.retire(vestNum);

        Vest retired = vests.findByVestNum(vestNum).orElseThrow();
        assertThat(retired.enabled()).isFalse();
        assertThat(retired.bindSecretHash()).isEqualTo(SEED_HASH);
        assertThat(sessions.findSessionSid(vestNum)).isEmpty();
        // 行还在：t_exercise_unit / t_hit_event / t_track_sample 都按 vest_num 引用历史
        assertThat(vests.existsByVestNum(vestNum)).isTrue();
    }

    @Test
    @DisplayName("演习进行中禁止注销：此刻该设备正被编成快照引用")
    void retire_whileExerciseRunning_isRejected() {
        clearRunningExercises();
        startRunningExercise();
        int vestNum = nextVestNum++;
        seed(vestNum, TAG + "-busy", Team.RED, false, null, null, null, null, null);

        assertCode(() -> ledger.retire(vestNum), ApiErrorCode.EXERCISE_ALREADY_RUNNING);
        assertThat(vests.findByVestNum(vestNum).orElseThrow().enabled()).isTrue();
    }

    @Test
    void retire_absent_isVestNotFound() {
        assertCode(() -> ledger.retire(nextVestNum++), ApiErrorCode.DEVICE_VEST_NOT_FOUND);
    }

    // ── 绑定闭环（决策 04 D03-4）──────────────────────────────────────────────

    @Test
    @DisplayName("首次绑定：写摘要 + bound=1，并顶掉此前签发的设备会话")
    void bind_writesHashedSecretAndInvalidatesTheOldSession() {
        int vestNum = nextVestNum++;
        seed(vestNum, TAG + "-bind", Team.RED, false, null, Instant.now(), null, null, null);
        // 不能借 openSession 造这个前置：它在同一条 UPDATE 里把 bound 置 1，而 bind 的前提恰是未绑定。
        // 这里只写会话列，保住「bind 也要顶掉残留会话」这条断言。
        openSessionKeepingUnbound(vestNum);
        assertThat(sessions.findSessionSid(vestNum)).isPresent();

        Vest bound = ledger.bind(vestNum, SECRET);

        assertThat(bound.bound()).isTrue();
        assertThat(bound.bindSecretHash()).isNotEqualTo(SECRET);
        assertThat(passwords.matches(SECRET, bound.bindSecretHash())).isTrue();
        assertThat(sessions.findSessionSid(vestNum)).isEmpty();
    }

    @Test
    void bind_onAlreadyBound_isAlreadyBound() {
        int vestNum = nextVestNum++;
        seed(vestNum, TAG + "-bound", Team.RED, true, SEED_HASH, null, null, null, null);

        assertCode(() -> ledger.bind(vestNum, SECRET), ApiErrorCode.DEVICE_VEST_ALREADY_BOUND);
    }

    @Test
    void bind_absent_isVestNotFound() {
        assertCode(() -> ledger.bind(nextVestNum++, SECRET), ApiErrorCode.DEVICE_VEST_NOT_FOUND);
    }

    @Test
    @DisplayName("换绑：替换摘要、bound 保持 1，未绑定的马甲报 409")
    void rebind_replacesSecretAndKeepsBound() {
        int vestNum = nextVestNum++;
        seed(vestNum, TAG + "-rebind", Team.RED, false, null, null, null, null, null);
        String first = ledger.bind(vestNum, SECRET).bindSecretHash();
        sessions.openSession(vestNum, UuidV7.generate().toString(), Instant.now());

        Vest rebound = ledger.rebind(vestNum, "another-ledger-secret");

        assertThat(rebound.bound()).isTrue();
        assertThat(rebound.bindSecretHash()).isNotEqualTo(first);
        assertThat(passwords.matches("another-ledger-secret", rebound.bindSecretHash())).isTrue();
        assertThat(sessions.findSessionSid(vestNum)).isEmpty();

        int unbound = nextVestNum++;
        seed(unbound, TAG + "-unbound", Team.RED, false, null, null, null, null, null);
        assertCode(() -> ledger.rebind(unbound, SECRET), ApiErrorCode.DEVICE_VEST_NOT_BOUND);
    }

    @Test
    @DisplayName("解绑：bound=0 + 清会话，保留摘要且幂等")
    void unbind_isIdempotentAndKeepsTheSecret() {
        int vestNum = nextVestNum++;
        seed(vestNum, TAG + "-unbind", Team.RED, false, null, null, null, null, null);
        Vest boundRow = ledger.bind(vestNum, SECRET);
        sessions.openSession(vestNum, UuidV7.generate().toString(), Instant.now());

        Vest unbound = ledger.unbind(vestNum);

        assertThat(unbound.bound()).isFalse();
        // 解绑撤销的是「认领」而不是销毁凭据：摘要原样留着，同一密钥再绑一次仍然有效
        assertThat(unbound.bindSecretHash()).isEqualTo(boundRow.bindSecretHash());
        assertThat(passwords.matches(SECRET, unbound.bindSecretHash())).isTrue();
        assertThat(sessions.findSessionSid(vestNum)).isEmpty();

        // 幂等：已经处于目标状态时不再写库，也不报错
        assertThat(ledger.unbind(vestNum).bound()).isFalse();
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    private Page<Vest> filter(String keyword, Team team, Boolean bound, VestStatus status) {
        return ledger.list(keyword, team, bound, status, PageRequest.of(1, 50));
    }

    private static List<String> names(Page<Vest> page) {
        return page.items().stream().map(Vest::name).toList();
    }

    /**
     * 注销依赖「当前无演习进行中」。库层唯一索引保证至多一场进行中，这里在本事务内清掉它，
     * 用例结束随事务回滚，真实的进行中场次原样保留。
     */
    private void clearRunningExercises() {
        jdbc.sql("DELETE FROM t_exercise WHERE ended_at IS NULL").update();
    }

    private void startRunningExercise() {
        exercises.insert(new Exercise(UuidV7.generate(), UuidV7.generate(), TAG + " 想定", null,
                BattleMode.TRAIN, Instant.now(), null, 0, 0, 0, null, null));
    }

    /** 直接写库播种：这些列没有对应的服务入口（SIM / 固件版本来自设备上报）。 */
    private int seed(int vestNum, String name, Team team, boolean bound, String hash, Instant lastSeenAt,
                     String simNo, String model, String firmwareVersion) {
        vests.insert(new Vest(vestNum, name, team, simNo, model, firmwareVersion,
                hash, bound, true, lastSeenAt, null, null, null));
        return vestNum;
    }

    /**
     * 只写会话列、不动 {@code bound} 的播种：用于「未绑定但残留会话」这个前置。
     * 不能用 {@link VestDeviceSessionRepository#openSession}——它顺带把 {@code bound} 置 1。
     */
    private void openSessionKeepingUnbound(int vestNum) {
        jdbc.sql("UPDATE t_vest SET device_session_sid = :sid, device_login_at = :loginAt"
                        + " WHERE vest_num = :vestNum")
                .param("sid", UuidV7.generate().toString())
                .param("loginAt", java.sql.Timestamp.from(Instant.now()))
                .param("vestNum", vestNum)
                .update();
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ApiErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
