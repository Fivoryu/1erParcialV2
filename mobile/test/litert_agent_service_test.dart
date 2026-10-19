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
  test('forwards bounded partials and still validates the strict final decision', () async {
    final partials = <String>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'generateAgent') {
        await deliverNativeCall(
          const MethodCall('onPartial', {'text': '{"tag":"answer"'}),
        );
        await deliverNativeCall(
          const MethodCall('onPartial', {'text': '{"tag":"answer","text":"done"}'}),
        );
        await deliverNativeCall(const MethodCall('onPartial', {'text': ''}));
        await deliverNativeCall(const MethodCall('onPartial', {'text': 42}));
        await deliverNativeCall(
          MethodCall('onPartial', {'text': 'x' * 32769}),
        );
      }
      return '{"tag":"answer","text":"done"}';
    });

    final result = await MethodChannelLiteRtAgentService(
      channel: channel,
    ).generateAgent(prompt: '{}', onPartial: partials.add);

    expect(partials, [
      '{"tag":"answer"',
      '{"tag":"answer","text":"done"}',
    ]);
    expect(AgentAction.parse(result).payload['text'], 'done');
    await deliverNativeCall(
      const MethodCall('onPartial', {'text': 'late partial'}),
    );
    expect(partials, hasLength(2));
  });

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
}
