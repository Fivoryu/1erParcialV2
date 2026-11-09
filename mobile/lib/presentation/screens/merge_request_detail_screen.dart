import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../data/platform_service.dart';
import '../../domain/models.dart';
import '../session_controller.dart';
import '../workspace_controller.dart';

/// A request belongs to the project and session in which it was opened.
class MergeRequestDetailScreen extends StatefulWidget {
  const MergeRequestDetailScreen({super.key, required this.session, required this.workspace, required this.service, required this.requestId});
  final SessionController session;
  final WorkspaceController workspace;
  final PlatformService service;
  final String requestId;

  @override
  State<MergeRequestDetailScreen> createState() => _MergeRequestDetailScreenState();
}

class _MergeRequestDetailScreenState extends State<MergeRequestDetailScreen> {
  UserSession? _session;
  PlatformProject? _project;
  Map<String, dynamic>? _detail;
  Map<String, dynamic>? _diff;
  String? _error;
  bool _invalidated = false;
  bool _canReview = false;
  bool _canClose = false;
  bool _sending = false;
  String? _actionMessage;

  @override
  void initState() {
    super.initState();
    _session = widget.session.session;
    _project = widget.workspace.project;
    widget.session.addListener(_contextChanged);
    widget.workspace.addListener(_contextChanged);
    if (_session != null && _project != null) _load(_session!, _project!);
  }

  bool _current(UserSession session, PlatformProject project) => mounted && !_invalidated && identical(widget.session.session, session) && identical(widget.workspace.project, project);

  void _contextChanged() {
    if (!identical(widget.session.session, _session) || !identical(widget.workspace.project, _project)) {
      setState(() { _invalidated = true; _detail = null; _diff = null; });
    }
  }

  Future<void> _load(UserSession session, PlatformProject project) async {
    try {
      final detail = await widget.service.mergeRequestDetail(session.token, project.id, widget.requestId);
      if (!_current(session, project)) return;
      if (!['title', 'state', 'sourceBranch', 'targetBranch'].every((key) => detail[key] is String) ||
          detail['approvals'] is! List || !(detail['approvals'] as List).every((item) => item is Map)) {
        throw const FormatException('Detalle inválido');
      }
      final diff = await widget.service.mergeRequestDetail(session.token, project.id, widget.requestId, diff: true);
      if (!_current(session, project)) return;
      if (diff['changes'] is! List || diff['commits'] is! List ||
          !(diff['commits'] as List).every((item) => item is Map) ||
          !(diff['changes'] as List).every((item) => item is Map && item['changeKind'] is String && item['targetType'] is String && item['fields'] is List && (item['fields'] as List).every((field) => field is Map && field['field'] is String))) {
        throw const FormatException('Comparación inválida');
      }
      var canReview = false;
      var canClose = false;
      if (detail['openedByUserId'] is String) {
        try {
          final permissions = await widget.service.vcsPermissions(session.token, project.id);
          if (!_current(session, project)) return;
          for (final permission in permissions) {
            if (permission is Map && permission['userId'] == session.user.id && permission['actions'] is List) {
              final actions = permission['actions'] as List;
              canReview = actions.contains('BRANCH') && detail['openedByUserId'] != session.user.id;
              canClose = actions.contains('MERGE');
            }
          }
        } on PlatformException catch (e) {
          if (!_current(session, project)) return;
          if (e.sessionExpired) { await widget.session.expireIfCurrent(session); return; }
        } catch (_) { /* Fail closed if permissions cannot be read. */ }
      }
      if (!_current(session, project)) return;
      setState(() { _detail = detail; _diff = diff; _canReview = canReview; _canClose = canClose; });
    } on PlatformException catch (e) {
      if (!_current(session, project)) return;
      if (e.sessionExpired) {
        await widget.session.expireIfCurrent(session);
      } else {
        setState(() => _error = e.message);
      }
    } catch (_) {
      if (_current(session, project)) setState(() => _error = 'No se pudo cargar la solicitud.');
    }
  }

