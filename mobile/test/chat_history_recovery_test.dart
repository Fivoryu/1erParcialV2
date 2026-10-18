import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:diagrammer_mobile/data/chat_repository.dart';
import 'package:diagrammer_mobile/data/chat_store.dart';
import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/domain/chat_models.dart';

void main() {
  final scope = ChatScope(
    platform: 'https://example.test',
    userId: 'u',
    projectId: 'p',
    deploymentId: 'd',
    contractHash: 'current',
  );
  Map<String, dynamic> conversation(String id, String hash) => {
    'id': id,
    'title': 'Chat $id',
    'contractHash': hash,
    'createdAt': '2026-10-13T12:00:00Z',
    'updatedAt': '2026-10-13T12:00:00Z',
  };
  test(
    'paginated recovery skips old contracts and preserves local drafts',
    () async {
      final store = MemoryChatStore();
      final time = DateTime.utc(2026);
      await store.saveConversation(
        LocalConversation(
          id: 'draft',
          scope: scope,
          title: 'Offline draft',
          createdAt: time,
          updatedAt: time,
        ),
      );
      var calls = 0;
      final service = PlatformService(
        baseUri: Uri.parse(scope.platform),
        client: MockClient((request) async {
          expect(request.method, 'GET');
          expect(request.url.path, '/projects/p/mobile/backends/d/chats');
          expect(request.headers['authorization'], 'Bearer fixture');
          calls++;
          if (calls == 1) {
            return http.Response(
              jsonEncode({
                'items': [
                  conversation('a', 'old'),
                  conversation('b', 'current'),
                ],
                'nextCursor': 'b',
              }),
              200,
            );
          }
          expect(request.url.queryParameters['cursor'], 'b');
          return http.Response(
            jsonEncode({
              'items': [
                conversation('c', 'current'),
                conversation('draft', 'current'),
              ],
              'nextCursor': null,
            }),
            200,
          );
        }),
      );
      final repository = ChatRepository(
        service: service,
        store: store,
        scope: scope,
      );
      final result = await repository.refreshConversations('fixture');
      expect(result.map((c) => c.id).toSet(), {'draft', 'b', 'c'});
      expect(calls, 2);
    },
  );
  test(
    'repeating cursor fails visibly without deleting local history',
    () async {
      final store = MemoryChatStore();
      final service = PlatformService(
        baseUri: Uri.parse(scope.platform),
        client: MockClient(
          (_) async => http.Response(
            jsonEncode({
              'items': [conversation('a', 'current')],
              'nextCursor': 'a',
            }),
            200,
          ),
        ),
      );
      final repository = ChatRepository(
        service: service,
        store: store,
        scope: scope,
      );
      await expectLater(
        repository.refreshConversations('fixture'),
        throwsA(isA<FormatException>()),
      );
      expect((await store.listConversations(scope)).single.id, 'a');
    },
  );
}
