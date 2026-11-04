import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_client.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/device_api.dart';
import 'package:scds_client/shared/api/device_models.dart';
import 'package:scds_client/shared/api/situation_models.dart';

/// device 域契约测试：马甲台账、绑定闭环、单马甲链路、UWB 基站、链路状态与开关，共 17 个端点。
///
/// 与 `replay_api_test.dart` / `map_api_test.dart` 同口径——本机 `HttpServer` 打桩，断言
/// **真实 HTTP 行为**：路径（含 `{vestNum}` / `{anchorId}` 展开）、查询串（只带非 null 参数、
/// `team`/`status` 转字面量、`bound` 转 `true`/`false`）、`Bearer` 注入、请求体口径
/// （整体替换 vs 只带非 null），以及非 2xx → `ApiException` 的映射。
void main() {
  late HttpServer server;
  late ApiClient client;
  late DeviceApi api;
  late List<_Seen> seen;

  /// 由各用例覆写：默认回 200 空 JSON。
  late Future<void> Function(HttpRequest request) handler;

  setUp(() async {
    seen = <_Seen>[];
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((HttpRequest request) async {
      final List<int> body = await request.fold<List<int>>(
        <int>[],
        (List<int> acc, List<int> chunk) => acc..addAll(chunk),
      );
      seen.add(_Seen(
        method: request.method,
        uri: request.uri,
        authorization: request.headers.value(HttpHeaders.authorizationHeader),
        contentType: request.headers.contentType,
        body: body,
      ));
      await handler(request);
    });
    handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{});
    client = ApiClient(baseUri: Uri(scheme: 'http', host: '127.0.0.1', port: server.port));
    api = DeviceApi(client);
  });

  tearDown(() async {
    client.close();
    await server.close(force: true);
  });

  group('listVests（马甲台账列表）', () {
    test('带 keyword/team/status/bound/page/size：GET /api/v1/devices/vests，枚举转字面量', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[
              <String, dynamic>{
                'vestNum': 101,
                'name': '张三',
                'team': 'RED',
                'simNo': '4600012345678',
                'model': 'V2',
                'firmwareVersion': '1.2.3',
                'remark': '一排',
                'bound': true,
                'enabled': true,
                'lastSeenAt': '2026-10-30T01:00:00Z',
                'createdAt': '2026-01-01T00:00:00Z',
                'updatedAt': '2026-01-02T00:00:00Z',
              },
            ],
            'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
          });

      final VestDevicePage result = await api.listVests(
        token: 'access-1',
        keyword: '张',
        team: Team.red,
        status: VestStatus.live,
        bound: true,
        page: 1,
        size: 20,
      );

      final VestDevice vest = result.items.single;
      expect(vest.vestNum, 101);
      expect(vest.name, '张三');
      expect(vest.team, Team.red);
      expect(vest.simNo, '4600012345678');
      expect(vest.firmwareVersion, '1.2.3');
      expect(vest.bound, isTrue);
      expect(vest.enabled, isTrue);
      expect(vest.lastSeenAt, DateTime.utc(2026, 10, 30, 1));
      expect(vest.createdAt, isNotNull);
      expect(result.meta.total, 1);
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/devices/vests');
      expect(seen.single.uri.queryParameters, <String, String>{
        'keyword': '张',
        'team': 'RED',
        'status': 'LIVE',
        'bound': 'true',
        'page': '1',
        'size': '20',
      });
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('不带参数：不拼查询串（走服务端默认分页）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[],
            'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 0},
          });

      final VestDevicePage result = await api.listVests(token: 'access-1');

      expect(result.items, isEmpty);
      expect(seen.single.uri.path, '$kApiPrefix/devices/vests');
      expect(seen.single.uri.hasQuery, isFalse);
    });
  });

  group('马甲台账写与详情（createVest / getVest / updateVest / deleteVest）', () {
    test('createVest：POST /api/v1/devices/vests，请求体**恒输出全部键**', () async {
      handler = (HttpRequest request) =>
          respondJson(request, 201, _vestJson(num: 101, name: '张三'));

      final VestDevice created = await api.createVest(
        token: 'access-1',
        request: const VestDeviceUpsertRequest(
          vestNum: 101,
          name: '张三',
          team: Team.red,
          model: 'V2',
        ),
      );

      expect(created.vestNum, 101);
      expect(created.name, '张三');
      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/devices/vests');
      expect(seen.single.contentType?.mimeType, 'application/json');
      // 整体替换口径：remark 未传也要显式落成 null（不能靠「省略」表达）。
      expect(jsonDecode(utf8.decode(seen.single.body)), <String, dynamic>{
        'vestNum': 101,
        'name': '张三',
        'team': 'RED',
        'model': 'V2',
        'remark': null,
      });
    });

    test('getVest：GET /api/v1/devices/vests/{vestNum}', () async {
      handler = (HttpRequest request) =>
          respondJson(request, 200, _vestJson(num: 101, name: '张三', bound: true));

      final VestDevice vest = await api.getVest(token: 'access-1', vestNum: 101);

      expect(vest.vestNum, 101);
      expect(vest.bound, isTrue);
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/devices/vests/101');
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('updateVest：PUT /api/v1/devices/vests/{vestNum}，请求体整体替换', () async {
      handler = (HttpRequest request) =>
          respondJson(request, 200, _vestJson(num: 101, name: '李四'));

      await api.updateVest(
        token: 'access-1',
        vestNum: 101,
        request: const VestDeviceUpsertRequest(vestNum: 101, name: '李四'),
      );

      expect(seen.single.method, 'PUT');
      expect(seen.single.uri.path, '$kApiPrefix/devices/vests/101');
      expect(jsonDecode(utf8.decode(seen.single.body)), <String, dynamic>{
        'vestNum': 101,
        'name': '李四',
        'team': null,
        'model': null,
        'remark': null,
      });
    });

    test('deleteVest：DELETE /api/v1/devices/vests/{vestNum}，204 无响应体', () async {
      handler = (HttpRequest request) async {
        request.response.statusCode = 204;
        await request.response.close();
      };

      await api.deleteVest(token: 'access-1', vestNum: 101);

      expect(seen.single.method, 'DELETE');
      expect(seen.single.uri.path, '$kApiPrefix/devices/vests/101');
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('createVest 编号冲突：409 → ApiException(DEVICE_VEST_NUM_CONFLICT)', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            409,
            _problemJson(409, ApiErrorCode.deviceVestNumConflict, '/api/v1/devices/vests'),
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(
        api.createVest(
          token: 'access-1',
          request: const VestDeviceUpsertRequest(vestNum: 101),
        ),
      );

      expect(failure.statusCode, 409);
      expect(failure.code, ApiErrorCode.deviceVestNumConflict);
    });
  });

  group('绑定闭环（binding：POST 绑定 / PUT 换绑 / DELETE 解绑）', () {
    test('bindVest：POST .../binding，请求体只带明文密钥', () async {
      handler = (HttpRequest request) =>
          respondJson(request, 201, _vestJson(num: 101, name: '张三', bound: true));

      final VestDevice bound = await api.bindVest(
        token: 'access-1',
        vestNum: 101,
        request: const VestBindingRequest(secret: 'secret-1234'),
      );

      expect(bound.bound, isTrue);
      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/devices/vests/101/binding');
      expect(jsonDecode(utf8.decode(seen.single.body)), <String, dynamic>{
        'secret': 'secret-1234',
      });
    });

    test('rebindVest：PUT .../binding，换绑', () async {
      handler = (HttpRequest request) =>
          respondJson(request, 200, _vestJson(num: 101, name: '张三', bound: true));

      await api.rebindVest(
        token: 'access-1',
        vestNum: 101,
        request: const VestBindingRequest(secret: 'secret-5678'),
      );

      expect(seen.single.method, 'PUT');
      expect(seen.single.uri.path, '$kApiPrefix/devices/vests/101/binding');
      expect(jsonDecode(utf8.decode(seen.single.body)), <String, dynamic>{
        'secret': 'secret-5678',
      });
    });

    test('unbindVest：DELETE .../binding（幂等，回 VestDevice）', () async {
      handler = (HttpRequest request) =>
          respondJson(request, 200, _vestJson(num: 101, name: '张三', bound: false));

      final VestDevice unbound = await api.unbindVest(token: 'access-1', vestNum: 101);

      expect(unbound.bound, isFalse);
      expect(seen.single.method, 'DELETE');
      expect(seen.single.uri.path, '$kApiPrefix/devices/vests/101/binding');
      expect(seen.single.body, isEmpty);
    });

    test('bindVest 已绑定：409 → ApiException(DEVICE_VEST_ALREADY_BOUND)', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            409,
            _problemJson(
              409,
              ApiErrorCode.deviceVestAlreadyBound,
              '/api/v1/devices/vests/101/binding',
            ),
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(
        api.bindVest(
          token: 'access-1',
          vestNum: 101,
          request: const VestBindingRequest(secret: 'secret-1234'),
        ),
      );

      expect(failure.statusCode, 409);
      expect(failure.code, ApiErrorCode.deviceVestAlreadyBound);
    });
  });

  group('getVestLink（单马甲链路状态）', () {
    test('GET .../link，解析内联对象（在线判定由服务端给出）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'vestNum': 101,
            'online': true,
            'lastHeartbeatAt': '2026-10-30T01:00:05Z',
            'lastFrameType': 'ff03',
            'framesTotal': 1234,
            'droppedTotal': 5,
          });

      final VestLink link = await api.getVestLink(token: 'access-1', vestNum: 101);

      expect(link.vestNum, 101);
      expect(link.online, isTrue);
      expect(link.lastHeartbeatAt, DateTime.utc(2026, 10, 30, 1, 0, 5));
      expect(link.lastFrameType, 'ff03');
      expect(link.framesTotal, 1234);
      expect(link.droppedTotal, 5);
      expect(seen.single.uri.path, '$kApiPrefix/devices/vests/101/link');
    });
  });

  group('anchors（UWB 基站）', () {
    test('listAnchors：GET /api/v1/devices/anchors，数组解析（可空坐标）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <dynamic>[
            <String, dynamic>{
              'anchorId': 1,
              'name': 'bs1',
              'xPx': 0.1,
              'yPx': 0.2,
              'enabled': true,
            },
            <String, dynamic>{
              'anchorId': 5,
              'name': null,
              'xPx': null,
              'yPx': null,
              'enabled': false,
            },
          ]);

      final List<Anchor> anchors = await api.listAnchors(token: 'access-1');

      expect(anchors, hasLength(2));
      expect(anchors.first.anchorId, 1);
      expect(anchors.first.xPx, 0.1);
      expect(anchors.first.enabled, isTrue);
      expect(anchors.last.anchorId, 5);
      expect(anchors.last.xPx, isNull);
      expect(anchors.last.enabled, isFalse);
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/devices/anchors');
    });

    test('upsertAnchor：PUT /api/v1/devices/anchors/{anchorId}，请求体**恒输出全部键**', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'anchorId': 2,
            'name': 'bs2',
            'xPx': 0.3,
            'yPx': 0.4,
            'enabled': true,
          });

      final Anchor anchor = await api.upsertAnchor(
        token: 'access-1',
        anchorId: 2,
        request: const AnchorUpsertRequest(name: 'bs2', xPx: 0.3, yPx: 0.4, enabled: true),
      );

      expect(anchor.anchorId, 2);
      expect(anchor.xPx, 0.3);
      expect(seen.single.method, 'PUT');
      expect(seen.single.uri.path, '$kApiPrefix/devices/anchors/2');
      expect(jsonDecode(utf8.decode(seen.single.body)), <String, dynamic>{
        'name': 'bs2',
        'xPx': 0.3,
        'yPx': 0.4,
        'enabled': true,
      });
    });
  });

  group('links（链路状态与开关）', () {
    test('getLinks：GET /api/v1/devices/links，三种链路各一条', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <dynamic>[
            <String, dynamic>{
              'kind': 'SERIAL_VEST',
              'state': 'OPEN',
              'target': 'COM7',
              'baudRate': 115200,
              'framesTotal': 100,
              'droppedTotal': 1,
            },
            <String, dynamic>{
              'kind': 'UWB_TCP_SERVER',
              'state': 'CLOSED',
              'target': null,
              'fdCount': null,
              'framesTotal': 0,
              'droppedTotal': 0,
              'crcErrorTotal': 0,
            },
            <String, dynamic>{
              'kind': 'UWB_SERIAL',
              'state': 'ERROR',
              'errorMessage': '串口被占用',
            },
          ]);

      final List<LinkStatus> links = await api.getLinks(token: 'access-1');

      expect(links, hasLength(3));
      expect(links[0].kind, LinkKind.serialVest);
      expect(links[0].state, LinkState.open);
      expect(links[0].target, 'COM7');
      expect(links[0].baudRate, 115200);
      expect(links[1].kind, LinkKind.uwbTcpServer);
      expect(links[1].state, LinkState.closed);
      expect(links[2].kind, LinkKind.uwbSerial);
      expect(links[2].state, LinkState.error);
      expect(links[2].errorMessage, '串口被占用');
      expect(seen.single.uri.path, '$kApiPrefix/devices/links');
    });

    test('listSerialPorts：GET /api/v1/devices/links/serial-ports', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <dynamic>[
            <String, dynamic>{'portName': 'COM7', 'description': 'USB-SERIAL CH340'},
            <String, dynamic>{'portName': 'COM9', 'description': null},
          ]);

      final List<SerialPortInfo> ports = await api.listSerialPorts(token: 'access-1');

      expect(ports, hasLength(2));
      expect(ports.first.portName, 'COM7');
      expect(ports.first.description, 'USB-SERIAL CH340');
      expect(ports.last.description, isNull);
      expect(seen.single.uri.path, '$kApiPrefix/devices/links/serial-ports');
    });

    test('openVestSerial：POST .../links/serial，请求体只带非 null（省略即默认）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'kind': 'SERIAL_VEST',
            'state': 'OPENING',
            'target': 'COM7',
          });

      final LinkStatus status = await api.openVestSerial(
        token: 'access-1',
        request: const LinkOpenSerialRequest(portName: 'COM7'),
      );

      expect(status.kind, LinkKind.serialVest);
      expect(status.state, LinkState.opening);
      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/devices/links/serial');
      expect(jsonDecode(utf8.decode(seen.single.body)), <String, dynamic>{'portName': 'COM7'});
    });

    test('openVestSerial 带 baudRate/flowControlRtsCts：非 null 才进请求体', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'kind': 'SERIAL_VEST',
            'state': 'OPEN',
          });

      await api.openVestSerial(
        token: 'access-1',
        request: const LinkOpenSerialRequest(
          portName: 'COM7',
          baudRate: 9600,
          flowControlRtsCts: true,
        ),
      );

      expect(jsonDecode(utf8.decode(seen.single.body)), <String, dynamic>{
        'portName': 'COM7',
        'baudRate': 9600,
        'flowControlRtsCts': true,
      });
    });

    test('closeVestSerial：DELETE .../links/serial（幂等）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'kind': 'SERIAL_VEST',
            'state': 'CLOSED',
          });

      final LinkStatus status = await api.closeVestSerial(token: 'access-1');

      expect(status.state, LinkState.closed);
      expect(seen.single.method, 'DELETE');
      expect(seen.single.uri.path, '$kApiPrefix/devices/links/serial');
    });

    test('openUwbLink：POST .../links/uwb（TCP_SERVER，host/port 硬约束 12001）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'kind': 'UWB_TCP_SERVER',
            'state': 'OPENING',
            'target': '0.0.0.0:12001',
            'fdCount': 1,
          });

      final LinkStatus status = await api.openUwbLink(
        token: 'access-1',
        request: const LinkOpenUwbRequest(
          dataSource: UwbDataSource.tcpServer,
          host: '0.0.0.0',
          port: 12001,
        ),
      );

      expect(status.kind, LinkKind.uwbTcpServer);
      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/devices/links/uwb');
      expect(jsonDecode(utf8.decode(seen.single.body)), <String, dynamic>{
        'dataSource': 'TCP_SERVER',
        'host': '0.0.0.0',
        'port': 12001,
      });
    });

    test('openUwbLink：SERIAL 模式只带 dataSource 与 serialPortName', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'kind': 'UWB_SERIAL',
            'state': 'OPENING',
            'target': 'COM9',
          });

      await api.openUwbLink(
        token: 'access-1',
        request: const LinkOpenUwbRequest(
          dataSource: UwbDataSource.serial,
          serialPortName: 'COM9',
        ),
      );

      expect(jsonDecode(utf8.decode(seen.single.body)), <String, dynamic>{
        'dataSource': 'SERIAL',
        'serialPortName': 'COM9',
      });
    });

    test('openUwbLink 端口占用：409 → ApiException(DEVICE_LINK_SERIAL_BUSY)', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            409,
            _problemJson(409, ApiErrorCode.deviceLinkSerialBusy, '/api/v1/devices/links/uwb'),
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(
        api.openUwbLink(
          token: 'access-1',
          request: const LinkOpenUwbRequest(dataSource: UwbDataSource.tcpServer),
        ),
      );

      expect(failure.statusCode, 409);
      expect(failure.code, ApiErrorCode.deviceLinkSerialBusy);
    });

    test('closeUwbLink：DELETE .../links/uwb（幂等）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'kind': 'UWB_TCP_SERVER',
            'state': 'CLOSED',
          });

      final LinkStatus status = await api.closeUwbLink(token: 'access-1');

      expect(status.state, LinkState.closed);
      expect(seen.single.method, 'DELETE');
      expect(seen.single.uri.path, '$kApiPrefix/devices/links/uwb');
    });
  });
}

