import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/app/client_factory.dart';
import 'package:scds_client/app/console_app.dart';
import 'package:scds_client/features/connection/connection_page.dart';
import 'package:scds_client/features/console/account_page.dart';
import 'package:scds_client/features/console/command_page.dart';
import 'package:scds_client/features/console/console_login_page.dart';
import 'package:scds_client/features/console/exercise_control_page.dart';
import 'package:scds_client/features/console/floor_points_page.dart';
import 'package:scds_client/features/console/link_panel_page.dart';
import 'package:scds_client/features/console/map_manager_page.dart';
import 'package:scds_client/features/console/overall_situation_page.dart';
import 'package:scds_client/features/console/plan_manager_page.dart';
import 'package:scds_client/features/console/replay_export_page.dart';
import 'package:scds_client/features/console/replay_list_page.dart';
import 'package:scds_client/features/console/replay_player_page.dart';
import 'package:scds_client/features/console/replay_statistics_page.dart';
import 'package:scds_client/features/console/roster_editor_page.dart';
import 'package:scds_client/features/console/team_summary_page.dart';
import 'package:scds_client/features/console/uwb_calibration_page.dart';
import 'package:scds_client/features/console/vest_ledger_page.dart';
import 'package:scds_client/shared/api/api_client.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/auth_models.dart';
import 'package:scds_client/shared/api/command_models.dart';
import 'package:scds_client/shared/api/device_models.dart';
import 'package:scds_client/shared/api/exercise_models.dart';
import 'package:scds_client/shared/api/health_api.dart';
import 'package:scds_client/shared/api/map_models.dart';
import 'package:scds_client/shared/api/replay_models.dart';
import 'package:scds_client/shared/api/situation_models.dart';
import 'package:scds_client/shared/auth/session_store.dart';
import 'package:scds_client/shared/config/connection_config.dart';
import 'package:scds_client/shared/config/connection_config_store.dart';
import 'package:scds_client/shared/state/situation_socket.dart';

