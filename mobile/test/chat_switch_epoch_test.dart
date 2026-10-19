import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:diagrammer_mobile/data/chat_store.dart';
import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/domain/chat_models.dart';
import 'package:diagrammer_mobile/presentation/chat_controller.dart';

class DelayedStore extends MemoryChatStore {
  final entered = Completer<void>();
  final release = Completer<void>();
  bool delay = false;
  @override
  Future<List<LocalConversation>> listConversations(ChatScope s) async {
    final result = await super.listConversations(s);
    if (delay && s.deploymentId == 'a') {
      delay = false;
      entered.complete();
      await release.future;
    }
    return result;
  }
}

void main() {
  test('switch B fences delayed new chat A list completion', () async {
    ChatScope scope(String d) => ChatScope(
      platform: 'https://example.test',
      userId: 'u',
      projectId: 'p',
      deploymentId: d,
      contractHash: 'h',
    );
    final st = DelayedStore();
    final c = ChatController(
      store: st,
      service: PlatformService(
        baseUri: Uri.parse('https://example.test'),
        client: MockClient(
          (r) async => http.Response(
            r.method == 'GET' ? '{"items":[],"nextCursor":null}' : '{}',
            200,
          ),
        ),
      ),
    );
    await c.bind(scope('a'), 'fixture');
    st.delay = true;
    final creating = c.newChat();
    await st.entered.future;
    await c.bind(scope('b'), 'fixture');
    expect(c.conversations, isEmpty);
    st.release.complete();
    await creating;
    expect(c.selected, isNull);
    expect(
      c.conversations,
      isEmpty,
      reason: 'A conversation must not overwrite active B list',
    );
  });
}
