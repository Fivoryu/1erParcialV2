/// Structured, read-only view of one connected backend's frozen OpenAPI
/// contract: its entities, their fields, enum literals, display labels,
/// queryable fields and relations. The agent only ever knows this backend.
library;

/// Query field kinds published by generated backends in `x-query-fields`.
enum QueryKind { string, integer, long, double, decimal, boolean, date, datetime, uuid, enumeration }

class QueryFieldInfo {
  const QueryFieldInfo({
    required this.name,
    required this.kind,
    this.literals = const [],
    this.references,
  });
  final String name;
  final QueryKind kind;
  final List<String> literals;

  /// Entity name a reference id points to (`cursoId` -> `curso`).
  final String? references;

  bool get numeric =>
      kind == QueryKind.integer ||
      kind == QueryKind.long ||
      kind == QueryKind.double ||
      kind == QueryKind.decimal;

  bool get temporal => kind == QueryKind.date || kind == QueryKind.datetime;
}

class FieldInfo {
  const FieldInfo({
    required this.name,
    required this.type,
    required this.required,
    this.format,
    this.literals = const [],
  });
  final String name;
  final String type;
  final String? format;
  final bool required;
  final List<String> literals;

  bool get technical =>
      name == 'id' || format == 'uuid' || (name.endsWith('Id') && type == 'string' && format == 'uuid');
}

class EntitySchema {
  const EntitySchema({
    required this.name,
    required this.path,
    required this.fields,
    required this.displayFields,
    required this.queryFields,
    required this.methods,
  });

  /// Last path segment (`/api/alumno` -> `alumno`), the name the model uses.
  final String name;
  final String path;
  final Map<String, FieldInfo> fields;
  final List<String> displayFields;
  final Map<String, QueryFieldInfo> queryFields;
  final Set<String> methods;

  /// Reference id fields of this entity mapped to the entity they point to.
  Map<String, String> get references => {
    for (final field in queryFields.values)
      if (field.references != null) field.name: field.references!,
  };

  /// Human-facing fields: declared display fields, else every non-technical
  /// scalar. Never an identifier.
  List<String> get readableFields {
    final readable = displayFields.where(fields.containsKey).toList();
    if (readable.isNotEmpty) return readable;
    return fields.values.where((f) => !f.technical && f.type != 'array').map((f) => f.name).toList();
  }
}

class BackendSchema {
  BackendSchema._(this.entities, this.queryCapable, this.operators, this.aggregates, this.maxPageSize);

  final Map<String, EntitySchema> entities;

  /// True when the backend publishes server-side query/values/aggregate.
  final bool queryCapable;
  final List<String> operators;
  final List<String> aggregates;
  final int maxPageSize;

  static const _subRoutes = {'query', 'values', 'aggregate'};

  factory BackendSchema.parse(Map<String, dynamic> openApi) {
    final paths = openApi['paths'];
    if (paths is! Map) throw const FormatException('El contrato no contiene rutas.');
    final schemas = (openApi['components'] is Map ? (openApi['components'] as Map)['schemas'] : null);
    final entities = <String, EntitySchema>{};
    for (final rawKey in paths.keys) {
      if (rawKey is! String ||
          !RegExp(r'^/[A-Za-z0-9_/-]+$').hasMatch(rawKey) ||
          rawKey.endsWith('/')) {
        continue;
      }
      final segments = rawKey.split('/');
      final parent = segments.sublist(0, segments.length - 1).join('/');
      // Generated helper routes (/api/x/query) are capabilities of x, not entities.
      if (_subRoutes.contains(segments.last) && paths.containsKey(parent)) continue;
      final name = segments.last;
      if (entities.containsKey(name)) throw const FormatException('El nombre de la entidad es ambiguo.');
      final item = paths[rawKey];
      final idItem = paths['$rawKey/{id}'];
      final methods = <String>{
        for (final source in [item, idItem])
          if (source is Map)
            for (final method in source.keys)
              if (method is String && {'get', 'post', 'put', 'delete'}.contains(method)) method.toUpperCase(),
      };
      final dto = _dtoSchema(item, idItem, schemas);
      entities[name] = EntitySchema(
        name: name,
        path: rawKey,
        fields: _fields(dto),
        displayFields: _strings(dto?['x-display-fields']),
        queryFields: _queryFields(dto?['x-query-fields']),
        methods: methods,
      );
    }
    final capabilities = openApi['x-agent-capabilities'];
    final capable = capabilities is Map &&
        capabilities['filterSyntax'] == 'field:operator:value' &&
        entities.values.every((entity) => paths.containsKey('${entity.path}/query'));
    return BackendSchema._(
      Map.unmodifiable(entities),
      capable,
      capable ? _strings(capabilities['operators']) : const [],
      capable ? _strings(capabilities['aggregates']) : const [],
      capable && capabilities['maxPageSize'] is int ? capabilities['maxPageSize'] as int : 50,
    );
  }

