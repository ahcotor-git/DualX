package com.dualx.core.error;

/**
 * 稳定业务错误码（契约 docs/api/schemas/common.yaml → {@code Problem.code}）。
 *
 * <p>前端据此分支处理，**不得依赖 title 文案**。前缀分类与原值均与契约逐字一致；
 * 新增错误码必须先在 openapi 的 {@code Problem.code} 描述里登记，再回到这里补枚举。</p>
 *
 * <p>放在 scds-domain 而非 scds-api：core 的业务规则要先于接口层知道「失败属于哪一类」
 * （口令错、令牌过期、限流……），而 core 不能反向依赖 api。枚举下沉后，core 抛
 * {@link BusinessException}、api 只负责码到 HTTP 状态的一层映射，取值只有这一份定义。</p>
 *
 * <p>末尾框架级错误码不在业务前缀内，表示校验、MVC 请求失败、限流、
 * 未预期异常与骨架期占位接口。</p>
 */
public enum ApiErrorCode {

    // ===== AUTH_* 认证与令牌 =====
    AUTH_BAD_CREDENTIALS,
    AUTH_TOKEN_EXPIRED,
    AUTH_TOKEN_INVALID,
    AUTH_DEVICE_NOT_BOUND,
    /**
     * 已认证但角色/权限不足（HTTP 403）。
     *
     * <p>与 {@link #AUTH_TOKEN_INVALID} 严格分工：后者是「令牌本身不成立」（缺头、
     * 签名错、aud 不匹配、设备令牌越权连 WS），属于**没通过认证**；本码是令牌有效、
     * 但角色不允许该操作。前端据此区分「去重新登录」与「找管理员要权限」两种引导，
     * 混用会导致用户反复登录却始终被拒。</p>
     */
    AUTH_FORBIDDEN,

    // ===== EXERCISE_* 演习 =====
    EXERCISE_ALREADY_RUNNING,
    EXERCISE_NOT_RUNNING,
    EXERCISE_PLAN_NOT_FOUND,
    EXERCISE_ROSTER_EMPTY,

    // ===== COMMAND_* 指挥 =====
    COMMAND_VEST_OFFLINE,
    COMMAND_VEST_DEAD,
    COMMAND_INVALID_WEAPON_SLOT,
    COMMAND_QUEUE_FULL,

    // ===== DEVICE_* 设备与链路 =====
    DEVICE_VEST_NOT_FOUND,
    DEVICE_VEST_NUM_CONFLICT,
    /**
     * 首次绑定（{@code POST .../binding}）打到已绑定的马甲。
     *
     * <p>与 {@link #DEVICE_VEST_NOT_BOUND} 成对：契约刻意让 POST/PUT 分工不重叠，
     * 客户端才能区分「首次绑定」与「换绑」，而不是让两者静默互相顶替（决策 04 D03-4）。</p>
     */
    DEVICE_VEST_ALREADY_BOUND,
    /** 换绑（{@code PUT .../binding}）打到未绑定的马甲；首次绑定请用 POST。 */
    DEVICE_VEST_NOT_BOUND,
    DEVICE_LINK_ALREADY_OPEN,
    DEVICE_LINK_SERIAL_BUSY,
    DEVICE_ANCHOR_NOT_CALIBRATED,

    // ===== MAP_* 地图 =====
    MAP_NOT_FOUND,
    /**
     * 底图存在，但还没做过 UWB 标定（HTTP 404，契约 {@code getUwbMeta}）。
     *
     * <p>与 {@link #MAP_NOT_FOUND} 成对：两者都是 404，但现场处置动作不同——
     * 前者是「id 打错了」，后者是「图在、去标定」。契约原本让这条 404 与
     * {@code putUwbMeta} 的 400 共用 {@link #MAP_UWB_META_INCOMPLETE}，
     * 与「一码一状态」的状态表设计冲突，故按「新增专用码」拆开。</p>
     */
    MAP_NOT_CALIBRATED,
    MAP_IMAGE_MISSING,
    /** 保存 {@code uwb_meta} 时基站缺项或几何不自洽（HTTP 400，固定给 PUT 校验失败）。 */
    MAP_UWB_META_INCOMPLETE,
    MAP_CALIBRATION_IN_PROGRESS,
    /**
     * 底图被引用，拒绝删除（HTTP 409）。
     *
     * <p>与 {@link #MAP_NOT_FOUND} 成对：删除要比「存在性」多判一层「有没有人在用」。
     * 契约把 409 写成通用 {@code Conflict}，前端无法据此区分「被想定引用」与其它冲突，
     * 故单独成码——地图被引用是运营动作（先解绑想定），不是参数错误。</p>
     */
    MAP_IN_USE,

