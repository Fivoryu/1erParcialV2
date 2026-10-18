import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:diagrammer_mobile/data/chat_store.dart';
import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/domain/chat_models.dart';
import 'package:diagrammer_mobile/presentation/chat_controller.dart';
import 'package:diagrammer_mobile/presentation/screens/chat_panel.dart';

class ObservedStore extends MemoryChatStore {
  bool closed = false;
  @override
  Future<void> close() async {
    closed = true;
    await super.close();
  }
}

void main() {
  testWidgets(
    'real connected panel saves offline user message without invented agent reply',
    (tester) async {
      final s = ChatScope(
        platform: 'https://example.test',
        userId: 'u',
        projectId: 'p',
        deploymentId: 'd',
        contractHash: 'h',
      );
      final st = ObservedStore();
      final paths = <String>[];
      final c = ChatController(
        store: st,
        service: PlatformService(
          baseUri: Uri.parse(s.platform),
          client: MockClient((r) async {
            paths.add(r.url.path);
            return http.Response('{}', 503);
          }),
        ),
      );
      await c.bind(s, 'fixture');
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(child: ChatPanel(controller: c)),
          ),
        ),
      );
      await tester.tap(find.byTooltip('New conversation'));
      await tester.pumpAndSettle();
      expect(c.selected, isNotNull);
      await tester.enterText(
        find.byKey(const ValueKey('chat-message')),
        'Remember this locally',
      );
      await tester.tap(find.text('Save message'));
      await tester.pumpAndSettle();
      expect(c.messages.single.content, 'Remember this locally');
      expect(c.messages.single.role, 'user');
      expect(find.text('Remember this locally'), findsOneWidget);
      expect(paths, everyElement(contains('/chats')));
      expect(await st.outbox(s), isEmpty);
      await tester.pumpWidget(const SizedBox());
      expect(st.closed, isFalse);
      expect(
        (await st.messages(s, c.selected!.id)).single.content,
        'Remember this locally',
      );
      expect(tester.takeException(), isNull);
    },
  );
}
