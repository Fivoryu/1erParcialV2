import 'agent_models.dart';
import 'backend_schema.dart';
import 'operation_proposal.dart';

/// Runs one read the model asked for. The model only names the entity, the
/// filters and the shape of the answer; values are grounded against the real
/// contract and data, and every number is computed by the backend (or by this
/// code for older backends), never by the model.
typedef QueryGateway = Future<AgentToolResult> Function(OperationProposal proposal);

enum QueryShape { list, count, aggregate }

class FilterSpec {
  const FilterSpec(this.field, this.op, this.value);
  final String field;
  final String op;
  final dynamic value;

  String get encoded => '$field:$op:${value is List ? value.join('|') : value}';

  @override
  String toString() => encoded;
}

/// The model asked for something this backend cannot answer as stated; the
/// user gets a question back, optionally with the real options to choose from.
class QueryClarification implements Exception {
  const QueryClarification(this.message, {this.options = const []});
  final String message;
  final List<String> options;

  @override
  String toString() => message;
}

class QuerySpec {
  const QuerySpec({
    required this.entity,
    required this.shape,
    this.filters = const [],
    this.sortField,
    this.sortDesc = false,
    this.limit = 20,
    this.metricOp = 'count',
    this.metricField,
    this.groupBy,
    this.respond = 'show',
  });

  final EntitySchema entity;
  final QueryShape shape;
  final List<FilterSpec> filters;
  final String? sortField;
  final bool sortDesc;
  final int limit;
  final String metricOp;
  final String? metricField;
  final String? groupBy;

  /// `show` renders the result from a template; `answer` asks the model to
  /// reason over the (already computed, bounded) result.
  final String respond;

