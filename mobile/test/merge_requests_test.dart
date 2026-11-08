import 'dart:async';
import 'dart:convert';

import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/main.dart';
import 'package:diagrammer_mobile/presentation/screens/merge_requests_screen.dart';
import 'package:diagrammer_mobile/presentation/session_controller.dart';
import 'package:diagrammer_mobile/presentation/workspace_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'workspace_switch_test.dart' as f;

class SessionApi extends f.SwitchPlatform {
  int logoutCalls = 0;
  @override
  Future<void> logout(String token) async { logoutCalls++; }
}

final replacementSession = UserSession(
  token: 'replacement', expiresAt: DateTime.utc(2099),
  user: const AppUser(id: 'u2', username: 'other', displayName: 'Other', email: null),
);

void main() {
  test('authenticated list uses only the project read endpoint', () async {
    final calls = <http.Request>[];
    final api = PlatformService(baseUri: Uri.parse('https://example.test'), client: MockClient((request) async {
      calls.add(request);
      return http.Response(jsonEncode([{'mergeRequestId': 'mr', 'title': 'Cambio', 'state': 'OPEN', 'sourceBranch': 'feature', 'targetBranch': 'main'}]), 200);
    }));
    final result = await api.mergeRequests('token', 'project id');
    expect(calls, hasLength(1));
    expect(calls.single.method, 'GET');
    expect(calls.single.url.toString(), 'https://example.test/projects/project%20id/vcs/merge-requests');
    expect(calls.single.headers['authorization'], 'Bearer token');
    expect(result.single.title, 'Cambio');
    expect(result.single.sourceBranch, 'feature');
    expect(result.single.targetBranch, 'main');
    expect(result.single.state, 'OPEN');
  });

  test('HTTP failures retain their status and only 401 expires', () async {
    for (final status in [401, 403, 500]) {
      final api = PlatformService(baseUri: Uri.parse('https://example.test'), client: MockClient((_) async => http.Response('', status)));
      await expectLater(api.mergeRequests('token', 'p'), throwsA(isA<PlatformException>().having((e) => e.sessionExpired, 'expired', status == 401)));
    }
  });

  testWidgets('project navigation loads list and back preserves selected backend', (tester) async {
    await tester.binding.setSurfaceSize(const Size(1080, 1200));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final calls = <http.Request>[];
    final api = PlatformService(baseUri: Uri.parse('https://example.test'), client: MockClient((request) async {
      calls.add(request);
      return http.Response(jsonEncode([{'mergeRequestId': 'mr', 'title': 'Cambio', 'state': 'OPEN', 'sourceBranch': 'feature', 'targetBranch': 'main'}]), 200);
    }));
    final sessionApi = SessionApi();
    final store = f.SwitchStore(savedSession: f.switchSession);
    final session = SessionController(store, sessionApi);
    await session.restore();
    final workspace = f.switchController(sessionApi, store);
    await workspace.selectBackend(f.switchSession, f.switchA);
    await tester.pumpWidget(DiagrammerApp(session: session, workspace: workspace, platformService: api));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Solicitudes de fusión'));
    await tester.pumpAndSettle();
    expect(find.text('Cambio'), findsOneWidget);
    expect(find.text('feature → main · OPEN'), findsOneWidget);
    expect(calls, hasLength(1));
    expect(calls.single.method, 'GET');
    expect(calls.single.url.path, '/projects/p/vcs/merge-requests');
    expect(calls.single.headers['authorization'], 'Bearer fixture-token');
    await tester.tap(find.byTooltip('Volver'));
    await tester.pumpAndSettle();
    expect(find.text('Contrato hash-a'), findsOneWidget);
    expect(workspace.backend, f.switchA);
    expect(session.authenticated, isTrue);
    expect(sessionApi.calls, isEmpty);
    expect(sessionApi.logoutCalls, 0);
  });

  for (final status in [401, 403, 500]) {
    testWidgets('list HTTP $status only expires on 401', (tester) async {
      final api = PlatformService(baseUri: Uri.parse('https://example.test'), client: MockClient((_) async => http.Response('', status)));
      final sessionApi = SessionApi();
      final store = f.SwitchStore(savedSession: f.switchSession);
      final session = SessionController(store, sessionApi);
      await session.restore();
      final workspace = f.switchController(sessionApi, store);
      await tester.pumpWidget(MaterialApp(home: MergeRequestsScreen(session: session, workspace: workspace, service: api)));
      await tester.pumpAndSettle();
      if (status == 401) {
        expect(session.authenticated, isFalse);
        expect(store.savedSession, isNull);
        expect(sessionApi.logoutCalls, 1);
        expect(session.error, 'Tu sesión venció. Inicia sesión de nuevo.');
      } else {
        expect(session.authenticated, isTrue);
        expect(store.savedSession, same(f.switchSession));
        expect(sessionApi.logoutCalls, 0);
        expect(find.text('La solicitud a la plataforma falló ($status).'), findsOneWidget);
      }
    });
  }

  testWidgets('old session 401 cannot expire replacement or replace its list', (tester) async {
    final old = Completer<http.Response>();
    final calls = <http.Request>[];
    final api = PlatformService(baseUri: Uri.parse('https://example.test'), client: MockClient((request) {
      calls.add(request);
      if (request.headers['authorization'] == 'Bearer fixture-token') return old.future;
      return Future.value(http.Response(jsonEncode([{'mergeRequestId': 'new', 'title': 'Nueva sesión', 'state': 'OPEN', 'sourceBranch': 'b', 'targetBranch': 'main'}]), 200));
    }));
    final sessionApi = SessionApi();
    final store = f.SwitchStore(savedSession: f.switchSession);
    final session = SessionController(store, sessionApi);
    await session.restore();
    final workspace = f.switchController(sessionApi, store);
    await tester.pumpWidget(MaterialApp(home: MergeRequestsScreen(session: session, workspace: workspace, service: api)));
    store.savedSession = replacementSession;
    await session.restore();
    await tester.pump();
    await tester.pump();
    old.complete(http.Response('', 401));
    await tester.pumpAndSettle();
    expect(calls, hasLength(2));
    expect(session.session, same(replacementSession));
    expect(store.savedSession, same(replacementSession));
    expect(sessionApi.logoutCalls, 0);
    expect(find.text('Nueva sesión'), findsOneWidget);
  });

  testWidgets('late project response is ignored and selected project is shown', (tester) async {
    final old = Completer<http.Response>();
    final api = PlatformService(baseUri: Uri.parse('https://example.test'), client: MockClient((request) {
      if (request.url.path.contains('/p/')) return old.future;
      return Future.value(http.Response(jsonEncode([{'mergeRequestId': 'new', 'title': 'Nueva', 'state': 'OPEN', 'sourceBranch': 'b', 'targetBranch': 'main'}]), 200));
    }));
    final store = f.SwitchStore(savedSession: f.switchSession);
    final session = SessionController(store, f.SwitchPlatform());
    await session.restore();
    final workspace = WorkspaceController();
    workspace.project = const PlatformProject(id: 'p', name: 'Anterior');
    await tester.pumpWidget(MaterialApp(home: MergeRequestsScreen(session: session, workspace: workspace, service: api)));
    workspace.project = const PlatformProject(id: 'q', name: 'Actual');
    workspace.notifyListeners();
    await tester.pump();
    await tester.pump();
    old.complete(http.Response(jsonEncode([{'mergeRequestId': 'old', 'title': 'Antigua', 'state': 'OPEN', 'sourceBranch': 'a', 'targetBranch': 'main'}]), 200));
    await tester.pump();
    expect(find.text('Actual'), findsOneWidget);
    expect(find.text('Nueva'), findsOneWidget);
    expect(find.text('Antigua'), findsNothing);
    expect(session.authenticated, isTrue);
  });
}
