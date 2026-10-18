import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:diagrammer_mobile/domain/agent_contract.dart';
import 'package:diagrammer_mobile/domain/agent_models.dart';
import 'package:diagrammer_mobile/domain/agent_calculator.dart';
import 'package:diagrammer_mobile/domain/agent_runner.dart';
import 'package:diagrammer_mobile/domain/models.dart';

AgentContract fixture() {
  final body = {
    'requestBody': {
      'content': {
        'application/json': {
          'schema': {
            'type': 'object',
            'required': ['text'],
            'properties': {
              'text': {'type': 'string'},
            },
          },
        },
      },
    },
  };
  return AgentContract(
    BackendContract(
      deploymentId: 'd',
      runId: 'r',
      contractHash: 'h',
      openApi: {
        'paths': {
          for (final name in ['Note', 'Satellite'])
            '/api/$name': {'get': {}, 'post': body},
          for (final name in ['Note', 'Satellite'])
            '/api/$name/{id}': {'get': {}, 'delete': {}, 'put': body},
        },
      },
    ),
  );
}

void main() {
  test(
    'production prompt supplies executable decisions and arithmetic guidance',
    () async {
      late Map<String, dynamic> envelope;
      var gatewayCalls = 0;
      final runner = AgentRunner(
        contract: fixture(),
        model: (prompt) async {
          envelope = jsonDecode(prompt) as Map<String, dynamic>;
          return '{"tag":"answer","text":"Hello"}';
        },
        gateway: (_) async {
          gatewayCalls++;
          throw StateError('unexpected');
        },
        scopeGuard: () => true,
      );
      expect((await runner.run('Hello')).status, AgentRunStatus.answered);
      final examples = envelope['examples'] as List;
      expect(examples, hasLength(3));
      expect(examples.map((e) => AgentAction.parse(jsonEncode(e)).tag), [
        'answer',
        'clarify',
        'calculate',
      ]);
      final calculation = Map<String, dynamic>.from(
        examples.last['calculation'] as Map,
      );
      expect(AgentCalculator.evaluate(calculation), 5);
      for (final op in [
        'sum',
        'add',
        'mean',
        'min',
        'max',
        'multiply',
        'subtract',
        'divide',
      ]) {
        expect(envelope['instruction'], contains(op));
      }
      expect(envelope['instruction'], contains('No markdown'));
      expect(envelope['instruction'], contains(r'\n;'));
      expect(gatewayCalls, 0);
    },
  );

  test(
    'advertised calculation feeds observation into final answer without a tool',
    () async {
      var calls = 0;
      final runner = AgentRunner(
        contract: fixture(),
        model: (prompt) async {
          final envelope = jsonDecode(prompt) as Map<String, dynamic>;
          if (calls++ == 0) {
            return jsonEncode((envelope['examples'] as List).last);
          }
          final observation = (envelope['observations'] as List).single as Map;
          expect(observation['calculation'], 5);
          expect(observation['complete'], false);
          return '{"tag":"answer","text":"2 + 3 = 5"}';
        },
        gateway: (_) async => throw StateError('calculation must not dispatch'),
        scopeGuard: () => true,
      );
      final result = await runner.run('Calcula 2 + 3');
      expect(result.status, AgentRunStatus.answered);
      expect(result.text, '2 + 3 = 5');
      expect(calls, 2);
    },
  );

  test(
    'prompt carries a compact contract and never the full OpenAPI document',
    () async {
      Map<String, dynamic>? envelope;
      final runner = AgentRunner(
        contract: fixture(),
        model: (prompt) async {
          envelope = jsonDecode(prompt) as Map<String, dynamic>;
          return '{"tag":"answer","text":"Hello"}';
        },
        gateway: (_) async => throw StateError('unexpected'),
        scopeGuard: () => true,
      );
      expect((await runner.run('Hello')).status, AgentRunStatus.answered);
      final contract = envelope!['contract'];
      expect(contract, isA<List<dynamic>>());
      final entities = {for (final e in contract as List) e['entity'] as String: e};
      expect(entities.keys, containsAll(['Note', 'Satellite']));
      // Methods come from the real paths of this exact contract.
      expect(entities['Note']!['methods'], containsAll(['GET', 'POST', 'PUT', 'DELETE']));
      // Writable fields are advertised so the model can fill values.
      expect(entities['Note']!['fields'], {'text': 'string'});
      expect(entities['Note']!['required'], ['text']);
      // The full frozen document must not leak into the prompt.
      expect(jsonEncode(contract), isNot(contains('openapi')));
      expect(jsonEncode(envelope!['contract']).length, lessThan(2000));
    },
  );

  test(
    'compact contract handles arbitrary generated backends',
    () {
      final noteSchema = {
        'type': 'object',
        'required': ['titulo'],
        'properties': {
          'titulo': {'type': 'string'},
          'etiquetas': {'type': 'array', 'items': {'type': 'string'}},
          'autor': {'type': 'object', 'properties': {'nombre': {'type': 'string'}}},
        },
      };
      final contract = AgentContract(
        BackendContract(
          deploymentId: 'd',
          runId: 'r',
          contractHash: 'h',
          openApi: {
            'components': {'schemas': {'NotaDto': noteSchema}},
            'paths': {
              // $ref-based create body, arbitrary entity casing.
              '/api/Notas': {
                'get': {},
                'post': {
                  'requestBody': {
                    'content': {
                      'application/json': {'schema': {'\$ref': '#/components/schemas/NotaDto'}},
                    },
                  },
                },
              },
              '/api/Notas/{id}': {'get': {}, 'delete': {}, 'put': {}},
              // Read-only entity without any requestBody.
              '/api/Reporte': {'get': {}},
              // Malformed operation body: must fail soft with empty fields.
              '/api/Raro': {'post': {'requestBody': {'content': {}}}},
            },
          },
        ),
      );
      final entities = {
        for (final e in contract.compactDescriptor) e['entity'] as String: e,
      };
      expect(entities['Notas']!['fields'], {
        'titulo': 'string',
        'etiquetas': 'array',
        'autor': 'object',
      });
      expect(entities['Notas']!['required'], ['titulo']);
      expect(entities['Notas']!['methods'], containsAll(['GET', 'POST', 'PUT', 'DELETE']));
      expect(entities['Reporte']!['fields'], isEmpty);
      expect(entities['Reporte']!['required'], isEmpty);
      expect(entities['Reporte']!['methods'], ['GET']);
      expect(entities['Raro']!['fields'], isEmpty);
      // Full validation authority is untouched.
      expect(contract.openApi, contains('paths'));
    },
  );

  test(
    'invalid model decision still fails closed with zero dispatch',
    () async {
      for (final raw in [
        '```json\n{"tag":"answer","text":"Hello"}\n```',
        '{"tag":"read","entity":"Note","extra":true}',
        '{"answer":"Hello","read":{"entity":"Note"}}',
      ]) {
        var gatewayCalls = 0;
        final runner = AgentRunner(
          contract: fixture(),
          model: (_) async => raw,
          gateway: (_) async {
            gatewayCalls++;
            throw StateError('unexpected');
          },
          scopeGuard: () => true,
        );
        expect(
          (await runner.run('Hello')).status,
          AgentRunStatus.clarification,
        );
        expect(gatewayCalls, 0);
      }
    },
  );

  test(
    'independently parsed Spanish create never authorizes unrelated entity',
    () async {
      var calls = 0;
      final runner = AgentRunner(
        contract: fixture(),
        model: (_) async =>
            '{"tag":"create","entity":"Satellite","values":{"text":"hello"}}',
        gateway: (_) async {
          calls++;
          return AgentToolResult(
            data: {},
            complete: true,
            fetchedAt: DateTime.now(),
          );
        },
        scopeGuard: () => true,
      );
      expect(
        (await runner.run('Crea Note con text = "hello"')).status,
        AgentRunStatus.clarification,
      );
      expect(calls, 0);
    },
  );
  test(
    'exact explicit create dispatches once then repeated action clarifies',
    () async {
      var calls = 0;
      final runner = AgentRunner(
        contract: fixture(),
        model: (_) async =>
            '{"tag":"create","entity":"Note","values":{"text":"hello"}}',
        gateway: (_) async {
          calls++;
          return AgentToolResult(
            data: {},
            complete: true,
            fetchedAt: DateTime.now(),
          );
        },
        scopeGuard: () => true,
      );
      expect(
        (await runner.run('Crea Note con text = "hello"')).status,
        AgentRunStatus.clarification,
      );
      expect(calls, 1);
    },
  );
  test('delete preview never dispatches and requires exact UUID', () async {
    var calls = 0;
    final runner = AgentRunner(
      contract: fixture(),
      model: (_) async => jsonEncode({
        'tag': 'delete_preview',
        'entity': 'Note',
        'id': '11111111-1111-4111-8111-111111111111',
      }),
      gateway: (_) async {
        calls++;
        throw StateError('must not run');
      },
      scopeGuard: () => true,
    );
    final result = await runner.run('Delete Note');
    expect(result.status, AgentRunStatus.deletePending);
    expect(result.deletePreview!.method, 'DELETE');
    expect(calls, 0);
  });
  test(
    'cancelled model remains in flight and late completion cannot dispatch',
    () async {
      final pending = Completer<String>();
      var calls = 0;
      final runner = AgentRunner(
        contract: fixture(),
        model: (_) => pending.future,
        gateway: (_) async {
          calls++;
          throw StateError('unexpected');
        },
        scopeGuard: () => true,
      );
      final future = runner.run('read');
      runner.cancel();
      expect(runner.inFlight, true);
      expect((await runner.run('new')).status, AgentRunStatus.stopped);
      pending.complete('{"tag":"read","entity":"Note"}');
      expect((await future).status, AgentRunStatus.stopped);
      expect(calls, 0);
      expect(runner.inFlight, false);
    },
  );
  test('absolute deadline fences late model response', () async {
    var now = DateTime.utc(2026);
    var calls = 0;
    final runner = AgentRunner(
      contract: fixture(),
      model: (_) async {
        now = now.add(const Duration(minutes: 5));
        return '{"tag":"read","entity":"Note"}';
      },
      gateway: (_) async {
        calls++;
        throw StateError('unexpected');
      },
      scopeGuard: () => true,
      now: () => now,
    );
    expect((await runner.run('read')).status, AgentRunStatus.stopped);
    expect(calls, 0);
  });
  test('contract validates fields and denies arbitrary routes', () {
    final contract = fixture();
    expect(
      () => contract.operation(
        AgentAction.parse(
          '{"tag":"create","entity":"Note","values":{"sql":"x"}}',
        ),
      ),
      throwsFormatException,
    );
    expect(
      () => contract.operation(
        AgentAction.parse('{"tag":"read","entity":"https://evil"}'),
      ),
      throwsFormatException,
    );
    expect(contract.authorize('add Note with text = "hello"'), isNotNull);
    expect(contract.authorize('add Note with text = hello'), isNull);
  });
}
