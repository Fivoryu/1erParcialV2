import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:diagrammer_mobile/data/litert_agent_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('agent-test');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));
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