  Future<void> _review() async {
    var message = '';
    final confirmed = await showDialog<bool>(context: context, builder: (context) => AlertDialog(
      title: const Text('Pedir cambios'),
      content: TextField(maxLength: 2000, maxLines: 4, onChanged: (value) => message = value,
        decoration: const InputDecoration(labelText: 'Cambios solicitados')),
      actions: [TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancelar')),
        TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Enviar revisión'))],
    ));
    if (confirmed != true || !mounted || _session == null || _project == null || !_current(_session!, _project!)) return;
    if (message.trim().isEmpty) { setState(() => _actionMessage = 'Escribe los cambios solicitados.'); return; }
    await _act('request-changes', message: message.trim());
  }

  Future<void> _close() async {
    final confirmed = await showDialog<bool>(context: context, builder: (context) => AlertDialog(
      title: const Text('Cerrar sin fusionar'), content: const Text('¿Cerrar esta solicitud sin fusionarla?'),
      actions: [TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancelar')),
        TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Cerrar solicitud'))],
    ));
    if (confirmed == true && mounted && _session != null && _project != null && _current(_session!, _project!)) await _act('close');
  }

  Future<void> _act(String action, {String? message}) async {
    final session = _session!; final project = _project!;
    if (_sending || !_current(session, project)) return;
    setState(() { _sending = true; _actionMessage = null; });
    try {
      final result = await widget.service.reviewAction(session.token, project.id, widget.requestId, action, message: message);
      if (!_current(session, project)) return;
      if (!['title', 'state', 'sourceBranch', 'targetBranch'].every((key) => result[key] is String) ||
          result['approvals'] is! List || !(result['approvals'] as List).every((item) => item is Map) ||
          result['state'] != (action == 'close' ? 'CLOSED' : 'OPEN')) {
        setState(() { _canReview = false; _canClose = false; });
        throw const FormatException('Resultado inválido');
      }
      setState(() {
        _detail = result;
        _actionMessage = action == 'request-changes' ? 'Revisión registrada; notificación pendiente de implementación.' : 'Solicitud cerrada sin fusionar.';
      });
    } on PlatformException catch (e) {
      if (!_current(session, project)) return;
      if (e.sessionExpired) { await widget.session.expireIfCurrent(session); }
      else { setState(() => _actionMessage = e.message); }
    } catch (_) {
      if (_current(session, project)) setState(() => _actionMessage = 'No se pudo confirmar el resultado. Revisa la solicitud antes de reintentar.');
    } finally {
      if (_current(session, project)) setState(() => _sending = false);
    }
  }

  @override
  void dispose() {
    widget.session.removeListener(_contextChanged);
    widget.workspace.removeListener(_contextChanged);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final detail = _detail;
    final diff = _diff;
    return Scaffold(
      appBar: AppBar(title: const Text('Cambios del modelo'), leading: IconButton(tooltip: 'Volver', icon: const BackButtonIcon(), onPressed: () => context.canPop() ? context.pop() : context.go('/merge-requests'))),
      body: SafeArea(child: _invalidated || _session == null || _project == null
        ? const Center(child: Text('El proyecto o la sesión cambiaron. Vuelve a la lista.'))
        : _error != null ? Center(child: Text(_error!))
        : detail == null || diff == null ? const Center(child: CircularProgressIndicator())
        : ListView(padding: const EdgeInsets.all(20), children: [
          Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${detail['state'] ?? ''}', style: Theme.of(context).textTheme.labelLarge),
            Text('${detail['title'] ?? ''}', style: Theme.of(context).textTheme.titleLarge),
            Text('${detail['sourceBranch'] ?? ''} → ${detail['targetBranch'] ?? ''}'),
            if (diff['hasConflicts'] == true) const Text('Hay conflictos'),
          ]))),
          if (detail['state'] == 'OPEN') Wrap(spacing: 8, children: [
            if (_canReview) OutlinedButton(onPressed: _sending ? null : _review, child: const Text('Pedir cambios')),
            if (_canClose) OutlinedButton(onPressed: _sending ? null : _close, child: const Text('Cerrar sin fusionar')),
          ]),
          if (_actionMessage != null) Text(_actionMessage!),
          const SizedBox(height: 16),
          Text('Resumen semántico', style: Theme.of(context).textTheme.titleLarge),
          const Text('Solo se muestran los campos modificados, no diagramas completos.'),
          if ((diff['changes'] as List).isEmpty) const Text('No hay cambios semánticos.'),
          for (final item in diff['changes'] as List) Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${item['changeKind']} · ${item['targetType']} · ${item['targetName'] ?? ''}', style: Theme.of(context).textTheme.titleMedium),
            if (item['ownerName'] != null) Text('${item['ownerName']}'),
            for (final field in item['fields'] as List) Padding(padding: const EdgeInsets.only(top: 8), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${field['field']}', style: Theme.of(context).textTheme.labelLarge),
              Text('Antes: ${field['before'] ?? '—'}'),
              Text('Después: ${field['after'] ?? '—'}'),
            ])),
          ]))),
          const SizedBox(height: 16),
          Text('Actividad parcial', style: Theme.of(context).textTheme.titleLarge),
          const Text('Datos disponibles de la solicitud y sus commits; no es una línea de tiempo completa.'),
          if (detail['openedByDisplayName'] != null) ListTile(title: Text('Solicitud abierta por ${detail['openedByDisplayName']}'), subtitle: Text('${detail['createdAt'] ?? ''}')),
          for (final commit in diff['commits'] as List) ListTile(title: Text('Commit: ${commit['message'] ?? ''}'), subtitle: Text('${commit['authorDisplayName'] ?? ''} · ${commit['createdAt'] ?? ''}')),
          for (final approval in detail['approvals'] as List) ListTile(title: Text('Aprobación: ${approval['displayName'] ?? ''}'), subtitle: Text('${approval['approvedAt'] ?? ''}')),
          if (detail['reviews'] is List) for (final review in detail['reviews'] as List) if (review is Map) ListTile(title: Text('Cambios solicitados: ${review['displayName'] ?? ''}'), subtitle: Text('${review['message'] ?? ''}')),
        ])),
    );
  }
}
