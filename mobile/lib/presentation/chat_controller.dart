import 'dart:convert';

import '../domain/agent_contract.dart';
import '../domain/agent_models.dart';
import '../domain/agent_query.dart';
import '../domain/agent_runner.dart';
import '../domain/models.dart';
import '../domain/operation_proposal.dart';

import 'dart:math';

import 'package:flutter/foundation.dart';

import '../data/chat_repository.dart';
import '../data/chat_store.dart';
import '../data/platform_service.dart';
import '../domain/chat_models.dart';

typedef StreamingAgentModel = Future<String> Function(
  String prompt, {
  void Function(String partial)? onPartial,
});

class ChatController extends ChangeNotifier {
  ChatController({
    required this.store,
    required this.service,
    this.model,
    this.cancelModel,
  });
  final ChatStore store;
  final PlatformService service;
  final AgentModel? model;
  final Future<void> Function()? cancelModel;
  void invalidateModel() {
    _requestCancelled = true;
    _clearStreamingMessage();
    _runner?.cancel();
    ++_epoch;
    busy = false;
    deletePreview = null;
    deletePreviews = const [];
    deleteLabels = const [];
    progress = const [];
    notifyListeners();
  }

  bool get agentConnected => model != null && _contract != null;
  BackendContract? _contract;
  AgentRunner? _runner;

  /// Record refs per backend scope, so "borrá el 2" resolves against rows
  /// shown in an earlier message of the same backend.
  final _refsByScope = <String, Map<String, RecordRef>>{};
  bool _requestCancelled = false;
  List<AgentProgress> progress = const [];
  Map<String, dynamic>? deletePreview;

  /// Receipts awaiting one confirmation for a bounded bulk deletion.
  List<Map<String, dynamic>> deletePreviews = const [];

  /// Human labels of the pending delete targets (single or bulk).
  List<String> deleteLabels = const [];
  final _writeScopes = <String>{};
  ChatScope? _scope;
  String? _token;
  int _epoch = 0;
  String? _streamingMessageId;
  bool busy = false;
  String? error;
  List<LocalConversation> conversations = const [];
  List<ChatMessage> messages = const [];
  LocalConversation? selected;
  final _drafts = <String, String>{};
  String get draft => _scope == null
      ? ''
      : _drafts['${_scope!.namespace}:${selected?.id}'] ?? '';
  void setDraft(String value) {
    if (_scope != null) _drafts['${_scope!.namespace}:${selected?.id}'] = value;
  }

  Future<void> bind(
    ChatScope? scope,
    String? token, {
    BackendContract? contract,
  }) async {
    if (scope == _scope && token == _token) return;
    cancelModel?.call().catchError((Object _) {});
    _runner?.cancel();
    deletePreview = null;
    deletePreviews = const [];
    deleteLabels = const [];
    progress = const [];
    _contract = contract;
    ++_epoch;
    _scope = scope;
    _token = token;
    conversations = const [];
    messages = const [];
    _streamingMessageId = null;
    selected = null;
    busy = false;
    error = null;
    notifyListeners();
    if (scope != null && token != null) await refresh();
  }

  Future<void> _run(
    Future<void> Function(ChatRepository, String, int) action,
  ) async {
    final scope = _scope;
    final token = _token;
    if (scope == null || token == null || busy) return;
    final epoch = _epoch;
    busy = true;
    error = null;
    notifyListeners();
    try {
      await action(
        ChatRepository(service: service, store: store, scope: scope),
        token,
        epoch,
      );
    } on BackendRejection catch (rejection) {
      if (epoch == _epoch) {
        _clearStreamingMessage();
        error = rejection.message;
      }
    } catch (e) {
      if (epoch == _epoch) {
        _clearStreamingMessage();
        error = 'History sync is unavailable. Local data is retained. $e';
      }
    } finally {
      if (epoch == _epoch) {
        busy = false;
        notifyListeners();
      }
    }
  }

