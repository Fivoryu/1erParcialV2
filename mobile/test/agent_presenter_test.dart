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

  test('an undeclared sort is ignored and reported without claiming an order', () async {
    final text = presenter.render(await run({'tag': 'query', 'entity': 'alumno', 'sort': 'desc'}));

    expect(text, startsWith('*Entendí: alumno*'));
    expect(text, endsWith('_No ordené por "desc": no es un campo de alumno._'));
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

  test('count ignores a supplied field and still counts records per group', () async {
    final backend = colegioData();
    final outcome = await QueryPlanner(schema: schema, gateway: backend.call).run(
      QuerySpec.fromModel({'tag': 'report', 'entity': 'alumno', 'metric': 'count', 'field': 'id'}, schema),
    );

    expect(outcome.spec.shape, QueryShape.count);
    expect(outcome.spec.metricField, isNull);
    expect(presenter.render(outcome), '*Entendí: cantidad de alumno*\n\nHay **4** registros de alumno.');
    expect(backend.calls.single.query.map((parameter) => parameter.name), ['op']);

    final groupedBackend = colegioData();
    final grouped = await QueryPlanner(schema: schema, gateway: groupedBackend.call).run(
      QuerySpec.fromModel({
        'tag': 'report', 'entity': 'alumno', 'metric': 'count', 'field': 'id', 'groupBy': 'cursoId',
      }, schema),
    );
    final groupedText = presenter.render(grouped);
    expect(groupedText, contains('| A | 2 |'));
    expect(groupedText, contains('| C | 1 |'));
    final groupedAggregate = groupedBackend.calls.firstWhere((call) => call.path.endsWith('/aggregate'));
    expect(groupedAggregate.query.map((parameter) => parameter.name), ['op', 'groupBy']);
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
