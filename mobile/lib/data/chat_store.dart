import 'dart:convert';

import 'package:flutter/services.dart';

import '../domain/chat_models.dart';

abstract interface class ChatStore {
  Future<void> saveConversation(LocalConversation conversation);
  Future<List<LocalConversation>> listConversations(ChatScope scope);
  Future<List<ChatMessage>> messages(ChatScope scope, String conversationId);
  Future<void> appendMessage(
    ChatScope scope,
    String conversationId,
    ChatMessage message,
  );
  Future<void> putCache(CacheEntry entry);
  Future<CacheEntry?> getCache(ChatScope scope, String path);
  Future<List<CacheEntry>> cache(ChatScope scope, {String? path});
  Future<void> enqueue(QueuedOperation operation, {ChatMessage? message});
  Future<List<QueuedOperation>> outbox(ChatScope scope);
  Future<void> updateOutbox(
    ChatScope scope,
    String id,
    OutboxState state, {
    String? error,
  });
  Future<void> close();
}

/// Android application-private SQLite. This adapter never dispatches operations.
class MethodChannelChatStore implements ChatStore {
  MethodChannelChatStore({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel('diagrammer/chat_store');
  final MethodChannel _channel;
  bool _closed = false;
  Future<dynamic> _invoke(String method, Map<String, dynamic> args) async {
    if (_closed) throw StateError('El almacén del chat está cerrado.');
    validateRecord(args);
    return _channel.invokeMethod<dynamic>(method, args);
  }

  Map<String, dynamic> _scope(ChatScope scope) => {'scope': scope.toJson()};
  Map<String, dynamic> _record(dynamic value) =>
      Map<String, dynamic>.from(jsonDecode(value as String) as Map);
  Future<List<T>> _list<T>(
    String method,
    Map<String, dynamic> args,
    T Function(Map<String, dynamic>) decode,
  ) async {
    final result = await _invoke(method, args) as List<dynamic>;
    return result.map((v) => decode(_record(v))).toList(growable: false);
  }

  @override
  Future<void> saveConversation(LocalConversation conversation) async {
    await _invoke('saveConversation', {'conversation': conversation.toJson()});
  }

  @override
  Future<List<LocalConversation>> listConversations(ChatScope scope) =>
      _list('listConversations', _scope(scope), LocalConversation.fromJson);
  @override
  Future<List<ChatMessage>> messages(ChatScope scope, String conversationId) =>
      _list('messages', {
        ..._scope(scope),
        'conversationId': identity(conversationId, 'conversationId'),
      }, ChatMessage.fromJson);
  @override
  Future<void> appendMessage(
    ChatScope scope,
    String conversationId,
    ChatMessage message,
  ) async {
    await _invoke('appendMessage', {
      ..._scope(scope),
      'conversationId': identity(conversationId, 'conversationId'),
      'message': message.toJson(),
    });
  }

  @override
  Future<void> putCache(CacheEntry entry) async {
    await _invoke('putCache', {'entry': entry.toJson()});
  }

  @override
  Future<CacheEntry?> getCache(ChatScope scope, String path) async {
    final entries = await cache(scope, path: path);
    return entries.isEmpty ? null : entries.single;
  }

  @override
  Future<List<CacheEntry>> cache(ChatScope scope, {String? path}) => _list(
    'cache',
    {..._scope(scope), if (path != null) 'path': validPath(path)},
    CacheEntry.fromJson,
  );
  @override
  Future<void> enqueue(
    QueuedOperation operation, {
    ChatMessage? message,
  }) async {
    if (operation.state != OutboxState.queued) {
      throw ArgumentError('Las operaciones nuevas deben ponerse en cola.');
    }
    await _invoke('enqueue', {
      'operation': operation.toJson(),
      if (message != null) 'message': message.toJson(),
    });
  }

  @override
  Future<List<QueuedOperation>> outbox(ChatScope scope) =>
      _list('outbox', _scope(scope), QueuedOperation.fromJson);
  @override
  Future<void> updateOutbox(
    ChatScope scope,
    String id,
    OutboxState state, {
    String? error,
  }) async {
    await _invoke('updateOutbox', {
      ..._scope(scope),
      'id': identity(id, 'id'),
      'state': state.name,
      'error': ?error,
    });
  }

  @override
  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    await _channel.invokeMethod<void>('close');
  }
}

/// Deterministic test seam; production uses [MethodChannelChatStore].
class MemoryChatStore implements ChatStore {
  final Map<String, LocalConversation> _conversations = {};
  final Map<String, Map<String, dynamic>> _messages = {};
  final Map<String, CacheEntry> _cache = {};
  final Map<String, QueuedOperation> _outbox = {};
  final Map<String, String?> _enqueueMessages = {};
  bool _closed = false;
  String _key(ChatScope scope, String id) =>
      jsonEncode([scope.namespace, identity(id, 'id')]);
  void _check() {
    if (_closed) throw StateError('El almacén del chat está cerrado.');
  }

  void _conversation(ChatScope scope, String id) {
    if (!_conversations.containsKey(_key(scope, id))) {
      throw StateError('No se encontró la conversación en este ámbito.');
    }
  }

  String _canonical(dynamic value) => jsonEncode(_sorted(value));
  dynamic _sorted(dynamic value) {
    if (value is Map) {
      final keys = value.keys.cast<String>().toList()..sort();
      return {for (final k in keys) k: _sorted(value[k])};
    }
    if (value is List) return value.map(_sorted).toList();
    return value;
  }

