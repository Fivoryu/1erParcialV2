import 'dart:convert';

import 'agent_presenter.dart';
import 'agent_query.dart';
import 'backend_schema.dart';

/// One evaluation phrase, as a user would say it (also by voice), and the
/// decision the model is expected to produce for it.
class AgentEvalCase {
  const AgentEvalCase(this.phrase, this.expected);
  final String phrase;

  /// Expected decision fields: tag, entity and, when relevant, metric,
  /// field, groupBy and filters ({field, value}).
  final Map<String, dynamic> expected;

  Map<String, dynamic> toJson() => {'phrase': phrase, 'expected': expected};
}

class AgentEvalScore {
  const AgentEvalScore(this.passed, this.total, this.failures, this.latencies);
  final int passed;
  final int total;
  final List<String> failures;
  final List<Duration> latencies;

  double get accuracy => total == 0 ? 0 : passed / total;

  Duration get p50 => _percentile(0.5);
  Duration get p90 => _percentile(0.9);

  Duration _percentile(double q) {
    if (latencies.isEmpty) return Duration.zero;
    final sorted = [...latencies]..sort();
    return sorted[((sorted.length - 1) * q).round()];
  }
}

/// Builds the evaluation set from the connected backend's contract alone, so
/// every generated backend brings its own set with no per-domain code.
class AgentEvalGenerator {
  const AgentEvalGenerator();

  List<AgentEvalCase> generate(BackendSchema schema) {
    final cases = <AgentEvalCase>[];
    for (final entity in schema.entities.values) {
      final plural = _plural(entity.name);
      cases
        ..add(AgentEvalCase('mostrame los $plural', {'tag': 'query', 'entity': entity.name}))
        ..add(AgentEvalCase('eh… pasame todos los $plural', {'tag': 'query', 'entity': entity.name}))
        ..add(AgentEvalCase('cuántos $plural hay', {'tag': 'report', 'entity': entity.name, 'metric': 'count'}));
      for (final field in entity.queryFields.values) {
        final spoken = AgentPresenter.field(field.name).replaceAll(' › ', ' del ');
        if (field.kind == QueryKind.enumeration && field.literals.isNotEmpty) {
          final value = AgentPresenter.literal(field.literals.first).toLowerCase();
          cases
            ..add(AgentEvalCase('los $plural con $spoken $value', {
              'tag': 'query',
              'entity': entity.name,
              'filters': [{'field': field.name, 'value': field.literals.first}],
            }))
            ..add(AgentEvalCase('cuántos $plural hay por $spoken', {
              'tag': 'report',
              'entity': entity.name,
              'metric': 'count',
              'groupBy': field.name,
            }));
        }
        if (field.numeric && !field.name.contains('.')) {
          cases
            ..add(AgentEvalCase('cuál es el promedio de $spoken de los $plural', {
              'tag': 'report',
              'entity': entity.name,
              'metric': 'avg',
              'field': field.name,
            }))
            ..add(AgentEvalCase('el $spoken más alto entre los $plural', {
              'tag': 'report',
              'entity': entity.name,
              'metric': 'max',
              'field': field.name,
            }))
            ..add(AgentEvalCase('$plural con $spoken mayor a 3', {
              'tag': 'query',
              'entity': entity.name,
              'filters': [{'field': field.name, 'op': 'gt', 'value': '3'}],
            }));
        }
        if (field.kind == QueryKind.date || field.kind == QueryKind.datetime) {
          cases.add(AgentEvalCase('los $plural de esta semana por $spoken', {
            'tag': 'query',
            'entity': entity.name,
            'filters': [{'field': field.name, 'relative': 'this_week'}],
          }));
        }
      }
    }
    return cases;
  }

  static String _plural(String name) {
    final plain = name.toLowerCase();
    if (plain.endsWith('s')) return plain;
    return RegExp(r'[aeiou]$').hasMatch(plain) ? '${plain}s' : '${plain}es';
  }
}

/// Scores raw model outputs against the expected decisions. A decision passes
/// when, after the same validation the app applies (QuerySpec), it has the
/// expected tag, entity, metric, field, groupBy and filter fields/values.
class AgentEvalScorer {
  const AgentEvalScorer(this.schema);
  final BackendSchema schema;

  bool matches(AgentEvalCase evalCase, String raw) => _mismatch(evalCase, raw) == null;

  AgentEvalScore score(List<AgentEvalCase> cases, List<String> outputs, {List<Duration> latencies = const []}) {
    var passed = 0;
    final failures = <String>[];
    for (var index = 0; index < cases.length; index++) {
      final problem = index < outputs.length ? _mismatch(cases[index], outputs[index]) : 'no output';
      if (problem == null) {
        passed++;
      } else {
        failures.add('"${cases[index].phrase}": $problem');
      }
    }
    return AgentEvalScore(passed, cases.length, failures, latencies);
  }

  String? _mismatch(AgentEvalCase evalCase, String raw) {
    final Map<String, dynamic> decision;
    try {
      final decoded = jsonDecode(raw.trim());
      if (decoded is! Map<String, dynamic>) return 'not a JSON object';
      decision = decoded;
    } on FormatException {
      return 'invalid JSON';
    }
    final expected = evalCase.expected;
    if (decision['tag'] != expected['tag']) return 'tag ${decision['tag']} != ${expected['tag']}';
    final QuerySpec spec;
    try {
      spec = QuerySpec.fromModel(decision, schema);
    } on QueryClarification catch (clarification) {
      return 'rejected: ${clarification.message}';
    } on FormatException catch (error) {
      return 'invalid: ${error.message}';
    }
    if (spec.entity.name != expected['entity']) return 'entity ${spec.entity.name} != ${expected['entity']}';
    if (expected['metric'] != null && spec.metricOp != expected['metric']) {
      return 'metric ${spec.metricOp} != ${expected['metric']}';
    }
    if (expected['field'] != null && spec.metricField != expected['field']) {
      return 'field ${spec.metricField} != ${expected['field']}';
    }
    if (expected['groupBy'] != null && spec.groupBy != expected['groupBy']) {
      return 'groupBy ${spec.groupBy} != ${expected['groupBy']}';
    }
    for (final wanted in (expected['filters'] as List? ?? const []).cast<Map>()) {
      final found = spec.filters.where((f) => f.field == wanted['field']).firstOrNull;
      if (found == null) return 'missing filter ${wanted['field']}';
      if (wanted['op'] != null && found.op != wanted['op']) return 'filter op ${found.op} != ${wanted['op']}';
      if (wanted['relative'] != null) {
        if (found.value is! Map || (found.value as Map)['relative'] != wanted['relative']) {
          return 'filter ${wanted['field']} is not relative ${wanted['relative']}';
        }
      } else if (wanted['value'] != null && normalize('${found.value}') != normalize('${wanted['value']}')) {
        return 'filter ${wanted['field']}=${found.value} != ${wanted['value']}';
      }
    }
    return null;
  }
}
