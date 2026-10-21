import 'package:diagrammer_mobile/domain/agent_models.dart';
import 'package:diagrammer_mobile/domain/operation_proposal.dart';

/// In-memory stand-in for a generated backend behind the platform gateway.
/// It answers the CRUD list and the /query, /values and /aggregate helper
/// routes with the same semantics as the generated QuerySupport (filters
/// `field:op:value`, `in` separated by `|`, `ne` matching missing values,
/// relation paths one level deep through `<relation>Id`).
class FakeBackend {
  FakeBackend(this.tables);

  /// Table name -> records (each with an `id`).
  final Map<String, List<Map<String, dynamic>>> tables;
  final calls = <OperationProposal>[];

  Future<AgentToolResult> call(OperationProposal proposal) async {
    calls.add(proposal);
    final segments = proposal.path.split('/');
    final helper = segments.last;
    if (RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(helper) && proposal.method == 'GET') {
      final record = (tables[segments[segments.length - 2]] ?? const []).where((r) => r['id'] == helper).firstOrNull;
      return AgentToolResult(data: record, complete: true, fetchedAt: DateTime.utc(2026, 10, 19));
    }
    final table = {'query', 'values', 'aggregate'}.contains(helper) ? segments[segments.length - 2] : segments.last;
    final rows = tables[table] ?? const [];
    final params = <String, List<String>>{};
    for (final parameter in proposal.query) {
      params.putIfAbsent(parameter.name, () => []).add(parameter.value);
    }
    final filtered = rows.where((row) => (params['filter'] ?? const []).every((f) => _matches(row, f))).toList();
    dynamic data;
    switch (helper) {
      case 'query':
        final size = int.parse((params['size'] ?? ['50']).single);
        final sorted = [...filtered];
        final sort = params['sort']?.single;
        if (sort != null) {
          final parts = sort.split(',');
          sorted.sort((a, b) => _compare(_value(a, parts[0]), _value(b, parts[0])) * (parts[1] == 'desc' ? -1 : 1));
        }
        data = {'items': sorted.take(size).toList(), 'total': filtered.length, 'page': 0, 'size': size};
      case 'values':
        final field = params['field']!.single;
        final values = {for (final row in filtered) _value(row, field)}..remove(null);
        data = values.toList()..sort(_compare);
      case 'aggregate':
        final op = params['op']!.single;
        final groupBy = params['groupBy']?.single;
        final field = params['field']?.single;
        final groups = <dynamic, List<Map<String, dynamic>>>{};
        for (final row in filtered) {
          groups.putIfAbsent(groupBy == null ? null : _value(row, groupBy), () => []).add(row);
        }
        if (groupBy == null) groups.putIfAbsent(null, () => []);
        final keys = groups.keys.toList()..sort(_compare);
        data = [
          for (final key in keys)
            {'group': key, 'value': _aggregate(op, field, groups[key]!)},
        ];
      default:
        data = rows;
    }
    return AgentToolResult(data: data, complete: true, fetchedAt: DateTime.utc(2026, 10, 19));
  }

  dynamic _value(Map<String, dynamic> row, String field) {
    if (!field.contains('.')) return row[field];
    final parts = field.split('.');
    final targetId = row['${parts[0]}Id'];
    final target = (tables[parts[0]] ?? const []).where((r) => r['id'] == targetId).firstOrNull;
    return target?[parts[1]];
  }

  bool _matches(Map<String, dynamic> row, String filter) {
    final parts = filter.split(':');
    final field = parts[0], op = parts[1], raw = parts.sublist(2).join(':');
    final actual = _value(row, field);
    bool equal(String expected) => actual?.toString() == expected;
    return switch (op) {
      'eq' => equal(raw),
      'ne' => actual == null || !equal(raw),
      'in' => raw.split('|').any(equal),
      'contains' => (actual?.toString().toLowerCase() ?? '').contains(raw.toLowerCase()),
      'is_null' => (actual == null) == (raw == 'true'),
      'gte' => actual != null && _compare(actual, _typed(actual, raw)) >= 0,
      'gt' => actual != null && _compare(actual, _typed(actual, raw)) > 0,
      'lte' => actual != null && _compare(actual, _typed(actual, raw)) <= 0,
      _ => actual != null && _compare(actual, _typed(actual, raw)) < 0,
    };
  }

  static dynamic _typed(dynamic actual, String raw) => actual is num ? num.parse(raw) : raw;

  static num? _aggregate(String op, String? field, List<Map<String, dynamic>> rows) {
    if (op == 'count') return field == null ? rows.length : rows.where((r) => r[field] != null).length;
    final values = rows.map((r) => r[field]).whereType<num>().toList();
    if (values.isEmpty) return null;
    return switch (op) {
      'sum' => values.reduce((a, b) => a + b),
      'avg' => values.reduce((a, b) => a + b) / values.length,
      'min' => values.reduce((a, b) => a < b ? a : b),
      _ => values.reduce((a, b) => a > b ? a : b),
    };
  }

  static int _compare(dynamic a, dynamic b) {
    if (a == null && b == null) return 0;
    if (a == null) return -1;
    if (b == null) return 1;
    if (a is num && b is num) return a.compareTo(b);
    return a.toString().compareTo(b.toString());
  }
}

const cursoMananaA = '11111111-1111-4111-8111-111111111111';
const cursoMananaC = '22222222-2222-4222-8222-222222222222';
const cursoTardeB = '33333333-3333-4333-8333-333333333333';

FakeBackend colegioData() => FakeBackend({
  'curso': [
    {'id': cursoMananaA, 'grado': 1, 'seccion': 'A', 'turno': 'MANANA'},
    {'id': cursoMananaC, 'grado': 2, 'seccion': 'C', 'turno': 'MANANA'},
    {'id': cursoTardeB, 'grado': 1, 'seccion': 'B', 'turno': 'TARDE'},
  ],
  'alumno': [
    {'id': 'aaaaaaaa-0000-4000-8000-000000000001', 'nombre': 'Ana', 'apellido': 'Paz', 'edad': 6, 'cursoId': cursoMananaA},
    {'id': 'aaaaaaaa-0000-4000-8000-000000000002', 'nombre': 'Beto', 'apellido': 'Rios', 'edad': 7, 'cursoId': cursoMananaA},
    {'id': 'aaaaaaaa-0000-4000-8000-000000000003', 'nombre': 'Carla', 'apellido': 'Soto', 'edad': 6, 'cursoId': cursoTardeB},
    {'id': 'aaaaaaaa-0000-4000-8000-000000000004', 'nombre': 'Dani', 'apellido': 'Vera', 'edad': 8, 'cursoId': cursoMananaC},
  ],
});
