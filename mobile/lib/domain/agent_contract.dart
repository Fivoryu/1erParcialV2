import 'dart:convert';

import 'models.dart';
import 'agent_models.dart';
import 'operation_proposal.dart';
import 'openapi_validator.dart';

class AgentContract {
  AgentContract(BackendContract contract)
    : scope = '${contract.deploymentId}:${contract.contractHash}',
      openApi = _freezeJson(
        jsonDecode(jsonEncode(contract.openApi)),
      ) as Map<String, dynamic>;
  final String scope;
  final Map<String, dynamic> openApi;
  static final uuid = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
  );
  Map<String, String> get entities {
    final result = <String, String>{};
    final paths = openApi['paths'];
    if (paths is! Map) throw const FormatException('Contract has no paths.');
    for (final key in paths.keys) {
      if (key is String &&
          RegExp(r'^/[A-Za-z0-9_/-]+$').hasMatch(key) &&
          !key.endsWith('/')) {
        final name = key.split('/').last;
        if (result.containsKey(name)) {
          throw const FormatException('Ambiguous entity name.');
        }
        result[name] = key;
      }
    }
    return result;
  }

  OperationProposal operation(AgentAction action) {
    final entity = action.payload['entity'];
    final base = entities[entity];
    if (base == null) throw const FormatException('Unknown entity.');
    final id = action.payload['id'];
    if (id != null && (id is! String || !uuid.hasMatch(id))) {
      throw const FormatException('Exact UUID required.');
    }
    final method = switch (action.tag) {
      'read' => 'GET',
      'create' => 'POST',
      'update' => 'PUT',
      'delete_preview' => 'DELETE',
      _ => throw const FormatException('Not a tool.'),
    };
    final proposal = OperationProposal(
      method: method,
      path: id == null ? base : '$base/$id',
      body: action.payload['values'],
    );
    if (proposal.path.contains('?') ||
        proposal.path.contains('#') ||
        proposal.path.contains('%') ||
        proposal.path.contains('//')) {
      throw const FormatException('Unsafe route.');
    }
    const OpenApiValidator().validate(proposal, openApi);
    return proposal;
  }

  /// Narrow natural-language grammar: verb + exact entity [+ UUID] + con/with
  /// + JSON object, or field = JSON literal. Nothing is inferred from the model.
  AgentWriteAuthorization? authorize(String request) {
    final match = RegExp(
      r'^(crea|crear|añade|agrega|create|add|edita|editar|actualiza|edit|update)\s+([A-Za-z0-9_-]+)(?:\s+([0-9a-fA-F-]{36}))?\s+(?:con|with)\s+(.+)$',
      caseSensitive: false,
    ).firstMatch(request.trim());
    if (match == null) return null;
    final names = entities.keys
        .where((name) => name.toLowerCase() == match[2]!.toLowerCase())
        .toList();
    if (names.length != 1) return null;
    final update = {
      'edita',
      'editar',
      'actualiza',
      'edit',
      'update',
    }.contains(match[1]!.toLowerCase());
    if (update != (match[3] != null)) return null;
    try {
      final raw = match[4]!;
      dynamic values;
      if (raw.startsWith('{')) {
        values = jsonDecode(raw);
      } else {
        final field = RegExp(r'^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+)$')
            .firstMatch(raw);
        if (field == null) return null;
        values = {field[1]!: jsonDecode(field[2]!)};
      }
      if (values is! Map<String, dynamic>) return null;
      final proposal = operation(
        AgentAction.parse(
          jsonEncode({
            'tag': update ? 'update' : 'create',
            'entity': names.single,
            if (update) 'id': match[3],
            'values': values,
          }),
        ),
      );
      return AgentWriteAuthorization(
        request: request,
        scope: scope,
        proposal: proposal,
      );
    } on FormatException {
      return null;
    }
  }

  Map<String, dynamic> get descriptor => {
    'entities': entities,
    'openApi': openApi,
  };

  /// Compact per-entity view for the on-device model prompt (T1): methods,
  /// writable fields with types, and required fields. The full frozen OpenAPI
  /// document stays out of the prompt; validation keeps using [openApi].
  List<Map<String, dynamic>> get compactDescriptor {
    final result = <Map<String, dynamic>>[];
    final paths = openApi['paths'];
    if (paths is! Map) throw const FormatException('Contract has no paths.');
    for (final entry in entities.entries) {
      final base = entry.value;
      final baseItem = paths[base];
      final idItem = paths['$base/{id}'];
      final methods = <String>{};
      final httpMethods = {'get', 'post', 'put', 'delete'};
      for (final item in [if (baseItem is Map) baseItem, if (idItem is Map) idItem]) {
        for (final method in item.keys) {
          if (method is String && httpMethods.contains(method)) methods.add(method.toUpperCase());
        }
      }
      final schema = _bodySchema(baseItem) ?? _bodySchema(idItem);
      result.add({
        'entity': entry.key,
        'methods': methods.toList()..sort(),
        'fields': schema.fields,
        'required': schema.required,
      });
    }
    return result;
  }

  ({Map<String, String> fields, List<String> required}) _bodySchema(Map? pathItem) {
    final fields = <String, String>{};
    final required = <String>[];
    if (pathItem == null) return (fields: fields, required: required);
    for (final op in pathItem.values) {
      if (op is! Map || op['requestBody'] is! Map) continue;
      final content = ((op['requestBody'] as Map)['content'] as Map?)?['application/json'];
      final schema = content is Map ? content['schema'] : null;
      final resolved = schema is Map ? _resolveSchema(schema) : null;
      if (resolved == null) continue;
      resolved.properties.forEach((name, type) => fields.putIfAbsent(name, () => type));
      for (final name in resolved.required) {
        if (!required.contains(name)) required.add(name);
      }
    }
    return (fields: fields, required: required);
  }

  ({Map<String, String> properties, List<String> required})? _resolveSchema(Map schema) {
    var target = schema;
    final reference = schema['\$ref'];
    if (reference is String && reference.startsWith('#/components/schemas/')) {
      final components = openApi['components'];
      final schemas = components is Map ? components['schemas'] : null;
      final resolved = schemas is Map ? schemas[reference.substring('#/components/schemas/'.length)] : null;
      if (resolved is! Map) return null;
      target = resolved;
    }
    // Generated backends are arbitrary: accept object schemas that declare
    // properties even without an explicit type, and fail soft otherwise.
    final rawProperties = target['properties'];
    if (rawProperties is! Map) return null;
    final properties = <String, String>{};
    rawProperties.forEach((name, spec) {
      if (name is String && spec is Map) {
        final type = spec['type'];
        if (type is String) {
          properties[name] = type;
        } else if (spec['\$ref'] is String) {
          properties[name] = 'object';
        } else if (spec['properties'] is Map || spec['items'] is Map) {
          properties[name] = spec['items'] is Map ? 'array' : 'object';
        }
      }
    });
    final required = <String>[
      if (target['required'] is List)
        for (final name in target['required'] as List)
          if (name is String) name,
    ];
    return (properties: properties, required: required);
  }
}

dynamic _freezeJson(dynamic value) {
  if (value is Map) {
    return Map<String, dynamic>.unmodifiable(
      value.map((key, item) => MapEntry(key as String, _freezeJson(item))),
    );
  }
  if (value is List) return List<dynamic>.unmodifiable(value.map(_freezeJson));
  return value;
}
