import 'dart:async';

import 'package:diagrammer_mobile/data/mnn_service.dart';
import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/data/secure_store.dart';
import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/domain/operation_proposal.dart';
import 'package:diagrammer_mobile/presentation/workspace_controller.dart';
import 'package:flutter_test/flutter_test.dart';

class MemoryStore implements SecureStore {
  final pending = <String, String>{};
  @override
  Future<void> clearAll() async {}
  @override
  Future<UserSession?> readSession() async => null;
  @override
  Future<String?> readPending(String scope) async => pending[scope];
  @override
  Future<void> savePending(String scope, String value) async =>
      pending[scope] = value;
  @override
  Future<void> saveSession(UserSession session) async {}
}

class FakePlatform extends PlatformService {
  FakePlatform() : super(baseUri: Uri.parse('https://example.test'));
  var writes = 0;
  bool failWrites = false;
  final calls = Completer<List<GeneratedBackend>>();
  @override
  Future<List<GeneratedBackend>> backends(String token, String projectId) =>
      projectId == 'p' ? calls.future : Future.value(const []);
  @override
  Future<BackendContract> contract(
    String token,
    String projectId,
    String deploymentId,
  ) async => BackendContract(
    deploymentId: deploymentId,
    runId: 'r',
    contractHash: 'hash',
    openApi: {
      'paths': {
        '/tasks': {'get': {}},
      },
    },
  );
  @override
  Future<GatewayResult> execute(
    String token,
    String projectId,
    String deploymentId,
    String contractHash,
    OperationProposal proposal,
  ) async {
    writes++;
    if (failWrites) throw StateError('offline');
    return const GatewayResult(status: 200, body: {});
  }
}

class FakeMnn implements MnnService {
  @override
  Future<String> generate({
    required String prompt,
    int maxTokens = 512,
  }) async => '{"method":"GET","path":"/tasks","body":null}';
  @override
  Future<MnnStatus> loadModel(String directory) async =>
      MnnStatus(modelDirectory: directory, isLoaded: true, message: 'loaded');
  @override
  Future<void> release() async {}
}

final session = UserSession(
  token: 't',
  expiresAt: DateTime.utc(2099),
  user: const AppUser(
    id: 'u',
    username: 'u',
    displayName: 'U',
    email: 'u@example.test',
  ),
);
const project = PlatformProject(id: 'p', name: 'Project');
const backend = GeneratedBackend(
  deploymentId: 'd',
  name: 'Backend',
  state: 'RUNNING',
  runId: 'r',
  contractHash: 'hash',
);

void main() {
  test('backend selection ignores stale responses', () async {
    final api = FakePlatform();
    final controller = WorkspaceController(
      service: api,
      store: MemoryStore(),
      mnn: FakeMnn(),
    );
    final first = controller.selectProject(session, project);
    final second = controller.selectProject(
      session,
      const PlatformProject(id: 'p2', name: 'New project'),
    );
    api.calls.complete([backend]);
    await Future.wait([first, second]);
    expect(controller.backends, isEmpty);
  });

  test(
    'offline confirmation preserves proposal and never retries writes',
    () async {
      final api = FakePlatform()..failWrites = true;
      final store = MemoryStore();
      final controller = WorkspaceController(
        service: api,
        store: store,
        mnn: FakeMnn(),
      );
      controller.project = project;
      controller.backend = backend;
      controller.contract = await api.contract('t', 'p', 'd');
      controller.setProposal(
        const OperationProposal(method: 'GET', path: '/tasks', body: null),
      );
      await controller.confirm(session);
      expect(api.writes, 1);
      expect(controller.proposal, isNotNull);
      expect(controller.error, contains('desconoce'));
      expect(store.pending['u.p.d.hash'], contains('/tasks'));
      expect(store.pending['u.p.d.hash.inspection'], 'required');
    },
  );
}
