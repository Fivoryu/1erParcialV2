import 'dart:async';
import 'dart:convert';

import 'package:diagrammer_mobile/domain/models.dart';

import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/presentation/screens/merge_request_detail_screen.dart';
import 'package:diagrammer_mobile/presentation/session_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'workspace_switch_test.dart' as f;

Map<String, dynamic> detail() => {
  'title': 'Cambio',
  'state': 'OPEN',
  'sourceBranch': 'feature',
  'targetBranch': 'main',
  'approvals': [],
};
Map<String, dynamic> diff([List<dynamic> changes = const []]) => {
  'changes': changes,
  'commits': [],
  'hasConflicts': false,
};

void main() {
  for (final badDiff in [false, true]) {
    testWidgets(
      'malformed ${badDiff ? 'diff' : 'detail'} gives a controlled error',
      (tester) async {
        final api = PlatformService(
          baseUri: Uri.parse('https://example.test'),
          client: MockClient(
            (r) async => http.Response(
              jsonEncode(
                r.url.path.endsWith('/diff') == badDiff
                    ? {}
                    : (badDiff ? detail() : diff()),
              ),
              200,
            ),
          ),
        );
        final session = SessionController(
          f.SwitchStore(savedSession: f.switchSession),
          f.SwitchPlatform(),
        );
        await session.restore();
        final workspace = f.switchController(
          f.SwitchPlatform(),
          f.SwitchStore(),
        );
        await tester.pumpWidget(
          MaterialApp(
            home: MergeRequestDetailScreen(
              session: session,
              workspace: workspace,
              service: api,
              requestId: 'mr',
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.text('No se pudo cargar la solicitud.'), findsOneWidget);
        expect(tester.takeException(), isNull);
        expect(session.authenticated, isTrue);
      },
    );
  }

  for (final failDiff in [false, true]) {
    for (final status in [401, 403, 500]) {
      testWidgets(
        'HTTP $status on ${failDiff ? 'diff' : 'detail'} preserves session except 401',
        (tester) async {
          final calls = <http.Request>[];
          final api = PlatformService(
            baseUri: Uri.parse('https://example.test'),
            client: MockClient((r) async {
              calls.add(r);
              return r.url.path.endsWith('/diff') == failDiff
                  ? http.Response('', status)
                  : http.Response(jsonEncode(detail()), 200);
            }),
          );
          final store = f.SwitchStore(savedSession: f.switchSession);
          final session = SessionController(store, f.SwitchPlatform());
          await session.restore();
          final workspace = f.switchController(
            f.SwitchPlatform(),
            f.SwitchStore(),
          );
          await tester.pumpWidget(
            MaterialApp(
              home: MergeRequestDetailScreen(
                session: session,
                workspace: workspace,
                service: api,
                requestId: 'mr',
              ),
            ),
          );
          await tester.pumpAndSettle();
          expect(session.authenticated, status != 401);
          expect(store.savedSession == null, status == 401);
          if (status != 401)
            expect(
              find.text('La solicitud a la plataforma falló ($status).'),
              findsOneWidget,
            );
          expect(calls.length, failDiff ? 2 : 1);
        },
      );
    }
  }

  for (final staleSession in [false, true]) {
    for (final staleDiff in [false, true]) {
      testWidgets(
        'stale ${staleSession ? 'session' : 'project'} ignores delayed ${staleDiff ? 'diff' : 'detail'} 401',
        (tester) async {
          final pending = Completer<http.Response>();
          final api = PlatformService(
            baseUri: Uri.parse('https://example.test'),
            client: MockClient(
              (r) => r.url.path.endsWith('/diff') == staleDiff
                  ? pending.future
                  : Future.value(http.Response(jsonEncode(detail()), 200)),
            ),
          );
          final store = f.SwitchStore(savedSession: f.switchSession);
          final session = SessionController(store, f.SwitchPlatform());
          await session.restore();
          final workspace = f.switchController(
            f.SwitchPlatform(),
            f.SwitchStore(),
          );
          await tester.pumpWidget(
            MaterialApp(
              home: MergeRequestDetailScreen(
                session: session,
                workspace: workspace,
                service: api,
                requestId: 'mr',
              ),
            ),
          );
          await tester.pump();
          if (staleSession) {
            final replacement = UserSession(
              token: 'new',
              expiresAt: DateTime.utc(2099),
              user: const AppUser(
                id: 'u2',
                username: 'other',
                displayName: 'Other',
                email: null,
              ),
            );
            store.savedSession = replacement;
            await session.restore();
            expect(session.session, same(replacement));
          } else {
            workspace.project = const PlatformProject(
              id: 'other',
              name: 'Other',
            );
            workspace.notifyListeners();
          }
          await tester.pump();
          pending.complete(http.Response('', 401));
          await tester.pumpAndSettle();
          expect(session.authenticated, isTrue);
          expect(
            find.text('El proyecto o la sesión cambiaron. Vuelve a la lista.'),
            findsOneWidget,
          );
          expect(find.text('Cambio'), findsNothing);
        },
      );
    }
  }

  for (final staleSession in [false, true]) {
    for (final staleDiff in [false, true]) {
      testWidgets(
        'stale ${staleSession ? 'session' : 'project'} ignores successful ${staleDiff ? 'diff' : 'detail'}',
        (tester) async {
          final pending = Completer<http.Response>();
          final calls = <http.Request>[];
          final api = PlatformService(
            baseUri: Uri.parse('https://example.test'),
            client: MockClient((request) {
              calls.add(request);
              if (request.url.path.endsWith('/diff') == staleDiff) {
                return pending.future;
              }
              return Future.value(http.Response(jsonEncode(detail()), 200));
            }),
          );
          final store = f.SwitchStore(savedSession: f.switchSession);
          final session = SessionController(store, f.SwitchPlatform());
          await session.restore();
          final workspace = f.switchController(
            f.SwitchPlatform(),
            f.SwitchStore(),
          );
          await tester.pumpWidget(
            MaterialApp(
              home: MergeRequestDetailScreen(
                session: session,
                workspace: workspace,
                service: api,
                requestId: 'mr',
              ),
            ),
          );
          await tester.pump();
          final replacement = UserSession(
            token: 'replacement',
            expiresAt: DateTime.utc(2099),
            user: const AppUser(
              id: 'u2',
              username: 'other',
              displayName: 'Other',
              email: null,
            ),
          );
          if (staleSession) {
            store.savedSession = replacement;
            await session.restore();
          } else {
            workspace.project = const PlatformProject(
              id: 'other',
              name: 'Other',
            );
            workspace.notifyListeners();
          }
          final response = staleDiff
              ? {
                  ...diff(),
                  'commits': [
                    {'message': 'stale commit'},
                  ],
                }
              : {...detail(), 'title': 'stale detail'};
          pending.complete(http.Response(jsonEncode(response), 200));
          await tester.pumpAndSettle();
          expect(find.textContaining('stale'), findsNothing);
          expect(find.text('Cambio'), findsNothing);
          expect(
            find.text('El proyecto o la sesión cambiaron. Vuelve a la lista.'),
            findsOneWidget,
          );
          expect(calls.map((request) => request.url.path).toList(), [
            '/projects/p/vcs/merge-requests/mr',
            if (staleDiff) '/projects/p/vcs/merge-requests/mr/diff',
          ]);
          expect(
            calls.every(
              (request) =>
                  request.method == 'GET' &&
                  request.headers['authorization'] == 'Bearer fixture-token',
            ),
            isTrue,
          );
          expect(
            session.session,
            same(staleSession ? replacement : f.switchSession),
          );
          expect(
            store.savedSession,
            same(staleSession ? replacement : f.switchSession),
          );
          expect(tester.takeException(), isNull);
        },
      );
    }
  }

  testWidgets('empty comparison is explicit', (tester) async {
    final api = PlatformService(
      baseUri: Uri.parse('https://example.test'),
      client: MockClient(
        (r) async => http.Response(
          jsonEncode(r.url.path.endsWith('/diff') ? diff() : detail()),
          200,
        ),
      ),
    );
    final session = SessionController(
      f.SwitchStore(savedSession: f.switchSession),
      f.SwitchPlatform(),
    );
    await session.restore();
    await tester.pumpWidget(
      MaterialApp(
        home: MergeRequestDetailScreen(
          session: session,
          workspace: f.switchController(f.SwitchPlatform(), f.SwitchStore()),
          service: api,
          requestId: 'mr',
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('No hay cambios semánticos.'), findsOneWidget);
  });

  testWidgets('added and removed fields show absent side', (tester) async {
    final changes = [
      {
        'changeKind': 'ADDED',
        'targetType': 'ATTRIBUTE',
        'targetName': 'correo',
        'fields': [
          {'field': 'name', 'before': null, 'after': 'correo'},
        ],
      },
      {
        'changeKind': 'REMOVED',
        'targetType': 'ATTRIBUTE',
        'targetName': 'telefono',
        'fields': [
          {'field': 'name', 'before': 'telefono', 'after': null},
        ],
      },
    ];
    final api = PlatformService(
      baseUri: Uri.parse('https://example.test'),
      client: MockClient(
        (r) async => http.Response(
          jsonEncode(r.url.path.endsWith('/diff') ? diff(changes) : detail()),
          200,
        ),
      ),
    );
    final session = SessionController(
      f.SwitchStore(savedSession: f.switchSession),
      f.SwitchPlatform(),
    );
    await session.restore();
    await tester.pumpWidget(
      MaterialApp(
        home: MergeRequestDetailScreen(
          session: session,
          workspace: f.switchController(f.SwitchPlatform(), f.SwitchStore()),
          service: api,
          requestId: 'mr',
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Antes: —'), findsOneWidget);
    expect(find.text('Después: correo'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Después: —'), 200);
    expect(find.text('Antes: telefono'), findsOneWidget);
    expect(find.text('Después: —'), findsOneWidget);
  });
  testWidgets(
    'shows semantic before and after and partial activity using only read endpoints',
    (tester) async {
      final calls = <http.Request>[];
      final api = PlatformService(
        baseUri: Uri.parse('https://example.test'),
        client: MockClient((request) async {
          calls.add(request);
          if (request.url.path.endsWith('/diff')) {
            return http.Response(
              jsonEncode({
                'changes': [
                  {
                    'changeKind': 'MODIFIED',
                    'targetType': 'CLASSIFIER',
                    'targetName': 'Lector',
                    'ownerName': null,
                    'fields': [
                      {'field': 'name', 'before': 'Lector', 'after': 'Usuario'},
                    ],
                  },
                ],
                'commits': [
                  {
                    'message': 'Renombrar',
                    'authorDisplayName': 'Ana',
                    'createdAt': '2025-01-01T00:00:00Z',
                  },
                ],
                'contributors': [],
                'hasConflicts': false,
              }),
              200,
            );
          }
          return http.Response(
            jsonEncode({
              'title': 'Cambio',
              'state': 'OPEN',
              'sourceBranch': 'feature',
              'targetBranch': 'main',
              'openedByDisplayName': 'Ana',
              'createdAt': '2025-01-01T00:00:00Z',
              'approvals': [
                {'displayName': 'Luis', 'approvedAt': '2025-01-02T00:00:00Z'},
              ],
            }),
            200,
          );
        }),
      );
      final session = SessionController(
        f.SwitchStore(savedSession: f.switchSession),
        f.SwitchPlatform(),
      );
      await session.restore();
      final workspace = f.switchController(f.SwitchPlatform(), f.SwitchStore());
      await tester.pumpWidget(
        MaterialApp(
          home: MergeRequestDetailScreen(
            session: session,
            workspace: workspace,
            service: api,
            requestId: 'mr',
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Cambio'), findsOneWidget);
      expect(find.text('Antes: Lector'), findsOneWidget);
      expect(find.text('Después: Usuario'), findsOneWidget);
      await tester.scrollUntilVisible(find.text('Actividad parcial'), 300);
      expect(find.text('Actividad parcial'), findsOneWidget);
      expect(find.textContaining('Renombrar'), findsOneWidget);
      await tester.scrollUntilVisible(find.textContaining('Luis'), 200);
      expect(find.textContaining('Luis'), findsOneWidget);
      expect(calls.map((r) => r.url.path).toSet(), {
        '/projects/p/vcs/merge-requests/mr',
        '/projects/p/vcs/merge-requests/mr/diff',
      });
      expect(
        calls.every(
          (r) =>
              r.method == 'GET' &&
              r.headers['authorization'] == 'Bearer fixture-token',
        ),
        isTrue,
      );
    },
  );
}