/// M6 CLIENT-WINDOWS 段 5：指挥端外壳装配 + 连接配置页健康诊断 + 账号登录页 + 队伍汇总总览页。
///
/// 全部为纯 widget 测试：不启动后端、不发真实请求。
/// 健康诊断注入 `InstanceHealthProbe` 桩；登录页注入 `AccountLogin` 桩；
/// 汇总页注入 `fetch` 桩并用 `tester.pump(Duration)` 推进假时钟——
/// **不落盘、不打印令牌、不依赖真实网络**。
///
/// RES-D 追加「指挥端认证接线」组：注入脚本化 `ApiTransport` 桩（**同样不建连接**），
/// 断言的是真实的请求序列与 `Authorization` 头——认证接线的正确性恰恰体现在
/// 「谁在第几次请求带了哪个令牌」。
void main() {
  late InMemoryConnectionConfigStore store;
  late InMemorySessionStore sessionStore;

  setUp(() {
    store = InMemoryConnectionConfigStore();
    sessionStore = InMemorySessionStore();
  });

  Future<void> pumpConsole(
    WidgetTester tester, {
    ApiTransport Function(ConnectionConfig config)? clientFactory,
  }) async {
    await tester.pumpWidget(
      ScdsConsoleApp(
        configStore: store,
        sessionStore: sessionStore,
        clientFactory: clientFactory ?? httpApiClient,
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> pumpConnection(WidgetTester tester, InstanceHealthProbe probe) async {
    await tester.pumpWidget(
      MaterialApp(home: ConnectionPage(store: store, probe: probe)),
    );
    await tester.pumpAndSettle();
  }

  String hostText(WidgetTester tester) =>
      tester.widget<TextField>(find.byKey(kConnectionHostFieldKey)).controller!.text;

  String portText(WidgetTester tester) =>
      tester.widget<TextField>(find.byKey(kConnectionPortFieldKey)).controller!.text;

  /// 段 54：取某个 `TextField` 的当前文本（断言快照预填 / 清空）。
  String fieldText(WidgetTester tester, Key key) =>
      tester.widget<TextField>(find.byKey(key)).controller!.text;

  Future<void> enter(WidgetTester tester, String host, String port) async {
    await tester.enterText(find.byKey(kConnectionHostFieldKey), host);
    await tester.enterText(find.byKey(kConnectionPortFieldKey), port);
  }

  Future<void> tapSave(WidgetTester tester) async {
    await tester.tap(find.byKey(kConnectionSaveButtonKey));
    await tester.pumpAndSettle();
  }

  Future<void> tapDiagnose(WidgetTester tester) async {
    await tester.tap(find.byKey(kConnectionHealthButtonKey));
    await tester.pumpAndSettle();
  }

  AccountSession accountSession({
    String username = 'admin',
    List<String> roles = const <String>['OPERATOR'],
    List<String> permissions = const <String>[],
    String accessToken = 'access-1',
    String? refreshToken,
    DateTime? expiresAt,
  }) =>
      AccountSession(
        accessToken: accessToken,
        refreshToken: refreshToken,
        expiresAt: expiresAt ?? DateTime.utc(2026, 10, 29),
        user: AuthUser(
          userId: 'u-1',
          username: username,
          roles: roles,
          permissions: permissions,
        ),
      );

  Future<void> pumpLogin(
    WidgetTester tester,
    AccountLogin login, {
    ValueChanged<AccountSession>? onLoggedIn,
  }) async {
    await tester.pumpWidget(
      MaterialApp(home: ConsoleLoginPage(login: login, onLoggedIn: onLoggedIn)),
    );
    await tester.pumpAndSettle();
  }

  Future<void> enterCredentials(
    WidgetTester tester,
    String username,
    String password,
  ) async {
    await tester.enterText(find.byKey(kConsoleLoginUsernameFieldKey), username);
    await tester.enterText(find.byKey(kConsoleLoginPasswordFieldKey), password);
  }

  Future<void> tapLogin(WidgetTester tester) async {
    await tester.tap(find.byKey(kConsoleLoginSubmitButtonKey));
    await tester.pumpAndSettle();
  }

  /// 段 6：造一个契约形状的 `ApiException`（Problem 必填字段齐全）。
  ApiException apiFailure(int statusCode, String code) => ApiException(
        statusCode: statusCode,
        problem: ApiProblem(
          type: null,
          title: null,
          status: statusCode,
          code: code,
          detail: null,
          instance: null,
          traceId: 'trace-$code',
        ),
      );

  Future<void> pumpAccount(
    WidgetTester tester, {
    AccountSession? session,
    AccountRefresh? refresh,
    AccountReload? reload,
    AccountLogout? logout,
    SessionClearing? clearSession,
    VoidCallback? onLoggedOut,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: AccountPage(
          session: session ?? accountSession(),
          refresh: refresh ?? (AccountSession current) async => accountSession(),
          reload: reload ??
              (AccountSession current) async => AuthUser(
                    userId: 'u-1',
                    username: 'admin',
                    roles: const <String>['OPERATOR'],
                  ),
          logout: logout ?? (AccountSession current) async {},
          clearSession: clearSession ?? () async {},
          onLoggedOut: onLoggedOut,
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> tapAccountButton(WidgetTester tester, Key key) async {
    await tester.tap(find.byKey(key));
    await tester.pumpAndSettle();
  }

  TeamSummary teamSummary(
    Team team, {
    int? total = 6,
    int? live = 4,
    int? dead = 1,
    int? offline = 1,
    int? hitsDealt = 12,
  }) =>
      TeamSummary(
        team: team,
        total: total,
        live: live,
        dead: dead,
        offline: offline,
        hitsDealt: hitsDealt,
      );

  Future<void> pumpSummary(
    WidgetTester tester,
    Future<List<TeamSummary>> Function() fetch, {
    Duration interval = const Duration(milliseconds: 200),
    VoidCallback? onOpenAccount,
    VoidCallback? onOpenSituation,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: TeamSummaryPage(
          session: accountSession(),
          fetch: fetch,
          interval: interval,
          onOpenAccount: onOpenAccount,
          onOpenSituation: onOpenSituation,
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 D 后半：造一条契约形状的态势信封（默认全量）。
  SituationEnvelope fullEnvelope({
    required int revision,
    List<UnitState> units = const <UnitState>[],
  }) =>
      SituationEnvelope(
        epoch: 'e-1',
        revision: revision,
        full: true,
        running: true,
        serverTime: DateTime.utc(2026, 10, 28, 12),
        exerciseId: 'ex-1',
        units: units,
      );

  UnitState unitOf(int vestNum, {VestStatus status = VestStatus.live, int? hp}) =>
      UnitState(vestNum: vestNum, status: status, revision: 1, hp: hp);

  /// 把「全体态势」页直接挂在树上（注入假 fetch / 假连接器，**不建真实连接**）。
  Future<void> pumpOverall(
    WidgetTester tester, {
    required Future<SituationEnvelope> Function({int? sinceRevision}) fetch,
    required Future<SituationSocket> Function({int? sinceRevision}) connect,
    void Function(int vestNum)? onCommand,
    Future<CommandAck> Function(ReloadRequest request)? reloadAll,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: OverallSituationPage(
          session: accountSession(),
          fetch: fetch,
          connect: connect,
          onCommand: onCommand,
          reloadAll: reloadAll,
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 47：造一条契约形状的受理回执。
  CommandAck ackOf({
    int vestNum = 14,
    String command = 'DIE',
    bool accepted = true,
    String? downlinkHex,
  }) =>
      CommandAck(
        vestNum: vestNum,
        command: command,
        accepted: accepted,
        downlinkHex: downlinkHex,
      );

  /// 段 47：未注入的指令端口被触发即判失败（用例应只点注入过的按钮）。
  Future<CommandAck> unexpectedCommand() async => fail('未预期的指令下发');

  /// 段 47：单兵指令页直接挂树——六个端口都是闭包桩，**不发真实请求**。
  ///
  /// 页面较长（`ListView` 懒构建），把测试视口放大到一屏容纳全部控件，
  /// 免得底部的回执 / 错误文本因在屏外而未构建。
  Future<void> pumpCommand(
    WidgetTester tester, {
    int vestNum = 14,
    required Future<CommandAck> Function() kill,
    Future<CommandAck> Function()? revive,
    Future<CommandAck> Function()? injure,
    Future<CommandAck> Function()? outOfRange,
    Future<CommandAck> Function(ReloadRequest request)? reload,
    Future<CommandAck> Function(VestConfigRequest request)? setConfig,
  }) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: CommandPage(
          vestNum: vestNum,
          session: accountSession(),
          kill: kill,
          revive: revive ?? unexpectedCommand,
          injure: injure ?? unexpectedCommand,
          outOfRange: outOfRange ?? unexpectedCommand,
          reload: reload ?? (ReloadRequest _) => unexpectedCommand(),
          setConfig: setConfig ?? (VestConfigRequest _) => unexpectedCommand(),
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 49：造一个契约形状的想定（必填仅 `planId` / `name` / `createdAt`）。
  Plan planOf(
    String planId, {
    String name = '想定A',
    String? mapId,
    BattleMode? battleMode,
    String? remark,
    int? rosterCount,
  }) =>
      Plan(
        planId: planId,
        name: name,
        createdAt: DateTime.utc(2026, 10, 29),
        mapId: mapId,
        battleMode: battleMode,
        remark: remark,
        rosterCount: rosterCount,
      );

  PlanPage planPageOf(List<Plan> items, {int? total}) => PlanPage(
        items: items,
        meta: PageMeta(page: 1, size: 20, total: total ?? items.length),
      );

  /// 段 49：想定管理页直接挂树——四个写端口都是闭包桩，**不发真实请求**。
  ///
  /// 未注入的写端口被触发即判失败（用例应只点注入过的动作）。
  /// 段 59 追加四端口：模板下载 / 导入 / 选文件 / 落盘（皆为 `null` 时不渲染对应入口）。
  /// 视口放大到一屏容纳列表与悬浮新建按钮，免得 `ListView` 懒构建漏掉控件。
  Future<void> pumpPlans(
    WidgetTester tester, {
    PlanLister? list,
    PlanCreator? create,
    PlanUpdater? update,
    PlanRemover? remove,
    PlanTemplateDownloader? downloadTemplate,
    PlanImporter? importPlan,
    PlanFilePicker? pickFile,
    PlanTemplateSaver? saveBytes,
  }) async {
    tester.view.physicalSize = const Size(1200, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: PlanManagerPage(
          session: accountSession(),
          list: list ?? ({String? keyword}) async => planPageOf(<Plan>[]),
          create: create ?? (PlanUpsertRequest request) async => fail('未预期的新建想定'),
          update: update ??
              (String planId, PlanUpsertRequest request) async => fail('未预期的修改想定'),
          remove: remove ?? (String planId) async => fail('未预期的删除想定'),
          downloadTemplate: downloadTemplate,
          importPlan: importPlan,
          pickFile: pickFile,
          saveBytes: saveBytes,
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 59：造一个待导入的模板文件（文件名 + 原始字节）。
  PickedPlanFile pickedPlanFileOf({
    String filename = 'template.xlsx',
    List<int> bytes = const <int>[1, 2, 3],
  }) =>
      PickedPlanFile(filename: filename, bytes: bytes);

  /// 段 50：造一个契约形状的编成条目（必填仅 `vestNum` / `team`）。
  RosterEntry rosterEntryOf(
    int vestNum, {
    Team team = Team.red,
    String? name,
    int? hp,
    int? ammo1,
    int? ammo2,
    VestMode? mode,
  }) =>
      RosterEntry(
        vestNum: vestNum,
        team: team,
        name: name,
        hp: hp,
        ammo1: ammo1,
        ammo2: ammo2,
        mode: mode,
      );

  Roster rosterOf(List<RosterEntry> entries, {String planId = 'p-1'}) =>
      Roster(planId: planId, entries: entries);

  /// 段 50：编成编辑页直接挂树——两个端口都是闭包桩，**不发真实请求**。
  ///
  /// 视口放大到一屏容纳列表与悬浮保存按钮，免得 `ListView` 懒构建漏掉控件。
  Future<void> pumpRoster(
    WidgetTester tester, {
    String planId = 'p-1',
    String planName = '红方突袭',
    RosterLoader? load,
    RosterSaver? save,
  }) async {
    tester.view.physicalSize = const Size(1200, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: RosterEditorPage(
          session: accountSession(),
          planId: planId,
          planName: planName,
          load: load ?? () async => rosterOf(<RosterEntry>[]),
          save: save ?? (Roster roster) async => fail('未预期的保存编成'),
        ),
      ),
    );
    await tester.pump();
  }

  /// 卸载整棵树：汇总页 `dispose` 会取消周期定时器，避免测试结束时残留 timer。
  Future<void> unmount(WidgetTester tester) => tester.pumpWidget(const SizedBox());

  /// 段 51：造一个契约形状的当前演习状态（必填仅 `running`）。
  Exercise exerciseOf({
    bool running = false,
    String? exerciseId,
    String? planId,
    String? planName,
    String? mapId,
    BattleMode? battleMode,
    int? elapsedSeconds,
    int? rosterCount,
    int? onlineCount,
  }) =>
      Exercise(
        running: running,
        exerciseId: exerciseId,
        planId: planId,
        planName: planName,
        mapId: mapId,
        battleMode: battleMode,
        elapsedSeconds: elapsedSeconds,
        rosterCount: rosterCount,
        onlineCount: onlineCount,
      );

  /// 段 51：演习控制页直接挂树——四个端口都是闭包桩，**不发真实请求**。
  ///
  /// 视口放大到一屏容纳表单与按钮，免得 `ListView` 懒构建漏掉控件。
  Future<void> pumpExercise(
    WidgetTester tester, {
    CurrentExerciseLoader? load,
    PlanLister? listPlans,
    ExerciseStarter? start,
    ExerciseStopper? stop,
  }) async {
    tester.view.physicalSize = const Size(1200, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: ExerciseControlPage(
          session: accountSession(),
          load: load ?? () async => exerciseOf(),
          listPlans:
              listPlans ?? ({String? keyword}) async => planPageOf(<Plan>[]),
          start: start ?? (StartExerciseRequest request) async => fail('未预期的开演'),
          stop: stop ?? () async => fail('未预期的停演'),
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 53：造一个契约形状的底图（必填仅 `mapId` / `name` / `type`）。
  MapDetail mapOf(
    String mapId, {
    String name = '底图A',
    MapType type = MapType.indoor,
    String? imageUrl,
    int? imageWidth,
    int? imageHeight,
    int? floorCount,
    bool? calibrated,
  }) =>
      MapDetail(
        mapId: mapId,
        name: name,
        type: type,
        imageUrl: imageUrl,
        imageWidth: imageWidth,
        imageHeight: imageHeight,
        floorCount: floorCount,
        calibrated: calibrated,
      );

  MapPage mapPageOf(List<MapDetail> items, {int? total}) => MapPage(
        items: items,
        meta: PageMeta(page: 1, size: 20, total: total ?? items.length),
      );

  /// 段 53：1×1 透明 PNG（真实图片头）——驱动 `Image.memory` 走正常解码路径。
  final List<int> tinyPng = base64Decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=',
  );

  /// 段 53：底图管理页直接挂树——四个必填端口都是闭包桩，**不发真实请求**。
  ///
  /// 上传 / 预览 / 选文件三个端口可选：不注入即不渲染对应行内按钮（既有只读用例不受影响）。
  /// 视口放大到一屏容纳列表与悬浮新建按钮，免得 `ListView` 懒构建漏掉控件。
  Future<void> pumpMaps(
    WidgetTester tester, {
    MapLister? list,
    MapCreator? create,
    MapUpdater? update,
    MapRemover? remove,
    MapImageUploader? uploadImage,
    MapImageDownloader? downloadImage,
    MapImagePicker? pickImage,
    MapCalibrationOpener? onOpenCalibration,
    MapFloorPointsOpener? onOpenFloorPoints,
  }) async {
    tester.view.physicalSize = const Size(1400, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: MapManagerPage(
          session: accountSession(),
          list: list ?? () async => mapPageOf(<MapDetail>[]),
          create: create ?? (MapUpsertRequest request) async => fail('未预期的新建底图'),
          update: update ??
              (String mapId, MapUpsertRequest request) async => fail('未预期的修改底图'),
          remove: remove ?? (String mapId) async => fail('未预期的删除底图'),
          uploadImage: uploadImage,
          downloadImage: downloadImage,
          pickImage: pickImage,
          onOpenCalibration: onOpenCalibration,
          onOpenFloorPoints: onOpenFloorPoints,
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 54：造一份契约形状的 UWB 标定参数（bs1~bs4 必填，长宽基准必填）。
  UwbMeta uwbMetaOf({
    double bs1x = 0,
    double bs1y = 0,
    double length = 600,
    double width = 400,
    UwbAnchorPoint? bs5,
  }) =>
      UwbMeta(
        bs1: UwbAnchorPoint(xPx: bs1x, yPx: bs1y),
        bs2: const UwbAnchorPoint(xPx: 600, yPx: 0),
        bs3: const UwbAnchorPoint(xPx: 600, yPx: 400),
        bs4: const UwbAnchorPoint(xPx: 0, yPx: 400),
        bs5: bs5,
        realLengthCm: length,
        realWidthCm: width,
      );

  /// 段 54：UWB 标定面板直接挂树——五个端口都是闭包桩，**不发真实请求**。
  ///
  /// 视口放大到一屏容纳 8 行基站输入与两个按钮，免得 `ListView` 懒构建漏掉控件。
  Future<void> pumpUwb(
    WidgetTester tester, {
    String mapId = 'm-1',
    String mapName = '演训场',
    UwbMetaLoader? loadMeta,
    UwbMetaSaver? saveMeta,
    UwbCalibrationStarter? startCalibration,
    UwbCalibrationPoller? pollCalibration,
    UwbCalibrationClearer? clearCalibration,
  }) async {
    tester.view.physicalSize = const Size(1400, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: UwbCalibrationPage(
          session: accountSession(),
          mapId: mapId,
          mapName: mapName,
          loadMeta: loadMeta ?? () async => fail('未预期的读取标定参数'),
          saveMeta: saveMeta ?? (UwbMeta meta) async => fail('未预期的保存标定参数'),
          startCalibration:
              startCalibration ?? (UwbCalibrationRequest? request) async => fail('未预期的启动校准'),
          pollCalibration: pollCalibration ?? () async => fail('未预期的查询校准状态'),
          clearCalibration: clearCalibration ?? () async => fail('未预期的清除校准'),
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 55：造一个契约形状的楼层点位（坐标是归一化百分比 0~1，原点左上）。
  FloorPoint floorPointOf({int posNum = 1, double x = 0.1, double y = 0.2, String? label}) =>
      FloorPoint(posNum: posNum, xPct: x, yPct: y, label: label);

  /// 段 55：楼层点位编辑页直接挂树——两个端口都是闭包桩，**不发真实请求**。
  ///
  /// 视口放大到一屏容纳楼层号行与多行点位，免得 `ListView` 懒构建漏掉控件。
  Future<void> pumpFloor(
    WidgetTester tester, {
    String mapId = 'm-1',
    String mapName = '演训场',
    int? floorCount,
    FloorPointsLoader? loadPoints,
    FloorPointsSaver? savePoints,
  }) async {
    tester.view.physicalSize = const Size(1400, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: FloorPointsPage(
          session: accountSession(),
          mapId: mapId,
          mapName: mapName,
          floorCount: floorCount,
          loadPoints: loadPoints ?? (int floorNum) async => fail('未预期的读取楼层点位'),
          savePoints: savePoints ??
              (int floorNum, FloorPointsRequest request) async => fail('未预期的保存楼层点位'),
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 57：造一个契约形状的复盘概览（必填仅 `replayId` / `planName` / `startedAt`）。
  ///
  /// 其余字段契约未置 `required`，默认留空——用来验证「缺失显示破折号，不臆造 0」。
  Replay replayOf(
    String replayId, {
    String planName = '想定A',
    DateTime? startedAt,
    BattleMode? battleMode,
    int? durationSeconds,
    int? participantCount,
    int? hitCount,
    int? deadCount,
  }) =>
      Replay(
        replayId: replayId,
        planName: planName,
        startedAt: startedAt ?? DateTime.utc(2026, 10, 1, 8),
        battleMode: battleMode,
        durationSeconds: durationSeconds,
        participantCount: participantCount,
        hitCount: hitCount,
        deadCount: deadCount,
      );

  ReplayPage replayPageOf(List<Replay> items, {int page = 1, int size = 20, int? total}) =>
      ReplayPage(
        items: items,
        meta: PageMeta(page: page, size: size, total: total ?? items.length),
      );

  /// 段 57：复盘列表页直接挂树——`list` 是闭包桩，**不发真实请求**。
  ///
  /// 视口放大到一屏容纳过滤行与分页行，免得 `ListView` 懒构建漏掉控件。
  Future<void> pumpReplayList(
    WidgetTester tester, {
    ReplayLister? list,
    ReplayOpener? onOpenReplay,
    ReplayExportOpener? onOpenExport,
  }) async {
    tester.view.physicalSize = const Size(1400, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: ReplayListPage(
          session: accountSession(),
          list: list ??
              ({String? keyword, DateTime? from, DateTime? to, int? page, int? size}) async =>
                  replayPageOf(<Replay>[]),
          onOpenReplay: onOpenReplay,
          onOpenExport: onOpenExport,
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 57：造一个契约形状的轨迹采样点（坐标两组各自可空，库里有哪套就写哪套）。
  TrackSample sampleOf({
    int t = 0,
    int vestNum = 1,
    double? xPct,
    double? yPct,
    double? lng,
    double? lat,
    int? hp,
    VestStatus? status,
  }) =>
      TrackSample(
        t: t,
        vestNum: vestNum,
        xPct: xPct,
        yPct: yPct,
        lng: lng,
        lat: lat,
        hp: hp,
        status: status,
      );

  /// 段 57：复盘回放器直接挂树——`loadTimeline` 是闭包桩，**不发真实请求**。
  Future<void> pumpReplayPlayer(
    WidgetTester tester, {
    Replay? replay,
    ReplayTimelineLoader? loadTimeline,
  }) async {
    tester.view.physicalSize = const Size(1400, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: ReplayPlayerPage(
          session: accountSession(),
          replay: replay ?? replayOf('r-1'),
          loadTimeline: loadTimeline ??
              ({String? cursor}) async => const TimelineChunk(samples: <TrackSample>[]),
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 57：造队伍战损汇总（契约五项计数皆非必填，默认留空验「破折号」）。
  TeamSummary teamSummaryOf(
    Team team, {
    int? total,
    int? live,
    int? dead,
    int? offline,
    int? hitsDealt,
  }) =>
      TeamSummary(
        team: team,
        total: total,
        live: live,
        dead: dead,
        offline: offline,
        hitsDealt: hitsDealt,
      );

  /// 段 57：造命中排行条目（字段皆非必填）。
  TopShooter shooterOf({int? vestNum, String? name, int? hitsDealt, int? kills}) =>
      TopShooter(vestNum: vestNum, name: name, hitsDealt: hitsDealt, kills: kills);

  /// 段 57：造命中部位分布条目。
  HitPartCount hitPartCountOf({HitPart? hitPart, int? count}) =>
      HitPartCount(hitPart: hitPart, count: count);

  /// 段 57：造存活曲线采样段（`t` 为**相对开演的毫秒偏移**）。
  TimelineBucket bucketOf({int? t, Map<String, int>? liveByTeam}) =>
      TimelineBucket(t: t, liveByTeam: liveByTeam);

  /// 段 57：造契约形状的复盘统计（必填仅 `replayId`）。
  ReplayStatistics statsOf(
    String replayId, {
    List<TeamSummary> teams = const <TeamSummary>[],
    List<TopShooter> topShooters = const <TopShooter>[],
    List<HitPartCount> hitPartDistribution = const <HitPartCount>[],
    List<TimelineBucket> timeline = const <TimelineBucket>[],
  }) =>
      ReplayStatistics(
        replayId: replayId,
        teams: teams,
        topShooters: topShooters,
        hitPartDistribution: hitPartDistribution,
        timeline: timeline,
      );

  /// 段 57：复盘统计面板直接挂树——`load` 是闭包桩，**不发真实请求**。
  Future<void> pumpReplayStatistics(
    WidgetTester tester, {
    Replay? replay,
    ReplayStatisticsLoader? load,
  }) async {
    tester.view.physicalSize = const Size(1400, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: ReplayStatisticsPage(
          session: accountSession(),
          replay: replay ?? replayOf('r-1'),
          load: load ??
              ({int? timelineBuckets}) async => statsOf('r-1'),
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 58：造契约形状的异步任务（必填仅 `taskId` / `state`，其余按需）。
  AsyncTask asyncTaskOf({
    String taskId = 't-1',
    AsyncTaskState state = AsyncTaskState.pending,
    int? progressPct,
    String? fileName,
    String? downloadUrl,
    DateTime? expireAt,
    String? errorMessage,
  }) =>
      AsyncTask(
        taskId: taskId,
        state: state,
        progressPct: progressPct,
        fileName: fileName,
        downloadUrl: downloadUrl,
        expireAt: expireAt,
        errorMessage: errorMessage,
      );

  /// 段 58：复盘导出面板直接挂树——三端口皆是闭包桩，**不发真实请求**。
  ///
  /// [saveBytes] 为 `null` 时不渲染「下载」按钮（生产端即如此，产物走 `downloadUrl`）。
  Future<void> pumpReplayExport(
    WidgetTester tester, {
    Replay? replay,
    ReplayExportStarter? startExport,
    ReplayExportPoller? pollExport,
    ReplayExportDownloader? downloadExport,
    ReplayExportSaver? saveBytes,
  }) async {
    tester.view.physicalSize = const Size(1400, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: ReplayExportPage(
          session: accountSession(),
          replay: replay ?? replayOf('r-1'),
          startExport: startExport ?? (ExportFormat format) async => asyncTaskOf(),
          pollExport: pollExport ?? (String taskId) async => asyncTaskOf(taskId: taskId),
          downloadExport: downloadExport ??
              (String taskId) async => ApiResponse(
                    statusCode: 200,
                    contentType: 'text/csv',
                    body: const <int>[1, 2, 3],
                  ),
          saveBytes: saveBytes,
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 61：造一个契约形状的马甲台账行（必填仅 `vestNum`）。
  VestDevice vestOf(
    int vestNum, {
    String? name,
    Team? team,
    String? simNo,
    String? model,
    String? firmwareVersion,
    String? remark,
    bool? bound,
    bool? enabled,
  }) =>
      VestDevice(
        vestNum: vestNum,
        name: name,
        team: team,
        simNo: simNo,
        model: model,
        firmwareVersion: firmwareVersion,
        remark: remark,
        bound: bound,
        enabled: enabled,
      );

  VestDevicePage vestPageOf(List<VestDevice> items, {int? total}) => VestDevicePage(
        items: items,
        meta: PageMeta(page: 1, size: 20, total: total ?? items.length),
      );

  /// 段 61：马甲台账页直接挂树——四个必填端口都是闭包桩，**不发真实请求**。
  ///
  /// 绑定三端口与单马甲链路端口（[bind] / [rebind] / [unbind] / [link]）可选：
  /// 不注入即不渲染对应行内按钮。
  /// 视口放大到一屏容纳行内动作按钮，免得 `ListView` 懒构建漏掉控件。
  Future<void> pumpVests(
    WidgetTester tester, {
    VestLister? list,
    VestCreator? create,
    VestUpdater? update,
    VestRemover? remove,
    VestBindingWriter? bind,
    VestBindingWriter? rebind,
    VestUnbinder? unbind,
    VestLinkReader? link,
  }) async {
    tester.view.physicalSize = const Size(1600, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: VestLedgerPage(
          session: accountSession(),
          list: list ?? ({String? keyword}) async => vestPageOf(<VestDevice>[]),
          create: create ?? (VestDeviceUpsertRequest request) async => fail('未预期的登记马甲'),
          update: update ??
              (int vestNum, VestDeviceUpsertRequest request) async => fail('未预期的修改马甲'),
          remove: remove ?? (int vestNum) async => fail('未预期的注销马甲'),
          bind: bind,
          rebind: rebind,
          unbind: unbind,
          link: link,
        ),
      ),
    );
    await tester.pump();
  }

  /// 段 63：造一条契约形状的单马甲链路状态（该内联对象无 `required`，全部可空）。
  VestLink vestLinkOf(
    int vestNum, {
    bool? online,
    DateTime? lastHeartbeatAt,
    String? lastFrameType,
    int? framesTotal,
    int? droppedTotal,
  }) =>
      VestLink(
        vestNum: vestNum,
        online: online,
        lastHeartbeatAt: lastHeartbeatAt,
        lastFrameType: lastFrameType,
        framesTotal: framesTotal,
        droppedTotal: droppedTotal,
      );

  /// 段 62：造一条契约形状的链路状态（必填仅 `kind` / `state`）。
  LinkStatus linkStatusOf(
    LinkKind kind, {
    LinkState state = LinkState.closed,
    String? target,
    int? baudRate,
    int? fdCount,
    int? framesTotal,
    int? droppedTotal,
    int? crcErrorTotal,
    String? errorMessage,
  }) =>
      LinkStatus(
        kind: kind,
        state: state,
        target: target,
        baudRate: baudRate,
        fdCount: fdCount,
        framesTotal: framesTotal,
        droppedTotal: droppedTotal,
        crcErrorTotal: crcErrorTotal,
        errorMessage: errorMessage,
      );

  /// 段 62：造一条契约形状的本机串口。
  SerialPortInfo portOf(String portName, {String? description}) =>
      SerialPortInfo(portName: portName, description: description);

  /// 段 62：造一行契约形状的 UWB 基站（必填仅 `anchorId`）。
  Anchor anchorOf(
    int anchorId, {
    String? name,
    double? xPx,
    double? yPx,
    bool? enabled,
  }) =>
      Anchor(anchorId: anchorId, name: name, xPx: xPx, yPx: yPx, enabled: enabled);

  /// 段 62：链路与基站页直接挂树——八个端口都是闭包桩，**不发真实请求**。
  ///
  /// 视口放大到一屏容纳全部小节（链路状态 / 开关 / 串口 / 基站），
  /// 免得 `ListView` 懒构建漏掉控件。
  Future<void> pumpLinks(
    WidgetTester tester, {
    LinkStatusReader? loadLinks,
    SerialPortLister? loadPorts,
    SerialLinkOpener? openSerial,
    SerialLinkCloser? closeSerial,
    UwbLinkOpener? openUwb,
    UwbLinkCloser? closeUwb,
    AnchorLister? loadAnchors,
    AnchorWriter? saveAnchor,
  }) async {
    tester.view.physicalSize = const Size(1600, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: LinkPanelPage(
          session: accountSession(),
          loadLinks: loadLinks ?? () async => <LinkStatus>[],
          loadPorts: loadPorts ?? () async => <SerialPortInfo>[],
          openSerial: openSerial ??
              (LinkOpenSerialRequest request) async => fail('未预期的开启串口链路'),
          closeSerial: closeSerial ?? () async => fail('未预期的关闭串口链路'),
          openUwb: openUwb ??
              (LinkOpenUwbRequest request) async => fail('未预期的开启 UWB 链路'),
          closeUwb: closeUwb ?? () async => fail('未预期的关闭 UWB 链路'),
          loadAnchors: loadAnchors ?? () async => <Anchor>[],
          saveAnchor: saveAnchor ??
              (int anchorId, AnchorUpsertRequest request) async => fail('未预期的配置基站'),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  group('指挥端外壳装配', () {
    testWidgets('启动即进入连接配置页，预填契约默认地址且带健康诊断入口', (WidgetTester tester) async {
      await pumpConsole(tester);

      expect(find.byType(ConnectionPage), findsOneWidget);
      expect(hostText(tester), '127.0.0.1');
      expect(portText(tester), '11001');
      expect(find.byKey(kConnectionHealthButtonKey), findsOneWidget);
      expect(find.byKey(kConnectionHealthStatusKey), findsNothing);
    });

    testWidgets('存储已有配置时预填该配置', (WidgetTester tester) async {
      await store.write(ConnectionConfig(host: '192.168.110.110', port: 11001));

      await pumpConsole(tester);

      expect(hostText(tester), '192.168.110.110');
      expect(portText(tester), '11001');
    });

    testWidgets('保存合法配置后写入存储并跳转指挥端登录页', (WidgetTester tester) async {
      await pumpConsole(tester);
      await enter(tester, '  192.168.110.110  ', '11001');

      await tapSave(tester);

      final ConnectionConfig? saved = await store.read();
      expect(saved?.host, '192.168.110.110');
      expect(saved?.port, 11001);
      expect(find.byType(ConsoleLoginPage), findsOneWidget);
    });

    testWidgets('空白地址：行内报错，不写存储、不跳转', (WidgetTester tester) async {
      await pumpConsole(tester);
      await enter(tester, '   ', '11001');

      await tapSave(tester);

      expect(find.byKey(kConnectionErrorKey), findsOneWidget);
      expect(find.text('实例地址不能为空'), findsOneWidget);
      expect(await store.read(), isNull);
      expect(find.byType(ConnectionPage), findsOneWidget);
    });
  });

  group('连接配置页健康诊断', () {
    testWidgets('未点诊断按钮时不探测；点击后按结果展示状态与结论', (WidgetTester tester) async {
      final List<ConnectionConfig> calls = <ConnectionConfig>[];
      await pumpConnection(tester, (ConnectionConfig config) async {
        calls.add(config);
        return const HealthReport(status: 'UP', httpStatus: 200, detail: '实例就绪（HTTP 200）');
      });

      expect(calls, isEmpty);
      expect(find.byKey(kConnectionHealthStatusKey), findsNothing);

      await tapDiagnose(tester);

      expect(calls.length, 1);
      expect(calls.single.display, '127.0.0.1:11001');
      expect(tester.widget<Text>(find.byKey(kConnectionHealthStatusKey)).data, '实例状态：UP');
      expect(
        tester.widget<Text>(find.byKey(kConnectionHealthDetailKey)).data,
        contains('实例就绪'),
      );
      expect(find.byKey(kConnectionHealthProgressKey), findsNothing);
    });

    testWidgets('诊断用当前字段而非已存配置：改地址后按新地址探测', (WidgetTester tester) async {
      final List<ConnectionConfig> calls = <ConnectionConfig>[];
      await store.write(ConnectionConfig(host: '10.0.0.9', port: 11001));
      await pumpConnection(tester, (ConnectionConfig config) async {
        calls.add(config);
        return const HealthReport(status: 'UP', httpStatus: 200, detail: '实例就绪（HTTP 200）');
      });
      await enter(tester, '192.168.110.110', '11001');

      await tapDiagnose(tester);

      expect(calls.single.display, '192.168.110.110:11001');
      final ConnectionConfig? stillStored = await store.read();
      expect(stillStored?.host, '10.0.0.9');
    });

    testWidgets('实例可达但未就绪 503：显示 HTTP_503 且给出依赖提示', (WidgetTester tester) async {
      await pumpConnection(
        tester,
        (ConnectionConfig config) async => const HealthReport(
          status: 'HTTP_503',
          httpStatus: 503,
          detail: '实例可达但未就绪（HTTP 503）——数据库/依赖可能尚未就绪',
        ),
      );

      await tapDiagnose(tester);

      expect(tester.widget<Text>(find.byKey(kConnectionHealthStatusKey)).data, '实例状态：HTTP_503');
      expect(
        tester.widget<Text>(find.byKey(kConnectionHealthDetailKey)).data,
        contains('未就绪'),
      );
    });

    testWidgets('探活端口抛异常：收敛成 UNREACHABLE，不冒泡成红屏', (WidgetTester tester) async {
      await pumpConnection(
        tester,
        (ConnectionConfig config) async => throw StateError('connection refused'),
      );

      await tapDiagnose(tester);

      expect(find.byKey(kConnectionHealthStatusKey), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(kConnectionHealthDetailKey)).data,
        contains('无法连接实例'),
      );
    });

    testWidgets('端口非数字时点诊断：行内报错，不调用探活', (WidgetTester tester) async {
      final List<ConnectionConfig> calls = <ConnectionConfig>[];
      await pumpConnection(tester, (ConnectionConfig config) async {
        calls.add(config);
        return const HealthReport(status: 'UP', httpStatus: 200, detail: '实例就绪（HTTP 200）');
      });
      await enter(tester, '192.168.110.110', 'abc');

      await tapDiagnose(tester);

      expect(find.text('端口须为数字'), findsOneWidget);
      expect(calls, isEmpty);
      expect(find.byKey(kConnectionHealthStatusKey), findsNothing);
    });
  });

  group('指挥端登录页', () {
    testWidgets('账号为空：本地报错，不调用登录端口', (WidgetTester tester) async {
      final List<(String, String)> calls = <(String, String)>[];
      await pumpLogin(tester, ({required String username, required String password}) async {
        calls.add((username, password));
        return accountSession();
      });

      await enterCredentials(tester, '   ', 'p@ssw0rd');
      await tapLogin(tester);

      expect(find.text('账号不能为空'), findsOneWidget);
      expect(calls, isEmpty);
    });

    testWidgets('密码为空：本地报错，不调用登录端口', (WidgetTester tester) async {
      final List<(String, String)> calls = <(String, String)>[];
      await pumpLogin(tester, ({required String username, required String password}) async {
        calls.add((username, password));
        return accountSession();
      });

      await enterCredentials(tester, 'admin', '');
      await tapLogin(tester);

      expect(find.text('密码不能为空'), findsOneWidget);
      expect(calls, isEmpty);
    });

    testWidgets('登录成功：账号去首尾空白、密码按原样提交，回调带出账号会话', (WidgetTester tester) async {
      final List<(String, String)> calls = <(String, String)>[];
      AccountSession? loggedIn;
      await pumpLogin(
        tester,
        ({required String username, required String password}) async {
          calls.add((username, password));
          return accountSession(username: username);
        },
        onLoggedIn: (AccountSession value) => loggedIn = value,
      );

      await enterCredentials(tester, ' admin ', ' p@ss ');
      await tapLogin(tester);

      expect(calls, <(String, String)>[('admin', ' p@ss ')]);
      expect(loggedIn?.accessToken, 'access-1');
      expect(loggedIn?.user?.username, 'admin');
      expect(find.byKey(kConsoleLoginErrorKey), findsNothing);
      expect(find.byKey(kConsoleLoginProgressKey), findsNothing);
    });

    testWidgets('401 AUTH_BAD_CREDENTIALS：提示账号或密码不正确，不回调', (WidgetTester tester) async {
      AccountSession? loggedIn;
      await pumpLogin(
        tester,
        ({required String username, required String password}) async {
          throw const ApiException(
            statusCode: 401,
            problem: ApiProblem(
              type: null,
              title: null,
              status: 401,
              code: ApiErrorCode.authBadCredentials,
              detail: null,
              instance: null,
              traceId: 'trace-login',
            ),
          );
        },
        onLoggedIn: (AccountSession value) => loggedIn = value,
      );

      await enterCredentials(tester, 'admin', 'wrong');
      await tapLogin(tester);

      expect(find.text('账号或密码不正确'), findsOneWidget);
      expect(loggedIn, isNull);
    });

    testWidgets('429 RATE_LIMITED：提示尝试过于频繁', (WidgetTester tester) async {
      await pumpLogin(tester, ({required String username, required String password}) async {
        throw const ApiException(
          statusCode: 429,
          problem: ApiProblem(
            type: null,
            title: null,
            status: 429,
            code: ApiErrorCode.rateLimited,
            detail: null,
            instance: null,
            traceId: 'trace-limit',
          ),
        );
      });

      await enterCredentials(tester, 'admin', 'p@ssw0rd');
      await tapLogin(tester);

      expect(find.textContaining('过于频繁'), findsOneWidget);
    });

    testWidgets('网络异常：显示可读错误，不冒泡', (WidgetTester tester) async {
      await pumpLogin(tester, ({required String username, required String password}) async {
        throw StateError('connection refused');
      });

      await enterCredentials(tester, 'admin', 'p@ssw0rd');
      await tapLogin(tester);

      expect(find.byKey(kConsoleLoginErrorKey), findsOneWidget);
      expect(find.textContaining('登录失败'), findsOneWidget);
    });

    testWidgets('提交期间禁用按钮并显示进度，完成后恢复', (WidgetTester tester) async {
      final Completer<AccountSession> pending = Completer<AccountSession>();
      await pumpLogin(
        tester,
        ({required String username, required String password}) => pending.future,
      );

      await enterCredentials(tester, 'admin', 'p@ssw0rd');
      await tester.tap(find.byKey(kConsoleLoginSubmitButtonKey));
      await tester.pump();

      expect(find.byKey(kConsoleLoginProgressKey), findsOneWidget);
      expect(
        tester.widget<FilledButton>(find.byKey(kConsoleLoginSubmitButtonKey)).onPressed,
        isNull,
      );

      pending.complete(accountSession());
      await tester.pumpAndSettle();

      expect(find.byKey(kConsoleLoginProgressKey), findsNothing);
      expect(
        tester.widget<FilledButton>(find.byKey(kConsoleLoginSubmitButtonKey)).onPressed,
        isNotNull,
      );
    });
  });

  group('队伍汇总总览页', () {
    testWidgets('首帧到达前提示等待；成功后展示账号、刷新次数与各队伍汇总', (WidgetTester tester) async {
      final Completer<List<TeamSummary>> pending = Completer<List<TeamSummary>>();
      await pumpSummary(tester, () => pending.future, interval: const Duration(seconds: 2));

      expect(find.text('admin（OPERATOR）'), findsOneWidget);
      expect(find.text('等待首帧…'), findsOneWidget);
      expect(find.byKey(kTeamSummaryErrorKey), findsNothing);

      pending.complete(<TeamSummary>[
        teamSummary(Team.red, total: 6, live: 4, dead: 1, offline: 1, hitsDealt: 12),
        teamSummary(Team.blue, total: 6, live: 3, dead: 2, offline: 1, hitsDealt: 9),
      ]);
      await tester.pump();
      await tester.pump();

      expect(find.textContaining('已刷新 1 次'), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(teamSummaryRowKey(Team.red))).data,
        'RED｜总数 6｜存活 4｜阵亡 1｜离线 1｜命中送出 12',
      );
      expect(
        tester.widget<Text>(find.byKey(teamSummaryRowKey(Team.blue))).data,
        'BLUE｜总数 6｜存活 3｜阵亡 2｜离线 1｜命中送出 9',
      );

      await unmount(tester);
    });

    testWidgets('可选计数缺失时显示破折号，不臆造 0', (WidgetTester tester) async {
      await pumpSummary(
        tester,
        () async => <TeamSummary>[
          teamSummary(
            Team.orange,
            total: null,
            live: null,
            dead: null,
            offline: null,
            hitsDealt: null,
          ),
        ],
      );

      expect(
        tester.widget<Text>(find.byKey(teamSummaryRowKey(Team.orange))).data,
        'ORANGE｜总数 —｜存活 —｜阵亡 —｜离线 —｜命中送出 —',
      );

      await unmount(tester);
    });

    testWidgets('空数组：提示本场没有已编成的队伍', (WidgetTester tester) async {
      await pumpSummary(tester, () async => const <TeamSummary>[]);

      expect(find.byKey(kTeamSummaryEmptyKey), findsOneWidget);
      expect(find.text('本场没有已编成的队伍'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('按注入周期轮询：每过一个周期成功计数 +1', (WidgetTester tester) async {
      int calls = 0;
      await pumpSummary(
        tester,
        () async {
          calls++;
          return <TeamSummary>[teamSummary(Team.red)];
        },
        interval: const Duration(milliseconds: 200),
      );

      expect(calls, 1);

      await tester.pump(const Duration(milliseconds: 200));
      await tester.pump();
      expect(calls, 2);
      expect(find.textContaining('已刷新 2 次'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('上一拍未回时跳过本拍，不叠加在途请求', (WidgetTester tester) async {
      int calls = 0;
      final Completer<List<TeamSummary>> pending = Completer<List<TeamSummary>>();
      await pumpSummary(
        tester,
        () {
          calls++;
          return pending.future;
        },
        interval: const Duration(milliseconds: 200),
      );

      expect(calls, 1);

      await tester.pump(const Duration(milliseconds: 200));
      await tester.pump(const Duration(milliseconds: 200));
      expect(calls, 1);

      pending.complete(<TeamSummary>[teamSummary(Team.red)]);
      await tester.pump();
      await tester.pump();
      expect(find.textContaining('已刷新 1 次'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('网络异常：显示可读错误，不冒泡、不清空上次成功数据', (WidgetTester tester) async {
      int calls = 0;
      await pumpSummary(
        tester,
        () async {
          calls++;
          if (calls == 1) {
            return <TeamSummary>[teamSummary(Team.red, live: 5)];
          }
          throw StateError('connection refused');
        },
        interval: const Duration(milliseconds: 200),
      );

      expect(
        tester.widget<Text>(find.byKey(teamSummaryRowKey(Team.red))).data,
        contains('存活 5'),
      );

      await tester.pump(const Duration(milliseconds: 200));
      await tester.pump();

      expect(find.byKey(kTeamSummaryErrorKey), findsOneWidget);
      expect(find.textContaining('拉取失败'), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(teamSummaryRowKey(Team.red))).data,
        contains('存活 5'),
      );

      await unmount(tester);
    });

    testWidgets('401 AUTH_TOKEN_EXPIRED：按契约错误码呈现，不冒泡', (WidgetTester tester) async {
      await pumpSummary(tester, () async {
        throw const ApiException(
          statusCode: 401,
          problem: ApiProblem(
            type: null,
            title: null,
            status: 401,
            code: ApiErrorCode.authTokenExpired,
            detail: null,
            instance: null,
            traceId: 'trace-expired',
          ),
        );
      });

      expect(find.byKey(kTeamSummaryErrorKey), findsOneWidget);
      expect(find.textContaining(ApiErrorCode.authTokenExpired), findsOneWidget);
      expect(find.text('等待首帧…'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('提供账号入口时点击触发回调', (WidgetTester tester) async {
      bool opened = false;
      await pumpSummary(
        tester,
        () async => const <TeamSummary>[],
        onOpenAccount: () => opened = true,
      );

      expect(find.byKey(kTeamSummaryAccountEntryKey), findsOneWidget);

      await tester.tap(find.byKey(kTeamSummaryAccountEntryKey));
      await tester.pump();

      expect(opened, isTrue);

      await unmount(tester);
    });

    testWidgets('未注入账号入口时不渲染该入口', (WidgetTester tester) async {
      await pumpSummary(tester, () async => const <TeamSummary>[]);

      expect(find.byKey(kTeamSummaryAccountEntryKey), findsNothing);

      await unmount(tester);
    });

    testWidgets('提供全体态势入口时点击触发回调', (WidgetTester tester) async {
      bool opened = false;
      await pumpSummary(
        tester,
        () async => const <TeamSummary>[],
        onOpenSituation: () => opened = true,
      );

      expect(find.byKey(kTeamSummarySituationEntryKey), findsOneWidget);

      await tester.tap(find.byKey(kTeamSummarySituationEntryKey));
      await tester.pump();

      expect(opened, isTrue);

      await unmount(tester);
    });

    testWidgets('未注入全体态势入口时不渲染该入口', (WidgetTester tester) async {
      await pumpSummary(tester, () async => const <TeamSummary>[]);

      expect(find.byKey(kTeamSummarySituationEntryKey), findsNothing);

      await unmount(tester);
    });
  });

  group('指挥端账号与会话页', () {
    testWidgets('展示当前身份、到期时刻与权限', (WidgetTester tester) async {
      await pumpAccount(
        tester,
        session: accountSession(
          username: 'duty',
          roles: const <String>['DIRECTOR'],
          permissions: const <String>['situation.read'],
        ),
      );

      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountIdentityKey)).data,
        'duty（DIRECTOR）',
      );
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountExpiryKey)).data,
        contains('令牌到期'),
      );
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountPermissionsKey)).data,
        '权限：situation.read',
      );
      expect(find.byKey(kConsoleAccountErrorKey), findsNothing);
    });

    testWidgets('已过期：只读提示已过期，不自动发起续期', (WidgetTester tester) async {
      int refreshCalls = 0;
      await pumpAccount(
        tester,
        session: accountSession(
          refreshToken: 'refresh-1',
          expiresAt: DateTime.utc(2020, 1, 1),
        ),
        refresh: (AccountSession current) async {
          refreshCalls++;
          return accountSession();
        },
      );

      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountExpiryKey)).data,
        contains('已过期'),
      );
      expect(refreshCalls, 0);
      expect(find.byKey(kConsoleAccountNoticeKey), findsNothing);
    });

    testWidgets('续期成功：会话被整体替换并给出新到期提示', (WidgetTester tester) async {
      final List<String> used = <String>[];
      await pumpAccount(
        tester,
        session: accountSession(refreshToken: 'refresh-1'),
        refresh: (AccountSession current) async {
          used.add(current.refreshToken ?? '');
          return accountSession(
            accessToken: 'access-2',
            refreshToken: 'refresh-2',
            expiresAt: DateTime.utc(2026, 10, 30, 12),
          );
        },
      );

      await tapAccountButton(tester, kConsoleAccountRefreshButtonKey);

      expect(used, <String>['refresh-1']);
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountExpiryKey)).data,
        contains('2026-10-30'),
      );
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountNoticeKey)).data,
        contains('已续期'),
      );
      expect(find.byKey(kConsoleAccountRefreshProgressKey), findsNothing);
    });

    testWidgets('续期进行中再次点击：不重复调用，按钮禁用', (WidgetTester tester) async {
      int refreshCalls = 0;
      final Completer<AccountSession> pending = Completer<AccountSession>();
      await pumpAccount(
        tester,
        session: accountSession(refreshToken: 'refresh-1'),
        refresh: (AccountSession current) {
          refreshCalls++;
          return pending.future;
        },
      );

      await tester.tap(find.byKey(kConsoleAccountRefreshButtonKey));
      await tester.pump();

      expect(find.byKey(kConsoleAccountRefreshProgressKey), findsOneWidget);
      expect(
        tester.widget<FilledButton>(find.byKey(kConsoleAccountRefreshButtonKey)).onPressed,
        isNull,
      );

      await tester.tap(find.byKey(kConsoleAccountRefreshButtonKey), warnIfMissed: false);
      await tester.pump();
      expect(refreshCalls, 1);

      pending.complete(accountSession(accessToken: 'access-2'));
      await tester.pumpAndSettle();

      expect(find.byKey(kConsoleAccountRefreshProgressKey), findsNothing);
    });

    testWidgets('会话没有刷新令牌：行内提示，不发续期请求', (WidgetTester tester) async {
      int refreshCalls = 0;
      await pumpAccount(
        tester,
        session: accountSession(),
        refresh: (AccountSession current) async {
          refreshCalls++;
          return accountSession();
        },
      );

      await tapAccountButton(tester, kConsoleAccountRefreshButtonKey);

      expect(refreshCalls, 0);
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountErrorKey)).data,
        contains('没有刷新令牌'),
      );
    });

    testWidgets('401 AUTH_TOKEN_EXPIRED：提示刷新令牌已过期', (WidgetTester tester) async {
      await pumpAccount(
        tester,
        session: accountSession(refreshToken: 'refresh-1'),
        refresh: (AccountSession current) async =>
            throw apiFailure(401, ApiErrorCode.authTokenExpired),
      );

      await tapAccountButton(tester, kConsoleAccountRefreshButtonKey);

      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountErrorKey)).data,
        '刷新令牌已过期，请重新登录',
      );
      expect(find.byKey(kConsoleAccountNoticeKey), findsNothing);
    });

    testWidgets('401 AUTH_TOKEN_INVALID：提示刷新令牌无效或已被撤销', (WidgetTester tester) async {
      await pumpAccount(
        tester,
        session: accountSession(refreshToken: 'refresh-1'),
        refresh: (AccountSession current) async =>
            throw apiFailure(401, ApiErrorCode.authTokenInvalid),
      );

      await tapAccountButton(tester, kConsoleAccountRefreshButtonKey);

      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountErrorKey)).data,
        contains('无效或已被撤销'),
      );
    });

    testWidgets('续期网络异常：显示可读错误，不冒泡', (WidgetTester tester) async {
      await pumpAccount(
        tester,
        session: accountSession(refreshToken: 'refresh-1'),
        refresh: (AccountSession current) async => throw StateError('connection refused'),
      );

      await tapAccountButton(tester, kConsoleAccountRefreshButtonKey);

      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountErrorKey)).data,
        contains('续期失败'),
      );
    });

    testWidgets('重新载入身份：展示 me 返回的账号与权限', (WidgetTester tester) async {
      final List<String> tokens = <String>[];
      await pumpAccount(
        tester,
        reload: (AccountSession current) async {
          tokens.add(current.accessToken);
          return AuthUser(
            userId: 'u-1',
            username: 'duty',
            roles: const <String>['ADMIN'],
            permissions: const <String>['plan.write'],
          );
        },
      );

      await tapAccountButton(tester, kConsoleAccountReloadButtonKey);

      expect(tokens, <String>['access-1']);
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountIdentityKey)).data,
        'duty（ADMIN）',
      );
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountPermissionsKey)).data,
        '权限：plan.write',
      );
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountNoticeKey)).data,
        contains('已重新载入身份'),
      );
    });

    testWidgets('身份重载失败：行内给出可读错误', (WidgetTester tester) async {
      await pumpAccount(
        tester,
        reload: (AccountSession current) async =>
            throw apiFailure(401, ApiErrorCode.authTokenExpired),
      );

      await tapAccountButton(tester, kConsoleAccountReloadButtonKey);

      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountErrorKey)).data,
        '身份重载失败：${ApiErrorCode.authTokenExpired}',
      );
    });

    testWidgets('退出成功：清本地会话并回登录页', (WidgetTester tester) async {
      int cleared = 0;
      int loggedOut = 0;
      await pumpAccount(
        tester,
        logout: (AccountSession current) async {},
        clearSession: () async {
          cleared++;
        },
        onLoggedOut: () => loggedOut++,
      );

      await tapAccountButton(tester, kConsoleAccountLogoutButtonKey);

      expect(cleared, 1);
      expect(loggedOut, 1);
      expect(find.byKey(kConsoleAccountErrorKey), findsNothing);
      expect(find.byKey(kConsoleAccountSignedOutKey), findsNothing);
    });

    testWidgets('退出服务端未确认：仍清本地会话，留在本页，点返回登录才导航', (WidgetTester tester) async {
      int cleared = 0;
      int loggedOut = 0;
      await pumpAccount(
        tester,
        logout: (AccountSession current) async =>
            throw apiFailure(401, ApiErrorCode.authTokenInvalid),
        clearSession: () async {
          cleared++;
        },
        onLoggedOut: () => loggedOut++,
      );

      await tapAccountButton(tester, kConsoleAccountLogoutButtonKey);

      expect(cleared, 1);
      expect(loggedOut, 0);
      expect(find.byKey(kConsoleAccountSignedOutKey), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountErrorKey)).data,
        contains('服务端未确认，已本地退出'),
      );
      expect(find.byKey(kConsoleAccountLogoutButtonKey), findsNothing);

      await tapAccountButton(tester, kConsoleAccountReturnButtonKey);

      expect(loggedOut, 1);
    });

    testWidgets('退出网络异常：同样清本地会话并留在本页', (WidgetTester tester) async {
      int cleared = 0;
      int loggedOut = 0;
      await pumpAccount(
        tester,
        logout: (AccountSession current) async => throw StateError('connection refused'),
        clearSession: () async {
          cleared++;
        },
        onLoggedOut: () => loggedOut++,
      );

      await tapAccountButton(tester, kConsoleAccountLogoutButtonKey);

      expect(cleared, 1);
      expect(loggedOut, 0);
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountErrorKey)).data,
        contains('服务端未确认，已本地退出'),
      );
    });
  });

  group('指挥端认证接线（RES-D）', () {
    /// 契约 `TokenPair` 形状的登录/刷新响应体。
    Map<String, dynamic> pairBody(String accessToken, String refreshToken) => <String, dynamic>{
          'accessToken': accessToken,
          'expiresIn': 900,
          'refreshToken': refreshToken,
          'user': <String, dynamic>{
            'userId': 'u-1',
            'username': 'admin',
            'roles': <String>['OPERATOR'],
          },
        };

    /// 走完「连接配置 → 登录」并停在队伍汇总页（全程只打脚本化传输桩）。
    Future<void> signIn(WidgetTester tester, ScriptedTransport transport) async {
      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);
    }

    testWidgets('登录后：队伍汇总按登录令牌带 Bearer', (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(pairBody('access-1', 'refresh-1'));
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[
              <String, dynamic>{'team': 'RED', 'total': 6, 'live': 4, 'dead': 1},
            ]);
        }
        fail('未预期的请求：${call.key}');
      };

      await signIn(tester, transport);

      expect(transport.sequence, <String>[
        'POST $kApiPrefix/auth/login',
        'GET $kApiPrefix/situation/teams/summary',
      ]);
      // 登录不带令牌；登录后的域请求带登录拿到的 accessToken。
      expect(transport.calls.first.authorization, isNull);
      expect(transport.calls.last.authorization, 'Bearer access-1');
      expect((await sessionStore.readAccount())!.accessToken, 'access-1');
      expect(find.byType(TeamSummaryPage), findsOneWidget);
    });

    testWidgets('队伍汇总遇 401 到期：自动续期一次并按新令牌重试（页面无感）', (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(pairBody('access-1', 'refresh-1'));
          case 'GET $kApiPrefix/situation/teams/summary':
            if (call.authorization == 'Bearer access-1') {
              throw apiFailure(401, ApiErrorCode.authTokenExpired);
            }
            return jsonResponse(<Object>[]);
          case 'POST $kApiPrefix/auth/refresh':
            // 滚动刷新：回新令牌对，**不回** `user`。
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-2',
              'expiresIn': 900,
              'refreshToken': 'refresh-2',
            });
        }
        fail('未预期的请求：${call.key}');
      };

      await signIn(tester, transport);

      expect(transport.sequence, <String>[
        'POST $kApiPrefix/auth/login',
        'GET $kApiPrefix/situation/teams/summary',
        'POST $kApiPrefix/auth/refresh',
        'GET $kApiPrefix/situation/teams/summary',
      ]);
      expect(transport.calls.last.authorization, 'Bearer access-2');
      // 续期结果写回会话存储，且保留登录时的身份快照。
      final AccountSession stored = (await sessionStore.readAccount())!;
      expect(stored.accessToken, 'access-2');
      expect(stored.refreshToken, 'refresh-2');
      expect(stored.user!.username, 'admin');
      // 页面无感：401 已在传输层被消化，汇总页不显示错误。
      expect(find.byKey(kTeamSummaryErrorKey), findsNothing);
      expect(find.byKey(kTeamSummaryEmptyKey), findsOneWidget);
    });

    testWidgets('账号页续期按钮：委托传输换新令牌并写回会话存储', (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(pairBody('access-1', 'refresh-1'));
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'POST $kApiPrefix/auth/refresh':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-2',
              'expiresIn': 1800,
              'refreshToken': 'refresh-2',
            });
        }
        fail('未预期的请求：${call.key}');
      };

      await signIn(tester, transport);
      await tapAccountButton(tester, kTeamSummaryAccountEntryKey);
      await tapAccountButton(tester, kConsoleAccountRefreshButtonKey);

      // 汇总页 2s 轮询期间会再拉一次 summary，与本次续期无关：只校验
      // 「登录 → 首次汇总 → 续期」这条子序列，并单独断言续期**只打一次**
      // （滚动刷新不可重放）。
      expect(
        transport.sequence,
        containsAllInOrder(<String>[
          'POST $kApiPrefix/auth/login',
          'GET $kApiPrefix/situation/teams/summary',
          'POST $kApiPrefix/auth/refresh',
        ]),
      );
      expect(
        transport.sequence.where((String key) => key == 'POST $kApiPrefix/auth/refresh'),
        hasLength(1),
      );
      // 刷新提交的是登录拿到的刷新令牌。
      final ScriptedCall refreshCall = transport.calls.lastWhere(
        (ScriptedCall call) => call.key == 'POST $kApiPrefix/auth/refresh',
      );
      final Map<String, dynamic> body = refreshCall.body! as Map<String, dynamic>;
      expect(body['refreshToken'], 'refresh-1');
      final AccountSession stored = (await sessionStore.readAccount())!;
      expect(stored.accessToken, 'access-2');
      expect(stored.refreshToken, 'refresh-2');
      expect(stored.user!.username, 'admin');
      expect(
        tester.widget<Text>(find.byKey(kConsoleAccountNoticeKey)).data,
        contains('已续期'),
      );
    });

    testWidgets('会话终结（刷新被 401 拒绝）：清本地会话并弹回登录页', (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(pairBody('access-1', 'refresh-1'));
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'POST $kApiPrefix/auth/refresh':
            throw apiFailure(401, ApiErrorCode.authTokenInvalid);
        }
        fail('未预期的请求：${call.key}');
      };

      await signIn(tester, transport);
      await tapAccountButton(tester, kTeamSummaryAccountEntryKey);
      expect(find.byType(AccountPage), findsOneWidget);

      await tapAccountButton(tester, kConsoleAccountRefreshButtonKey);

      // 会话终结：本地会话被清，页面栈一路弹回登录页（账号页与汇总页都不再在栈上）。
      expect(await sessionStore.readAccount(), isNull);
      expect(find.byType(AccountPage), findsNothing);
      expect(find.byType(TeamSummaryPage), findsNothing);
      expect(find.byType(ConsoleLoginPage), findsOneWidget);
    });
  });

  /// 段 D 后半：指挥端「全体态势」页接 `SituationFeed`（WS + 10s 兜底 Poll 双通道）。
  ///
  /// 用例全部跑在 `testWidgets` 的**假时钟**上，注入假 `fetch` 与假连接器——**不建真实连接**。
  /// 判据是三条：WS 不得替代 HTTP 兜底轮询、两条通道同源归并让旧帧不覆盖新状态、
  /// 缓存随页面生灭（不跨会话泄漏）。
  group('指挥端全体态势页（RES-D 后半）', () {
    testWidgets('首帧前提示等待；首帧到达后展示账号、通道状态与各单元', (WidgetTester tester) async {
      final Completer<SituationEnvelope> pending = Completer<SituationEnvelope>();
      final TestSituationConnector connector = TestSituationConnector(silent: true);
      await pumpOverall(
        tester,
        fetch: ({int? sinceRevision}) => pending.future,
        connect: connector.call,
      );

      expect(
        tester.widget<Text>(find.byKey(kOverallSituationAccountKey)).data,
        'admin（OPERATOR）',
      );
      expect(find.byKey(kOverallSituationWaitingKey), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(kOverallSituationChannelKey)).data,
        contains('WS 未连'),
      );

      pending.complete(fullEnvelope(
        revision: 10,
        units: <UnitState>[
          unitOf(14, hp: 88),
          unitOf(21, status: VestStatus.die, hp: 0),
        ],
      ));
      await tester.pump();
      await tester.pump();

      expect(
        tester.widget<Text>(find.byKey(overallSituationUnitRowKey(14))).data,
        '#14 LIVE｜HP 88',
      );
      expect(
        tester.widget<Text>(find.byKey(overallSituationUnitRowKey(21))).data,
        '#21 DIE｜HP 0',
      );
      expect(find.byKey(kOverallSituationWaitingKey), findsNothing);
      expect(
        tester.widget<Text>(find.byKey(kOverallSituationCountsKey)).data,
        contains('单元 2'),
      );

      await unmount(tester);
    });

    testWidgets('WS 连上：通道切为实时，且收到的 WS 帧同源归并进单元列表', (WidgetTester tester) async {
      final TestSituationConnector connector = TestSituationConnector();
      await pumpOverall(
        tester,
        fetch: ({int? sinceRevision}) async =>
            fullEnvelope(revision: 10, units: <UnitState>[unitOf(14)]),
        connect: connector.call,
      );

      expect(
        tester.widget<Text>(find.byKey(kOverallSituationChannelKey)).data,
        contains('WS 已连'),
      );
      expect(find.byKey(overallSituationUnitRowKey(14)), findsOneWidget);
      // 首个连接未对齐 → 不带 sinceRevision 取全量。
      expect(connector.sinceRevisions, <int?>[null]);

      connector.last.emit(jsonEncode(<String, dynamic>{
        'epoch': 'e-1',
        'revision': 11,
        'full': false,
        'running': true,
        'serverTime': '2026-10-28T12:00:00Z',
        'changes': <dynamic>[
          <String, dynamic>{'vestNum': 21, 'status': 'LIVE', 'revision': 11},
        ],
      }));
      await tester.pump();
      await tester.pump();

      expect(find.byKey(overallSituationUnitRowKey(21)), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(kOverallSituationCountsKey)).data,
        contains('单元 2'),
      );

      await unmount(tester);
    });

    testWidgets('重新同步按钮：WS 已连时上行 RESYNC 帧并带本地 revision', (WidgetTester tester) async {
      final TestSituationConnector connector = TestSituationConnector();
      await pumpOverall(
        tester,
        fetch: ({int? sinceRevision}) async =>
            fullEnvelope(revision: 10, units: <UnitState>[unitOf(14)]),
        connect: connector.call,
      );

      await tester.tap(find.byKey(kOverallSituationResyncButtonKey));
      await tester.pump();

      expect(
        connector.last.sent.single,
        jsonEncode(const <String, Object?>{'action': 'RESYNC', 'sinceRevision': 10}),
      );

      await unmount(tester);
    });

    testWidgets('拉取失败：显示可读错误，保留陈旧单元数据', (WidgetTester tester) async {
      int calls = 0;
      final TestSituationConnector connector = TestSituationConnector(silent: true);
      await pumpOverall(
        tester,
        fetch: ({int? sinceRevision}) async {
          calls++;
          if (calls == 1) {
            return fullEnvelope(revision: 10, units: <UnitState>[unitOf(14, hp: 77)]);
          }
          throw StateError('connection refused');
        },
        connect: connector.call,
      );

      expect(
        tester.widget<Text>(find.byKey(overallSituationUnitRowKey(14))).data,
        contains('77'),
      );

      // WS 未连 → 按 2s 档轮询；下一拍失败。
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();

      expect(find.byKey(kOverallSituationErrorKey), findsOneWidget);
      expect(find.textContaining('拉取失败'), findsOneWidget);
      // 失败不得抹掉上一次成功的数据（保陈旧，不空屏）。
      expect(
        tester.widget<Text>(find.byKey(overallSituationUnitRowKey(14))).data,
        contains('77'),
      );

      await unmount(tester);
    });

    testWidgets('缓存隔离：重开页面为新会话，首拉仍取全量且不残留上一会话的单元', (WidgetTester tester) async {
      final List<int?> sinceRevisions = <int?>[];
      final TestSituationConnector connector = TestSituationConnector(silent: true);
      await pumpOverall(
        tester,
        fetch: ({int? sinceRevision}) async {
          sinceRevisions.add(sinceRevision);
          return fullEnvelope(revision: 10, units: <UnitState>[unitOf(14)]);
        },
        connect: connector.call,
      );

      expect(sinceRevisions, <int?>[null]);
      expect(find.byKey(overallSituationUnitRowKey(14)), findsOneWidget);

      await unmount(tester);

      // 重开一页：页面自建新的 feed / 缓存——不得残留上一会话的单元，首个请求仍为全量。
      sinceRevisions.clear();
      final Completer<SituationEnvelope> pending = Completer<SituationEnvelope>();
      await pumpOverall(
        tester,
        fetch: ({int? sinceRevision}) {
          sinceRevisions.add(sinceRevision);
          return pending.future;
        },
        connect: connector.call,
      );

      expect(sinceRevisions, <int?>[null]);
      expect(find.byKey(overallSituationUnitRowKey(14)), findsNothing);
      expect(find.byKey(kOverallSituationWaitingKey), findsOneWidget);

      await unmount(tester);
    });
  });

  /// 段 47：单兵指挥指令页——从「全体态势」页所选单元行进入，目标编号不手输。
  ///
  /// 六个写端点全部是注入的闭包桩（**不发真实请求**）。判据围绕三条契约口径：
  /// 「受理 ≠ 下发」（`accepted=false` 只提示未下发）、覆盖配置的**只发非空键**语义、
  /// 以及写操作的**单飞去重**。
  group('指挥指令页（段 47）', () {
    testWidgets('目标来自所选单元行：判死下发后显示受理回执（含下发字节）', (WidgetTester tester) async {
      int kills = 0;
      await pumpCommand(tester, kill: () async {
        kills++;
        return ackOf(command: 'DIE', downlinkHex: '0B01');
      });

      expect(tester.widget<Text>(find.byKey(kCommandTargetKey)).data, '目标 #14｜admin（OPERATOR）');
      expect(find.text('尚未下发指令'), findsOneWidget);

      await tester.tap(find.byKey(kCommandKillButtonKey));
      await tester.pump();

      expect(kills, 1);
      expect(
        tester.widget<Text>(find.byKey(kCommandAckKey)).data,
        'DIE → #14｜已下发：0B01',
      );
    });

    testWidgets('accepted=false：提示「未下发」而非报错（HTTP 200 只表示已受理）', (WidgetTester tester) async {
      await pumpCommand(tester, kill: () async => ackOf(command: 'DIE', accepted: false));

      await tester.tap(find.byKey(kCommandKillButtonKey));
      await tester.pump();

      expect(
        tester.widget<Text>(find.byKey(kCommandAckKey)).data,
        contains('未下发'),
      );
      expect(find.byKey(kCommandErrorKey), findsNothing);
    });

    testWidgets('契约错误码映射为现场文案（演习未运行）', (WidgetTester tester) async {
      await pumpCommand(
        tester,
        kill: () async => throw apiFailure(409, ApiErrorCode.exerciseNotRunning),
      );

      await tester.tap(find.byKey(kCommandKillButtonKey));
      await tester.pump();

      expect(
        tester.widget<Text>(find.byKey(kCommandErrorKey)).data,
        contains('当前没有进行中的演习'),
      );
      expect(find.byKey(kCommandAckKey), findsNothing);
    });

    testWidgets('单兵充弹：非法弹量不发请求，合法则带契约请求体', (WidgetTester tester) async {
      final List<ReloadRequest> sent = <ReloadRequest>[];
      await pumpCommand(tester, kill: unexpectedCommand, reload: (ReloadRequest request) async {
        sent.add(request);
        return ackOf(command: 'RELOAD', downlinkHex: '0B02');
      });

      await tester.enterText(find.byKey(kCommandReloadAmmoFieldKey), 'abc');
      await tester.tap(find.byKey(kCommandReloadButtonKey));
      await tester.pump();

      expect(sent, isEmpty);
      expect(
        tester.widget<Text>(find.byKey(kCommandErrorKey)).data,
        contains('0..65535'),
      );

      await tester.enterText(find.byKey(kCommandReloadAmmoFieldKey), '60000');
      await tester.tap(find.byKey(kCommandReloadButtonKey));
      await tester.pump();

      expect(sent.single.ammo, 60000);
      expect(sent.single.weapon, WeaponSlot.primary);
      expect(
        tester.widget<Text>(find.byKey(kCommandAckKey)).data,
        contains('RELOAD → #14'),
      );
    });

    testWidgets('覆盖配置：全空不发请求；只填 HP 则请求体只含 hp 键', (WidgetTester tester) async {
      final List<VestConfigRequest> sent = <VestConfigRequest>[];
      await pumpCommand(tester, kill: unexpectedCommand,
          setConfig: (VestConfigRequest request) async {
        sent.add(request);
        return ackOf(command: 'CONFIG', downlinkHex: '0E01');
      });

      await tester.tap(find.byKey(kCommandConfigSaveButtonKey));
      await tester.pump();

      expect(sent, isEmpty);
      expect(
        tester.widget<Text>(find.byKey(kCommandErrorKey)).data,
        contains('至少'),
      );

      await tester.enterText(find.byKey(kCommandConfigHpFieldKey), '200');
      await tester.tap(find.byKey(kCommandConfigSaveButtonKey));
      await tester.pump();

      // 覆盖语义：未填字段不得下发（`null` 不是「置空」）。
      expect(sent.single.toJson(), <String, Object?>{'hp': 200});
      expect(
        tester.widget<Text>(find.byKey(kCommandAckKey)).data,
        contains('CONFIG → #14'),
      );
    });

    testWidgets('下发期间单飞去重：连点只发一次请求', (WidgetTester tester) async {
      final Completer<CommandAck> pending = Completer<CommandAck>();
      int kills = 0;
      await pumpCommand(tester, kill: () {
        kills++;
        return pending.future;
      });

      await tester.tap(find.byKey(kCommandKillButtonKey));
      await tester.pump();
      await tester.tap(find.byKey(kCommandKillButtonKey));
      await tester.pump();

      expect(kills, 1);

      pending.complete(ackOf(command: 'DIE'));
      await tester.pump();

      expect(
        tester.widget<Text>(find.byKey(kCommandAckKey)).data,
        contains('DIE → #14'),
      );
    });
  });

  /// 段 47：「全体态势」页的两处指令入口——单元行 → 单兵指令页；AppBar → 全体充弹（广播）。
  ///
  /// 两个端口都是**可选注入**：不注入时页面与既有只读用例渲染完全一致（回归在此固定）。
  group('指挥端全体态势页·指令入口（段 47）', () {
    Future<void> pumpWithUnits(
      WidgetTester tester, {
      void Function(int vestNum)? onCommand,
      Future<CommandAck> Function(ReloadRequest request)? reloadAll,
    }) async {
      final TestSituationConnector connector = TestSituationConnector(silent: true);
      await pumpOverall(
        tester,
        fetch: ({int? sinceRevision}) async => fullEnvelope(
          revision: 10,
          units: <UnitState>[unitOf(14), unitOf(21)],
        ),
        connect: connector.call,
        onCommand: onCommand,
        reloadAll: reloadAll,
      );
      await tester.pump();
    }

    testWidgets('注入 onCommand：单元行可点，回调目标编号', (WidgetTester tester) async {
      final List<int> tapped = <int>[];
      await pumpWithUnits(tester, onCommand: tapped.add);

      // 未注入 reloadAll → AppBar 不渲染广播入口。
      expect(find.byKey(kOverallSituationReloadAllButtonKey), findsNothing);

      await tester.tap(find.byKey(overallSituationUnitTapKey(21)));
      await tester.pump();

      expect(tapped, <int>[21]);
      // 行文本键不变（点击只是包了一层 InkWell）。
      expect(
        tester.widget<Text>(find.byKey(overallSituationUnitRowKey(21))).data,
        '#21 LIVE｜HP —',
      );

      await unmount(tester);
    });

    testWidgets('未注入 onCommand：行仍为只读文本，无点击键', (WidgetTester tester) async {
      await pumpWithUnits(tester);

      expect(find.byKey(overallSituationUnitRowKey(14)), findsOneWidget);
      expect(find.byKey(overallSituationUnitTapKey(14)), findsNothing);

      await unmount(tester);
    });

    testWidgets('注入 reloadAll：AppBar 入口 → 对话框 → 广播回执提示为「全体」', (WidgetTester tester) async {
      final List<ReloadRequest> sent = <ReloadRequest>[];
      await pumpWithUnits(tester, reloadAll: (ReloadRequest request) async {
        sent.add(request);
        return ackOf(vestNum: 0, command: 'RELOAD', downlinkHex: '0B03');
      });

      await tester.tap(find.byKey(kOverallSituationReloadAllButtonKey));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      await tester.enterText(find.byKey(kReloadAllAmmoFieldKey), '50');
      await tester.tap(find.byKey(kReloadAllConfirmKey));
      await tester.pump();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(sent.single.ammo, 50);
      expect(sent.single.weapon, WeaponSlot.primary);
      // 广播回执 vestNum 固定为 0 → 文案显示「全体」。
      expect(find.text('RELOAD → 全体｜已下发：0B03'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('全体充弹对话框：非法弹量不关闭且不发请求', (WidgetTester tester) async {
      final List<ReloadRequest> sent = <ReloadRequest>[];
      await pumpWithUnits(tester, reloadAll: (ReloadRequest request) async {
        sent.add(request);
        return ackOf(vestNum: 0, command: 'RELOAD');
      });

      await tester.tap(find.byKey(kOverallSituationReloadAllButtonKey));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      await tester.enterText(find.byKey(kReloadAllAmmoFieldKey), 'x');
      await tester.tap(find.byKey(kReloadAllConfirmKey));
      await tester.pump();

      expect(sent, isEmpty);
      expect(find.byKey(kReloadAllErrorKey), findsOneWidget);
      expect(find.byKey(kReloadAllConfirmKey), findsOneWidget);

      await tester.tap(find.byKey(kReloadAllCancelKey));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      await unmount(tester);
    });
  });

  /// 段 49：想定管理页——exercise 域写端点的指挥端接线（列表 / 新建 / 编辑 / 删除）。
  ///
  /// 全部注入闭包桩，**不发真实请求**。判据两条：
  /// 序列化口径（`PUT` 是整体替换，四键全发）与写后重拉列表。
  group('想定管理页（段 49）', () {
    testWidgets('加载想定列表：展示账号与每行契约字段；空列表显示空态', (WidgetTester tester) async {
      final List<String?> keywords = <String?>[];
      await pumpPlans(tester, list: ({String? keyword}) async {
        keywords.add(keyword);
        return planPageOf(<Plan>[]);
      });
      await tester.pumpAndSettle();

      // 首帧不带关键字（服务端「列出全部」口径）。
      expect(keywords, <String?>[null]);
      expect(tester.widget<Text>(find.byKey(kPlanManagerAccountKey)).data, 'admin（OPERATOR）');
      expect(find.byKey(kPlanManagerEmptyKey), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('行文本取契约字面量；搜索按关键字重拉（两侧空白被裁剪）', (WidgetTester tester) async {
      final List<String?> keywords = <String?>[];
      await pumpPlans(tester, list: ({String? keyword}) async {
        keywords.add(keyword);
        return planPageOf(<Plan>[
          planOf(
            'p-1',
            name: '红方突袭',
            mapId: 'm-1',
            battleMode: BattleMode.battle,
            rosterCount: 3,
          ),
        ]);
      });
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(planManagerRowKey('p-1'))).data,
        '红方突袭｜模式 BATTLE｜底图 m-1｜编成 3',
      );

      await tester.enterText(find.byKey(kPlanManagerKeywordFieldKey), ' 红方 ');
      await tester.tap(find.byKey(kPlanManagerSearchButtonKey));
      await tester.pumpAndSettle();

      expect(keywords, <String?>[null, '红方']);

      await unmount(tester);
    });

    testWidgets('新建想定：提交整体替换四键，空底图 / 备注置 null', (WidgetTester tester) async {
      final List<PlanUpsertRequest> created = <PlanUpsertRequest>[];
      await pumpPlans(tester, create: (PlanUpsertRequest request) async {
        created.add(request);
        return planOf('p-9', name: request.name);
      });
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kPlanManagerCreateButtonKey));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(kPlanFormNameFieldKey), ' 新想定 ');
      await tester.tap(find.byKey(kPlanFormSaveKey));
      await tester.pumpAndSettle();

      expect(created.single.toJson(), <String, Object?>{
        'name': '新想定',
        'mapId': null,
        'battleMode': 'TRAIN',
        'remark': null,
      });
      expect(find.text('已新建想定「新想定」'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('编辑想定：以服务端快照预填，仅改名字仍提交全部四键（整体替换回归）',
        (WidgetTester tester) async {
      final List<PlanUpsertRequest> updated = <PlanUpsertRequest>[];
      await pumpPlans(
        tester,
        list: ({String? keyword}) async => planPageOf(<Plan>[
          planOf(
            'p-1',
            name: '旧名',
            mapId: 'm-1',
            battleMode: BattleMode.battle,
            remark: '备注',
          ),
        ]),
        update: (String planId, PlanUpsertRequest request) async {
          updated.add(request);
          return planOf(planId, name: request.name);
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(planManagerEditKey('p-1')));
      await tester.pumpAndSettle();

      // 快照预填：不动的字段必须原样出现在表单里，否则整体替换会误伤它们。
      expect(
        tester.widget<TextField>(find.byKey(kPlanFormNameFieldKey)).controller!.text,
        '旧名',
      );
      expect(
        tester.widget<TextField>(find.byKey(kPlanFormMapIdFieldKey)).controller!.text,
        'm-1',
      );
      expect(
        tester.widget<TextField>(find.byKey(kPlanFormRemarkFieldKey)).controller!.text,
        '备注',
      );

      await tester.enterText(find.byKey(kPlanFormNameFieldKey), '新名');
      await tester.tap(find.byKey(kPlanFormSaveKey));
      await tester.pumpAndSettle();

      expect(updated.single.toJson(), <String, Object?>{
        'name': '新名',
        'mapId': 'm-1',
        'battleMode': 'BATTLE',
        'remark': '备注',
      });

      await unmount(tester);
    });

    testWidgets('想定名非法：不发请求、对话框保留并显示行内错误', (WidgetTester tester) async {
      await pumpPlans(tester);
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kPlanManagerCreateButtonKey));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(kPlanFormNameFieldKey), '   ');
      await tester.tap(find.byKey(kPlanFormSaveKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kPlanFormErrorKey), findsOneWidget);
      expect(find.byKey(kPlanFormSaveKey), findsOneWidget);

      await tester.tap(find.byKey(kPlanFormCancelKey));
      await tester.pumpAndSettle();

      await unmount(tester);
    });

    testWidgets('删除想定：确认后调用删除端口并重拉列表', (WidgetTester tester) async {
      final List<String> removed = <String>[];
      int listCalls = 0;
      await pumpPlans(
        tester,
        list: ({String? keyword}) async {
          listCalls++;
          return planPageOf(<Plan>[planOf('p-1', name: '红方突袭')]);
        },
        remove: (String planId) async => removed.add(planId),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(planManagerDeleteKey('p-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kPlanDeleteConfirmKey));
      await tester.pumpAndSettle();

      expect(removed, <String>['p-1']);
      // 首帧一次 + 删除后重拉一次。
      expect(listCalls, 2);
      expect(find.text('已删除想定「红方突袭」'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('契约错误码映射为现场文案（想定被引用）', (WidgetTester tester) async {
      await pumpPlans(
        tester,
        list: ({String? keyword}) async => planPageOf(<Plan>[planOf('p-1', name: '红方突袭')]),
        remove: (String planId) async => throw apiFailure(409, ApiErrorCode.planInUse),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(planManagerDeleteKey('p-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kPlanDeleteConfirmKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kPlanManagerErrorKey)).data,
        contains('正在演习中'),
      );

      await unmount(tester);
    });
  });

  /// 段 49：想定管理页接线——从队伍汇总页进入，`ExerciseApi` 挂同一条已认证传输。
  group('指挥端想定管理接线（段 49）', () {
    Future<void> signIn(WidgetTester tester, ScriptedTransport transport) async {
      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);
    }

    testWidgets('队伍汇总页「想定」入口：进入想定管理页并按登录令牌拉列表',
        (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-1',
              'expiresIn': 900,
              'refreshToken': 'refresh-1',
              'user': <String, dynamic>{
                'userId': 'u-1',
                'username': 'admin',
                'roles': <String>['OPERATOR'],
              },
            });
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'GET $kApiPrefix/plans':
            return jsonResponse(<String, dynamic>{
              'items': <Object>[
                <String, dynamic>{
                  'planId': 'p-1',
                  'name': '红方突袭',
                  'createdAt': '2026-10-29T00:00:00Z',
                  'battleMode': 'BATTLE',
                },
              ],
              'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
            });
        }
        fail('未预期的请求：${call.key}');
      };

      await signIn(tester, transport);
      await tester.tap(find.byKey(kTeamSummaryPlansEntryKey));
      await tester.pumpAndSettle();

      expect(find.byType(PlanManagerPage), findsOneWidget);
      expect(
        transport.sequence,
        containsAllInOrder(<String>[
          'POST $kApiPrefix/auth/login',
          'GET $kApiPrefix/situation/teams/summary',
          'GET $kApiPrefix/plans',
        ]),
      );
      final ScriptedCall listCall = transport.calls
          .lastWhere((ScriptedCall call) => call.key == 'GET $kApiPrefix/plans');
      expect(listCall.authorization, 'Bearer access-1');
      expect(
        tester.widget<Text>(find.byKey(planManagerRowKey('p-1'))).data,
        contains('红方突袭'),
      );

      await unmount(tester);
    });

    testWidgets('未注入入口：队伍汇总页不渲染「想定」入口', (WidgetTester tester) async {
      await pumpSummary(tester, () async => <TeamSummary>[]);

      expect(find.byKey(kTeamSummaryPlansEntryKey), findsNothing);

      await unmount(tester);
    });
  });

  /// 段 50：编成编辑页——exercise 域 `roster` 读写端点的指挥端接线。
  ///
  /// 全部注入闭包桩，**不发真实请求**。判据三条：
  /// 就地编辑本地清单、条目序列化口径（只发非 null 键）、保存即整体替换。
  group('编成编辑页（段 50）', () {
    testWidgets('加载编成：展示账号 / 想定名；空编成显示空态', (WidgetTester tester) async {
      await pumpRoster(tester, load: () async => rosterOf(<RosterEntry>[]));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kRosterEditorAccountKey)).data,
        'admin（OPERATOR）',
      );
      expect(tester.widget<Text>(find.byKey(kRosterEditorPlanKey)).data, '想定：红方突袭');
      expect(find.byKey(kRosterEditorEmptyKey), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('行文本取契约字面量，缺失字段显示破折号', (WidgetTester tester) async {
      await pumpRoster(
        tester,
        load: () async => rosterOf(<RosterEntry>[
          rosterEntryOf(
            7,
            team: Team.blue,
            name: '张三',
            hp: 80,
            ammo1: 30,
            ammo2: 20,
            mode: VestMode.manual,
          ),
          rosterEntryOf(3),
        ]),
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(rosterEditorRowKey(7))).data,
        '#7｜BLUE｜张三｜HP 80｜弹 30/20｜MANUAL',
      );
      expect(
        tester.widget<Text>(find.byKey(rosterEditorRowKey(3))).data,
        '#3｜RED｜—｜HP —｜弹 —/—｜—',
      );

      await unmount(tester);
    });

    testWidgets('新增条目并保存：整体替换提交 planId 与全部条目，空字段不发该键',
        (WidgetTester tester) async {
      final List<Roster> saved = <Roster>[];
      await pumpRoster(tester, save: (Roster roster) async {
        saved.add(roster);
        return roster;
      });
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kRosterEditorAddButtonKey));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(kRosterEntryVestNumKey), '7');
      await tester.enterText(find.byKey(kRosterEntryNameKey), ' 张三 ');
      await tester.tap(find.byKey(kRosterEntrySaveKey));
      await tester.pumpAndSettle();

      expect(find.byKey(rosterEditorRowKey(7)), findsOneWidget);

      await tester.tap(find.byKey(kRosterEditorSaveButtonKey));
      await tester.pumpAndSettle();

      expect(saved.single.toJson(), <String, Object?>{
        'planId': 'p-1',
        'entries': <Object>[
          <String, Object?>{'vestNum': 7, 'team': 'RED', 'name': '张三'},
        ],
      });
      expect(find.text('已保存编成（1 名成员）'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('编辑条目：以服务端快照预填，改生命值后保存', (WidgetTester tester) async {
      final List<Roster> saved = <Roster>[];
      await pumpRoster(
        tester,
        load: () async => rosterOf(<RosterEntry>[
          rosterEntryOf(
            7,
            team: Team.blue,
            name: '张三',
            hp: 80,
            ammo1: 30,
            ammo2: 20,
            mode: VestMode.manual,
          ),
        ]),
        save: (Roster roster) async {
          saved.add(roster);
          return roster;
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(rosterEditorEditKey(7)));
      await tester.pumpAndSettle();

      // 快照预填：不动的字段必须原样出现在表单里，否则整体替换会误伤它们。
      expect(
        tester.widget<TextField>(find.byKey(kRosterEntryVestNumKey)).controller!.text,
        '7',
      );
      expect(
        tester.widget<TextField>(find.byKey(kRosterEntryHpKey)).controller!.text,
        '80',
      );

      await tester.enterText(find.byKey(kRosterEntryHpKey), '50');
      await tester.tap(find.byKey(kRosterEntrySaveKey));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kRosterEditorSaveButtonKey));
      await tester.pumpAndSettle();

      expect(saved.single.entries.single.hp, 50);
      expect(saved.single.entries.single.team, Team.blue);
      expect(saved.single.entries.single.mode, VestMode.manual);

      await unmount(tester);
    });

    testWidgets('马甲编号非法：不发请求、对话框保留并显示行内错误', (WidgetTester tester) async {
      await pumpRoster(tester);
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kRosterEditorAddButtonKey));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(kRosterEntryVestNumKey), 'abc');
      await tester.tap(find.byKey(kRosterEntrySaveKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kRosterEntryErrorKey), findsOneWidget);
      expect(find.byKey(kRosterEntrySaveKey), findsOneWidget);

      await tester.tap(find.byKey(kRosterEntryCancelKey));
      await tester.pumpAndSettle();
      expect(find.byKey(kRosterEditorEmptyKey), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('重复马甲编号：行内错误且不提交', (WidgetTester tester) async {
      await pumpRoster(
        tester,
        load: () async => rosterOf(<RosterEntry>[rosterEntryOf(7)]),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kRosterEditorAddButtonKey));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(kRosterEntryVestNumKey), '7');
      await tester.tap(find.byKey(kRosterEntrySaveKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kRosterEntryErrorKey), findsOneWidget);
      expect(find.textContaining('已在编成中'), findsOneWidget);

      await tester.tap(find.byKey(kRosterEntryCancelKey));
      await tester.pumpAndSettle();
      await unmount(tester);
    });

    testWidgets('移除条目：确认后从本地清单移除，保存提交剩余条目', (WidgetTester tester) async {
      final List<Roster> saved = <Roster>[];
      await pumpRoster(
        tester,
        load: () async => rosterOf(<RosterEntry>[rosterEntryOf(7), rosterEntryOf(8)]),
        save: (Roster roster) async {
          saved.add(roster);
          return roster;
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(rosterEditorRemoveKey(7)));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kRosterRemoveConfirmKey));
      await tester.pumpAndSettle();

      expect(find.byKey(rosterEditorRowKey(7)), findsNothing);
      expect(find.byKey(rosterEditorRowKey(8)), findsOneWidget);

      await tester.tap(find.byKey(kRosterEditorSaveButtonKey));
      await tester.pumpAndSettle();

      expect(saved.single.entries.single.vestNum, 8);

      await unmount(tester);
    });

    testWidgets('契约错误码映射为现场文案（编成校验失败）', (WidgetTester tester) async {
      await pumpRoster(
        tester,
        save: (Roster roster) async => throw apiFailure(400, ApiErrorCode.validationFailed),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kRosterEditorSaveButtonKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kRosterEditorErrorKey)).data,
        contains('编成校验失败'),
      );

      await unmount(tester);
    });
  });

  /// 段 50：编成编辑接线——从想定管理页行内「编成」进入，`ExerciseApi` 挂同一条已认证传输。
  group('指挥端编成编辑接线（段 50）', () {
    testWidgets('想定行「编成」入口：进入编成编辑页并按登录令牌拉编成',
        (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-1',
              'expiresIn': 900,
              'refreshToken': 'refresh-1',
              'user': <String, dynamic>{
                'userId': 'u-1',
                'username': 'admin',
                'roles': <String>['OPERATOR'],
              },
            });
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'GET $kApiPrefix/plans':
            return jsonResponse(<String, dynamic>{
              'items': <Object>[
                <String, dynamic>{
                  'planId': 'p-1',
                  'name': '红方突袭',
                  'createdAt': '2026-10-29T00:00:00Z',
                },
              ],
              'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
            });
          case 'GET $kApiPrefix/plans/p-1/roster':
            return jsonResponse(<String, dynamic>{
              'planId': 'p-1',
              'entries': <Object>[
                <String, dynamic>{'vestNum': 7, 'team': 'BLUE'},
              ],
            });
        }
        fail('未预期的请求：${call.key}');
      };

      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);

      await tester.tap(find.byKey(kTeamSummaryPlansEntryKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(planManagerRosterKey('p-1')));
      await tester.pumpAndSettle();

      expect(find.byType(RosterEditorPage), findsOneWidget);
      final ScriptedCall rosterCall = transport.calls.lastWhere(
        (ScriptedCall call) => call.key == 'GET $kApiPrefix/plans/p-1/roster',
      );
      expect(rosterCall.authorization, 'Bearer access-1');
      expect(find.byKey(rosterEditorRowKey(7)), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('未注入入口：想定管理页不渲染「编成」按钮', (WidgetTester tester) async {
      await pumpPlans(
        tester,
        list: ({String? keyword}) async => planPageOf(<Plan>[planOf('p-1', name: '红方突袭')]),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(planManagerRosterKey('p-1')), findsNothing);

      await unmount(tester);
    });
  });

  group('演习控制页（段 51）', () {
    testWidgets('无演习进行中：显示空态与开演表单，默认选中首个想定', (WidgetTester tester) async {
      await pumpExercise(
        tester,
        load: () async => exerciseOf(),
        listPlans: ({String? keyword}) async =>
            planPageOf(<Plan>[planOf('p-1', name: '红方突袭', rosterCount: 5)]),
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kExerciseControlAccountKey)).data,
        'admin（OPERATOR）',
      );
      expect(find.byKey(kExerciseControlIdleKey), findsOneWidget);
      expect(
        tester
            .widget<DropdownButton<String>>(find.byKey(kExerciseControlPlanFieldKey))
            .value,
        'p-1',
      );
      expect(find.byKey(kExerciseControlStartButtonKey), findsOneWidget);
      expect(find.byKey(kExerciseControlStopButtonKey), findsNothing);

      await unmount(tester);
    });

    testWidgets('进行中：显示场次摘要与停演按钮', (WidgetTester tester) async {
      await pumpExercise(
        tester,
        load: () async => exerciseOf(
          running: true,
          exerciseId: 'ex-1',
          planId: 'p-1',
          planName: '红方突袭',
          battleMode: BattleMode.battle,
          elapsedSeconds: 120,
          rosterCount: 5,
          onlineCount: 4,
        ),
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kExerciseControlRunningKey)).data,
        '进行中：想定 红方突袭｜模式 BATTLE｜已进行 120s｜参演 5｜在线 4',
      );
      expect(find.byKey(kExerciseControlStopButtonKey), findsOneWidget);
      expect(find.byKey(kExerciseControlIdleKey), findsNothing);

      await unmount(tester);
    });

    testWidgets('开演默认沿用想定：请求体只发 planId（不发 battleMode），成功后转为进行中',
        (WidgetTester tester) async {
      final List<StartExerciseRequest> started = <StartExerciseRequest>[];
      await pumpExercise(
        tester,
        load: () async => exerciseOf(),
        listPlans: ({String? keyword}) async =>
            planPageOf(<Plan>[planOf('p-1', name: '红方突袭')]),
        start: (StartExerciseRequest request) async {
          started.add(request);
          return exerciseOf(
            running: true,
            exerciseId: 'ex-1',
            planId: request.planId,
            planName: '红方突袭',
          );
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kExerciseControlStartButtonKey));
      await tester.pumpAndSettle();

      expect(started.single.toJson(), <String, Object?>{'planId': 'p-1'});
      expect(find.byKey(kExerciseControlRunningKey), findsOneWidget);
      expect(find.text('已开演：想定「红方突袭」'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('开演选演习模式：请求体带上契约字面量', (WidgetTester tester) async {
      final List<StartExerciseRequest> started = <StartExerciseRequest>[];
      await pumpExercise(
        tester,
        load: () async => exerciseOf(),
        listPlans: ({String? keyword}) async =>
            planPageOf(<Plan>[planOf('p-1', name: '红方突袭')]),
        start: (StartExerciseRequest request) async {
          started.add(request);
          return exerciseOf(running: true, exerciseId: 'ex-1', planName: '红方突袭');
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kExerciseControlBattleModeKey));
      await tester.pumpAndSettle();
      await tester.tap(find.text('BATTLE').last);
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kExerciseControlStartButtonKey));
      await tester.pumpAndSettle();

      expect(started.single.toJson(), <String, Object?>{
        'planId': 'p-1',
        'battleMode': 'BATTLE',
      });

      await unmount(tester);
    });

    testWidgets('无想定：开演触发本地校验、不发请求', (WidgetTester tester) async {
      await pumpExercise(
        tester,
        listPlans: ({String? keyword}) async => planPageOf(<Plan>[]),
      );
      await tester.pumpAndSettle();

      expect(find.text('暂无想定，请先在想定管理中创建'), findsOneWidget);

      await tester.tap(find.byKey(kExerciseControlStartButtonKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kExerciseControlErrorKey)).data,
        '请先选择想定再开演',
      );

      await unmount(tester);
    });

    testWidgets('停演：二次确认后提交，以服务端状态回切到开演态并提示复盘编号',
        (WidgetTester tester) async {
      bool running = true;
      int stopCalls = 0;
      await pumpExercise(
        tester,
        load: () async => running
            ? exerciseOf(running: true, exerciseId: 'ex-1', planName: '红方突袭')
            : exerciseOf(),
        listPlans: ({String? keyword}) async =>
            planPageOf(<Plan>[planOf('p-1', name: '红方突袭')]),
        stop: () async {
          stopCalls++;
          running = false;
          return StopExerciseResult(
            exerciseId: 'ex-1',
            endedAt: DateTime.utc(2026, 10, 31),
            replayId: 'ex-1',
          );
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kExerciseControlStopButtonKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kExerciseStopConfirmKey));
      await tester.pumpAndSettle();

      expect(stopCalls, 1);
      expect(find.byKey(kExerciseControlIdleKey), findsOneWidget);
      expect(find.text('已停演，复盘编号 ex-1'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('停演取消：不提交、仍停在运行态', (WidgetTester tester) async {
      await pumpExercise(
        tester,
        load: () async => exerciseOf(running: true, exerciseId: 'ex-1', planName: '红方突袭'),
        stop: () async => fail('取消后不应停演'),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kExerciseControlStopButtonKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kExerciseStopCancelKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kExerciseControlRunningKey), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('契约错误码映射：链路未开启', (WidgetTester tester) async {
      await pumpExercise(
        tester,
        listPlans: ({String? keyword}) async =>
            planPageOf(<Plan>[planOf('p-1', name: '红方突袭')]),
        start: (StartExerciseRequest request) async =>
            throw apiFailure(409, ApiErrorCode.deviceLinkNotOpen),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kExerciseControlStartButtonKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kExerciseControlErrorKey)).data,
        contains('马甲串口链路未开启'),
      );

      await unmount(tester);
    });

    testWidgets('契约错误码映射：编成为空', (WidgetTester tester) async {
      await pumpExercise(
        tester,
        listPlans: ({String? keyword}) async =>
            planPageOf(<Plan>[planOf('p-1', name: '红方突袭')]),
        start: (StartExerciseRequest request) async =>
            throw apiFailure(400, ApiErrorCode.exerciseRosterEmpty),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kExerciseControlStartButtonKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kExerciseControlErrorKey)).data,
        contains('编成为空'),
      );

      await unmount(tester);
    });

    testWidgets('契约错误码映射：已有演习进行中', (WidgetTester tester) async {
      await pumpExercise(
        tester,
        listPlans: ({String? keyword}) async =>
            planPageOf(<Plan>[planOf('p-1', name: '红方突袭')]),
        start: (StartExerciseRequest request) async =>
            throw apiFailure(409, ApiErrorCode.exerciseAlreadyRunning),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kExerciseControlStartButtonKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kExerciseControlErrorKey)).data,
        contains('已有演习进行中'),
      );

      await unmount(tester);
    });

    testWidgets('契约错误码映射：停演时无进行中演习', (WidgetTester tester) async {
      await pumpExercise(
        tester,
        load: () async => exerciseOf(running: true, exerciseId: 'ex-1', planName: '红方突袭'),
        stop: () async => throw apiFailure(409, ApiErrorCode.exerciseNotRunning),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kExerciseControlStopButtonKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kExerciseStopConfirmKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kExerciseControlErrorKey)).data,
        contains('当前没有进行中的演习'),
      );

      await unmount(tester);
    });
  });

  /// 段 51：演习控制接线——从队伍汇总页「演习」入口进入，`ExerciseApi` 挂同一条已认证传输。
  group('指挥端演习控制接线（段 51）', () {
    testWidgets('汇总页「演习」入口：进入演习控制页并按登录令牌拉当前演习与想定',
        (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-1',
              'expiresIn': 900,
              'refreshToken': 'refresh-1',
              'user': <String, dynamic>{
                'userId': 'u-1',
                'username': 'admin',
                'roles': <String>['OPERATOR'],
              },
            });
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'GET $kApiPrefix/exercise/current':
            return jsonResponse(<String, dynamic>{'running': false});
          case 'GET $kApiPrefix/plans':
            return jsonResponse(<String, dynamic>{
              'items': <Object>[
                <String, dynamic>{
                  'planId': 'p-1',
                  'name': '红方突袭',
                  'createdAt': '2026-10-29T00:00:00Z',
                },
              ],
              'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
            });
        }
        fail('未预期的请求：${call.key}');
      };

      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);

      await tester.tap(find.byKey(kTeamSummaryExerciseEntryKey));
      await tester.pumpAndSettle();

      expect(find.byType(ExerciseControlPage), findsOneWidget);
      final ScriptedCall currentCall = transport.calls.lastWhere(
        (ScriptedCall call) => call.key == 'GET $kApiPrefix/exercise/current',
      );
      expect(currentCall.authorization, 'Bearer access-1');
      expect(find.byKey(kExerciseControlIdleKey), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('未注入入口：汇总页不渲染「演习」按钮', (WidgetTester tester) async {
      await pumpSummary(tester, () async => <TeamSummary>[]);
      await tester.pumpAndSettle();

      expect(find.byKey(kTeamSummaryExerciseEntryKey), findsNothing);

      await unmount(tester);
    });
  });

  /// 段 53：底图管理页——map 域底图子域的指挥端接线。
  ///
  /// 全部注入闭包桩，**不发真实请求**。判据四条：
  /// 列表契约字段呈现、写端点整体替换口径、图片上传（选文件端口）与下载预览、
  /// 未注入可选端口时对应行内按钮不渲染。
  group('底图管理页（段 53）', () {
    testWidgets('加载底图列表：展示账号；空列表显示空态', (WidgetTester tester) async {
      int listCalls = 0;
      await pumpMaps(tester, list: () async {
        listCalls++;
        return mapPageOf(<MapDetail>[]);
      });
      await tester.pumpAndSettle();

      expect(listCalls, 1);
      expect(tester.widget<Text>(find.byKey(kMapManagerAccountKey)).data, 'admin（OPERATOR）');
      expect(find.byKey(kMapManagerEmptyKey), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('行文本取契约字面量；缺字段如实显示破折号', (WidgetTester tester) async {
      await pumpMaps(
        tester,
        list: () async => mapPageOf(<MapDetail>[
          mapOf(
            'm-1',
            name: '演训场',
            type: MapType.outdoor,
            imageWidth: 1920,
            imageHeight: 1080,
            calibrated: true,
          ),
          mapOf('m-2', name: '室内馆'),
        ]),
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(mapManagerRowKey('m-1'))).data,
        '演训场｜类型 OUTDOOR｜图片 1920×1080｜标定 已标定',
      );
      expect(
        tester.widget<Text>(find.byKey(mapManagerRowKey('m-2'))).data,
        '室内馆｜类型 INDOOR｜图片 无｜标定 —',
      );

      await unmount(tester);
    });

    testWidgets('新建底图：提交整体替换二键（名称 + 类型字面量）', (WidgetTester tester) async {
      final List<MapUpsertRequest> created = <MapUpsertRequest>[];
      await pumpMaps(tester, create: (MapUpsertRequest request) async {
        created.add(request);
        return mapOf('m-9', name: request.name);
      });
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kMapManagerCreateButtonKey));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(kMapFormNameFieldKey), ' 新底图 ');
      await tester.tap(find.byKey(kMapFormSaveKey));
      await tester.pumpAndSettle();

      expect(created.single.toJson(), <String, Object?>{
        'name': '新底图',
        'type': 'INDOOR',
      });
      expect(find.text('已新建底图「新底图」'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('编辑底图：以服务端快照预填，仅改名字仍提交全部二键（整体替换回归）',
        (WidgetTester tester) async {
      final List<MapUpsertRequest> updated = <MapUpsertRequest>[];
      await pumpMaps(
        tester,
        list: () async => mapPageOf(<MapDetail>[
          mapOf('m-1', name: '旧名', type: MapType.outdoor),
        ]),
        update: (String mapId, MapUpsertRequest request) async {
          updated.add(request);
          return mapOf(mapId, name: request.name);
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(mapManagerEditKey('m-1')));
      await tester.pumpAndSettle();

      // 快照预填：不动的字段必须原样出现在表单里，否则整体替换会误伤它们。
      expect(
        tester.widget<TextField>(find.byKey(kMapFormNameFieldKey)).controller!.text,
        '旧名',
      );
      expect(
        tester.widget<DropdownButton<MapType>>(find.byKey(kMapFormTypeKey)).value,
        MapType.outdoor,
      );

      await tester.enterText(find.byKey(kMapFormNameFieldKey), '新名');
      await tester.tap(find.byKey(kMapFormSaveKey));
      await tester.pumpAndSettle();

      expect(updated.single.toJson(), <String, Object?>{
        'name': '新名',
        'type': 'OUTDOOR',
      });

      await unmount(tester);
    });

    testWidgets('底图名非法：不发请求、对话框保留并显示行内错误', (WidgetTester tester) async {
      await pumpMaps(tester);
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kMapManagerCreateButtonKey));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(kMapFormNameFieldKey), '   ');
      await tester.tap(find.byKey(kMapFormSaveKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kMapFormErrorKey), findsOneWidget);
      expect(find.byKey(kMapFormSaveKey), findsOneWidget);

      await tester.tap(find.byKey(kMapFormCancelKey));
      await tester.pumpAndSettle();

      await unmount(tester);
    });

    testWidgets('删除底图：确认后调用删除端口并重拉列表', (WidgetTester tester) async {
      final List<String> removed = <String>[];
      int listCalls = 0;
      await pumpMaps(
        tester,
        list: () async {
          listCalls++;
          return mapPageOf(<MapDetail>[mapOf('m-1', name: '演训场')]);
        },
        remove: (String mapId) async => removed.add(mapId),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(mapManagerDeleteKey('m-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kMapDeleteConfirmKey));
      await tester.pumpAndSettle();

      expect(removed, <String>['m-1']);
      // 首帧一次 + 删除后重拉一次。
      expect(listCalls, 2);
      expect(find.text('已删除底图「演训场」'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('契约错误码映射为现场文案（底图被想定引用）', (WidgetTester tester) async {
      await pumpMaps(
        tester,
        list: () async => mapPageOf(<MapDetail>[mapOf('m-1', name: '演训场')]),
        remove: (String mapId) async => throw apiFailure(409, ApiErrorCode.mapInUse),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(mapManagerDeleteKey('m-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kMapDeleteConfirmKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kMapManagerErrorKey)).data,
        contains('已被想定引用'),
      );

      await unmount(tester);
    });

    testWidgets('上传图片：选文件返回图片即调用上传端口并重拉列表', (WidgetTester tester) async {
      final List<String> uploadTargets = <String>[];
      final List<PickedImage> uploaded = <PickedImage>[];
      int listCalls = 0;
      await pumpMaps(
        tester,
        list: () async {
          listCalls++;
          return mapPageOf(<MapDetail>[mapOf('m-1', name: '演训场')]);
        },
        pickImage: () async => const PickedImage(
          filename: 'plan.png',
          bytes: <int>[137, 80, 78, 71],
          contentType: 'image/png',
        ),
        uploadImage: (String mapId, PickedImage image) async {
          uploadTargets.add(mapId);
          uploaded.add(image);
          return mapOf(mapId, name: '演训场');
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(mapManagerUploadKey('m-1')));
      await tester.pumpAndSettle();

      expect(uploadTargets, <String>['m-1']);
      expect(uploaded.single.filename, 'plan.png');
      expect(uploaded.single.contentType, 'image/png');
      expect(listCalls, 2);
      expect(find.text('已上传底图「演训场」的图片'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('选文件取消（回 null）：不调用上传端口', (WidgetTester tester) async {
      int uploadCalls = 0;
      await pumpMaps(
        tester,
        list: () async => mapPageOf(<MapDetail>[mapOf('m-1', name: '演训场')]),
        pickImage: () async => null,
        uploadImage: (String mapId, PickedImage image) async {
          uploadCalls++;
          return mapOf(mapId, name: '演训场');
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(mapManagerUploadKey('m-1')));
      await tester.pumpAndSettle();

      expect(uploadCalls, 0);

      await unmount(tester);
    });

    testWidgets('预览图片：按原格式下载字节并打开预览对话框', (WidgetTester tester) async {
      int downloadCalls = 0;
      await pumpMaps(
        tester,
        list: () async => mapPageOf(<MapDetail>[mapOf('m-1', name: '演训场')]),
        downloadImage: (String mapId) async {
          downloadCalls++;
          return ApiResponse(statusCode: 200, contentType: 'image/png', body: tinyPng);
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(mapManagerPreviewKey('m-1')));
      await tester.pumpAndSettle();

      expect(downloadCalls, 1);
      expect(find.byKey(kMapImagePreviewKey), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(kMapImagePreviewInfoKey)).data,
        '类型 image/png｜${tinyPng.length} 字节',
      );

      await tester.tap(find.byKey(kMapImagePreviewCloseKey));
      await tester.pumpAndSettle();

      await unmount(tester);
    });

    testWidgets('未注入上传 / 预览端口：不渲染对应行内按钮', (WidgetTester tester) async {
      await pumpMaps(
        tester,
        list: () async => mapPageOf(<MapDetail>[mapOf('m-1', name: '演训场')]),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(mapManagerUploadKey('m-1')), findsNothing);
      expect(find.byKey(mapManagerPreviewKey('m-1')), findsNothing);
      expect(find.byKey(mapManagerEditKey('m-1')), findsOneWidget);
      expect(find.byKey(mapManagerDeleteKey('m-1')), findsOneWidget);

      await unmount(tester);
    });
  });

  /// 段 53：底图管理页接线——从队伍汇总页进入，`MapApi` 挂同一条已认证传输。
  group('指挥端底图管理接线（段 53）', () {
    testWidgets('汇总页「底图」入口：进入底图管理页并按登录令牌拉列表',
        (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-1',
              'expiresIn': 900,
              'refreshToken': 'refresh-1',
              'user': <String, dynamic>{
                'userId': 'u-1',
                'username': 'admin',
                'roles': <String>['OPERATOR'],
              },
            });
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'GET $kApiPrefix/maps':
            return jsonResponse(<String, dynamic>{
              'items': <Object>[
                <String, dynamic>{'mapId': 'm-1', 'name': '演训场', 'type': 'INDOOR'},
              ],
              'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
            });
        }
        fail('未预期的请求：${call.key}');
      };

      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);

      await tester.tap(find.byKey(kTeamSummaryMapsEntryKey));
      await tester.pumpAndSettle();

      expect(find.byType(MapManagerPage), findsOneWidget);
      expect(
        transport.sequence,
        containsAllInOrder(<String>[
          'POST $kApiPrefix/auth/login',
          'GET $kApiPrefix/situation/teams/summary',
          'GET $kApiPrefix/maps',
        ]),
      );
      final ScriptedCall listCall = transport.calls
          .lastWhere((ScriptedCall call) => call.key == 'GET $kApiPrefix/maps');
      expect(listCall.authorization, 'Bearer access-1');
      expect(
        tester.widget<Text>(find.byKey(mapManagerRowKey('m-1'))).data,
        contains('演训场'),
      );

      await unmount(tester);
    });

    testWidgets('未注入入口：汇总页不渲染「底图」按钮', (WidgetTester tester) async {
      await pumpSummary(tester, () async => <TeamSummary>[]);

      expect(find.byKey(kTeamSummaryMapsEntryKey), findsNothing);

      await unmount(tester);
    });
  });

  /// 段 54：UWB 标定面板——`uwb-meta` 读 / 写 + `uwb-calibration` 启动 / 轮询 / 清除。
  group('UWB 标定面板（段 54）', () {
    testWidgets('已标定：状态文案取长宽基准，表单以服务端快照预填十键', (WidgetTester tester) async {
      await pumpUwb(
        tester,
        loadMeta: () async =>
            uwbMetaOf(bs5: const UwbAnchorPoint(xPx: 12, yPx: 34)),
        pollCalibration: () async =>
            const UwbCalibrationStatus(state: UwbCalibrationState.idle),
      );
      await tester.pumpAndSettle();

      expect(tester.widget<Text>(find.byKey(kUwbMetaStatusKey)).data, '已标定｜长 600cm｜宽 400cm');
      expect(tester.widget<Text>(find.byKey(kUwbCalibrationStateKey)).data, 'IDLE');

      // 整体替换回归：不动的字段必须原样出现在表单里，否则保存会误伤它们。
      expect(fieldText(tester, uwbMetaXKey(1)), '0');
      expect(fieldText(tester, uwbMetaYKey(1)), '0');
      expect(fieldText(tester, uwbMetaXKey(2)), '600');
      expect(fieldText(tester, uwbMetaXKey(5)), '12');
      expect(fieldText(tester, uwbMetaYKey(5)), '34');
      expect(fieldText(tester, uwbMetaXKey(6)), '');
      expect(fieldText(tester, kUwbMetaRealLengthKey), '600');
      expect(fieldText(tester, kUwbMetaRealWidthKey), '400');
      expect(tester.widget<Text>(find.byKey(kUwbCalibrationAccountKey)).data, 'admin（OPERATOR）');

      await unmount(tester);
    });

    testWidgets('未标定（404 MAP_NOT_CALIBRATED）：切「未标定」态而非报错', (WidgetTester tester) async {
      await pumpUwb(
        tester,
        loadMeta: () async => throw apiFailure(404, ApiErrorCode.mapNotCalibrated),
        pollCalibration: () async =>
            const UwbCalibrationStatus(state: UwbCalibrationState.idle),
      );
      await tester.pumpAndSettle();

      expect(tester.widget<Text>(find.byKey(kUwbMetaStatusKey)).data, '未标定');
      expect(find.byKey(kUwbCalibrationErrorKey), findsNothing);
      expect(fieldText(tester, uwbMetaXKey(1)), '');

      await unmount(tester);
    });

    testWidgets('底图不存在（404 MAP_NOT_FOUND）：真错误落页面顶部提示', (WidgetTester tester) async {
      await pumpUwb(
        tester,
        loadMeta: () async => throw apiFailure(404, ApiErrorCode.mapNotFound),
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kUwbCalibrationErrorKey)).data,
        contains(ApiErrorCode.mapNotFound),
      );

      await unmount(tester);
    });

    testWidgets('保存：整体替换提交十键（bs5 有值、bs6~bs8 置 null）', (WidgetTester tester) async {
      final List<UwbMeta> saved = <UwbMeta>[];
      await pumpUwb(
        tester,
        loadMeta: () async => uwbMetaOf(bs5: const UwbAnchorPoint(xPx: 12, yPx: 34)),
        pollCalibration: () async =>
            const UwbCalibrationStatus(state: UwbCalibrationState.idle),
        saveMeta: (UwbMeta meta) async {
          saved.add(meta);
          return meta;
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kUwbMetaSaveButtonKey));
      await tester.pumpAndSettle();

      expect(saved.single.toJson(), <String, Object?>{
        'bs1': <String, Object?>{'xPx': 0.0, 'yPx': 0.0},
        'bs2': <String, Object?>{'xPx': 600.0, 'yPx': 0.0},
        'bs3': <String, Object?>{'xPx': 600.0, 'yPx': 400.0},
        'bs4': <String, Object?>{'xPx': 0.0, 'yPx': 400.0},
        'bs5': <String, Object?>{'xPx': 12.0, 'yPx': 34.0},
        'bs6': null,
        'bs7': null,
        'bs8': null,
        'realLengthCm': 600.0,
        'realWidthCm': 400.0,
      });
      expect(find.text('已保存 UWB 标定参数'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('bs1 留空：不发请求、显示行内校验错误', (WidgetTester tester) async {
      int saveCalls = 0;
      await pumpUwb(
        tester,
        loadMeta: () async => uwbMetaOf(),
        pollCalibration: () async =>
            const UwbCalibrationStatus(state: UwbCalibrationState.idle),
        saveMeta: (UwbMeta meta) async {
          saveCalls++;
          return meta;
        },
      );
      await tester.pumpAndSettle();

      // 清空 bs1 的横纵坐标（必填四角之一）。
      await tester.enterText(find.byKey(uwbMetaXKey(1)), '');
      await tester.enterText(find.byKey(uwbMetaYKey(1)), '');
      await tester.tap(find.byKey(kUwbMetaSaveButtonKey));
      await tester.pumpAndSettle();

      expect(saveCalls, 0);
      expect(
        tester.widget<Text>(find.byKey(kUwbMetaFormErrorKey)).data,
        contains('基站 1 为必填'),
      );

      await unmount(tester);
    });

    testWidgets('启动校准：携带采样帧受理，轮询推进，命中终态即停表', (WidgetTester tester) async {
      final List<UwbCalibrationRequest?> started = <UwbCalibrationRequest?>[];
      int pollCalls = 0;
      await pumpUwb(
        tester,
        loadMeta: () async => uwbMetaOf(),
        pollCalibration: () async {
          pollCalls++;
          return switch (pollCalls) {
            <= 1 => const UwbCalibrationStatus(state: UwbCalibrationState.idle),
            2 => const UwbCalibrationStatus(
                state: UwbCalibrationState.running,
                progressPct: 40,
                sampledFrames: 80,
              ),
            _ => const UwbCalibrationStatus(
                state: UwbCalibrationState.succeeded,
                progressPct: 100,
                sampledFrames: 200,
              ),
          };
        },
        startCalibration: (UwbCalibrationRequest? request) async {
          started.add(request);
          return const UwbCalibrationStatus(state: UwbCalibrationState.running);
        },
      );
      await tester.pumpAndSettle();

      expect(tester.widget<Text>(find.byKey(kUwbCalibrationStateKey)).data, 'IDLE');

      await tester.enterText(find.byKey(kUwbCalibrationSampleFramesKey), '120');
      await tester.tap(find.byKey(kUwbCalibrationStartButtonKey));
      await tester.pump();
      await tester.pump();

      // 已受理并不等于在跑：只有 RUNNING 才起 1s 定时器。
      expect(started.single!.toJson(), <String, Object?>{'sampleFrames': 120});
      expect(tester.widget<Text>(find.byKey(kUwbCalibrationStateKey)).data, 'RUNNING');

      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      expect(
        tester.widget<Text>(find.byKey(kUwbCalibrationStateKey)).data,
        'RUNNING｜进度 40%｜已采样 80 帧',
      );

      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      expect(
        tester.widget<Text>(find.byKey(kUwbCalibrationStateKey)).data,
        'SUCCEEDED｜进度 100%｜已采样 200 帧',
      );

      // 终态后再推进 5s 不应再有轮询。
      await tester.pump(const Duration(seconds: 5));
      await tester.pump();
      expect(pollCalls, 3);

      await unmount(tester);
    });

    testWidgets('启动校准留空采样帧：端口收到 null（不发请求体，服务端取默认）',
        (WidgetTester tester) async {
      final List<UwbCalibrationRequest?> started = <UwbCalibrationRequest?>[];
      await pumpUwb(
        tester,
        loadMeta: () async => uwbMetaOf(),
        pollCalibration: () async =>
            const UwbCalibrationStatus(state: UwbCalibrationState.idle),
        startCalibration: (UwbCalibrationRequest? request) async {
          started.add(request);
          return const UwbCalibrationStatus(state: UwbCalibrationState.idle);
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kUwbCalibrationStartButtonKey));
      await tester.pumpAndSettle();

      expect(started.single, isNull);
      expect(find.text('已受理 UWB 校准'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('清除校准：状态回到「未查询」并提示', (WidgetTester tester) async {
      int clearCalls = 0;
      await pumpUwb(
        tester,
        loadMeta: () async => uwbMetaOf(),
        pollCalibration: () async =>
            const UwbCalibrationStatus(state: UwbCalibrationState.succeeded, progressPct: 100),
        clearCalibration: () async => clearCalls++,
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kUwbCalibrationStateKey)).data,
        'SUCCEEDED｜进度 100%',
      );

      await tester.tap(find.byKey(kUwbCalibrationClearButtonKey));
      await tester.pumpAndSettle();

      expect(clearCalls, 1);
      expect(tester.widget<Text>(find.byKey(kUwbCalibrationStateKey)).data, '未查询');
      expect(find.text('已清除 UWB 校准结果'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('契约错误码映射为现场文案（链路未开 / 标定参数不自洽）', (WidgetTester tester) async {
      await pumpUwb(
        tester,
        loadMeta: () async => uwbMetaOf(),
        pollCalibration: () async =>
            const UwbCalibrationStatus(state: UwbCalibrationState.idle),
        startCalibration: (UwbCalibrationRequest? request) async =>
            throw apiFailure(409, ApiErrorCode.deviceLinkNotOpen),
        saveMeta: (UwbMeta meta) async =>
            throw apiFailure(400, ApiErrorCode.mapUwbMetaIncomplete),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kUwbCalibrationStartButtonKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kUwbCalibrationErrorKey)).data,
        contains(ApiErrorCode.deviceLinkNotOpen),
      );

      await tester.tap(find.byKey(kUwbMetaSaveButtonKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kUwbCalibrationErrorKey)).data,
        contains(ApiErrorCode.mapUwbMetaIncomplete),
      );

      await unmount(tester);
    });
  });

  /// 段 54：UWB 标定接线——底图行「标定」入口进入，四个端口挂同一条已认证传输。
  group('指挥端 UWB 标定接线（段 54）', () {
    testWidgets('底图行「标定」入口：进入标定面板并按登录令牌拉参数与校准状态',
        (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-1',
              'expiresIn': 900,
              'refreshToken': 'refresh-1',
              'user': <String, dynamic>{
                'userId': 'u-1',
                'username': 'admin',
                'roles': <String>['OPERATOR'],
              },
            });
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'GET $kApiPrefix/maps':
            return jsonResponse(<String, dynamic>{
              'items': <Object>[
                <String, dynamic>{'mapId': 'm-1', 'name': '演训场', 'type': 'INDOOR'},
              ],
              'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
            });
          case 'GET $kApiPrefix/maps/m-1/uwb-meta':
            return jsonResponse(<String, dynamic>{
              'bs1': <String, dynamic>{'xPx': 0, 'yPx': 0},
              'bs2': <String, dynamic>{'xPx': 600, 'yPx': 0},
              'bs3': <String, dynamic>{'xPx': 600, 'yPx': 400},
              'bs4': <String, dynamic>{'xPx': 0, 'yPx': 400},
              'realLengthCm': 600,
              'realWidthCm': 400,
            });
          case 'GET $kApiPrefix/maps/m-1/uwb-calibration':
            return jsonResponse(<String, dynamic>{'state': 'IDLE'});
        }
        fail('未预期的请求：${call.key}');
      };

      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);

      await tester.tap(find.byKey(kTeamSummaryMapsEntryKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(mapManagerCalibrationKey('m-1')));
      await tester.pumpAndSettle();

      expect(find.byType(UwbCalibrationPage), findsOneWidget);
      expect(
        transport.sequence,
        containsAllInOrder(<String>[
          'POST $kApiPrefix/auth/login',
          'GET $kApiPrefix/situation/teams/summary',
          'GET $kApiPrefix/maps',
          'GET $kApiPrefix/maps/m-1/uwb-meta',
          'GET $kApiPrefix/maps/m-1/uwb-calibration',
        ]),
      );
      final ScriptedCall metaCall = transport.calls.lastWhere(
        (ScriptedCall call) => call.key == 'GET $kApiPrefix/maps/m-1/uwb-meta',
      );
      expect(metaCall.authorization, 'Bearer access-1');
      expect(tester.widget<Text>(find.byKey(kUwbMetaStatusKey)).data, '已标定｜长 600cm｜宽 400cm');

      await unmount(tester);
    });

    testWidgets('未注入入口：底图行不渲染「标定」按钮', (WidgetTester tester) async {
      await pumpMaps(
        tester,
        list: () async => mapPageOf(<MapDetail>[mapOf('m-1', name: '演训场')]),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(mapManagerCalibrationKey('m-1')), findsNothing);
      expect(find.byKey(mapManagerEditKey('m-1')), findsOneWidget);

      await unmount(tester);
    });
  });

  /// 段 55：楼层点位编辑页——map 域最后 2 端点（点位表读 / 整体替换）。
  group('楼层点位编辑页（段 55）', () {
    testWidgets('已加载：状态文案给出楼层与点位数，表单以服务端快照整表预填', (WidgetTester tester) async {
      final List<int> loaded = <int>[];
      await pumpFloor(
        tester,
        floorCount: 3,
        loadPoints: (int floorNum) async {
          loaded.add(floorNum);
          return FloorPointsRequest(points: <FloorPoint>[
            floorPointOf(posNum: 1, x: 0.1, y: 0.2, label: '门口'),
            floorPointOf(posNum: 2, x: 0.5, y: 1),
          ]);
        },
      );
      await tester.pumpAndSettle();

      expect(loaded, <int>[1]);
      expect(tester.widget<Text>(find.byKey(kFloorPointsStatusKey)).data, '楼层 1｜共 2 个点位');
      expect(
        tester.widget<Text>(find.byKey(kFloorPointsAccountKey)).data,
        'admin（OPERATOR）',
      );

      // 整体替换回归：不动的字段必须原样出现在表单里，否则保存会误伤它们。
      expect(fieldText(tester, kFloorPointsFloorNumKey), '1');
      expect(fieldText(tester, floorPointsPosNumKey(0)), '1');
      expect(fieldText(tester, floorPointsXKey(0)), '0.1');
      expect(fieldText(tester, floorPointsYKey(0)), '0.2');
      expect(fieldText(tester, floorPointsLabelKey(0)), '门口');
      expect(fieldText(tester, floorPointsPosNumKey(1)), '2');
      expect(fieldText(tester, floorPointsXKey(1)), '0.5');
      expect(fieldText(tester, floorPointsYKey(1)), '1');
      expect(fieldText(tester, floorPointsLabelKey(1)), '');

      await unmount(tester);
    });

    testWidgets('空楼层：显示空态，新增一行后空态消失', (WidgetTester tester) async {
      await pumpFloor(
        tester,
        loadPoints: (int floorNum) async => const FloorPointsRequest(points: <FloorPoint>[]),
      );
      await tester.pumpAndSettle();

      expect(tester.widget<Text>(find.byKey(kFloorPointsStatusKey)).data, '楼层 1｜共 0 个点位');
      expect(find.byKey(kFloorPointsEmptyKey), findsOneWidget);

      await tester.tap(find.byKey(kFloorPointsAddButtonKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kFloorPointsEmptyKey), findsNothing);
      expect(find.byKey(floorPointsPosNumKey(0)), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('删行后保存：整表替换为空数组（合法清空）', (WidgetTester tester) async {
      final List<FloorPointsRequest> saved = <FloorPointsRequest>[];
      await pumpFloor(
        tester,
        loadPoints: (int floorNum) async =>
            FloorPointsRequest(points: <FloorPoint>[floorPointOf(posNum: 9, x: 0.3, y: 0.4)]),
        savePoints: (int floorNum, FloorPointsRequest request) async {
          saved.add(request);
          return request;
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(floorPointsRemoveKey(0)));
      await tester.pumpAndSettle();
      expect(tester.widget<Text>(find.byKey(kFloorPointsStatusKey)).data, '楼层 1｜共 0 个点位');

      await tester.tap(find.byKey(kFloorPointsSaveButtonKey));
      await tester.pumpAndSettle();

      expect(saved.single.toJson(), <String, Object?>{'points': <Object>[]});
      expect(find.text('已保存楼层 1 的 0 个点位'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('保存是整体替换：新增行与既有行四键全提交（label 留空显式 null）', (WidgetTester tester) async {
      final List<int> savedFloors = <int>[];
      final List<FloorPointsRequest> saved = <FloorPointsRequest>[];
      await pumpFloor(
        tester,
        loadPoints: (int floorNum) async => FloorPointsRequest(
          points: <FloorPoint>[floorPointOf(posNum: 1, x: 0.1, y: 0.2, label: '门口')],
        ),
        savePoints: (int floorNum, FloorPointsRequest request) async {
          savedFloors.add(floorNum);
          saved.add(request);
          return request;
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kFloorPointsAddButtonKey));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(floorPointsPosNumKey(1)), '7');
      await tester.enterText(find.byKey(floorPointsXKey(1)), '0.9');
      await tester.enterText(find.byKey(floorPointsYKey(1)), '0.8');
      await tester.enterText(find.byKey(floorPointsLabelKey(1)), '空地');
      // 既有行的备注清空——整体替换下这就是「置空」，必须显式发 null。
      await tester.enterText(find.byKey(floorPointsLabelKey(0)), '');

      await tester.tap(find.byKey(kFloorPointsSaveButtonKey));
      await tester.pumpAndSettle();

      expect(savedFloors, <int>[1]);
      expect(saved.single.toJson(), <String, Object?>{
        'points': <Object>[
          <String, Object?>{'posNum': 1, 'xPct': 0.1, 'yPct': 0.2, 'label': null},
          <String, Object?>{'posNum': 7, 'xPct': 0.9, 'yPct': 0.8, 'label': '空地'},
        ],
      });
      expect(find.text('已保存楼层 1 的 2 个点位'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('posNum 留空：不发请求、显示行内校验错误', (WidgetTester tester) async {
      int saveCalls = 0;
      await pumpFloor(
        tester,
        loadPoints: (int floorNum) async =>
            FloorPointsRequest(points: <FloorPoint>[floorPointOf()]),
        savePoints: (int floorNum, FloorPointsRequest request) async {
          saveCalls++;
          return request;
        },
      );
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(floorPointsPosNumKey(0)), '');
      await tester.tap(find.byKey(kFloorPointsSaveButtonKey));
      await tester.pumpAndSettle();

      expect(saveCalls, 0);
      expect(
        tester.widget<Text>(find.byKey(kFloorPointsFormErrorKey)).data,
        '第 1 行点位号需为整数',
      );

      await unmount(tester);
    });

    testWidgets('坐标越界 1.2：不发请求、显示行内校验错误', (WidgetTester tester) async {
      int saveCalls = 0;
      await pumpFloor(
        tester,
        loadPoints: (int floorNum) async =>
            FloorPointsRequest(points: <FloorPoint>[floorPointOf()]),
        savePoints: (int floorNum, FloorPointsRequest request) async {
          saveCalls++;
          return request;
        },
      );
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(floorPointsXKey(0)), '1.2');
      await tester.tap(find.byKey(kFloorPointsSaveButtonKey));
      await tester.pumpAndSettle();

      expect(saveCalls, 0);
      expect(
        tester.widget<Text>(find.byKey(kFloorPointsFormErrorKey)).data,
        '第 1 行 xPct 需为 0~1 的数值（归一化百分比）',
      );
      expect(tester.widget<Text>(find.byKey(kFloorPointsStatusKey)).data, '楼层 1｜共 1 个点位');

      await unmount(tester);
    });

    testWidgets('坐标边界 0 与 1 合法：照常提交', (WidgetTester tester) async {
      final List<FloorPointsRequest> saved = <FloorPointsRequest>[];
      await pumpFloor(
        tester,
        loadPoints: (int floorNum) async =>
            FloorPointsRequest(points: <FloorPoint>[floorPointOf()]),
        savePoints: (int floorNum, FloorPointsRequest request) async {
          saved.add(request);
          return request;
        },
      );
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(floorPointsXKey(0)), '0');
      await tester.enterText(find.byKey(floorPointsYKey(0)), '1');
      await tester.tap(find.byKey(kFloorPointsSaveButtonKey));
      await tester.pumpAndSettle();

      expect(saved.single.points.single.xPct, 0.0);
      expect(saved.single.points.single.yPct, 1.0);

      await unmount(tester);
    });

    testWidgets('改了楼层号未重新加载即保存：挡下，不把本层清单写到新楼层', (WidgetTester tester) async {
      final List<int> loaded = <int>[];
      int saveCalls = 0;
      await pumpFloor(
        tester,
        loadPoints: (int floorNum) async {
          loaded.add(floorNum);
          return FloorPointsRequest(points: <FloorPoint>[floorPointOf(posNum: floorNum)]);
        },
        savePoints: (int floorNum, FloorPointsRequest request) async {
          saveCalls++;
          return request;
        },
      );
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(kFloorPointsFloorNumKey), '2');
      await tester.tap(find.byKey(kFloorPointsSaveButtonKey));
      await tester.pumpAndSettle();

      expect(saveCalls, 0);
      expect(
        tester.widget<Text>(find.byKey(kFloorPointsFormErrorKey)).data,
        '楼层号已改，请先「加载」该楼层再保存',
      );

      // 重新「加载」后才切到第 2 层，且整表以第 2 层的快照重建。
      await tester.tap(find.byKey(kFloorPointsLoadButtonKey));
      await tester.pumpAndSettle();

      expect(loaded, <int>[1, 2]);
      expect(tester.widget<Text>(find.byKey(kFloorPointsStatusKey)).data, '楼层 2｜共 1 个点位');
      expect(fieldText(tester, floorPointsPosNumKey(0)), '2');

      await unmount(tester);
    });

    testWidgets('底图不存在（404 MAP_NOT_FOUND）：错误落到页面顶部而非行内', (WidgetTester tester) async {
      await pumpFloor(
        tester,
        loadPoints: (int floorNum) async => throw apiFailure(404, ApiErrorCode.mapNotFound),
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kFloorPointsErrorKey)).data,
        '底图不存在（MAP_NOT_FOUND）',
      );
      expect(find.byKey(kFloorPointsFormErrorKey), findsNothing);
      expect(tester.widget<Text>(find.byKey(kFloorPointsStatusKey)).data, '未加载');

      await unmount(tester);
    });
  });

  /// 段 55：楼层点位接线——底图行「点位」入口进入，两个端口挂同一条已认证传输。
  group('指挥端楼层点位接线（段 55）', () {
    testWidgets('底图行「点位」入口：进入点位页并按登录令牌拉第 1 层点位表',
        (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-1',
              'expiresIn': 900,
              'refreshToken': 'refresh-1',
              'user': <String, dynamic>{
                'userId': 'u-1',
                'username': 'admin',
                'roles': <String>['OPERATOR'],
              },
            });
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'GET $kApiPrefix/maps':
            return jsonResponse(<String, dynamic>{
              'items': <Object>[
                <String, dynamic>{'mapId': 'm-1', 'name': '演训场', 'type': 'INDOOR'},
              ],
              'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
            });
          case 'GET $kApiPrefix/maps/m-1/floors/1/points':
            return jsonResponse(<String, dynamic>{
              'points': <Object>[
                <String, dynamic>{'posNum': 1, 'xPct': 0.25, 'yPct': 0.75, 'label': '门口'},
              ],
            });
        }
        fail('未预期的请求：${call.key}');
      };

      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);

      await tester.tap(find.byKey(kTeamSummaryMapsEntryKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(mapManagerFloorPointsKey('m-1')));
      await tester.pumpAndSettle();

      expect(find.byType(FloorPointsPage), findsOneWidget);
      expect(
        transport.sequence,
        containsAllInOrder(<String>[
          'POST $kApiPrefix/auth/login',
          'GET $kApiPrefix/situation/teams/summary',
          'GET $kApiPrefix/maps',
          'GET $kApiPrefix/maps/m-1/floors/1/points',
        ]),
      );
      final ScriptedCall pointsCall = transport.calls.lastWhere(
        (ScriptedCall call) => call.key == 'GET $kApiPrefix/maps/m-1/floors/1/points',
      );
      expect(pointsCall.authorization, 'Bearer access-1');
      expect(tester.widget<Text>(find.byKey(kFloorPointsStatusKey)).data, '楼层 1｜共 1 个点位');
      expect(fieldText(tester, floorPointsPosNumKey(0)), '1');

      await unmount(tester);
    });

    testWidgets('未注入入口：底图行不渲染「点位」按钮', (WidgetTester tester) async {
      await pumpMaps(
        tester,
        list: () async => mapPageOf(<MapDetail>[mapOf('m-1', name: '演训场')]),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(mapManagerFloorPointsKey('m-1')), findsNothing);
      expect(find.byKey(mapManagerEditKey('m-1')), findsOneWidget);

      await unmount(tester);
    });
  });

  /// 段 57：复盘列表页——replay 域只读面（列表 / 概览）的指挥端接线。
  group('复盘列表页（段 57）', () {
    testWidgets('已加载：给出账号与行摘要（想定 / 模式 / 开始 / 时长 / 各计数）',
        (WidgetTester tester) async {
      final Replay replay = replayOf(
        'r-1',
        planName: '红方突袭',
        startedAt: DateTime.utc(2026, 10, 1, 8),
        battleMode: BattleMode.battle,
        durationSeconds: 600,
        participantCount: 12,
        hitCount: 5,
        deadCount: 3,
      );
      await pumpReplayList(
        tester,
        list: ({String? keyword, DateTime? from, DateTime? to, int? page, int? size}) async =>
            replayPageOf(<Replay>[replay]),
      );
      await tester.pumpAndSettle();

      expect(tester.widget<Text>(find.byKey(kReplayListAccountKey)).data, 'admin（OPERATOR）');
      expect(
        tester.widget<Text>(find.byKey(replayListRowKey('r-1'))).data,
        '红方突袭｜模式 BATTLE｜开始 2026-10-01T08:00:00.000Z｜时长 600s｜参演 12｜命中 5｜阵亡 3',
      );
      expect(tester.widget<Text>(find.byKey(kReplayListPageKey)).data, '第 1 页｜共 1 场（每页 20）');

      await unmount(tester);
    });

    testWidgets('可选字段缺失：模式与各计数显示破折号，不臆造 0', (WidgetTester tester) async {
      await pumpReplayList(
        tester,
        list: ({String? keyword, DateTime? from, DateTime? to, int? page, int? size}) async =>
            replayPageOf(<Replay>[replayOf('r-2', planName: '夜间行军')]),
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(replayListRowKey('r-2'))).data,
        '夜间行军｜模式 —｜开始 2026-10-01T08:00:00.000Z｜时长 —｜参演 —｜命中 —｜阵亡 —',
      );

      await unmount(tester);
    });

    testWidgets('空列表：显示空态', (WidgetTester tester) async {
      await pumpReplayList(tester);
      await tester.pumpAndSettle();

      expect(find.byKey(kReplayListEmptyKey), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('查询：日期按 UTC 零点解释，关键字与区间一并交端口并重拉第 1 页',
        (WidgetTester tester) async {
      final List<String?> keywords = <String?>[];
      final List<DateTime?> froms = <DateTime?>[];
      final List<DateTime?> tos = <DateTime?>[];
      final List<int?> pages = <int?>[];
      await pumpReplayList(
        tester,
        list: ({String? keyword, DateTime? from, DateTime? to, int? page, int? size}) async {
          keywords.add(keyword);
          froms.add(from);
          tos.add(to);
          pages.add(page);
          return replayPageOf(<Replay>[]);
        },
      );
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(kReplayListKeywordKey), ' 红方 ');
      await tester.enterText(find.byKey(kReplayListFromKey), '2026-10-01');
      await tester.enterText(find.byKey(kReplayListToKey), '2026-10-05');
      await tester.tap(find.byKey(kReplayListSearchButtonKey));
      await tester.pumpAndSettle();

      expect(keywords, <String?>[null, '红方']);
      // 只给日期时按 **UTC 零点**解释——否则本地时区会把区间整体平移一天。
      expect(froms, <DateTime?>[null, DateTime.utc(2026, 10, 1)]);
      expect(tos, <DateTime?>[null, DateTime.utc(2026, 10, 5)]);
      expect(pages, <int?>[1, 1]);

      await unmount(tester);
    });

    testWidgets('非法时间：本地拦下、不发请求，显示行内校验错误', (WidgetTester tester) async {
      int calls = 0;
      await pumpReplayList(
        tester,
        list: ({String? keyword, DateTime? from, DateTime? to, int? page, int? size}) async {
          calls++;
          return replayPageOf(<Replay>[]);
        },
      );
      await tester.pumpAndSettle();
      expect(calls, 1);

      await tester.enterText(find.byKey(kReplayListFromKey), '2026/10/01');
      await tester.tap(find.byKey(kReplayListSearchButtonKey));
      await tester.pumpAndSettle();

      expect(calls, 1);
      expect(
        tester.widget<Text>(find.byKey(kReplayListFormErrorKey)).data,
        '时间需为 yyyy-MM-dd 或 ISO-8601（留空表示不限）',
      );

      await unmount(tester);
    });

    testWidgets('分页：下一页按 page+1 拉取，末页禁用「下一页」、首页禁用「上一页」',
        (WidgetTester tester) async {
      final List<int?> pages = <int?>[];
      await pumpReplayList(
        tester,
        list: ({String? keyword, DateTime? from, DateTime? to, int? page, int? size}) async {
          pages.add(page);
          if (page == 2) {
            return replayPageOf(<Replay>[replayOf('r-21')], page: 2, total: 25);
          }
          return replayPageOf(
            <Replay>[for (int i = 1; i <= 20; i++) replayOf('r-$i')],
            page: 1,
            total: 25,
          );
        },
      );
      await tester.pumpAndSettle();

      expect(pages, <int?>[1]);
      expect(tester.widget<TextButton>(find.byKey(kReplayListPrevButtonKey)).onPressed, isNull);
      expect(tester.widget<TextButton>(find.byKey(kReplayListNextButtonKey)).onPressed, isNotNull);

      await tester.tap(find.byKey(kReplayListNextButtonKey));
      await tester.pumpAndSettle();

      expect(pages, <int?>[1, 2]);
      expect(tester.widget<Text>(find.byKey(kReplayListPageKey)).data, '第 2 页｜共 25 场（每页 20）');
      expect(tester.widget<TextButton>(find.byKey(kReplayListNextButtonKey)).onPressed, isNull);
      expect(tester.widget<TextButton>(find.byKey(kReplayListPrevButtonKey)).onPressed, isNotNull);
      expect(find.byKey(replayListRowKey('r-21')), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('行内「回放」：回调收到该行复盘', (WidgetTester tester) async {
      final List<Replay> opened = <Replay>[];
      await pumpReplayList(
        tester,
        list: ({String? keyword, DateTime? from, DateTime? to, int? page, int? size}) async =>
            replayPageOf(<Replay>[replayOf('r-1')]),
        onOpenReplay: (Replay replay) => opened.add(replay),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(replayListPlayKey('r-1')));
      await tester.pumpAndSettle();

      expect(opened.single.replayId, 'r-1');

      await unmount(tester);
    });

    testWidgets('未注入入口：行内不渲染「回放」按钮', (WidgetTester tester) async {
      await pumpReplayList(
        tester,
        list: ({String? keyword, DateTime? from, DateTime? to, int? page, int? size}) async =>
            replayPageOf(<Replay>[replayOf('r-1')]),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(replayListPlayKey('r-1')), findsNothing);
      expect(find.byKey(replayListExportKey('r-1')), findsNothing);
      expect(find.byKey(replayListRowKey('r-1')), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('行内「导出」：回调收到该行复盘', (WidgetTester tester) async {
      final List<Replay> opened = <Replay>[];
      await pumpReplayList(
        tester,
        list: ({String? keyword, DateTime? from, DateTime? to, int? page, int? size}) async =>
            replayPageOf(<Replay>[replayOf('r-1')]),
        onOpenExport: (Replay replay) => opened.add(replay),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(replayListExportKey('r-1')));
      await tester.pumpAndSettle();

      expect(opened.single.replayId, 'r-1');

      await unmount(tester);
    });

    testWidgets('复盘不存在（404 REPLAY_NOT_FOUND）：错误落到页面顶部', (WidgetTester tester) async {
      await pumpReplayList(
        tester,
        list: ({String? keyword, DateTime? from, DateTime? to, int? page, int? size}) async =>
            throw apiFailure(404, ApiErrorCode.replayNotFound),
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kReplayListErrorKey)).data,
        '复盘不存在（REPLAY_NOT_FOUND）',
      );

      await unmount(tester);
    });
  });

  /// 段 57：数据驱动回放器——轨迹分片按游标边拉边播，不画底图。
  group('复盘回放器（段 57）', () {
    testWidgets('首片：首次不传 cursor，状态给出采样点数与游标', (WidgetTester tester) async {
      final List<String?> cursors = <String?>[];
      await pumpReplayPlayer(
        tester,
        loadTimeline: ({String? cursor}) async {
          cursors.add(cursor);
          return TimelineChunk(
            samples: <TrackSample>[
              sampleOf(t: 0, vestNum: 1, xPct: 0.25, yPct: 0.75, hp: 100, status: VestStatus.live),
              sampleOf(t: 1000, vestNum: 1, xPct: 0.3, yPct: 0.7),
              sampleOf(t: 2000, vestNum: 2, xPct: 0.5, yPct: 0.5),
            ],
            nextCursor: 'c-2',
          );
        },
      );
      await tester.pumpAndSettle();

      expect(cursors, <String?>[null]);
      expect(tester.widget<Text>(find.byKey(kReplayPlayerAccountKey)).data, 'admin（OPERATOR）');
      expect(tester.widget<Text>(find.byKey(kReplayPlayerSummaryKey)).data, contains('想定A'));
      expect(
        tester.widget<Text>(find.byKey(kReplayPlayerStatusKey)).data,
        '已装载 3 个采样点｜游标 c-2',
      );
      // 时间轴是**相对开演的毫秒偏移**，不是时刻。
      expect(tester.widget<Text>(find.byKey(kReplayPlayerProgressKey)).data, '00:00.0 / 00:02.0');
      // 位置 0：只有 vest 1 的第一条采样（t=0）满足 t <= 0。
      expect(
        tester.widget<Text>(find.byKey(replayPlayerUnitKey(1))).data,
        '#1｜00:00.0｜室内 (0.250, 0.750)｜血量 100｜状态 LIVE',
      );
      expect(find.byKey(replayPlayerUnitKey(2)), findsNothing);
      expect(find.byKey(kReplayPlayerEndKey), findsNothing);

      await unmount(tester);
    });

    testWidgets('拖到某时刻：给出每个单元 t<=位置 的最新读数', (WidgetTester tester) async {
      await pumpReplayPlayer(
        tester,
        loadTimeline: ({String? cursor}) async => TimelineChunk(
          samples: <TrackSample>[
            sampleOf(t: 0, vestNum: 2, xPct: 0.1, yPct: 0.1, hp: 100),
            sampleOf(t: 0, vestNum: 1, xPct: 0.2, yPct: 0.2, hp: 100),
            sampleOf(t: 5000, vestNum: 1, xPct: 0.9, yPct: 0.9, hp: 40, status: VestStatus.die),
          ],
          nextCursor: null,
        ),
      );
      await tester.pumpAndSettle();

      final Slider slider = tester.widget<Slider>(find.byKey(kReplayPlayerSliderKey));
      slider.onChanged!(5000);
      await tester.pump();

      expect(tester.widget<Text>(find.byKey(kReplayPlayerProgressKey)).data, '00:05.0 / 00:05.0');
      expect(
        tester.widget<Text>(find.byKey(replayPlayerUnitKey(1))).data,
        '#1｜00:05.0｜室内 (0.900, 0.900)｜血量 40｜状态 DIE',
      );
      expect(
        tester.widget<Text>(find.byKey(replayPlayerUnitKey(2))).data,
        '#2｜00:00.0｜室内 (0.100, 0.100)｜血量 100｜状态 —',
      );

      await unmount(tester);
    });

    testWidgets('nextCursor 为 null 即末尾：显示末尾提示、「加载下一片」禁用',
        (WidgetTester tester) async {
      int calls = 0;
      await pumpReplayPlayer(
        tester,
        loadTimeline: ({String? cursor}) async {
          calls++;
          return TimelineChunk(
            samples: <TrackSample>[sampleOf(t: 0, vestNum: 1, xPct: 0.1, yPct: 0.1)],
            nextCursor: null,
          );
        },
      );
      await tester.pumpAndSettle();

      expect(calls, 1);
      expect(find.byKey(kReplayPlayerEndKey), findsOneWidget);
      expect(
        tester.widget<TextButton>(find.byKey(kReplayPlayerLoadMoreButtonKey)).onPressed,
        isNull,
      );

      await unmount(tester);
    });

    testWidgets('空片且末尾：显示空读数空态与末尾提示', (WidgetTester tester) async {
      await pumpReplayPlayer(tester);
      await tester.pumpAndSettle();

      expect(find.byKey(kReplayPlayerEmptyKey), findsOneWidget);
      expect(find.byKey(kReplayPlayerEndKey), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(kReplayPlayerProgressKey)).data, '00:00.0 / 00:00.0');

      await unmount(tester);
    });

    testWidgets('边拉边播：播到已装载末尾自动续拉下一片，拉到末尾自动停表',
        (WidgetTester tester) async {
      final List<String?> cursors = <String?>[];
      await pumpReplayPlayer(
        tester,
        loadTimeline: ({String? cursor}) async {
          cursors.add(cursor);
          if (cursor == null) {
            return TimelineChunk(
              samples: <TrackSample>[
                sampleOf(t: 0, vestNum: 1, xPct: 0.1, yPct: 0.1),
                sampleOf(t: 100, vestNum: 1, xPct: 0.2, yPct: 0.2),
              ],
              nextCursor: 'c-2',
            );
          }
          return TimelineChunk(
            samples: <TrackSample>[sampleOf(t: 200, vestNum: 1, xPct: 0.3, yPct: 0.3)],
            nextCursor: null,
          );
        },
      );
      await tester.pumpAndSettle();
      expect(cursors, <String?>[null]);

      await tester.tap(find.byKey(kReplayPlayerPlayButtonKey));
      await tester.pumpAndSettle();

      // 追到已装载末尾时就地续拉下一片（游标 c-2），拉到末尾（nextCursor null）才停。
      expect(cursors, <String?>[null, 'c-2']);
      expect(tester.widget<Text>(find.byKey(kReplayPlayerStatusKey)).data, '已装载 3 个采样点｜游标 —');
      expect(find.byKey(kReplayPlayerEndKey), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(kReplayPlayerProgressKey)).data, '00:00.2 / 00:00.2');
      expect(
        tester
            .widget<Text>(find.descendant(
              of: find.byKey(kReplayPlayerPlayButtonKey),
              matching: find.byType(Text),
            ))
            .data,
        '播放',
      );

      await unmount(tester);
    });

    testWidgets('拉取失败即停表：显示错误且不每拍重试', (WidgetTester tester) async {
      final List<String?> cursors = <String?>[];
      await pumpReplayPlayer(
        tester,
        loadTimeline: ({String? cursor}) async {
          cursors.add(cursor);
          if (cursor == null) {
            return TimelineChunk(
              samples: <TrackSample>[
                sampleOf(t: 0, vestNum: 1, xPct: 0.1, yPct: 0.1),
                sampleOf(t: 100, vestNum: 1, xPct: 0.2, yPct: 0.2),
              ],
              nextCursor: 'c-2',
            );
          }
          throw apiFailure(404, ApiErrorCode.replayNotFound);
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kReplayPlayerPlayButtonKey));
      await tester.pumpAndSettle();

      expect(cursors, <String?>[null, 'c-2']);
      expect(
        tester.widget<Text>(find.byKey(kReplayPlayerErrorKey)).data,
        '复盘不存在（REPLAY_NOT_FOUND）',
      );
      expect(
        tester
            .widget<Text>(find.descendant(
              of: find.byKey(kReplayPlayerPlayButtonKey),
              matching: find.byType(Text),
            ))
            .data,
        '播放',
      );

      // 停表后不再自动重试：再走几拍也不新增请求。
      await tester.pump(const Duration(seconds: 1));
      await tester.pump(const Duration(seconds: 1));
      expect(cursors, <String?>[null, 'c-2']);

      await unmount(tester);
    });

    test('坐标两组分列、不换算不翻转；偏移格式化为 mm:ss.S；缺失字段显示破折号', () {
      expect(describeSamplePosition(sampleOf(xPct: 0.25, yPct: 0.75)), '室内 (0.250, 0.750)');
      expect(
        describeSamplePosition(sampleOf(lng: 116.397, lat: 39.908)),
        '室外 (116.39700, 39.90800)',
      );
      expect(describeSamplePosition(sampleOf()), '无坐标');

      expect(formatReplayOffset(0), '00:00.0');
      expect(formatReplayOffset(90500), '01:30.5');
      expect(formatReplayOffset(3661500), '61:01.5');

      expect(
        describeSampleReading(
          sampleOf(t: 1500, vestNum: 7, xPct: 0.5, yPct: 0.5, hp: 88, status: VestStatus.live),
        ),
        '#7｜00:01.5｜室内 (0.500, 0.500)｜血量 88｜状态 LIVE',
      );
      expect(
        describeSampleReading(sampleOf(t: 1500, vestNum: 7)),
        '#7｜00:01.5｜无坐标｜血量 —｜状态 —',
      );
    });
  });

  /// 段 57：复盘统计面板——replay 域 statistics 端点的指挥端接线。
  group('复盘统计页（段 57）', () {
    testWidgets('已加载：四段如实渲染，采样段数留空即不传该键', (WidgetTester tester) async {
      final List<int?> buckets = <int?>[];
      await pumpReplayStatistics(
        tester,
        load: ({int? timelineBuckets}) async {
          buckets.add(timelineBuckets);
          return statsOf(
            'r-1',
            teams: <TeamSummary>[
              teamSummaryOf(Team.red, total: 12, live: 5, dead: 3, offline: 4, hitsDealt: 20),
            ],
            topShooters: <TopShooter>[
              shooterOf(vestNum: 3, name: 'admin', hitsDealt: 12, kills: 4),
            ],
            hitPartDistribution: <HitPartCount>[
              hitPartCountOf(hitPart: HitPart.head, count: 3),
            ],
            timeline: <TimelineBucket>[
              bucketOf(t: 1500, liveByTeam: <String, int>{'RED': 5, 'BLUE': 3}),
            ],
          );
        },
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kReplayStatisticsAccountKey)).data,
        'admin（OPERATOR）',
      );
      expect(
        tester.widget<Text>(find.byKey(kReplayStatisticsSummaryKey)).data,
        '想定A｜模式 —｜开始 2026-10-01T08:00:00.000Z｜时长 —｜参演 —｜命中 —｜阵亡 —',
      );
      expect(
        tester.widget<Text>(find.byKey(replayStatisticsTeamRowKey('RED'))).data,
        'RED｜总 12｜存活 5｜阵亡 3｜离线 4｜命中 20',
      );
      expect(
        tester.widget<Text>(find.byKey(replayStatisticsShooterRowKey(1))).data,
        '第 1 名｜admin｜编号 3｜命中 12｜击杀 4',
      );
      expect(tester.widget<Text>(find.byKey(replayStatisticsHitPartRowKey(0))).data, 'HEAD｜3');
      // 曲线的时间轴是**相对开演的毫秒偏移**（1500ms → 00:01.5），不是时刻。
      expect(
        tester.widget<Text>(find.byKey(replayStatisticsBucketRowKey(0))).data,
        '00:01.5｜RED 5 / BLUE 3',
      );
      // 留空即**不传该键**，由服务端走默认值。
      expect(buckets, <int?>[null]);

      await unmount(tester);
    });

    testWidgets('契约可选字段缺失：计数 / 排行 / 曲线显示破折号，不臆造 0', (WidgetTester tester) async {
      await pumpReplayStatistics(
        tester,
        load: ({int? timelineBuckets}) async => statsOf(
          'r-1',
          teams: <TeamSummary>[teamSummaryOf(Team.blue)],
          topShooters: <TopShooter>[shooterOf()],
          hitPartDistribution: <HitPartCount>[hitPartCountOf()],
          timeline: <TimelineBucket>[bucketOf()],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(replayStatisticsTeamRowKey('BLUE'))).data,
        'BLUE｜总 —｜存活 —｜阵亡 —｜离线 —｜命中 —',
      );
      expect(
        tester.widget<Text>(find.byKey(replayStatisticsShooterRowKey(1))).data,
        '第 1 名｜（未登记）｜编号 —｜命中 —｜击杀 —',
      );
      expect(tester.widget<Text>(find.byKey(replayStatisticsHitPartRowKey(0))).data, '—｜—');
      expect(tester.widget<Text>(find.byKey(replayStatisticsBucketRowKey(0))).data, '—｜—');

      await unmount(tester);
    });

    testWidgets('采样段数合法：随重载请求送出', (WidgetTester tester) async {
      final List<int?> buckets = <int?>[];
      await pumpReplayStatistics(
        tester,
        load: ({int? timelineBuckets}) async {
          buckets.add(timelineBuckets);
          return statsOf('r-1');
        },
      );
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(kReplayStatisticsBucketsKey), ' 60 ');
      await tester.tap(find.byKey(kReplayStatisticsReloadButtonKey));
      await tester.pumpAndSettle();

      expect(buckets, <int?>[null, 60]);
      expect(find.byKey(kReplayStatisticsFormErrorKey), findsNothing);

      await unmount(tester);
    });

    testWidgets('采样段数非整数：本地拦下、不发请求，且不清空已装载数据', (WidgetTester tester) async {
      int calls = 0;
      await pumpReplayStatistics(
        tester,
        load: ({int? timelineBuckets}) async {
          calls++;
          return statsOf('r-1', teams: <TeamSummary>[teamSummaryOf(Team.red, total: 2)]);
        },
      );
      await tester.pumpAndSettle();
      expect(calls, 1);

      await tester.enterText(find.byKey(kReplayStatisticsBucketsKey), 'abc');
      await tester.tap(find.byKey(kReplayStatisticsReloadButtonKey));
      await tester.pumpAndSettle();

      expect(calls, 1);
      expect(
        tester.widget<Text>(find.byKey(kReplayStatisticsFormErrorKey)).data,
        '采样段数需为 10..500 的整数（留空走服务端默认）',
      );
      expect(find.byKey(replayStatisticsTeamRowKey('RED')), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('采样段数越界（9 / 501）：本地拦下、不发请求', (WidgetTester tester) async {
      int calls = 0;
      await pumpReplayStatistics(
        tester,
        load: ({int? timelineBuckets}) async {
          calls++;
          return statsOf('r-1');
        },
      );
      await tester.pumpAndSettle();

      for (final String text in <String>['9', '501']) {
        await tester.enterText(find.byKey(kReplayStatisticsBucketsKey), text);
        await tester.tap(find.byKey(kReplayStatisticsReloadButtonKey));
        await tester.pumpAndSettle();
        expect(find.byKey(kReplayStatisticsFormErrorKey), findsOneWidget);
      }
      expect(calls, 1);

      await unmount(tester);
    });

    testWidgets('复盘不存在（404 REPLAY_NOT_FOUND）：映射为文案', (WidgetTester tester) async {
      await pumpReplayStatistics(
        tester,
        load: ({int? timelineBuckets}) async =>
            throw apiFailure(404, ApiErrorCode.replayNotFound),
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kReplayStatisticsErrorKey)).data,
        '复盘不存在（REPLAY_NOT_FOUND）',
      );

      await unmount(tester);
    });

    testWidgets('无权查看（403 AUTH_FORBIDDEN）：映射为文案', (WidgetTester tester) async {
      await pumpReplayStatistics(
        tester,
        load: ({int? timelineBuckets}) async =>
            throw apiFailure(403, ApiErrorCode.authForbidden),
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kReplayStatisticsErrorKey)).data,
        '当前账号无权查看该复盘（AUTH_FORBIDDEN）',
      );

      await unmount(tester);
    });

    testWidgets('实例未就绪（503 INSTANCE_NOT_READY）：映射为文案', (WidgetTester tester) async {
      await pumpReplayStatistics(
        tester,
        load: ({int? timelineBuckets}) async =>
            throw apiFailure(503, ApiErrorCode.instanceNotReady),
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kReplayStatisticsErrorKey)).data,
        '实例尚未就绪，请稍后重试（INSTANCE_NOT_READY）',
      );

      await unmount(tester);
    });

    testWidgets('四段皆空：各段分别显示空态', (WidgetTester tester) async {
      await pumpReplayStatistics(tester);
      await tester.pumpAndSettle();

      expect(find.byKey(kReplayStatisticsTeamsEmptyKey), findsOneWidget);
      expect(find.byKey(kReplayStatisticsShootersEmptyKey), findsOneWidget);
      expect(find.byKey(kReplayStatisticsHitPartsEmptyKey), findsOneWidget);
      expect(find.byKey(kReplayStatisticsTimelineEmptyKey), findsOneWidget);

      await unmount(tester);
    });
  });

  /// 段 57：复盘接线——汇总页「复盘」入口进入，列表 + 回放器挂同一条已认证传输。
  group('指挥端复盘接线（段 57）', () {
    testWidgets('「复盘」入口：进入列表页并按登录令牌拉第一页，行内「回放」进入回放器',
        (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-1',
              'expiresIn': 900,
              'refreshToken': 'refresh-1',
              'user': <String, dynamic>{
                'userId': 'u-1',
                'username': 'admin',
                'roles': <String>['OPERATOR'],
              },
            });
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'GET $kApiPrefix/replays':
            return jsonResponse(<String, dynamic>{
              'items': <Object>[
                <String, dynamic>{
                  'replayId': 'r-1',
                  'planName': '红方突袭',
                  'startedAt': '2026-10-01T08:00:00Z',
                  'battleMode': 'BATTLE',
                  'durationSeconds': 600,
                  'participantCount': 12,
                  'hitCount': 5,
                  'deadCount': 3,
                },
              ],
              'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
            });
          case 'GET $kApiPrefix/replays/r-1/timeline':
            return jsonResponse(<String, dynamic>{
              'samples': <Object>[
                <String, dynamic>{
                  't': 0,
                  'vestNum': 1,
                  'xPct': 0.25,
                  'yPct': 0.75,
                  'hp': 100,
                  'status': 'LIVE',
                },
              ],
              'nextCursor': null,
            });
        }
        fail('未预期的请求：${call.key}');
      };

      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);

      await tester.tap(find.byKey(kTeamSummaryReplaysEntryKey));
      await tester.pumpAndSettle();

      expect(find.byType(ReplayListPage), findsOneWidget);
      final ScriptedCall listCall = transport.calls
          .lastWhere((ScriptedCall call) => call.key == 'GET $kApiPrefix/replays');
      expect(listCall.authorization, 'Bearer access-1');
      expect(
        tester.widget<Text>(find.byKey(replayListRowKey('r-1'))).data,
        '红方突袭｜模式 BATTLE｜开始 2026-10-01T08:00:00.000Z｜时长 600s｜参演 12｜命中 5｜阵亡 3',
      );

      // 行内「回放」→ 回放器，按同一令牌拉该复盘第一片轨迹（cursor 不传）。
      await tester.tap(find.byKey(replayListPlayKey('r-1')));
      await tester.pumpAndSettle();

      expect(find.byType(ReplayPlayerPage), findsOneWidget);
      expect(
        transport.sequence,
        containsAllInOrder(<String>[
          'POST $kApiPrefix/auth/login',
          'GET $kApiPrefix/situation/teams/summary',
          'GET $kApiPrefix/replays',
          'GET $kApiPrefix/replays/r-1/timeline',
        ]),
      );
      final ScriptedCall timelineCall = transport.calls
          .lastWhere((ScriptedCall call) => call.key == 'GET $kApiPrefix/replays/r-1/timeline');
      expect(timelineCall.authorization, 'Bearer access-1');
      expect(find.byKey(kReplayPlayerEndKey), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('行内「统计」：按登录令牌拉该复盘统计（采样段数留空即不传）',
        (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-1',
              'expiresIn': 900,
              'refreshToken': 'refresh-1',
              'user': <String, dynamic>{
                'userId': 'u-1',
                'username': 'admin',
                'roles': <String>['OPERATOR'],
              },
            });
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'GET $kApiPrefix/replays':
            return jsonResponse(<String, dynamic>{
              'items': <Object>[
                <String, dynamic>{
                  'replayId': 'r-1',
                  'planName': '红方突袭',
                  'startedAt': '2026-10-01T08:00:00Z',
                },
              ],
              'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
            });
          case 'GET $kApiPrefix/replays/r-1/statistics':
            return jsonResponse(<String, dynamic>{
              'replayId': 'r-1',
              'teams': <Object>[
                <String, dynamic>{
                  'team': 'RED',
                  'total': 12,
                  'live': 5,
                  'dead': 3,
                  'offline': 4,
                  'hitsDealt': 20,
                },
              ],
              'topShooters': <Object>[
                <String, dynamic>{'vestNum': 3, 'name': 'admin', 'hitsDealt': 12, 'kills': 4},
              ],
              'hitPartDistribution': <Object>[
                <String, dynamic>{'hitPart': 'HEAD', 'count': 3},
              ],
              'timeline': <Object>[
                <String, dynamic>{
                  't': 1500,
                  'liveByTeam': <String, int>{'RED': 5, 'BLUE': 3},
                },
              ],
            });
        }
        fail('未预期的请求：${call.key}');
      };

      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);

      await tester.tap(find.byKey(kTeamSummaryReplaysEntryKey));
      await tester.pumpAndSettle();

      // 行内「统计」→ 统计面板，按同一令牌拉该复盘统计。
      await tester.tap(find.byKey(replayListStatisticsKey('r-1')));
      await tester.pumpAndSettle();

      expect(find.byType(ReplayStatisticsPage), findsOneWidget);
      final ScriptedCall statsCall = transport.calls.lastWhere(
        (ScriptedCall call) => call.key == 'GET $kApiPrefix/replays/r-1/statistics',
      );
      expect(statsCall.authorization, 'Bearer access-1');
      expect(
        tester.widget<Text>(find.byKey(replayStatisticsTeamRowKey('RED'))).data,
        'RED｜总 12｜存活 5｜阵亡 3｜离线 4｜命中 20',
      );
      expect(
        tester.widget<Text>(find.byKey(replayStatisticsBucketRowKey(0))).data,
        '00:01.5｜RED 5 / BLUE 3',
      );
      expect(
        transport.sequence,
        containsAllInOrder(<String>[
          'POST $kApiPrefix/auth/login',
          'GET $kApiPrefix/situation/teams/summary',
          'GET $kApiPrefix/replays',
          'GET $kApiPrefix/replays/r-1/statistics',
        ]),
      );

      await unmount(tester);
    });
  });

  /// 段 58：复盘导出面板——replay 域 exports 三端点（发起 202 / 轮询 / 下载）的指挥端接线。
  group('复盘导出页（段 58）', () {
    testWidgets('发起导出：携带所选格式，PENDING/RUNNING 期间 1s 轮询、SUCCEEDED 即停表',
        (WidgetTester tester) async {
      final List<ExportFormat> started = <ExportFormat>[];
      int pollCalls = 0;
      await pumpReplayExport(
        tester,
        startExport: (ExportFormat format) async {
          started.add(format);
          return asyncTaskOf(taskId: 't-9', state: AsyncTaskState.pending);
        },
        pollExport: (String taskId) async {
          pollCalls++;
          return switch (pollCalls) {
            1 => asyncTaskOf(taskId: 't-9', state: AsyncTaskState.running, progressPct: 40),
            _ => asyncTaskOf(
                taskId: 't-9',
                state: AsyncTaskState.succeeded,
                progressPct: 100,
                fileName: 'r-1.csv',
                downloadUrl: '/api/v1/replays/r-1/exports/t-9/download?sig=abc',
              ),
          };
        },
        saveBytes: (String filename, List<int> bytes, String? contentType) async {},
      );
      await tester.pumpAndSettle();

      expect(tester.widget<Text>(find.byKey(kReplayExportAccountKey)).data, 'admin（OPERATOR）');
      expect(find.byKey(kReplayExportFormatKey), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(kReplayExportStateKey)).data, '尚未发起导出');

      await tester.tap(find.byKey(kReplayExportStartButtonKey));
      await tester.pump();
      await tester.pump();

      // 默认格式 XLSX；固定 202 即 PENDING，起 1s 定时器。
      expect(started, <ExportFormat>[ExportFormat.xlsx]);
      expect(tester.widget<Text>(find.byKey(kReplayExportStateKey)).data, 'PENDING');

      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      expect(tester.widget<Text>(find.byKey(kReplayExportStateKey)).data, 'RUNNING｜进度 40%');

      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      expect(
        tester.widget<Text>(find.byKey(kReplayExportStateKey)).data,
        'SUCCEEDED｜进度 100%｜文件 r-1.csv',
      );
      expect(
        tester.widget<Text>(find.byKey(kReplayExportDownloadUrlKey)).data,
        '/api/v1/replays/r-1/exports/t-9/download?sig=abc',
      );
      expect(find.byKey(kReplayExportDownloadButtonKey), findsOneWidget);

      // 终态后再推进 5s 不应再有轮询。
      await tester.pump(const Duration(seconds: 5));
      await tester.pump();
      expect(pollCalls, 2);

      await unmount(tester);
    });

    testWidgets('格式可选 CSV：发起时携带所选格式', (WidgetTester tester) async {
      final List<ExportFormat> started = <ExportFormat>[];
      await pumpReplayExport(
        tester,
        startExport: (ExportFormat format) async {
          started.add(format);
          return asyncTaskOf(state: AsyncTaskState.succeeded, progressPct: 100);
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kReplayExportFormatKey));
      await tester.pumpAndSettle();
      await tester.tap(find.text('CSV').last);
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kReplayExportStartButtonKey));
      await tester.pumpAndSettle();

      expect(started, <ExportFormat>[ExportFormat.csv]);

      await unmount(tester);
    });

    testWidgets('下载：走 Bearer 端口取回字节并交落盘端口（文件名取服务端 fileName）',
        (WidgetTester tester) async {
      final List<String> taskIds = <String>[];
      final List<String> savedNames = <String>[];
      final List<List<int>> savedBytes = <List<int>>[];
      final List<String?> savedTypes = <String?>[];
      await pumpReplayExport(
        tester,
        startExport: (ExportFormat format) async => asyncTaskOf(
          taskId: 't-7',
          state: AsyncTaskState.succeeded,
          fileName: 'r-1.csv',
        ),
        downloadExport: (String taskId) async {
          taskIds.add(taskId);
          return ApiResponse(
            statusCode: 200,
            contentType: 'text/csv',
            body: const <int>[65, 66, 67],
          );
        },
        saveBytes: (String filename, List<int> bytes, String? contentType) async {
          savedNames.add(filename);
          savedBytes.add(bytes);
          savedTypes.add(contentType);
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kReplayExportStartButtonKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kReplayExportDownloadButtonKey));
      await tester.pumpAndSettle();

      expect(taskIds, <String>['t-7']);
      expect(savedNames, <String>['r-1.csv']);
      expect(savedBytes.single, <int>[65, 66, 67]);
      expect(savedTypes.single, 'text/csv');
      expect(find.text('已保存 r-1.csv'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('服务端缺 fileName：以本地格式回退命名产物', (WidgetTester tester) async {
      final List<String> savedNames = <String>[];
      await pumpReplayExport(
        tester,
        replay: replayOf('r-9'),
        startExport: (ExportFormat format) async => asyncTaskOf(state: AsyncTaskState.succeeded),
        saveBytes: (String filename, List<int> bytes, String? contentType) async =>
            savedNames.add(filename),
      );
      await tester.pumpAndSettle();

      // 默认格式 XLSX（未改动下拉）。
      await tester.tap(find.byKey(kReplayExportStartButtonKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kReplayExportDownloadButtonKey));
      await tester.pumpAndSettle();

      expect(savedNames, <String>['replay-r-9.xlsx']);

      await unmount(tester);
    });

    testWidgets('未注入落盘端口：SUCCEEDED 也不渲染「下载」按钮，但仍给出短时效 URL',
        (WidgetTester tester) async {
      await pumpReplayExport(
        tester,
        startExport: (ExportFormat format) async => asyncTaskOf(
          state: AsyncTaskState.succeeded,
          progressPct: 100,
          downloadUrl: '/api/v1/replays/r-1/exports/t-1/download?sig=x',
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kReplayExportStartButtonKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kReplayExportDownloadButtonKey), findsNothing);
      expect(
        tester.widget<Text>(find.byKey(kReplayExportDownloadUrlKey)).data,
        '/api/v1/replays/r-1/exports/t-1/download?sig=x',
      );

      await unmount(tester);
    });

    testWidgets('契约可选字段缺失：不臆造进度 / 文件名 / URL；FAILED 显示失败原因',
        (WidgetTester tester) async {
      await pumpReplayExport(
        tester,
        startExport: (ExportFormat format) async =>
            asyncTaskOf(state: AsyncTaskState.failed, errorMessage: '磁盘空间不足'),
        saveBytes: (String filename, List<int> bytes, String? contentType) async {},
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kReplayExportStartButtonKey));
      await tester.pumpAndSettle();

      expect(tester.widget<Text>(find.byKey(kReplayExportStateKey)).data, 'FAILED');
      expect(tester.widget<Text>(find.byKey(kReplayExportMessageKey)).data, '磁盘空间不足');
      expect(find.byKey(kReplayExportDownloadUrlKey), findsNothing);
      // FAILED 不是 SUCCEEDED，不给下载按钮。
      expect(find.byKey(kReplayExportDownloadButtonKey), findsNothing);

      await unmount(tester);
    });

    testWidgets('轮询失败即停表：错误落页面且不每拍重试', (WidgetTester tester) async {
      int pollCalls = 0;
      await pumpReplayExport(
        tester,
        startExport: (ExportFormat format) async =>
            asyncTaskOf(taskId: 't-3', state: AsyncTaskState.running),
        pollExport: (String taskId) async {
          pollCalls++;
          throw apiFailure(409, ApiErrorCode.replayExportFailed);
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kReplayExportStartButtonKey));
      await tester.pump();
      await tester.pump();
      expect(tester.widget<Text>(find.byKey(kReplayExportStateKey)).data, 'RUNNING');

      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      expect(pollCalls, 1);
      expect(
        tester.widget<Text>(find.byKey(kReplayExportErrorKey)).data,
        '导出失败或产物已过期，请重新发起导出（REPLAY_EXPORT_FAILED）',
      );

      // 停表后不再自动重试。
      await tester.pump(const Duration(seconds: 5));
      await tester.pump();
      expect(pollCalls, 1);

      await unmount(tester);
    });

    testWidgets('错误码映射为现场文案（409 导出失败 / 404 / 403 / 503）', (WidgetTester tester) async {
      final Map<String, String> cases = <String, String>{
        ApiErrorCode.replayExportFailed: '导出失败或产物已过期，请重新发起导出（REPLAY_EXPORT_FAILED）',
        ApiErrorCode.replayNotFound: '复盘不存在（REPLAY_NOT_FOUND）',
        ApiErrorCode.authForbidden: '当前账号无权导出该复盘（AUTH_FORBIDDEN）',
        ApiErrorCode.instanceNotReady: '实例尚未就绪，请稍后重试（INSTANCE_NOT_READY）',
      };
      for (final MapEntry<String, String> entry in cases.entries) {
        await pumpReplayExport(
          tester,
          startExport: (ExportFormat format) async => throw apiFailure(409, entry.key),
        );
        await tester.pumpAndSettle();

        await tester.tap(find.byKey(kReplayExportStartButtonKey));
        await tester.pumpAndSettle();

        expect(tester.widget<Text>(find.byKey(kReplayExportErrorKey)).data, entry.value);
      }

      await unmount(tester);
    });
  });

  /// 段 58：复盘导出接线——列表行内「导出」进入导出面板，发起导出带登录令牌与所选格式。
  group('指挥端复盘导出接线（段 58）', () {
    testWidgets('行内「导出」：进入导出页并按登录令牌发起导出（固定 202）', (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-1',
              'expiresIn': 900,
              'refreshToken': 'refresh-1',
              'user': <String, dynamic>{
                'userId': 'u-1',
                'username': 'admin',
                'roles': <String>['OPERATOR'],
              },
            });
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'GET $kApiPrefix/replays':
            return jsonResponse(<String, dynamic>{
              'items': <Object>[
                <String, dynamic>{
                  'replayId': 'r-1',
                  'planName': '红方突袭',
                  'startedAt': '2026-10-01T08:00:00Z',
                },
              ],
              'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
            });
          case 'POST $kApiPrefix/replays/r-1/exports':
            return jsonResponse(
              <String, dynamic>{
                'taskId': 't-1',
                'state': 'SUCCEEDED',
                'progressPct': 100,
                'fileName': 'r-1.xlsx',
              },
              statusCode: 202,
            );
        }
        fail('未预期的请求：${call.key}');
      };

      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);

      await tester.tap(find.byKey(kTeamSummaryReplaysEntryKey));
      await tester.pumpAndSettle();

      // 行内「导出」→ 导出面板。
      await tester.tap(find.byKey(replayListExportKey('r-1')));
      await tester.pumpAndSettle();

      expect(find.byType(ReplayExportPage), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(kReplayExportStateKey)).data, '尚未发起导出');

      // 发起导出：按同一令牌 POST，请求体只带 format（默认 XLSX）。
      await tester.tap(find.byKey(kReplayExportStartButtonKey));
      await tester.pumpAndSettle();

      final ScriptedCall exportCall = transport.calls
          .lastWhere((ScriptedCall call) => call.key == 'POST $kApiPrefix/replays/r-1/exports');
      expect(exportCall.authorization, 'Bearer access-1');
      expect(exportCall.body, <String, Object?>{'format': 'XLSX'});
      expect(
        transport.sequence,
        containsAllInOrder(<String>[
          'POST $kApiPrefix/auth/login',
          'GET $kApiPrefix/situation/teams/summary',
          'GET $kApiPrefix/replays',
          'POST $kApiPrefix/replays/r-1/exports',
        ]),
      );
      // 生产端未注入落盘端口，故不给「下载」按钮（产物走 downloadUrl）。
      expect(find.byKey(kReplayExportDownloadButtonKey), findsNothing);

      await unmount(tester);
    });
  });

  /// 段 59：想定导入 / 模板下载——exercise 域文件端点的指挥端接线。
  ///
  /// 「模板下载」（`GET /plans/template?format=`，二进制）+ 「导入想定」（`POST /plans/import`，
  /// multipart）两对端口皆注入闭包桩，**不发真实请求**；文件能力（选文件 / 落盘）由注入假件驱动，
  /// 缺一即不渲染对应入口（同段 53 `pickImage` / 段 58 `saveBytes` 口径）。
  group('想定模板与导入（段 59）', () {
    testWidgets('下载模板：按所选格式取二进制并交落盘端口（默认 XLSX）', (WidgetTester tester) async {
      final List<PlanTemplateFormat> requested = <PlanTemplateFormat>[];
      final List<String> savedNames = <String>[];
      final List<List<int>> savedBytes = <List<int>>[];
      final List<String?> savedTypes = <String?>[];
      await pumpPlans(
        tester,
        downloadTemplate: (PlanTemplateFormat format) async {
          requested.add(format);
          return ApiResponse(
            statusCode: 200,
            contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            body: const <int>[80, 75, 3, 4],
          );
        },
        saveBytes: (String filename, List<int> bytes, String? contentType) async {
          savedNames.add(filename);
          savedBytes.add(bytes);
          savedTypes.add(contentType);
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kPlanManagerTemplateButtonKey));
      await tester.pumpAndSettle();

      // 默认格式 XLSX；字节与 contentType 原样交落盘端口，本页不解码。
      expect(requested, <PlanTemplateFormat>[PlanTemplateFormat.xlsx]);
      expect(savedNames, <String>['plan-template.xlsx']);
      expect(savedBytes.single, <int>[80, 75, 3, 4]);
      expect(
        savedTypes.single,
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      );
      expect(find.text('已保存模板 plan-template.xlsx'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('模板格式切 CSV：请求携带 CSV 且落盘文件名 .csv', (WidgetTester tester) async {
      final List<PlanTemplateFormat> requested = <PlanTemplateFormat>[];
      final List<String> savedNames = <String>[];
      await pumpPlans(
        tester,
        downloadTemplate: (PlanTemplateFormat format) async {
          requested.add(format);
          return ApiResponse(statusCode: 200, contentType: 'text/csv', body: const <int>[97]);
        },
        saveBytes: (String filename, List<int> bytes, String? contentType) async =>
            savedNames.add(filename),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kPlanManagerTemplateFormatKey));
      await tester.pumpAndSettle();
      await tester.tap(find.text('CSV').last);
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kPlanManagerTemplateButtonKey));
      await tester.pumpAndSettle();

      expect(requested, <PlanTemplateFormat>[PlanTemplateFormat.csv]);
      expect(savedNames.single, 'plan-template.csv');

      await unmount(tester);
    });

    testWidgets('未注入落盘端口：不渲染「下载模板」（模板须落盘才有意义）', (WidgetTester tester) async {
      await pumpPlans(
        tester,
        downloadTemplate: (PlanTemplateFormat format) async => ApiResponse(
          statusCode: 200,
          contentType: 'application/octet-stream',
          body: const <int>[],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(kPlanManagerTemplateButtonKey), findsNothing);
      expect(find.byKey(kPlanManagerTemplateFormatKey), findsNothing);

      await unmount(tester);
    });

    testWidgets('导入想定：选文件后调导入端口（文件名 / 字节），并重拉列表 + 回执',
        (WidgetTester tester) async {
      final List<String> filenames = <String>[];
      final List<List<int>> contents = <List<int>>[];
      final List<String?> names = <String?>[];
      int listCalls = 0;
      await pumpPlans(
        tester,
        list: ({String? keyword}) async {
          listCalls++;
          return planPageOf(<Plan>[planOf('p-1', name: '红方突袭')]);
        },
        importPlan: (String filename, List<int> bytes, String? name) async {
          filenames.add(filename);
          contents.add(bytes);
          names.add(name);
          return planOf('p-9', name: '夜间行军');
        },
        pickFile: () async => pickedPlanFileOf(filename: 'roster.xlsx', bytes: const <int>[9, 8]),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kPlanManagerImportButtonKey));
      await tester.pumpAndSettle();

      expect(filenames, <String>['roster.xlsx']);
      expect(contents.single, <int>[9, 8]);
      // 不传 name 即由服务端取文件名（契约口径）。
      expect(names.single, isNull);
      // 首帧一次 + 导入后重拉一次。
      expect(listCalls, 2);
      expect(find.text('已导入想定「夜间行军」'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('未选择文件：不发导入请求，提示「未选择文件」', (WidgetTester tester) async {
      int importCalls = 0;
      int listCalls = 0;
      await pumpPlans(
        tester,
        list: ({String? keyword}) async {
          listCalls++;
          return planPageOf(<Plan>[]);
        },
        importPlan: (String filename, List<int> bytes, String? name) async {
          importCalls++;
          return planOf('p-9');
        },
        pickFile: () async => null,
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kPlanManagerImportButtonKey));
      await tester.pumpAndSettle();

      expect(importCalls, 0);
      // 未选择即止步：不重拉列表（只有首帧那次）。
      expect(listCalls, 1);
      expect(find.text('未选择文件'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('未注入选文件端口：不渲染「导入想定」', (WidgetTester tester) async {
      await pumpPlans(
        tester,
        importPlan: (String filename, List<int> bytes, String? name) async => planOf('p-9'),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(kPlanManagerImportButtonKey), findsNothing);

      await unmount(tester);
    });

    testWidgets('导入失败：模板整文件校验失败落页面顶部提示', (WidgetTester tester) async {
      await pumpPlans(
        tester,
        importPlan: (String filename, List<int> bytes, String? name) async =>
            throw apiFailure(400, ApiErrorCode.validationFailed),
        pickFile: () async => pickedPlanFileOf(),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kPlanManagerImportButtonKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kPlanManagerErrorKey)).data,
        '参数校验失败（VALIDATION_FAILED）',
      );

      await unmount(tester);
    });
  });

  /// 段 59：想定导入接线——从队伍汇总页进入想定管理页，文件能力未接入时的生产行为。
  group('指挥端想定导入接线（段 59）', () {
    testWidgets('想定管理页：接入选文件占位后「导入想定」在位，点击即「未选择文件」且不发请求',
        (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-1',
              'expiresIn': 900,
              'refreshToken': 'refresh-1',
              'user': <String, dynamic>{
                'userId': 'u-1',
                'username': 'admin',
                'roles': <String>['OPERATOR'],
              },
            });
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'GET $kApiPrefix/plans':
            return jsonResponse(<String, dynamic>{
              'items': <Object>[
                <String, dynamic>{
                  'planId': 'p-1',
                  'name': '红方突袭',
                  'createdAt': '2026-10-29T00:00:00Z',
                },
              ],
              'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
            });
        }
        fail('未预期的请求：${call.key}');
      };

      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);

      await tester.tap(find.byKey(kTeamSummaryPlansEntryKey));
      await tester.pumpAndSettle();

      // 生产端接了个「未选择」占位：入口在位；未注入落盘端口：不给「下载模板」。
      expect(find.byKey(kPlanManagerImportButtonKey), findsOneWidget);
      expect(find.byKey(kPlanManagerTemplateButtonKey), findsNothing);

      await tester.tap(find.byKey(kPlanManagerImportButtonKey));
      await tester.pumpAndSettle();

      expect(find.text('未选择文件'), findsOneWidget);
      // 未选择即止步：链路只到 login / summary / plans（汇总页另有周期轮询，故用有序包含），
      // 且**未发生任何导入 POST**。
      expect(
        transport.sequence,
        containsAllInOrder(<String>[
          'POST $kApiPrefix/auth/login',
          'GET $kApiPrefix/situation/teams/summary',
          'GET $kApiPrefix/plans',
        ]),
      );
      expect(
        transport.calls
            .where((ScriptedCall call) => call.key == 'POST $kApiPrefix/plans/import'),
        isEmpty,
      );

      await unmount(tester);
    });
  });

  /// 段 61：马甲台账页——device 域前 8 端点（台账 5 + 绑定闭环 3）的指挥端接线。
  group('马甲台账页（段 61）', () {
    testWidgets('加载台账：展示账号；空列表显示空态', (WidgetTester tester) async {
      int listCalls = 0;
      await pumpVests(tester, list: ({String? keyword}) async {
        listCalls++;
        return vestPageOf(<VestDevice>[]);
      });
      await tester.pumpAndSettle();

      expect(listCalls, 1);
      expect(tester.widget<Text>(find.byKey(kVestLedgerAccountKey)).data, 'admin（OPERATOR）');
      expect(find.byKey(kVestLedgerEmptyKey), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('行文本取契约字面量；缺字段如实显示破折号', (WidgetTester tester) async {
      await pumpVests(
        tester,
        list: ({String? keyword}) async => vestPageOf(<VestDevice>[
          vestOf(7, name: '张三', team: Team.red, simNo: '12345', bound: true, enabled: true),
          vestOf(9),
        ]),
      );
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(vestLedgerRowKey(7))).data,
        '编号 7｜姓名 张三｜阵营 RED｜SIM 12345｜绑定 已绑定｜启用 启用',
      );
      expect(
        tester.widget<Text>(find.byKey(vestLedgerRowKey(9))).data,
        '编号 9｜姓名 —｜阵营 —｜SIM —｜绑定 —｜启用 —',
      );

      await unmount(tester);
    });

    testWidgets('关键字搜索：非空传 keyword，空白不传（走服务端默认）', (WidgetTester tester) async {
      final List<String?> keywords = <String?>[];
      await pumpVests(tester, list: ({String? keyword}) async {
        keywords.add(keyword);
        return vestPageOf(<VestDevice>[]);
      });
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(kVestLedgerKeywordFieldKey), ' 张三 ');
      await tester.tap(find.byKey(kVestLedgerSearchButtonKey));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(kVestLedgerKeywordFieldKey), '   ');
      await tester.tap(find.byKey(kVestLedgerSearchButtonKey));
      await tester.pumpAndSettle();

      expect(keywords, <String?>[null, '张三', null]);

      await unmount(tester);
    });

    testWidgets('登记马甲：提交整体替换五键（姓名两端空白被裁）', (WidgetTester tester) async {
      final List<VestDeviceUpsertRequest> created = <VestDeviceUpsertRequest>[];
      await pumpVests(tester, create: (VestDeviceUpsertRequest request) async {
        created.add(request);
        return vestOf(request.vestNum);
      });
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kVestLedgerCreateButtonKey));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(kVestFormNumFieldKey), '7');
      await tester.enterText(find.byKey(kVestFormNameFieldKey), ' 张三 ');
      await tester.tap(find.byKey(kVestFormTeamKey));
      await tester.pumpAndSettle();
      await tester.tap(find.text('RED').last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kVestFormSaveKey));
      await tester.pumpAndSettle();

      expect(created.single.toJson(), <String, Object?>{
        'vestNum': 7,
        'name': '张三',
        'team': 'RED',
        'model': null,
        'remark': null,
      });
      expect(find.text('已登记马甲 7'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('编号非法：不发请求、对话框保留并显示行内错误', (WidgetTester tester) async {
      await pumpVests(tester);
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kVestLedgerCreateButtonKey));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(kVestFormNumFieldKey), '70000');
      await tester.tap(find.byKey(kVestFormSaveKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kVestFormErrorKey), findsOneWidget);
      expect(find.byKey(kVestFormSaveKey), findsOneWidget);

      await tester.tap(find.byKey(kVestFormCancelKey));
      await tester.pumpAndSettle();

      await unmount(tester);
    });

    testWidgets('姓名超 64 字符：不发请求、行内错误', (WidgetTester tester) async {
      await pumpVests(tester);
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kVestLedgerCreateButtonKey));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(kVestFormNumFieldKey), '7');
      await tester.enterText(
        find.byKey(kVestFormNameFieldKey),
        List<String>.filled(65, 'x').join(),
      );
      await tester.tap(find.byKey(kVestFormSaveKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kVestFormErrorKey), findsOneWidget);
      expect(find.byKey(kVestFormSaveKey), findsOneWidget);

      await tester.tap(find.byKey(kVestFormCancelKey));
      await tester.pumpAndSettle();

      await unmount(tester);
    });

    testWidgets('编辑马甲：快照预填、编号只读，仅改姓名仍提交全部五键', (WidgetTester tester) async {
      final List<VestDeviceUpsertRequest> updated = <VestDeviceUpsertRequest>[];
      await pumpVests(
        tester,
        list: ({String? keyword}) async => vestPageOf(<VestDevice>[
          vestOf(7, name: '旧名', team: Team.blue, model: 'V2', remark: '旧备注'),
        ]),
        update: (int vestNum, VestDeviceUpsertRequest request) async {
          updated.add(request);
          return vestOf(vestNum);
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(vestLedgerEditKey(7)));
      await tester.pumpAndSettle();

      // 快照预填：不动的字段必须原样出现在表单里，否则整体替换会误伤它们。
      expect(fieldText(tester, kVestFormNumFieldKey), '7');
      expect(tester.widget<TextField>(find.byKey(kVestFormNumFieldKey)).enabled, isFalse);
      expect(fieldText(tester, kVestFormNameFieldKey), '旧名');
      expect(
        tester.widget<DropdownButton<String>>(find.byKey(kVestFormTeamKey)).value,
        'BLUE',
      );

      await tester.enterText(find.byKey(kVestFormNameFieldKey), '新名');
      await tester.tap(find.byKey(kVestFormSaveKey));
      await tester.pumpAndSettle();

      expect(updated.single.toJson(), <String, Object?>{
        'vestNum': 7,
        'name': '新名',
        'team': 'BLUE',
        'model': 'V2',
        'remark': '旧备注',
      });

      await unmount(tester);
    });

    testWidgets('注销马甲：确认后调用注销端口并重拉列表', (WidgetTester tester) async {
      final List<int> removed = <int>[];
      int listCalls = 0;
      await pumpVests(
        tester,
        list: ({String? keyword}) async {
          listCalls++;
          return vestPageOf(<VestDevice>[vestOf(7, name: '张三')]);
        },
        remove: (int vestNum) async => removed.add(vestNum),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(vestLedgerDeleteKey(7)));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kVestConfirmKey));
      await tester.pumpAndSettle();

      expect(removed, <int>[7]);
      expect(listCalls, 2);
      expect(find.text('已注销马甲 7'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('未绑定行：给「绑定」（POST），提交密钥并重拉列表', (WidgetTester tester) async {
      final List<VestBindingRequest> bound = <VestBindingRequest>[];
      int listCalls = 0;
      await pumpVests(
        tester,
        list: ({String? keyword}) async {
          listCalls++;
          return vestPageOf(<VestDevice>[vestOf(7, name: '张三', bound: false)]);
        },
        bind: (int vestNum, VestBindingRequest request) async {
          bound.add(request);
          return vestOf(vestNum, bound: true);
        },
      );
      await tester.pumpAndSettle();

      expect(find.byKey(vestLedgerBindKey(7)), findsOneWidget);
      expect(find.byKey(vestLedgerRebindKey(7)), findsNothing);
      expect(find.byKey(vestLedgerUnbindKey(7)), findsNothing);

      await tester.tap(find.byKey(vestLedgerBindKey(7)));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(kVestBindingSecretFieldKey), 'secret-123');
      await tester.tap(find.byKey(kVestBindingSaveKey));
      await tester.pumpAndSettle();

      expect(bound.single.toJson(), <String, Object?>{'secret': 'secret-123'});
      expect(listCalls, 2);
      expect(find.text('已绑定马甲 7'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('已绑定行：给「换绑」（PUT），提交密钥并重拉列表', (WidgetTester tester) async {
      final List<VestBindingRequest> rebound = <VestBindingRequest>[];
      await pumpVests(
        tester,
        list: ({String? keyword}) async =>
            vestPageOf(<VestDevice>[vestOf(7, name: '张三', bound: true)]),
        rebind: (int vestNum, VestBindingRequest request) async {
          rebound.add(request);
          return vestOf(vestNum, bound: true);
        },
      );
      await tester.pumpAndSettle();

      expect(find.byKey(vestLedgerBindKey(7)), findsNothing);
      expect(find.byKey(vestLedgerRebindKey(7)), findsOneWidget);
      expect(find.byKey(vestLedgerUnbindKey(7)), findsNothing);

      await tester.tap(find.byKey(vestLedgerRebindKey(7)));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(kVestBindingSecretFieldKey), 'new-secret-1');
      await tester.tap(find.byKey(kVestBindingSaveKey));
      await tester.pumpAndSettle();

      expect(rebound.single.toJson(), <String, Object?>{'secret': 'new-secret-1'});
      expect(find.text('已换绑马甲 7'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('已绑定行：解绑确认后调用解绑端口并重拉列表', (WidgetTester tester) async {
      final List<int> unbound = <int>[];
      await pumpVests(
        tester,
        list: ({String? keyword}) async =>
            vestPageOf(<VestDevice>[vestOf(7, name: '张三', bound: true)]),
        unbind: (int vestNum) async {
          unbound.add(vestNum);
          return vestOf(vestNum, bound: false);
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(vestLedgerUnbindKey(7)));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kVestConfirmKey));
      await tester.pumpAndSettle();

      expect(unbound, <int>[7]);
      expect(find.text('已解绑马甲 7'), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('密钥不足 8 位：不发请求、对话框保留并显示行内错误', (WidgetTester tester) async {
      await pumpVests(
        tester,
        list: ({String? keyword}) async =>
            vestPageOf(<VestDevice>[vestOf(7, bound: false)]),
        bind: (int vestNum, VestBindingRequest request) async => fail('未预期的绑定'),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(vestLedgerBindKey(7)));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(kVestBindingSecretFieldKey), '1234567');
      await tester.tap(find.byKey(kVestBindingSaveKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kVestBindingErrorKey), findsOneWidget);
      expect(find.byKey(kVestBindingSaveKey), findsOneWidget);

      await tester.tap(find.byKey(kVestBindingCancelKey));
      await tester.pumpAndSettle();

      await unmount(tester);
    });

    testWidgets('未注入绑定端口：不渲染绑定 / 换绑 / 解绑，编辑与注销仍在',
        (WidgetTester tester) async {
      await pumpVests(
        tester,
        list: ({String? keyword}) async => vestPageOf(<VestDevice>[
          vestOf(7, name: '张三', bound: false),
          vestOf(8, name: '李四', bound: true),
        ]),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(vestLedgerBindKey(7)), findsNothing);
      expect(find.byKey(vestLedgerRebindKey(8)), findsNothing);
      expect(find.byKey(vestLedgerUnbindKey(8)), findsNothing);
      expect(find.byKey(vestLedgerEditKey(7)), findsOneWidget);
      expect(find.byKey(vestLedgerDeleteKey(8)), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('契约错误码映射：409 DEVICE_VEST_ALREADY_BOUND 落顶部提示',
        (WidgetTester tester) async {
      await pumpVests(
        tester,
        list: ({String? keyword}) async =>
            vestPageOf(<VestDevice>[vestOf(7, bound: false)]),
        bind: (int vestNum, VestBindingRequest request) async =>
            throw apiFailure(409, ApiErrorCode.deviceVestAlreadyBound),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(vestLedgerBindKey(7)));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(kVestBindingSecretFieldKey), 'secret-123');
      await tester.tap(find.byKey(kVestBindingSaveKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kVestLedgerErrorKey)).data,
        contains('已绑定'),
      );

      await unmount(tester);
    });
  });

  /// 段 61：指挥端装配接线——队伍汇总页「马甲」入口 → 马甲台账页。
  group('指挥端马甲台账接线（段 61）', () {
    testWidgets('汇总页「马甲」入口：进入马甲台账页并按登录令牌拉列表',
        (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-1',
              'expiresIn': 900,
              'refreshToken': 'refresh-1',
              'user': <String, dynamic>{
                'userId': 'u-1',
                'username': 'admin',
                'roles': <String>['OPERATOR'],
              },
            });
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'GET $kApiPrefix/devices/vests':
            return jsonResponse(<String, dynamic>{
              'items': <Object>[
                <String, dynamic>{
                  'vestNum': 7,
                  'name': '张三',
                  'bound': true,
                  'enabled': true,
                },
              ],
              'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
            });
        }
        fail('未预期的请求：${call.key}');
      };

      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);

      await tester.tap(find.byKey(kTeamSummaryVestsEntryKey));
      await tester.pumpAndSettle();

      expect(find.byType(VestLedgerPage), findsOneWidget);
      expect(
        transport.sequence,
        containsAllInOrder(<String>[
          'POST $kApiPrefix/auth/login',
          'GET $kApiPrefix/situation/teams/summary',
          'GET $kApiPrefix/devices/vests',
        ]),
      );
      final ScriptedCall listCall = transport.calls
          .lastWhere((ScriptedCall call) => call.key == 'GET $kApiPrefix/devices/vests');
      expect(listCall.authorization, 'Bearer access-1');
      expect(
        tester.widget<Text>(find.byKey(vestLedgerRowKey(7))).data,
        contains('张三'),
      );

      await unmount(tester);
    });

    testWidgets('未注入入口：汇总页不渲染「马甲」按钮', (WidgetTester tester) async {
      await pumpSummary(tester, () async => <TeamSummary>[]);

      expect(find.byKey(kTeamSummaryVestsEntryKey), findsNothing);

      await unmount(tester);
    });
  });

  /// 段 63：马甲台账页——device 域第 9 端点（单马甲链路状态 `getVestLink`）的指挥端接线。
  group('马甲台账页·单马甲链路（段 63）', () {
    testWidgets('未注入链路端口：行内不渲染「链路」按钮', (WidgetTester tester) async {
      await pumpVests(
        tester,
        list: ({String? keyword}) async => vestPageOf(<VestDevice>[vestOf(7)]),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(vestLedgerLinkKey(7)), findsNothing);

      await unmount(tester);
    });

    testWidgets('点击「链路」：按行编号拉取并弹只读对话框，展示契约字面量',
        (WidgetTester tester) async {
      final List<int> requested = <int>[];
      await pumpVests(
        tester,
        list: ({String? keyword}) async => vestPageOf(<VestDevice>[vestOf(7)]),
        link: (int vestNum) async {
          requested.add(vestNum);
          return vestLinkOf(
            7,
            online: true,
            lastHeartbeatAt: DateTime.utc(2026, 11, 4, 1, 2, 3),
            lastFrameType: 'ff03',
            framesTotal: 120,
            droppedTotal: 3,
          );
        },
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(vestLedgerLinkKey(7)));
      await tester.pumpAndSettle();

      expect(requested, <int>[7]);
      expect(tester.widget<Text>(find.byKey(kVestLinkTitleKey)).data, '马甲 7 链路状态');
      expect(
        tester.widget<Text>(find.byKey(kVestLinkBodyKey)).data,
        '编号 7｜在线 在线｜最后心跳 2026-11-04T01:02:03.000Z｜末帧 ff03｜收帧 120｜丢弃 3',
      );

      await tester.tap(find.byKey(kVestLinkCloseKey));
      await tester.pumpAndSettle();
      expect(find.byKey(kVestLinkDialogKey), findsNothing);

      await unmount(tester);
    });

    testWidgets('链路字段缺失：如实显示破折号，不臆造默认值', (WidgetTester tester) async {
      await pumpVests(
        tester,
        list: ({String? keyword}) async => vestPageOf(<VestDevice>[vestOf(9)]),
        link: (int vestNum) async => vestLinkOf(9),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(vestLedgerLinkKey(9)));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kVestLinkBodyKey)).data,
        '编号 9｜在线 —｜最后心跳 —｜末帧 —｜收帧 —｜丢弃 —',
      );

      await unmount(tester);
    });

    testWidgets('拉取失败：不弹对话框、不臆造数据，错误落页面顶部提示',
        (WidgetTester tester) async {
      await pumpVests(
        tester,
        list: ({String? keyword}) async => vestPageOf(<VestDevice>[vestOf(7)]),
        link: (int vestNum) async =>
            throw apiFailure(404, ApiErrorCode.deviceVestNotFound),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(vestLedgerLinkKey(7)));
      await tester.pumpAndSettle();

      expect(find.byKey(kVestLinkDialogKey), findsNothing);
      expect(
        tester.widget<Text>(find.byKey(kVestLedgerErrorKey)).data,
        contains(ApiErrorCode.deviceVestNotFound),
      );

      await unmount(tester);
    });
  });

  /// 段 63：指挥端装配接线——马甲台账行「链路」→ `GET /devices/vests/{n}/link`。
  group('指挥端马甲台账链路接线（段 63）', () {
    testWidgets('汇总页 → 马甲台账 → 行「链路」：按登录令牌拉取单马甲链路',
        (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-1',
              'expiresIn': 900,
              'refreshToken': 'refresh-1',
              'user': <String, dynamic>{
                'userId': 'u-1',
                'username': 'admin',
                'roles': <String>['OPERATOR'],
              },
            });
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'GET $kApiPrefix/devices/vests':
            return jsonResponse(<String, dynamic>{
              'items': <Object>[
                <String, dynamic>{
                  'vestNum': 7,
                  'name': '张三',
                  'bound': true,
                  'enabled': true,
                },
              ],
              'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
            });
          case 'GET $kApiPrefix/devices/vests/7/link':
            return jsonResponse(<String, dynamic>{
              'vestNum': 7,
              'online': true,
              'lastFrameType': 'ff03',
              'framesTotal': 5,
              'droppedTotal': 0,
            });
        }
        fail('未预期的请求：${call.key}');
      };

      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);

      await tester.tap(find.byKey(kTeamSummaryVestsEntryKey));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(vestLedgerLinkKey(7)));
      await tester.pumpAndSettle();

      final ScriptedCall linkCall = transport.calls.lastWhere(
        (ScriptedCall call) => call.key == 'GET $kApiPrefix/devices/vests/7/link',
      );
      expect(linkCall.authorization, 'Bearer access-1');
      expect(
        tester.widget<Text>(find.byKey(kVestLinkBodyKey)).data,
        '编号 7｜在线 在线｜最后心跳 —｜末帧 ff03｜收帧 5｜丢弃 0',
      );

      await unmount(tester);
    });
  });

  group('链路与基站页（段 62）', () {
    testWidgets('加载链路状态与基站台账并渲染契约字面量', (WidgetTester tester) async {
      await pumpLinks(
        tester,
        loadLinks: () async => <LinkStatus>[
          linkStatusOf(
            LinkKind.serialVest,
            state: LinkState.open,
            target: 'COM7',
            baudRate: 115200,
            framesTotal: 120,
            droppedTotal: 3,
            crcErrorTotal: 1,
          ),
          linkStatusOf(LinkKind.uwbTcpServer),
          linkStatusOf(LinkKind.uwbSerial, state: LinkState.error, errorMessage: '打开被拒'),
        ],
        loadAnchors: () async => <Anchor>[
          anchorOf(1, name: '角1', xPx: 0.1, yPx: 0.2, enabled: true),
        ],
      );

      final Text serial =
          tester.widget<Text>(find.byKey(linkStatusRowKey(LinkKind.serialVest)));
      expect(serial.data, contains('SERIAL_VEST'));
      expect(serial.data, contains('OPEN'));
      expect(serial.data, contains('COM7'));
      expect(serial.data, contains('帧 120'));
      expect(serial.data, contains('丢帧 3'));

      final Text uwbError =
          tester.widget<Text>(find.byKey(linkStatusRowKey(LinkKind.uwbSerial)));
      expect(uwbError.data, contains('ERROR'));
      expect(uwbError.data, contains('打开被拒'));

      final Text anchor = tester.widget<Text>(find.byKey(anchorRowKey(1)));
      expect(anchor.data, contains('角1'));
      expect(anchor.data, contains('0.1'));
      expect(anchor.data, contains('启用'));

      await unmount(tester);
    });

    testWidgets('没有基站时给出空态提示', (WidgetTester tester) async {
      await pumpLinks(tester);

      expect(find.byKey(kLinkPanelAnchorsEmptyKey), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('「刷新」重拉链路状态与基站台账', (WidgetTester tester) async {
      int linkCalls = 0;
      int anchorCalls = 0;
      await pumpLinks(
        tester,
        loadLinks: () async {
          linkCalls++;
          return <LinkStatus>[];
        },
        loadAnchors: () async {
          anchorCalls++;
          return <Anchor>[];
        },
      );
      expect(linkCalls, 1);
      expect(anchorCalls, 1);

      await tester.tap(find.byKey(kLinkPanelRefreshButtonKey));
      await tester.pumpAndSettle();

      expect(linkCalls, 2);
      expect(anchorCalls, 2);

      await unmount(tester);
    });

    testWidgets('开启串口链路：填端口后上行请求，留空字段不带上行，并按端口重拉',
        (WidgetTester tester) async {
      LinkOpenSerialRequest? sent;
      int linkCalls = 0;
      await pumpLinks(
        tester,
        loadLinks: () async {
          linkCalls++;
          return <LinkStatus>[];
        },
        loadPorts: () async => <SerialPortInfo>[
          portOf('COM7', description: 'USB 转串口'),
        ],
        openSerial: (LinkOpenSerialRequest request) async {
          sent = request;
          return linkStatusOf(LinkKind.serialVest,
              state: LinkState.open, target: request.portName);
        },
      );

      await tester.tap(find.byKey(kLinkSerialOpenKey));
      await tester.pumpAndSettle();

      // 对话框预填首个可用串口。
      expect(
        tester.widget<TextField>(find.byKey(kSerialLinkPortKey)).controller!.text,
        'COM7',
      );
      await tester.enterText(find.byKey(kSerialLinkPortKey), 'COM9');
      await tester.tap(find.byKey(kSerialLinkSaveKey));
      await tester.pumpAndSettle();

      expect(sent, isNotNull);
      expect(sent!.portName, 'COM9');
      expect(sent!.baudRate, isNull);
      expect(sent!.flowControlRtsCts, isNull);
      expect(sent!.toJson(), <String, Object?>{'portName': 'COM9'});
      expect(linkCalls, 2);

      await unmount(tester);
    });

    testWidgets('串口名为空：对话框内报错且不上行', (WidgetTester tester) async {
      await pumpLinks(
        tester,
        loadPorts: () async => <SerialPortInfo>[],
        openSerial: (LinkOpenSerialRequest request) async => fail('不应上行开启串口链路'),
      );

      await tester.tap(find.byKey(kLinkSerialOpenKey));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(kSerialLinkPortKey), '   ');
      await tester.tap(find.byKey(kSerialLinkSaveKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kSerialLinkErrorKey), findsOneWidget);
      expect(find.byKey(kSerialLinkPortKey), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('关闭串口链路：调用关闭端口并重拉（幂等）', (WidgetTester tester) async {
      int closeCalls = 0;
      int linkCalls = 0;
      await pumpLinks(
        tester,
        loadLinks: () async {
          linkCalls++;
          return <LinkStatus>[];
        },
        closeSerial: () async {
          closeCalls++;
          return linkStatusOf(LinkKind.serialVest);
        },
      );

      await tester.tap(find.byKey(kLinkSerialCloseKey));
      await tester.pumpAndSettle();

      expect(closeCalls, 1);
      expect(linkCalls, 2);

      await unmount(tester);
    });

    testWidgets('开启 UWB 链路：默认 TCP_SERVER，留空字段不带上行', (WidgetTester tester) async {
      LinkOpenUwbRequest? sent;
      await pumpLinks(
        tester,
        openUwb: (LinkOpenUwbRequest request) async {
          sent = request;
          return linkStatusOf(LinkKind.uwbTcpServer, state: LinkState.open);
        },
      );

      await tester.tap(find.byKey(kLinkUwbOpenKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kUwbLinkSaveKey));
      await tester.pumpAndSettle();

      expect(sent, isNotNull);
      expect(sent!.dataSource, UwbDataSource.tcpServer);
      expect(sent!.host, isNull);
      expect(sent!.port, isNull);
      expect(sent!.toJson(), <String, Object?>{'dataSource': 'TCP_SERVER'});

      await unmount(tester);
    });

    testWidgets('关闭 UWB 链路：调用关闭端口', (WidgetTester tester) async {
      int closeCalls = 0;
      await pumpLinks(
        tester,
        closeUwb: () async {
          closeCalls++;
          return linkStatusOf(LinkKind.uwbTcpServer);
        },
      );

      await tester.tap(find.byKey(kLinkUwbCloseKey));
      await tester.pumpAndSettle();

      expect(closeCalls, 1);

      await unmount(tester);
    });

    testWidgets('已开启再开（409 DEVICE_LINK_ALREADY_OPEN）按错误码提示',
        (WidgetTester tester) async {
      await pumpLinks(
        tester,
        openSerial: (LinkOpenSerialRequest request) async =>
            throw apiFailure(409, ApiErrorCode.deviceLinkAlreadyOpen),
      );

      await tester.tap(find.byKey(kLinkSerialOpenKey));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(kSerialLinkPortKey), 'COM7');
      await tester.tap(find.byKey(kSerialLinkSaveKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kLinkPanelErrorKey)).data,
        contains(ApiErrorCode.deviceLinkAlreadyOpen),
      );

      await unmount(tester);
    });

    testWidgets('串口占用（409 DEVICE_LINK_SERIAL_BUSY）按错误码提示',
        (WidgetTester tester) async {
      await pumpLinks(
        tester,
        openSerial: (LinkOpenSerialRequest request) async =>
            throw apiFailure(409, ApiErrorCode.deviceLinkSerialBusy),
      );

      await tester.tap(find.byKey(kLinkSerialOpenKey));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(kSerialLinkPortKey), 'COM7');
      await tester.tap(find.byKey(kSerialLinkSaveKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(kLinkPanelErrorKey)).data,
        contains(ApiErrorCode.deviceLinkSerialBusy),
      );

      await unmount(tester);
    });

    testWidgets('串口列表未读取显示占位；读取为空显示空态', (WidgetTester tester) async {
      await pumpLinks(tester, loadPorts: () async => <SerialPortInfo>[]);

      expect(find.text('尚未读取'), findsOneWidget);
      expect(find.byKey(kLinkPanelPortsEmptyKey), findsNothing);

      await tester.tap(find.byKey(kLinkSerialOpenKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kSerialLinkCancelKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kLinkPanelPortsEmptyKey), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('串口列表逐条渲染', (WidgetTester tester) async {
      await pumpLinks(
        tester,
        loadPorts: () async => <SerialPortInfo>[
          portOf('COM7', description: 'USB 转串口'),
          portOf('COM9'),
        ],
      );

      await tester.tap(find.byKey(kLinkSerialOpenKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(kSerialLinkCancelKey));
      await tester.pumpAndSettle();

      expect(
        tester.widget<Text>(find.byKey(serialPortRowKey(0))).data,
        contains('COM7'),
      );
      expect(
        tester.widget<Text>(find.byKey(serialPortRowKey(1))).data,
        contains('COM9'),
      );

      await unmount(tester);
    });

    testWidgets('新增基站：编号 + 请求体上行并按返回重拉', (WidgetTester tester) async {
      int? savedId;
      AnchorUpsertRequest? savedRequest;
      int anchorCalls = 0;
      await pumpLinks(
        tester,
        loadAnchors: () async {
          anchorCalls++;
          return <Anchor>[];
        },
        saveAnchor: (int anchorId, AnchorUpsertRequest request) async {
          savedId = anchorId;
          savedRequest = request;
          return anchorOf(anchorId, name: request.name);
        },
      );

      await tester.tap(find.byKey(kLinkPanelAnchorCreateKey));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(kAnchorFormIdFieldKey), '3');
      await tester.enterText(find.byKey(kAnchorFormNameFieldKey), '角3');
      await tester.enterText(find.byKey(kAnchorFormXFieldKey), '0.25');
      await tester.enterText(find.byKey(kAnchorFormYFieldKey), '0.75');
      await tester.tap(find.byKey(kAnchorFormSaveKey));
      await tester.pumpAndSettle();

      expect(savedId, 3);
      expect(savedRequest!.xPx, 0.25);
      expect(savedRequest!.yPx, 0.75);
      expect(savedRequest!.enabled, isTrue);
      expect(savedRequest!.toJson(), <String, Object?>{
        'name': '角3',
        'xPx': 0.25,
        'yPx': 0.75,
        'enabled': true,
      });
      expect(anchorCalls, 2);

      await unmount(tester);
    });

    testWidgets('编辑基站：编号只读并以快照预填四键', (WidgetTester tester) async {
      AnchorUpsertRequest? savedRequest;
      await pumpLinks(
        tester,
        loadAnchors: () async => <Anchor>[
          anchorOf(5, name: '角5', xPx: 0.4, yPx: 0.6, enabled: false),
        ],
        saveAnchor: (int anchorId, AnchorUpsertRequest request) async {
          savedRequest = request;
          return anchorOf(anchorId);
        },
      );

      await tester.tap(find.byKey(anchorEditKey(5)));
      await tester.pumpAndSettle();

      expect(
        tester.widget<TextField>(find.byKey(kAnchorFormIdFieldKey)).enabled,
        isFalse,
      );
      expect(
        tester.widget<TextField>(find.byKey(kAnchorFormNameFieldKey)).controller!.text,
        '角5',
      );

      await tester.tap(find.byKey(kAnchorFormSaveKey));
      await tester.pumpAndSettle();

      expect(savedRequest!.name, '角5');
      expect(savedRequest!.xPx, 0.4);
      expect(savedRequest!.yPx, 0.6);
      expect(savedRequest!.enabled, isFalse);

      await unmount(tester);
    });

    testWidgets('基站编号非法：对话框内报错且不上行', (WidgetTester tester) async {
      await pumpLinks(
        tester,
        saveAnchor: (int anchorId, AnchorUpsertRequest request) async => fail('不应上行配置基站'),
      );

      await tester.tap(find.byKey(kLinkPanelAnchorCreateKey));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(kAnchorFormIdFieldKey), '9');
      await tester.tap(find.byKey(kAnchorFormSaveKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kAnchorFormErrorKey), findsOneWidget);

      await unmount(tester);
    });

    testWidgets('基站坐标越界：对话框内报错且不上行', (WidgetTester tester) async {
      await pumpLinks(
        tester,
        saveAnchor: (int anchorId, AnchorUpsertRequest request) async => fail('不应上行配置基站'),
      );

      await tester.tap(find.byKey(kLinkPanelAnchorCreateKey));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(kAnchorFormIdFieldKey), '1');
      await tester.enterText(find.byKey(kAnchorFormXFieldKey), '1.5');
      await tester.tap(find.byKey(kAnchorFormSaveKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kAnchorFormErrorKey), findsOneWidget);

      await unmount(tester);
    });
  });

  group('指挥端链路与基站接线（段 62）', () {
    testWidgets('汇总页「链路」入口：进入链路与基站页并按登录令牌拉链路状态与基站',
        (WidgetTester tester) async {
      final ScriptedTransport transport = ScriptedTransport();
      transport.responder = (ScriptedCall call) async {
        switch (call.key) {
          case 'POST $kApiPrefix/auth/login':
            return jsonResponse(<String, dynamic>{
              'accessToken': 'access-1',
              'expiresIn': 900,
              'refreshToken': 'refresh-1',
              'user': <String, dynamic>{
                'userId': 'u-1',
                'username': 'admin',
                'roles': <String>['OPERATOR'],
              },
            });
          case 'GET $kApiPrefix/situation/teams/summary':
            return jsonResponse(<Object>[]);
          case 'GET $kApiPrefix/devices/links':
            return jsonResponse(<Object>[
              <String, dynamic>{'kind': 'SERIAL_VEST', 'state': 'OPEN', 'target': 'COM7'},
              <String, dynamic>{'kind': 'UWB_TCP_SERVER', 'state': 'CLOSED'},
              <String, dynamic>{'kind': 'UWB_SERIAL', 'state': 'CLOSED'},
            ]);
          case 'GET $kApiPrefix/devices/anchors':
            return jsonResponse(<Object>[
              <String, dynamic>{
                'anchorId': 1,
                'name': '角1',
                'xPx': 0.1,
                'yPx': 0.2,
                'enabled': true,
              },
            ]);
        }
        fail('未预期的请求：${call.key}');
      };

      await pumpConsole(tester, clientFactory: (ConnectionConfig config) => transport);
      await enter(tester, '127.0.0.1', '11001');
      await tapSave(tester);
      await enterCredentials(tester, 'admin', 'secret');
      await tapLogin(tester);

      await tester.tap(find.byKey(kTeamSummaryLinksEntryKey));
      await tester.pumpAndSettle();

      expect(find.byType(LinkPanelPage), findsOneWidget);
      expect(
        transport.sequence,
        containsAllInOrder(<String>[
          'POST $kApiPrefix/auth/login',
          'GET $kApiPrefix/situation/teams/summary',
          'GET $kApiPrefix/devices/links',
          'GET $kApiPrefix/devices/anchors',
        ]),
      );
      final ScriptedCall linksCall = transport.calls
          .lastWhere((ScriptedCall call) => call.key == 'GET $kApiPrefix/devices/links');
      expect(linksCall.authorization, 'Bearer access-1');
      expect(
        tester.widget<Text>(find.byKey(linkStatusRowKey(LinkKind.serialVest))).data,
        contains('COM7'),
      );

      await unmount(tester);
    });

    testWidgets('未注入入口：汇总页不渲染「链路」按钮', (WidgetTester tester) async {
      await pumpSummary(tester, () async => <TeamSummary>[]);

      expect(find.byKey(kTeamSummaryLinksEntryKey), findsNothing);

      await unmount(tester);
    });
  });
}

/// RES-D：脚本化传输桩——按请求回预置响应，并把**真实收到的请求**记下来。
///
/// 路径拼接借内层 [ApiClient]（基址取一个不会连接的端口）：测试里不必重抄一份
/// `/api/v1` 拼接规则，同时**不建连接、不发真实请求**。
/// 非 2xx 由用例以 `throw apiFailure(...)` 表达，与 [ApiClient] 的抛错形状一致。
class ScriptedTransport implements ApiTransport {
  ScriptedTransport() : _inner = ApiClient(baseUri: Uri.parse('http://127.0.0.1:1'));

  final ApiClient _inner;

  /// 已收到的请求（按发生顺序）。
  final List<ScriptedCall> calls = <ScriptedCall>[];

  /// 由用例覆写：按请求决定回什么；缺省回 200 空对象。
  Future<ApiResponse> Function(ScriptedCall call) responder =
      (ScriptedCall call) async => jsonResponse(<String, dynamic>{});

  /// 已收到的「方法 + 路径」序列（接线顺序的直接证据）。
  List<String> get sequence =>
      calls.map((ScriptedCall call) => call.key).toList(growable: false);

  @override
  Uri get baseUri => _inner.baseUri;

  @override
  Uri api(String path, [Map<String, String>? query]) => _inner.api(path, query);

  @override
  Uri resolve(String path, [Map<String, String>? query]) => _inner.resolve(path, query);

  @override
  Future<ApiResponse> get(String path, {Map<String, String>? query, String? bearerToken}) =>
      _record('GET', path, query: query, bearerToken: bearerToken);

  @override
  Future<ApiResponse> post(String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      _record('POST', path, query: query, jsonBody: jsonBody, bearerToken: bearerToken);

  @override
  Future<ApiResponse> put(String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      _record('PUT', path, query: query, jsonBody: jsonBody, bearerToken: bearerToken);

  @override
  Future<ApiResponse> delete(String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      _record('DELETE', path, query: query, jsonBody: jsonBody, bearerToken: bearerToken);

  @override
  Future<ApiResponse> send(String method, String path,
          {Map<String, String>? query, Object? jsonBody, String? bearerToken}) =>
      _record(method, path, query: query, jsonBody: jsonBody, bearerToken: bearerToken);

  /// 原始字节体（multipart）：桩只记路径与令牌，正文不参与指挥端接线断言。
  @override
  Future<ApiResponse> sendBytes(String method, String path,
          {Map<String, String>? query,
          required List<int> body,
          required String contentType,
          String? bearerToken}) =>
      _record(method, path, query: query, bearerToken: bearerToken);

  @override
  Future<ApiResponse> probe(String path) => _record('GET', path, absolute: true);

  @override
  void close() => _inner.close();

  Future<ApiResponse> _record(
    String method,
    String path, {
    Map<String, String>? query,
    Object? jsonBody,
    String? bearerToken,
    bool absolute = false,
  }) {
    final Uri uri = absolute ? _inner.resolve(path, query) : _inner.api(path, query);
    calls.add(ScriptedCall(
      method: method,
      path: uri.path,
      authorization: (bearerToken == null || bearerToken.isEmpty) ? null : 'Bearer $bearerToken',
      body: jsonBody,
    ));
    // `Future.sync` 把同步抛错也转成 future 错误——与 `ApiClient` 的抛错形状一致。
    return Future<ApiResponse>.sync(() => responder(calls.last));
  }
}

/// 脚本化传输桩收到的一次请求。
class ScriptedCall {
  const ScriptedCall({
    required this.method,
    required this.path,
    required this.authorization,
    this.body,
  });

  final String method;
  final String path;

  /// 已还原成 `Bearer <token>` 的授权头；未带令牌为 `null`。
  final String? authorization;
  final Object? body;

  /// 「方法 + 路径」，用于断言请求序列。
  String get key => '$method $path';
}

/// 契约形状的 JSON 响应（UTF-8 编码，与 `ApiClient` 解码头一致）。
ApiResponse jsonResponse(Object body, {int statusCode = 200}) => ApiResponse(
      statusCode: statusCode,
      contentType: 'application/json',
      body: utf8.encode(jsonEncode(body)),
    );

/// 段 D 后半：假态势 WS 连接——手动投喂下行帧、记录上行帧，**不建真实连接**。
class TestSituationSocket implements SituationSocket {
  // 单订阅控制器（与 `situation_feed_test.dart` 一致）：仅 `SituationFeed` 一个监听者。
  final StreamController<String> _controller = StreamController<String>();

  /// 客户端上行的文本帧。
  final List<String> sent = <String>[];

  int closeCallCount = 0;

  @override
  Stream<String> get messages => _controller.stream;

  @override
  void send(String text) => sent.add(text);

  @override
  int? get closeCode => null;

  @override
  String? get closeReason => null;

  @override
  Future<void> close([int? code, String? reason]) async {
    closeCallCount++;
    if (!_controller.isClosed) {
      await _controller.close();
    }
  }

  /// 投喂一帧下行文本。
  void emit(String raw) {
    if (!_controller.isClosed) {
      _controller.add(raw);
    }
  }
}

/// 假连接器：`silent=true` 时返回一个**永不落地**的 future（把用例按在「WS 未连」档）。
class TestSituationConnector {
  TestSituationConnector({this.silent = false});

  final bool silent;

  /// 每次连接携带的 `sinceRevision`（`null` = 取全量）。
  final List<int?> sinceRevisions = <int?>[];

  final List<TestSituationSocket> opened = <TestSituationSocket>[];

  TestSituationSocket get last => opened.last;

  Future<SituationSocket> call({int? sinceRevision}) {
    sinceRevisions.add(sinceRevision);
    if (silent) {
      return Completer<SituationSocket>().future;
    }
    final TestSituationSocket socket = TestSituationSocket();
    opened.add(socket);
    return Future<SituationSocket>.value(socket);
  }
}
