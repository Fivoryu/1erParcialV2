import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/material.dart';

import '../tool/litert_trial.dart' as trial;

import 'package:flutter_test/flutter_test.dart';
import 'package:diagrammer_mobile/data/litert_trial_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('trial-test');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));
  testWidgets('trial stops after first structural native failure', (
    tester,
  ) async {
    const native = MethodChannel('diagrammer/litert_trial');
    var runs = 0;
    messenger.setMockMethodCallHandler(native, (call) async {
      if (call.method == 'status') {
        return {
          'available': true,
          'runtime': 'LiteRT-LM',
          'version': '0.18.0',
          'modelPresent': true,
        };
      }
      if (call.method == 'inspectPreviousExit') {
        return {'availability': 'none'};
      }
      if (call.method == 'beginDiagnostic') {
        return {'runId': '11111111-1111-4111-8111-111111111111', 'metrics': {}};
      }
      if (call.method == 'runCase') {
        runs++;
        throw PlatformException(
          code: 'TRIAL_FAILED',
          message: 'Synthetic initialization failure',
        );
      }
      return null;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(native, null));
    await tester.pumpWidget(const MaterialApp(home: trial.TrialScreen()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Run synthetic cases'));
    await tester.pumpAndSettle();
    expect(runs, 1);
    expect(find.textContaining('Stopped after native failure'), findsOneWidget);
  });
  test('missing native plugin is unavailable, not success', () async {
    final status = await LiteRtTrialService(channel: channel).status();
    expect(status.available, false);
    expect(status.modelPresent, false);
  });
  test(
    'strict response accepts display text and never executes calls',
    () async {
      final calls = <String>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call.method);
        return {
          'text': 'literal',
          'toolCalls': [
            {
              'name': 'read',
              'arguments': {'entity': 'Note'},
            },
          ],
          'metrics': {'inferenceMs': 12},
        };
      });
      final result = await LiteRtTrialService(channel: channel).runCase(
        request: 'Lee Note',
        entities: ['Note'],
        schemas: const {
          'Note': {'text': 'string'},
        },
      );
      expect(result.text, 'literal');
      expect(result.toolCalls.single.name, 'read');
      expect(calls, ['runCase']);
    },
  );
  test('invalid requests dispatch nothing', () async {
    var calls = 0;
    messenger.setMockMethodCallHandler(channel, (_) async {
      calls++;
      return null;
    });
    final service = LiteRtTrialService(channel: channel);
    await expectLater(
      service.runCase(
        request: '',
        entities: ['Note'],
        schemas: const {
          'Note': {'text': 'string'},
        },
      ),
      throwsArgumentError,
    );
    await expectLater(
      service.runCase(
        request: 'x',
        entities: List.generate(9, (i) => 'E$i'),
        schemas: const {},
      ),
      throwsArgumentError,
    );
    expect(calls, 0);
  });
  test('malformed calls and metrics fail closed', () async {
    final bad = [
      {
        'name': 'DELETE',
        'arguments': {'entity': 'Note'},
      },
      {
        'name': 'read',
        'arguments': {'entity': 'Foreign'},
      },
      {
        'name': 'read',
        'arguments': {'entity': 'Note', 'url': 'https://example.com'},
      },
      {
        'name': 'calculate',
        'arguments': {
          'op': 'sum',
          'values': [double.infinity],
        },
      },
      {
        'name': 'create',
        'arguments': {'entity': 'Note', 'values': 'not json'},
      },
    ];
    for (final value in bad) {
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => {
          'text': '',
          'toolCalls': [value],
          'metrics': {},
        },
      );
      await expectLater(
        LiteRtTrialService(channel: channel).runCase(
          request: 'x',
          entities: ['Note'],
          schemas: const {
            'Note': {'text': 'string'},
          },
        ),
        throwsFormatException,
      );
    }
    messenger.setMockMethodCallHandler(
      channel,
      (_) async => {
        'text': '',
        'toolCalls': [],
        'metrics': {'ttftMs': -1},
      },
    );
    await expectLater(
      LiteRtTrialService(channel: channel).runCase(
        request: 'x',
        entities: ['Note'],
        schemas: const {
          'Note': {'text': 'string'},
        },
      ),
      throwsFormatException,
    );
  });
  test(
    'duplicate and release rejected until actual native call settles',
    () async {
      final pending = Completer<Object?>();
      final calls = <String>[];
      messenger.setMockMethodCallHandler(channel, (call) {
        calls.add(call.method);
        return call.method == 'runCase' ? pending.future : Future.value(null);
      });
      final service = LiteRtTrialService(channel: channel);
      final first = service.runCase(
        request: 'x',
        entities: ['Note'],
        schemas: const {
          'Note': {'text': 'string'},
        },
      );
      await expectLater(
        service.runCase(
          request: 'x',
          entities: ['Note'],
          schemas: const {
            'Note': {'text': 'string'},
          },
        ),
        throwsStateError,
      );
      await expectLater(service.release(), throwsStateError);
      expect(service.inFlight, true);
      pending.complete({'text': '', 'toolCalls': [], 'metrics': {}});
      await first;
      await service.release();
      expect(calls, ['runCase', 'release']);
      expect(service.inFlight, false);
    },
  );
  test('release owns lifecycle until native release settles', () async {
    final pending = Completer<Object?>();
    messenger.setMockMethodCallHandler(channel, (_) => pending.future);
    final service = LiteRtTrialService(channel: channel);
    final release = service.release();
    expect(service.inFlight, true);
    await expectLater(
      service.runCase(
        request: 'x',
        entities: ['Note'],
        schemas: const {
          'Note': {'text': 'string'},
        },
      ),
      throwsStateError,
    );
    pending.complete(null);
    await release;
    expect(service.inFlight, false);
  });
  test('natural corpus contains no tool protocol instructions', () {
    expect(liteRtTrialCases.length, 8);
    for (final sample in liteRtTrialCases) {
      expect(
        RegExp(r'answer|clarify|calculate|values|usando|texto exacto|"|\{|\}')
            .hasMatch(sample.request),
        false,
      );
    }
  });
  test(
    'grader accepts greeting text and clarification without fixed phrase',
    () {
      expect(
        liteRtTrialCases.first.matches(
          const LiteRtTrialResult('¡Hola!', [], {}),
        ),
        true,
      );
      expect(
        liteRtTrialCases.last.matches(
          const LiteRtTrialResult('', [
            LiteRtTrialCall('clarify', {'text': '¿Cuál registro?'}),
          ], {}),
        ),
        true,
      );
      expect(
        liteRtTrialCases.last.matches(
          const LiteRtTrialResult('Which?', [], {}),
        ),
        false,
      );
    },
  );
  test('grader compares decoded object values and rejects duplicate calls', () {
    final sample = liteRtTrialCases.firstWhere(
      (c) => c.id == 'create-satellite',
    );
    const call = LiteRtTrialCall('create', {
      'entity': 'Satellite',
      'values': '{ "altitudeKm": 500, "name": "Orion" }',
    });
    expect(sample.matches(const LiteRtTrialResult('', [call], {})), true);
    expect(
      sample.matches(const LiteRtTrialResult('', [call, call], {})),
      false,
    );
    expect(
      sample.matches(
        const LiteRtTrialResult('', [
          LiteRtTrialCall('create', {'entity': 'Satellite', 'values': '[]'}),
        ], {}),
      ),
      false,
    );
  });
  test(
    'schema grounding rejects invalid shapes before native dispatch',
    () async {
      var calls = 0;
      messenger.setMockMethodCallHandler(channel, (_) async {
        calls++;
        return {'text': '', 'toolCalls': [], 'metrics': {}};
      });
      final service = LiteRtTrialService(channel: channel);
      final bad = <Map<String, Map<String, String>>>[
        {},
        {
          'Foreign': {'text': 'string'},
        },
        {
          'Note': {'text': 'object'},
        },
        {
          'Note': {for (var i = 0; i < 33; i++) 'field$i': 'string'},
        },
        {
          'Note': {'abcdefghijklmnopqrstuvwxyz1234567': 'string'},
        },
      ];
      for (final schemas in bad) {
        await expectLater(
          service.runCase(request: 'x', entities: ['Note'], schemas: schemas),
          throwsArgumentError,
        );
      }
      expect(calls, 0);
      Map<dynamic, dynamic>? sent;
      messenger.setMockMethodCallHandler(channel, (call) async {
        sent = call.arguments as Map;
        return {'text': '', 'toolCalls': [], 'metrics': {}};
      });
      await service.runCase(
        request: 'Mostrame Note',
        entities: ['Note'],
        schemas: const {
          'Note': {'text': 'string'},
        },
      );
      expect(sent, {
        'request': 'Mostrame Note',
        'entities': ['Note'],
        'schemas': {
          'Note': {'text': 'string'},
        },
      });
    },
  );
  test('diagnostic channels reject malformed exit fields', () async {
    final service = LiteRtTrialService(channel: channel);
    for (final raw in [
      {'availability': 'available', 'reason': 1},
      {'availability': 'none', 'reason': 1},
      {'availability': 'unavailable', 'description': 'private'},
      {
        'availability': 'available',
        'reason': 1,
        'status': 0,
        'timestampMs': 1,
        'pssKb': -1,
        'rssKb': 1,
      },
    ]) {
      messenger.setMockMethodCallHandler(channel, (_) async => raw);
      await expectLater(service.inspectPreviousExit(), throwsFormatException);
    }
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.arguments, isNull);
      return call.method == 'beginDiagnostic'
          ? {
              'runId': '11111111-1111-4111-8111-111111111111',
              'metrics': {
                'pssKb': 5,
                'rssKb': 6,
                'wallClockMs': 1,
                'elapsedRealtimeMs': 2,
                'availableRamKb': 3,
                'totalRamKb': 4,
              },
            }
          : {
              'availability': 'available',
              'reason': 1,
              'status': 0,
              'timestampMs': 1,
              'pssKb': 2,
              'rssKb': 3,
            };
    });
    expect((await service.beginDiagnostic()).metrics['wallClockMs'], 1);
    expect((await service.inspectPreviousExit()).availability, 'available');
  });
  testWidgets(
    'startup inspects once without inference and explicit run has two cases',
    (tester) async {
      const native = MethodChannel('diagrammer/litert_trial');
      final calls = <String>[];
      messenger.setMockMethodCallHandler(native, (call) async {
        calls.add(call.method);
        switch (call.method) {
          case 'status':
            return {
              'available': true,
              'modelPresent': true,
              'runtime': 'LiteRT-LM',
              'version': '0.18.0',
            };
          case 'inspectPreviousExit':
            return {'availability': 'none'};
          case 'beginDiagnostic':
            return {
              'runId': '11111111-1111-4111-8111-111111111111',
              'metrics': {},
            };
          case 'runCase':
            return {'text': 'Hello', 'toolCalls': [], 'metrics': {}};
          default:
            return null;
        }
      });
      await tester.pumpWidget(const MaterialApp(home: trial.TrialScreen()));
      await tester.pumpAndSettle();
      expect(calls, ['status', 'inspectPreviousExit']);
      await tester.tap(find.text('Run synthetic cases'));
      await tester.pumpAndSettle();
      expect(calls.where((c) => c == 'runCase').length, 2);
      expect(calls.last, 'finishDiagnostic');
      messenger.setMockMethodCallHandler(native, null);
    },
  );
  testWidgets(
    'begin persistence and structural inference failures stop further dispatch',
    (tester) async {
      const native = MethodChannel('diagrammer/litert_trial');
      for (final failBegin in [true, false]) {
        final calls = <String>[];
        messenger.setMockMethodCallHandler(native, (call) async {
          calls.add(call.method);
          if (call.method == 'status') {
            return {
              'available': true,
              'modelPresent': true,
              'runtime': 'LiteRT-LM',
              'version': '0.18.0',
            };
          }
          if (call.method == 'inspectPreviousExit') {
            return {'availability': 'none'};
          }
          if (call.method == 'beginDiagnostic') {
            if (failBegin) {
              throw PlatformException(code: 'PERSIST_FAILED');
            }
            return {
              'runId': '11111111-1111-4111-8111-111111111111',
              'metrics': {},
            };
          }
          if (call.method == 'runCase') {
            throw PlatformException(code: 'NATIVE_FAILED');
          }
          return null;
        });
        await tester.pumpWidget(
          MaterialApp(home: trial.TrialScreen(key: ValueKey(failBegin))),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.text('Run synthetic cases'));
        await tester.pumpAndSettle();
        expect(calls.where((c) => c == 'runCase').length, failBegin ? 0 : 1);
        expect(calls.contains('finishDiagnostic'), false);
      }
      messenger.setMockMethodCallHandler(native, null);
    },
  );
  test('runCase preserves finite phase memory separate from peaks', () async {
    messenger.setMockMethodCallHandler(
      channel,
      (_) async => {
        'text': '',
        'toolCalls': [],
        'metrics': {
          'pssKb': 10,
          'rssKb': 20,
          'peakPssKb': 30,
          'peakRssKb': 40,
          'wallClockMs': 50,
          'elapsedRealtimeMs': 60,
          'availableRamKb': 70,
          'totalRamKb': 80,
        },
      },
    );
    final result = await LiteRtTrialService(channel: channel).runCase(
      request: 'Hola',
      entities: ['Note'],
      schemas: const {
        'Note': {'text': 'string'},
      },
    );
    expect(result.metrics, {
      'pssKb': 10,
      'rssKb': 20,
      'peakPssKb': 30,
      'peakRssKb': 40,
      'wallClockMs': 50,
      'elapsedRealtimeMs': 60,
      'availableRamKb': 70,
      'totalRamKb': 80,
    });
  });
}
