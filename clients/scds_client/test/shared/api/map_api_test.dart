import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_client.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/api/api_problem.dart';
import 'package:scds_client/shared/api/map_api.dart';
import 'package:scds_client/shared/api/map_models.dart';

/// map 域契约测试：底图 CRUD、底图图片（multipart / 二进制）、楼层点位表、UWB 标定
/// （坐标参数 + 中心基准校准），共 14 个端点。
///
/// 与 `exercise_api_test.dart` 同口径——本机 `HttpServer` 打桩，断言**真实 HTTP 行为**：
/// 路径（含 `{mapId}` / `{floorNum}` 展开）、查询串（只带非 null 参数）、`Bearer` 注入、
/// 请求体口径（**整体替换 vs 可选缺省**），以及非 2xx → `ApiException` 的映射。
void main() {
  late HttpServer server;
  late ApiClient client;
  late MapApi api;
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
    api = MapApi(client);
  });

  tearDown(() async {
    client.close();
    await server.close(force: true);
  });

  group('listMaps（底图列表）', () {
    test('带 type/page/size：GET /api/v1/maps，回 items 与 meta', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[
              <String, dynamic>{
                'mapId': 'map-3',
                'name': '三号楼二层',
                'type': 'INDOOR',
                'imageUrl': '/api/v1/files?path=maps/map-3/a1.png&exp=1793000000&signature=AbC',
                'imageWidth': 4096,
                'imageHeight': 3072,
                'floorCount': 2,
                'calibrated': true,
                'calibratedAt': '2026-10-25T06:00:00Z',
                'createdAt': '2026-10-01T02:00:00Z',
                'updatedAt': '2026-10-25T06:00:00Z',
              },
            ],
            'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
          });

      final MapPage result =
          await api.listMaps(token: 'access-1', type: MapType.indoor, page: 1, size: 20);

      expect(result.items.single.mapId, 'map-3');
      expect(result.items.single.name, '三号楼二层');
      expect(result.items.single.type, MapType.indoor);
      expect(result.items.single.imageWidth, 4096);
      expect(result.items.single.calibrated, isTrue);
      expect(result.items.single.calibratedAt, isNotNull);
      expect(result.meta.total, 1);
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/maps');
      expect(seen.single.uri.queryParameters,
          <String, String>{'type': 'INDOOR', 'page': '1', 'size': '20'});
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('不带参数：不拼查询串（走服务端默认分页）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[],
            'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 0},
          });

      final MapPage result = await api.listMaps(token: 'access-1');

      expect(result.items, isEmpty);
      expect(result.meta.total, 0);
      expect(seen.single.uri.query, isEmpty);
    });

    test('未知 type 字面量：抛 FormatException（不静默落默认分支）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'items': <dynamic>[
              <String, dynamic>{'mapId': 'map-3', 'name': 'x', 'type': 'MIXED'},
            ],
            'meta': <String, dynamic>{'page': 1, 'size': 20, 'total': 1},
          });

      expect(() => api.listMaps(token: 'access-1'), throwsA(isA<FormatException>()));
    });
  });

  group('createMap / getMap / updateMap / deleteMap', () {
    test('createMap：POST /api/v1/maps，正文二键（name/type），201 回 MapDetail', () async {
      handler = (HttpRequest request) => respondJson(request, 201, <String, dynamic>{
            'mapId': 'map-9',
            'name': '新建底图',
            'type': 'OUTDOOR',
            'createdAt': '2026-10-31T02:00:00Z',
          });

      final MapDetail map = await api.createMap(
        token: 'access-1',
        request: const MapUpsertRequest(name: '新建底图', type: MapType.outdoor),
      );

      expect(map.mapId, 'map-9');
      expect(map.type, MapType.outdoor);
      expect(map.imageUrl, isNull);
      expect(map.floorCount, isNull);
      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/maps');
      expect(seen.single.contentType?.mimeType, 'application/json');
      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{'name': '新建底图', 'type': 'OUTDOOR'},
      );
    });

    test('getMap：404 MAP_NOT_FOUND → ApiException', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            404,
            <String, dynamic>{
              'status': 404,
              'code': ApiErrorCode.mapNotFound,
              'traceId': 'trace-map',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure =
          await expectApiFailure(api.getMap(token: 'access-1', mapId: 'missing'));

      expect(failure.statusCode, 404);
      expect(failure.code, ApiErrorCode.mapNotFound);
      expect(seen.single.uri.path, '$kApiPrefix/maps/missing');
    });

    test('updateMap：PUT 整体替换——正文恒发二键', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'mapId': 'map-3',
            'name': '改名后的底图',
            'type': 'INDOOR',
            'createdAt': '2026-10-01T02:00:00Z',
            'updatedAt': '2026-10-31T02:05:00Z',
          });

      final MapDetail map = await api.updateMap(
        token: 'access-1',
        mapId: 'map-3',
        request: const MapUpsertRequest(name: '改名后的底图', type: MapType.indoor),
      );

      expect(map.name, '改名后的底图');
      expect(seen.single.method, 'PUT');
      expect(seen.single.uri.path, '$kApiPrefix/maps/map-3');
      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{'name': '改名后的底图', 'type': 'INDOOR'},
      );
    });

    test('deleteMap：DELETE /api/v1/maps/{id}，204 无正文', () async {
      handler = (HttpRequest request) async {
        request.response.statusCode = 204;
        await request.response.close();
      };

      await api.deleteMap(token: 'access-1', mapId: 'map-9');

      expect(seen.single.method, 'DELETE');
      expect(seen.single.uri.path, '$kApiPrefix/maps/map-9');
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('deleteMap：409 MAP_IN_USE → ApiException（不做级联删除）', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            409,
            <String, dynamic>{
              'status': 409,
              'code': ApiErrorCode.mapInUse,
              'traceId': 'trace-map-del',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure =
          await expectApiFailure(api.deleteMap(token: 'access-1', mapId: 'map-3'));

      expect(failure.statusCode, 409);
      expect(failure.code, ApiErrorCode.mapInUse);
    });
  });

  group('底图图片（multipart / 二进制）', () {
    test('uploadMapImage：POST multipart/form-data，正文含 name="file" 与 filename', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'mapId': 'map-3',
            'name': '三号楼二层',
            'type': 'INDOOR',
            'imageUrl': '/api/v1/files?path=maps/map-3/b2.png&exp=1793000000&signature=Zz9',
            'imageWidth': 4096,
            'imageHeight': 3072,
            'createdAt': '2026-10-01T02:00:00Z',
            'updatedAt': '2026-10-31T02:20:00Z',
          });

      final MapDetail map = await api.uploadMapImage(
        token: 'access-1',
        mapId: 'map-3',
        filename: 'floor2.png',
        bytes: <int>[0x89, 0x50, 0x4E, 0x47],
        contentType: 'image/png',
      );

      expect(map.imageWidth, 4096);
      expect(map.imageHeight, 3072);
      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/maps/map-3/image');
      expect(seen.single.contentType?.mimeType, 'multipart/form-data');
      expect(seen.single.contentType?.parameters['boundary'], isNotEmpty);
      final String body = utf8.decode(seen.single.body, allowMalformed: true);
      expect(body, contains('name="file"'));
      expect(body, contains('filename="floor2.png"'));
      expect(body, contains('Content-Type: image/png'));
    });

    test('uploadMapImage：415 非白名单真实图片 → ApiException', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            415,
            <String, dynamic>{
              'status': 415,
              'code': ApiErrorCode.unsupportedMediaType,
              'traceId': 'trace-map-img',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(api.uploadMapImage(
        token: 'access-1',
        mapId: 'map-3',
        filename: 'note.txt',
        bytes: <int>[0x68, 0x69],
      ));

      expect(failure.statusCode, 415);
      expect(failure.code, ApiErrorCode.unsupportedMediaType);
    });

    test('downloadMapImage：GET /api/v1/maps/{id}/image，回原始二进制与 contentType', () async {
      const List<int> png = <int>[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A];
      handler = (HttpRequest request) async {
        request.response
          ..statusCode = 200
          ..headers.contentType = ContentType('image', 'png');
        request.response.add(png);
        await request.response.close();
      };

      final ApiResponse response =
          await api.downloadMapImage(token: 'access-1', mapId: 'map-3');

      expect(response.statusCode, 200);
      expect(response.body, png);
      expect(response.contentType, 'image/png');
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/maps/map-3/image');
      expect(seen.single.authorization, 'Bearer access-1');
    });

    test('downloadMapImage：404 MAP_IMAGE_MISSING → ApiException', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            404,
            <String, dynamic>{
              'status': 404,
              'code': ApiErrorCode.mapImageMissing,
              'traceId': 'trace-img-missing',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(
          api.downloadMapImage(token: 'access-1', mapId: 'map-3'));

      expect(failure.statusCode, 404);
      expect(failure.code, ApiErrorCode.mapImageMissing);
    });
  });

  group('楼层点位表（floor points）', () {
    test('listFloorPoints：GET .../floors/{floorNum}/points，回 points', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'points': <dynamic>[
              <String, dynamic>{'posNum': 1, 'xPct': 0.25, 'yPct': 0.5, 'label': '东门'},
              <String, dynamic>{'posNum': 2, 'xPct': 0.75, 'yPct': 0.5, 'label': null},
            ],
          });

      final FloorPointsRequest result =
          await api.listFloorPoints(token: 'access-1', mapId: 'map-3', floorNum: 2);

      expect(result.points.length, 2);
      expect(result.points.first.posNum, 1);
      expect(result.points.first.xPct, 0.25);
      expect(result.points.first.label, '东门');
      expect(result.points.last.label, isNull);
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/maps/map-3/floors/2/points');
      expect(seen.single.uri.query, isEmpty);
    });

    test('replaceFloorPoints：PUT 整体替换——正文四点键全发（label 缺席显式 null）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'points': <dynamic>[
              <String, dynamic>{'posNum': 1, 'xPct': 0.25, 'yPct': 0.5, 'label': null},
            ],
          });

      final FloorPointsRequest result = await api.replaceFloorPoints(
        token: 'access-1',
        mapId: 'map-3',
        floorNum: 2,
        request: const FloorPointsRequest(
          points: <FloorPoint>[FloorPoint(posNum: 1, xPct: 0.25, yPct: 0.5)],
        ),
      );

      expect(result.points.single.posNum, 1);
      expect(seen.single.method, 'PUT');
      expect(seen.single.uri.path, '$kApiPrefix/maps/map-3/floors/2/points');
      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{
          'points': <dynamic>[
            <String, dynamic>{'posNum': 1, 'xPct': 0.25, 'yPct': 0.5, 'label': null},
          ],
        },
      );
    });

    test('replaceFloorPoints：空列表即清空，正文 points 为空数组', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            200,
            <String, dynamic>{'points': <dynamic>[]},
          );

      await api.replaceFloorPoints(
        token: 'access-1',
        mapId: 'map-3',
        floorNum: 2,
        request: const FloorPointsRequest(points: <FloorPoint>[]),
      );

      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{'points': <dynamic>[]},
      );
    });
  });

  group('UWB 标定参数（uwb-meta）', () {
    Map<String, dynamic> metaJson() => <String, dynamic>{
          'bs1': <String, dynamic>{'xPx': 100, 'yPx': 200},
          'bs2': <String, dynamic>{'xPx': 3900, 'yPx': 200},
          'bs3': <String, dynamic>{'xPx': 3900, 'yPx': 2900},
          'bs4': <String, dynamic>{'xPx': 100, 'yPx': 2900},
          'bs5': null,
          'bs6': null,
          'bs7': null,
          'bs8': null,
          'realLengthCm': 3200,
          'realWidthCm': 2400,
        };

    test('getUwbMeta：GET .../uwb-meta，回 bs1~bs8 与两个基准（bs5~bs8 可空）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, metaJson());

      final UwbMeta meta = await api.getUwbMeta(token: 'access-1', mapId: 'map-3');

      expect(meta.bs1.xPx, 100);
      expect(meta.bs1.yPx, 200);
      expect(meta.bs2.xPx, 3900);
      expect(meta.bs4.yPx, 2900);
      expect(meta.bs5, isNull);
      expect(meta.realLengthCm, 3200);
      expect(meta.realWidthCm, 2400);
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/maps/map-3/uwb-meta');
    });

    test('getUwbMeta：404 MAP_NOT_CALIBRATED 与 MAP_NOT_FOUND 分码', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            404,
            <String, dynamic>{
              'status': 404,
              'code': ApiErrorCode.mapNotCalibrated,
              'traceId': 'trace-not-calibrated',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure =
          await expectApiFailure(api.getUwbMeta(token: 'access-1', mapId: 'map-3'));

      expect(failure.statusCode, 404);
      expect(failure.code, ApiErrorCode.mapNotCalibrated);
    });

    test('putUwbMeta：PUT 整体替换——正文十键全发（bs5~bs8 缺席显式 null）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, metaJson());

      await api.putUwbMeta(
        token: 'access-1',
        mapId: 'map-3',
        meta: const UwbMeta(
          bs1: UwbAnchorPoint(xPx: 100, yPx: 200),
          bs2: UwbAnchorPoint(xPx: 3900, yPx: 200),
          bs3: UwbAnchorPoint(xPx: 3900, yPx: 2900),
          bs4: UwbAnchorPoint(xPx: 100, yPx: 2900),
          realLengthCm: 3200,
          realWidthCm: 2400,
        ),
      );

      expect(seen.single.method, 'PUT');
      expect(seen.single.uri.path, '$kApiPrefix/maps/map-3/uwb-meta');
      final Map<String, dynamic> body =
          jsonDecode(utf8.decode(seen.single.body)) as Map<String, dynamic>;
      expect(body.keys.length, 10);
      expect(body['bs1'], <String, dynamic>{'xPx': 100.0, 'yPx': 200.0});
      expect(body['bs5'], isNull);
      expect(body['bs8'], isNull);
      expect(body['realLengthCm'], 3200.0);
      expect(body['realWidthCm'], 2400.0);
    });

    test('putUwbMeta：400 MAP_UWB_META_INCOMPLETE → ApiException（语义问题）', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            400,
            <String, dynamic>{
              'status': 400,
              'code': ApiErrorCode.mapUwbMetaIncomplete,
              'traceId': 'trace-meta',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(api.putUwbMeta(
        token: 'access-1',
        mapId: 'map-3',
        meta: const UwbMeta(
          bs1: UwbAnchorPoint(xPx: 1, yPx: 1),
          bs2: UwbAnchorPoint(xPx: 2, yPx: 2),
          bs3: UwbAnchorPoint(xPx: 3, yPx: 3),
          bs4: UwbAnchorPoint(xPx: 4, yPx: 4),
          realLengthCm: 0,
          realWidthCm: 0,
        ),
      ));

      expect(failure.statusCode, 400);
      expect(failure.code, ApiErrorCode.mapUwbMetaIncomplete);
    });
  });

  group('UWB 中心基准校准（uwb-calibration）', () {
    test('startUwbCalibration：POST（202）——正文只发非 null 键', () async {
      handler = (HttpRequest request) => respondJson(request, 202, <String, dynamic>{
            'state': 'RUNNING',
            'progressPct': 0,
            'sampledFrames': 0,
          });

      final UwbCalibrationStatus status = await api.startUwbCalibration(
        token: 'access-1',
        mapId: 'map-3',
        request: const UwbCalibrationRequest(sampleFrames: 300),
      );

      expect(status.state, UwbCalibrationState.running);
      expect(status.progressPct, 0);
      expect(status.appliedAt, isNull);
      expect(seen.single.method, 'POST');
      expect(seen.single.uri.path, '$kApiPrefix/maps/map-3/uwb-calibration');
      expect(
        jsonDecode(utf8.decode(seen.single.body)),
        <String, dynamic>{'sampleFrames': 300},
      );
    });

    test('startUwbCalibration：省略请求体时不发正文（走服务端默认 200 帧）', () async {
      handler = (HttpRequest request) => respondJson(request, 202, <String, dynamic>{
            'state': 'RUNNING',
          });

      await api.startUwbCalibration(token: 'access-1', mapId: 'map-3');

      expect(seen.single.body, isEmpty);
      expect(seen.single.contentType, isNull);
    });

    test('startUwbCalibration：409 MAP_CALIBRATION_IN_PROGRESS → ApiException', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            409,
            <String, dynamic>{
              'status': 409,
              'code': ApiErrorCode.mapCalibrationInProgress,
              'traceId': 'trace-calib',
            },
            contentType: kProblemMediaType,
          );

      final ApiException failure = await expectApiFailure(
          api.startUwbCalibration(token: 'access-1', mapId: 'map-3'));

      expect(failure.statusCode, 409);
      expect(failure.code, ApiErrorCode.mapCalibrationInProgress);
    });

    test('getUwbCalibration：GET，回 SUCCEEDED 与 appliedAt', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'state': 'SUCCEEDED',
            'progressPct': 100,
            'sampledFrames': 200,
            'appliedAt': '2026-10-31T02:30:00Z',
            'message': null,
          });

      final UwbCalibrationStatus status =
          await api.getUwbCalibration(token: 'access-1', mapId: 'map-3');

      expect(status.state, UwbCalibrationState.succeeded);
      expect(status.progressPct, 100);
      expect(status.sampledFrames, 200);
      expect(status.appliedAt, isNotNull);
      expect(status.message, isNull);
      expect(seen.single.method, 'GET');
      expect(seen.single.uri.path, '$kApiPrefix/maps/map-3/uwb-calibration');
    });

    test('未知校准 state 字面量：抛 FormatException', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'state': 'PENDING',
          });

      expect(
        () => api.getUwbCalibration(token: 'access-1', mapId: 'map-3'),
        throwsA(isA<FormatException>()),
      );
    });

    test('clearUwbCalibration：DELETE .../uwb-calibration，204 无正文', () async {
      handler = (HttpRequest request) async {
        request.response.statusCode = 204;
        await request.response.close();
      };

      await api.clearUwbCalibration(token: 'access-1', mapId: 'map-3');

      expect(seen.single.method, 'DELETE');
      expect(seen.single.uri.path, '$kApiPrefix/maps/map-3/uwb-calibration');
      expect(seen.single.authorization, 'Bearer access-1');
    });
  });

  group('契约解析守则', () {
    test('MapDetail 必填字段缺失（mapId）：抛 TypeError（不臆造缺省值）', () async {
      handler = (HttpRequest request) => respondJson(
            request,
            200,
            <String, dynamic>{'name': 'x', 'type': 'INDOOR'},
          );

      expect(
        () => api.getMap(token: 'access-1', mapId: 'map-3'),
        throwsA(isA<TypeError>()),
      );
    });

    test('FloorPoint 的 xPct 为整数也解析为 double（num → double）', () async {
      handler = (HttpRequest request) => respondJson(request, 200, <String, dynamic>{
            'points': <dynamic>[
              <String, dynamic>{'posNum': 7, 'xPct': 0, 'yPct': 1},
            ],
          });

      final FloorPointsRequest result =
          await api.listFloorPoints(token: 'access-1', mapId: 'map-3', floorNum: 1);

      expect(result.points.single.xPct, 0.0);
      expect(result.points.single.yPct, 1.0);
      expect(result.points.single.label, isNull);
    });
  });
}

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