    // ===== PLAN_* 想定 =====
    /**
     * 想定不存在（HTTP 404，契约 {@code getPlan} / {@code updatePlan} / {@code deletePlan}
     * 与编成读写的公共前置）。
     *
     * <p>刻意不复用 {@link #EXERCISE_PLAN_NOT_FOUND}：那个码属开演语境（它描述的是
     * 「开演所引用的想定查不到」这一条失败路径），而本码描述的是想定资源本身的读取失败。
     * 两者状态同为 404，但现场处置不同——前者是「这场演习的想定被人删了」，
     * 后者是「你点的这个想定不在」。与地图域 {@link #MAP_NOT_FOUND} 同一取向。</p>
     */
    PLAN_NOT_FOUND,
    /**
     * 该想定正在演习中，禁止修改 / 删除 / 改编成（HTTP 409，契约 {@code updatePlan} /
     * {@code deletePlan} / {@code replaceRoster}）。
     *
     * <p>与 {@link #EXERCISE_ALREADY_RUNNING} 成对但不同义：后者是「开演被拒」，本码是
     * 「想定被占着不能改」。演习运行中改想定会让 {@code t_exercise} 里的名/图快照与
     * 想定本体不一致，现场看到的两处数据会互相打脸，因此必须拒绝而不是静默放过。</p>
     */
    PLAN_IN_USE,

    // ===== REPLAY_* 复盘 =====
    REPLAY_NOT_FOUND,
    REPLAY_EXPORT_FAILED,

    // ===== SITUATION_* 态势 =====
    SITUATION_REVISION_TOO_OLD,

    // ===== FILE_* 受保护文件 =====
    /**
     * 文件访问签名不成立：签名错、参数被改动，或已过期（HTTP 403，契约 {@code paths/files.yaml}）。
     *
     * <p>刻意**不拆分**「签名错」与「已过期」：客户端对两者的处置完全相同（回详情接口取新 URL），
     * 拆开只会让探测者能区分「路径存在但签名不对」与「签名对但过期了」。</p>
     *
     * <p>与 {@link #AUTH_TOKEN_INVALID} 分属两个信任域：文件签名走独立 HMAC 密钥（D09-⑤），
     * 轮换它不该让任何人重新登录。文件本身不存在则另走 {@link #MAP_IMAGE_MISSING}（404），
     * 两者状态码不同，前端不必靠文案区分。</p>
     */
    FILE_URL_INVALID,

    // ===== 实例级 =====
    /**
     * 本实例尚未完成启动自愈，暂不可接业务（HTTP 503，设计 01 §4.4）。
     *
     * <p>{@code ApplicationRunner} 排在 {@code ApplicationReadyEvent} 之前，但**挡不住已经监听的
     * HTTP 端口收请求**——启动瞬间会有这样一个窗口：进程活着、端口通、自愈还没把上次崩溃留下的
     * 「进行中」场次收尾。此刻若放行开演，新场次会撞唯一索引，而操作员看到的是一个看着正常、
     * 行为说不通的实例。</p>
     *
     * <p>与 {@link #INTERNAL_ERROR} 的 500 严格分工：后者是「这一次请求出了意外」，本码是
     * 「实例本身没问题，只是还没准备好」——客户端应当稍后重试（或去问运维启动是否卡住），
     * 而不是把这当成服务缺陷去翻日志。恢复**失败**不走本码：那时启动直接失败，进程根本不接受请求
     * （门禁的最强形态），本码只覆盖「还在跑、但没跑完」这一段。</p>
     */
    INSTANCE_NOT_READY,

    // ===== 框架级兜底 =====
    /** 字段级校验失败，详情见 {@code Problem.errors}。 */
    VALIDATION_FAILED,
    /** MVC 路由或静态资源不存在（HTTP 404），不代替业务资源错误码。 */
    ROUTE_NOT_FOUND,
    /** 请求方法不支持（HTTP 405），响应头保留 Allow。 */
    METHOD_NOT_ALLOWED,
    /** 无法提供 Accept 要求的响应类型（HTTP 406）。 */
    NOT_ACCEPTABLE,
    /** 上传超过服务器限制（HTTP 413）。 */
    PAYLOAD_TOO_LARGE,
    /** 请求 Content-Type 不支持（HTTP 415）。 */
    UNSUPPORTED_MEDIA_TYPE,
    /** 触发限流（HTTP 429），响应头带 {@code Retry-After}。 */
    RATE_LIMITED,
    /** 未预期异常的统一出口。 */
    INTERNAL_ERROR,
    /** 骨架期占位：路由与 DTO 已按契约登记，业务实现归属服务层。 */
    NOT_IMPLEMENTED
}
