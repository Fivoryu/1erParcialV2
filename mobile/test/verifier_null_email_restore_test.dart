import 'dart:convert';

import 'package:diagrammer_mobile/data/secure_store.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart' as plugin;
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('restores an authenticated user whose optional email is null', () async {
    plugin.FlutterSecureStorage.setMockInitialValues({
      'diagrammer.session': jsonEncode({
        'token': 'secret',
        'expiresAt': DateTime.utc(2099).toIso8601String(),
        'user': {
          'id': 'user-id',
          'username': 'member',
          'displayName': 'Member',
          'email': null,
        },
      }),
    });

    final session = await FlutterSecureStore().readSession();

    expect(session, isNotNull);
    expect(session!.user.email, isNull);
  });
}
