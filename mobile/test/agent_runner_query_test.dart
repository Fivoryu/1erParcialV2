import 'dart:convert';

import 'package:diagrammer_mobile/domain/agent_models.dart';
import 'package:diagrammer_mobile/domain/agent_runner.dart';
import 'package:flutter_test/flutter_test.dart';

import 'fixtures/colegio_contract.dart';
import 'fixtures/fake_backend.dart';

void main() {
  test('a list request takes one model call and renders the result from a template', () async {
    final backend = colegioData();
    final prompts = <Map<String, dynamic>>[];
    final runner = AgentRunner(
      contract: colegio(),
      model: (prompt) async {
        prompts.add(jsonDecode(prompt) as Map<String, dynamic>);
        return jsonEncode({
          'tag': 'query',
          'entity': 'alumno',
          'filter': [
            {'field': 'curso.grado', 'op': 'eq', 'value': '1ro'},
            {'field': 'curso.seccion', 'op': 'eq', 'value': 'A'},
            {'field': 'curso.turno', 'op': 'eq', 'value': 'mañana'},
          ],
          'sort': 'nombre',
          'respond': 'show',
        });
      },
      gateway: backend.call,
      scopeGuard: () => true,
    );

    final result = await runner.run('pasame los alumnos del 1ro A del turno de la mañana');

    expect(result.status, AgentRunStatus.answered);
    expect(prompts, hasLength(1));
    expect(result.text, startsWith('*Entendí: alumno · curso › grado = 1'));
    expect(result.text, contains('| 1 | Ana | Paz | A | 6 |'));
    expect(RegExp(r'[0-9a-f]{8}-[0-9a-f]{4}').hasMatch(result.text), isFalse);
    final described = (prompts.single['contract'] as List).firstWhere((e) => e['entity'] == 'alumno') as Map;
    expect(described['query']['curso.turno'], ['MANANA', 'TARDE']);
    expect(described['label'], ['nombre', 'apellido']);
    expect(prompts.single['instruction'], contains('"1ro A" is grado 1 and seccion A'));
  });

  test('a filter that echoes the request is omitted for a read and noted', () async {
    const request = 'Mostrame todos los alumnos del turno de la mañana';
    final runner = AgentRunner(
      contract: colegio(),
      model: (_) async => jsonEncode({
        'tag': 'query',
        'entity': 'alumno',
        'filter': [{'field': 'nombre', 'op': 'contains', 'value': request}],
        'respond': 'show',
      }),
      gateway: colegioData().call,
      scopeGuard: () => true,
    );

    final result = await runner.run(request);

    expect(result.status, AgentRunStatus.answered);
    expect(result.text, contains('| 1 | Ana | Paz | A | 6 |'));
    expect(result.text, contains('| 4 | Dani | Vera | C | 8 |'));
    expect(result.text, endsWith('_Omití el filtro "nombre": su valor repite tu pedido._'));
  });

  test('a one-word request remains a usable filter value', () async {
    final backend = colegioData();
    backend.tables['alumno']![0]['nombre'] = 'hola';
    final runner = AgentRunner(
      contract: colegio(),
      model: (_) async => jsonEncode({
        'tag': 'query',
        'entity': 'alumno',
        'filter': [{'field': 'nombre', 'op': 'eq', 'value': 'hola'}],
        'respond': 'show',
      }),
      gateway: backend.call,
      scopeGuard: () => true,
    );

    final result = await runner.run('hola');

    expect(result.status, AgentRunStatus.answered);
    expect(result.text, contains('| 1 | hola | Paz | A | 6 |'));
    expect(result.text, isNot(contains('Omití el filtro')));
  });

  test('respond answer lets the model reason over the computed result, through the quality gate', () async {
    var calls = 0;
    final runner = AgentRunner(
      contract: colegio(),
      model: (prompt) async {
        final envelope = jsonDecode(prompt) as Map<String, dynamic>;
        if (calls++ == 0) {
          return jsonEncode({'tag': 'report', 'entity': 'alumno', 'metric': 'count', 'groupBy': 'cursoId', 'respond': 'answer'});
        }
        final observation = (envelope['observations'] as List).single as Map;
        expect(observation['result'], contains('| A | 2 |'));
        return jsonEncode({'tag': 'answer', 'text': 'El curso con más alumnos es A ($cursoMananaA).'});
      },
      gateway: colegioData().call,
      scopeGuard: () => true,
    );

    final result = await runner.run('¿qué curso tiene más alumnos?');

    expect(calls, 2);
    expect(result.text, 'El curso con más alumnos es A ((registro)).');
  });

  test('an unknown value asks back with the real options instead of returning nothing', () async {
    final runner = AgentRunner(
      contract: colegio(),
      model: (_) async => jsonEncode({
        'tag': 'query',
        'entity': 'alumno',
        'filter': [{'field': 'curso.turno', 'op': 'eq', 'value': 'noche'}],
      }),
      gateway: colegioData().call,
      scopeGuard: () => true,
    );

    final result = await runner.run('alumnos del turno noche');

    expect(result.status, AgentRunStatus.clarification);
    expect(result.text, '"noche" no es un valor de curso.turno.\n\nOpciones: Manana, Tarde');
  });

  test('rows carry refs that a later message can use to delete that exact record', () async {
    final decisions = [
      {'tag': 'query', 'entity': 'alumno', 'filter': [{'field': 'curso.seccion', 'value': 'A'}], 'sort': 'nombre'},
      {'tag': 'delete_preview', 'entity': 'alumno', 'id': '#2'},
    ];
    final runner = AgentRunner(
      contract: colegio(),
      model: (_) async => jsonEncode(decisions.removeAt(0)),
      gateway: colegioData().call,
      scopeGuard: () => true,
    );

    final listed = await runner.run('alumnos del A');
    expect(listed.text, contains('| 2 | Beto | Rios | A | 7 |'));
    final deletion = await runner.run('borrá el segundo');

    expect(deletion.status, AgentRunStatus.deletePending);
    expect(deletion.deletePreview!.path, '/api/alumno/aaaaaaaa-0000-4000-8000-000000000002');
  });
}
