
import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';

import '../chat_controller.dart';

class ChatPanel extends StatefulWidget {
  const ChatPanel({super.key, required this.controller});
  final ChatController controller;
  @override
  State<ChatPanel> createState() => _ChatPanelState();
}

class _ChatPanelState extends State<ChatPanel> {
  final _text = TextEditingController();
  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final c = widget.controller;
      if (_text.text != c.draft) {
        _text.value = TextEditingValue(
          text: c.draft,
          selection: TextSelection.collapsed(offset: c.draft.length),
        );
      }
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  c.selected == null
                      ? 'Conversaciones'
                      : _displayConversationTitle(c.selected!.title),
                  style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                ),
              ),
              if (c.selected != null)
                Tooltip(
                  message: 'Historial de conversaciones',
                  child: OutlinedButton(
                    onPressed: c.busy ? null : c.showHistory,
                    child: const Text('Historial'),
                  ),
                ),
              IconButton(
                tooltip: 'Actualizar historial',
                onPressed: c.busy ? null : c.refresh,
                icon: const Icon(Icons.sync),
              ),
              IconButton(
                tooltip: 'Nueva conversación',
                onPressed: c.busy ? null : c.newChat,
                icon: const Icon(Icons.add),
              ),
            ],
          ),
          if (c.busy) ...[
            const LinearProgressIndicator(),
            TextButton(
              onPressed: c.cancelRequest,
              child: const Text('Detener solicitud'),
            ),
          ],
          for (final event in c.progress)
            Text(
              '${event.elapsed.inSeconds}s • ${_displayProgressPhase(event.phase)}${event.tool == null ? "" : " • ${event.tool}"}',
            ),
          if (c.writePreview != null)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(c.writeSummary ?? 'Confirma este cambio'),
                    FilledButton(
                      onPressed: c.busy ? null : c.confirmWrite,
                      child: const Text('Confirmar cambio'),
                    ),
                    TextButton(
                      onPressed: c.dismissWrite,
                      child: const Text('Cancelar'),
                    ),
                  ],
                ),
              ),
            ),
          if (c.deletePreview != null || c.deletePreviews.isNotEmpty)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(c.deletePreviews.isEmpty
                        ? 'Confirma la eliminación de este registro exacto'
                        : 'Confirma la eliminación de estos ${c.deletePreviews.length} registros'),
                    // Labels only: the confirmation never shows record identifiers.
                    for (final label in c.deleteLabels) Text('• $label'),
                    FilledButton(
                      onPressed: c.busy ? null : c.confirmDelete,
                      child: Text(c.deletePreviews.isEmpty ? 'Confirmar eliminación' : 'Eliminar los ${c.deletePreviews.length}'),
                    ),
                    TextButton(
                      onPressed: c.dismissDelete,
                      child: const Text('Conservar datos'),
                    ),
                  ],
                ),
              ),
            ),
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: OutlinedButton.icon(
              style: OutlinedButton.styleFrom(
                alignment: Alignment.centerLeft,
                minimumSize: const Size.fromHeight(48),
                backgroundColor: Theme.of(context).colorScheme.surface,
              ),
              onPressed: c.busy ? null : c.inspectOperations,
              icon: const Icon(Icons.arrow_right),
              label: const Text('Revisar operaciones pendientes'),
            ),
          ),
          if (c.error != null)
            Padding(padding: const EdgeInsets.all(8), child: Text(c.error!)),
          Padding(
            padding: EdgeInsets.symmetric(vertical: 8),
            child: Text(
              c.agentConnected
                  ? 'IA local en el dispositivo. Las acciones y los tiempos aparecen abajo. Confirma antes de eliminar. Los resultados en caché podrían estar incompletos.'
                  : 'El historial local está disponible. Las respuestas del agente no están conectadas en esta vista.',
            ),
          ),
          if (c.selected == null) ...[
            if (c.conversations.isEmpty)
              const Text('Aún no hay conversaciones. Inicia una nueva.'),
            for (final conversation in c.conversations)
              ListTile(
                title: Text(_displayConversationTitle(conversation.title)),
                subtitle: Text(conversation.updatedAt.toLocal().toString()),
                onTap: c.busy ? null : () => c.open(conversation),
              ),
          ] else ...[
            for (final message in c.messages) _MessageBubble(message.role, message.content),
            Semantics(
              identifier: 'chat-message',
              label: 'Mensaje',
              child: TextField(
                key: const ValueKey('chat-message'),
                controller: _text,
                onChanged: c.setDraft,
                minLines: 1,
                maxLines: 5,
                maxLength: 16000,
                decoration: const InputDecoration(labelText: 'Mensaje'),
              ),
            ),
            FilledButton(
              onPressed: c.busy
                  ? null
                  : () => c.agentConnected
                        ? c.sendMessage(_text.text)
                        : c.saveMessage(_text.text),
              child: Text(c.agentConnected ? 'Enviar' : 'Guardar mensaje'),
            ),
          ],
        ],
      );
    },
  );
}

/// One turn of the conversation: the person's own message sits right in a soft purple
/// bubble, the agent's answer left in a bordered card, each under its author.
class _MessageBubble extends StatelessWidget {
  const _MessageBubble(this.role, this.content);
  final String role;
  final String content;

  @override
  Widget build(BuildContext context) {
    final mine = role == 'user';
    // Theme roles, not fixed colors: the phone may run the dark theme.
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Column(
        crossAxisAlignment: mine ? CrossAxisAlignment.end : CrossAxisAlignment.start,
        children: [
          Text(
            mine ? 'Tú' : 'Agente local',
            style: Theme.of(context).textTheme.labelMedium?.copyWith(
              color: mine ? scheme.tertiary : scheme.primary,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 6),
          Container(
            margin: EdgeInsets.only(left: mine ? 32 : 0),
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: mine ? scheme.primaryContainer : scheme.surface,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: scheme.outlineVariant),
            ),
            child: mine
                ? SelectableText(content)
                : MarkdownBody(data: content, selectable: true),
          ),
        ],
      ),
    );
  }
}

String _displayConversationTitle(String title) =>
    title == 'New conversation' ? 'Nueva conversación' : title;

String _displayProgressPhase(String phase) => switch (phase) {
  'tool_started' => 'Iniciando operación',
  'tool_finished' => 'Operación finalizada',
  'model_started' => 'Iniciando modelo',
  'model_finished' => 'Modelo finalizado',
  'calculation_finished' => 'Cálculo finalizado',
  'calculation_repeated' => 'Cálculo repetido',
  'finished' => 'Finalizado',
  _ => phase,
};
