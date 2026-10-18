import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:diagrammer_mobile/data/chat_store.dart';
import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/domain/chat_models.dart';
import 'package:diagrammer_mobile/presentation/chat_controller.dart';

void main() {
  test(
    'connected history survives offline refresh and clears on leave',
    () async {
      final scope = ChatScope(
        platform: 'https://example.test',
        userId: 'u',
        projectId: 'p',
        deploymentId: 'd',
        contractHash: 'h',
      );
      final store = MemoryChatStore();
      final now = DateTime.utc(2026);
      await store.saveConversation(
        LocalConversation(
          id: 'c',
          scope: scope,
          title: 'Saved chat',
          createdAt: now,
          updatedAt: now,
        ),
      );
      final api = PlatformService(
        baseUri: Uri.parse(scope.platform),
        client: MockClient(
          (_) async => http.Response(jsonEncode({'message': 'offline'}), 503),
        ),
      );
      final chat = ChatController(store: store, service: api);
      await chat.bind(scope, 'fixture');
      expect(chat.conversations.single.title, 'Saved chat');
      expect(chat.error, isNotNull);
      await chat.bind(null, null);
      expect(chat.conversations, isEmpty);
      expect((await store.listConversations(scope)).single.id, 'c');
    },
  );
}
