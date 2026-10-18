import 'dart:convert';

import 'package:flutter/material.dart';

import 'package:diagrammer_mobile/data/mnn_service.dart';
import 'package:diagrammer_mobile/domain/openapi_validator.dart';
import 'package:diagrammer_mobile/domain/operation_proposal.dart';

// Isolated physical-device acceptance entry point. It never calls the gateway.
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MaterialApp(home: DeviceProbe()));
}

class DeviceProbe extends StatefulWidget {
  const DeviceProbe({super.key});
  @override
  State<DeviceProbe> createState() => _DeviceProbeState();
}

class _DeviceProbeState extends State<DeviceProbe> {
  final _events = <String>[];
  final _mnn = const MethodChannelMnnService();

  @override
  void initState() {
    super.initState();
    _run();
  }

  void _record(String phase, Map<String, dynamic> details) {
    final event = jsonEncode({'phase': phase, ...details});
    debugPrint('DEVICE_PROBE $event');
    if (mounted) setState(() => _events.add(event));
  }

  Future<void> _run() async {
    const directory = String.fromEnvironment(
      'MODEL_DIRECTORY',
      defaultValue: '/data/user/0/com.diagrammer.diagrammer_mobile/files/models/qwen3.5-0.8b',
    );
    final loadTimer = Stopwatch()..start();
    try {
      final status = await _mnn.loadModel(directory);
      if (!status.isLoaded) throw StateError(status.message);
      _record('loaded', {
        'runtime': status.message,
        'loadMilliseconds': loadTimer.elapsedMilliseconds,
      });
      const prompt =
          'Return only one JSON object with keys method, path, body. '
          'No markdown, explanations, or extra keys. Allowed operation: '
          'POST /api/notes with body {"title": string}. '
          'Create a note with title "USB offline test". '
          'The exact JSON format is '
          '{"method":"POST","path":"/api/notes",'
          '"body":{"title":"USB offline test"}}.';
      final timer = Stopwatch()..start();
      final output = await _mnn.generate(prompt: prompt, maxTokens: 256);
      _record('generated', {
        'generationMilliseconds': timer.elapsedMilliseconds,
        'output': output,
      });
      final proposal = OperationProposal.parse(output);
      const contract = <String, dynamic>{
        'paths': {
          '/api/notes': {
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
      };
      const OpenApiValidator().validate(proposal, contract);
      if (proposal.method != 'POST' ||
          proposal.path != '/api/notes' ||
          proposal.body is! Map ||
          proposal.body['title'] != 'USB offline test') {
        throw StateError('The generated proposal does not match the request.');
      }
      _record('passed', {'proposal': proposal.toJson(), 'gatewayCalls': 0});
    } catch (error) {
      _record('failed', {'error': error.toString()});
    } finally {
      try {
        await _mnn.release();
        _record('released', {});
      } catch (error) {
        _record('releaseFailed', {'error': error.toString()});
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Offline MNN device probe')),
    body: SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: SelectableText(_events.join('\n\n')),
      ),
    ),
  );
}