  Future<void> refresh() => _run((repository, token, epoch) async {
    final local = await store.listConversations(repository.scope);
    if (epoch != _epoch) return;
    conversations = local;
    notifyListeners();
    final remote = await repository.refreshConversations(token);
    if (epoch != _epoch) return;
    conversations = remote;
  });
  Future<void> newChat() => _run((repository, token, epoch) async {
    final now = DateTime.now().toUtc();
    final conversation = LocalConversation(
      id: _uuid(),
      scope: repository.scope,
      title: 'New conversation',
      createdAt: now,
      updatedAt: now,
    );
    await store.saveConversation(conversation);
    if (epoch != _epoch) return;
    selected = conversation;
    messages = const [];
    final savedConversations = await store.listConversations(repository.scope);
    if (epoch != _epoch) return;
    conversations = savedConversations;
    notifyListeners();
    await repository.createConversation(token, conversation);
  });
  Future<void> open(LocalConversation conversation) =>
      _run((repository, token, epoch) async {
        if (conversation.scope != repository.scope) {
          throw StateError('Foreign conversation');
        }
        final local = await store.messages(repository.scope, conversation.id);
        if (epoch != _epoch) return;
        selected = conversation;
        messages = local;
        notifyListeners();
        await repository.createConversation(token, conversation);
        if (epoch != _epoch) return;
        await repository.syncMessages(token, conversation.id);
        final updated = await store.messages(repository.scope, conversation.id);
        if (epoch != _epoch) return;
        messages = updated;
      });
  void showHistory() {
    if (busy) return;
    selected = null;
    _clearStreamingMessage();
    messages = const [];
    notifyListeners();
  }

  void cancelRequest() {
    _requestCancelled = true;
    deletePreview = null;
    deletePreviews = const [];
    deleteLabels = const [];
    _runner?.cancel();
    cancelModel?.call().catchError((Object _) {});
    error = 'Stop requested. No new tool will be sent; an in-flight model or write may still finish.';
    notifyListeners();
  }

  void _clearStreamingMessage() {
    final id = _streamingMessageId;
    if (id != null) {
      messages = messages.where((message) => message.id != id).toList();
    }
    _streamingMessageId = null;
  }

