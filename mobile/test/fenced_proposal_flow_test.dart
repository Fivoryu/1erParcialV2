import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/domain/operation_proposal.dart';
import 'package:diagrammer_mobile/presentation/workspace_controller.dart';
import 'package:flutter_test/flutter_test.dart';

import 'workspace_flow_test.dart' as f;

class OutputMnn extends f.FakeMnn {
  OutputMnn(this.output);
  final String output;
  int calls = 0;
  @override
  Future<String> generate({required String prompt, int maxTokens = 512}) async {
    calls++;
    return output;
  }
}

void main() {
  const raw =
      '{"method":"POST","path":"/api/notes","body":{"title":"USB offline test"}}';
  const fence = '```json\n$raw\n```';
  final contract = BackendContract(
    deploymentId: 'd',
    runId: 'r',
    contractHash: 'hash',
    openApi: {
      'paths': {
        '/api/notes': {
          'post': {
            'requestBody': {
              'content': {
                'application/json': {
                  'schema': {
                    'type': 'object',
                    'required': ['title'],
                    'properties': {
                      'title': {'type': 'string'},
                    },
                  },
                },
              },
            },
          },
        },
      },
    },
  );
  test('fenced offline output is canonical pending and sends only after confirmation', () async {
    final api = f.FakePlatform();
    final store = f.MemoryStore();
    final mnn = OutputMnn(fence);
    final c = WorkspaceController(selectedRuntime: LocalRuntime.qwen, service: api, store: store, mnn: mnn)
      ..project = f.project
      ..backend = f.backend
      ..contract = contract;
    await c.propose(f.session, 'Create a note');
    expect(c.error, isNull);
    expect(c.proposal!.encode(), raw);
    expect(store.pending.values.single, raw);
    expect(api.writes, 0);
    expect(mnn.calls, 1);
    await c.confirm(f.session);
    expect(api.writes, 1);
  });
  test('invalid fenced output preserves prior pending data and makes zero gateway calls', () async {
    final rejected = [
      'Explanation\n$fence',
      '$fence\nDone',
      '$fence\n$fence',
      '```json\n{"method":POST}\n```',
      '```json\n[$raw]\n```',
      '```json\n{"method":123,"path":"/api/notes"}\n```',
      '```json\n{"method":"POST","path":"/api/notes","extra":true}\n```',
      '```json\n${' ' * 32769}$raw\n```',
      '```json\n{"method":"POST","path":"/other","body":{"title":"x"}}\n```',
      '```json\n{"method":"DELETE","path":"/api/notes"}\n```',
      '```json\n{"method":"POST","path":"/api/notes","body":{"title":123}}\n```',
    ];
    for (final output in rejected) {
      final api = f.FakePlatform();
      final store = f.MemoryStore()..pending['u.p.d.hash'] = raw;
      final before = Map<String, String>.from(store.pending);
      final mnn = OutputMnn(output);
      final c = WorkspaceController(selectedRuntime: LocalRuntime.qwen, service: api, store: store, mnn: mnn)
        ..project = f.project
        ..backend = f.backend
        ..contract = contract
        ..setProposal(OperationProposal.parse(raw));
      await c.propose(f.session, 'Create a note');
      expect(c.error, contains('propuesta no válida'));
      expect(c.proposal!.encode(), raw);
      expect(store.pending, before);
      expect(api.writes, 0);
      expect(mnn.calls, 2);
    }
  });
}
