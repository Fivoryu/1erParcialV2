import 'agent_query.dart';
import 'backend_schema.dart';

/// Renders query outcomes as chat markdown from templates: exact values,
/// readable fields only, reference ids replaced by their labels. The model
/// never formats these numbers or records.
class AgentPresenter {
  const AgentPresenter({this.maxColumns = 6});
  final int maxColumns;

  static final uuid = RegExp(r'\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b');

  /// "Entendí: alumno · curso › grado = 1 · curso › turno = Mañana".
  String interpretation(QueryOutcome outcome) {
    final spec = outcome.spec;
    final parts = <String>[
      switch (spec.shape) {
        QueryShape.list => spec.entity.name,
        QueryShape.count => 'cantidad de ${spec.entity.name}',
        QueryShape.aggregate => '${_metric(spec.metricOp)} de ${field(spec.metricField!)} en ${spec.entity.name}',
      },
      for (final filter in outcome.filters) _condition(outcome, filter),
      if (spec.groupBy != null) 'por ${field(_groupField(spec))}',
      if (spec.sortField != null) 'ordenado por ${field(spec.sortField!)}${spec.sortDesc ? ' (desc.)' : ''}',
    ];
    return 'Entendí: ${parts.join(' · ')}';
  }

  /// [rowRefs] are the record refs ("2" for "#2") shown in the `#` column,
  /// so a follow-up can name a row; rows are numbered 1..n without them.
  String render(QueryOutcome outcome, {List<String> rowRefs = const []}) {
    final body = switch (outcome.spec.shape) {
      QueryShape.list => _list(outcome, rowRefs),
      QueryShape.count || QueryShape.aggregate => _buckets(outcome),
    };
    final notes = outcome.notes.map((note) => '_${note}_').join('\n');
    return [
      '*${interpretation(outcome)}*',
      body,
      if (notes.isNotEmpty) notes,
    ].join('\n\n');
  }

  /// Columns shown for an entity: readable fields first, then a label column
  /// per reference, then the remaining non-technical fields, capped.
  List<String> columns(EntitySchema entity) {
    final result = <String>[...entity.readableFields];
    for (final reference in entity.references.keys) {
      result.add(reference);
    }
    for (final field in entity.fields.values) {
      if (!field.technical && field.type != 'array' && !result.contains(field.name)) result.add(field.name);
    }
    return result.take(maxColumns).toList();
  }

  String _list(QueryOutcome outcome, List<String> rowRefs) {
    final entity = outcome.spec.entity;
    if (outcome.records.isEmpty) {
      return outcome.filters.isEmpty
          ? 'No hay registros de ${entity.name}.'
          : 'No hay registros de ${entity.name} con esos filtros.';
    }
    final columns = this.columns(entity);
    final header = ['#', ...columns.map((c) => field(_referenceName(entity, c)))];
    final rows = <List<String>>[];
    for (var index = 0; index < outcome.records.length; index++) {
      final record = outcome.records[index];
      rows.add([
        index < rowRefs.length && rowRefs[index].isNotEmpty ? rowRefs[index] : '${index + 1}',
        for (final column in columns) cell(_display(outcome, entity, column, record[column])),
      ]);
    }
    final total = outcome.total;
    return [
      _table(header, rows),
      if (total != null) 'Total: **$total**',
    ].join('\n\n');
  }

  String _buckets(QueryOutcome outcome) {
    final spec = outcome.spec;
    final label = spec.shape == QueryShape.count ? 'Cantidad' : _capitalize(_metric(spec.metricOp));
    if (spec.groupBy == null) {
      final value = outcome.buckets.isEmpty ? null : outcome.buckets.single.value;
      return spec.shape == QueryShape.count
          ? 'Hay **${number(value ?? 0)}** registros de ${spec.entity.name}.'
          : '$label de ${field(spec.metricField!)}: **${value == null ? 'sin datos' : number(value)}**';
    }
    if (outcome.buckets.isEmpty) return 'No hay registros de ${spec.entity.name} para agrupar.';
    final rows = [
      for (final bucket in outcome.buckets)
        [
          cell(_display(outcome, spec.entity, spec.groupBy!, bucket.group)),
          bucket.value == null ? 'sin datos' : number(bucket.value),
        ],
    ];
    return _table([field(_groupField(spec)), label], rows);
  }