  Future<void> sendMessage(String content) => _run((
    repository,
    token,
    epoch,
  ) async {
    _requestCancelled = false;
    final deadline = DateTime.now().add(const Duration(minutes: 5));
    final conversation = selected;
    bool allowed() =>
        epoch == _epoch &&
        !_requestCancelled &&
        DateTime.now().isBefore(deadline) &&
        identical(selected, conversation);
    final selectedContract = _contract;
    final generate = model;
    if (conversation == null || content.trim().isEmpty) return;
    if (selectedContract == null || generate == null) {
      throw StateError('The local agent is not connected.');
    }
    if (content.length > 16000) {
      throw ArgumentError('Message exceeds 16000 characters');
    }
    await store.appendMessage(
      repository.scope,
      conversation.id,
      ChatMessage(
        id: _uuid(),
        role: 'user',
        content: content,
        createdAt: DateTime.now().toUtc(),
      ),
    );
    final local = await store.messages(repository.scope, conversation.id);
    if (epoch != _epoch) return;
    messages = local;
    _streamingMessageId = null;
    setDraft('');
    progress = const [];
    deletePreview = null;
    deletePreviews = const [];
    deleteLabels = const [];
    notifyListeners();
    final runner = AgentRunner(
      contract: AgentContract(selectedContract),
      refs: _refsByScope.putIfAbsent(
        '${repository.scope.namespace}:${selectedContract.contractHash}',
        () => <String, RecordRef>{},
      ),
      model: (prompt) async {
        final input = jsonDecode(prompt) as Map<String, dynamic>;
        // Few recent turns: the prompt stays small, so prefill stays fast.
        input['conversation'] = local
            .skip(local.length > 4 ? local.length - 4 : 0)
            .map((m) => {'role': m.role, 'content': m.content})
            .toList();
        final modelInput = jsonEncode(input);
        if (generate is StreamingAgentModel) {
          return generate(
            modelInput,
            onPartial: (partial) {
              if (!allowed() || partial.isEmpty || partial.length > 32768) {
                return;
              }
              final messageId = _streamingMessageId ??= _uuid();
              messages = [
                ...local,
                ChatMessage(
                  id: messageId,
                  role: 'assistant',
                  content: 'Gemma is generating (partial):\n\n$partial',
                  createdAt: DateTime.now().toUtc(),
                  metadata: const {'streaming': true},
                ),
              ];
              notifyListeners();
            },
          );
        }
        return generate(modelInput);
      },
      gateway: (proposal) =>
          _tool(repository.scope, token, proposal, allowed: allowed),
      scopeGuard: allowed,
      onProgress: (event) {
        if (epoch == _epoch) {
          progress = [...progress, event];
          notifyListeners();
        }
      },
    );
    _runner = runner;
    final result = await runner.run(content);
    if (epoch != _epoch) return;
    if (result.deletePreview != null && allowed()) {
      final proposal = result.deletePreview!;
      final receipt = await service.agentOperation(
        token,
        repository.scope.projectId,
        repository.scope.deploymentId,
        _payload(repository.scope, proposal, _uuid()),
      );
      if (epoch != _epoch) return;
      if (!allowed()) return;
      if (receipt['state'] != 'awaiting_confirmation') {
        throw StateError('Deletion preview unavailable.');
      }
      final target = await _tool(
        repository.scope,
        token,
        OperationProposal(method: 'GET', path: proposal.path, body: null),
        allowed: allowed,
      );
      if (epoch != _epoch) return;
      if (!allowed()) return;
      deletePreview = {...receipt, 'target': target.toJson()};
      deleteLabels = result.deleteLabels.isNotEmpty
          ? result.deleteLabels
          : [_label(selectedContract, proposal.path, target.data)];
    }
    if (result.deletePreviews.isNotEmpty && allowed()) {
      final previews = <Map<String, dynamic>>[];
      for (final proposal in result.deletePreviews) {
        final receipt = await service.agentOperation(
          token,
          repository.scope.projectId,
          repository.scope.deploymentId,
          _payload(repository.scope, proposal, _uuid()),
        );
        if (epoch != _epoch || !allowed()) return;
        if (receipt['state'] != 'awaiting_confirmation') {
          throw StateError('Deletion preview unavailable.');
        }
        previews.add(receipt);
      }
      deletePreviews = previews;
      deleteLabels = result.deleteLabels;
    }
    await store.appendMessage(
      repository.scope,
      conversation.id,
      ChatMessage(
        id: _uuid(),
        role: 'assistant',
        content: result.text.length > 16000
            ? result.text.substring(0, 16000)
            : result.text,
        createdAt: DateTime.now().toUtc(),
        metadata: {'status': result.status.name},
      ),
    );
    final updated = await store.messages(repository.scope, conversation.id);
    if (epoch != _epoch) return;
    _streamingMessageId = null;
    messages = updated;
    notifyListeners();
    try {
      await repository.createConversation(token, conversation);
      if (epoch == _epoch) {
        await repository.syncMessages(token, conversation.id);
      }
    } catch (_) {
      if (epoch == _epoch) {
        error =
            'Conversation saved locally; history synchronization is pending.';
      }
    }
  });

  Map<String, dynamic> _payload(
    ChatScope scope,
    OperationProposal proposal,
    String id,
  ) => {
    'operationId': id,
    'contractHash': scope.contractHash,
    'method': proposal.method,
    'path': proposal.path,
    'body': proposal.body,
  };

