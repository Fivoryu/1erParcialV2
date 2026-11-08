import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../data/platform_service.dart';
import '../../domain/models.dart';
import '../session_controller.dart';
import '../workspace_controller.dart';

class MergeRequestsScreen extends StatefulWidget {
  const MergeRequestsScreen({super.key, required this.session, required this.workspace, required this.service});
  final SessionController session;
  final WorkspaceController workspace;
  final PlatformService service;

  @override
  State<MergeRequestsScreen> createState() => _MergeRequestsScreenState();
}

class _MergeRequestsScreenState extends State<MergeRequestsScreen> {
  UserSession? _session;
  PlatformProject? _project;
  List<MergeRequestSummary>? _requests;
  String? _error;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    widget.session.addListener(_contextChanged);
    widget.workspace.addListener(_contextChanged);
    _contextChanged();
  }

  void _contextChanged() {
    final session = widget.session.session;
    final project = widget.workspace.project;
    if (identical(session, _session) && identical(project, _project)) return;
    _session = session;
    _project = project;
    final generation = ++_generation;
    _requests = null;
    _error = null;
    if (mounted) setState(() {});
    if (session != null && project != null) _load(session, project, generation);
  }

  bool _current(UserSession session, PlatformProject project, int generation) =>
      mounted && generation == _generation &&
      identical(widget.session.session, session) &&
      identical(widget.workspace.project, project);

  Future<void> _load(UserSession session, PlatformProject project, int generation) async {
    try {
      final requests = await widget.service.mergeRequests(session.token, project.id);
      if (!_current(session, project, generation)) return;
      setState(() => _requests = requests);
    } on PlatformException catch (e) {
      if (!_current(session, project, generation)) return;
      if (e.sessionExpired) {
        await widget.session.expireIfCurrent(session);
      } else {
        setState(() => _error = e.message);
      }
    } catch (_) {
      if (!_current(session, project, generation)) return;
      setState(() => _error = 'No se pudieron cargar las solicitudes.');
    }
  }

  @override
  void dispose() {
    ++_generation;
    widget.session.removeListener(_contextChanged);
    widget.workspace.removeListener(_contextChanged);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Solicitudes de fusión'), leading: IconButton(
      tooltip: 'Volver', icon: const BackButtonIcon(),
      onPressed: () => context.canPop() ? context.pop() : context.go('/backends'),
    )),
    body: SafeArea(child: ListView(padding: const EdgeInsets.all(20), children: [
      Text(_project?.name ?? 'Selecciona un proyecto', style: Theme.of(context).textTheme.titleLarge),
      const SizedBox(height: 16),
      if (_error != null) Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error))
      else if (_project == null || _session == null) const SizedBox.shrink()
      else if (_requests == null) const Center(child: CircularProgressIndicator())
      else if (_requests!.isEmpty) const Text('No hay solicitudes de fusión.')
      else for (final request in _requests!) Card(child: ListTile(
        title: Text(request.title),
        subtitle: Text('${request.sourceBranch} → ${request.targetBranch} · ${request.state}'),
      )),
    ])),
  );
}
