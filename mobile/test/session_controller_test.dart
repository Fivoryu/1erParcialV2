import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/data/secure_store.dart';
import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/presentation/session_controller.dart';
import 'package:flutter_test/flutter_test.dart';

class MemoryStore implements SecureStore {
  UserSession? session;
  var cleared = false;
  final pending = <String, String>{};
  @override
  Future<void> clearAll() async {
    cleared = true;
    session = null;
    pending.clear();
  }

  @override
  Future<UserSession?> readSession() async => session;
  @override
  Future<String?> readPending(String scope) async => pending[scope];
  @override
  Future<void> savePending(String scope, String value) async =>
      pending[scope] = value;
  @override
  Future<void> saveSession(UserSession value) async => session = value;
}

class FakePlatform extends PlatformService {
  FakePlatform() : super(baseUri: Uri.parse('https://example.test'));
  @override
  Future<void> logout(String token) async {}
}

void main() {
  test('expired restored session clears persistent token state', () async {
    final store = MemoryStore()
      ..session = UserSession(
        token: 'secret',
        expiresAt: DateTime.now().toUtc().subtract(const Duration(minutes: 1)),
        user: const AppUser(
          id: 'u',
          username: 'u',
          displayName: 'U',
          email: 'u@example.test',
        ),
      );
    final controller = SessionController(store, FakePlatform());
    await controller.restore();
    expect(controller.authenticated, isFalse);
    expect(store.cleared, isTrue);
    expect(store.session, isNull);
  });
}
