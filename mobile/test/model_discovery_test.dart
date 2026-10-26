import 'dart:async';

import 'package:diagrammer_mobile/data/mnn_service.dart';
import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/presentation/workspace_controller.dart';
import 'package:flutter_test/flutter_test.dart';

import 'workspace_switch_test.dart' as f;

class DetectedMnn extends f.SwitchMnn implements MnnModelDiscovery {
  String? directory = '/private/models/qwen3.5-0.8b';
  Completer<MnnStatus>? loading;
  int loads = 0;
  @override
  Future<String?> discoverModel() async => directory;
  @override
  Future<MnnStatus> loadModel(String directory) async {
    loads++;
    return loading?.future ??
        MnnStatus(modelDirectory: directory, isLoaded: true, message: 'Loaded');
  }
}

void main() {
  test('installed model is detected and loaded without manual path', () async {
    final mnn = DetectedMnn();
    final c = WorkspaceController(mnn: mnn);
    await c.discoverModel();
    expect(c.detectedModelDirectory, mnn.directory);
    expect(mnn.loads, 0);
    await c.loadDetectedModel();
    expect(mnn.loads, 1);
    expect(c.mnnStatus.isLoaded, isTrue);
  });
  test('missing installed model is actionable and does not load', () async {
    final mnn = DetectedMnn()..directory = null;
    final c = WorkspaceController(mnn: mnn);
    await c.discoverModel();
    await c.loadDetectedModel();
    expect(mnn.loads, 0);
    expect(c.mnnStatus.isLoaded, isFalse);
    expect(c.mnnStatus.message, contains('no está instalado'));
  });
  test('logout fences delayed model load and releases ownership', () async {
    final mnn = DetectedMnn()..loading = Completer<MnnStatus>();
    final c = WorkspaceController(mnn: mnn);
    await c.discoverModel();
    final load = c.loadDetectedModel();
    await Future<void>.delayed(Duration.zero);
    expect(c.isModelLoading, isTrue);
    final clear = c.clear();
    mnn.loading!.complete(
      MnnStatus(
        modelDirectory: mnn.directory,
        isLoaded: true,
        message: 'Loaded',
      ),
    );
    await load;
    await clear;
    expect(c.mnnStatus.isLoaded, isFalse);
    expect(c.detectedModelDirectory, isNull);
    expect(c.isModelLoading, isFalse);
    expect(mnn.releases, 1);
  });
}
