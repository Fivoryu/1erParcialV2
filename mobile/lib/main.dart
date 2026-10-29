import 'data/chat_store.dart';
import 'presentation/app_theme.dart';
import 'domain/chat_models.dart';
import 'presentation/chat_controller.dart';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'data/mnn_service.dart';
import 'data/litert_agent_service.dart';
import 'data/platform_service.dart';
import 'data/secure_store.dart';
import 'presentation/screens/login_screen.dart';
import 'presentation/screens/workspace_screen.dart';
import 'presentation/session_controller.dart';
import 'presentation/workspace_controller.dart';

const _platformUrl = String.fromEnvironment(
  'PLATFORM_URL',
  defaultValue: 'https://localhost:8080',
);

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final session = SessionController(
    FlutterSecureStore(),
    PlatformService(baseUri: Uri.parse(_platformUrl)),
  );
  final workspace = WorkspaceController(
    service: PlatformService(baseUri: Uri.parse(_platformUrl)),
    store: FlutterSecureStore(),
    mnn: const MethodChannelMnnService(),
    litert: MethodChannelLiteRtAgentService(),
  );
  session.restore();
  runApp(
    DiagrammerApp(
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
  });
  final SessionController session;
  final WorkspaceController workspace;
  final ChatController? chat;
  @override
  State<DiagrammerApp> createState() => _DiagrammerAppState();
}

class _DiagrammerAppState extends State<DiagrammerApp> {
  late final GoRouter _router;
  int? _boundModelEpoch;
  void _sessionChanged() {
    if (!widget.session.authenticated) widget.workspace.clear();
    _bindChat();
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
