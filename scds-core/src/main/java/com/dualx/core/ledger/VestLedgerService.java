package com.dualx.core.ledger;

import com.dualx.core.domain.Vest;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.repository.VestDeviceSessionRepository;
import com.dualx.core.repository.VestRepository;
import com.dualx.core.repository.VestRepository.VestFilter;
import com.dualx.core.support.Page;
import com.dualx.core.support.PageRequest;
import com.dualx.protocol.ProtocolConstants;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 马甲台账服务（契约 docs/api/paths/device.yaml，需求 FR-LEDGER）。
 *
 * <p><b>为什么必须经过这一层</b>：契约里 {@code deleteVest} 是**逻辑注销**，而
 * {@link VestRepository#deleteByVestNum} 是物理删除；库层又没有外键，因此「不许直接暴露
 * 物理删除」与「演习进行中禁止注销」这两件事只能由服务负责（评审 R22）。控制器一律调本类，
 * 不碰仓储。</p>
 *
 * <p><b>哪里需要事务</b>：注销与绑定闭环都要写两处——台账行（{@code enabled} / {@code bound} /
 * {@code bind_secret_hash}）与设备会话（{@code device_session_sid}）。分属两个仓储、两条语句，
 * 就用 {@code @Transactional} 把它们收进一次提交；单语句的登记 / 修改则不加事务，
 * 与 {@code DeviceAuthService} 同一取向——为一条语句套事务只会多出「事务内抛异常要不要回滚」
 * 的无谓分支。</p>
 *
 * <p><b>不引入缓存</b>（决策 04 D03-5）：{@code t_vest} 即权威，读写都走它。</p>
 */
@Service
public class VestLedgerService {

    /** 库内统一存 UTC 挂钟值（V1 迁移文件头第 2 条）。 */
    private static final Clock CLOCK = Clock.systemUTC();

    private final VestRepository vests;
    private final VestDeviceSessionRepository sessions;
    private final ExerciseRepository exercises;
    private final PasswordEncoder passwordEncoder;

    public VestLedgerService(VestRepository vests,
                             VestDeviceSessionRepository sessions,
                             ExerciseRepository exercises,
                             PasswordEncoder passwordEncoder) {
        this.vests = vests;
        this.sessions = sessions;
        this.exercises = exercises;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 台账分页查询。过滤条件的口径全部压在 {@link VestRepository#search} 一处，
     * 本方法只负责把「HTTP 查询参数」翻译成仓储认识的形状。
     *
     * <p>{@code status} 非空时才计算在线下界：它只是「距今是否超过心跳窗口」，
     * 与 {@code last_seen_at} 的比较在同一瞬间完成，不传下去就没人会误用旧时刻。</p>
     */
    public Page<Vest> list(String keyword, Team team, Boolean bound, VestStatus status, PageRequest request) {
        Instant onlineSince = status == null
                ? null
                : CLOCK.instant().minusMillis(ProtocolConstants.HEARTBEAT_ONLINE_WINDOW_MS);
        VestFilter filter = new VestFilter(blankToNull(keyword), team, bound, status, onlineSince);
        return Page.of(vests.search(filter, request), vests.count(filter), request);
    }

    /** 单条台账；不存在即 404，不做「返回 null 让调用方自己判」。 */
    public Vest get(int vestNum) {
        return vests.findByVestNum(vestNum)
                .orElseThrow(() -> notFound(vestNum));
    }

    /**
     * 登记马甲。新行一律 {@code bound=false}、{@code enabled=true}、无绑定密钥——
     * 认领是绑定闭环的事，登记只负责「台账里有这台设备」。
     */
    public Vest create(VestDraft draft) {
        Vest drafted = new Vest(draft.vestNum(), draft.name(), draft.team(), null, draft.model(),
                null, null, false, true, null, draft.remark(), null, null);
        try {
            vests.insert(drafted);
        } catch (DataIntegrityViolationException e) {
            // 主键冲突是唯一能让这条 INSERT 失败的业务原因（编号上下界已由契约约束在 DTO 上挡住）
            throw new BusinessException(ApiErrorCode.DEVICE_VEST_NUM_CONFLICT,
                    "马甲编号已存在：" + draft.vestNum());
        }
        return get(draft.vestNum());
    }

    /**
     * 修改台账人工维护的几列。{@code simNo} / {@code firmwareVersion} / {@code lastSeenAt}
     * 从当前行原样带回——它们由设备上报回写，请求体里没有这些字段，不能让一次改名把它们抹掉。
     */
    public Vest update(int vestNum, VestDraft draft) {
        Vest current = get(vestNum);
        if (draft.vestNum() != vestNum) {
            // 契约允许 PUT 请求体带 vestNum（与 POST 共用同一 schema），两者不一致时无法判定意图
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED,
                    "路径编号与请求体 vestNum 不一致：" + vestNum + " / " + draft.vestNum());
        }
        vests.update(edited(current, draft.name(), draft.team(), draft.model(), draft.remark()));
        return get(vestNum);
    }

    /**
     * 逻辑注销（契约 {@code deleteVest}）：{@code enabled=0} + 清会话，**保留绑定密钥**。
     *
     * <p>物理删除被刻意排除：{@code t_exercise_unit} / {@code t_hit_event} / {@code t_track_sample}
     * 都以 {@code vest_num} 引用历史，删掉行会让复盘里的编号指向虚空；{@code enabled=0} 已经
     * 足够表达「这台设备不算数了」。</p>
     *
     * <p><b>引用保护只拦「演习进行中」</b>：此刻该设备正被 {@code t_exercise_unit} 引用，
     * 注销会让态势凭空少人。想定编成（{@code t_plan_roster}）的引用不拦——编成在下一次开演时
     * 才生效，届时按 {@code enabled} 过滤即可，为一个可能永远不会再用的想定锁住台账得不偿失。</p>
     */
    @Transactional
    public void retire(int vestNum) {
        Vest current = get(vestNum);
        if (exercises.findRunning().isPresent()) {
            throw new BusinessException(ApiErrorCode.EXERCISE_ALREADY_RUNNING,
                    "演习进行中，禁止注销马甲：" + vestNum);
        }
        vests.update(rebound(current, current.bindSecretHash(), current.bound(), false));
        sessions.clearSession(vestNum);
    }

    /**
     * 首次绑定（契约 {@code bindVest}）：要求当前未绑定，写入新摘要并置 {@code bound=1}。
     *
     * <p>对已绑定的马甲报 409 而不是直接覆盖：绑定与换绑分成两个方法，客户端才分得清
     * 「我以为这台是新的」与「我确实要替换凭据」（决策 04 D03-4）。</p>
     */
    @Transactional
    public Vest bind(int vestNum, String secret) {
        Vest current = get(vestNum);
        if (current.bound()) {
            throw new BusinessException(ApiErrorCode.DEVICE_VEST_ALREADY_BOUND,
                    "该马甲已绑定，换绑请用 PUT：" + vestNum);
        }
        return writeSecret(current, secret);
    }

    /** 换绑（契约 {@code rebindVest}）：要求当前已绑定，{@code bound} 保持 1。 */
    @Transactional
    public Vest rebind(int vestNum, String secret) {
        Vest current = get(vestNum);
        if (!current.bound()) {
            throw new BusinessException(ApiErrorCode.DEVICE_VEST_NOT_BOUND,
                    "该马甲尚未绑定，首次绑定请用 POST：" + vestNum);
        }
        return writeSecret(current, secret);
    }

    /**
     * 解绑（契约 {@code unbindVest}）：{@code bound=0} + 清会话，**保留摘要**——解绑撤销的是
     * 「认领」这件事，不是销毁凭据，同一密钥再绑一次仍然有效（决策 04 D03-4）。
     *
     * <p>幂等：本来就未绑定时直接返回当前行，不写库、不报错。DELETE 的语义是
     * 「保证它处于未绑定」，而不是「必须曾经绑定过」。</p>
     */
    @Transactional
    public Vest unbind(int vestNum) {
        Vest current = get(vestNum);
        if (!current.bound()) {
            return current;
        }
        vests.update(rebound(current, current.bindSecretHash(), false, current.enabled()));
        sessions.clearSession(vestNum);
        return get(vestNum);
    }

    /**
     * 绑定与换绑共用的写入口。
     *
     * <p>「清 SID」不是可选项：换了一份凭据，此前签发的设备令牌必须立即失效，否则
     * 拿着旧令牌的终端能一直用到过期（设计 03 §4.9）。两条语句同处一次提交，
     * 不会出现「摘要已换、旧令牌仍有效」的窗口。</p>
     */
    private Vest writeSecret(Vest current, String secret) {
        vests.update(rebound(current, passwordEncoder.encode(secret), true, current.enabled()));
        sessions.clearSession(current.vestNum());
        return get(current.vestNum());
    }

    /** 只改「人工维护」的四列，其余（含设备上报的 SIM / 固件版本）沿用当前行。 */
    private static Vest edited(Vest base, String name, Team team, String model, String remark) {
        return new Vest(base.vestNum(), name, team, base.simNo(), model, base.firmwareVersion(),
                base.bindSecretHash(), base.bound(), base.enabled(), base.lastSeenAt(),
                remark, base.createdAt(), base.updatedAt());
    }

    /** 只改「凭据 + 生命周期」三处，其余沿用当前行——注销 / 绑定 / 解绑都走这一条。 */
    private static Vest rebound(Vest base, String bindSecretHash, boolean bound, boolean enabled) {
        return new Vest(base.vestNum(), base.name(), base.team(), base.simNo(), base.model(),
                base.firmwareVersion(), bindSecretHash, bound, enabled, base.lastSeenAt(),
                base.remark(), base.createdAt(), base.updatedAt());
    }

    private static BusinessException notFound(int vestNum) {
        return new BusinessException(ApiErrorCode.DEVICE_VEST_NOT_FOUND, "马甲不存在：" + vestNum);
    }

    /** 空串与 null 同义：{@code ?keyword=} 是「没填」，不是「搜空字符串」。 */
    private static String blankToNull(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword;
    }

    /**
     * 登记 / 修改马甲的入参：只有「人工维护」的那几列。
     *
     * <p>刻意不含 {@code simNo} / {@code firmwareVersion}（设备上报，由上行链路回写），
     * 更不含 {@code bindSecretHash} / {@code bound} / {@code lastSeenAt}——把它们放进请求体，
     * 等于允许管理端手工伪造「这台设备刚上线过」。绑定密钥的写入口只有 {@link #bind} /
     * {@link #rebind}，这样「密钥只写摘要、永不回显」才是一处可审计的实现。</p>
     */
    public record VestDraft(int vestNum, String name, Team team, String model, String remark) {
    }
}
