import 'package:diagrammer_mobile/domain/agent_presenter.dart';
import 'package:diagrammer_mobile/domain/agent_query.dart';
import 'package:diagrammer_mobile/domain/backend_schema.dart';
import 'package:flutter_test/flutter_test.dart';

import 'fixtures/colegio_contract.dart';
import 'fixtures/fake_backend.dart';

void main() {
  final schema = BackendSchema.parse(colegioContract());
  const presenter = AgentPresenter();

  Future<QueryOutcome> run(Map<String, dynamic> decision) =>
      QueryPlanner(schema: schema, gateway: colegioData().call).run(QuerySpec.fromModel(decision, schema));

  test('a list shows readable fields and the course label, never an id', () async {
    final text = presenter.render(await run({
      'tag': 'query',
      'entity': 'alumno',
      'filter': [
        {'field': 'curso.grado', 'value': '1ro'},
        {'field': 'curso.seccion', 'value': 'A'},
        {'field': 'curso.turno', 'value': 'mañana'},
      ],
      'sort': 'nombre',
    }));

    expect(text, '*Entendí: alumno · curso › grado = 1 · curso › seccion = A · curso › turno = Manana · '
        'ordenado por nombre*\n\n'
        '| # | nombre | apellido | curso | edad |\n'
        '| --- | --- | --- | --- | --- |\n'
        '| 1 | Ana | Paz | A | 6 |\n'
        '| 2 | Beto | Rios | A | 7 |\n\n'
        'Total: **2**');
    expect(AgentPresenter.uuid.hasMatch(text), isFalse);
  });

  test('"cantidad de ejercicios" style counts answer with the number, grouped by label', () async {
    final total = presenter.render(await run({'tag': 'report', 'entity': 'alumno', 'metric': 'count'}));
    expect(total, '*Entendí: cantidad de alumno*\n\nHay **4** registros de alumno.');

    final grouped = presenter.render(await run({
      'tag': 'report',
      'entity': 'alumno',
      'metric': 'count',
      'groupBy': 'cursoId',
    }));
    expect(grouped, contains('| curso | Cantidad |'));
    expect(grouped, contains('| A | 2 |'));
    expect(AgentPresenter.uuid.hasMatch(grouped), isFalse);
  });

  test('aggregates are formatted from the computed value', () async {
    final text = presenter.render(await run({'tag': 'report', 'entity': 'alumno', 'metric': 'avg', 'field': 'edad'}));
    expect(text, '*Entendí: promedio de edad en alumno*\n\nPromedio de edad: **6.75**');
  });

  test('an empty result says so and keeps the grounding note', () async {
    final text = presenter.render(await run({
      'tag': 'query',
      'entity': 'alumno',
      'filter': [
        {'field': 'curso.seccion', 'value': 'B'},
        {'field': 'curso.turno', 'value': 'mañana'},
      ],
    }));
    expect(text, endsWith('No hay registros de alumno con esos filtros.\n\n'
        '_Los valores existen, pero ningún registro cumple todos los filtros._'));
  });

  test('the quality gate replaces ids in model text with labels or removes them', () {
    const id = '11111111-1111-4111-8111-111111111111';
    expect(AgentPresenter.sanitize('El curso $id tiene 2 alumnos', labels: {id: '1ro A'}),
        'El curso 1ro A tiene 2 alumnos');
    expect(AgentPresenter.sanitize('Registro 22222222-2222-4222-8222-222222222222 listo'),
        'Registro (registro) listo');
    expect(AgentPresenter.sanitize('id $id', identifiersRequested: true), 'id $id');
  });

  test('cells cannot break the markdown table', () {
    expect(AgentPresenter.cell('a|b\nc'), r'a\|b c');
    expect(AgentPresenter.field('curso.fechaNacimiento'), 'curso › fecha nacimiento');
    expect(AgentPresenter.literal('MEDIO_TIEMPO'), 'Medio tiempo');
    expect(AgentPresenter.number(2.0), '2');
    expect(AgentPresenter.number(6.333333), '6.33');
  });
}
