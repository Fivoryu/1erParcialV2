import 'dart:async';

import 'package:diagrammer_mobile/data/mnn_service.dart';
import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/data/secure_store.dart';
import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/domain/operation_proposal.dart';
import 'package:diagrammer_mobile/presentation/workspace_controller.dart';
import 'package:flutter_test/flutter_test.dart';

const switchProject = PlatformProject(id: 'p', name: 'Fixture project');
const otherProject = PlatformProject(id: 'q', name: 'Other project');
const switchA = GeneratedBackend(
  deploymentId: 'a',
  name: 'Backend A',
  state: 'UP',
  runId: 'run-a',
  contractHash: 'hash-a',
);
const switchB = GeneratedBackend(
  deploymentId: 'b',
  name: 'Backend B',
  state: 'UP',
  runId: 'run-b',
  contractHash: 'hash-b',
);
final switchSession = UserSession(
  token: 'fixture-token',
  expiresAt: DateTime.utc(2099),
  user: const AppUser(
    id: 'u',
    username: 'fixture',
    displayName: 'Fixture',
    email: null,
  ),
);
String scopeFor(String backend) => 'u.p.$backend.hash-$backend';
String guardFor(String backend) => '${scopeFor(backend)}.inspection';
const proposalA = OperationProposal(
  method: 'POST',
  path: '/api/notes',
  body: {'title': 'A'},
);
const proposalB = OperationProposal(
  method: 'GET',
  path: '/api/notes',
  body: null,
);

