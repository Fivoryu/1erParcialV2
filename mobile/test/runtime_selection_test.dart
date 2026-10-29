import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:diagrammer_mobile/data/litert_agent_service.dart';
import 'package:diagrammer_mobile/data/mnn_service.dart';
import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/presentation/workspace_controller.dart';

class FakeQwen implements MnnService, MnnAgentService, MnnModelDiscovery {
  FakeQwen(this.events);
  final List<String> events;
  @override
  Future<String?> discoverModel() async => '/private/qwen';
  @override
  Future<MnnStatus> loadModel(String directory) async {
    events.add('qwen.load');
    return MnnStatus(
      modelDirectory: directory,
      isLoaded: true,
      message: 'ready',
    );
  }

  @override
  Future<String> generate({
    required String prompt,
    int maxTokens = 512,
  }) async => '{}';
  @override
  Future<String> generateAgent({
    required String prompt,
    int maxTokens = 512,
  }) async => '{"tag":"answer","text":"qwen"}';
  @override
  Future<void> release() async {
    events.add('qwen.release');
  }
}

class FakeGemma implements LiteRtAgentService {
  FakeGemma(this.events);
  final List<String> events;
  Completer<String>? generation;
  Completer<LiteRtAgentStatus>? loading;
  String? received;
  bool failRelease = false;
  @override
  Future<LiteRtAgentStatus> status() async => const LiteRtAgentStatus(
    available: true,
    modelPresent: true,
    loaded: false,
    message: 'installed',
  );
  @override
  Future<LiteRtAgentStatus> load() async {
    events.add('gemma.load');
    return loading?.future ??
        const LiteRtAgentStatus(
          available: true,
          modelPresent: true,
          loaded: true,
          message: 'ready',
        );
  }

  @override
  Future<String> generateAgent({
    required String prompt,
    int maxTokens = 512,
  }) async {
    received = prompt;
    events.add('gemma.generate');
    return generation?.future ?? '{"tag":"answer","text":"gemma"}';
  }

  @override
  Future<void> cancel() async {
    events.add('gemma.cancel');
  }

  @override
  Future<void> release() async {
    events.add('gemma.release');
    if (failRelease) {
      throw StateError('release failed');
    }
  }
}

class FakeSessionGemma extends FakeGemma implements LiteRtSessionAgentService {
  FakeSessionGemma(super.events);
  final opened = <(String, String)>[];
  final sent = <(String, String)>[];

  @override
  Future<void> openSession({required String scope, required String systemInstruction}) async {
    opened.add((scope, systemInstruction));
  }

  @override
  Future<String> sendInSession({
    required String scope,
    required String message,
    int maxTokens = 256,
    Duration timeout = const Duration(seconds: 30),
    void Function(String partial)? onPartial,
  }) async {
    sent.add((scope, message));
    return '{"tag":"answer","text":"gemma"}';
  }

  @override
  Future<void> closeSession() async {}

  @override
  Future<String> generateAgent({
    required String prompt,
    int maxTokens = 512,
    void Function(String partial)? onPartial,
  }) => super.generateAgent(prompt: prompt, maxTokens: maxTokens);
}