  static const operators = {'eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'contains', 'in', 'is_null'};
  static const metrics = {'count', 'sum', 'avg', 'min', 'max'};

  /// Validates a model decision (`tag` query or report) against the schema.
  /// Field names are matched case/accent-insensitively and a bare name that is
  /// the unique suffix of a relation path resolves to it (`turno` -> `curso.turno`).
  factory QuerySpec.fromModel(Map<String, dynamic> payload, BackendSchema schema) {
    final entity = schema.entity(_entityName(payload['entity'], schema));
    if (entity == null) {
      throw QueryClarification('No conozco esa entidad en este backend.',
          options: schema.entities.keys.toList());
    }
    final report = payload['tag'] == 'report';
    final filters = <FilterSpec>[];
    final rawFilters = payload['filter'] ?? payload['filters'] ?? const [];
    if (rawFilters is! List) throw const FormatException('filter must be a list.');
    for (final raw in rawFilters) {
      if (raw is! Map || raw['field'] is! String) throw const FormatException('Invalid filter.');
      final op = (raw['op'] ?? 'eq').toString();
      if (!operators.contains(op)) throw FormatException('Unknown operator $op.');
      filters.add(FilterSpec(resolveField(entity, schema, raw['field'] as String), op, raw['value']));
    }
    final metric = (payload['metric'] ?? 'count').toString();
    if (report && !metrics.contains(metric)) throw FormatException('Unknown metric $metric.');
    final metricField = payload['field'] is String
        ? resolveField(entity, schema, payload['field'] as String)
        : null;
    if (report && metric != 'count' && metricField == null) {
      throw QueryClarification('¿Sobre qué campo querés calcular $metric?');
    }
    final groupBy = payload['groupBy'] is String
        ? resolveField(entity, schema, payload['groupBy'] as String)
        : null;
    final sort = payload['sort'] is String ? resolveField(entity, schema, payload['sort'] as String) : null;
    final limit = payload['limit'] is int ? payload['limit'] as int : 20;
    if (metricField != null && report && metric != 'count') {
      final info = _info(entity, schema, metricField);
      if ((metric == 'sum' || metric == 'avg') && !(info?.numeric ?? false)) {
        throw QueryClarification('$metricField no es un campo numérico.');
      }
      if ((metric == 'min' || metric == 'max') && !((info?.numeric ?? false) || (info?.temporal ?? false))) {
        throw QueryClarification('$metricField no es un campo numérico ni de fecha.');
      }
    }
    return QuerySpec(
      entity: entity,
      shape: !report ? QueryShape.list : (metric == 'count' && metricField == null ? QueryShape.count : QueryShape.aggregate),
      filters: filters,
      sortField: sort,
      sortDesc: payload['desc'] == true,
      limit: limit.clamp(1, schema.maxPageSize),
      metricOp: metric,
      metricField: metricField,
      groupBy: groupBy,
      respond: payload['respond'] == 'answer' ? 'answer' : 'show',
    );
  }

  static String? _entityName(dynamic raw, BackendSchema schema) {
    if (raw is! String) return null;
    final wanted = normalize(raw);
    for (final name in schema.entities.keys) {
      final plain = normalize(name);
      if (plain == wanted || '${plain}s' == wanted || '${plain}es' == wanted) return name;
    }
    return null;
  }

  static String resolveField(EntitySchema entity, BackendSchema schema, String raw) {
    final names = schema.queryCapable ? entity.queryFields.keys : entity.fields.keys;
    final wanted = normalize(raw);
    for (final name in names) {
      if (normalize(name) == wanted) return name;
    }
    final suffix = names.where((name) => normalize(name).endsWith('.$wanted')).toList();
    if (suffix.length == 1) return suffix.single;
    throw QueryClarification('No encontré el campo "$raw" en ${entity.name}.',
        options: names.where((name) => name != 'id').toList());
  }

  static QueryFieldInfo? _info(EntitySchema entity, BackendSchema schema, String field) {
    final query = entity.queryFields[field];
    if (query != null) return query;
    final plain = entity.fields[field];
    if (plain == null) return null;
    return QueryFieldInfo(
      name: field,
      kind: switch (plain.type) {
        'integer' => QueryKind.integer,
        'number' => QueryKind.double,
        'boolean' => QueryKind.boolean,
        _ when plain.literals.isNotEmpty => QueryKind.enumeration,
        _ when plain.format == 'date' => QueryKind.date,
        _ when plain.format == 'date-time' => QueryKind.datetime,
        _ when plain.format == 'uuid' => QueryKind.uuid,
        _ => QueryKind.string,
      },
      literals: plain.literals,
    );
  }
}

/// Lowercase, without accents and without anything but letters and digits.
String normalize(String value) {
  const accents = {'á': 'a', 'é': 'e', 'í': 'i', 'ó': 'o', 'ú': 'u', 'ü': 'u', 'ñ': 'n'};
  final buffer = StringBuffer();
  for (final rune in value.toLowerCase().runes) {
    final character = String.fromCharCode(rune);
    final mapped = accents[character] ?? character;
    if (RegExp(r'[a-z0-9.]').hasMatch(mapped)) buffer.write(mapped);
  }
  return buffer.toString();
}

class QueryOutcome {
  const QueryOutcome({
    required this.spec,
    required this.filters,
    this.records = const [],
    this.total,
    this.buckets = const [],
    this.labels = const {},
    this.complete = true,
    this.notes = const [],
  });
  final QuerySpec spec;

  /// Filters exactly as sent, after grounding (real enum literals and values).
  final List<FilterSpec> filters;
  final List<Map<String, dynamic>> records;
  final int? total;
  final List<({dynamic group, dynamic value})> buckets;

  /// Reference field -> id -> human label of the referenced record.
  final Map<String, Map<String, String>> labels;
  final bool complete;
  final List<String> notes;
}

class QueryPlanner {
  QueryPlanner({
    required this.schema,
    required this.gateway,
    DateTime Function()? now,
    this.refs = const {},
    this.maxLocalRecords = 500,
  }) : now = now ?? DateTime.now;

  final BackendSchema schema;
  final QueryGateway gateway;
  final DateTime Function() now;
  final Map<String, RecordRef> refs;
  final int maxLocalRecords;
  final _values = <String, List<String>>{};

