import '../domain/chat_models.dart';
import 'chat_store.dart';
import 'platform_service.dart';

/// Synchronizes private history without granting generated-data authority.
/// Original local append payloads remain immutable across every retry.
class ChatRepository {
  ChatRepository({
    required this.service,
    required this.store,
    required this.scope,
  }) {
    if (!service.matchesChatPlatform(scope.platform)) {
      throw ArgumentError(
        'Chat scope does not match the authenticated platform',
      );
    }
  }
  final PlatformService service;
  final ChatStore store;
  final ChatScope scope;
  bool _syncing = false;

  /// Refresh is bounded and additive: incomplete pages never erase local drafts.
  Future<List<LocalConversation>> refreshConversations(String token) async {
    if (_syncing) {
      throw StateError('History synchronization is already running');
    }
    _syncing = true;
    try {
      final existing = {
        for (final c in await store.listConversations(scope)) c.id: c,
      };
      String? cursor;
      final seen = <String>{};
      for (var page = 0; page < 100; page++) {
        final response = await service.chatRequest(
          token,
          scope.projectId,
          scope.deploymentId,
          cursor: cursor,
        );
        final items = response['items'];
        if (items is! List || items.length > 100) {
          throw const FormatException('Invalid conversation page');
        }
        for (final value in items) {
          final item = Map<String, dynamic>.from(value as Map);
          if (item['contractHash'] != scope.contractHash) {
            continue;
          }
          if (existing.containsKey(item['id'])) {
            continue;
          }
          await store.saveConversation(
            LocalConversation(
              id: item['id'] as String,
              scope: scope,
              title: item['title'] as String,
              createdAt: DateTime.parse(item['createdAt'] as String),
              updatedAt: DateTime.parse(item['updatedAt'] as String),
            ),
          );
        }
        final next = response['nextCursor'];
        if (next == null) return await store.listConversations(scope);
        if (next is! String ||
            next.isEmpty ||
            items.isEmpty ||
            !seen.add(next) ||
            (items.last as Map)['id'] != next) {
          throw const FormatException('Invalid conversation cursor');
        }
        cursor = next;
      }
      throw StateError('Conversation synchronization exceeded its page limit');
    } finally {
      _syncing = false;
    }
  }

  Future<void> createConversation(
    String token,
    LocalConversation conversation,
  ) async {
    if (conversation.scope != scope) {
      throw StateError('Foreign conversation scope');
    }
    await store.saveConversation(conversation);
    await service.chatRequest(
      token,
      scope.projectId,
      scope.deploymentId,
      payload: {
        'id': conversation.id,
        'title': conversation.title,
        'contractHash': scope.contractHash,
      },
    );
  }

  Future<void> syncMessages(String token, String conversationId) async {
    if (_syncing) {
      throw StateError('History synchronization is already running');
    }
    _syncing = true;
    try {
      final conversations = await store.listConversations(scope);
      if (!conversations.any((c) => c.id == conversationId)) {
        throw StateError('Conversation is unavailable in this scope');
      }
      final local = await store.messages(scope, conversationId);
      for (final message in local.where((m) => m.sequence == null)) {
        if (message.role == 'system') {
          continue;
        } // Local instructions are never synchronized.
        await service.chatRequest(
          token,
          scope.projectId,
          scope.deploymentId,
          conversationId: conversationId,
          payload: message.toJson(),
        );
      }
      var cursor = 0;
      final known = {for (final message in local) message.id: message};
      for (var page = 0; page < 100; page++) {
        final response = await service.chatRequest(
          token,
          scope.projectId,
          scope.deploymentId,
          conversationId: conversationId,
          afterSequence: cursor,
        );
        final items = response['items'];
        if (items is! List || items.length > 100) {
          throw const FormatException('Invalid history page');
        }
        for (final item in items) {
          final remote = ChatMessage.fromJson(
            Map<String, dynamic>.from(item as Map),
          );
          if (remote.sequence == null ||
              remote.sequence! <= cursor ||
              remote.role == 'system') {
            throw const FormatException('Invalid history sequence');
          }
          cursor = remote.sequence!;
          final original = known[remote.id];
          if (original != null) {
            if (original.role != remote.role ||
                original.content != remote.content ||
                !_sameJson(original.metadata ?? {}, remote.metadata ?? {})) {
              throw StateError('Remote message conflicts with local history');
            }
          } else {
            await store.appendMessage(scope, conversationId, remote);
            known[remote.id] = remote;
          }
        }
        final next = response['nextCursor'];
        if (next == null) return;
        if (items.isEmpty || int.tryParse(next.toString()) != cursor) {
          throw const FormatException('Invalid history cursor');
        }
      }
      throw StateError('History synchronization exceeded its page limit');
    } finally {
      _syncing = false;
    }
  }
}

bool _sameJson(dynamic left, dynamic right) {
  if (left is Map && right is Map) {
    return left.length == right.length &&
        left.keys.every(
          (key) => right.containsKey(key) && _sameJson(left[key], right[key]),
        );
  }
  if (left is List && right is List) {
    if (left.length != right.length) return false;
    for (var i = 0; i < left.length; i++) {
      if (!_sameJson(left[i], right[i])) return false;
    }
    return true;
  }
  return left == right;
}