/// 台账行 JSON（契约 `VestDevice`），缺省字段一律写 `null` 以贴近真实响应。
Map<String, dynamic> _vestJson({required int num, String? name, bool? bound}) =>
    <String, dynamic>{
      'vestNum': num,
      'name': name,
      'team': 'RED',
      'simNo': null,
      'model': null,
      'firmwareVersion': null,
      'remark': null,
      'bound': bound,
      'enabled': true,
      'lastSeenAt': null,
      'createdAt': '2026-01-01T00:00:00Z',
      'updatedAt': '2026-01-02T00:00:00Z',
    };

/// RFC 7807 Problem JSON（`application/problem+json`），`status` / `code` / `traceId` 为必填。
Map<String, dynamic> _problemJson(int status, String code, String instance) =>
    <String, dynamic>{
      'type': 'about:blank',
      'title': 'Error',
      'status': status,
      'code': code,
      'detail': '测试用错误',
      'instance': instance,
      'traceId': 'trace-device-1',
    };

/// 打桩服务端记录到的请求快照（`HttpRequest` 在响应关闭后不可读，故先抄下来）。
class _Seen {
  _Seen({
    required this.method,
    required this.uri,
    required this.authorization,
    required this.contentType,
    required this.body,
  });

  final String method;
  final Uri uri;
  final String? authorization;
  final ContentType? contentType;
  final List<int> body;
}

Future<void> respondJson(
  HttpRequest request,
  int status,
  Object body, {
  String contentType = 'application/json',
}) async {
  // 必须显式声明 charset：HttpResponse 在无 charset 时按 latin1 编码，写中文会抛
  // 「Contains invalid characters」，表现为客户端等到超时。
  final List<String> parts = contentType.split('/');
  request.response
    ..statusCode = status
    ..headers.contentType = ContentType(parts[0], parts[1], charset: 'utf-8');
  request.response.add(utf8.encode(jsonEncode(body)));
  await request.response.close();
}

/// 捕获 [ApiException]；未抛出时以 `fail` 报出真实结果。
Future<ApiException> expectApiFailure(Future<Object?> request) async {
  try {
    final Object? result = await request;
    fail('应当抛出 ApiException，实际得到 $result');
  } on ApiException catch (failure) {
    return failure;
  }
}