  Future<QueryOutcome> run(QuerySpec spec) async {
    final filters = <FilterSpec>[];
    for (final filter in spec.filters) {
      filters.addAll(await _ground(spec.entity, filter));
    }
    final outcome = schema.queryCapable ? await _server(spec, filters) : await _local(spec, filters);
    final labels = await _labels(spec.entity, outcome.records, outcome.buckets, spec.groupBy);
    final notes = [...outcome.notes];
    if (spec.shape == QueryShape.list && outcome.records.isEmpty && filters.isNotEmpty) {
      notes.add('Los valores existen, pero ningún registro cumple todos los filtros.');
    }
    return QueryOutcome(
      spec: spec,
      filters: filters,
      records: outcome.records,
      total: outcome.total,
      buckets: outcome.buckets,
      labels: labels,
      complete: outcome.complete,
      notes: notes,
    );
  }

  // ---- grounding -------------------------------------------------------

  Future<List<FilterSpec>> _ground(EntitySchema entity, FilterSpec filter) async {
    final info = QuerySpec._info(entity, schema, filter.field)!;
    final op = filter.op;
    final value = filter.value;
    if (op == 'is_null') return [FilterSpec(filter.field, op, value == false || value == 'false' ? 'false' : 'true')];
    if (info.temporal && value is Map) return _relativeDate(filter.field, op, value, info.kind == QueryKind.datetime);
    final raws = op == 'in' ? (value is List ? value : value.toString().split(RegExp(r'\s*[|,]\s*'))) : [value];
    final grounded = <String>[];
    for (final raw in raws) {
      if (raw == null) throw QueryClarification('Falta el valor para ${filter.field}.');
      grounded.add(await _groundValue(entity, filter.field, info, op, raw));
    }
    return [FilterSpec(filter.field, op, op == 'in' ? grounded : grounded.single)];
  }

  Future<String> _groundValue(EntitySchema entity, String field, QueryFieldInfo info, String op, dynamic raw) async {
    final text = raw.toString().trim();
    switch (info.kind) {
      case QueryKind.enumeration:
        return _match(text, info.literals, field) ??
            (throw QueryClarification('"$text" no es un valor de $field.', options: info.literals));
      case QueryKind.integer:
      case QueryKind.long:
        final number = _integer(text);
        if (number == null) throw QueryClarification('"$text" no es un número entero para $field.');
        return number.toString();
      case QueryKind.double:
      case QueryKind.decimal:
        final number = num.tryParse(text.replaceAll(',', '.'));
        if (number == null) throw QueryClarification('"$text" no es un número para $field.');
        return number.toString();
      case QueryKind.boolean:
        final plain = normalize(text);
        if ({'si', 'true', 'verdadero', 'yes', '1'}.contains(plain)) return 'true';
        if ({'no', 'false', 'falso', '0'}.contains(plain)) return 'false';
        throw QueryClarification('"$text" no es sí/no para $field.');
      case QueryKind.uuid:
        if (text.startsWith('#')) {
          final ref = refs[text];
          if (ref == null) throw const QueryClarification('No reconozco esa referencia; volvé a listar los registros.');
          return ref.id;
        }
        if (!RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(text)) {
          throw QueryClarification('Para $field necesito elegir un registro concreto.');
        }
        return text;
      case QueryKind.date:
      case QueryKind.datetime:
        if (DateTime.tryParse(text) == null) throw QueryClarification('"$text" no es una fecha válida para $field.');
        return text;
      case QueryKind.string:
        if (op == 'contains' || !schema.queryCapable) return text;
        final existing = await _distinct(entity, field);
        return _match(text, existing, field) ??
            (throw QueryClarification('No encontré "$text" en $field.', options: existing.take(8).toList()));
    }
  }

  /// Exact normalized match, else a unique containment match; null if none.
  /// Several containment matches are ambiguous and ask the user.
  String? _match(String wanted, List<String> candidates, String field) {
    final plain = normalize(wanted);
    for (final candidate in candidates) {
      if (normalize(candidate) == plain) return candidate;
    }
    final partial = candidates
        .where((c) => plain.isNotEmpty && (normalize(c).contains(plain) || plain.contains(normalize(c))))
        .toList();
    if (partial.length == 1) return partial.single;
    if (partial.length > 1) {
      throw QueryClarification('"$wanted" coincide con varios valores de $field.', options: partial.take(8).toList());
    }
    return null;
  }