BackendContract switchContract(String id) => BackendContract(
  deploymentId: id,
  runId: 'run-$id',
  contractHash: 'hash-$id',
  openApi: {
    'paths': {
      '/api/notes': {
        'get': {},
        'post': {
          'requestBody': {
            'content': {
              'application/json': {
                'schema': {
                  'type': 'object',
                  'required': ['title'],
                  'additionalProperties': false,
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

class SwitchStore implements SecureStore {
  SwitchStore({this.savedSession});
  UserSession? savedSession;
  final pending = <String, String>{};
  Completer<void>? writeGate;
  Completer<void>? writeStarted;
  @override
  Future<void> clearAll() async {
    pending.clear();
    savedSession = null;
  }

  @override
  Future<UserSession?> readSession() async => savedSession;
  @override
  Future<void> saveSession(UserSession value) async {
    savedSession = value;
  }

  @override
  Future<String?> readPending(String scope) async => pending[scope];
  @override
  Future<void> savePending(String scope, String value) async {
    pending[scope] = value;
    if (!scope.endsWith('.inspection') && writeGate != null) {
      if (writeStarted != null && !writeStarted!.isCompleted) {
        writeStarted!.complete();
      }
      await writeGate!.future;
    }
  }
}

class SwitchMnn implements MnnService {
  int releases = 0;
  Completer<String>? generated;
  @override
  Future<String> generate({required String prompt, int maxTokens = 512}) =>
      generated?.future ?? Future.value(proposalB.encode());
  @override
  Future<MnnStatus> loadModel(String directory) async =>
      MnnStatus(modelDirectory: directory, isLoaded: true, message: 'Loaded');
  @override
  Future<void> release() async {
    releases++;
  }
}

class SwitchPlatform extends PlatformService {
  SwitchPlatform() : super(baseUri: Uri.parse('https://example.test'));
  Future<BackendContract> Function(String)? contractLoader;
  Future<List<GeneratedBackend>> Function(String)? backendsLoader;
  Future<GatewayResult> Function(String, OperationProposal)? handler;
  final calls = <({String backend, String method})>[];
  @override
  Future<List<PlatformProject>> projects(String token) async => [
    switchProject,
    otherProject,
  ];
  @override
  Future<List<GeneratedBackend>> backends(String token, String projectId) =>
      backendsLoader?.call(projectId) ??
      Future.value(projectId == 'p' ? [switchA, switchB] : []);
  @override
  Future<BackendContract> contract(
    String token,
    String projectId,
    String deploymentId,
  ) =>
      contractLoader?.call(deploymentId) ??
      Future.value(switchContract(deploymentId));
  @override
  Future<GatewayResult> execute(
    String token,
    String projectId,
    String deploymentId,
    String contractHash,
    OperationProposal proposal,
  ) {
    calls.add((backend: deploymentId, method: proposal.method));
    return handler?.call(deploymentId, proposal) ??
        Future.value(
          GatewayResult(
            status: 200,
            body: [
              {'title': 'record $deploymentId'},
            ],
          ),
        );
  }
}

WorkspaceController switchController(
  SwitchPlatform api,
  SwitchStore store, [
  SwitchMnn? mnn,
]) => WorkspaceController(selectedRuntime: LocalRuntime.qwen, service: api, store: store, mnn: mnn ?? SwitchMnn())
  ..projects = [switchProject, otherProject]
  ..project = switchProject
  ..backends = [switchA, switchB]
  ..mnnStatus = const MnnStatus(
    modelDirectory: '/private/model',
    isLoaded: true,
    message: 'Loaded',
  );

void main() {
  test('leaving clears backend state but keeps project, model and pending proposal', () async {
    final api = SwitchPlatform();
    final store = SwitchStore()..pending[scopeFor('a')] = proposalA.encode();
    final mnn = SwitchMnn();
    final c = switchController(api, store, mnn);
    await c.selectBackend(switchSession, switchA);
    final status = c.mnnStatus;
    c.latestResult = const GatewayResult(status: 200, body: 'A-only-result');
    c.error = 'A-only-error';
    c.requiresInspection = true;
    c.isConfirming = true;
    c.leaveBackend();
    expect(c.backend, isNull);
    expect(c.contract, isNull);
    expect(c.proposal, isNull);
    expect(c.latestResult, isNull);
    expect(c.error, isNull);
    expect(c.requiresInspection, isFalse);
    expect(c.isConfirming, isFalse);
    expect(c.project, switchProject);
    expect(c.backends, [switchA, switchB]);
    expect(c.projects, [switchProject, otherProject]);
    expect(c.mnnStatus, same(status));
    expect(mnn.releases, 0);
    expect(store.pending[scopeFor('a')], proposalA.encode());
    expect(api.calls, isEmpty);
  });

  test(
    'switching restores only B pending JSON and clears all A transient state',
    () async {
      final api = SwitchPlatform();
      final store = SwitchStore()
        ..pending[scopeFor('a')] = proposalA.encode()
        ..pending[scopeFor('b')] = proposalB.encode();
      final c = switchController(api, store);
      await c.selectBackend(switchSession, switchA);
      c.latestResult = const GatewayResult(status: 200, body: 'A-only-result');
      c.error = 'A-only-error';
      c.requiresInspection = true;
      c.isConfirming = true;
      await c.selectBackend(switchSession, switchB);
      expect(c.backend, switchB);
      expect(c.contract!.deploymentId, 'b');
      expect(c.proposal!.encode(), proposalB.encode());
      expect(c.latestResult, isNull);
      expect(c.error, isNull);
      expect(c.requiresInspection, isFalse);
      expect(c.isConfirming, isFalse);
      expect(store.pending[scopeFor('a')], proposalA.encode());
      expect(api.calls, isEmpty);
    },
  );

  test('late A contract failure cannot overwrite selected B', () async {
    final a = Completer<BackendContract>();
    final api = SwitchPlatform()
      ..contractLoader = (id) =>
          id == 'a' ? a.future : Future.value(switchContract(id));
    final c = switchController(api, SwitchStore());
    final old = c.selectBackend(switchSession, switchA);
    await c.selectBackend(switchSession, switchB);
    a.completeError(const PlatformException(503, 'Old A failure'));
    await old;
    expect(c.backend, switchB);
    expect(c.contract!.deploymentId, 'b');
    expect(c.error, isNull);
  });

  test(
    'late project failure cannot overwrite a newer project selection',
    () async {
      final oldList = Completer<List<GeneratedBackend>>();
      final api = SwitchPlatform()
        ..backendsLoader = (id) =>
            id == 'p' ? oldList.future : Future.value([]);
      final c = switchController(api, SwitchStore());
      final old = c.selectProject(switchSession, switchProject);
      await c.selectProject(switchSession, otherProject);
      oldList.completeError(
        const PlatformException(503, 'Old project failure'),
      );
      await old;
      expect(c.project, otherProject);
      expect(c.backend, isNull);
      expect(c.error, isNull);
    },
  );

  test('leaving while contract loads cannot resurrect a backend', () async {
    final a = Completer<BackendContract>();
    final api = SwitchPlatform()..contractLoader = (_) => a.future;
    final c = switchController(api, SwitchStore());
    final loading = c.selectBackend(switchSession, switchA);
    c.leaveBackend();
    a.complete(switchContract('a'));
    await loading;
    expect(c.backend, isNull);
    expect(c.contract, isNull);
    expect(c.proposal, isNull);
    expect(api.calls, isEmpty);
  });

  test(
    'old A send cannot poison B and returning to in-flight A cannot replay it',
    () async {
      final response = Completer<GatewayResult>();
      final started = Completer<void>();
      final api = SwitchPlatform()
        ..handler = (id, p) {
          if (p.method == 'POST') {
            started.complete();
            return response.future;
          }
          return Future.value(const GatewayResult(status: 200, body: []));
        };
      final store = SwitchStore()..pending[scopeFor('a')] = proposalA.encode();
      final c = switchController(api, store);
      await c.selectBackend(switchSession, switchA);
      final sending = c.confirm(switchSession);
      await started.future;
      await c.selectBackend(switchSession, switchB);
      expect(c.isConfirming, isFalse);
      expect(c.latestResult, isNull);
      await c.selectBackend(switchSession, switchA);
      expect(c.isConfirming, isTrue);
      await c.confirm(switchSession);
      expect(api.calls, [(backend: 'a', method: 'POST')]);
      response.complete(
        const GatewayResult(status: 200, body: 'Old A completion'),
      );
      await sending;
      expect(c.isConfirming, isFalse);
      expect(c.latestResult, isNull);
      expect(c.requiresInspection, isTrue);
      expect(store.pending[guardFor('a')], 'required');
      await c.inspect(switchSession);
      expect(c.requiresInspection, isFalse);
      expect(store.pending[guardFor('a')], '');
      expect(api.calls.last, (backend: 'a', method: 'GET'));
    },
  );

  test('unknown A outcome survives B switching and a fresh controller without blocking B', () async {
    final api = SwitchPlatform()
      ..handler = (_, p) => p.method == 'POST'
          ? Future.error(StateError('Connection lost after send'))
          : Future.value(const GatewayResult(status: 200, body: []));
    final store = SwitchStore()..pending[scopeFor('a')] = proposalA.encode();
    final c = switchController(api, store);
    await c.selectBackend(switchSession, switchA);
    await c.confirm(switchSession);
    expect(c.requiresInspection, isTrue);
    expect(store.pending[scopeFor('a')], proposalA.encode());
    await c.selectBackend(switchSession, switchB);
    expect(c.requiresInspection, isFalse);
    final restored = switchController(api, store);
    await restored.selectBackend(switchSession, switchA);
    expect(restored.requiresInspection, isTrue);
    await restored.confirm(switchSession);
    expect(api.calls.length, 1);
  });

  test(
    'leaving during pending storage cancels a request before it is sent',
    () async {
      final api = SwitchPlatform();
      final store = SwitchStore()..pending[scopeFor('a')] = proposalA.encode();
      final c = switchController(api, store);
      await c.selectBackend(switchSession, switchA);
      store.writeGate = Completer<void>();
      store.writeStarted = Completer<void>();
      final sending = c.confirm(switchSession);
      await Future<void>.delayed(Duration.zero);
      c.leaveBackend();
      store.writeGate!.complete();
      await sending;
      expect(api.calls, isEmpty);
      expect(c.backend, isNull);
      expect(c.isConfirming, isFalse);
    },
  );

  test('late A local generation cannot create a B proposal or change B pending data', () async {
    final generated = Completer<String>();
    final mnn = SwitchMnn()..generated = generated;
    final store = SwitchStore()..pending[scopeFor('b')] = proposalB.encode();
    final api = SwitchPlatform();
    final c = switchController(api, store, mnn);
    await c.selectBackend(switchSession, switchA);
    final generating = c.propose(switchSession, 'Read A');
    await c.selectBackend(switchSession, switchB);
    generated.complete(proposalA.encode());
    await generating;
    expect(c.backend, switchB);
    expect(c.proposal!.encode(), proposalB.encode());
    expect(store.pending[scopeFor('b')], proposalB.encode());
    expect(api.calls, isEmpty);
  });
}
