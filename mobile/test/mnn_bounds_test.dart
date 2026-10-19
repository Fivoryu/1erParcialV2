import 'package:diagrammer_mobile/data/mnn_service.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('test/mnn-bounds');
  const service = MethodChannelMnnService(channel);
  test(
    'discovery channel is read-only and preserves loadModel contract',
    () async {
      final methods = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            methods.add(call.method);
            if (call.method == 'discoverModel') {
              return '/private/models/qwen3.5-0.8b';
            }
            expect(call.arguments, {
              'directory': '/private/models/qwen3.5-0.8b',
            });
            return {'loaded': true, 'message': 'Loaded'};
          });
      expect(await service.discoverModel(), '/private/models/qwen3.5-0.8b');
      expect(methods, ['discoverModel']);
      expect(
        (await service.loadModel('/private/models/qwen3.5-0.8b')).isLoaded,
        isTrue,
      );
      expect(methods, ['discoverModel', 'loadModel']);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    },
  );
  test('Dart/native prompt and token bounds match', () async {
    var calls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls++;
          return '{}';
        });
    expect(await service.generate(prompt: 'x' * 32768, maxTokens: 1), '{}');
    expect(await service.generate(prompt: 'x', maxTokens: 512), '{}');
    await expectLater(
      service.generate(prompt: 'x' * 32769),
      throwsA(isA<MnnUnavailableException>()),
    );
    await expectLater(
      service.generate(prompt: 'x', maxTokens: 0),
      throwsA(isA<MnnUnavailableException>()),
    );
    await expectLater(
      service.generate(prompt: 'x', maxTokens: 513),
      throwsA(isA<MnnUnavailableException>()),
    );
    expect(calls, 2);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
}
