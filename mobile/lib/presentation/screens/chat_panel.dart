
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
                  tooltip: 'Conversation history',
                  onPressed: c.busy ? null : c.showHistory,
                  icon: const Icon(Icons.history),
                ),
              Expanded(
                child: Text(
                  c.selected?.title ?? 'Conversations',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              IconButton(
                tooltip: 'Refresh history',
                onPressed: c.busy ? null : c.refresh,
                icon: const Icon(Icons.sync),
              ),
              IconButton(
                tooltip: 'New conversation',
                onPressed: c.busy ? null : c.newChat,
                icon: const Icon(Icons.add),
              ),
            ],
          ),
          if (c.busy) ...[
            const LinearProgressIndicator(),
            TextButton(
              onPressed: c.cancelRequest,
              child: const Text('Stop request'),
            ),
          ],
          for (final event in c.progress)
            Text(
              '${event.elapsed.inSeconds}s • ${event.phase}${event.tool == null ? "" : " • ${event.tool}"}',
            ),
          if (c.deletePreview != null || c.deletePreviews.isNotEmpty)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(c.deletePreviews.isEmpty
                        ? 'Confirm deletion of this exact record'
                        : 'Confirm deletion of these ${c.deletePreviews.length} records'),
                    // Labels only: the confirmation never shows record identifiers.
                    for (final label in c.deleteLabels) Text('• $label'),
                    FilledButton(
                      onPressed: c.busy ? null : c.confirmDelete,
                      child: Text(c.deletePreviews.isEmpty ? 'Confirm delete' : 'Delete all ${c.deletePreviews.length}'),
                    ),
                    TextButton(
                      onPressed: c.dismissDelete,
                      child: const Text('Keep data'),
                    ),
                  ],
                ),
              ),
            ),
          TextButton(
            onPressed: c.busy ? null : c.inspectOperations,
            child: const Text('Inspect pending operations'),
          ),
          if (c.error != null)
            Padding(padding: const EdgeInsets.all(8), child: Text(c.error!)),
          Padding(
            padding: EdgeInsets.symmetric(vertical: 8),
            child: Text(
              c.agentConnected
                  ? 'Device-local AI. Actions and timings appear below. Confirm before deleting. Cached results may be incomplete.'
                  : 'Local history is available. Agent replies are not connected in this view.',
            ),
          ),
          if (c.selected == null) ...[
            if (c.conversations.isEmpty)
              const Text('No conversations yet. Start a new one.'),
            for (final conversation in c.conversations)
              ListTile(
                title: Text(conversation.title),
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
              label: 'Message',
              child: TextField(
                key: const ValueKey('chat-message'),
                controller: _text,
                onChanged: c.setDraft,
                minLines: 1,
                maxLines: 5,
                maxLength: 16000,
                decoration: const InputDecoration(labelText: 'Message'),
              ),
            ),
            FilledButton(
              onPressed: c.busy
                  ? null
                  : () => c.agentConnected
                        ? c.sendMessage(_text.text)
                        : c.saveMessage(_text.text),
              child: Text(c.agentConnected ? 'Send' : 'Save message'),
            ),
          ],
        ],
      );
    },
  );
}
