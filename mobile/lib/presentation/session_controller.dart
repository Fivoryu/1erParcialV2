import 'package:flutter/foundation.dart';

import '../data/platform_service.dart';
import '../data/secure_store.dart';
import '../domain/models.dart';

class SessionController extends ChangeNotifier {
  SessionController(this._store, this._service);
  final SecureStore _store;
  final PlatformService _service;
  UserSession? _session;
  bool _ready = false;
  String? _error;
  int _epoch = 0;
  UserSession? get session => _session;
  bool get ready => _ready;
  bool get authenticated => _session != null && !_session!.isExpired;
  String? get error => _error;

  Future<void> restore() async {
    final saved = await _store.readSession();
    if (saved != null && !saved.isExpired) {
      _session = saved;
    } else if (saved != null) {
      await _store.clearAll();
    }
    _ready = true;
    notifyListeners();
  }

  Future<void> login(String username, String password) async {
    final epoch = ++_epoch;
    _error = null;
    notifyListeners();
    try {
      final result = await _service.login(username, password);
      if (epoch != _epoch) return;
      _session = result;
      await _store.saveSession(result);
      if (epoch != _epoch) return;
    } on PlatformException catch (error) {
      if (epoch != _epoch) return;
      _error = error.message;
    } finally {
      notifyListeners();
    }
  }

  Future<void> logout() async {
    ++_epoch;
    final current = _session;
    _session = null;
    _error = null;
    notifyListeners();
    await _store.clearAll();
    if (current != null) {
      try {
        await _service.logout(current.token);
      } catch (_) {}
    }
  }

  Future<void> expireIfCurrent(UserSession requestSession) async {
    if (!identical(_session, requestSession)) return;
    await logout();
    _error = 'Your session expired. Sign in again.';
    notifyListeners();
  }
}
