/// scds-next 客户端契约常量。
///
/// 取值必须与 `docs/api/openapi.yaml` 和 `docs/api/schemas/common.yaml` 逐字一致。
/// 端口与路径前缀是**硬约束**：HTTP 11001 / UWB TCP Server 12001，不随部署形态改变。
library;

/// 边缘网关默认主机（私有化单机 / 演习现场）。现场主机 IP 由连接配置覆盖。
const String kDefaultHost = '127.0.0.1';

/// HTTP 服务端口（硬约束，见 openapi `servers`）。
const int kHttpPort = 11001;

/// 业务接口前缀（REST）。
const String kApiPrefix = '/api/v1';

/// WebSocket 前缀。
const String kWsPrefix = '/ws/v1';

/// 态势 WS 子协议（`docs/api/paths/situation.yaml` 的 `x-websocket.subprotocol`）。
const String kSituationSubprotocol = 'scds.situation.v1';

/// 错误响应媒体类型（RFC 7807）。
const String kProblemMediaType = 'application/problem+json';

/// 稳定业务错误码。前端据此分支处理，**不得依赖 `title` 文案**。
abstract final class ApiErrorCode {
  // AUTH_* 认证与令牌
  static const String authBadCredentials = 'AUTH_BAD_CREDENTIALS';
  static const String authTokenExpired = 'AUTH_TOKEN_EXPIRED';
  static const String authTokenInvalid = 'AUTH_TOKEN_INVALID';
  static const String authDeviceNotBound = 'AUTH_DEVICE_NOT_BOUND';
  static const String authForbidden = 'AUTH_FORBIDDEN';

  // EXERCISE_* 演习
  static const String exerciseAlreadyRunning = 'EXERCISE_ALREADY_RUNNING';
  static const String exerciseNotRunning = 'EXERCISE_NOT_RUNNING';
  static const String exercisePlanNotFound = 'EXERCISE_PLAN_NOT_FOUND';
  static const String exerciseRosterEmpty = 'EXERCISE_ROSTER_EMPTY';

  // COMMAND_* 指挥
  static const String commandVestOffline = 'COMMAND_VEST_OFFLINE';
  static const String commandVestDead = 'COMMAND_VEST_DEAD';
  static const String commandInvalidWeaponSlot = 'COMMAND_INVALID_WEAPON_SLOT';
  static const String commandQueueFull = 'COMMAND_QUEUE_FULL';

  /// 「正式演习中禁止手动置位」（HTTP 403）：演习模式不允许该动作，与 [authForbidden] 分属两件事。
  static const String commandMoveForbidden = 'COMMAND_MOVE_FORBIDDEN';

  // DEVICE_* 设备与链路
  static const String deviceVestNotFound = 'DEVICE_VEST_NOT_FOUND';
  static const String deviceVestNumConflict = 'DEVICE_VEST_NUM_CONFLICT';
  static const String deviceVestAlreadyBound = 'DEVICE_VEST_ALREADY_BOUND';
  static const String deviceVestNotBound = 'DEVICE_VEST_NOT_BOUND';
  static const String deviceLinkAlreadyOpen = 'DEVICE_LINK_ALREADY_OPEN';
  static const String deviceLinkSerialBusy = 'DEVICE_LINK_SERIAL_BUSY';

  /// 「目标链路当前未开启，本动作需要它先处于 OPEN」（HTTP 409），与 [deviceLinkAlreadyOpen] 语义相反。
  static const String deviceLinkNotOpen = 'DEVICE_LINK_NOT_OPEN';
  static const String deviceAnchorNotCalibrated = 'DEVICE_ANCHOR_NOT_CALIBRATED';

  // MAP_* 地图
  static const String mapNotFound = 'MAP_NOT_FOUND';
  static const String mapNotCalibrated = 'MAP_NOT_CALIBRATED';
  static const String mapImageMissing = 'MAP_IMAGE_MISSING';
  static const String mapUwbMetaIncomplete = 'MAP_UWB_META_INCOMPLETE';
  static const String mapCalibrationInProgress = 'MAP_CALIBRATION_IN_PROGRESS';
  static const String mapInUse = 'MAP_IN_USE';

  /// 「本场底图的这个楼层没有这个点位」（HTTP 404）。
  static const String mapFloorPointNotFound = 'MAP_FLOOR_POINT_NOT_FOUND';

  // PLAN_* 想定
  static const String planNotFound = 'PLAN_NOT_FOUND';
  static const String planInUse = 'PLAN_IN_USE';

  // REPLAY_* 复盘
  static const String replayNotFound = 'REPLAY_NOT_FOUND';
  static const String replayExportFailed = 'REPLAY_EXPORT_FAILED';

  // SITUATION_* 态势
  static const String situationRevisionTooOld = 'SITUATION_REVISION_TOO_OLD';

  // FILE_* 受保护文件
  /// 签名无效或已过期（HTTP 403，刻意不拆分两种成因）。
  static const String fileUrlInvalid = 'FILE_URL_INVALID';

  // 通用
  /// 本实例尚未完成启动自愈，稍后重试即可（HTTP 503）。
  static const String instanceNotReady = 'INSTANCE_NOT_READY';
  static const String validationFailed = 'VALIDATION_FAILED';
  static const String routeNotFound = 'ROUTE_NOT_FOUND';
  static const String methodNotAllowed = 'METHOD_NOT_ALLOWED';
  static const String notAcceptable = 'NOT_ACCEPTABLE';
  static const String payloadTooLarge = 'PAYLOAD_TOO_LARGE';
  static const String unsupportedMediaType = 'UNSUPPORTED_MEDIA_TYPE';
  static const String rateLimited = 'RATE_LIMITED';
  static const String internalError = 'INTERNAL_ERROR';
  static const String notImplemented = 'NOT_IMPLEMENTED';
}