  /// "1", "1ro", "1°", "1º", "primero", "segunda" -> 1.
  static int? _integer(String text) {
    final digits = RegExp(r'^(-?\d+)\s*(?:ro|ra|do|da|er|to|ta|mo|ma|no|na|vo|va|°|º|o|a)?$').firstMatch(text.trim());
    if (digits != null) return int.parse(digits.group(1)!);
    const words = {
      'primero': 1, 'primer': 1, 'primera': 1, 'segundo': 2, 'segunda': 2, 'tercero': 3, 'tercer': 3,
      'tercera': 3, 'cuarto': 4, 'cuarta': 4, 'quinto': 5, 'quinta': 5, 'sexto': 6, 'sexta': 6,
      'uno': 1, 'una': 1, 'dos': 2, 'tres': 3, 'cuatro': 4, 'cinco': 5, 'seis': 6, 'siete': 7, 'ocho': 8,
      'nueve': 9, 'diez': 10, 'veinte': 20, 'treinta': 30, 'cien': 100,
    };
    return words[normalize(text)];
  }

  List<FilterSpec> _relativeDate(String field, String op, Map value, bool datetime) {
    final today = DateTime(now().year, now().month, now().day);
    late DateTime start, end;
    final relative = value['relative'];
    if (value['last_days'] is int) {
      end = today.add(const Duration(days: 1));
      start = end.subtract(Duration(days: value['last_days'] as int));
    } else if (value['next_days'] is int) {
      start = today;
      end = today.add(Duration(days: (value['next_days'] as int) + 1));
    } else {
      switch (relative) {
        case 'today':
          start = today;
          end = today.add(const Duration(days: 1));
        case 'yesterday':
          start = today.subtract(const Duration(days: 1));
          end = today;
        case 'tomorrow':
          start = today.add(const Duration(days: 1));
          end = today.add(const Duration(days: 2));
        case 'this_week' || 'last_week':
          final monday = today.subtract(Duration(days: today.weekday - 1));
          start = relative == 'this_week' ? monday : monday.subtract(const Duration(days: 7));
          end = start.add(const Duration(days: 7));
        case 'this_month' || 'last_month':
          start = relative == 'this_month'
              ? DateTime(today.year, today.month)
              : DateTime(today.year, today.month - 1);
          end = DateTime(start.year, start.month + 1);
        case 'this_year' || 'last_year':
          start = DateTime(relative == 'this_year' ? today.year : today.year - 1);
          end = DateTime(start.year + 1);
        default:
          throw QueryClarification('No entiendo el período "$relative".');
      }
    }
    String format(DateTime value) =>
        datetime ? value.toIso8601String().substring(0, 19) : value.toIso8601String().substring(0, 10);
    // A period is a half-open range [start, end).
    final lastDay = datetime ? end.subtract(const Duration(seconds: 1)) : end.subtract(const Duration(days: 1));
    return switch (op) {
      'gt' => [FilterSpec(field, 'gte', format(end))],
      'gte' => [FilterSpec(field, 'gte', format(start))],
      'lt' => [FilterSpec(field, 'lt', format(start))],
      'lte' => [FilterSpec(field, 'lte', format(lastDay))],
      _ => [FilterSpec(field, 'gte', format(start)), FilterSpec(field, 'lte', format(lastDay))],
    };
  }

  Future<List<String>> _distinct(EntitySchema entity, String field) async {
    final key = '${entity.name}:$field';
    final cached = _values[key];
    if (cached != null) return cached;
    final result = await gateway(OperationProposal(
      method: 'GET',
      path: '${entity.path}/values',
      body: null,
      query: [QueryParameter('field', field)],
    ));
    final values = result.data is List
        ? (result.data as List).where((v) => v != null).map((v) => v.toString()).toList()
        : <String>[];
    return _values[key] = values;
  }

  // ---- execution --------------------------------------------------------