  EntitySchema? entity(String? name) => name == null ? null : entities[name];

  static Map<String, dynamic>? _dtoSchema(dynamic item, dynamic idItem, dynamic schemas) {
    for (final source in [item, idItem]) {
      if (source is! Map) continue;
      for (final operation in source.values) {
        if (operation is! Map) continue;
        final body = operation['requestBody'];
        final content = body is Map ? body['content'] : null;
        final media = content is Map ? content['application/json'] : null;
        final schema = media is Map ? media['schema'] : null;
        if (schema is! Map) continue;
        final reference = schema[r'$ref'];
        if (reference is String && reference.startsWith('#/components/schemas/') && schemas is Map) {
          final resolved = schemas[reference.substring('#/components/schemas/'.length)];
          if (resolved is Map) return Map<String, dynamic>.from(resolved);
        }
        if (schema['properties'] is Map) return Map<String, dynamic>.from(schema);
      }
    }
    return null;
  }

  static Map<String, FieldInfo> _fields(Map<String, dynamic>? dto) {
    final properties = dto?['properties'];
    if (properties is! Map) return const {};
    final required = _strings(dto?['required']).toSet();
    final result = <String, FieldInfo>{};
    properties.forEach((name, spec) {
      if (name is! String || spec is! Map) return;
      result[name] = FieldInfo(
        name: name,
        type: spec['type'] is String ? spec['type'] as String : (spec[r'$ref'] is String ? 'object' : 'unknown'),
        format: spec['format'] is String ? spec['format'] as String : null,
        required: required.contains(name),
        literals: _strings(spec['enum'] ?? (spec['items'] is Map ? (spec['items'] as Map)['enum'] : null)),
      );
    });
    return Map.unmodifiable(result);
  }

  static Map<String, QueryFieldInfo> _queryFields(dynamic raw) {
    if (raw is! List) return const {};
    final result = <String, QueryFieldInfo>{};
    for (final item in raw) {
      if (item is! Map || item['name'] is! String || item['kind'] is! String) continue;
      final kind = switch (item['kind']) {
        'STRING' => QueryKind.string,
        'INTEGER' => QueryKind.integer,
        'LONG' => QueryKind.long,
        'DOUBLE' => QueryKind.double,
        'DECIMAL' => QueryKind.decimal,
        'BOOLEAN' => QueryKind.boolean,
        'DATE' => QueryKind.date,
        'DATETIME' => QueryKind.datetime,
        'UUID' => QueryKind.uuid,
        'ENUM' => QueryKind.enumeration,
        _ => null,
      };
      if (kind == null) continue;
      final name = item['name'] as String;
      result[name] = QueryFieldInfo(
        name: name,
        kind: kind,
        literals: _strings(item['enum']),
        references: item['references'] is String ? item['references'] as String : null,
      );
    }
    return Map.unmodifiable(result);
  }

  static List<String> _strings(dynamic raw) =>
      raw is List ? List.unmodifiable(raw.whereType<String>()) : const [];
}
