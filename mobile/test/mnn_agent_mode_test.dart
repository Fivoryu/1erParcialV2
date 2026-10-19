import 'package:diagrammer_mobile/data/mnn_service.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('agent mode is additive and preserves legacy generate', () async {
    const channel = MethodChannel('test/agent-mode');
    const service = MethodChannelMnnService(channel);
    final methods = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          methods.add(call.method);
          return '{"type":"answer","text":"Hello"}';
        });
    await service.generateAgent(prompt: 'hello');
    await service.generate(prompt: 'hello');
    expect(methods, ['generateAgent', 'generate']);
    await expectLater(
      service.generateAgent(prompt: 'x' * 32769),
      throwsA(isA<MnnUnavailableException>()),
    );
    expect(methods.length, 2);
  });
}
