import 'dart:async';
import 'dart:convert';

import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/data/push_registrar.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

class _Source implements PushTokenSource {
  _Source(this.value);
  final String? value;
  final controller = StreamController<String>.broadcast();
  @override
  Future<String?> token() async => value;
  @override
  Stream<String> get refreshes => controller.stream;
}

void main() {
  late List<http.Request> calls;
  late PlatformService api;

  setUp(() {
    calls = [];
    api = PlatformService(
      baseUri: Uri.parse('https://example.test'),
      client: MockClient((request) async {
        calls.add(request);
        return http.Response('', 204);
      }),
    );
  });

  String body(http.Request r) => (jsonDecode(r.body) as Map)['token'] as String;

  test('registers the device token with the session and removes it on sign-out', () async {
    final registrar = PushRegistrar(_Source('dev-1'), api);
    await registrar.register('session');
    expect(calls.single.method, 'POST');
    expect(calls.single.url.path, '/push/devices');
    expect(calls.single.headers['authorization'], 'Bearer session');
    expect(body(calls.single), 'dev-1');
    await registrar.unregister('session');
    expect(calls.last.method, 'DELETE');
    expect(body(calls.last), 'dev-1');
  });

  test('without a token nothing is sent and sign-out is a no-op', () async {
    final registrar = PushRegistrar(_Source(null), api);
    await registrar.register('session');
    await registrar.unregister('session');
    expect(calls, isEmpty);
  });

  test('a rotated token is registered and the previous one removed', () async {
    final source = _Source('old');
    final registrar = PushRegistrar(source, api);
    await registrar.register('session');
    source.controller.add('new');
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    expect(calls.map((c) => '${c.method} ${body(c)}'),
        ['POST old', 'POST new', 'DELETE old']);
  });

  test('transport failures never escape', () async {
    final failing = PlatformService(
      baseUri: Uri.parse('https://example.test'),
      client: MockClient((_) async => throw http.ClientException('down')),
    );
    await PushRegistrar(_Source('dev'), failing).register('session');
  });
}
