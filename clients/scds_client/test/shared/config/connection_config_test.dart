import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/config/connection_config.dart';
import 'package:scds_client/shared/config/connection_config_store.dart';
import 'package:scds_client/shared/config/disk_connection_config_store.dart';

/// 段 3a：连接配置模型与内存配置存储。
///
/// 判据是「默认值与契约常量逐字一致」「构造即校验（空白地址 / 越界端口）」
/// 与「存储读回语义与 [ConnectionConfig.defaults] 回落约定」。
void main() {
  group('ConnectionConfig', () {
    test('defaults 与契约常量一致，并给出默认 baseUri', () {
      const ConnectionConfig config = ConnectionConfig.defaults;

      expect(config.host, kDefaultHost);
      expect(config.port, kHttpPort);
      expect(config.baseUri, Uri.parse('http://127.0.0.1:11001'));
      expect(config.baseUri.scheme, 'http');
      expect(config.baseUri.host, '127.0.0.1');
      expect(config.baseUri.port, 11001);
    });

    test('现场主机地址：baseUri 与 display 按 host:port 呈现', () {
      final ConnectionConfig config = ConnectionConfig(host: '192.168.110.110', port: 11001);

      expect(config.baseUri, Uri.parse('http://192.168.110.110:11001'));
      expect(config.display, '192.168.110.110:11001');
    });

    test('地址前后空白被裁剪', () {
      expect(ConnectionConfig(host: '  192.168.1.7  ', port: 11001).host, '192.168.1.7');
    });

    test('地址为空白（空串 / 纯空格）时抛 ArgumentError', () {
      expect(() => ConnectionConfig(host: '', port: 11001), throwsArgumentError);
      expect(() => ConnectionConfig(host: '   ', port: 11001), throwsArgumentError);
    });

    test('端口越界（0 / 65536）时抛 ArgumentError', () {
      expect(() => ConnectionConfig(host: '127.0.0.1', port: 0), throwsArgumentError);
      expect(() => ConnectionConfig(host: '127.0.0.1', port: 65536), throwsArgumentError);
    });

    test('端口边界（1 / 65535）合法', () {
      expect(ConnectionConfig(host: '127.0.0.1', port: kMinPort).port, 1);
      expect(ConnectionConfig(host: '127.0.0.1', port: kMaxPort).port, 65535);
    });
  });

  group('InMemoryConnectionConfigStore', () {
    late InMemoryConnectionConfigStore store;

    setUp(() => store = InMemoryConnectionConfigStore());

    test('初始未保存时 read 为 null（调用方回落到 defaults）', () async {
      expect(await store.read(), isNull);
      expect(ConnectionConfig.defaults.host, kDefaultHost);
    });

    test('写入后可原样读回', () async {
      final ConnectionConfig config = ConnectionConfig(host: '10.0.0.8', port: 11001);

      await store.write(config);

      final ConnectionConfig? read = await store.read();
      expect(read!.host, '10.0.0.8');
      expect(read.port, 11001);
    });

    test('再次写入覆盖旧值（只保留最近一次）', () async {
      await store.write(ConnectionConfig(host: '10.0.0.8', port: 11001));
      await store.write(ConnectionConfig(host: '10.0.0.9', port: 12001));

      expect((await store.read())!.display, '10.0.0.9:12001');
    });

    test('clear 后 read 回到 null', () async {
      await store.write(ConnectionConfig(host: '10.0.0.8', port: 11001));

      await store.clear();

      expect(await store.read(), isNull);
    });
  });

  group('DiskConnectionConfigStore（段 71：落盘，注入临时目录）', () {
    late Directory dir;
    late DiskConnectionConfigStore store;

    setUp(() {
      dir = Directory.systemTemp.createTempSync('scds_conn_cfg_');
      store = DiskConnectionConfigStore(directoryProvider: () async => dir);
    });

    tearDown(() {
      if (dir.existsSync()) {
        dir.deleteSync(recursive: true);
      }
    });

    File configFile() =>
        File('${dir.path}${Platform.pathSeparator}connection_config.json');

    test('未保存时 read 为 null（文件不存在）', () async {
      expect(await store.read(), isNull);
      expect(configFile().existsSync(), isFalse);
    });

    test('写入后落盘并原样读回', () async {
      await store.write(ConnectionConfig(host: '10.0.0.8', port: 11001));

      expect(configFile().existsSync(), isTrue);
      final ConnectionConfig? read = await store.read();
      expect(read!.host, '10.0.0.8');
      expect(read.port, 11001);
    });

    test('再次写入覆盖旧值（文件只保留最近一次）', () async {
      await store.write(ConnectionConfig(host: '10.0.0.8', port: 11001));
      await store.write(ConnectionConfig(host: '10.0.0.9', port: 12001));

      expect((await store.read())!.display, '10.0.0.9:12001');
    });

    test('clear 删除文件且 read 回到 null', () async {
      await store.write(ConnectionConfig(host: '10.0.0.8', port: 11001));

      await store.clear();

      expect(configFile().existsSync(), isFalse);
      expect(await store.read(), isNull);
    });

    test('文件不存在时 clear 静默通过', () async {
      await store.clear();

      expect(await store.read(), isNull);
    });

    test('坏 JSON 降级为 null（不砖住启动）', () async {
      await configFile().writeAsString('{ not json');

      expect(await store.read(), isNull);
    });

    test('非对象 JSON（数组）降级为 null', () async {
      await configFile().writeAsString(jsonEncode(<Object?>[1, 2, 3]));

      expect(await store.read(), isNull);
    });

    test('缺 host 字段降级为 null', () async {
      await configFile().writeAsString(jsonEncode(<String, Object?>{'port': 11001}));

      expect(await store.read(), isNull);
    });

    test('存量端口越界降级为 null（构造校验拦截）', () async {
      await configFile().writeAsString(
        jsonEncode(<String, Object?>{'host': '10.0.0.8', 'port': 0}),
      );

      expect(await store.read(), isNull);
    });
  });
}
