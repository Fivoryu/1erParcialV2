import 'package:diagrammer_mobile/main.dart';
import 'package:diagrammer_mobile/presentation/session_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'workspace_switch_test.dart' as f;

void main() {
  testWidgets(
    'separate screens and Back preserve draft connection and session',
    (tester) async {
      final api = f.SwitchPlatform();
      final c = f.switchController(api, f.SwitchStore());
      await c.selectBackend(f.switchSession, f.switchA);
      final session = SessionController(
        f.SwitchStore(savedSession: f.switchSession),
        api,
      );
      await session.restore();
      await tester.pumpWidget(DiagrammerApp(session: session, workspace: c));
      await tester.pumpAndSettle();
      expect(find.text('Proyectos'), findsOneWidget);
      expect(find.byKey(const ValueKey('operation-request')), findsNothing);
      await tester.tap(find.text('Abrir agente'));
      await tester.pumpAndSettle();
      final field = find.descendant(
        of: find.byKey(const ValueKey('operation-request')),
        matching: find.byType(TextField),
      );
      await tester.enterText(field, 'Read notes');
      await tester.tap(find.byTooltip('Configuración'));
      await tester.pumpAndSettle();
      expect(find.text('Configuración'), findsOneWidget);
      expect(find.byType(TextField), findsNothing);
      await tester.tap(find.byTooltip('Volver'));
      await tester.pumpAndSettle();
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        'Read notes',
      );
      await tester.tap(find.byTooltip('Volver'));
      await tester.pumpAndSettle();
      expect(find.text('Proyectos'), findsOneWidget);
      expect(c.backend, f.switchA);
      expect(session.authenticated, isTrue);
      expect(api.calls, isEmpty);
    },
  );
}
