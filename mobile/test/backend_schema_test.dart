import 'package:diagrammer_mobile/domain/agent_contract.dart';
import 'package:diagrammer_mobile/domain/backend_schema.dart';
import 'package:diagrammer_mobile/domain/models.dart';
import 'package:flutter_test/flutter_test.dart';

import 'fixtures/colegio_contract.dart';

void main() {
  test('helper routes are capabilities of their entity, not entities', () {
    final schema = BackendSchema.parse(colegioContract());

    expect(schema.entities.keys, ['alumno', 'curso']);
    expect(schema.queryCapable, isTrue);
    expect(schema.operators, contains('is_null'));
    expect(schema.maxPageSize, 200);
    expect(schema.entity('alumno')!.methods, {'GET', 'POST', 'PUT', 'DELETE'});
  });

  test('fields, enum literals, labels and references come from the contract', () {
    final alumno = BackendSchema.parse(colegioContract()).entity('alumno')!;
    final curso = BackendSchema.parse(colegioContract()).entity('curso')!;

    expect(alumno.displayFields, ['nombre', 'apellido']);
    expect(alumno.readableFields, ['nombre', 'apellido']);
    expect(alumno.references, {'cursoId': 'curso'});
    expect(alumno.fields['cursoId']!.technical, isTrue);
    expect(alumno.fields['nombre']!.required, isTrue);
    expect(alumno.queryFields['curso.turno']!.kind, QueryKind.enumeration);
    expect(alumno.queryFields['curso.turno']!.literals, ['MANANA', 'TARDE']);
    expect(curso.fields['turno']!.literals, ['MANANA', 'TARDE']);
    expect(curso.queryFields['grado']!.numeric, isTrue);
  });

  test('a contract from an older generator is not query capable and still lists entities', () {
    final schema = BackendSchema.parse(colegioContract(queryCapable: false));

    expect(schema.entities.keys, ['alumno', 'curso']);
    expect(schema.queryCapable, isFalse);
    expect(schema.entity('alumno')!.queryFields, isEmpty);
    expect(schema.entity('alumno')!.readableFields, ['nombre', 'apellido', 'edad']);
  });

  test('the agent contract exposes only entities, never helper routes', () {
    final contract = AgentContract(BackendContract(
      deploymentId: 'd',
      runId: 'r',
      contractHash: 'h',
      openApi: colegioContract(),
    ));

    expect(contract.entities, {'alumno': '/api/alumno', 'curso': '/api/curso'});
    expect(contract.compactDescriptor.map((entry) => entry['entity']), ['alumno', 'curso']);
  });

  test('a contract without paths is rejected', () {
    expect(() => BackendSchema.parse({'openapi': '3.0.3'}), throwsFormatException);
  });
}