  Future<QueryOutcome> _server(QuerySpec spec, List<FilterSpec> filters) async {
    final filterParameters = [for (final filter in filters) QueryParameter('filter', filter.encoded)];
    if (spec.shape == QueryShape.list) {
      final result = await gateway(OperationProposal(
        method: 'GET',
        path: '${spec.entity.path}/query',
        body: null,
        query: [
          ...filterParameters,
          if (spec.sortField != null) QueryParameter('sort', '${spec.sortField},${spec.sortDesc ? 'desc' : 'asc'}'),
          QueryParameter('size', '${spec.limit}'),
        ],
      ));
      final page = result.data is Map ? result.data as Map : const {};
      final items = page['items'] is List ? page['items'] as List : const [];
      final total = page['total'] is int ? page['total'] as int : items.length;
      return QueryOutcome(
        spec: spec,
        filters: filters,
        records: [for (final item in items) if (item is Map) Map<String, dynamic>.from(item)],
        total: total,
        complete: result.complete || total <= items.length,
        notes: [if (total > items.length) 'Mostrando ${items.length} de $total registros.'],
      );
    }
    final result = await gateway(OperationProposal(
      method: 'GET',
      path: '${spec.entity.path}/aggregate',
      body: null,
      query: [
        QueryParameter('op', spec.metricOp),
        if (spec.metricField != null) QueryParameter('field', spec.metricField!),
        if (spec.groupBy != null) QueryParameter('groupBy', spec.groupBy!),
        ...filterParameters,
      ],
    ));
    final rows = result.data is List ? result.data as List : const [];
    final buckets = [
      for (final row in rows)
        if (row is Map) (group: row['group'], value: row['value']),
    ];
    return QueryOutcome(
      spec: spec,
      filters: filters,
      buckets: buckets,
      total: spec.groupBy == null && buckets.isNotEmpty && buckets.single.value is num
          ? (buckets.single.value as num).toInt()
          : null,
    );
  }

  /// Older backends without /query: read the list and compute here, with a
  /// cap; anything beyond it is reported as possibly incomplete.
  Future<QueryOutcome> _local(QuerySpec spec, List<FilterSpec> filters) async {
    for (final field in [...filters.map((f) => f.field), spec.groupBy, spec.metricField, spec.sortField]) {
      if (field != null && field.contains('.')) {
        throw const QueryClarification(
            'Este backend es anterior a las consultas: no puede filtrar por datos de otra entidad. Regeneralo para habilitarlo.');
      }
    }
    final result = await gateway(OperationProposal(method: 'GET', path: spec.entity.path, body: null));
    final all = result.data is List ? result.data as List : const [];
    final capped = all.length > maxLocalRecords;
    final records = [
      for (final item in all.take(maxLocalRecords))
        if (item is Map && filters.every((f) => _matches(Map<String, dynamic>.from(item), f)))
          Map<String, dynamic>.from(item),
    ];
    final notes = [
      if (capped) 'Este backend no filtra en el servidor: revisé solo los primeros $maxLocalRecords registros; el resultado puede estar incompleto.',
    ];
    if (spec.sortField != null) {
      records.sort((a, b) => _compare(a[spec.sortField], b[spec.sortField]) * (spec.sortDesc ? -1 : 1));
    }
    if (spec.shape == QueryShape.list) {
      return QueryOutcome(
        spec: spec,
        filters: filters,
        records: records.take(spec.limit).toList(),
        total: records.length,
        complete: !capped,
        notes: [...notes, if (records.length > spec.limit) 'Mostrando ${spec.limit} de ${records.length} registros.'],
      );
    }
    final groups = <dynamic, List<Map<String, dynamic>>>{};
    for (final record in records) {
      groups.putIfAbsent(spec.groupBy == null ? null : record[spec.groupBy], () => []).add(record);
    }
    final keys = groups.keys.toList()..sort(_compare);
    final buckets = [
      for (final key in keys) (group: key, value: _aggregate(spec, groups[key]!)),
    ];
    return QueryOutcome(
      spec: spec,
      filters: filters,
      buckets: buckets.isEmpty && spec.groupBy == null ? [(group: null, value: _aggregate(spec, const []))] : buckets,
      total: spec.groupBy == null ? records.length : null,
      complete: !capped,
      notes: notes,
    );
  }

