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

  OperationProposal operation(AgentAction action, {Map<String, RecordRef> refs = const {}}) {
    final entity = action.payload['entity'];
    final base = entities[entity];
    if (base == null) throw const FormatException('Unknown entity.');
    var id = action.payload['id'];
    // Record refs ("#1") resolve against this run's read observations; an
    // unknown or foreign-entity ref never falls back to a guess.
    if (id is String && id.startsWith('#')) {
      final ref = refs[id];
      if (ref == null) {
        throw const FormatException('Unknown record ref.');
      }
      if (entity is! String ||
          ref.entity.toLowerCase() != entity.toLowerCase()) {
        throw const FormatException('Record ref does not match the entity.');
      }
      id = ref.id;
    }
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

  /// Natural-language write authorization (S4/T3). Writes stay authorized
  /// only by deterministic parsing of the user's own request text — never
  /// from model output. Accents/case are ignored; the entity mention must
  /// match a contract entity (plural -s/-es allowed); values bind only to
  /// declared fields, and free text binds only to the entity's single
  /// required string field. Anything ambiguous fails closed (null).
  AgentWriteAuthorization? authorize(String request) {
    final normalized = _plain(request.trim());
    final verb = RegExp(
      r'^(crea|cree|crear|anade|agrega|create|add|edita|editar|actualiza|actualizar|edit|update)\b(.*)$',
      dotAll: true,
    ).firstMatch(normalized);
    if (verb == null) return null;
    final update = {
      'edita',
      'editar',
      'actualiza',
      'actualizar',
      'edit',
      'update',
    }.contains(verb[1]!);
    final rest = verb[2]!.trim();

    // Update verbs require an exact record identifier; create verbs forbid one.
    String? id;
    var working = rest;
    final idMatch = RegExp(r'\b([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\b')
        .firstMatch(working);
    if (idMatch != null) {
      id = idMatch.group(1);
      working = working.replaceRange(idMatch.start, idMatch.end, ' ');
    }
    if (update != (id != null)) return null;

    // Entity mention: first non-article token naming exactly one contract
    // entity, singular/plural and accent/case insensitive.
    String? entityName;
    var tailStart = -1;
    final words = RegExp(r'\S+').allMatches(working).toList();
    for (final word in words) {
      final token = word.group(0)!;
      final singulars = _singulars(token);
      final names = entities.keys
          .where((name) => singulars.contains(_plain(name)))
          .toList();
      if (names.length == 1) {
        entityName = names.single;
        tailStart = word.end;
        break;
      }
      if (names.length > 1) return null;
      if (!_articles.contains(token)) return null;
    }
    if (entityName == null || tailStart < 0) return null;
    final tail = working.substring(tailStart).trim();

    final values = _valuesFromTail(tail, entityName);
    if (values == null) return null;
    try {
      final proposal = operation(
        AgentAction.parse(
          jsonEncode({
            'tag': update ? 'update' : 'create',
            'entity': entityName,
            if (update) 'id': id,
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

  static const _articles = {
    'un', 'una', 'unos', 'unas', 'el', 'la', 'los', 'las',
    'the', 'a', 'an', 'mi', 'mis', 'este', 'esta', 'estos', 'estas',
  };

  /// Tail -> values. Accepted shapes, all deterministic:
  /// 1. JSON object: {"text":"x"}
  /// 2. field = value pairs (JSON literal or bare single token per value)
  /// 3. free text after que (diga|digan|tenga|tengan|contenga|contengan|sea|sean)
  ///    or a quoted/single-token tail, bound to the entity's single required
  ///    string field; anything else fails closed.
  Map<String, dynamic>? _valuesFromTail(String tail, String entityName) {
    if (tail.isEmpty) return null;
    if (tail.startsWith('{')) {
      try {
        final decoded = jsonDecode(tail);
        return decoded is Map<String, dynamic> ? decoded : null;
      } on FormatException {
        return null;
      }
    }
    final body = tail.replaceFirst(RegExp(r'^(?:con|with)\s+'), '');
    final pairShape = RegExp(r'^([A-Za-z_][A-Za-z0-9_]*)\s*=');
    if (pairShape.firstMatch(body) != null) {
      final values = <String, dynamic>{};
      for (final part in body.split(',')) {
        final pair = RegExp(r'^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+)$', dotAll: true)
            .firstMatch(part.trim());
        if (pair == null) return null;
        final value = _literal(pair.group(2)!.trim());
        if (value == null) return null;
        values[pair.group(1)!] = value;
      }
      return values;
    }
    final free = RegExp(
      r'^(?:que\s+)?(?:digan|diga|contengan|contenga|tengan|tenga|sean|sea)\s+(.+)$',
      dotAll: true,
    ).firstMatch(tail);
    final text = free == null ? tail : free.group(1)!.trim();
    if (text.contains(' ') && !(text.startsWith('"') && text.endsWith('"'))) {
      return null;
    }
    final value = _literal(text);
    if (value is! String) return null;
    final target = _singleRequiredStringField(entityName);
    return target == null ? null : {target: value};
  }

  /// JSON literal when it parses; quoted text stripped of quotes; otherwise a
  /// bare single-token string. Multiword unquoted text never reaches here.
  dynamic _literal(String raw) {
    if (raw.length >= 2 &&
        raw.startsWith('"') &&
        raw.endsWith('"') &&
        !raw.substring(1, raw.length - 1).contains('"')) {
      return raw.substring(1, raw.length - 1);
    }
    try {
      return jsonDecode(raw);
    } on FormatException {
      if (raw.contains(' ') || raw.contains('"')) return null;
      return raw;
    }
  }

  /// The entity's exactly-one required string field, from the compact
  /// contract view; zero or multiple candidates fail closed.
  String? _singleRequiredStringField(String entityName) {
    final entry = compactDescriptor
        .where((e) => e['entity'] == entityName)
        .firstOrNull;
    if (entry == null) return null;
    final fields = entry['fields'];
    final required = entry['required'];
    if (fields is! Map || required is! List) return null;
    final candidates = required
        .whereType<String>()
        .where((name) => fields[name] == 'string')
        .toList();
    return candidates.length == 1 ? candidates.single : null;
  }

  /// Both plural unfoldings: Spanish/English '-s' and '-es'. 'notes' ->
  /// {'note', 'not'}; only an exact entity match in either form is accepted.
  static Set<String> _singulars(String token) {
    final plain = _plain(token);
    return {
      plain,
      if (plain.length > 4 && plain.endsWith('es'))
        plain.substring(0, plain.length - 2),
      if (plain.length > 3 && plain.endsWith('s'))
        plain.substring(0, plain.length - 1),
    };
  }

  static String _plain(String value) {
    const accents = {
      'á': 'a', 'é': 'e', 'í': 'i', 'ó': 'o', 'ú': 'u', 'ü': 'u', 'ñ': 'n',
    };
    final buffer = StringBuffer();
    for (final rune in value.toLowerCase().runes) {
      final character = String.fromCharCode(rune);
      buffer.write(accents[character] ?? character);
    }
    return buffer.toString();
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
