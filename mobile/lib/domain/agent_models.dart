import 'dart:convert';

import 'operation_proposal.dart';

enum AgentRunStatus { answered, clarification, deletePending, writePending, stopped, failed }

/// Raised by a model adapter when generation ran past its budget and was
/// cancelled; the runner reports it instead of waiting forever.
abstract interface class AgentModelTimeout implements Exception {}

class AgentAction {
  AgentAction._(this.tag, this.payload);
  final String tag;
  final Map<String, dynamic> payload;  static AgentAction parse(String raw) {
    if (raw.length > 32768) {
      throw const FormatException('La respuesta del modelo supera el límite permitido.');
    }
    final value = jsonDecode(raw);
    if (value is! Map<String, dynamic>) {
      throw const FormatException('Se esperaba un objeto JSON.');
    }
    const keys = {
      'answer': {'tag', 'text'},
      'clarify': {'tag', 'text'},
      'read': {'tag', 'entity', 'id'},
      'create': {'tag', 'entity', 'values'},
      'update': {'tag', 'entity', 'id', 'match', 'values'},
      'delete_preview': {'tag', 'entity', 'id', 'match', 'all'},
      'calculate': {'tag', 'calculation'},
      // Reads and reports are validated against the contract by QuerySpec.
      'query': {'tag', 'entity', 'filter', 'sort', 'desc', 'limit', 'respond'},
      'report': {'tag', 'entity', 'metric', 'field', 'groupBy', 'filter', 'respond'},
    };
    final tag = value['tag'];
    if (tag is! String ||
        !keys.containsKey(tag) ||
        value.keys.any((k) => !keys[tag]!.contains(k))) {
      throw const FormatException('La acción o sus campos no son compatibles.');
    }
    if (tag == 'answer' || tag == 'clarify') {
      if (value['text'] is! String || (value['text'] as String).isEmpty) {
        throw const FormatException('Falta el texto.');
      }
    } else if (tag == 'calculate') {
      if (value['calculation'] is! Map<String, dynamic>) {
        throw const FormatException('Falta el cálculo.');
      }
    } else if (tag == 'query' || tag == 'report') {
      if (value['entity'] is! String) {
        throw const FormatException('Falta la entidad.');
      }
      if (value.containsKey('filter') && value['filter'] is! List) {
        throw const FormatException('El campo filter debe ser una lista.');
      }
    } else {
      if (value['entity'] is! String) {
        throw const FormatException('Falta la entidad.');
      }
      if (value.containsKey('id') && value['id'] is! String) {
        throw const FormatException('El identificador no es válido.');
      }
      if (tag == 'delete_preview' && value.containsKey('match') && value['match'] is! List) {
        throw const FormatException('El campo match debe ser una lista de filtros.');
      }
      if ((tag == 'update' && !value.containsKey('id') && !value.containsKey('match')) ||
          (tag == 'delete_preview' && !value.containsKey('id') && !value.containsKey('match'))) {
        throw const FormatException('Falta el identificador.');
      }
      if ((tag == 'create' || tag == 'update') &&
          value['values'] is! Map<String, dynamic>) {
        throw const FormatException('Faltan los valores.');
      }
    }
    return AgentAction._(tag, Map.unmodifiable(value));
  }
}

class AgentToolResult {
  const AgentToolResult({
    required this.data,
    required this.complete,
    required this.fetchedAt,
    this.source = 'gateway',
    this.committed = true,
  });
  final dynamic data;
  final bool complete, committed;
  final DateTime fetchedAt;
  final String source;
  Map<String, dynamic> toJson() => {
    'data': data,
    'complete': complete,
    'fetchedAt': fetchedAt.toUtc().toIso8601String(),
    'source': source,
    'committed': committed,
  };
}

class AgentProgress {
  const AgentProgress(this.phase, this.elapsed, {this.tool, this.duration});
  final String phase;
  final Duration elapsed;
  final String? tool;
  final Duration? duration;
}

class AgentRunResult {
  const AgentRunResult(
    this.status,
    this.text, {
    this.deletePreview,
    this.deletePreviews = const [],
    this.deleteLabels = const [],
    this.writePreview,
    this.data,
    this.failure,
    this.failureStackTrace,
  });
  final AgentRunStatus status;
  final String text;
  final Object? failure;
  final StackTrace? failureStackTrace;
  final OperationProposal? deletePreview;

  /// Several exact targets for one confirmed bulk deletion (bounded).
  final List<OperationProposal> deletePreviews;

  /// Human labels of the targets, in the same order; never identifiers.
  final List<String> deleteLabels;

  /// A create or update the model proposed, already grounded and validated;
  /// it is only sent after the user confirms the shown summary.
  final OperationProposal? writePreview;
  final dynamic data;
}

/// The backend rejected a write; [message] is already human readable.
class BackendRejection implements Exception {
  const BackendRejection(this.status, this.message);
  final int? status;
  final String message;

  /// Translates a generated backend error (`ApiErrorResponse`) for the chat.
  factory BackendRejection.from(int? status, dynamic body) {
    final code = body is Map ? body['code']?.toString() : null;
    final detail = body is Map && body['detail'] is String ? (body['detail'] as String).trim() : '';
    final message = switch ((status, code)) {
      (_, 'VALIDATION_FAILED') => 'Faltan datos o hay valores inválidos${detail.isEmpty ? '' : ': $detail'}.',
      (_, 'INVALID_REFERENCE') => 'El registro relacionado no existe.',
      (_, 'INVALID_REQUEST') => 'Algún valor no tiene el formato esperado${detail.isEmpty ? '' : ': $detail'}.',
      (404, _) || (_, 'NOT_FOUND') => 'Ese registro ya no existe.',
      (409, _) || (_, 'DATA_CONSTRAINT_VIOLATION') => 'No se pudo: el registro está duplicado o lo usan otros registros.',
      _ => 'El backend rechazó la operación${status == null ? '' : ' ($status)'}.',
    };
    return BackendRejection(status, message);
  }

  @override
  String toString() => message;
}

/// A record reference handed to the on-device model: stable short alias
/// instead of the raw record UUID, bound to the entity it was read from.
class RecordRef {
  const RecordRef(this.id, this.entity);
  final String id;
  final String entity;
}

/// Issued by trusted UI/application code, never deserialized from model output.
class AgentWriteAuthorization {
  AgentWriteAuthorization({
    required this.request,
    required this.scope,
    required OperationProposal proposal,
  }) : encodedProposal = proposal.encode();
  final String request, scope, encodedProposal;
  bool permits(String raw, String currentScope, OperationProposal proposal) =>
      request == raw &&
      scope == currentScope &&
      encodedProposal == proposal.encode();
}
