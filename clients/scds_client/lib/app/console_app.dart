import 'package:flutter/material.dart';

import '../features/connection/connection_page.dart';
import '../features/console/account_page.dart';
import '../features/console/command_page.dart';
import '../features/console/console_login_page.dart';
import '../features/console/overall_situation_page.dart';
import '../features/console/plan_manager_page.dart';
import '../features/console/team_summary_page.dart';
import '../shared/api/api_client.dart';
import '../shared/api/auth_api.dart';
import '../shared/api/auth_models.dart';
import '../shared/api/command_api.dart';
import '../shared/api/command_models.dart';
import '../shared/api/exercise_api.dart';
import '../shared/api/exercise_models.dart';
import '../shared/api/health_api.dart';
import '../shared/api/situation_api.dart';
import '../shared/auth/refreshing_api_transport.dart';
import '../shared/auth/session_store.dart';
import '../shared/config/connection_config.dart';
import '../shared/config/connection_config_store.dart';
import '../shared/state/situation_socket.dart';
import 'client_factory.dart';

/// 指挥端登录路由名：会话终结（[RefreshingApiTransport.onLost]）时据此**弹回登录页**。
const String kConsoleLoginRouteName = 'console/login';

/// 指挥端应用外壳（M6 CLIENT-WINDOWS 段 5；段 6 补账号会话页）——Windows 入口装配（D12）。
///
/// 装配点与单兵端同构（连接配置存储、会话存储、HTTP 客户端工厂），差别只在链路：
/// 连接配置页（**带健康诊断**）→ 账号登录页 → 队伍汇总总览页（AppBar 可进账号与会话页）。
///
/// 账号令牌走 `AccountSession`（`accessToken`），单兵端走 `DeviceSession`——
/// 两套会话在 [SessionStore] 里分开存，互不覆盖。
class ScdsConsoleApp extends StatefulWidget {
  const ScdsConsoleApp({
    super.key,
    required this.configStore,
    required this.sessionStore,
    this.clientFactory = httpApiClient,
  });

  /// 连接配置存储端口。当前为内存实现（不落盘）。
  final ConnectionConfigStore configStore;

  /// 会话存储端口（账号 / 设备两类）。当前为内存实现（不落盘）。
  final SessionStore sessionStore;

  /// HTTP 客户端工厂；换实例即换客户端。
  ///
  /// 类型是窄端口 [ApiTransport] 而非具体 `ApiClient`——生产用 `httpApiClient`（真实 `dart:io`
  /// 连接），测试可注入脚本化传输桩，**不建连接也不发真实请求**。
  final ApiTransport Function(ConnectionConfig config) clientFactory;

  @override
  State<ScdsConsoleApp> createState() => _ScdsConsoleAppState();
}

class _ScdsConsoleAppState extends State<ScdsConsoleApp> {
  ApiTransport? _client;

  /// 当前装配的账号会话（登录时置入、续期时整体替换）。
  ///
  /// 队伍汇总的拉取闭包**每次取用时读这个字段**，续期换到的新 `accessToken`
  /// 才会真正生效；退出时清的是 [SessionStore]（本地会话），本字段留给下一次登录覆盖。
  AccountSession? _account;

  /// 换实例时释放旧客户端（连接池随配置替换一起关闭）。
  ApiTransport _clientFor(ConnectionConfig config) {
    _client?.close();
    return _client = widget.clientFactory(config);
  }

  /// 诊断用**一次性客户端**：探完即关，不顶掉业务链路正在用的 [_client]。
  Future<HealthReport> _probe(ConnectionConfig config) async {
    final ApiTransport probeClient = widget.clientFactory(config);
    try {
      return await InstanceHealthApi(probeClient).probe();
    } finally {
      probeClient.close();
    }
  }

  /// 建业务传输：把 HTTP 客户端包进认证单飞装饰器（M6 CLIENT-RESILIENCE 段 C/D）。
  ///
  /// auth 域与 situation 域客户端都挂它下面，「401 到期 → 刷新 → 重试一次」对整条链路一致生效，
  /// 页面**不必自己拼令牌、也不必自己合并续期结果**：
  /// - [RefreshingApiTransport.onRefreshed]：续期成功 → 写回 [SessionStore]（持久化口径）
  ///   并替换 [_account]（进程内后续请求取新令牌的口径）；
  /// - [RefreshingApiTransport.onLost]：会话终结（刷新被 401 拒绝）→ 清本地会话并弹回登录页；
  ///   留在页上只会误导现场。
  RefreshingApiTransport _transportFor(ApiTransport client, NavigatorState navigator) =>
      RefreshingApiTransport(
        inner: client,
        onRefreshed: (AccountSession session) async {
          _account = session;
          await widget.sessionStore.writeAccount(session);
        },
        onLost: () async {
          _account = null;
          await widget.sessionStore.clearAll();
          _returnToLogin(navigator);
        },
      );