  String _condition(QueryOutcome outcome, FilterSpec filter) {
    final name = field(_referenceName(outcome.spec.entity, filter.field));
    String show(dynamic value) => _display(outcome, outcome.spec.entity, filter.field, value);
    return switch (filter.op) {
      'eq' => '$name = ${show(filter.value)}',
      'ne' => '$name ≠ ${show(filter.value)}',
      'gt' => '$name > ${show(filter.value)}',
      'gte' => '$name ≥ ${show(filter.value)}',
      'lt' => '$name < ${show(filter.value)}',
      'lte' => '$name ≤ ${show(filter.value)}',
      'contains' => '$name contiene "${filter.value}"',
      'in' => '$name ∈ {${(filter.value as List).map(show).join(', ')}}',
      _ => filter.value == 'true' ? 'sin $name' : 'con $name',
    };
  }

  /// A value as a person reads it: labels for reference ids, humanized enum
  /// literals, `—` for missing values; a raw id is never returned.
  String _display(QueryOutcome outcome, EntitySchema entity, String column, dynamic value) {
    if (value == null) return '—';
    final labels = outcome.labels[column];
    if (labels != null && value is String) return labels[value] ?? '—';
    if (value is String && uuid.hasMatch(value)) return '—';
    final literals = entity.queryFields[column]?.literals ?? entity.fields[column]?.literals ?? const [];
    if (value is String && literals.contains(value)) return literal(value);
    if (value is bool) return value ? 'Sí' : 'No';
    if (value is num) return number(value);
    return value.toString();
  }

  /// `cursoId` -> `curso`: a reference column is named after what it shows.
  String _referenceName(EntitySchema entity, String column) =>
      entity.references.containsKey(column) && column.endsWith('Id')
          ? column.substring(0, column.length - 2)
          : column;

  String _groupField(QuerySpec spec) => _referenceName(spec.entity, spec.groupBy!);

  static String _metric(String op) => switch (op) {
    'sum' => 'suma',
    'avg' => 'promedio',
    'min' => 'mínimo',
    'max' => 'máximo',
    _ => 'cantidad',
  };

  /// `curso.fechaNacimiento` -> `curso › fecha nacimiento`.
  static String field(String name) => name
      .split('.')
      .map((part) => part
          .replaceAllMapped(RegExp(r'([a-z0-9])([A-Z])'), (m) => '${m[1]} ${m[2]}')
          .replaceAll('_', ' ')
          .toLowerCase())
      .join(' › ');

  /// `MANANA` -> `Manana`, `MEDIO_TIEMPO` -> `Medio tiempo`.
  static String literal(String value) => _capitalize(value.replaceAll('_', ' ').toLowerCase());

  static String number(dynamic value) {
    if (value is int) return '$value';
    if (value is num) {
      if (value == value.roundToDouble()) return value.toInt().toString();
      return value.toStringAsFixed(2).replaceFirst(RegExp(r'0+$'), '').replaceFirst(RegExp(r'\.$'), '');
    }
    return value.toString();
  }

  static String cell(String value) =>
      value.replaceAll('|', r'\|').replaceAll(RegExp(r'[\r\n]+'), ' ');

  static String _capitalize(String value) => value.isEmpty ? value : value[0].toUpperCase() + value.substring(1);

  static String _table(List<String> header, List<List<String>> rows) => [
    '| ${header.join(' | ')} |',
    '| ${header.map((_) => '---').join(' | ')} |',
    for (final row in rows) '| ${row.join(' | ')} |',
  ].join('\n');

  /// Quality gate for text the model wrote: known record ids become their
  /// labels and any other identifier is removed, so an answer never shows a
  /// UUID unless the user asked for identifiers.
  static String sanitize(String text, {Map<String, String> labels = const {}, bool identifiersRequested = false}) {
    if (identifiersRequested) return text;
    return text.replaceAllMapped(uuid, (match) => labels[match[0]!.toLowerCase()] ?? labels[match[0]!] ?? '(registro)');
  }
}
