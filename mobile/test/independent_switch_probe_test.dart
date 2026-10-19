import 'dart:async';

import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/domain/operation_proposal.dart';
import 'package:flutter_test/flutter_test.dart';

import 'workspace_switch_test.dart' as f;

class MarkerGateStore extends f.SwitchStore {
  final started = Completer<void>();
  final release = Completer<void>();
  bool delayClear = false;
  @override
  Future<void> savePending(String scope, String value) async {
    if (delayClear && scope.endsWith('.inspection') && value.isEmpty) {
      started.complete();
      await release.future;
    }
    await super.savePending(scope, value);
  }
}

void main() {
  test(
    'independent late A transport failure leaves B usable and A guarded',
    () async {
      final reply = Completer<GatewayResult>();
      final sent = Completer<void>();
      final api = f.SwitchPlatform()
        ..handler = (id, p) {
          if (id == 'a') {
            sent.complete();
            return reply.future;
          }
          return Future.value(const GatewayResult(status: 200, body: 'B'));
        };
      final store = f.SwitchStore()
        ..pending[f.scopeFor('a')] = f.proposalA.encode()
        ..pending[f.scopeFor('b')] = f.proposalB.encode();
      final c = f.switchController(api, store);
      await c.selectBackend(f.switchSession, f.switchA);
      final old = c.confirm(f.switchSession);
      await sent.future;
      c.leaveBackend();
      await c.selectBackend(f.switchSession, f.switchB);
      reply.completeError(StateError('Disconnected'));
      await old;
      expect(c.error, isNull);
      await c.confirm(f.switchSession);
      expect(c.latestResult!.body, 'B');
      expect(api.calls, [
        (backend: 'a', method: 'POST'),
        (backend: 'b', method: 'GET'),
      ]);
      expect(store.pending[f.scopeFor('a')], f.proposalA.encode());
      final fresh = f.switchController(api, store);
      await fresh.selectBackend(f.switchSession, f.switchA);
      await fresh.confirm(f.switchSession);
      expect(fresh.requiresInspection, isTrue);
      expect(api.calls.length, 2);
    },
  );
  test('independent inspector serializes confirmation and stale inspection preserves guard', () async {
    final response = Completer<GatewayResult>();
    final started = Completer<void>();
    final api = f.SwitchPlatform()
      ..handler = (_, p) {
        started.complete();
        return response.future;
      };
    final store = f.SwitchStore()
      ..pending[f.scopeFor('a')] = f.proposalA.encode()
      ..pending[f.guardFor('a')] = 'required';
    final c = f.switchController(api, store);
    await c.selectBackend(f.switchSession, f.switchA);
    final inspecting = c.inspect(f.switchSession);
    await started.future;
    await c.confirm(f.switchSession);
    c.leaveBackend();
    await c.selectBackend(f.switchSession, f.switchB);
    response.complete(const GatewayResult(status: 200, body: 'A'));
    await inspecting;
    expect(c.latestResult, isNull);
    expect(c.error, isNull);
    expect(store.pending[f.guardFor('a')], 'required');
    expect(store.pending[f.scopeFor('a')], f.proposalA.encode());
    expect(api.calls, [(backend: 'a', method: 'GET')]);
  });
  test(
    'independent delayed inspector marker clear repairs guard after switching',
    () async {
      final api = f.SwitchPlatform();
      final store = MarkerGateStore()
        ..pending[f.scopeFor('a')] = f.proposalA.encode()
        ..pending[f.guardFor('a')] = 'required'
        ..delayClear = true;
      final c = f.switchController(api, store);
      await c.selectBackend(f.switchSession, f.switchA);
      final inspection = c.inspect(f.switchSession);
      await store.started.future;
      await c.selectBackend(f.switchSession, f.switchB);
      await c.selectBackend(f.switchSession, f.switchA);
      await c.confirm(f.switchSession);
      expect(api.calls, [(backend: 'a', method: 'GET')]);
      store.release.complete();
      await inspection;
      expect(c.latestResult, isNull);
      expect(store.pending[f.guardFor('a')], 'required');
      final fresh = f.switchController(api, store);
      await fresh.selectBackend(f.switchSession, f.switchA);
      await fresh.confirm(f.switchSession);
      expect(api.calls.length, 1);
    },
  );
  test(
    'independent legacy pending validation preserves rejected bytes',
    () async {
      const invalid = OperationProposal(
        method: 'POST',
        path: '/api/removed',
        body: {'title': 'A'},
      );
      final api = f.SwitchPlatform();
      final store = f.SwitchStore()
        ..pending[f.scopeFor('a')] = invalid.encode();
      final before = Map<String, String>.of(store.pending);
      final c = f.switchController(api, store);
      await c.selectBackend(f.switchSession, f.switchA);
      expect(c.proposal, isNull);
      expect(c.error, contains('does not match'));
      await c.confirm(f.switchSession);
      expect(store.pending, before);
      expect(api.calls, isEmpty);
    },
  );
}
