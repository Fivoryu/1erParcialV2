import 'dart:convert';

import 'package:diagrammer_mobile/domain/agent_models.dart';
import 'package:diagrammer_mobile/domain/agent_runner.dart';
import 'package:flutter_test/flutter_test.dart';

import 'agent_runner_test.dart' as fixture;

void main() {
  test(
    'an exact repeated calculation stops with the real partial observation',
    () async {
      var calls = 0;
      var gatewayCalls = 0;
      final runner = AgentRunner(
        contract: fixture.fixture(),
        model: (prompt) async {
          calls++;
          final envelope = jsonDecode(prompt) as Map;
          expect(envelope['request'], 'Calculate 2 + 3');
          if (calls == 2) {
            final observation =
                (envelope['observations'] as List).single as Map;
            expect(observation['calculation'], 5);
            expect(observation['complete'], false);
          }
          return calls == 1
              ? '{"tag":"calculate","calculation":{"op":"add","values":[2,3]}}'
              : '{"calculation":{"values":[2,3],"op":"add"},"tag":"calculate"}';
        },
        gateway: (_) async {
          gatewayCalls++;
          throw StateError('No gateway dispatch is allowed.');
        },
        scopeGuard: () => true,
      );
      final result = await runner.run('Calculate 2 + 3');
      expect(calls, 2);
      expect(gatewayCalls, 0);
      expect(result.status, AgentRunStatus.stopped);
      expect(result.text, contains('5'));
      expect(result.text, contains('resultado parcial'));
      expect(result.text, contains('propuestos por el modelo'));
      final observations = result.data as List;
      expect(observations, hasLength(1));
      expect(observations.single['calculation'], 5);
      expect(observations.single['complete'], false);
    },
  );

  test('distinct calculations with the same result may continue to a model answer', () async {
    var calls = 0;
    final runner = AgentRunner(
      contract: fixture.fixture(),
      model: (_) async => switch (++calls) {
        1 => '{"tag":"calculate","calculation":{"op":"add","values":[2,3]}}',
        2 =>
          '{"tag":"calculate","calculation":{"op":"subtract","values":[7,2]}}',
        _ => '{"tag":"answer","text":"Both calculations returned 5."}',
      },
      gateway: (_) async => throw StateError('Unexpected gateway.'),
      scopeGuard: () => true,
    );
    final result = await runner.run('Compare both calculations');
    expect(result.status, AgentRunStatus.answered);
    expect(calls, 3);
    expect(result.data as List, hasLength(2));
  });

  test('completed calculations are isolated between runs', () async {
    var calls = 0;
    final runner = AgentRunner(
      contract: fixture.fixture(),
      model: (_) async => ++calls % 2 == 1
          ? '{"tag":"calculate","calculation":{"op":"add","values":[2,3]}}'
          : '{"tag":"answer","text":"5"}',
      gateway: (_) async => throw StateError('Unexpected gateway.'),
      scopeGuard: () => true,
    );
    expect(
      (await runner.run('Calculate 2 + 3')).status,
      AgentRunStatus.answered,
    );
    expect(
      (await runner.run('Calculate 2 + 3')).status,
      AgentRunStatus.answered,
    );
    expect(calls, 4);
  });
}
