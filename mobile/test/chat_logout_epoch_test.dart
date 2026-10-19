import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:diagrammer_mobile/data/chat_store.dart';
import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/domain/chat_models.dart';
import 'package:diagrammer_mobile/presentation/chat_controller.dart';

class DelayedMessages extends MemoryChatStore {
  bool delay = false;
  final entered = Completer<void>();
  final release = Completer<void>();
  @override
  Future<List<ChatMessage>> messages(ChatScope s, String id) async {
    final list = await super.messages(s, id);
    if (delay) {
      delay = false;
      entered.complete();
      await release.future;
    }
    return list;
  }
}

void main() {
  test('logout fences delayed saved message completion', () async {
    final s = ChatScope(
      platform: 'https://example.test',
      userId: 'u',
      projectId: 'p',
      deploymentId: 'a',
      contractHash: 'h',
    );
    final st = DelayedMessages();
    final c = ChatController(
      store: st,
      service: PlatformService(
        baseUri: Uri.parse(s.platform),
        client: MockClient(
          (r) async => http.Response(
            r.method == 'GET' ? '{"items":[],"nextCursor":null}' : '{}',
            200,
          ),
        ),
      ),
    );
    await c.bind(s, 'fixture');
    await c.newChat();
    st.delay = true;
    final save = c.saveMessage('Private account A');
    await st.entered.future;
    await c.bind(null, null);
    st.release.complete();
    await save;
    expect(
      c.messages,
      isEmpty,
      reason: 'Signed-out state must not expose old account message',
    );
    expect(c.selected, isNull);
  });
}
