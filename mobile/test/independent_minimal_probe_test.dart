import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/presentation/workspace_controller.dart';

import 'model_discovery_test.dart';

class PendingDiscovery extends DetectedMnn {
  final pending = Completer<String?>();
  @override
  Future<String?> discoverModel() => pending.future;
}

class OrderedMnn extends DetectedMnn {
  final calls = <String>[];
  final first = Completer<MnnStatus>();
  @override
  Future<MnnStatus> loadModel(String directory) async {
    calls.add('load:$directory');
    if (calls.length == 1) return first.future;
    return MnnStatus(
      modelDirectory: directory,
      isLoaded: true,
      message: 'Loaded',
    );
  }

  @override
  Future<void> release() async {
    calls.add('release');
  }
}

void main() {
  test('logout fences delayed discovery', () async {
    final m = PendingDiscovery();
    final c = WorkspaceController(selectedRuntime: LocalRuntime.qwen, mnn: m);
    final discover = c.discoverModel();
    await c.clear();
    m.pending.complete('/private/models/qwen3.5-0.8b');
    await discover;
    expect(c.detectedModelDirectory, isNull);
    expect(c.isModelDiscovering, isFalse);
    expect(c.mnnStatus.isLoaded, isFalse);
  });
  test('new load follows old load then logout release', () async {
    final m = OrderedMnn();
    final c = WorkspaceController(selectedRuntime: LocalRuntime.qwen, mnn: m);
    final old = c.configureModel('old');
    await Future<void>.delayed(Duration.zero);
    final clear = c.clear();
    final fresh = c.configureModel('fresh');
    m.first.complete(
      const MnnStatus(modelDirectory: 'old', isLoaded: true, message: 'old'),
    );
    await Future.wait([old, clear, fresh]);
    expect(m.calls, ['load:old', 'release', 'load:fresh']);
    expect(c.mnnStatus.modelDirectory, 'fresh');
    expect(c.mnnStatus.isLoaded, isTrue);
    expect(c.isModelLoading, isFalse);
  });
}
