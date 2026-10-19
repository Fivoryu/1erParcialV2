import 'dart:convert';

import 'package:diagrammer_mobile/domain/agent_eval.dart';
import 'package:diagrammer_mobile/domain/backend_schema.dart';
import 'package:flutter_test/flutter_test.dart';

import 'fixtures/colegio_contract.dart';

void main() {
  final schema = BackendSchema.parse(colegioContract());
  final cases = const AgentEvalGenerator().generate(schema);

  test('the set comes from the contract: entities, enums, relations and numbers', () {
    final phrases = cases.map((c) => c.phrase).toList();

    expect(phrases, containsAll([
      'mostrame los alumnos',
      'cuántos cursos hay',
      'los alumnos con curso del turno manana',
      'cuántos alumnos hay por curso del turno',
      'cuál es el promedio de edad de los alumnos',
      'cursos con grado mayor a 3',
    ]));
    final byTurno = cases.firstWhere((c) => c.phrase == 'los alumnos con curso del turno manana');
    expect(byTurno.expected, {
      'tag': 'query',
      'entity': 'alumno',
      'filters': [{'field': 'curso.turno', 'value': 'MANANA'}],
    });
    expect(jsonEncode(cases.first.toJson()), contains('"phrase"'));
  });

  test('scoring applies the app validation and names each failure', () {
    final byTurno = cases.firstWhere((c) => c.phrase == 'los alumnos con curso del turno manana');
    final average = cases.firstWhere((c) => c.phrase == 'cuál es el promedio de edad de los alumnos');
    final scorer = AgentEvalScorer(schema);

    final score = scorer.score([byTurno, average, byTurno, average], [
      '{"tag":"query","entity":"alumnos","filter":[{"field":"turno","op":"eq","value":"mañana"}]}',
      '{"tag":"report","entity":"alumno","metric":"avg","field":"edad"}',
      '{"tag":"query","entity":"alumno","filter":[{"field":"curso.turno","value":"tarde"}]}',
      'Claro, el promedio es 7',
    ], latencies: const [Duration(milliseconds: 900), Duration(milliseconds: 1200),
      Duration(milliseconds: 800), Duration(milliseconds: 3000)]);

    expect(score.passed, 2);
    expect(score.accuracy, 0.5);
    expect(score.failures, [
      '"los alumnos con curso del turno manana": filter curso.turno=tarde != MANANA',
      '"cuál es el promedio de edad de los alumnos": invalid JSON',
    ]);
    expect(score.p50, const Duration(milliseconds: 1200));
    expect(score.p90, const Duration(milliseconds: 3000));
  });
}
