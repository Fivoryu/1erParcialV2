import 'package:diagrammer_mobile/domain/agent_query.dart';
import 'package:diagrammer_mobile/domain/backend_schema.dart';
import 'package:flutter_test/flutter_test.dart';

import 'fixtures/colegio_contract.dart';
import 'fixtures/fake_backend.dart';

void main() {
  final schema = BackendSchema.parse(colegioContract());

  Future<QueryOutcome> run(Map<String, dynamic> decision, {FakeBackend? backend, BackendSchema? on}) {
    final target = on ?? schema;
    return QueryPlanner(schema: target, gateway: (backend ?? colegioData()).call, now: () => DateTime(2026, 10, 22))
        .run(QuerySpec.fromModel(decision, target));
  }

  test('"los alumnos del 1ro A del turno de la mañana" grounds every value against real data', () async {
    final backend = colegioData();
    final outcome = await run({
      'tag': 'query',
      'entity': 'alumnos',
      'filter': [
        {'field': 'curso.grado', 'op': 'eq', 'value': '1ro'},
        {'field': 'seccion', 'op': 'eq', 'value': 'a'},
        {'field': 'turno', 'op': 'eq', 'value': 'mañana'},
      ],
      'sort': 'nombre',
    }, backend: backend);

    expect(outcome.filters.map((f) => f.encoded),
        ['curso.grado:eq:1', 'curso.seccion:eq:A', 'curso.turno:eq:MANANA']);
    expect(outcome.records.map((r) => r['nombre']), ['Ana', 'Beto']);
    expect(outcome.total, 2);
    expect(outcome.labels['cursoId'], {cursoMananaA: 'A'});
    expect(outcome.notes, isEmpty);
    final query = backend.calls.firstWhere((c) => c.path == '/api/alumno/query' && c.query.length > 2);
    expect(query.query.map((p) => '${p.name}=${p.value}'),
        ['filter=curso.grado:eq:1', 'filter=curso.seccion:eq:A', 'filter=curso.turno:eq:MANANA', 'sort=nombre,asc', 'size=20']);
  });

  test('a value that does not exist asks back with the real options', () async {
    expect(
      () => run({
        'tag': 'query',
        'entity': 'alumno',
        'filter': [{'field': 'curso.seccion', 'op': 'eq', 'value': 'Z'}],
      }),
      throwsA(isA<QueryClarification>()
          .having((c) => c.message, 'message', contains('No encontré "Z"'))
          .having((c) => c.options, 'options', ['A', 'B', 'C'])),
    );
    expect(
      () => run({
        'tag': 'query',
        'entity': 'alumno',
        'filter': [{'field': 'curso.turno', 'op': 'eq', 'value': 'noche'}],
      }),
      throwsA(isA<QueryClarification>().having((c) => c.options, 'options', ['MANANA', 'TARDE'])),
    );
  });

  test('values that exist but match no record are reported as empty, not as unknown', () async {
    final outcome = await run({
      'tag': 'query',
      'entity': 'alumno',
      'filter': [
        {'field': 'curso.seccion', 'op': 'eq', 'value': 'B'},
        {'field': 'curso.turno', 'op': 'eq', 'value': 'mañana'},
      ],
    });

    expect(outcome.records, isEmpty);
    expect(outcome.notes, ['Los valores existen, pero ningún registro cumple todos los filtros.']);
  });

  test('a count report is computed by the backend, grouped and labelled', () async {
    final outcome = await run({
      'tag': 'report',
      'entity': 'alumno',
      'metric': 'count',
      'groupBy': 'cursoId',
    });

    expect(outcome.spec.shape, QueryShape.count);
    expect(outcome.buckets.map((b) => (b.group, b.value)),
        [(cursoMananaA, 2), (cursoMananaC, 1), (cursoTardeB, 1)]);
    expect(outcome.labels['cursoId'], {cursoMananaA: 'A', cursoMananaC: 'C', cursoTardeB: 'B'});
  });

  test('aggregates validate the metric field and accept ordinals and number words', () async {
    final average = await run({
      'tag': 'report',
      'entity': 'alumno',
      'metric': 'avg',
      'field': 'edad',
      'filter': [{'field': 'curso.grado', 'op': 'eq', 'value': 'primero'}],
    });
    expect(average.buckets.single.value, closeTo(19 / 3, 1e-9));
    expect(
      () => QuerySpec.fromModel({'tag': 'report', 'entity': 'alumno', 'metric': 'sum', 'field': 'nombre'}, schema),
      throwsA(isA<QueryClarification>()),
    );
    expect(
      () => QuerySpec.fromModel({'tag': 'query', 'entity': 'alumno', 'filter': [{'field': 'color', 'value': 'x'}]}, schema),
      throwsA(isA<QueryClarification>().having((c) => c.options, 'options', contains('curso.turno'))),
    );
    expect(
      () => QuerySpec.fromModel({'tag': 'query', 'entity': 'profesor'}, schema),
      throwsA(isA<QueryClarification>().having((c) => c.options, 'options', ['alumno', 'curso'])),
    );
  });

  test('ne, in and is_null keep their meaning', () async {
    final notMorning = await run({
      'tag': 'query',
      'entity': 'alumno',
      'filter': [{'field': 'curso.turno', 'op': 'ne', 'value': 'mañana'}],
    });
    expect(notMorning.records.map((r) => r['nombre']), ['Carla']);
    final sections = await run({
      'tag': 'query',
      'entity': 'curso',
      'filter': [{'field': 'seccion', 'op': 'in', 'value': ['a', 'c']}],
    });
    expect(sections.filters.single.encoded, 'seccion:in:A|C');
    expect(sections.total, 2);
  });

  test('relative periods become a half-open date range computed by code', () async {
    final backend = FakeBackend({
      'pago': [
        {'id': 'p1', 'fecha': '2026-10-19'},
        {'id': 'p2', 'fecha': '2026-10-25'},
        {'id': 'p3', 'fecha': '2026-10-26'},
      ],
    });
    final contract = colegioContract();
    (contract['paths'] as Map)['/api/pago'] = {
      'post': {
        'requestBody': {
          'content': {
            'application/json': {'schema': {r'$ref': '#/components/schemas/PagoDto'}},
          },
        },
      },
    };
    (contract['paths'] as Map)['/api/pago/query'] = {'get': {}};
    ((contract['components'] as Map)['schemas'] as Map)['PagoDto'] = {
      'properties': {'fecha': {'type': 'string', 'format': 'date'}},
      'x-query-fields': [{'name': 'fecha', 'kind': 'DATE'}],
    };
    final withPagos = BackendSchema.parse(contract);
    final thisWeek = await run({
      'tag': 'query',
      'entity': 'pago',
      'filter': [{'field': 'fecha', 'op': 'eq', 'value': {'relative': 'this_week'}}],
    }, backend: backend, on: withPagos);

    expect(thisWeek.filters.map((f) => f.encoded), ['fecha:gte:2026-10-19', 'fecha:lte:2026-10-25']);
    expect(thisWeek.records.map((r) => r['id']), ['p1', 'p2']);
  });

  test('an older backend without /query is filtered locally and refuses relation paths', () async {
    final legacy = BackendSchema.parse(colegioContract(queryCapable: false));
    final backend = colegioData();
    final outcome = await run({
      'tag': 'query',
      'entity': 'alumno',
      'filter': [{'field': 'edad', 'op': 'gte', 'value': '7'}],
      'sort': 'edad',
      'desc': true,
    }, backend: backend, on: legacy);

    expect(outcome.records.map((r) => r['nombre']), ['Dani', 'Beto']);
    expect(backend.calls.map((c) => c.path), ['/api/alumno']);
    expect(
      () => QuerySpec.fromModel({
        'tag': 'query',
        'entity': 'alumno',
        'filter': [{'field': 'curso.turno', 'value': 'MANANA'}],
      }, legacy),
      throwsA(isA<QueryClarification>()),
    );
  });
}
