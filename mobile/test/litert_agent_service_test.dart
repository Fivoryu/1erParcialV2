import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:diagrammer_mobile/data/litert_agent_service.dart';
import 'package:diagrammer_mobile/domain/agent_models.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('agent-test');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  Future<void> deliverNativeCall(MethodCall call) async {
    final reply = Completer<void>();
    messenger.handlePlatformMessage(
      channel.name,
      const StandardMethodCodec().encodeMethodCall(call),
      (_) => reply.complete(),
    );
    await reply.future;
  }

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    channel.setMethodCallHandler(null);
  });
  test('optional native unavailable never fakes readiness', () async {
    expect(
      (await MethodChannelLiteRtAgentService(
        channel: channel,
      ).status()).available,
      false,
    );
  });
  test(
    'full envelope and explicit cancellation reach native unchanged',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return call.method == 'generateAgent'
            ? ' {"tag":"answer","text":"ok"} '
            : null;
      });
      final service = MethodChannelLiteRtAgentService(channel: channel);
      const prompt =
          '{"request":"hello","observations":[1],"conversation":[2]}';
      expect(await service.generateAgent(prompt: prompt), contains('answer'));
      await service.cancel();
      expect(calls.first.arguments, {'prompt': prompt, 'maxTokens': 512});
      expect(calls.last.method, 'cancel');
      expect(calls.last.arguments, isNull);
    },
  );
  test(
    'forwards bounded partials and still validates the strict final decision',
    () async {
      final partials = <String>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'generateAgent') {
          await deliverNativeCall(
            const MethodCall('onPartial', {'text': '{"tag":"answer"'}),
          );
          await deliverNativeCall(
            const MethodCall('onPartial', {
              'text': '{"tag":"answer","text":"done"}',
            }),
          );
          await deliverNativeCall(const MethodCall('onPartial', {'text': ''}));
          await deliverNativeCall(const MethodCall('onPartial', {'text': 42}));
          await deliverNativeCall(
            MethodCall('onPartial', {'text': 'x' * 32769}),
          );
        }
        return '{"tag":"answer","text":"done"}';
      });

      final result = await MethodChannelLiteRtAgentService(channel: channel)
          .generateAgent(prompt: '{}', onPartial: partials.add);

      expect(partials, ['{"tag":"answer"', '{"tag":"answer","text":"done"}']);
      expect(AgentAction.parse(result).payload['text'], 'done');
      await deliverNativeCall(
        const MethodCall('onPartial', {'text': 'late partial'}),
      );
      expect(partials, hasLength(2));
    },
  );

  test(
    'strict response rejects arbitrary output and malformed status',
    () async {
      messenger.setMockMethodCallHandler(
        channel,
        (call) async =>
            call.method == 'status' ? {'available': true} : 'not json',
      );
      final service = MethodChannelLiteRtAgentService(channel: channel);
      await expectLater(service.status(), throwsFormatException);
      await expectLater(
        service.generateAgent(prompt: '{}'),
        throwsFormatException,
      );
    },
  );
  test(
    'native prompt bound rejects oversized full envelope before dispatch',
    () async {
      var calls = 0;
      messenger.setMockMethodCallHandler(channel, (_) async {
        calls++;
        return '{"tag":"answer","text":"ok"}';
      });
      final service = MethodChannelLiteRtAgentService(channel: channel);
      await service.generateAgent(prompt: 'x' * 32768);
      await expectLater(
        service.generateAgent(prompt: 'x' * 32769),
        throwsArgumentError,
      );
      expect(calls, 1);
    },
  );

  group('persistent session', () {
    late List<MethodCall> calls;
    late MethodChannelLiteRtAgentService service;
    Object? Function(MethodCall call) onCall = (_) => 'reply';
    setUp(() {
      calls = <MethodCall>[];
      onCall = (_) => 'reply';
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return onCall(call);
      });
      service = MethodChannelLiteRtAgentService(channel: channel);
    });

    test(
      'open, send and close reach native with the agreed argument shapes',
      () async {
        await service.openSession(
          scope: 'backend-1',
          systemInstruction: 'contract',
        );
        final text = await service.sendInSession(
          scope: 'backend-1',
          message: 'list members',
          maxTokens: 128,
          timeout: const Duration(seconds: 20),
        );
        await service.closeSession();
        expect(text, 'reply');
        expect(calls.map((c) => c.method), [
          'openSession',
          'sendInSession',
          'closeSession',
        ]);
        expect(calls[0].arguments, {
          'scope': 'backend-1',
          'systemInstruction': 'contract',
        });
        expect(calls[1].arguments, {
          'scope': 'backend-1',
          'message': 'list members',
          'maxTokens': 128,
          'timeoutMs': 20000,
        });
      },
    );

    test(
      'reopening with the same scope and instruction keeps the session',
      () async {
        await service.openSession(scope: 's', systemInstruction: 'a');
        await service.openSession(scope: 's', systemInstruction: 'a');
        await service.openSession(scope: 's', systemInstruction: 'b');
        expect(calls.map((c) => c.method), ['openSession', 'openSession']);
      },
    );

    test('validates inputs before dispatch', () async {
      await expectLater(
        service.openSession(scope: '', systemInstruction: 'a'),
        throwsArgumentError,
      );
      await expectLater(
        service.openSession(scope: 's', systemInstruction: 'x' * 24001),
        throwsArgumentError,
      );
      await service.openSession(scope: 's', systemInstruction: 'x' * 24000);
      calls.clear();
      for (final bad in <Future<String> Function()>[
        () => service.sendInSession(scope: 's', message: ''),
        () => service.sendInSession(scope: 's', message: 'x' * 16001),
        () => service.sendInSession(scope: 's', message: 'm', maxTokens: 0),
        () => service.sendInSession(scope: 's', message: 'm', maxTokens: 513),
        () => service.sendInSession(
          scope: 's',
          message: 'm',
          timeout: const Duration(milliseconds: 999),
        ),
        () => service.sendInSession(
          scope: 's',
          message: 'm',
          timeout: const Duration(milliseconds: 120001),
        ),
      ]) {
        await expectLater(bad(), throwsArgumentError);
      }
      expect(calls, isEmpty);
    });

    test(
      'sending for another scope or without a session is a StateError',
      () async {
        await expectLater(
          service.sendInSession(scope: 's', message: 'm'),
          throwsStateError,
        );
        await service.openSession(scope: 's', systemInstruction: 'a');
        await expectLater(
          service.sendInSession(scope: 'other', message: 'm'),
          throwsStateError,
        );
        await service.closeSession();
        await expectLater(
          service.sendInSession(scope: 's', message: 'm'),
          throwsStateError,
        );
        expect(calls.map((c) => c.method), ['openSession', 'closeSession']);
      },
    );

    test(
      'native timeout maps to LiteRtTimeoutException and the next send reopens',
      () async {
        await service.openSession(scope: 's', systemInstruction: 'sys');
        onCall = (call) => call.method == 'sendInSession'
            ? throw PlatformException(
                code: 'LITERT_AGENT_TIMEOUT',
                message: 'slow',
              )
            : null;
        await expectLater(
          service.sendInSession(scope: 's', message: 'm'),
          throwsA(isA<LiteRtTimeoutException>()),
        );
        onCall = (_) => 'fine';
        calls.clear();
        expect(
          await service.sendInSession(scope: 's', message: 'again'),
          'fine',
        );
        expect(calls.map((c) => c.method), ['openSession', 'sendInSession']);
        expect(calls.first.arguments, {
          'scope': 's',
          'systemInstruction': 'sys',
        });
      },
    );

    test(
      'other native failures also discard the session and surface unchanged',
      () async {
        await service.openSession(scope: 's', systemInstruction: 'sys');
        onCall = (call) => call.method == 'sendInSession'
            ? throw PlatformException(code: 'LITERT_AGENT_FAILED')
            : null;
        await expectLater(
          service.sendInSession(scope: 's', message: 'm'),
          throwsA(isA<PlatformException>()),
        );
        onCall = (_) => 'ok';
        calls.clear();
        await service.sendInSession(scope: 's', message: 'm');
        expect(calls.first.method, 'openSession');
      },
    );

    test('streams bounded partials and rejects an empty final text', () async {
      final partials = <String>[];
      await service.openSession(scope: 's', systemInstruction: 'a');
      onCall = (call) {
        if (call.method == 'sendInSession') {
          deliverNativeCall(const MethodCall('onPartial', {'text': 'par'}));
        }
        return 'final text';
      };
      expect(
        await service.sendInSession(
          scope: 's',
          message: 'm',
          onPartial: partials.add,
        ),
        'final text',
      );
      await Future<void>.delayed(Duration.zero);
      expect(partials, ['par']);
      onCall = (_) => '';
      await expectLater(
        service.sendInSession(scope: 's', message: 'm'),
        throwsFormatException,
      );
    });

    test(
      'status accepts the optional backend key only with a valid value',
      () async {
        onCall = (_) => {
          'available': true,
          'modelPresent': true,
          'loaded': true,
          'message': 'ok',
          'backend': 'gpu',
        };
        expect((await service.status()).backend, 'gpu');
        onCall = (_) => {
          'available': true,
          'modelPresent': true,
          'loaded': true,
          'message': 'ok',
          'backend': 'tpu',
        };
        await expectLater(service.status(), throwsFormatException);
      },
    );
  });
}
