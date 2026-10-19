import 'package:flutter_test/flutter_test.dart';
import 'package:diagrammer_mobile/domain/chat_models.dart';

void main() {
  test('scope refuses missing identity and collision-safe namespace', () {
    for (final invalid in ['', ' ', '\u0000', 'x' * 2049]) {
      expect(
        () => ChatScope(
          platform: invalid,
          userId: 'u',
          projectId: 'p',
          deploymentId: 'd',
          contractHash: 'h',
        ),
        throwsArgumentError,
      );
    }
    final a = ChatScope(
      platform: 'a:b',
      userId: 'c',
      projectId: 'p',
      deploymentId: 'd',
      contractHash: 'h',
    );
    final b = ChatScope(
      platform: 'a',
      userId: 'b:c',
      projectId: 'p',
      deploymentId: 'd',
      contractHash: 'h',
    );
    expect(a.namespace, isNot(b.namespace));
    expect(ChatScope.fromJson(a.toJson()), a);
    expect(a.namespace, '["a:b","c","p","d","h"]');
  });
  test('offline edits and deletes and arbitrary host paths refused', () {
    final s = ChatScope(
      platform: 'https://p',
      userId: 'u',
      projectId: 'p',
      deploymentId: 'd',
      contractHash: 'h',
    );
    for (final method in ['PUT', 'DELETE', 'PATCH', 'GET']) {
      expect(
        () => QueuedOperation(
          id: 'o',
          scope: s,
          conversationId: 'c',
          method: method,
          path: '/api/note',
          body: {},
          createdAt: DateTime.utc(2026),
        ),
        throwsArgumentError,
      );
    }
    for (final path in [
      'https://evil/api',
      '//evil/api',
      '/api#fragment',
      '/api\\bad',
      '',
    ]) {
      expect(
        () => CacheEntry(
          scope: s,
          path: path,
          body: [],
          fetchedAt: DateTime.utc(2026),
          complete: false,
        ),
        throwsArgumentError,
      );
    }
  });
  test('message JSON uses UTC and preserves metadata sequence', () {
    final m = ChatMessage(
      id: 'm',
      role: 'tool',
      content: 'Result',
      createdAt: DateTime.parse('2026-01-01T03:00:00+03:00'),
      metadata: {'coverage': 'partial'},
      sequence: 3,
    );
    expect(m.toJson()['createdAt'], '2026-01-01T00:00:00.000Z');
    expect(ChatMessage.fromJson(m.toJson()).sequence, 3);
    expect(ChatMessage.fromJson(m.toJson()).metadata, {'coverage': 'partial'});
    expect(
      () => ChatMessage(
        id: 'm',
        role: 'other',
        content: '',
        createdAt: DateTime.utc(2026),
      ),
      throwsArgumentError,
    );
  });
}
