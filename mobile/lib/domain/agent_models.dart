import 'dart:convert';

import 'operation_proposal.dart';

enum AgentRunStatus { answered, clarification, deletePending, stopped, failed }

/// Raised by a model adapter when generation ran past its budget and was
/// cancelled; the runner reports it instead of waiting forever.
abstract interface class AgentModelTimeout implements Exception {}

class AgentAction {
  AgentAction._(this.tag, this.payload);
  final String tag;
  final Map<String, dynamic> payload;  static AgentAction parse(String raw) {
    if (raw.length > 32768) {
      throw const FormatException('Model response exceeds limit.');
    }
    final value = jsonDecode(raw);
    if (value is! Map<String, dynamic>) {
      throw const FormatException('Expected a JSON object.');
    }
    const keys = {
      'answer': {'tag', 'text'},
      'clarify': {'tag', 'text'},
      'read': {'tag', 'entity', 'id'},
      'create': {'tag', 'entity', 'values'},
      'update': {'tag', 'entity', 'id', 'values'},
      'delete_preview': {'tag', 'entity', 'id'},
      'calculate': {'tag', 'calculation'},
      // Reads and reports are validated against the contract by QuerySpec.
      'query': {'tag', 'entity', 'filter', 'sort', 'desc', 'limit', 'respond'},
      'report': {'tag', 'entity', 'metric', 'field', 'groupBy', 'filter', 'respond'},
    };
    final tag = value['tag'];
    if (tag is! String ||
        !keys.containsKey(tag) ||
        value.keys.any((k) => !keys[tag]!.contains(k))) {
      throw const FormatException('Unsupported action or fields.');
    }
    if (tag == 'answer' || tag == 'clarify') {
      if (value['text'] is! String || (value['text'] as String).isEmpty) {
        throw const FormatException('Missing text.');
      }
    } else if (tag == 'calculate') {
      if (value['calculation'] is! Map<String, dynamic>) {
        throw const FormatException('Missing calculation.');
      }
    } else if (tag == 'query' || tag == 'report') {
      if (value['entity'] is! String) {
        throw const FormatException('Missing entity.');
      }
      if (value.containsKey('filter') && value['filter'] is! List) {
        throw const FormatException('filter must be a list.');
      }
    } else {
      if (value['entity'] is! String) {
        throw const FormatException('Missing entity.');
      }
      if (value.containsKey('id') && value['id'] is! String) {
        throw const FormatException('Invalid identifier.');
      }
      if ((tag == 'update' || tag == 'delete_preview') &&
          !value.containsKey('id')) {
        throw const FormatException('Missing identifier.');
      }
      if ((tag == 'create' || tag == 'update') &&
          value['values'] is! Map<String, dynamic>) {
        throw const FormatException('Missing values.');
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
  const AgentRunResult(this.status, this.text, {this.deletePreview, this.data});
  final AgentRunStatus status;
  final String text;
  final OperationProposal? deletePreview;
  final dynamic data;
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
