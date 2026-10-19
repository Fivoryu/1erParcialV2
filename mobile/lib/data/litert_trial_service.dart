import 'dart:convert';

import 'package:flutter/services.dart';

class LiteRtTrialStatus {
  const LiteRtTrialStatus({
    required this.available,
    required this.modelPresent,
    this.reason,
  });
  final bool available, modelPresent;
  final String? reason;
  String get runtime => 'LiteRT-LM';
  String get version => '0.18.0';
}

class LiteRtTrialCall {
  const LiteRtTrialCall(this.name, this.arguments);
  final String name;
  final Map<String, dynamic> arguments;
  Map<String, dynamic> toJson() => {'name': name, 'arguments': arguments};
}

class LiteRtTrialResult {
  const LiteRtTrialResult(this.text, this.toolCalls, this.metrics);

  /// Literal final model text for display only, never executable authorization.
  final String text;
  final List<LiteRtTrialCall> toolCalls;
  final Map<String, num> metrics;
}

class LiteRtDiagnosticStart {
  const LiteRtDiagnosticStart(this.runId, this.metrics);
  final String runId;
  final Map<String, num> metrics;
}

class LiteRtPreviousExit {
  const LiteRtPreviousExit(this.availability, this.fields);
  final String availability;
  final Map<String, int> fields;
  Map<String, dynamic> toJson() => {'availability': availability, ...fields};
}

