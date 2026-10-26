
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
              if (c.selected != null)
                IconButton(
                  tooltip: 'Historial de conversaciones',
                  onPressed: c.busy ? null : c.showHistory,
                  icon: const Icon(Icons.history),
                ),
              Expanded(
                child: Text(
                  c.selected == null
                      ? 'Conversaciones'
                      : _displayConversationTitle(c.selected!.title),
                  style: Theme.of(context).textTheme.titleLarge,
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
          TextButton(
            onPressed: c.busy ? null : c.inspectOperations,
            child: const Text('Revisar operaciones pendientes'),
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
            for (final message in c.messages)
              Align(
                alignment: message.role == 'user'
                    ? Alignment.centerRight
                    : Alignment.centerLeft,
                child: Card(
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: message.role == 'assistant'
                        ? MarkdownBody(data: message.content, selectable: true)
                        : SelectableText(message.content),
                  ),
                ),
              ),
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
