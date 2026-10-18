import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../domain/models.dart';

abstract interface class SecureStore {
  Future<void> saveSession(UserSession session);
  Future<UserSession?> readSession();
  Future<void> savePending(String scope, String value);
  Future<String?> readPending(String scope);
  Future<void> clearAll();
}

class FlutterSecureStore implements SecureStore {
  FlutterSecureStore([FlutterSecureStorage? storage])
    : _storage = storage ?? const FlutterSecureStorage();
  final FlutterSecureStorage _storage;
  static const _sessionKey = 'diagrammer.session';

  @override
  Future<void> saveSession(UserSession session) => _storage.write(
    key: _sessionKey,
    value: jsonEncode({
      'token': session.token,
      'expiresAt': session.expiresAt.toUtc().toIso8601String(),
      'user': {
        'id': session.user.id,
        'username': session.user.username,
        'displayName': session.user.displayName,
        'email': session.user.email,
      },
    }),
  );

  @override
  Future<UserSession?> readSession() async {
    final value = await _storage.read(key: _sessionKey);
    if (value == null) return null;
    final json = jsonDecode(value) as Map<String, dynamic>;
    final user = json['user'] as Map<String, dynamic>;
    return UserSession(
      token: json['token'] as String,
      expiresAt: DateTime.parse(json['expiresAt'] as String),
      user: AppUser(
        id: user['id'] as String,
        username: user['username'] as String,
        displayName: user['displayName'] as String,
        email: user['email'] as String?,
      ),
    );
  }

  @override
  Future<void> savePending(String scope, String value) =>
      _storage.write(key: 'pending.$scope', value: value);
  @override
  Future<String?> readPending(String scope) =>
      _storage.read(key: 'pending.$scope');
  @override
  Future<void> clearAll() => _storage.deleteAll();
}
