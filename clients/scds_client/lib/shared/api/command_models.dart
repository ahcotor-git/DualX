/// command 域契约模型（`docs/api/schemas/domain.yaml`）。
///
/// 本文件只做**指令请求体的构造**与**受理回执的解析**：指挥指令是写操作，
/// 响应统一为 [CommandAck]。解析口径与 `exercise_models.dart` / `situation_models.dart` 一致：
/// **不臆造缺省值**——契约必填字段缺失 → `TypeError`；形状不符 / 未知枚举字面量 → `FormatException`。
library;

import 'situation_models.dart' show Team;

/// 武器槽位（`domain.yaml#/ReloadRequest.weapon`）。
enum WeaponSlot {
  primary('PRIMARY'),
  secondary('SECONDARY');

  const WeaponSlot(this.wire);

  /// 契约字面量。
  final String wire;

  static WeaponSlot fromWire(String wire) => values.firstWhere(
        (WeaponSlot value) => value.wire == wire,
        orElse: () => throw FormatException('未知 WeaponSlot 字面量: $wire'),
      );
}

/// 马甲轮询模式（`domain.yaml#/VestConfigRequest.mode`）。
///
/// `AUTO` 由硬件自身按轮询自主上报；`MANUAL` 暂停自主上报、由服务端覆盖配置驱动。
enum VestMode {
  auto('AUTO'),
  manual('MANUAL');

  const VestMode(this.wire);

  final String wire;

  static VestMode fromWire(String wire) => values.firstWhere(
        (VestMode value) => value.wire == wire,
        orElse: () => throw FormatException('未知 VestMode 字面量: $wire'),
      );
}

/// 充弹请求体（`domain.yaml#/ReloadRequest`）——单兵充弹与全体充弹共用。必填：`ammo` / `weapon`。
class ReloadRequest {
  const ReloadRequest({required this.ammo, required this.weapon});

  /// 弹量，取值 0..65535。
  final int ammo;
  final WeaponSlot weapon;

  Map<String, Object?> toJson() => <String, Object?>{
        'ammo': ammo,
        'weapon': weapon.wire,
      };
}

/// 覆盖配置请求体（`domain.yaml#/VestConfigRequest`）——对应 14B 轮询配置帧，下轮轮询生效。
///
/// **覆盖语义**：字段全可空，**未传的字段保持原值**，故 [toJson] 只输出非 `null` 的键
/// （不能把 `null` 当成「置空」下发）。范围：`hp` 0..255、`ammo1`/`ammo2` 0..65535。
class VestConfigRequest {
  const VestConfigRequest({this.mode, this.hp, this.ammo1, this.ammo2, this.team});

  final VestMode? mode;
  final int? hp;
  final int? ammo1;
  final int? ammo2;
  final Team? team;

  Map<String, Object?> toJson() {
    final Map<String, Object?> json = <String, Object?>{};
    if (mode != null) {
      json['mode'] = mode!.wire;
    }
    if (hp != null) {
      json['hp'] = hp;
    }
    if (ammo1 != null) {
      json['ammo1'] = ammo1;
    }
    if (ammo2 != null) {
      json['ammo2'] = ammo2;
    }
    if (team != null) {
      json['team'] = team!.wire;
    }
    return json;
  }
}

/// 手动置位请求体（`domain.yaml#/MoveVestRequest`）——联调专用，正式演习服务端拒绝（403）。
///
/// 字段可空，[toJson] 只输出非 `null` 的键。
class MoveVestRequest {
  const MoveVestRequest({this.floorNum, this.posNum});

  final int? floorNum;
  final int? posNum;

  Map<String, Object?> toJson() {
    final Map<String, Object?> json = <String, Object?>{};
    if (floorNum != null) {
      json['floorNum'] = floorNum;
    }
    if (posNum != null) {
      json['posNum'] = posNum;
    }
    return json;
  }
}

/// 指挥指令受理回执（`domain.yaml#/CommandAck`）——command 域全部接口的 200 响应体。
///
/// **HTTP 200 只表示「已受理」**：[accepted] 为 `false` 表示未入队、未下发任何字节
/// （马甲离线 / 队列满），此时 [queuedAt] 为 `null`。调用方应提示「未下发」而非报错。
class CommandAck {
  const CommandAck({
    required this.vestNum,
    required this.command,
    required this.accepted,
    this.queuedAt,
    this.downlinkHex,
  });

  factory CommandAck.fromJson(Map<String, dynamic> json) => CommandAck(
        vestNum: json['vestNum'] as int,
        command: json['command'] as String,
        accepted: json['accepted'] as bool,
        queuedAt: _dateTimeOrNull(json['queuedAt']),
        downlinkHex: json['downlinkHex'] as String?,
      );

  /// 目标马甲编号。**全体充弹的广播帧固定为 0**（真实编号下界为 1，不会混淆），
  /// 前端应据此把该条指令显示为「全体」，而不是编号 0 的马甲——见 [isBroadcast]。
  final int vestNum;

  /// 指令名，如 `DIE` / `RELOAD`。
  final String command;

  /// 是否入队；`false` 表示已受理但未入队（马甲离线 / 队列满），未下发任何字节。
  final bool accepted;

  /// 入队时刻；[accepted] 为 `false` 时为空。
  final DateTime? queuedAt;

  /// 下发指令的十六进制原文（逐字节可核对）。
  final String? downlinkHex;

  /// 是否为全体广播回执（`vestNum == 0`）。
  bool get isBroadcast => vestNum == 0;
}

DateTime? _dateTimeOrNull(Object? value) =>
    value == null ? null : DateTime.parse(value as String);
