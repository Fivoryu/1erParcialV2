import 'package:diagrammer_mobile/domain/operation_proposal.dart';
import 'package:diagrammer_mobile/presentation/workspace_controller.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('discard makes zero write calls', () {
    final controller = WorkspaceController(selectedRuntime: LocalRuntime.qwen);
    controller.setProposal(
      const OperationProposal(method: 'GET', path: '/tasks', body: null),
    );
    controller.discardProposal();
    expect(controller.proposal, isNull);
  });
}
