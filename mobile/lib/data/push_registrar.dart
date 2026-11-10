import 'dart:async';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';

import 'platform_service.dart';

/// Source of the device push token. Abstract so the registration flow is testable without Firebase.
abstract class PushTokenSource {
  Future<String?> token();
  Stream<String> get refreshes;
}

/// Firebase Cloud Messaging token source. Any failure (no Play services, permission denied, missing
/// configuration) yields no token: push is optional and must never break sign-in.
class FirebasePushTokenSource implements PushTokenSource {
  Future<FirebaseMessaging?> _messaging() async {
    try {
      if (Firebase.apps.isEmpty) await Firebase.initializeApp();
      final messaging = FirebaseMessaging.instance;
      final settings = await messaging.requestPermission();
      if (settings.authorizationStatus == AuthorizationStatus.denied) return null;
      return messaging;
    } catch (_) {
      return null;
    }
  }

  @override
  Future<String?> token() async => (await _messaging())?.getToken();

  @override
  Stream<String> get refreshes => Firebase.apps.isEmpty
      ? const Stream.empty()
      : FirebaseMessaging.instance.onTokenRefresh;
}

/// Registers the device token for the signed-in user, follows token rotation and removes it on sign-out.
class PushRegistrar {
  PushRegistrar(this._source, this._service);
  final PushTokenSource _source;
  final PlatformService _service;
  String? _registered;
  StreamSubscription<String>? _refresh;

  Future<void> register(String sessionToken) async {
    try {
      final token = await _source.token();
      if (token == null || token.isEmpty) return;
      await _service.registerPushDevice(sessionToken, token);
      _registered = token;
      await _refresh?.cancel();
      _refresh = _source.refreshes.listen((next) async {
        try {
          await _service.registerPushDevice(sessionToken, next);
          final previous = _registered;
          _registered = next;
          if (previous != null && previous != next) {
            await _service.unregisterPushDevice(sessionToken, previous);
          }
        } catch (_) {}
      });
    } catch (_) {}
  }

  Future<void> unregister(String sessionToken) async {
    await _refresh?.cancel();
    _refresh = null;
    final token = _registered;
    _registered = null;
    if (token == null) return;
    try {
      await _service.unregisterPushDevice(sessionToken, token);
    } catch (_) {}
  }
}
