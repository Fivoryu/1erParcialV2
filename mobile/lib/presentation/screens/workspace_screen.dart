import '../chat_controller.dart';
import 'chat_panel.dart';

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../domain/models.dart';
import '../session_controller.dart';
import '../workspace_controller.dart';

enum WorkspacePage { backends, agent, settings }

class WorkspaceScreen extends StatefulWidget {
  const WorkspaceScreen({
    super.key,
    required this.session,
    required this.controller,
    this.page = WorkspacePage.backends,
    this.chat,
  });
  final SessionController session;
  final WorkspaceController controller;
  final WorkspacePage page;
  final ChatController? chat;
  @override
  State<WorkspaceScreen> createState() => _WorkspaceScreenState();
}

class _WorkspaceScreenState extends State<WorkspaceScreen> {
  late final TextEditingController _prompt;
  @override
  void initState() {
    super.initState();
    _prompt = TextEditingController(text: widget.controller.requestDraft);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (widget.page == WorkspacePage.backends &&
          widget.controller.projects.isEmpty) {
        _load();
      }
      if (widget.page == WorkspacePage.settings) {
        widget.controller.discoverModel();
      }
    });
  }

  Future<void> _load() async {
    final s = widget.session.session;
    if (s == null) return;
    try {
      await widget.controller.loadProjects(s);
    } catch (_) {
      await widget.session.expireIfCurrent(s);
    }
  }

  @override
  void dispose() {
    _prompt.dispose();

    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(switch (widget.page) {
        WorkspacePage.backends => 'Proyectos',
        WorkspacePage.agent => 'Agente',
        WorkspacePage.settings => 'Configuración',
      }),
      leading: widget.page == WorkspacePage.backends
          ? null
          : IconButton(
              tooltip: 'Volver',
              icon: const BackButtonIcon(),
              onPressed: () {
                if (context.canPop()) {
                  context.pop();
                } else {
                  context.go('/backends');
                }
              },
            ),
      actions: [
        if (widget.page != WorkspacePage.settings)
          IconButton(
            tooltip: 'Configuración',
            icon: const Icon(Icons.settings_outlined),
            onPressed: () => context.push('/settings'),
          ),
      ],
    ),
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 680),
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              switch (widget.page) {
                WorkspacePage.backends => _selectionCard(),
                WorkspacePage.agent =>
                  widget.chat == null
                      ? _agentCard()
                      : Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            _backendContext(),
                            const SizedBox(height: 16),
                            ChatPanel(controller: widget.chat!),
                          ],
                        ),
                WorkspacePage.settings => _settingsCard(),
              },
            ],
          ),
        ),
      ),
    ),
  );

  /// The backend the agent talks to, always in view, with the way back to pick another one.
  Widget _backendContext() => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final c = widget.controller;
      final name = [c.project?.name, c.backend?.name].whereType<String>().join(' · ');
      final scheme = Theme.of(context).colorScheme;
      return Container(
        padding: const EdgeInsets.fromLTRB(14, 12, 10, 12),
        decoration: BoxDecoration(
          color: scheme.secondaryContainer,
          borderRadius: BorderRadius.circular(9),
        ),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'BACKEND SELECCIONADO',
                    style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: scheme.onSecondaryContainer,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0.4,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    name.isEmpty ? 'Ningún backend elegido' : name,
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      color: scheme.onSurface,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ],
              ),
            ),
            OutlinedButton(
              style: OutlinedButton.styleFrom(backgroundColor: scheme.surface),
              onPressed: () => context.go('/backends'),
              child: const Text('Cambiar'),
            ),
          ],
        ),
      );
    },
  );

  Widget _settingsCard() => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final c = widget.controller;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('IA local', style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 8),
          const Text(
            'Elige un modelo instalado en el dispositivo. No se descargará ni se usará un servicio en la nube como alternativa.',
          ),
          const SizedBox(height: 20),
          DropdownButtonFormField<LocalRuntime>(
            key: const ValueKey('local-runtime-selector'),
            initialValue: c.selectedRuntime,
            isExpanded: true,
            decoration: const InputDecoration(labelText: 'Modelo instalado'),
            items: const [
              DropdownMenuItem(
                value: LocalRuntime.qwen,
                child: Text('Qwen (MNN)'),
              ),
              DropdownMenuItem(
                value: LocalRuntime.gemma,
                child: Text('Gemma (LiteRT)'),
              ),
            ],
            onChanged: c.isModelLoading || c.isModelDiscovering
                ? null
                : (runtime) {
                    if (runtime != null) c.selectRuntime(runtime);
                  },
          ),
          const SizedBox(height: 16),
          Text(c.mnnStatus.message),
          const SizedBox(height: 16),
          if (c.isModelDiscovering || c.isModelLoading)
            const LinearProgressIndicator(),
          FilledButton.icon(
            onPressed:
                c.detectedModelDirectory == null ||
                    c.isModelLoading ||
                    c.isModelDiscovering ||
                    c.mnnStatus.isLoaded
                ? null
                : c.loadDetectedModel,
            icon: const Icon(Icons.memory_outlined),
            label: Text(
              c.isModelLoading
                  ? 'Cargando IA…'
                  : c.mnnStatus.isLoaded
                  ? 'IA lista'
                  : 'Cargar IA',
            ),
          ),
          if (c.detectedModelDirectory == null && !c.isModelDiscovering)
            TextButton(
              onPressed: c.discoverModel,
              child: const Text('Volver a comprobar'),
            ),
          const SizedBox(height: 32),
          Text('Cuenta', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 8),
          Text(widget.session.session?.user.displayName ?? ''),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: widget.session.logout,
            icon: const Icon(Icons.logout),
            label: const Text('Cerrar sesión'),
          ),
        ],
      );
    },
  );

  Widget _selectionCard() => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final c = widget.controller;
      final session = widget.session.session;
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('Conexión', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 12),
              DropdownButtonFormField<PlatformProject>(
                key: ValueKey('project:${c.project?.id}'),
                initialValue: c.project,
                decoration: const InputDecoration(
                  labelText: 'Proyecto de la plataforma',
                ),
                items: c.projects
                    .map((p) => DropdownMenuItem(value: p, child: Text(p.name)))
                    .toList(),
                onChanged: session == null
                    ? null
                    : (p) async {
                        if (p == null) return;
                        try {
                          _prompt.clear();
                          await c.selectProject(session, p);
                        } catch (_) {
                          await widget.session.expireIfCurrent(session);
                        }
                      },
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: session == null || c.project == null
                    ? null
                    : () => context.push('/merge-requests'),
                icon: const Icon(Icons.merge_type),
                label: const Text('Solicitudes de fusión'),
              ),
              const SizedBox(height: 16),
              DropdownButtonFormField<GeneratedBackend>(
                key: ValueKey(
                  'backend:${c.project?.id}:${c.backend?.deploymentId}',
                ),
                initialValue: c.backend,
                decoration: const InputDecoration(
                  labelText: 'Backend generado',
                ),
                items: c.backends
                    .map(
                      (b) => DropdownMenuItem(
                        value: b,
                        child: Text('${b.name} · ${b.state}'),
                      ),
                    )
                    .toList(),
                onChanged: session == null
                    ? null
                    : (b) async {
                        if (b == null) return;
                        try {
                          _prompt.clear();
                          await c.selectBackend(session, b);
                        } catch (_) {
                          await widget.session.expireIfCurrent(session);
                        }
                      },
              ),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                onPressed: c.backend == null
                    ? null
                    : () {
                        c.leaveBackend();
                        _prompt.clear();
                      },
                icon: const Icon(Icons.link_off),
                label: const Text('Desconectar backend'),
              ),
              if (c.contract != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text('Contrato ${c.contract!.contractHash}'),
                ),
              const SizedBox(height: 24),
              FilledButton.icon(
                onPressed: c.contract == null
                    ? null
                    : () => context.push('/agent'),
                icon: const Icon(Icons.chat_bubble_outline),
                label: const Text('Abrir agente'),
              ),
              if (c.error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(
                    c.error!,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ),
            ],
          ),
        ),
      );
    },
  );
  Widget _agentCard() => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final c = widget.controller;
      final session = widget.session.session;
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(c.backend?.name ?? 'No hay ningún backend seleccionado'),
              const SizedBox(height: 8),
              Text(
                'Proponer una operación',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 8),
              const Text(
                'El modelo local solo propone operaciones. No se enviará nada hasta que lo confirmes.',
              ),
              const SizedBox(height: 12),
              Semantics(
                key: const ValueKey('operation-request'),
                identifier: 'operation-request',
                label: 'Describe la operación con los datos',
                child: TextField(
                  controller: _prompt,
                  onChanged: (value) => c.requestDraft = value,
                  maxLength: 2048,
                  minLines: 3,
                  maxLines: 6,
                  decoration: const InputDecoration(
                    labelText: 'Describe la operación con los datos',
                  ),
                ),
              ),
              if (!c.mnnStatus.isLoaded) ...[
                const SizedBox(height: 8),
                TextButton(
                  onPressed: () => context.push('/settings'),
                  child: const Text('Configurar IA local'),
                ),
              ],
              if (c.error != null)
                Text(
                  c.error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              FilledButton(
                onPressed:
                    session == null ||
                        c.contract == null ||
                        c.isConfirming ||
                        !c.mnnStatus.isLoaded ||
                        c.isModelLoading
                    ? null
                    : () => c.propose(session, _prompt.text),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(48),
                ),
                child: const Text('Generar propuesta'),
              ),
              if (c.proposal != null) ...[
                const SizedBox(height: 20),
                Text(
                  'Revisar propuesta',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                SelectableText(
                  const JsonEncoder.withIndent('  ')
                      .convert(c.proposal!.toJson()),
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        onPressed: session == null
                            ? null
                            : () => c.discardProposal(session.user.id),
                        style: OutlinedButton.styleFrom(
                          minimumSize: const Size.fromHeight(48),
                        ),
                        child: const Text('Descartar'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: FilledButton(
                        onPressed: session == null || c.isConfirming
                            ? null
                            : () async {
                                try {
                                  await c.confirm(session);
                                } catch (_) {
                                  await widget.session.expireIfCurrent(session);
                                }
                              },
                        style: FilledButton.styleFrom(
                          minimumSize: const Size.fromHeight(48),
                        ),
                        child: Text(
                          c.isConfirming ? 'Enviando…' : 'Confirmar y enviar',
                        ),
                      ),
                    ),
                  ],
                ),
              ],
              if (c.requiresInspection)
                Padding(
                  padding: const EdgeInsets.only(top: 16),
                  child: OutlinedButton(
                    onPressed: session == null
                        ? null
                        : () => c.inspect(session),
                    style: OutlinedButton.styleFrom(
                      minimumSize: const Size.fromHeight(48),
                    ),
                    child: const Text('Revisar los datos actuales antes de reintentar'),
                  ),
                ),
              if (c.latestResult != null) ...[
                const SizedBox(height: 16),
                Text(
                  'Resultado de la puerta de enlace',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                SelectableText(
                  const JsonEncoder.withIndent('  ')
                      .convert(c.latestResult!.body),
                ),
              ],
            ],
          ),
        ),
      );
    },
  );
}