/// Diagnostic transport only. It has no tool executor, network or data store.
class LiteRtTrialService {
  LiteRtTrialService({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel('diagrammer/litert_trial');
  final MethodChannel _channel;
  bool _inFlight = false;
  bool get inFlight => _inFlight;
  Future<LiteRtTrialStatus> status() async {
    try {
      final raw = await _channel.invokeMethod<Object?>('status');
      final map = _map(raw, {
        'available',
        'runtime',
        'version',
        'modelPresent',
        'reason',
      });
      if (map['available'] is! bool ||
          map['modelPresent'] is! bool ||
          map['runtime'] != 'LiteRT-LM' ||
          map['version'] != '0.18.0' ||
          (map.containsKey('reason') && map['reason'] is! String)) {
        throw const FormatException('Invalid trial status.');
      }
      return LiteRtTrialStatus(
        available: map['available'] as bool,
        modelPresent: map['modelPresent'] as bool,
        reason: map['reason'] as String?,
      );
    } on MissingPluginException {
      return const LiteRtTrialStatus(
        available: false,
        modelPresent: false,
        reason: 'The optional native trial is not installed.',
      );
    }
  }

  Future<LiteRtTrialResult> runCase({
    required String request,
    required List<String> entities,
    required Map<String, Map<String, String>> schemas,
  }) async {
    if (_inFlight) {
      throw StateError('A native operation is still in flight.');
    }
    if (request.trim().isEmpty ||
        request.length > 4096 ||
        entities.isEmpty ||
        entities.length > 8 ||
        entities.toSet().length != entities.length ||
        entities.any(
          (e) => !RegExp(r'^[A-Za-z][A-Za-z0-9_]{0,31}$').hasMatch(e),
        )) {
      throw ArgumentError('Invalid bounded synthetic case.');
    }
    final fieldPattern = RegExp(r'^[A-Za-z][A-Za-z0-9_]{0,31}$');
    if (schemas.length != entities.length ||
        entities.any((e) => !schemas.containsKey(e)) ||
        schemas.entries.any(
          (entry) =>
              !entities.contains(entry.key) ||
              entry.value.length > 32 ||
              entry.value.entries.any(
                (field) =>
                    !fieldPattern.hasMatch(field.key) ||
                    !{
                      'string',
                      'number',
                      'integer',
                      'boolean',
                    }.contains(field.value),
              ),
        )) {
      throw ArgumentError('Invalid synthetic schemas.');
    }
    final safeSchemas = {
      for (final entry in schemas.entries)
        entry.key: Map<String, String>.of(entry.value),
    };
    final allowed = Set<String>.of(entities);
    _inFlight = true;
    try {
      final raw = await _channel.invokeMethod<Object?>('runCase', {
        'request': request,
        'entities': List<String>.of(entities),
        'schemas': safeSchemas,
      });
      final result = _map(raw, {'text', 'toolCalls', 'metrics'});
      if (result['text'] is! String ||
          (result['text'] as String).length > 32768 ||
          result['toolCalls'] is! List ||
          (result['toolCalls'] as List).length > 16) {
        throw const FormatException('Invalid bounded trial result.');
      }
      final validatedMetrics = _metrics(result['metrics']);
      final calls = (result['toolCalls'] as List)
          .map((v) => _call(v, allowed))
          .toList(growable: false);
      return LiteRtTrialResult(
        result['text'] as String,
        List.unmodifiable(calls),
        Map.unmodifiable(validatedMetrics),
      );
    } finally {
      _inFlight = false;
    }
  }

  Future<T> _diagnostic<T>(Future<T> Function() action) async {
    if (_inFlight) {
      throw StateError('A native operation is still in flight.');
    }
    _inFlight = true;
    try {
      return await action();
    } finally {
      _inFlight = false;
    }
  }

  Future<LiteRtDiagnosticStart> beginDiagnostic() => _diagnostic(() async {
    final value = _map(
      await _channel.invokeMethod<Object?>('beginDiagnostic'),
      {'runId', 'metrics'},
    );
    final id = value['runId'];
    if (id is! String ||
        !RegExp(
          r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
        ).hasMatch(id)) {
      throw const FormatException('Invalid diagnostic run identity.');
    }
    return LiteRtDiagnosticStart(id, _metrics(value['metrics']));
  });
  Future<LiteRtPreviousExit> inspectPreviousExit() => _diagnostic(() async {
    final value = _map(
      await _channel.invokeMethod<Object?>('inspectPreviousExit'),
      {'availability', 'reason', 'status', 'timestampMs', 'pssKb', 'rssKb'},
    );
    final availability = value['availability'];
    if (!{'none', 'available', 'unavailable'}.contains(availability)) {
      throw const FormatException('Invalid exit availability.');
    }
    final fields = <String, int>{};
    if (availability == 'available') {
      for (final key in ['reason', 'status', 'timestampMs', 'pssKb', 'rssKb']) {
        final n = value[key];
        if (n is! int || n < 0) {
          throw const FormatException('Invalid exit scalar.');
        }
        fields[key] = n;
      }
    } else if (value.length != 1) {
      throw const FormatException('Unavailable exit must not contain fields.');
    }
    return LiteRtPreviousExit(availability as String, Map.unmodifiable(fields));
  });
  Future<void> finishDiagnostic() => _diagnostic(() async {
    await _channel.invokeMethod<void>('finishDiagnostic');
  });
  static Map<String, num> _metrics(Object? raw) {
    final value = _map(raw, {
      'pssKb',
      'rssKb',
      'loadMs',
      'inferenceMs',
      'ttftMs',
      'peakPssKb',
      'peakRssKb',
      'wallClockMs',
      'elapsedRealtimeMs',
      'availableRamKb',
      'totalRamKb',
    });
    final result = <String, num>{};
    for (final entry in value.entries) {
      if (entry.value is! num ||
          !(entry.value as num).isFinite ||
          (entry.value as num) < 0) {
        throw const FormatException('Invalid diagnostic metric.');
      }
      result[entry.key] = entry.value as num;
    }
    return Map.unmodifiable(result);
  }

  Future<void> release() async {
    if (_inFlight) {
      throw StateError('Wait for actual native settlement before release.');
    }
    _inFlight = true;
    try {
      await _channel.invokeMethod<void>('release');
    } on MissingPluginException {
      /* Optional runtime has nothing to release. */
    } finally {
      _inFlight = false;
    }
  }

  static Map<String, dynamic> _map(Object? raw, Set<String> allowed) {
    if (raw is! Map ||
        raw.keys.any((k) => k is! String || !allowed.contains(k))) {
      throw const FormatException('Unexpected trial fields.');
    }
    return Map<String, dynamic>.from(raw);
  }

  static LiteRtTrialCall _call(Object? raw, Set<String> entities) {
    final call = _map(raw, {'name', 'arguments'});
    const keys = {
      'read': {'entity', 'id'},
      'create': {'entity', 'values'},
      'update': {'entity', 'id', 'values'},
      'delete_preview': {'entity', 'id'},
      'calculate': {'op', 'values'},
      'clarify': {'text'},
      'answer': {'text'},
    };
    final name = call['name'];
    if (name is! String || !keys.containsKey(name)) {
      throw const FormatException('Unknown tool name.');
    }
    final args = _map(call['arguments'], keys[name]!);
    if ({'read', 'create', 'update', 'delete_preview'}.contains(name)) {
      if (args['entity'] is! String || !entities.contains(args['entity'])) {
        throw const FormatException('Out-of-scope entity.');
      }
      if (args.containsKey('id') ||
          name == 'update' ||
          name == 'delete_preview') {
        if (args['id'] is! String ||
            !RegExp(
              r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
            ).hasMatch(args['id'] as String)) {
          throw const FormatException('Exact UUID required.');
        }
      }
      if (name == 'create' || name == 'update') {
        if (args['values'] is! String ||
            (args['values'] as String).length > 8192 ||
            jsonDecode(args['values'] as String) is! Map<String, dynamic>) {
          throw const FormatException('Values must encode a JSON object.');
        }
      }
    } else if (name == 'calculate') {
      final values = args['values'];
      if (!{
            'add',
            'subtract',
            'multiply',
            'divide',
            'sum',
            'mean',
            'min',
            'max',
          }.contains(args['op']) ||
          values is! List ||
          values.isEmpty ||
          values.length > 1000 ||
          values.any((v) => v is! num || !v.isFinite)) {
        throw const FormatException('Invalid numeric calculation.');
      }
    } else if (args['text'] is! String ||
        (args['text'] as String).trim().isEmpty ||
        (args['text'] as String).length > 8192) {
      throw const FormatException('Missing bounded text.');
    }
    return LiteRtTrialCall(name, Map.unmodifiable(args));
  }
}

/// Fixed public synthetic requests; expectations are evaluator-only data.
class LiteRtTrialCase {
  const LiteRtTrialCase(this.id, this.request, this.name, this.arguments);
  final String id, request, name;
  final Map<String, dynamic> arguments;
  bool matches(LiteRtTrialResult result) {
    if (name == 'answer' && result.toolCalls.isEmpty) {
      return result.text.trim().isNotEmpty;
    }
    if (result.toolCalls.length != 1 || result.toolCalls.single.name != name) {
      return false;
    }
    final actual = result.toolCalls.single.arguments;
    if (name == 'answer' || name == 'clarify') {
      return actual.length == 1 &&
          actual['text'] is String &&
          (actual['text'] as String).trim().isNotEmpty;
    }
    if (actual.length != arguments.length) {
      return false;
    }
    for (final key in arguments.keys) {
      if (!actual.containsKey(key)) {
        return false;
      }
      dynamic a = actual[key], b = arguments[key];
      if (key == 'values' && name != 'calculate') {
        if (a is! String || b is! String) {
          return false;
        }
        try {
          a = jsonDecode(a);
          b = jsonDecode(b);
        } on FormatException {
          return false;
        }
        if (a is! Map || b is! Map) {
          return false;
        }
      }
      if (!_trialEqual(a, b)) {
        return false;
      }
    }
    return true;
  }
}

const _trialId = '11111111-1111-4111-8111-111111111111';
const liteRtTrialCases = [
  LiteRtTrialCase('greeting', 'Hola, ¿cómo estás?', 'answer', {}),
  LiteRtTrialCase('calculation', '¿Cuánto es 12 más 7?', 'calculate', {
    'op': 'add',
    'values': [12, 7],
  }),
  LiteRtTrialCase('read-note', 'Mostrame los registros de Note.', 'read', {
    'entity': 'Note',
  }),
  LiteRtTrialCase(
    'read-satellite',
    'Mostrame el Satellite identificado por $_trialId.',
    'read',
    {'entity': 'Satellite', 'id': _trialId},
  ),
  LiteRtTrialCase(
    'create-satellite',
    'Agregá un Satellite llamado Orion a 500 kilómetros de altitud.',
    'create',
    {'entity': 'Satellite', 'values': '{"name":"Orion","altitudeKm":500}'},
  ),
  LiteRtTrialCase(
    'update-note',
    'Cambiá el texto de Note $_trialId a hello.',
    'update',
    {'entity': 'Note', 'id': _trialId, 'values': '{"text":"hello"}'},
  ),
  LiteRtTrialCase(
    'delete-preview',
    'Quiero borrar el Satellite $_trialId.',
    'delete_preview',
    {'entity': 'Satellite', 'id': _trialId},
  ),
  LiteRtTrialCase('ambiguity', 'Cambiá eso.', 'clarify', {}),
];
bool _trialEqual(dynamic a, dynamic b) {
  if (a is Map && b is Map) {
    return a.length == b.length &&
        a.keys.every((k) => b.containsKey(k) && _trialEqual(a[k], b[k]));
  }
  if (a is List && b is List) {
    return a.length == b.length &&
        List.generate(a.length, (i) => i).every((i) => _trialEqual(a[i], b[i]));
  }
  return a == b;
}

const liteRtTrialSchemas = <String, Map<String, String>>{
  'Note': {'text': 'string'},
  'Satellite': {'name': 'string', 'altitudeKm': 'number'},
};
