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
      await tester.tap(find.byTooltip('Nueva conversación'));
      await tester.pumpAndSettle();
      expect(c.selected, isNotNull);
      await tester.enterText(
        find.byKey(const ValueKey('chat-message')),
        'Remember this locally',
      );
      await tester.tap(find.text('Guardar mensaje'));
      await tester.pumpAndSettle();
      expect(c.messages.single.content, 'Remember this locally');
      expect(c.messages.single.role, 'user');
      expect(find.text('Remember this locally'), findsOneWidget);
      // The person's own turn is labelled as theirs, as in the agent mockup.
      expect(find.text('Tú'), findsOneWidget);
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

  testWidgets('assistant markdown renders strong text instead of raw markers', (
    tester,
  ) async {
    final scope = ChatScope(
      platform: 'https://example.test',
      userId: 'u',
      projectId: 'p',
      deploymentId: 'd',
      contractHash: 'h',
    );
    final now = DateTime.utc(2025);
    final controller = ChatController(
      store: MemoryChatStore(),
      service: PlatformService(
        baseUri: Uri.parse(scope.platform),
        client: MockClient((_) async => http.Response('{}', 200)),
      ),
    )
      ..selected = LocalConversation(
        id: 'conversation-1',
        scope: scope,
        title: 'Fixture',
        createdAt: now,
        updatedAt: now,
      )
      ..messages = [
        ChatMessage(
          id: 'assistant-1',
          role: 'assistant',
          content: '**Hola** mundo',
          createdAt: now,
        ),
      ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: ChatPanel(controller: controller)),
      ),
    );

    final renderedAnswer = find.byWidgetPredicate(
      (widget) =>
          widget is SelectableText &&
          widget.textSpan?.toPlainText() == 'Hola mundo',
    );
    expect(renderedAnswer, findsOneWidget);
    expect(find.text('**Hola** mundo'), findsNothing);

    bool hasStrongHola(InlineSpan span) {
      if (span is! TextSpan) return false;
      if (span.text == 'Hola' &&
          span.style?.fontWeight == FontWeight.bold) {
        return true;
      }
      return span.children?.any(hasStrongHola) ?? false;
    }

    expect(
      hasStrongHola(tester.widget<SelectableText>(renderedAnswer).textSpan!),
      isTrue,
    );

    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });
}