  static num? _aggregate(QuerySpec spec, List<Map<String, dynamic>> records) {
    if (spec.metricOp == 'count') {
      return spec.metricField == null ? records.length : records.where((r) => r[spec.metricField] != null).length;
    }
    final values = records.map((r) => r[spec.metricField]).whereType<num>().toList();
    if (values.isEmpty) return null;
    return switch (spec.metricOp) {
      'sum' => values.reduce((a, b) => a + b),
      'avg' => values.reduce((a, b) => a + b) / values.length,
      'min' => values.reduce((a, b) => a < b ? a : b),
      _ => values.reduce((a, b) => a > b ? a : b),
    };
  }

  static bool _matches(Map<String, dynamic> record, FilterSpec filter) {
    final actual = record[filter.field];
    final wanted = filter.value;
    bool equal(dynamic expected) => actual is num
        ? num.tryParse(expected.toString()) == actual
        : normalize(actual?.toString() ?? '') == normalize(expected.toString());
    return switch (filter.op) {
      'eq' => equal(wanted),
      'ne' => !equal(wanted),
      'in' => (wanted as List).any(equal),
      'contains' => normalize(actual?.toString() ?? '').contains(normalize(wanted.toString())),
      'is_null' => (actual == null) == (wanted == 'true'),
      'gt' => actual != null && _compare(actual, _typed(actual, wanted)) > 0,
      'gte' => actual != null && _compare(actual, _typed(actual, wanted)) >= 0,
      'lt' => actual != null && _compare(actual, _typed(actual, wanted)) < 0,
      _ => actual != null && _compare(actual, _typed(actual, wanted)) <= 0,
    };
  }

  static dynamic _typed(dynamic actual, dynamic wanted) =>
      actual is num ? num.tryParse(wanted.toString()) : wanted.toString();

  static int _compare(dynamic a, dynamic b) {
    if (a == null && b == null) return 0;
    if (a == null) return -1;
    if (b == null) return 1;
    if (a is num && b is num) return a.compareTo(b);
    return a.toString().compareTo(b.toString());
  }

  // ---- labels -----------------------------------------------------------

  /// Human labels for reference ids in records (and in group keys), from the
  /// referenced entity's display fields; ids are never shown as values.
  Future<Map<String, Map<String, String>>> _labels(EntitySchema entity, List<Map<String, dynamic>> records,
      List<({dynamic group, dynamic value})> buckets, String? groupBy) async {
    final labels = <String, Map<String, String>>{};
    if (!schema.queryCapable) return labels;
    for (final reference in entity.references.entries) {
      final target = schema.entity(reference.value);
      if (target == null) continue;
      final ids = <String>{
        for (final record in records)
          if (record[reference.key] is String) record[reference.key] as String,
        if (groupBy == reference.key)
          for (final bucket in buckets)
            if (bucket.group is String) bucket.group as String,
      }.toList();
      if (ids.isEmpty) continue;
      final found = <String, String>{};
      for (var index = 0; index < ids.length; index += 6) {
        final chunk = ids.sublist(index, index + 6 > ids.length ? ids.length : index + 6);
        final result = await gateway(OperationProposal(
          method: 'GET',
          path: '${target.path}/query',
          body: null,
          query: [QueryParameter('filter', 'id:in:${chunk.join('|')}'), QueryParameter('size', '${chunk.length}')],
        ));
        final page = result.data is Map ? result.data as Map : const {};
        for (final item in page['items'] is List ? page['items'] as List : const []) {
          if (item is Map && item['id'] is String) found[item['id'] as String] = label(target, item);
        }
      }
      labels[reference.key] = found;
    }
    return labels;
  }

  static String label(EntitySchema entity, Map record) {
    final parts = [
      for (final field in entity.readableFields)
        if (record[field] != null && record[field].toString().isNotEmpty) record[field].toString(),
    ];
    return parts.isEmpty ? entity.name : parts.join(' ');
  }
}