void main() {
  test('a session-capable Gemma gets the static prompt once per backend and only the request per message', () async {
    final events = <String>[];
    final gemma = FakeSessionGemma(events);
    final c = WorkspaceController(selectedRuntime: LocalRuntime.qwen, mnn: FakeQwen(events), litert: gemma);
    await c.selectRuntime(LocalRuntime.gemma);
    await c.loadDetectedModel();
    c.contract = const BackendContract(deploymentId: 'd', runId: 'r', contractHash: 'h', openApi: {});
    const envelope =
        '{"instruction":"rules","examples":[1],"request":"hola","contract":[{"entity":"alumno"}],"observations":[]}';

    expect(await c.generateAgent(envelope), '{"tag":"answer","text":"gemma"}');

    expect(gemma.opened, [('d:h', '{"instruction":"rules","examples":[1],"contract":[{"entity":"alumno"}]}')]);
    expect(gemma.sent, [('d:h', '{"request":"hola","observations":[]}')]);
    expect(gemma.received, isNull, reason: 'the one-shot path is not used');
    expect(await c.generateAgent('not an envelope'), '{"tag":"answer","text":"gemma"}');
    expect(gemma.received, 'not an envelope');
  });

  test('the app starts on Gemma, the default local model', () {
    final c = WorkspaceController();
    expect(c.selectedRuntime, LocalRuntime.gemma);
  });

  test('explicit switch clears ready but retains backend and draft; release precedes load', () async {
    final events = <String>[];
    final qwen = FakeQwen(events);
    final gemma = FakeGemma(events);
    final c = WorkspaceController(selectedRuntime: LocalRuntime.qwen, mnn: qwen, litert: gemma);
    expect(c.selectedRuntime, LocalRuntime.qwen);
    await c.discoverModel();
    await c.loadDetectedModel();
    c.backend = const GeneratedBackend(
      deploymentId: 'd',
      name: 'fixture',
      state: 'UP',
      runId: 'r',
      contractHash: 'h',
    );
    c.requestDraft = 'keep';
    await c.selectRuntime(LocalRuntime.gemma);
    expect(c.mnnStatus.isLoaded, false);
    expect(c.requestDraft, 'keep');
    expect(c.backend!.deploymentId, 'd');
    expect(events, ['qwen.load', 'qwen.release']);
    await c.loadDetectedModel();
    expect(events.last, 'gemma.load');
    const full =
        '{"request":"hi","observations":[{"complete":false}],"conversation":[1]}';
    await c.generateAgent(full);
    expect(gemma.received, full);
  });
  test(
    'switch reaches native cancel immediately and fences late generation',
    () async {
      final events = <String>[];
      final gemma = FakeGemma(events);
      final c = WorkspaceController(selectedRuntime: LocalRuntime.qwen, mnn: FakeQwen(events), litert: gemma);
      await c.selectRuntime(LocalRuntime.gemma);
      await c.loadDetectedModel();
      gemma.generation = Completer<String>();
      final output = c.generateAgent('{}');
      final rejection = expectLater(
        output,
        throwsA(isA<MnnUnavailableException>()),
      );
      await Future<void>.delayed(Duration.zero);
      final switched = c.selectRuntime(LocalRuntime.qwen);
      await Future<void>.delayed(Duration.zero);
      expect(events, contains('gemma.cancel'));
      expect(c.mnnStatus.isLoaded, false);
      expect(events.last, isNot('gemma.release'));
      gemma.generation!.complete('{"tag":"answer","text":"late"}');
      await rejection;
      await switched;
      expect(events.last, 'gemma.release');
    },
  );
  test(
    'logout fences Gemma load and releases both engines without fallback',
    () async {
      final events = <String>[];
      final gemma = FakeGemma(events);
      final c = WorkspaceController(selectedRuntime: LocalRuntime.qwen, mnn: FakeQwen(events), litert: gemma);
      await c.selectRuntime(LocalRuntime.gemma);
      gemma.loading = Completer<LiteRtAgentStatus>();
      final load = c.loadDetectedModel();
      await Future<void>.delayed(Duration.zero);
      final clear = c.clear();
      gemma.loading!.complete(
        const LiteRtAgentStatus(
          available: true,
          modelPresent: true,
          loaded: true,
          message: 'late',
        ),
      );
      await load;
      await clear;
      expect(c.mnnStatus.isLoaded, false);
      expect(events, containsAll(['gemma.cancel', 'gemma.release']));
    },
  );
  test(
    'optional Gemma absent remains controlled and never falls back',
    () async {
      final events = <String>[];
      final c = WorkspaceController(selectedRuntime: LocalRuntime.qwen, mnn: FakeQwen(events));
      await c.selectRuntime(LocalRuntime.gemma);
      await c.loadDetectedModel();
      expect(c.mnnStatus.isLoaded, false);
      expect(events, ['qwen.release']);
    },
  );
  test('failed old release prevents loading another engine', () async {
    final events = <String>[];
    final gemma = FakeGemma(events);
    final c = WorkspaceController(selectedRuntime: LocalRuntime.qwen, mnn: FakeQwen(events), litert: gemma);
    await c.selectRuntime(LocalRuntime.gemma);
    await c.loadDetectedModel();
    gemma.failRelease = true;
    await c.selectRuntime(LocalRuntime.qwen);
    await c.configureModel('/private/qwen');
    expect(events.where((e) => e == 'qwen.load'), isEmpty);
    expect(c.mnnStatus.isLoaded, false);
  });
  test(
    'failed Gemma release cannot be bypassed by switching selector back',
    () async {
      final events = <String>[];
      final gemma = FakeGemma(events);
      final c = WorkspaceController(selectedRuntime: LocalRuntime.qwen, mnn: FakeQwen(events), litert: gemma);
      await c.selectRuntime(LocalRuntime.gemma);
      await c.loadDetectedModel();
      expect(events.where((e) => e == 'gemma.load').length, 1);
      gemma.failRelease = true;
      await c.selectRuntime(LocalRuntime.qwen);
      await c.selectRuntime(LocalRuntime.gemma);
      await c.loadDetectedModel();
      expect(
        events.where((e) => e == 'gemma.load').length,
        1,
        reason: 'Failed ownership release must not be cleared by releasing the never-loaded alternative',
      );
    },
  );
  test(
    'successful retained owner cleanup permits the requested next runtime',
    () async {
      final events = <String>[];
      final gemma = FakeGemma(events);
      final c = WorkspaceController(selectedRuntime: LocalRuntime.qwen, mnn: FakeQwen(events), litert: gemma);
      await c.selectRuntime(LocalRuntime.gemma);
      await c.loadDetectedModel();
      gemma.failRelease = true;
      await c.selectRuntime(LocalRuntime.qwen);
      await c.selectRuntime(LocalRuntime.gemma);
      await c.loadDetectedModel();
      gemma.failRelease = false;
      await c.selectRuntime(LocalRuntime.qwen);
      await c.loadDetectedModel();
      expect(events.where((e) => e == 'gemma.load').length, 1);
      expect(events.last, 'qwen.load');
      expect(c.mnnStatus.isLoaded, true);
    },
  );
}
