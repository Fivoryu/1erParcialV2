import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/main.dart';
import 'package:diagrammer_mobile/presentation/session_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'workspace_switch_test.dart' as fixture;

void main() {
  testWidgets('existing routes preserve selection and enforce session guards', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(1080, 1200));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final api = fixture.SwitchPlatform();
    final store = fixture.SwitchStore(savedSession: fixture.switchSession);
    final session = SessionController(store, api);
    await session.restore();
    final workspace = fixture.switchController(api, store);
    await tester.pumpWidget(DiagrammerApp(session: session, workspace: workspace));
    await tester.pumpAndSettle();

    final router = GoRouter.of(tester.element(find.byType(Scaffold).first));
    router.go('/agent');
    await tester.pumpAndSettle();
    expect(find.text('Proyectos'), findsOneWidget);
    expect(workspace.contract, isNull);

    await tester.tap(find.byType(DropdownButtonFormField<GeneratedBackend>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Backend A · UP').last);
    await tester.pumpAndSettle();
    expect(workspace.project, fixture.switchProject);
    expect(workspace.backend, fixture.switchA);
    expect(workspace.contract?.deploymentId, 'a');

    router.go('/agent');
    await tester.pumpAndSettle();
    expect(find.text('Agente'), findsOneWidget);
    expect(find.byKey(const ValueKey('operation-request')), findsOneWidget);
    await tester.tap(find.byTooltip('Configuración'));
    await tester.pumpAndSettle();
    expect(find.text('Configuración'), findsOneWidget);
    await tester.tap(find.byTooltip('Volver'));
    await tester.pumpAndSettle();
    expect(find.text('Agente'), findsOneWidget);
    expect(workspace.project, fixture.switchProject);
    expect(workspace.backend, fixture.switchA);
    expect(workspace.contract?.deploymentId, 'a');

    router.go('/workspace');
    await tester.pumpAndSettle();
    expect(find.text('Proyectos'), findsOneWidget);
    expect(workspace.backend, fixture.switchA);
    await tester.tap(find.byTooltip('Configuración'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Cerrar sesión'));
    await tester.tap(find.text('Cerrar sesión'));
    await tester.pumpAndSettle();
    expect(find.text('Iniciar sesión'), findsOneWidget);
    expect(workspace.project, isNull);
    expect(workspace.backend, isNull);
    expect(workspace.contract, isNull);

    for (final route in ['/agent', '/workspace', '/settings']) {
      router.go(route);
      await tester.pumpAndSettle();
      expect(find.text('Iniciar sesión'), findsOneWidget);
    }
    expect(api.calls, isEmpty);
  });
}
