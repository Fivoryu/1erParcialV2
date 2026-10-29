import 'dart:convert';

import 'package:diagrammer_mobile/domain/agent_contract.dart';
import 'package:diagrammer_mobile/domain/agent_models.dart';
import 'package:diagrammer_mobile/domain/agent_runner.dart';
import 'package:diagrammer_mobile/domain/models.dart';
import 'package:flutter_test/flutter_test.dart';

/// Two entities, Customer (displayName) and Order (total), like the generated demo backend.
AgentContract contract() {
  Map<String, dynamic> body(Map<String, String> fields) => {
    'requestBody': {
      'content': {
        'application/json': {
          'schema': {
            'type': 'object',
            'required': <String>[],
            'properties': {
              for (final entry in fields.entries) entry.key: {'type': entry.value},
            },
          },
        },
      },
    },
  };
  final fields = {
    'Customer': {'displayName': 'string'},
    'Order': {'total': 'number'},
  };
  return AgentContract(
    BackendContract(
      deploymentId: 'd',
      runId: 'r',
      contractHash: 'h',
      openApi: {
        'paths': {
          for (final name in fields.keys) '/api/$name': {'get': {}, 'post': body(fields[name]!)},
          for (final name in fields.keys) '/api/$name/{id}': {'get': {}, 'put': body(fields[name]!)},
        },
      },
    ),
  );
}

void main() {
  AgentRunner runner(List<String> prompts) => AgentRunner(
    contract: contract(),
    model: (prompt) async {
      prompts.add(prompt);
      return '{"tag":"answer","text":"model answer"}';
    },
    gateway: (_) async => throw StateError('a schema question never calls the backend'),
    scopeGuard: () => true,
  );

  for (final question in ['que clases hay?', '¿Qué entidades tiene el backend?', 'cuales son las tablas']) {
    test('"$question" is answered from the contract, without the model', () async {
      final prompts = <String>[];

      final result = await runner(prompts).run(question);

      expect(result.status, AgentRunStatus.answered);
      expect(result.text, contains('Customer'));
      expect(result.text, contains('Order'));
      expect(prompts, isEmpty);
    });
  }

  test('a question about the fields of one entity lists only that entity', () async {
    final prompts = <String>[];

    final result = await runner(prompts).run('qué campos tiene Order?');

    expect(result.status, AgentRunStatus.answered);
    expect(result.text, contains('total'));
    expect(result.text, isNot(contains('displayName')));
    expect(prompts, isEmpty);
  });

  test('a question about records still goes to the model', () async {
    final prompts = <String>[];

    await runner(prompts).run('Cuantos clientes hay registrados?');

    expect(prompts, hasLength(1));
  });

  test('the prompt tells the model to clarify a report that names no entity or metric', () async {
    final prompts = <String>[];

    await runner(prompts).run('hazme un reporte');

    final instruction = (jsonDecode(prompts.single) as Map)['instruction'] as String;
    expect(instruction, contains('names no entity or no metric'));
    expect(instruction, contains('never default to a count'));
  });
}
