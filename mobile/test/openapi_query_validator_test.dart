import 'package:diagrammer_mobile/domain/openapi_validator.dart';
import 'package:diagrammer_mobile/domain/operation_proposal.dart';
import 'package:flutter_test/flutter_test.dart';

import 'fixtures/colegio_contract.dart';

void main() {
  const validator = OpenApiValidator();
  final contract = colegioContract();

  OperationProposal read(String path, List<(String, String)> query) => OperationProposal(
    method: 'GET',
    path: path,
    body: null,
    query: [for (final (name, value) in query) QueryParameter(name, value)],
  );

  test('declared query parameters pass and are kept out of the encoding when absent', () {
    validator.validate(read('/api/alumno/query', [('filter', 'curso.turno:eq:MANANA'), ('filter', 'edad:gte:6'), ('size', '20')]), contract);
    validator.validate(read('/api/alumno/aggregate', [('op', 'count'), ('groupBy', 'cursoId')]), contract);
    const plain = OperationProposal(method: 'GET', path: '/api/alumno', body: null);
    validator.validate(plain, contract);
    expect(plain.encode(), '{"method":"GET","path":"/api/alumno","body":null}');
    expect(read('/api/alumno/query', [('filter', 'nombre:eq:Ana Paz')]).cacheKey,
        '/api/alumno/query?filter=nombre%3Aeq%3AAna+Paz');
  });

  test('undeclared, repeated, out-of-range, enum-violating or missing parameters are rejected', () {
    for (final proposal in [
      read('/api/alumno/query', [('secret', 'x')]),
      read('/api/alumno/query', [('size', '999')]),
      read('/api/alumno/query', [('size', '1'), ('size', '2')]),
      read('/api/alumno/query', [('filter', 'a:eq:b\nc')]),
      read('/api/alumno/aggregate', [('op', 'drop')]),
      read('/api/alumno/aggregate', [('groupBy', 'cursoId')]),
      read('/api/alumno', [('filter', 'nombre:eq:Ana')]),
      const OperationProposal(method: 'POST', path: '/api/alumno', body: {'nombre': 'A', 'apellido': 'B', 'cursoId': cursoId},
          query: [QueryParameter('size', '1')]),
    ]) {
      expect(() => validator.validate(proposal, contract), throwsFormatException, reason: proposal.encode());
    }
  });

  test('enum fields in a request body only accept their literals', () {
    validator.validate(
        const OperationProposal(method: 'POST', path: '/api/curso', body: {'grado': 1, 'seccion': 'A', 'turno': 'TARDE'}),
        contract);
    expect(
      () => validator.validate(
          const OperationProposal(method: 'POST', path: '/api/curso', body: {'grado': 1, 'seccion': 'A', 'turno': 'tarde'}),
          contract),
      throwsFormatException,
    );
  });
}

const cursoId = '11111111-1111-4111-8111-111111111111';
