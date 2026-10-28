import 'package:flutter_test/flutter_test.dart';
import 'package:scds_client/shared/api/api_contract.dart';
import 'package:scds_client/shared/config/connection_config.dart';
import 'package:scds_client/shared/config/connection_config_store.dart';

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
}
