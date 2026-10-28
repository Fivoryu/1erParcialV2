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
      final instruction = envelope['instruction'] as String;
      expect(instruction, contains('No markdown'));
      expect(instruction, contains('"sort" must be a declared field name'));
      expect(instruction, contains('how many'));
      expect(instruction, contains('never "desc", "limit" or "respond"'));
      expect(instruction, contains('filter "value" is the real value to match, never the user sentence'));
      expect(instruction, contains('listing everything uses "filter":[]'));
      expect(instruction, contains(
          '{"tag":"query","entity":"Note","filter":[],"sort":"text","desc":false,"limit":20,"respond":"show"}'));
      expect(instruction, contains(
          '{"tag":"report","entity":"Note","metric":"count","filter":[],"respond":"show"}'));
      // A small model answered "average price" with a calculation over invented numbers: the prompt must show
      // the average report of a stored field and forbid calculating over values that are not in observations.
      expect(instruction, contains(
          '{"tag":"report","entity":"Note","metric":"avg","field":"score","filter":[],"respond":"show"}'));
      expect(instruction, contains('never calculate over stored records'));
      expect(instruction, contains('never add "observations"'));
      expect(instruction, contains(r'\n;'));
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

  test('read observations carry refs instead of raw record identifiers', () async {
    const uuid = '22222222-2222-4222-8222-222222222222';
    Map<String, dynamic>? secondEnvelope;
    var calls = 0;
    final runner = AgentRunner(
      contract: fixture(),
      model: (prompt) async {
        if (calls++ == 0) return '{"tag":"read","entity":"Note"}';
        secondEnvelope = jsonDecode(prompt) as Map<String, dynamic>;
        return '{"tag":"answer","text":"La nota dice hola"}';
      },
      gateway: (_) async => AgentToolResult(
        data: [
          {'id': uuid, 'text': 'hola'},
          {'id': '33333333-3333-4333-8333-333333333333', 'text': 'chau'},
        ],
        complete: true,
        fetchedAt: DateTime.now(),
      ),
      scopeGuard: () => true,
    );
    final result = await runner.run('Mostrá las notas');
    expect(result.status, AgentRunStatus.answered);
    final observations = secondEnvelope!['observations'] as List;
    final records = (observations.cast<Map>().last['result'] as Map)['data'] as List;
    expect(records.first['ref'], '#1');
    expect(records.first['entity'], 'Note');
    expect(records.first['text'], 'hola');
    expect(records.last['ref'], '#2');
    expect(jsonEncode(records), isNot(contains(uuid)));
  });

  test('record refs are stable across reads in one run', () async {
    const first = '22222222-2222-4222-8222-222222222222';
    const second = '33333333-3333-4333-8333-333333333333';
    final seen = <List<dynamic>>[];
    var reads = 0;
    final runner = AgentRunner(
      contract: fixture(),
      model: (prompt) async {
        final envelope = jsonDecode(prompt) as Map<String, dynamic>;
        if (envelope['observations'] is List && (envelope['observations'] as List).isNotEmpty) {
          seen.add(((envelope['observations'] as List).last as Map)['result']['data'] as List);
        }
        if (reads++ < 2) return '{"tag":"read","entity":"Note"}';
        return '{"tag":"answer","text":"ok"}';
      },
      gateway: (_) async => AgentToolResult(
        data: reads < 3
            ? [
                {'id': first, 'text': 'a'},
                {'id': second, 'text': 'b'},
              ]
            : [
                {'id': second, 'text': 'b'},
              ],
        complete: true,
        fetchedAt: DateTime.now(),
      ),
      scopeGuard: () => true,
    );
    final result = await runner.run('leer dos veces');
    expect(result.status, AgentRunStatus.answered);
    expect(seen, hasLength(2));
    // First read assigns #1 and #2 in order.
    expect(seen[0].map((r) => r['ref']), ['#1', '#2']);
    // Second read of the same records reuses their refs.
    expect(seen[1].map((r) => r['ref']), ['#1', '#2']);
  });

  test('delete preview resolves a record ref to the exact identifier', () async {
    const uuid = '22222222-2222-4222-8222-222222222222';
    var calls = 0;
    final runner = AgentRunner(
      contract: fixture(),
      model: (_) async {
        if (calls++ == 0) return '{"tag":"read","entity":"Note"}';
        return '{"tag":"delete_preview","entity":"Note","id":"#1"}';
      },
      gateway: (_) async => AgentToolResult(
        data: [
          {'id': uuid, 'text': 'hola'},
        ],
        complete: true,
        fetchedAt: DateTime.now(),
      ),
      scopeGuard: () => true,
    );
    final result = await runner.run('Borrá la nota');
    expect(result.status, AgentRunStatus.deletePending);
    expect(result.deletePreview!.path, '/api/Note/$uuid');
  });

  test('unknown or foreign-entity refs fail closed without dispatch', () async {
    for (final action in [
      '{"tag":"delete_preview","entity":"Note","id":"#9"}',
      '{"tag":"delete_preview","entity":"Satellite","id":"#1"}',
    ]) {
      var gatewayCalls = 0;
      final runner = AgentRunner(
        contract: fixture(),
        model: (_) async {
          return gatewayCalls == 0
              ? '{"tag":"read","entity":"Note"}'
              : action;
        },
        gateway: (_) async {
          gatewayCalls++;
          return AgentToolResult(
            data: [
              {'id': '22222222-2222-4222-8222-222222222222', 'text': 'hola'},
            ],
            complete: true,
            fetchedAt: DateTime.now(),
          );
        },
        scopeGuard: () => true,
      );
      final result = await runner.run('Borrá');
      expect(result.status, AgentRunStatus.clarification);
      expect(gatewayCalls, 1);
    }
  });

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
      // The mismatched entity is shown for confirmation, never sent.
      expect(
        (await runner.run('Crea Note con text = "hello"')).status,
        AgentRunStatus.writePending,
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
    // Natural values are now authorized deterministically from user text.
    expect(contract.authorize('add Note with text = hello'), isNotNull);
    expect(contract.authorize('Creá una Note que diga "hola"'), isNotNull);
    expect(contract.authorize('agregá la Note hola'), isNotNull);
    expect(
      contract.authorize('Crea Note con text = "hello" y text2 = "bye"'),
      isNull,
    );
  });

  test('natural Spanish create authorizes values from user text only', () {
    final contract = fixture();
    for (final request in [
      'Creá una Note que diga "hola mundo"',
      'Crea Note que diga hola',
      'agregá la Note "hola"',
      'agrega Note con text = hola',
      'add Note with text = "hi there"',
    ]) {
      final authorization = contract.authorize(request);
      expect(authorization, isNotNull, reason: request);
    }
    // Values binding is deterministic: user text 'hola mundo' → required text field.
    expect(
      contract.authorize('Creá una Note que diga "hola mundo"')!.encodedProposal,
      contains('hola mundo'),
    );
    // Unknown entities, missing values and type mismatches stay closed.
    for (final request in [
      'Creá una entidad rara que diga hola',
      'Creá una Note',
      'borrá la Note',
      'Creá una Note que diga hola y chau',
      'crear Note text = 42',
    ]) {
      expect(contract.authorize(request), isNull, reason: request);
    }
  });

  test('plural and accented entity mentions resolve without guessing', () {
    final contract = fixture();
    expect(contract.authorize('Creá unas Notes que digan "hola"'), isNotNull);
    expect(contract.authorize('creá la NOTA que diga "hola"'), isNull);
  });

  test('failed agent result retains an unknown tool error', () async {
    final error = StateError('transient tool failure');
    final runner = AgentRunner(
      contract: fixture(),
      model: (_) async => '{"tag":"read","entity":"Note"}',
      gateway: (_) async => throw error,
      scopeGuard: () => true,
    );

    final result = await runner.run('Read the notes');

    expect(result.status, AgentRunStatus.failed);
    expect(
      result.text,
      'Falló el modelo o la herramienta; no se asume que haya tenido éxito. Se desconoce si se completaron las escrituras enviadas.',
    );
    expect(result.failure, same(error));
  });
}
