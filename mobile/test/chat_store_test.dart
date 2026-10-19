import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:diagrammer_mobile/data/chat_store.dart';
import 'package:diagrammer_mobile/domain/chat_models.dart';

final time = DateTime.utc(2026);
ChatScope scope({
  String user = 'u',
  String deployment = 'd',
  String hash = 'h',
  String project = 'p',
  String platform = 'https://p',
}) => ChatScope(
  platform: platform,
  userId: user,
  projectId: project,
  deploymentId: deployment,
  contractHash: hash,
);
LocalConversation conversation(ChatScope s, {String id = 'c'}) =>
    LocalConversation(
      id: id,
      scope: s,
      title: 'Chat',
      createdAt: time,
      updatedAt: time,
    );
ChatMessage message({String id = 'm', String content = 'Add one'}) =>
    ChatMessage(id: id, role: 'user', content: content, createdAt: time);
QueuedOperation operation(
  ChatScope s, {
  String id = 'o',
  dynamic body = const {'title': 'One'},
  String conversationId = 'c',
}) => QueuedOperation(
  id: id,
  scope: s,
  conversationId: conversationId,
  method: 'POST',
  path: '/api/note',
  body: body,
  createdAt: time,
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late MemoryChatStore store;
  setUp(() {
    store = MemoryChatStore();
  });
  test(
    'atomic message plus addition, duplicate and unknown retention',
    () async {
      final s = scope();
      await expectLater(
        store.enqueue(operation(s), message: message()),
        throwsStateError,
      );
      expect(await store.messages(s, 'c'), isEmpty);
      await store.saveConversation(conversation(s));
      await store.enqueue(operation(s), message: message());
      await store.enqueue(operation(s), message: message());
      expect(await store.messages(s, 'c'), hasLength(1));
      expect(await store.outbox(s), hasLength(1));
      await store.updateOutbox(s, 'o', OutboxState.sending);
      await store.updateOutbox(s, 'o', OutboxState.unknown, error: 'Timeout');
      await expectLater(
        store.updateOutbox(s, 'o', OutboxState.queued),
        throwsStateError,
      );
      await store.enqueue(operation(s), message: message());
      expect((await store.outbox(s)).single.state, OutboxState.unknown);
      expect((await store.outbox(s)).single.error, 'Timeout');
    },
  );
  test(
    'every namespace dimension isolates conversation cache and outbox',
    () async {
      final a = scope();
      await store.saveConversation(conversation(a));
      await store.enqueue(operation(a), message: message());
      await store.putCache(
        CacheEntry(
          scope: a,
          path: '/api/note',
          body: [1],
          fetchedAt: time,
          complete: false,
        ),
      );
      for (final b in [
        scope(user: 'other'),
        scope(deployment: 'other'),
        scope(hash: 'other'),
        scope(project: 'other'),
        scope(platform: 'https://other'),
      ]) {
        expect(await store.listConversations(b), isEmpty);
        expect(await store.messages(b, 'c'), isEmpty);
        expect(await store.outbox(b), isEmpty);
        expect(await store.getCache(b, '/api/note'), isNull);
        await expectLater(
          store.appendMessage(b, 'c', message()),
          throwsStateError,
        );
      }
    },
  );
  test(
    'duplicate IDs with different payload reject without either insertion',
    () async {
      final s = scope();
      await store.saveConversation(conversation(s));
      await store.enqueue(operation(s), message: message());
      await expectLater(
        store.enqueue(
          operation(s, body: {'title': 'Different'}),
          message: message(id: 'second'),
        ),
        throwsStateError,
      );
      expect(await store.messages(s, 'c'), hasLength(1));
      expect((await store.outbox(s)).single.body, {'title': 'One'});
      await expectLater(
        store.appendMessage(s, 'c', message(content: 'Different')),
        throwsStateError,
      );
      expect((await store.messages(s, 'c')).single.content, 'Add one');
    },
  );
  test('message identity cannot move to a different conversation', () async {
    final s = scope();
    await store.saveConversation(conversation(s));
    await store.saveConversation(conversation(s, id: 'c2'));
    await store.appendMessage(s, 'c', message());
    await expectLater(
      store.enqueue(operation(s, conversationId: 'c2'), message: message()),
      throwsStateError,
    );
    expect(await store.outbox(s), isEmpty);
    expect(await store.messages(s, 'c2'), isEmpty);
  });
  test(
    'cache records preserve coverage timestamp and key by exact path',
    () async {
      final s = scope();
      await store.putCache(
        CacheEntry(
          scope: s,
          path: '/api/note',
          body: [
            {'id': 1},
          ],
          fetchedAt: time,
          complete: false,
        ),
      );
      await store.putCache(
        CacheEntry(
          scope: s,
          path: '/api/other',
          body: [],
          fetchedAt: time,
          complete: true,
        ),
      );
      expect(await store.cache(s), hasLength(2));
      final record = await store.getCache(s, '/api/note');
      expect(record!.complete, isFalse);
      expect(record.fetchedAt, time);
      expect(record.body, [
        {'id': 1},
      ]);
    },
  );
  test(
    'oversized message in enqueue leaves message and operation unchanged',
    () async {
      final s = scope();
      await store.saveConversation(conversation(s));
      await expectLater(
        store.enqueue(
          operation(s),
          message: message(content: 'x' * (1024 * 1024)),
        ),
        throwsArgumentError,
      );
      expect(await store.messages(s, 'c'), isEmpty);
      expect(await store.outbox(s), isEmpty);
    },
  );
  test('completed rejected conflict cannot silently requeue', () async {
    for (final state in [
      OutboxState.completed,
      OutboxState.rejected,
      OutboxState.conflict,
    ]) {
      final s = scope(user: state.name);
      await store.saveConversation(conversation(s));
      await store.enqueue(operation(s));
      if (state == OutboxState.completed) {
        await store.updateOutbox(s, 'o', OutboxState.sending);
      }
      await store.updateOutbox(s, 'o', state, error: 'Explain');
      await expectLater(
        store.updateOutbox(s, 'o', OutboxState.queued),
        throwsStateError,
      );
      expect((await store.outbox(s)).single.state, state);
    }
  });
  test('duplicate enqueue also binds the optional message payload', () async {
    final s = scope();
    await store.saveConversation(conversation(s));
    await store.enqueue(operation(s), message: message());
    await expectLater(
      store.enqueue(operation(s), message: message(id: 'second')),
      throwsStateError,
    );
    expect(await store.messages(s, 'c'), hasLength(1));
    expect(await store.outbox(s), hasLength(1));
  });
  test('canonical map order does not break operation idempotency', () async {
    final s = scope();
    await store.saveConversation(conversation(s));
    await store.enqueue(operation(s, body: {'title': 'One', 'rank': 1}));
    await store.enqueue(operation(s, body: {'rank': 1, 'title': 'One'}));
    expect(await store.outbox(s), hasLength(1));
  });
  test('closed store rejects reads writes and closes idempotently', () async {
    await store.close();
    await store.close();
    await expectLater(store.listConversations(scope()), throwsStateError);
    await expectLater(
      store.saveConversation(conversation(scope())),
      throwsStateError,
    );
  });
  group('MethodChannel serialization', () {
    const channel = MethodChannel('test/chat_store');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    late List<MethodCall> calls;
    late MethodChannelChatStore adapter;
    setUp(() {
      calls = [];
      adapter = MethodChannelChatStore(channel: channel);
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        if (call.method == 'listConversations') {
          return [jsonEncode(conversation(scope()).toJson())];
        }
        if (call.method == 'messages') return [jsonEncode(message().toJson())];
        if (call.method == 'outbox') {
          return [jsonEncode(operation(scope()).toJson())];
        }
        if (call.method == 'cache') {
          return [
            jsonEncode(
              CacheEntry(
                scope: scope(),
                path: '/api/note',
                body: [1],
                fetchedAt: time,
                complete: false,
              ).toJson(),
            ),
          ];
        }
        return null;
      });
    });
    tearDown(() => messenger.setMockMethodCallHandler(channel, null));
    test(
      'forwards scope and decoded body, never namespace-only arbitrary SQL',
      () async {
        await adapter.saveConversation(conversation(scope()));
        await adapter.enqueue(operation(scope()), message: message());
        expect(
          (calls.last.arguments as Map)['operation'],
          operation(scope()).toJson(),
        );
        expect((calls.last.arguments as Map)['message'], message().toJson());
        expect((await adapter.listConversations(scope())).single.id, 'c');
        expect((await adapter.messages(scope(), 'c')).single.id, 'm');
        expect((await adapter.outbox(scope())).single.method, 'POST');
        expect(
          (await adapter.getCache(scope(), '/api/note'))!.complete,
          isFalse,
        );
        expect((calls.last.arguments as Map)['scope'], scope().toJson());
      },
    );
    test('oversize rejected before channel and close stops dispatch', () async {
      await expectLater(
        adapter.appendMessage(
          scope(),
          'c',
          message(content: 'x' * (1024 * 1024)),
        ),
        throwsArgumentError,
      );
      expect(calls, isEmpty);
      await adapter.close();
      await adapter.close();
      await expectLater(adapter.outbox(scope()), throwsStateError);
      expect(calls.map((c) => c.method), ['close']);
    });
    test('native rejection remains observable', () async {
      messenger.setMockMethodCallHandler(
        channel,
        (call) async => throw PlatformException(code: 'store_rejected'),
      );
      await expectLater(
        adapter.enqueue(operation(scope()), message: message()),
        throwsA(isA<PlatformException>()),
      );
    });
  });
}