  Future<AgentToolResult> _tool(
    ChatScope scope,
    String token,
    OperationProposal proposal, {
    bool Function()? allowed,
  }) async {
    void check() {
      if (allowed != null && !allowed()) {
        throw StateError('Request stopped before dispatch.');
      }
    }

    check();
    if (proposal.method == 'GET') {
      try {
        final result = await service.execute(
          token,
          scope.projectId,
          scope.deploymentId,
          scope.contractHash,
          proposal,
        );
        if (result.status < 200 || result.status >= 300) {
          throw StateError('Backend read rejected: ${result.status}');
        }
        final time = DateTime.now().toUtc();
        await store.putCache(
          CacheEntry(
            scope: scope,
            path: proposal.cacheKey,
            body: result.body,
            fetchedAt: time,
            complete: false,
          ),
        );
        return AgentToolResult(
          data: result.body,
          complete: false,
          fetchedAt: time,
        );
      } catch (_) {
        final cached = await store.getCache(scope, proposal.cacheKey);
        if (cached == null) rethrow;
        return AgentToolResult(
          data: cached.body,
          complete: cached.complete,
          fetchedAt: cached.fetchedAt,
          source: 'offline cache',
          committed: false,
        );
      }
    }
    return _write(scope, token, proposal, allowed: allowed);
  }

  Future<AgentToolResult> _write(
    ChatScope scope,
    String token,
    OperationProposal proposal, {
    Map<String, dynamic>? preview,
    bool Function()? allowed,
  }) async {
    if (_writeScopes.contains(scope.namespace)) {
      throw StateError('A backend write remains in flight.');
    }
    void check() {
      if (allowed != null && !allowed()) {
        throw StateError('Request stopped before write dispatch.');
      }
    }

    check();
    final receipts = await store.cache(scope);
    check();
    if (receipts.any(
      (c) => c.path.startsWith('/.agent/receipts/') && !c.complete,
    )) {
      throw StateError(
        'Inspect the previous unresolved operation before another write.',
      );
    }
    final id = preview?['operationId'] as String? ?? _uuid();
    final payload = _payload(scope, proposal, id);
    if (preview != null) {
      payload['confirmationToken'] = preview['confirmationToken'];
    }
    final key = '/.agent/receipts/$id';
    _writeScopes.add(scope.namespace);
    try {
      await store.putCache(
        CacheEntry(
          scope: scope,
          path: key,
          body: payload,
          fetchedAt: DateTime.now().toUtc(),
          complete: false,
        ),
      );
      if (allowed != null && !allowed()) {
        await store.putCache(
          CacheEntry(
            scope: scope,
            path: key,
            body: {'state': 'cancelled_before_dispatch'},
            fetchedAt: DateTime.now().toUtc(),
            complete: true,
          ),
        );
        check();
      }
      final receipt = await service.agentOperation(
        token,
        scope.projectId,
        scope.deploymentId,
        payload,
      );
      final completed = receipt['state'] == 'completed';
      await store.putCache(
        CacheEntry(
          scope: scope,
          path: key,
          body: {'request': payload, 'receipt': receipt},
          fetchedAt: DateTime.now().toUtc(),
          complete: completed,
        ),
      );
      if (!completed) {
        throw StateError(
          'Operation outcome is unresolved; no automatic resend.',
        );
      }
      final status = receipt['status'] as int?;
      if (status == null || status < 200 || status >= 300) {
        throw BackendRejection.from(status, receipt['result']);
      }
      return AgentToolResult(
        data: receipt['result'],
        complete: false,
        fetchedAt: DateTime.now().toUtc(),
      );
    } finally {
      _writeScopes.remove(scope.namespace);
    }
  }

