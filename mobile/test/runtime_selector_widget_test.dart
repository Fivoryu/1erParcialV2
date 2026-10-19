import 'dart:async';

import 'package:diagrammer_mobile/data/litert_agent_service.dart';
import 'package:diagrammer_mobile/presentation/screens/workspace_screen.dart';
import 'package:diagrammer_mobile/presentation/session_controller.dart';
import 'package:diagrammer_mobile/presentation/workspace_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'runtime_selection_test.dart' as runtime;
import 'workspace_switch_test.dart' as fixture;

class UnavailableGemma extends runtime.FakeGemma {
  UnavailableGemma(super.events);
  @override
  Future<LiteRtAgentStatus> status() async => const LiteRtAgentStatus(
    available: false,
    modelPresent: false,
    loaded: false,
    message: 'Optional Gemma runtime is unavailable in this build.',
  );
}

Future<void> showSettings(
  WidgetTester tester,
  WorkspaceController controller,
) async {
  await tester.binding.setSurfaceSize(const Size(390, 844));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  final session = SessionController(
    fixture.SwitchStore(),
    fixture.SwitchPlatform(),
  );
  await tester.pumpWidget(
    MaterialApp(
      home: WorkspaceScreen(
        session: session,
        controller: controller,
        page: WorkspacePage.settings,
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> chooseGemma(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('local-runtime-selector')));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Gemma (LiteRT)').last);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'visible Settings selector reaches Gemma load and preserves workspace',
    (tester) async {
      final events = <String>[];
      final controller = WorkspaceController(
        mnn: runtime.FakeQwen(events),
        litert: runtime.FakeGemma(events),
      );
      controller.backend = fixture.switchA;
      controller.requestDraft = 'Keep this draft';
      await showSettings(tester, controller);
      expect(find.text('Qwen (MNN)'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('local-runtime-selector')),
        findsOneWidget,
      );
      await chooseGemma(tester);
      expect(controller.selectedRuntime, LocalRuntime.gemma);
      expect(find.text('Gemma (LiteRT)'), findsOneWidget);
      await tester.tap(find.text('Load AI'));
      await tester.pumpAndSettle();
      expect(events, containsAllInOrder(['qwen.release', 'gemma.load']));
      expect(events, isNot(contains('qwen.load')));
      expect(find.text('AI ready'), findsOneWidget);
      expect(controller.backend, fixture.switchA);
      expect(controller.requestDraft, 'Keep this draft');
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('unavailable Gemma stays visible without enabling Load AI', (
    tester,
  ) async {
    final events = <String>[];
    final controller = WorkspaceController(
      mnn: runtime.FakeQwen(events),
      litert: UnavailableGemma(events),
    );
    await showSettings(tester, controller);
    await chooseGemma(tester);
    expect(
      find.text('Optional Gemma runtime is unavailable in this build.'),
      findsOneWidget,
    );
    final load = tester.widget<FilledButton>(
      find.widgetWithText(FilledButton, 'Load AI'),
    );
    expect(load.onPressed, isNull);
    expect(events, isNot(contains('gemma.load')));
  });

  testWidgets('runtime selector is disabled while Gemma load is in flight', (
    tester,
  ) async {
    final events = <String>[];
    final gemma = runtime.FakeGemma(events)
      ..loading = Completer<LiteRtAgentStatus>();
    final controller = WorkspaceController(
      mnn: runtime.FakeQwen(events),
      litert: gemma,
    );
    await showSettings(tester, controller);
    await chooseGemma(tester);
    await tester.tap(find.text('Load AI'));
    await tester.pump();
    final selector = tester.widget<DropdownButtonFormField<LocalRuntime>>(
      find.byKey(const ValueKey('local-runtime-selector')),
    );
    expect(selector.onChanged, isNull);
    gemma.loading!.complete(
      const LiteRtAgentStatus(
        available: true,
        modelPresent: true,
        loaded: true,
        message: 'ready',
      ),
    );
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<DropdownButtonFormField<LocalRuntime>>(
            find.byKey(const ValueKey('local-runtime-selector')),
          )
          .onChanged,
      isNotNull,
    );
  });
}
