import 'dart:convert';

import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/main.dart';
import 'package:diagrammer_mobile/presentation/session_controller.dart';
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

void main() {
  testWidgets('project navigation loads list and back preserves selected backend', (tester) async {
    await tester.binding.setSurfaceSize(const Size(1080, 1200));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final calls = <http.Request>[];
    final api = PlatformService(baseUri: Uri.parse('https://example.test'), client: MockClient((request) async {
      calls.add(request);
      if (request.url.path.endsWith('/mr/diff')) return http.Response(jsonEncode({'changes': [], 'commits': []}), 200);
      if (request.url.path.endsWith('/mr')) return http.Response(jsonEncode({'title': 'Detalle', 'state': 'OPEN', 'sourceBranch': 'feature', 'targetBranch': 'main', 'approvals': []}), 200);
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
    await tester.tap(find.text('Cambio'));
    await tester.pumpAndSettle();
    expect(find.text('Detalle'), findsOneWidget);
    expect(calls.map((r) => r.url.path).toList(), ['/projects/p/vcs/merge-requests', '/projects/p/vcs/merge-requests/mr', '/projects/p/vcs/merge-requests/mr/diff']);
    expect(calls.every((r) => r.method == 'GET' && r.headers['authorization'] == 'Bearer fixture-token'), isTrue);
    await tester.tap(find.byTooltip('Volver'));
    await tester.pumpAndSettle();
    expect(find.text('Cambio'), findsOneWidget);
    await tester.tap(find.byTooltip('Volver'));
    await tester.pumpAndSettle();
    expect(find.text('Contrato hash-a'), findsOneWidget);
    expect(workspace.backend, f.switchA);
    expect(session.authenticated, isTrue);
    expect(sessionApi.calls, isEmpty);
    expect(sessionApi.logoutCalls, 0);
  });
}
