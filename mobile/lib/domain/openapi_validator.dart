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
      throw const FormatException('La operación no es compatible.');
    }
    final paths = openApi['paths'];
    if (paths is! Map) {
      throw const FormatException('El contrato seleccionado no contiene rutas.');
    }
    final pathItem = _matchPath(
      Map<String, dynamic>.from(paths),
      proposal.path,
    );
    if (pathItem == null) {
      throw const FormatException(
        'La ruta propuesta no está en el contrato seleccionado.',
      );
    }
    final operation = pathItem[proposal.method.toLowerCase()];
    if (operation is! Map) {
      throw const FormatException(
        'El contrato seleccionado no permite ese método.',
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
        'Esta operación no admite un cuerpo de solicitud.',
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
      throw const FormatException('Los parámetros de consulta solo se permiten en operaciones de lectura.');
    }
    final counts = <String, int>{};
    for (final parameter in proposal.query) {
      final declaration = declared[parameter.name];
      if (declaration == null) {
        throw FormatException('Parámetro de consulta desconocido: ${parameter.name}');
      }
      if (parameter.value.length > 256 || RegExp(r'[\x00-\x1F\x7F]').hasMatch(parameter.value)) {
        throw const FormatException('El valor del parámetro de consulta es demasiado largo o contiene caracteres de control.');
      }
      final schema = declaration['schema'] is Map ? declaration['schema'] as Map : const {};
      final repeatable = schema['type'] == 'array';
      counts[parameter.name] = (counts[parameter.name] ?? 0) + 1;
      if (counts[parameter.name]! > 1 && !repeatable) {
        throw FormatException('El parámetro de consulta está repetido: ${parameter.name}');
      }
      final valueSchema = repeatable && schema['items'] is Map ? schema['items'] as Map : schema;
      final allowed = valueSchema['enum'];
      if (allowed is List && !allowed.contains(parameter.value)) {
        throw FormatException('El valor de consulta no está permitido para ${parameter.name}.');
      }
      if (valueSchema['type'] == 'integer') {
        final number = int.tryParse(parameter.value);
        final minimum = valueSchema['minimum'], maximum = valueSchema['maximum'];
        if (number == null ||
            (minimum is num && number < minimum) ||
            (maximum is num && number > maximum)) {
          throw FormatException('El valor de consulta está fuera del rango permitido para ${parameter.name}.');
        }
      }
    }
    for (final entry in declared.entries) {
      if (entry.value['required'] == true && !counts.containsKey(entry.key)) {
        throw FormatException('Falta el parámetro de consulta: ${entry.key}');
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
        'Solo se admiten esquemas de componentes locales.',
      );
    }
    final name = ref.substring('#/components/schemas/'.length);
    final components = openApi['components'];
    final schemas = components is Map ? components['schemas'] : null;
    final resolved = schemas is Map ? schemas[name] : null;
    if (resolved is! Map) {
      throw const FormatException('El contrato hace referencia a un esquema desconocido.');
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
      throw const FormatException('Un valor obligatorio no puede ser nulo.');
    }
    final type = schema['type'];
    if (type == 'object') {
      if (value is! Map) {
        throw const FormatException('El cuerpo de la solicitud debe ser un objeto.');
      }
      final properties = Map<String, dynamic>.from(
        schema['properties'] as Map? ?? {},
      );
      final required = (schema['required'] as List? ?? const []).cast<String>();
      for (final key in required) {
        if (!value.containsKey(key)) {
          throw FormatException('Falta el campo obligatorio: $key');
        }
      }
      if (value.keys.any(
        (key) => key is! String || !properties.containsKey(key),
      )) {
        throw const FormatException('El cuerpo de la solicitud contiene un campo desconocido.');
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
        throw const FormatException('El cuerpo de la solicitud debe ser una lista.');
      }
      final items = schema['items'];
      if (items is! Map) {
        throw const FormatException('El esquema de la lista no especifica el tipo de sus elementos.');
      }
      for (final item in value) {
        _validateSchema(item, Map<String, dynamic>.from(items), openApi);
      }
    } else if (type == 'string') {
      if (value is! String) {
        throw const FormatException('El valor debe ser texto.');
      }
      if (schema['format'] == 'uuid' && !_uuid.hasMatch(value)) {
        throw const FormatException('El valor debe ser un UUID.');
      }
      final literals = schema['enum'];
      if (literals is List && !literals.contains(value)) {
        throw FormatException('El valor debe ser uno de estos: ${literals.join(', ')}.');
      }
    } else if (type == 'integer' && value is! int) {
      throw const FormatException('El valor debe ser un número entero.');
    } else if (type == 'number' && value is! num) {
      throw const FormatException('El valor debe ser un número.');
    } else if (type == 'boolean' && value is! bool) {
      throw const FormatException('El valor debe ser verdadero o falso.');
    }
  }
}
