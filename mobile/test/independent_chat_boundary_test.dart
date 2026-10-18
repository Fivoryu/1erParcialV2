import 'package:flutter_test/flutter_test.dart';

import 'chat_store_test.dart' as fixture;

import 'package:diagrammer_mobile/data/chat_store.dart';
import 'package:diagrammer_mobile/domain/chat_models.dart';

void main() {
  test('rejected duplicate preserves all existing public records', () async {
    final store = MemoryChatStore(), s = fixture.scope();
    await store.saveConversation(fixture.conversation(s));
    await store.enqueue(fixture.operation(s), message: fixture.message());
    await store.updateOutbox(s, 'o', OutboxState.sending);
    await store.updateOutbox(s, 'o', OutboxState.unknown, error: 'Uncertain');
    final before = (await store.outbox(s)).single.toJson();
    await expectLater(
      store.enqueue(
        fixture.operation(s),
        message: fixture.message(content: 'Changed'),
      ),
      throwsStateError,
    );
    expect((await store.outbox(s)).single.toJson(), before);
    expect((await store.messages(s, 'c')).single.content, 'Add one');
    await store.saveConversation(fixture.conversation(s, id: 'second'));
    await expectLater(
      store.enqueue(
        fixture.operation(s, conversationId: 'second'),
        message: fixture.message(),
      ),
      throwsStateError,
    );
    expect(await store.messages(s, 'second'), isEmpty);
    expect((await store.outbox(s)).single.toJson(), before);
  });
}
