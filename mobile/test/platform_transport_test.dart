import 'dart:async';
import 'dart:convert';

import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/domain/operation_proposal.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;

class Client extends http.BaseClient {
  Client(this.handler);
  final Future<http.StreamedResponse> Function(http.BaseRequest) handler;
  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) =>
      handler(request);
}

void main() {
  test('known non-JSON HTTP failure retains status and body', () async {
    final api = PlatformService(
      baseUri: Uri.parse('https://example.test'),
      client: Client(
        (r) async => http.StreamedResponse(
          Stream.value(utf8.encode('unavailable')),
          503,
        ),
      ),
    );
    final result = await api.execute(
      't',
      'p',
      'd',
      'h',
      const OperationProposal(method: 'POST', path: '/tasks', body: {}),
    );
    expect(result.status, 503);
    expect(result.body, 'unavailable');
  });
  test('redirects are disabled on every platform endpoint', () async {
    final api = PlatformService(
      baseUri: Uri.parse('https://example.test'),
      client: Client((r) async {
        expect(r.followRedirects, isFalse);
        return http.StreamedResponse(
          Stream.value(utf8.encode('{}')),
          302,
          isRedirect: true,
        );
      }),
    );
    final calls = <Future<dynamic> Function()>[
      () => api.login('u', 'p'),
      () => api.me('t'),
      () => api.logout('t'),
      () => api.projects('t'),
      () => api.backends('t', 'p'),
      () => api.contract('t', 'p', 'd'),
      () => api.execute(
        't',
        'p',
        'd',
        'h',
        const OperationProposal(method: 'GET', path: '/tasks', body: null),
      ),
    ];
    for (final call in calls) {
      await expectLater(call(), throwsA(isA<PlatformTransportException>()));
    }
  });
  test('timeout includes delayed body after headers', () async {
    final api = PlatformService(
      baseUri: Uri.parse('https://example.test'),
      timeout: const Duration(milliseconds: 25),
      client: Client((r) async {
        await Future<void>.delayed(const Duration(milliseconds: 15));
        return http.StreamedResponse(
          Stream.fromFuture(
            Future.delayed(
              const Duration(milliseconds: 20),
              () => utf8.encode('[]'),
            ),
          ),
          200,
        );
      }),
    );
    await expectLater(
      api.projects('t'),
      throwsA(isA<PlatformTransportException>()),
    );
  });
  test('response cap rejects more than one MiB', () async {
    final api = PlatformService(
      baseUri: Uri.parse('https://example.test'),
      client: Client(
        (r) async => http.StreamedResponse(
          Stream.value(List.filled(1024 * 1024 + 1, 32)),
          200,
        ),
      ),
    );
    await expectLater(
      api.projects('t'),
      throwsA(isA<PlatformTransportException>()),
    );
  });
}