  void _validateMessage(
    ChatScope scope,
    String conversationId,
    ChatMessage message,
  ) {
    _conversation(scope, conversationId);
    validateRecord(message.toJson());
    final old = _messages[_key(scope, message.id)];
    if (old != null &&
        (old['conversationId'] != conversationId ||
            _canonical(old['message']) != _canonical(message.toJson()))) {
      throw StateError('Conflicto con el identificador del mensaje.');
    }
  }

  @override
  Future<void> saveConversation(LocalConversation conversation) async {
    _check();
    validateRecord(conversation.toJson());
    final key = _key(conversation.scope, conversation.id),
        old = _conversations[_key(conversation.scope, conversation.id)];
    if (old != null &&
        old.createdAt.toUtc() != conversation.createdAt.toUtc()) {
      throw StateError('Conflicto con el identificador de la conversación.');
    }
    _conversations[key] = LocalConversation.fromJson(conversation.toJson());
  }

  @override
  Future<List<LocalConversation>> listConversations(ChatScope scope) async {
    _check();
    final result = _conversations.values.where((c) => c.scope == scope).toList()
      ..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    return result;
  }

  @override
  Future<List<ChatMessage>> messages(
    ChatScope scope,
    String conversationId,
  ) async {
    _check();
    identity(conversationId, 'conversationId');
    return _messages.values
        .where(
          (m) =>
              m['namespace'] == scope.namespace &&
              m['conversationId'] == conversationId,
        )
        .map(
          (m) => ChatMessage.fromJson(
            Map<String, dynamic>.from(m['message'] as Map),
          ),
        )
        .toList();
  }

  @override
  Future<void> appendMessage(
    ChatScope scope,
    String conversationId,
    ChatMessage message,
  ) async {
    _check();
    _validateMessage(scope, conversationId, message);
    _messages[_key(scope, message.id)] = {
      'namespace': scope.namespace,
      'conversationId': conversationId,
      'message': jsonDecode(jsonEncode(message.toJson())),
    };
  }

  @override
  Future<void> putCache(CacheEntry entry) async {
    _check();
    validateRecord(entry.toJson());
    _cache[_key(entry.scope, entry.path)] = CacheEntry.fromJson(
      jsonDecode(jsonEncode(entry.toJson())) as Map<String, dynamic>,
    );
  }

  @override
  Future<CacheEntry?> getCache(ChatScope scope, String path) async {
    _check();
    validPath(path);
    return _cache[_key(scope, path)];
  }

  @override
  Future<List<CacheEntry>> cache(ChatScope scope, {String? path}) async {
    _check();
    if (path != null) validPath(path);
    return _cache.values
        .where((e) => e.scope == scope && (path == null || path == e.path))
        .toList();
  }

  Map<String, dynamic> _operationIdentity(QueuedOperation op) =>
      Map.of(op.toJson())
        ..remove('state')
        ..remove('error');
  @override
  Future<void> enqueue(
    QueuedOperation operation, {
    ChatMessage? message,
  }) async {
    _check();
    _conversation(operation.scope, operation.conversationId);
    validateRecord(operation.toJson());
    if (operation.state != OutboxState.queued) {
      throw ArgumentError('Las operaciones nuevas deben ponerse en cola.');
    }
    if (message != null) {
      _validateMessage(operation.scope, operation.conversationId, message);
    }
    final key = _key(operation.scope, operation.id),
        old = _outbox[_key(operation.scope, operation.id)];
    if (old != null &&
        _canonical(_operationIdentity(old)) !=
            _canonical(_operationIdentity(operation))) {
      throw StateError('Conflicto con el identificador de la operación.');
    }
    final fingerprint = message == null ? null : _canonical(message.toJson());
    if (old != null && _enqueueMessages[key] != fingerprint) {
      throw StateError('Conflicto con el mensaje de la operación.');
    }
    if (message != null) {
      _messages[_key(operation.scope, message.id)] = {
        'namespace': operation.scope.namespace,
        'conversationId': operation.conversationId,
        'message': jsonDecode(jsonEncode(message.toJson())),
      };
    }
    _enqueueMessages.putIfAbsent(key, () => fingerprint);
    _outbox.putIfAbsent(
      key,
      () => QueuedOperation.fromJson(
        jsonDecode(jsonEncode(operation.toJson())) as Map<String, dynamic>,
      ),
    );
  }

  @override
  Future<List<QueuedOperation>> outbox(ChatScope scope) async {
    _check();
    return _outbox.values.where((o) => o.scope == scope).toList();
  }

  @override
  Future<void> updateOutbox(
    ChatScope scope,
    String id,
    OutboxState state, {
    String? error,
  }) async {
    _check();
    final key = _key(scope, id), old = _outbox[_key(scope, id)];
    if (old == null || !canTransition(old.state, state)) {
      throw StateError('La transición de la bandeja de salida no es válida.');
    }
    final json = old.toJson()
      ..['state'] = state.name
      ..remove('error');
    if (error != null) json['error'] = error;
    validateRecord(json);
    _outbox[key] = QueuedOperation.fromJson(json);
  }

  @override
  Future<void> close() async {
    _closed = true;
  }
}
