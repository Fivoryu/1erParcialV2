import 'data/chat_store.dart';
import 'presentation/app_theme.dart';
import 'domain/chat_models.dart';
import 'presentation/chat_controller.dart';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'data/mnn_service.dart';
import 'data/litert_agent_service.dart';
import 'data/platform_service.dart';
import 'data/push_registrar.dart';
import 'data/secure_store.dart';
import 'presentation/screens/login_screen.dart';
import 'presentation/screens/merge_requests_screen.dart';
import 'presentation/screens/merge_request_detail_screen.dart';
import 'presentation/screens/workspace_screen.dart';
import 'presentation/session_controller.dart';
import 'presentation/workspace_controller.dart';

const _platformUrl = String.fromEnvironment(
  'PLATFORM_URL',
  defaultValue: 'https://localhost:8080',
);

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final platform = PlatformService(baseUri: Uri.parse(_platformUrl));
  final push = PushRegistrar(FirebasePushTokenSource(), platform);
  final session = SessionController(FlutterSecureStore(), platform, push: push);
  final workspace = WorkspaceController(
    service: PlatformService(baseUri: Uri.parse(_platformUrl)),
    store: FlutterSecureStore(),
    mnn: const MethodChannelMnnService(),
    litert: MethodChannelLiteRtAgentService(),
  );
  session.restore();
  runApp(
    DiagrammerApp(
      push: push,
      session: session,
      workspace: workspace,
      chat: ChatController(
        store: MethodChannelChatStore(),
        model: workspace.generateAgent,
        cancelModel: workspace.cancelModelGeneration,
        service: PlatformService(baseUri: Uri.parse(_platformUrl)),
      ),
    ),
  );
}

class DiagrammerApp extends StatefulWidget {
  const DiagrammerApp({
    super.key,
    required this.session,
    required this.workspace,
    this.chat,
    this.platformService,
    this.push,
  });
  final PushRegistrar? push;
  final PlatformService? platformService;
  final SessionController session;
  final WorkspaceController workspace;
  final ChatController? chat;
  @override
  State<DiagrammerApp> createState() => _DiagrammerAppState();
}

class _DiagrammerAppState extends State<DiagrammerApp> {
  late final GoRouter _router;
  int? _boundModelEpoch;
  Map<String, String>? _pendingPush;

  void _sessionChanged() {
    if (!widget.session.authenticated) widget.workspace.clear();
    _bindChat();
    _drainPush();
  }

  /// Opens the merge request named by a tapped notification once the session is ready.
  void _pushOpened(Map<String, String> data) {
    if (!['REQUEST_OPENED', 'REVIEW_CHANGES'].contains(data['type'])) return;
    if ((data['projectId'] ?? '').isEmpty || (data['mergeRequestId'] ?? '').isEmpty) return;
    _pendingPush = data;
    _drainPush();
  }

  Future<void> _drainPush() async {
    final data = _pendingPush;
    if (data == null || !widget.session.ready) return;
    final session = widget.session.session;
    if (session == null || session.isExpired) {
      _pendingPush = null;
      return;
    }
    _pendingPush = null;
    try {
      final workspace = widget.workspace;
      if (workspace.project?.id != data['projectId']) {
        if (workspace.projects.isEmpty) await workspace.loadProjects(session);
        final matches = workspace.projects.where((p) => p.id == data['projectId']);
        if (matches.isEmpty) return;
        await workspace.selectProject(session, matches.first);
      }
      if (workspace.project?.id != data['projectId']) return;
      _router.go('/merge-requests/${Uri.encodeComponent(data['mergeRequestId']!)}');
    } catch (_) {}
  }

  void _bindChat() {
    final session = widget.session.session;
    final workspace = widget.workspace;
    if (_boundModelEpoch != workspace.modelEpoch) {
      _boundModelEpoch = workspace.modelEpoch;
      widget.chat?.invalidateModel();
    }
    final scope =
        session == null ||
            workspace.project == null ||
            workspace.backend == null ||
            workspace.contract == null
        ? null
        : ChatScope(
            platform: _platformUrl,
            userId: session.user.id,
            projectId: workspace.project!.id,
            deploymentId: workspace.backend!.deploymentId,
            contractHash: workspace.contract!.contractHash,
          );
    widget.chat?.bind(scope, session?.token, contract: workspace.contract);
  }

  @override
  void initState() {
    super.initState();
    widget.session.addListener(_sessionChanged);
    widget.workspace.addListener(_bindChat);
    _bindChat();
    widget.push?.listenForOpens(_pushOpened);
    _router = GoRouter(
      initialLocation: '/backends',
      refreshListenable: Listenable.merge([widget.session, widget.workspace]),
      redirect: (context, state) {
        if (!widget.session.ready) return '/loading';
        if (!widget.session.authenticated) {
          return state.matchedLocation == '/login' ? null : '/login';
        }
        if ([
          '/login',
          '/loading',
          '/workspace',
        ].contains(state.matchedLocation)) {
          return '/backends';
        }
        if (state.matchedLocation.startsWith('/merge-requests') &&
            widget.workspace.project == null) {
          return '/backends';
        }
        if (state.matchedLocation == '/agent' &&
            widget.workspace.contract == null) {
          return '/backends';
        }
        return null;
      },
      routes: [
        GoRoute(
          path: '/loading',
          builder: (_, _) =>
              const Scaffold(body: Center(child: CircularProgressIndicator())),
        ),
        GoRoute(
          path: '/login',
          builder: (_, _) => LoginScreen(controller: widget.session),
        ),
        GoRoute(path: '/workspace', redirect: (_, _) => '/backends'),
        GoRoute(
          path: '/merge-requests',
          builder: (_, _) => MergeRequestsScreen(
            session: widget.session,
            workspace: widget.workspace,
            service: widget.platformService ?? PlatformService(baseUri: Uri.parse(_platformUrl)),
          ),
          routes: [GoRoute(
            path: ':id',
            builder: (_, state) => MergeRequestDetailScreen(
              session: widget.session,
              workspace: widget.workspace,
              service: widget.platformService ?? PlatformService(baseUri: Uri.parse(_platformUrl)),
              requestId: state.pathParameters['id']!,
            ),
          )],
        ),
        for (final entry in const {
          '/backends': WorkspacePage.backends,
          '/agent': WorkspacePage.agent,
          '/settings': WorkspacePage.settings,
        }.entries)
          GoRoute(
            path: entry.key,
            builder: (_, _) => WorkspaceScreen(
              session: widget.session,
              controller: widget.workspace,
              page: entry.value,
              chat: widget.chat,
            ),
          ),
      ],
    );
  }

  @override
  void dispose() {
    widget.session.removeListener(_sessionChanged);
    widget.workspace.removeListener(_bindChat);
    _router.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp.router(
    title: 'Diagrammer Mobile',
    theme: buildLightTheme(),
    darkTheme: buildDarkTheme(),
    routerConfig: _router,
  );
}
