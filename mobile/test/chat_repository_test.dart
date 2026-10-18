import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/data/chat_store.dart';
import 'package:diagrammer_mobile/data/chat_repository.dart';
import 'package:diagrammer_mobile/domain/chat_models.dart';

void main() {
  final scope = ChatScope(
    platform: 'https://example.test',
    userId: 'u',
    projectId: 'p',
    deploymentId: 'd',
    contractHash: 'h',
  );
  test(
    'sync retains original retry payload despite normalized server timestamps',
    () async {
      final bodies = <String>[];
      final store = MemoryChatStore();
      final time = DateTime.utc(2026, 10, 13, 12, 0, 0, 123, 456);
      await store.saveConversation(
        LocalConversation(
          id: 'c',
          scope: scope,
          title: 'Chat',
          createdAt: time,
          updatedAt: time,
        ),
      );
      await store.appendMessage(
        scope,
        'c',
        ChatMessage(
          id: 'm',
          role: 'user',
          content: 'Hello',
          createdAt: time,
          metadata: {'z': 1, 'a': 2},
        ),
      );
      final api = PlatformService(
        baseUri: Uri.parse(scope.platform),
        client: MockClient((request) async {
          expect(request.headers['authorization'], 'Bearer fixture');
          expect(
            request.url.path,
            startsWith('/projects/p/mobile/backends/d/chats/c'),
          );
          if (request.method == 'POST') {
            bodies.add(request.body);
            final body = jsonDecode(request.body) as Map;
            return http.Response(
              jsonEncode({
                ...body,
                'createdAt': '2026-10-13T12:00:00.123457Z',
                'sequence': 1,
                'metadata': {'a': 2, 'z': 1},
              }),
              200,
            );
          }
          return http.Response(
            jsonEncode({
              'items': [
                {
                  'id': 'm',
                  'role': 'user',
                  'content': 'Hello',
                  'createdAt': '2026-10-13T12:00:00.123457Z',
                  'sequence': 1,
                  'metadata': {'a': 2, 'z': 1},
                },
              ],
              'nextCursor': null,
            }),
            200,
          );
        }),
      );
      final repository = ChatRepository(
        service: api,
        store: store,
        scope: scope,
      );
      await repository.syncMessages('fixture', 'c');
      await repository.syncMessages('fixture', 'c');
      expect(bodies, hasLength(2));
      expect(bodies[0], bodies[1]);
      final local = (await store.messages(scope, 'c')).single;
      expect(local.createdAt, time);
      expect(local.sequence, isNull);
    },
  );
  test('transport failure preserves pending local history', () async {
    final store = MemoryChatStore();
    final time = DateTime.utc(2026);
    await store.saveConversation(
      LocalConversation(
        id: 'c',
        scope: scope,
        title: 'Chat',
        createdAt: time,
        updatedAt: time,
      ),
    );
    await store.appendMessage(
      scope,
      'c',
      ChatMessage(
        id: 'm',
        role: 'user',
        content: 'Hello',
        createdAt: time,
        metadata: {'z': 1, 'a': 2},
      ),
    );
    final api = PlatformService(
      baseUri: Uri.parse(scope.platform),
      client: MockClient((_) async => throw http.ClientException('offline')),
    );
    final repository = ChatRepository(service: api, store: store, scope: scope);
    await expectLater(
      repository.syncMessages('fixture', 'c'),
      throwsA(isA<http.ClientException>()),
    );
    expect((await store.messages(scope, 'c')).single.content, 'Hello');
    expect(await store.outbox(scope), isEmpty);
  });
}
