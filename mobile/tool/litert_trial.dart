import 'dart:convert';

import 'package:flutter/material.dart';

import 'package:diagrammer_mobile/data/litert_trial_service.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MaterialApp(home: TrialScreen()));
}

class TrialScreen extends StatefulWidget {
  const TrialScreen({super.key});
  @override
  State<TrialScreen> createState() => _TrialScreenState();
}

class _TrialScreenState extends State<TrialScreen> {
  final _service = LiteRtTrialService();
  LiteRtTrialStatus? _status;
  bool _busy = false;
  String _notice = 'Checking optional native trial…';
  final _results = <Map<String, dynamic>>[];
  @override
  void initState() {
    super.initState();
    _check();
  }

  void _emit(Map<String, dynamic> value) =>
      debugPrint('LITERT_TRIAL ${jsonEncode(value)}');
  Future<void> _check() async {
    try {
      final status = await _service.status();
      _emit({
        'event': 'ready',
        'runtime': status.runtime,
        'version': status.version,
        'model': 'gemma-4-E2B-it.litertlm',
        'available': status.available,
        'modelPresent': status.modelPresent,
      });
      try {
        final previous = await _service.inspectPreviousExit();
        if (previous.availability != 'none') {
          debugPrint('LITERT_EXIT ${jsonEncode(previous.toJson())}');
        }
      } catch (_) {
        debugPrint('LITERT_EXIT {"availability":"unavailable"}');
      }
      if (mounted) {
        setState(() {
          _status = status;
          _notice = status.available && status.modelPresent
              ? 'Ready for an explicit two-case CPU stability probe.'
              : 'Trial unavailable or model missing. No download or fallback.';
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() => _notice = 'Native status failed validation.');
      }
    }
  }

  Future<void> _run() async {
    if (_busy || _status?.available != true || _status?.modelPresent != true) {
      return;
    }
    setState(() {
      _busy = true;
      _results.clear();
      _notice =
          'Running synthetic tool-selection measurement. No tools execute.';
    });
    var valid = 0, correct = 0;
    var halted = false;
    var completed = 0;
    try {
      final baseline = await _service.beginDiagnostic();
      _emit({
        'event': 'diagnostic_begin',
        'runId': baseline.runId,
        'metrics': baseline.metrics,
      });
    } catch (_) {
      _emit({
        'event': 'summary',
        'completed': 0,
        'plannedCases': 2,
        'stoppedEarly': true,
        'reason': 'Diagnostic persistence failed',
        'executedToolCount': 0,
      });
      if (mounted) {
        setState(() {
          _busy = false;
          _notice = 'Stopped before inference: diagnostic persistence failed.';
        });
      }
      return;
    }
    if (!mounted) {
      return;
    }
    for (final sample in liteRtTrialCases.take(2)) {
      final timer = Stopwatch()..start();
      Map<String, dynamic> row;
      try {
        final result = await _service.runCase(
          request: sample.request,
          entities: ['Note', 'Satellite'],
          schemas: liteRtTrialSchemas,
        );
        valid++;
        final match = sample.matches(result);
        if (match) correct++;
        row = {
          'event': 'case',
          'case': sample.id,
          'syntaxValid': true,
          'correct': match,
          'latencyMs': timer.elapsedMilliseconds,
          'attemptedToolCount': result.toolCalls.length,
          'executedToolCount': 0,
          'metrics': {
            for (final key in [
              'loadMs',
              'inferenceMs',
              'ttftMs',
              'pssKb',
              'rssKb',
              'peakPssKb',
              'peakRssKb',
              'wallClockMs',
              'elapsedRealtimeMs',
              'availableRamKb',
              'totalRamKb',
            ])
              key: result.metrics[key] ?? 'unavailable',
          },
          'energy': 'unavailable',
        };
      } on FormatException {
        row = {
          'event': 'case',
          'case': sample.id,
          'syntaxValid': false,
          'correct': false,
          'latencyMs': timer.elapsedMilliseconds,
          'reason': 'Malformed native tool result',
          'executedToolCount': 0,
        };
      } catch (_) {
        halted = true;
        row = {
          'event': 'case',
          'case': sample.id,
          'syntaxValid': false,
          'correct': false,
          'latencyMs': timer.elapsedMilliseconds,
          'reason': 'Native inference unavailable or failed',
          'executedToolCount': 0,
        };
      }
      completed++;
      _emit(row);
      if (!mounted) {
        break;
      }
      setState(() => _results.add(row));
      if (halted) break;
    }
    if (!halted && completed == 2) {
      try {
        await _service.finishDiagnostic();
      } catch (_) {
        halted = true;
      }
    }
    _emit({
      'event': 'summary',
      'completed': completed,
      'syntaxValid': valid,
      'correct': correct,
      'plannedCases': 2,
      'stoppedEarly': halted,
      'executedToolCount': 0,
      'scope': 'Synthetic diagnostic only; not unrestricted capability proof',
    });
    if (mounted) {
      setState(() {
        _busy = false;
        _notice = halted
            ? 'Stopped after native failure. No further case was dispatched.'
            : 'Finished: $valid valid / $correct exact correct. No operations executed.';
      });
    }
  }

  Future<void> _release() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await _service.release();
      if (mounted) {
        setState(() => _notice = 'Optional runtime released.');
      }
    } catch (_) {
      if (mounted) {
        setState(() => _notice = 'Release failed; no cancellation is assumed.');
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Isolated LiteRT trial')),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text('Gemma 4 E2B · LiteRT-LM 0.18.0'),
          const Text(
            'Short CPU stability probe: at most two synthetic cases. Development and performance builds are not equivalent. No gateway, database, cloud or automatic tool execution.',
          ),
          const SizedBox(height: 16),
          Text(_notice),
          FilledButton(
            onPressed:
                _busy ||
                    _status?.available != true ||
                    _status?.modelPresent != true
                ? null
                : _run,
            child: const Text('Run synthetic cases'),
          ),
          OutlinedButton(
            onPressed: _busy ? null : _release,
            child: const Text('Release trial runtime'),
          ),
          const Text(
            'No cancel claim: wait for native inference to settle. Timings and RAM are available only when reported numerically. Raw model reasoning is never shown or logged.',
          ),
          for (final row in _results)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Text(jsonEncode(row)),
            ),
        ],
      ),
    ),
  );
}
