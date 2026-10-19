import 'operation_proposal.dart';

class OpenApiValidator {
  const OpenApiValidator();
  static final _uuid = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
  );

  void validate(OperationProposal proposal, Map<String, dynamic> openApi) {
    const allowed = {'GET', 'POST', 'PUT', 'DELETE'};
    if (!allowed.contains(proposal.method) ||
        !proposal.path.startsWith('/') ||
        proposal.path.contains('://') ||
        proposal.path.contains('..')) {
      throw const FormatException('Unsupported operation.');
    }
    final paths = openApi['paths'];
    if (paths is! Map) {
      throw const FormatException('The selected contract has no paths.');
    }
    final pathItem = _matchPath(
      Map<String, dynamic>.from(paths),
      proposal.path,
    );
    if (pathItem == null) {
      throw const FormatException(
        'The proposed route is not in the selected contract.',
      );
    }
    final operation = pathItem[proposal.method.toLowerCase()];
    if (operation is! Map) {
      throw const FormatException(
        'The selected contract does not allow that method.',
      );
    }
    _validateQuery(proposal, operation);
    final requestBody = operation['requestBody'];
    if (requestBody is Map) {
      final content = requestBody['content'];
      final media = content is Map ? content['application/json'] : null;
      final schema = media is Map ? media['schema'] : null;
      if (schema is Map) {
        _validateSchema(
          proposal.body,
          _resolve(Map<String, dynamic>.from(schema), openApi),
          openApi,
        );
      }
    } else if (proposal.body != null) {
      throw const FormatException(
        'This operation does not accept a request body.',
      );
    }
  }

  /// Mirrors the platform gateway: only GET, only declared `in: query`
  /// parameters, non-array parameters once, enum and integer bounds, bounded
  /// values without control characters, and required parameters present.
  void _validateQuery(OperationProposal proposal, Map operation) {
    final declared = <String, Map>{
      for (final parameter in operation['parameters'] as List? ?? const [])
        if (parameter is Map && parameter['in'] == 'query' && parameter['name'] is String)
          parameter['name'] as String: parameter,
    };
    if (proposal.query.isEmpty && !declared.values.any((p) => p['required'] == true)) return;
    if (proposal.method != 'GET' || proposal.query.length > 24) {
      throw const FormatException('Query parameters are only allowed on reads.');
    }
    final counts = <String, int>{};
    for (final parameter in proposal.query) {
      final declaration = declared[parameter.name];
      if (declaration == null) {
        throw FormatException('Unknown query parameter: ${parameter.name}');
      }
      if (parameter.value.length > 256 || RegExp(r'[\x00-\x1F\x7F]').hasMatch(parameter.value)) {
        throw const FormatException('Query value is too long or contains control characters.');
      }
      final schema = declaration['schema'] is Map ? declaration['schema'] as Map : const {};
      final repeatable = schema['type'] == 'array';
      counts[parameter.name] = (counts[parameter.name] ?? 0) + 1;
      if (counts[parameter.name]! > 1 && !repeatable) {
        throw FormatException('Query parameter repeated: ${parameter.name}');
      }
      final valueSchema = repeatable && schema['items'] is Map ? schema['items'] as Map : schema;
      final allowed = valueSchema['enum'];
      if (allowed is List && !allowed.contains(parameter.value)) {
        throw FormatException('Query value not allowed for ${parameter.name}.');
      }
      if (valueSchema['type'] == 'integer') {
        final number = int.tryParse(parameter.value);
        final minimum = valueSchema['minimum'], maximum = valueSchema['maximum'];
        if (number == null ||
            (minimum is num && number < minimum) ||
            (maximum is num && number > maximum)) {
          throw FormatException('Query value out of range for ${parameter.name}.');
        }
      }
    }
    for (final entry in declared.entries) {
      if (entry.value['required'] == true && !counts.containsKey(entry.key)) {
        throw FormatException('Missing query parameter: ${entry.key}');
      }
    }
  }

  Map<String, dynamic>? _matchPath(Map<String, dynamic> paths, String actual) {
    final direct = paths[actual];
    if (direct is Map) return Map<String, dynamic>.from(direct);
    final segments = actual.split('/');
    for (final entry in paths.entries) {
      final template = entry.key.split('/');
      if (template.length != segments.length || entry.value is! Map) continue;
      var matches = true;
      for (var i = 0; i < template.length; i++) {
        final variable =
            template[i].startsWith('{') && template[i].endsWith('}');
        if (variable && !_uuid.hasMatch(segments[i])) {
          matches = false;
          break;
        }
        if (!variable && template[i] != segments[i]) {
          matches = false;
          break;
        }
      }
      if (matches) return Map<String, dynamic>.from(entry.value as Map);
    }
    return null;
  }

  Map<String, dynamic> _resolve(
    Map<String, dynamic> schema,
    Map<String, dynamic> openApi,
  ) {
    final ref = schema[r'$ref'];
    if (ref is! String) return schema;
    if (!ref.startsWith('#/components/schemas/')) {
      throw const FormatException(
        'Only local component schemas are supported.',
      );
    }
    final name = ref.substring('#/components/schemas/'.length);
    final components = openApi['components'];
    final schemas = components is Map ? components['schemas'] : null;
    final resolved = schemas is Map ? schemas[name] : null;
    if (resolved is! Map) {
      throw const FormatException('The contract references an unknown schema.');
    }
    return Map<String, dynamic>.from(resolved);
  }

  void _validateSchema(
    dynamic value,
    Map<String, dynamic> schema,
    Map<String, dynamic> openApi,
  ) {
    schema = _resolve(schema, openApi);
    if (value == null) {
      if (schema['nullable'] == true) return;
      throw const FormatException('A required value cannot be null.');
    }
    final type = schema['type'];
    if (type == 'object') {
      if (value is! Map) {
        throw const FormatException('Request body must be an object.');
      }
      final properties = Map<String, dynamic>.from(
        schema['properties'] as Map? ?? {},
      );
      final required = (schema['required'] as List? ?? const []).cast<String>();
      for (final key in required) {
        if (!value.containsKey(key)) {
          throw FormatException('Missing required field: $key');
        }
      }
      if (value.keys.any(
        (key) => key is! String || !properties.containsKey(key),
      )) {
        throw const FormatException('Request body contains an unknown field.');
      }
      value.forEach(
        (key, child) => _validateSchema(
          child,
          Map<String, dynamic>.from(properties[key] as Map),
          openApi,
        ),
      );
    } else if (type == 'array') {
      if (value is! List) {
        throw const FormatException('Request body must be an array.');
      }
      final items = schema['items'];
      if (items is! Map) {
        throw const FormatException('Array schema has no item type.');
      }
      for (final item in value) {
        _validateSchema(item, Map<String, dynamic>.from(items), openApi);
      }
    } else if (type == 'string') {
      if (value is! String) {
        throw const FormatException('Value must be a string.');
      }
      if (schema['format'] == 'uuid' && !_uuid.hasMatch(value)) {
        throw const FormatException('Value must be a UUID.');
      }
      final literals = schema['enum'];
      if (literals is List && !literals.contains(value)) {
        throw FormatException('Value must be one of ${literals.join(', ')}.');
      }
    } else if (type == 'integer' && value is! int) {
      throw const FormatException('Value must be an integer.');
    } else if (type == 'number' && value is! num) {
      throw const FormatException('Value must be a number.');
    } else if (type == 'boolean' && value is! bool) {
      throw const FormatException('Value must be a boolean.');
    }
  }
}
