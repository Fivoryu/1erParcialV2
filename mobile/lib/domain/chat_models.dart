import 'dart:convert';

String identity(String value, String name) {
  if (value.trim().isEmpty || value.length > 2048 || value.contains('\u0000')) {
    {
      throw ArgumentError.value(value, name, 'Identidad no válida');
    }
  }
  return value;
}

void validateRecord(Map<String, dynamic> value) {
  if (utf8.encode(jsonEncode(value)).length > 1024 * 1024) {
    {
      throw ArgumentError('Record exceeds 1 MiB');
    }
  }
}

class ChatScope {
  ChatScope({
    required String platform,
    required String userId,
    required String projectId,
    required String deploymentId,
    required String contractHash,
  }) : platform = identity(platform, 'platform'),
       userId = identity(userId, 'userId'),
       projectId = identity(projectId, 'projectId'),
       deploymentId = identity(deploymentId, 'deploymentId'),
       contractHash = identity(contractHash, 'contractHash');
  final String platform, userId, projectId, deploymentId, contractHash;
  String get namespace =>
      jsonEncode([platform, userId, projectId, deploymentId, contractHash]);
  Map<String, dynamic> toJson() => {
    'platform': platform,
    'userId': userId,
    'projectId': projectId,
    'deploymentId': deploymentId,
    'contractHash': contractHash,
  };
  factory ChatScope.fromJson(Map<String, dynamic> json) => ChatScope(
    platform: json['platform'] as String,
    userId: json['userId'] as String,
    projectId: json['projectId'] as String,
    deploymentId: json['deploymentId'] as String,
    contractHash: json['contractHash'] as String,
  );
  @override
  bool operator ==(Object other) =>
      other is ChatScope && namespace == other.namespace;
  @override
  int get hashCode => namespace.hashCode;
}

class LocalConversation {
  LocalConversation({
    required String id,
    required this.scope,
    required this.title,
    required this.createdAt,
    required this.updatedAt,
  }) : id = identity(id, 'id');
  final String id, title;
  final ChatScope scope;
  final DateTime createdAt, updatedAt;
  Map<String, dynamic> toJson() => {
    'id': id,
    'scope': scope.toJson(),
    'title': title,
    'createdAt': createdAt.toUtc().toIso8601String(),
    'updatedAt': updatedAt.toUtc().toIso8601String(),
  };
  factory LocalConversation.fromJson(Map<String, dynamic> json) =>
      LocalConversation(
        id: json['id'] as String,
        scope: ChatScope.fromJson(
          Map<String, dynamic>.from(json['scope'] as Map),
        ),
        title: json['title'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
        updatedAt: DateTime.parse(json['updatedAt'] as String),
      );
}

class ChatMessage {
  ChatMessage({
    required String id,
    required this.role,
    required this.content,
    required this.createdAt,
    Map<String, dynamic>? metadata,
    this.sequence,
  }) : id = identity(id, 'id'),
       metadata = metadata == null ? null : Map.unmodifiable(metadata) {
    if (!['user', 'assistant', 'tool', 'system'].contains(role) ||
        (sequence != null && sequence! < 0)) {
      throw ArgumentError('El rol o la secuencia del mensaje no son válidos');
    }
  }
  final String id, role, content;
  final DateTime createdAt;
  final Map<String, dynamic>? metadata;
  final int? sequence;
  Map<String, dynamic> toJson() => {
    'id': id,
    'role': role,
    'content': content,
    'createdAt': createdAt.toUtc().toIso8601String(),
    if (metadata != null) 'metadata': metadata,
    if (sequence != null) 'sequence': sequence,
  };
  factory ChatMessage.fromJson(Map<String, dynamic> json) => ChatMessage(
    id: json['id'] as String,
    role: json['role'] as String,
    content: json['content'] as String,
    createdAt: DateTime.parse(json['createdAt'] as String),
    metadata: json['metadata'] == null
        ? null
        : Map<String, dynamic>.from(json['metadata'] as Map),
    sequence: json['sequence'] as int?,
  );
}

class CacheEntry {
  CacheEntry({
    required this.scope,
    required String path,
    required this.body,
    required this.fetchedAt,
    required this.complete,
  }) : path = validPath(path);
  final ChatScope scope;
  final String path;
  final dynamic body;
  final DateTime fetchedAt;
  final bool complete;
  Map<String, dynamic> toJson() => {
    'scope': scope.toJson(),
    'path': path,
    'body': body,
    'fetchedAt': fetchedAt.toUtc().toIso8601String(),
    'complete': complete,
  };
  factory CacheEntry.fromJson(Map<String, dynamic> json) => CacheEntry(
    scope: ChatScope.fromJson(Map<String, dynamic>.from(json['scope'] as Map)),
    path: json['path'] as String,
    body: json['body'],
    fetchedAt: DateTime.parse(json['fetchedAt'] as String),
    complete: json['complete'] as bool,
  );
}

String validPath(String value) {
  identity(value, 'path');
  final uri = Uri.parse(value);
  if (!value.startsWith('/') ||
      value.startsWith('//') ||
      uri.hasScheme ||
      uri.hasAuthority ||
      uri.hasFragment ||
      value.contains('\\')) {
    throw ArgumentError('Solo se admiten rutas relativas del backend');
  }
  return value;
}

enum OutboxState { queued, sending, completed, rejected, unknown, conflict }

bool canTransition(OutboxState from, OutboxState to) =>
    from == to ||
    switch (from) {
      OutboxState.queued =>
        to == OutboxState.sending ||
            to == OutboxState.rejected ||
            to == OutboxState.conflict,
      OutboxState.sending => [
        OutboxState.completed,
        OutboxState.rejected,
        OutboxState.unknown,
        OutboxState.conflict,
      ].contains(to),
      _ => false,
    };

class QueuedOperation {
  QueuedOperation({
    required String id,
    required this.scope,
    required String conversationId,
    required this.method,
    required String path,
    required this.body,
    required this.createdAt,
    this.state = OutboxState.queued,
    this.error,
  }) : id = identity(id, 'id'),
       conversationId = identity(conversationId, 'conversationId'),
       path = validPath(path) {
    if (method != 'POST') {
      throw ArgumentError('Solo se pueden poner en cola las incorporaciones sin conexión');
    }
  }
  final String id, conversationId, method, path;
  final ChatScope scope;
  final dynamic body;
  final DateTime createdAt;
  final OutboxState state;
  final String? error;
  Map<String, dynamic> toJson() => {
    'id': id,
    'scope': scope.toJson(),
    'conversationId': conversationId,
    'method': method,
    'path': path,
    'body': body,
    'createdAt': createdAt.toUtc().toIso8601String(),
    'state': state.name,
    'error': ?error,
  };
  factory QueuedOperation.fromJson(Map<String, dynamic> json) =>
      QueuedOperation(
        id: json['id'] as String,
        scope: ChatScope.fromJson(
          Map<String, dynamic>.from(json['scope'] as Map),
        ),
        conversationId: json['conversationId'] as String,
        method: json['method'] as String,
        path: json['path'] as String,
        body: json['body'],
        createdAt: DateTime.parse(json['createdAt'] as String),
        state: OutboxState.values.byName(json['state'] as String),
        error: json['error'] as String?,
      );
}
