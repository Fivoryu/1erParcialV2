import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:diagrammer_mobile/data/chat_store.dart';
import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/domain/chat_models.dart';
import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/presentation/chat_controller.dart';

void main() {
  test('streaming assistant partial is visible but never persisted', () async {
    final partialSent = Completer<void>();
    final finalDecision = Completer<String>();
    final scope = ChatScope(
      platform: 'https://example.test',
      userId: 'u',
      projectId: 'p',
      deploymentId: 'd',
      contractHash: 'h',
    );
    final store = MemoryChatStore();
    final now = DateTime.utc(2026);
    final conversation = LocalConversation(
      id: 'c',
      scope: scope,
      title: 'Streaming chat',
      createdAt: now,
      updatedAt: now,
    );
    await store.saveConversation(conversation);
    final api = PlatformService(
      baseUri: Uri.parse(scope.platform),
      client: MockClient(
        (_) async => http.Response('{"message":"offline"}', 503),
      ),
    );
    final chat = ChatController(
      store: store,
      service: api,
      model: (prompt, {onPartial}) async {
        onPartial?.call('{"tag":"answer","text":"draft');
        partialSent.complete();
        return finalDecision.future;
      },
    );
    await chat.bind(
      scope,
      'fixture',
      contract: const BackendContract(
        deploymentId: 'd',
        runId: 'r',
        contractHash: 'h',
        openApi: {
          'paths': {
            '/api/note': {'get': {}},
          },
        },
      ),
    );
    await chat.open(conversation);

    final sending = chat.sendMessage('hello');
    await partialSent.future;
    expect(chat.messages.last.role, 'assistant');
    expect(
      chat.messages.last.content,
      'Gemma is generating (partial):\n\n{"tag":"answer","text":"draft',
    );
    expect(chat.messages.last.metadata?['streaming'], isTrue);
    expect(
      (await store.messages(scope, conversation.id)).map((message) => message.role),
      ['user'],
    );

    finalDecision.complete('{"tag":"answer","text":"final answer"}');
    await sending;
    expect(chat.messages.map((message) => message.content), [
      'hello',
      'final answer',
    ]);
    expect(
      (await store.messages(scope, conversation.id)).map((message) => message.content),
      ['hello', 'final answer'],
    );
  });

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
