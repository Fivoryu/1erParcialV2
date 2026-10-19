import 'package:flutter/material.dart';
import 'package:diagrammer_mobile/presentation/session_controller.dart';
import 'package:diagrammer_mobile/presentation/screens/workspace_screen.dart';
import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/domain/operation_proposal.dart';
import 'package:diagrammer_mobile/presentation/workspace_controller.dart';
import 'package:flutter_test/flutter_test.dart';

import 'workspace_flow_test.dart' as fixture;

class KnownFailure extends fixture.FakePlatform {
  @override
  Future<GatewayResult> execute(
    String token,
    String projectId,
    String deploymentId,
    String contractHash,
    OperationProposal proposal,
  ) async => const GatewayResult(status: 422, body: {'error': 'invalid data'});
}

void main() {
  testWidgets('completed write result renders without a pending proposal', (
    tester,
  ) async {
    final api = fixture.FakePlatform();
    final controller = WorkspaceController()
      ..latestResult = const GatewayResult(
        status: 201,
        body: {'created': true},
      );
    await tester.pumpWidget(
      MaterialApp(
        home: WorkspaceScreen(
          page: WorkspacePage.agent,
          session: SessionController(fixture.MemoryStore(), api),
          controller: controller,
        ),
      ),
    );
    expect(find.text('Gateway result'), findsOneWidget);
    expect(find.textContaining('"created": true'), findsOneWidget);
    expect(find.text('Review proposal'), findsNothing);
  });
  test('known gateway failure retains result without unknown guard', () async {
    final api = KnownFailure();
    final controller =
        WorkspaceController(service: api, store: fixture.MemoryStore())
          ..project = fixture.project
          ..backend = fixture.backend
          ..contract = await api.contract('t', 'p', 'd')
          ..setProposal(
            const OperationProposal(method: 'GET', path: '/tasks', body: null),
          );
    await controller.confirm(fixture.session);
    expect(controller.latestResult?.status, 422);
    expect(controller.latestResult?.body, {'error': 'invalid data'});
    expect(controller.requiresInspection, isFalse);
  });
  test(
    'successful inspection clears unknown guard and permits next confirm',
    () async {
      final api = fixture.FakePlatform()..failWrites = true;
      final controller =
          WorkspaceController(service: api, store: fixture.MemoryStore())
            ..project = fixture.project
            ..backend = fixture.backend
            ..contract = await api.contract('t', 'p', 'd')
            ..setProposal(
              const OperationProposal(
                method: 'GET',
                path: '/tasks',
                body: null,
              ),
            );
      await controller.confirm(fixture.session);
      api.failWrites = false;
      await controller.inspect(fixture.session);
      expect(controller.requiresInspection, isFalse);
      expect(controller.latestResult?.status, 200);
      await controller.confirm(fixture.session);
      expect(api.writes, 3);
    },
  );
}
