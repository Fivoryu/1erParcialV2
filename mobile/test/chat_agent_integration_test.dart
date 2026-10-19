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
  test(
    'Send persists real model response despite unavailable history sync',
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
      final conversation = LocalConversation(
        id: 'c',
        scope: scope,
        title: 'Test',
        createdAt: now,
        updatedAt: now,
      );
      await store.saveConversation(conversation);
      final paths = <String>[];
      var calls = 0;
      final api = PlatformService(
        baseUri: Uri.parse(scope.platform),
        client: MockClient((request) async {
          paths.add(request.url.path);
          return http.Response('{"message":"history unavailable"}', 503);
        }),
      );
      final controller = ChatController(
        store: store,
        service: api,
        model: (prompt) async {
          calls++;
          final envelope = jsonDecode(prompt) as Map;
          expect(envelope['request'], 'Hola');
          expect(envelope['observations'], isA<List>());
          expect(envelope['conversation'], isA<List>());
          expect(envelope['contract'], isA<List>());
          return '{"tag":"answer","text":"Hola, estoy listo."}';
        },
      );
      await controller.bind(
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
      await controller.open(conversation);
      await controller.sendMessage('Hola');
      expect(calls, 1);
      expect(controller.messages.map((m) => m.content), [
        'Hola',
        'Hola, estoy listo.',
      ]);
      expect(
        controller.progress.map((e) => e.phase),
        contains('model_finished'),
      );
      expect(paths.every((p) => p.contains('/chats')), isTrue);
    },
  );
  test('cancel during delete preview stops later target read', () async {
    final started = Completer<void>();
    final reply = Completer<http.Response>();
    var reads = 0;
    final scope = ChatScope(
      platform: 'https://example.test',
      userId: 'u',
      projectId: 'p',
      deploymentId: 'd',
      contractHash: 'h',
    );
    final store = MemoryChatStore();
    final now = DateTime.utc(2026);
    final c = LocalConversation(
      id: 'c',
      scope: scope,
      title: 'Test',
      createdAt: now,
      updatedAt: now,
    );
    await store.saveConversation(c);
    final api = PlatformService(
      baseUri: Uri.parse(scope.platform),
      client: MockClient((request) async {
        if (request.url.path.endsWith('/agent/operations')) {
          started.complete();
          return reply.future;
        }
        if (request.url.path.endsWith('/operations')) {
          reads++;
          return http.Response('{"status":200,"body":{"text":"x"}}', 200);
        }
        return http.Response('{"message":"unavailable"}', 503);
      }),
    );
    final controller = ChatController(
      store: store,
      service: api,
      model: (_) async => '{"tag":"delete_preview","entity":"note","id":"11111111-1111-4111-8111-111111111111"}',
    );
    await controller.bind(
      scope,
      'fixture',
      contract: const BackendContract(
        deploymentId: 'd',
        runId: 'r',
        contractHash: 'h',
        openApi: {
          'paths': {
            '/api/note': {'get': {}},
            '/api/note/{id}': {'get': {}, 'delete': {}},
          },
        },
      ),
    );
    await controller.open(c);
    final sending = controller.sendMessage('delete note');
    await started.future;
    controller.cancelRequest();
    reply.complete(
      http.Response(
        '{"state":"awaiting_confirmation","operationId":"op","path":"/api/note/11111111-1111-4111-8111-111111111111"}',
        200,
      ),
    );
    await sending;
    expect(reads, 0, reason: 'Cancellation must stop future dispatch');
  });
  test(
    'scope switch during receipt cache prevents undispatched write',
    () async {
      final store = DelayedReceiptStore();
      var writes = 0;
      final scope = ChatScope(
        platform: 'https://example.test',
        userId: 'u',
        projectId: 'p',
        deploymentId: 'd',
        contractHash: 'h',
      );
      final now = DateTime.utc(2026);
      final c = LocalConversation(
        id: 'c',
        scope: scope,
        title: 'Test',
        createdAt: now,
        updatedAt: now,
      );
      await store.saveConversation(c);
      final api = PlatformService(
        baseUri: Uri.parse(scope.platform),
        client: MockClient((request) async {
          if (request.url.path.endsWith('/agent/operations')) {
            writes++;
            return http.Response(
              '{"state":"completed","status":200,"result":{}}',
              200,
            );
          }
          return http.Response('{"message":"unavailable"}', 503);
        }),
      );
      var models = 0;
      final controller = ChatController(
        store: store,
        service: api,
        model: (_) async => ++models == 1
            ? '{"tag":"create","entity":"note","values":{"text":"x"}}'
            : '{"tag":"answer","text":"done"}',
      );
      await controller.bind(
        scope,
        'fixture',
        contract: const BackendContract(
          deploymentId: 'd',
          runId: 'r',
          contractHash: 'h',
          openApi: {
            'paths': {
              '/api/note': {
                'post': {
                  'requestBody': {
                    'content': {
                      'application/json': {
                        'schema': {
                          'type': 'object',
                          'properties': {
                            'text': {'type': 'string'},
                          },
                          'required': ['text'],
                        },
                      },
                    },
                  },
                },
              },
            },
          },
        ),
      );
      await controller.open(c);
      final sending = controller.sendMessage('create note with text = "x"');
      await store.started.future;
      await controller.bind(null, null);
      store.reply.complete();
      await sending;
      expect(
        writes,
        0,
        reason: 'Switch must fence write after asynchronous receipt lookup',
      );
    },
  );
  test('runtime invalidation retains selected conversation and draft while cancelling', () async {
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
      title: 'retain',
      createdAt: now,
      updatedAt: now,
    );
    await store.saveConversation(conversation);
    var cancelled = 0;
    final chat = ChatController(
      store: store,
      service: PlatformService(
        baseUri: Uri.parse(scope.platform),
        client: MockClient((_) async => http.Response('{}', 503)),
      ),
      cancelModel: () async {
        cancelled++;
      },
    );
    await chat.bind(scope, 'fixture');
    await chat.open(conversation);
    chat.setDraft('keep draft');
    chat.invalidateModel();
    expect(chat.selected?.id, 'c');
    expect(chat.draft, 'keep draft');
    chat.cancelRequest();
    await Future<void>.delayed(Duration.zero);
    expect(cancelled, greaterThan(0));
    expect((await store.listConversations(scope)).single.id, 'c');
  });
  test(
    'runtime epoch discards late assistant reply without losing user history',
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
      final c = LocalConversation(
        id: 'c',
        scope: scope,
        title: 'test',
        createdAt: now,
        updatedAt: now,
      );
      await store.saveConversation(c);
      final started = Completer<void>();
      final reply = Completer<String>();
      final chat = ChatController(
        store: store,
        service: PlatformService(
          baseUri: Uri.parse(scope.platform),
          client: MockClient((_) async => http.Response('{}', 503)),
        ),
        model: (_) {
          started.complete();
          return reply.future;
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
      await chat.open(c);
      final send = chat.sendMessage('Hola');
      await started.future;
      chat.setDraft('next draft');
      chat.invalidateModel();
      reply.complete('{"tag":"answer","text":"stale"}');
      await send;
      expect((await store.messages(scope, c.id)).map((m) => m.content), [
        'Hola',
      ]);
      expect(chat.draft, 'next draft');
      expect(chat.selected?.id, 'c');
    },
  );
}

class DelayedReceiptStore extends MemoryChatStore {
  final started = Completer<void>();
  final reply = Completer<void>();
  @override
  Future<List<CacheEntry>> cache(ChatScope scope, {String? path}) async {
    started.complete();
    await reply.future;
    return super.cache(scope, path: path);
  }
}
