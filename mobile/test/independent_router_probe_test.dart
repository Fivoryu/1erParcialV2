import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:diagrammer_mobile/main.dart';
import 'package:diagrammer_mobile/presentation/session_controller.dart';

import 'workspace_switch_test.dart' as f;

void main() {
  testWidgets('direct agent route is guarded and logout blocks settings', (
    tester,
  ) async {
    final api = f.SwitchPlatform();
    final store = f.SwitchStore(savedSession: f.switchSession);
    final session = SessionController(store, api);
    await session.restore();
    final c = f.switchController(api, store);
    await tester.pumpWidget(DiagrammerApp(session: session, workspace: c));
    await tester.pumpAndSettle();
    final router = GoRouter.of(tester.element(find.byType(Scaffold).first));
    router.go('/agent');
    await tester.pumpAndSettle();
    expect(find.text('Proyectos'), findsOneWidget);
    expect(c.contract, isNull);
    router.go('/settings');
    await tester.pumpAndSettle();
    expect(find.text('Configuración'), findsOneWidget);
    await session.logout();
    await tester.pumpAndSettle();
    router.go('/settings');
    await tester.pumpAndSettle();
    expect(find.text('Iniciar sesión'), findsOneWidget);
    expect(c.backend, isNull);
    expect(c.requestDraft, isEmpty);
  });
}
