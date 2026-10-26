import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/main.dart';
import 'package:diagrammer_mobile/presentation/session_controller.dart';
import 'package:diagrammer_mobile/presentation/workspace_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'workspace_switch_test.dart' as f;

Future<(SessionController, f.SwitchStore)> showWorkspace(
  WidgetTester tester,
  WorkspaceController controller,
  f.SwitchPlatform api,
) async {
  tester.view.resetPhysicalSize();
  await tester.binding.setSurfaceSize(const Size(1080, 1200));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  final store = f.SwitchStore(savedSession: f.switchSession);
  final session = SessionController(store, api);
  await session.restore();
  await tester.pumpWidget(
    DiagrammerApp(session: session, workspace: controller),
  );
  await tester.pumpAndSettle();
  return (session, store);
}

void main() {
  testWidgets(
    'request identity remains stable and manual model field is removed',
    (tester) async {
      final api = f.SwitchPlatform();
      final controller = f.switchController(api, f.SwitchStore());
      await controller.selectBackend(f.switchSession, f.switchA);
      await showWorkspace(tester, controller, api);
      expect(find.byType(TextField), findsNothing);
      await tester.tap(find.text('Abrir agente'));
      await tester.pumpAndSettle();
      final request = find.byKey(const ValueKey('operation-request'));
      expect(request, findsOneWidget);
      expect(
        tester.widget<Semantics>(request).properties.identifier,
        'operation-request',
      );
      final field = find.descendant(
        of: request,
        matching: find.byType(TextField),
      );
      await tester.enterText(field, 'Read notes');
      expect(controller.requestDraft, 'Read notes');
      await tester.tap(find.byTooltip('Configuración'));
      await tester.pumpAndSettle();
      expect(find.byType(TextField), findsNothing);
      expect(
        find.text(controller.mnnStatus.isLoaded ? 'IA lista' : 'Cargar IA'),
        findsOneWidget,
      );
      expect(api.calls, isEmpty);
    },
  );
  testWidgets(
    'leave resets backend picker and result, retains login, then selects B',
    (tester) async {
      final api = f.SwitchPlatform();
      final controller = f.switchController(api, f.SwitchStore());
      await controller.selectBackend(f.switchSession, f.switchA);
      controller.latestResult = const GatewayResult(
        status: 200,
        body: 'A-only-result',
      );
      final (session, _) = await showWorkspace(tester, controller, api);
      expect(find.text('Desconectar backend'), findsOneWidget);
      await tester.tap(find.text('Abrir agente'));
      await tester.pumpAndSettle();
      expect(find.textContaining('A-only-result'), findsOneWidget);
      await tester.tap(find.byTooltip('Volver'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Desconectar backend'));
      await tester.pumpAndSettle();
      expect(session.authenticated, isTrue);
      expect(controller.project, f.switchProject);
      expect(controller.backend, isNull);
      expect(find.textContaining('A-only-result'), findsNothing);
      final picker = find.byType(DropdownButtonFormField<GeneratedBackend>);
      expect(
        tester.state<FormFieldState<GeneratedBackend>>(picker).value,
        isNull,
      );
      await tester.tap(picker);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Backend B · UP').last);
      await tester.pumpAndSettle();
      expect(controller.backend, f.switchB);
      expect(controller.contract!.deploymentId, 'b');
      expect(find.text('Contrato hash-b'), findsOneWidget);
      expect(find.text('Contrato hash-a'), findsNothing);
      expect(api.calls, isEmpty);
    },
  );

  testWidgets('project change visually clears the old selected backend', (
    tester,
  ) async {
    final api = f.SwitchPlatform();
    final controller = f.switchController(api, f.SwitchStore());
    await controller.selectBackend(f.switchSession, f.switchA);
    await showWorkspace(tester, controller, api);
    final picker = find.byType(DropdownButtonFormField<PlatformProject>);
    await tester.tap(picker);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Other project').last);
    await tester.pumpAndSettle();
    expect(controller.project, f.otherProject);
    expect(controller.backend, isNull);
    final backend = find.byType(DropdownButtonFormField<GeneratedBackend>);
    expect(
      tester.state<FormFieldState<GeneratedBackend>>(backend).value,
      isNull,
    );
    expect(find.text('Contrato hash-a'), findsNothing);
    expect(api.calls, isEmpty);
  });
}