  /// 弹回登录路由。用 `popUntil` 而非「按层数 pop」：不管当前叠了几层页面都停在登录页；
  /// 登录路由不在栈内（或已在登录页）时停在栈底。
  void _returnToLogin(NavigatorState navigator) {
    if (!mounted) {
      return;
    }
    navigator.popUntil(
      (Route<dynamic> route) => route.isFirst || route.settings.name == kConsoleLoginRouteName,
    );
  }

  @override
  void dispose() {
    _client?.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SCDS 指挥端',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo)),
      home: Builder(
        builder: (BuildContext context) {
          final NavigatorState navigator = Navigator.of(context);
          return ConnectionPage(
            store: widget.configStore,
            probe: _probe,
            onConnected: (ConnectionConfig config) {
              final ApiTransport client = _clientFor(config);
              final RefreshingApiTransport transport = _transportFor(client, navigator);
              final AuthApi auth = AuthApi(transport);
              final SituationApi situations = SituationApi(transport);
              final CommandApi command = CommandApi(transport);
              final ExerciseApi exercises = ExerciseApi(transport);

              navigator.push(
                MaterialPageRoute<void>(
                  settings: const RouteSettings(name: kConsoleLoginRouteName),
                  builder: (BuildContext pageContext) => ConsoleLoginPage(
                    login: ({required String username, required String password}) async {
                      final TokenPair pair = await auth.login(
                        username: username,
                        password: password,
                      );
                      final AccountSession session = AccountSession.fromTokenPair(pair);
                      await widget.sessionStore.writeAccount(session);
                      return session;
                    },
                    onLoggedIn: (AccountSession session) {
                      _account = session;
                      // 登录成功即把会话接进装饰器：后续域请求的 401 才有令牌可续。
                      transport.adopt(session);
                      // WS 基址取自本实例的传输端口（`http://host:port` → `ws://host:port`）。
                      final Uri baseUri = client.baseUri;
                      navigator.push(
                        MaterialPageRoute<void>(
                          builder: (BuildContext pageContext) => TeamSummaryPage(
                            session: session,
                            fetch: () => situations.getTeamSummary(
                              token: _account!.accessToken,
                            ),
                            onOpenAccount: () => _openAccount(
                              navigator: navigator,
                              transport: transport,
                              auth: auth,
                            ),
                            onOpenSituation: () => _openOverallSituation(
                              navigator: navigator,
                              baseUri: baseUri,
                              situations: situations,
                              command: command,
                            ),
                            onOpenPlans: () => _openPlans(
                              navigator: navigator,
                              exercises: exercises,
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                ),
              );
            },
          );
        },
      ),
    );
  }

  /// 段 6 + RES-D：打开账号与会话页（续期 / 身份重载 / 退出）。
  ///
  /// 三个端口都**委托给同一条 [RefreshingApiTransport]**：
  /// - 续期按钮 → [RefreshingApiTransport.refreshNow]，与自动续期共用单飞闸
  ///   （契约的刷新是滚动的，重放旧 `refreshToken` 会撤销该账号全部 `refreshToken`）；
  /// - 身份重载 / 退出 → 走 `AuthApi(transport)`，令牌到期时自动续期并重试一次。
  ///
  /// 续期的落盘（`SessionStore` / [_account]）与合并（保留原身份）已由装饰器统一处理，
  /// 本方法**不再自己合并会话**；退出时把装饰器里的会话一并作废（[forget]），
  /// 免得本地会话清了、装饰器还攥着旧令牌发请求。
  void _openAccount({
    required NavigatorState navigator,
    required RefreshingApiTransport transport,
    required AuthApi auth,
  }) {
    final AccountSession? current = _account;
    if (current == null) {
      return;
    }

    navigator.push(
      MaterialPageRoute<void>(
        builder: (BuildContext pageContext) => AccountPage(
          session: current,
          refresh: (AccountSession _) => transport.refreshNow(),
          reload: (AccountSession session) => auth.me(accessToken: session.accessToken),
          logout: (AccountSession session) => auth.logout(accessToken: session.accessToken),
          clearSession: () async {
            _account = null;
            transport.forget();
            await widget.sessionStore.clearAll();
          },
          onLoggedOut: () {
            // 退出后回到登录页：弹出账号页与队伍汇总页（两层）。
            if (navigator.canPop()) {
              navigator.pop();
            }
            if (navigator.canPop()) {
              navigator.pop();
            }
          },
        ),
      ),
    );
  }

  /// RES-D 后半：打开「全体态势」页，把 `SituationFeed` 的两个端口接到当前账号令牌上。
  ///
  /// - `fetch` → `GET /api/v1/situation`（HTTP 兜底轮询，走已认证的 [SituationApi]）；
  /// - `connect` → `ws(s)://{host}:11001/ws/v1/situation`（WS 主通道）。
  ///
  /// 两者**每次取用时读 [_account]** 的当前 `accessToken`（续期换令牌后自动生效）。
  /// 页面自建 `SituationFeed` 并在其 `dispose` 时释放——缓存随页面生灭，不跨会话泄漏。
  ///
  /// 注：契约（`docs/design/03-auth.md` Q7 / §4.7）规定 WS 握手**只认账号令牌**且
  /// 令牌走 `Authorization: Bearer` 头，故此处只能由指挥端（账号令牌）接线。
  void _openOverallSituation({
    required NavigatorState navigator,
    required Uri baseUri,
    required SituationApi situations,
    required CommandApi command,
  }) {
    navigator.push(
      MaterialPageRoute<void>(
        builder: (BuildContext pageContext) => OverallSituationPage(
          session: _account!,
          fetch: ({int? sinceRevision}) => situations.getSituation(
            token: _account!.accessToken,
            sinceRevision: sinceRevision,
          ),
          connect: ({int? sinceRevision}) => connectSituationSocket(
            baseUri: baseUri,
            accessToken: _account!.accessToken,
            sinceRevision: sinceRevision,
          ),
          onCommand: (int vestNum) =>
              _openCommand(navigator: navigator, command: command, vestNum: vestNum),
          reloadAll: (ReloadRequest request) =>
              command.reloadAllVests(request: request, token: _account!.accessToken),
        ),
      ),
    );
  }

  /// 段 47：打开单兵指挥指令页（从「全体态势」页所选单元行进入，目标编号不手输）。
  ///
  /// 六个端口都是闭包，**每次取用时读 [_account]** 的当前 `accessToken`
  /// （续期换令牌后自动生效）；全部走同一条 [RefreshingApiTransport]，
  /// 令牌到期时自动续期并重试一次。
  void _openCommand({
    required NavigatorState navigator,
    required CommandApi command,
    required int vestNum,
  }) {
    final AccountSession? session = _account;
    if (session == null) {
      return;
    }

    navigator.push(
      MaterialPageRoute<void>(
        builder: (BuildContext pageContext) => CommandPage(
          vestNum: vestNum,
          session: session,
          kill: () => command.killVest(vestNum: vestNum, token: _account!.accessToken),
          revive: () => command.reviveVest(vestNum: vestNum, token: _account!.accessToken),
          injure: () => command.injureVest(vestNum: vestNum, token: _account!.accessToken),
          outOfRange: () =>
              command.outOfRangeVest(vestNum: vestNum, token: _account!.accessToken),
          reload: (ReloadRequest request) => command.reloadVest(
            vestNum: vestNum,
            request: request,
            token: _account!.accessToken,
          ),
          setConfig: (VestConfigRequest request) => command.setVestConfig(
            vestNum: vestNum,
            request: request,
            token: _account!.accessToken,
          ),
        ),
      ),
    );
  }

  /// 段 49：打开想定管理页（列表 / 新建 / 编辑 / 删除）。
  ///
  /// 四个端口都是闭包，**每次取用时读 [_account]** 的当前 `accessToken`
  /// （续期换令牌后自动生效）；全部走同一条 [RefreshingApiTransport]，
  /// 令牌到期时自动续期并重试一次。
  ///
  /// 注：修改想定是**整体替换**（`PlanUpsertRequest` 显式输出全部键），
  /// 页面以服务端回传快照预填表单，故这里只管透传请求体。
  void _openPlans({
    required NavigatorState navigator,
    required ExerciseApi exercises,
  }) {
    final AccountSession? session = _account;
    if (session == null) {
      return;
    }

    navigator.push(
      MaterialPageRoute<void>(
        builder: (BuildContext pageContext) => PlanManagerPage(
          session: session,
          list: ({String? keyword}) => exercises.listPlans(
            token: _account!.accessToken,
            keyword: keyword,
          ),
          create: (PlanUpsertRequest request) => exercises.createPlan(
            token: _account!.accessToken,
            request: request,
          ),
          update: (String planId, PlanUpsertRequest request) => exercises.updatePlan(
            token: _account!.accessToken,
            planId: planId,
            request: request,
          ),
          remove: (String planId) => exercises.deletePlan(
            token: _account!.accessToken,
            planId: planId,
          ),
        ),
      ),
    );
  }
}