  /// Confirms the pending deletion: one exact target, or every target of a
  /// bounded bulk preview, each with its own persisted preview receipt.
  Future<void> confirmDelete() => _run((repository, token, epoch) async {
    final previews = [?deletePreview, ...deletePreviews];
    final labels = deleteLabels;
    final conversation = selected;
    if (previews.isEmpty || conversation == null) return;
    deletePreview = null;
    deletePreviews = const [];
    deleteLabels = const [];
    _requestCancelled = false;
    final deadline = DateTime.now().add(const Duration(minutes: 5));
    bool allowed() =>
        epoch == _epoch &&
        !_requestCancelled &&
        DateTime.now().isBefore(deadline) &&
        identical(selected, conversation);
    var deleted = 0;
    try {
      for (final preview in previews) {
        await _write(
          repository.scope,
          token,
          OperationProposal(
            method: 'DELETE',
            path: preview['path'] as String,
            body: preview['body'],
          ),
          preview: preview,
          allowed: allowed,
        );
        deleted++;
        if (epoch != _epoch) return;
      }
    } finally {
      if (epoch == _epoch && deleted > 0) {
        final names = labels.take(deleted).join(', ');
        await store.appendMessage(
          repository.scope,
          conversation.id,
          ChatMessage(
            id: _uuid(),
            role: 'assistant',
            content: deleted == 1
                ? 'Eliminado${names.isEmpty ? '' : ': $names'}.'
                : 'Eliminados $deleted de ${previews.length} registros${names.isEmpty ? '' : ': $names'}.',
            createdAt: DateTime.now().toUtc(),
          ),
        );
        final local = await store.messages(repository.scope, conversation.id);
        if (epoch == _epoch) messages = local;
      }
    }
  });

  /// Display label of a record read from [path], from its entity's readable
  /// fields; never its identifier.
  String _label(BackendContract contract, String path, dynamic record) {
    final schema = AgentContract(contract).schema;
    final entity = schema.entities.values
        .where((candidate) => path.startsWith('${candidate.path}/'))
        .firstOrNull;
    if (entity == null || record is! Map) return 'el registro seleccionado';
    return QueryPlanner.label(entity, record);
  }

  Future<void> inspectOperations() => _run((repository, token, epoch) async {
    final cached = await store.cache(repository.scope);
    for (final item in cached.where(
      (c) => c.path.startsWith('/.agent/receipts/') && !c.complete,
    )) {
      if (epoch != _epoch) return;
      final id = item.path.split('/').last;
      final receipt = await service.agentReceipt(
        token,
        repository.scope.projectId,
        repository.scope.deploymentId,
        id,
        repository.scope.contractHash,
      );
      await store.putCache(
        CacheEntry(
          scope: repository.scope,
          path: item.path,
          body: receipt,
          fetchedAt: DateTime.now().toUtc(),
          complete: receipt['state'] == 'completed',
        ),
      );
      if (receipt['state'] != 'completed') {
        throw StateError(
          'An operation is still unresolved; automatic resend remains disabled.',
        );
      }
    }
  });

  void dismissDelete() {
    deletePreview = null;
    deletePreviews = const [];
    deleteLabels = const [];
    notifyListeners();
  }

  Future<void> saveMessage(String content) =>
      _run((repository, token, epoch) async {
        final conversation = selected;
        if (conversation == null || content.trim().isEmpty) return;
        if (content.length > 16000) {
          throw ArgumentError('Message exceeds 16000 characters');
        }
        await store.appendMessage(
          repository.scope,
          conversation.id,
          ChatMessage(
            id: _uuid(),
            role: 'user',
            content: content,
            createdAt: DateTime.now().toUtc(),
          ),
        );
        if (epoch != _epoch) return;
        setDraft('');
        final savedMessages = await store.messages(
          repository.scope,
          conversation.id,
        );
        if (epoch != _epoch) return;
        messages = savedMessages;
        notifyListeners();
        await repository.createConversation(token, conversation);
        if (epoch != _epoch) return;
        await repository.syncMessages(token, conversation.id);
      });
}

String _uuid() {
  final random = Random.secure();
  final bytes = List.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}
