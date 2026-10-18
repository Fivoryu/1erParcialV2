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
        WorkspacePage.backends => 'Backends',
        WorkspacePage.agent => 'Agent',
        WorkspacePage.settings => 'Settings',
      }),
      leading: widget.page == WorkspacePage.backends
          ? null
          : BackButton(
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
            tooltip: 'Settings',
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
                      : ChatPanel(controller: widget.chat!),
                WorkspacePage.settings => _settingsCard(),
              },
            ],
          ),
        ),
      ),
    ),
  );

  Widget _settingsCard() => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final c = widget.controller;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Local AI', style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 8),
          const Text(
            'Choose an installed on-device model. No download or cloud fallback.',
          ),
          const SizedBox(height: 20),
          DropdownButtonFormField<LocalRuntime>(
            key: const ValueKey('local-runtime-selector'),
            initialValue: c.selectedRuntime,
            isExpanded: true,
            decoration: const InputDecoration(labelText: 'On-device model'),
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
                  ? 'Loading AI…'
                  : c.mnnStatus.isLoaded
                  ? 'AI ready'
                  : 'Load AI',
            ),
          ),
          if (c.detectedModelDirectory == null && !c.isModelDiscovering)
            TextButton(
              onPressed: c.discoverModel,
              child: const Text('Check again'),
            ),
          const SizedBox(height: 32),
          Text('Account', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 8),
          Text(widget.session.session?.user.displayName ?? ''),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: widget.session.logout,
            icon: const Icon(Icons.logout),
            label: const Text('Sign out'),
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
              Text('Connection', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 12),
              DropdownButtonFormField<PlatformProject>(
                key: ValueKey('project:${c.project?.id}'),
                initialValue: c.project,
                decoration: const InputDecoration(
                  labelText: 'Platform project',
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
              const SizedBox(height: 16),
              DropdownButtonFormField<GeneratedBackend>(
                key: ValueKey(
                  'backend:${c.project?.id}:${c.backend?.deploymentId}',
                ),
                initialValue: c.backend,
                decoration: const InputDecoration(
                  labelText: 'Generated backend',
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
                label: const Text('Leave backend'),
              ),
              if (c.contract != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text('Contract ${c.contract!.contractHash}'),
                ),
              const SizedBox(height: 24),
              FilledButton.icon(
                onPressed: c.contract == null
                    ? null
                    : () => context.push('/agent'),
                icon: const Icon(Icons.chat_bubble_outline),
                label: const Text('Open agent'),
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
              Text(c.backend?.name ?? 'No backend selected'),
              const SizedBox(height: 8),
              Text(
                'Propose an operation',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 8),
              const Text(
                'The local model only proposes. Nothing is sent until you confirm.',
              ),
              const SizedBox(height: 12),
              Semantics(
                key: const ValueKey('operation-request'),
                identifier: 'operation-request',
                label: 'Describe the data operation',
                child: TextField(
                  controller: _prompt,
                  onChanged: (value) => c.requestDraft = value,
                  maxLength: 2048,
                  minLines: 3,
                  maxLines: 6,
                  decoration: const InputDecoration(
                    labelText: 'Describe the data operation',
                  ),
                ),
              ),
              if (!c.mnnStatus.isLoaded) ...[
                const SizedBox(height: 8),
                TextButton(
                  onPressed: () => context.push('/settings'),
                  child: const Text('Set up local AI'),
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
                child: const Text('Generate proposal'),
              ),
              if (c.proposal != null) ...[
                const SizedBox(height: 20),
                Text(
                  'Review proposal',
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
                        child: const Text('Discard'),
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
                          c.isConfirming ? 'Sending…' : 'Confirm and send',
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
                    child: const Text('Inspect current data before retrying'),
                  ),
                ),
              if (c.latestResult != null) ...[
                const SizedBox(height: 16),
                Text(
                  'Gateway result',
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
