import 'dart:convert';

import 'package:diagrammer_mobile/data/chat_store.dart';
import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/domain/agent_models.dart';
import 'package:diagrammer_mobile/domain/agent_runner.dart';
import 'package:diagrammer_mobile/domain/chat_models.dart';
import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/domain/operation_proposal.dart';
import 'package:diagrammer_mobile/presentation/chat_controller.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'fixtures/colegio_contract.dart';
import 'fixtures/fake_backend.dart';

AgentRunner runnerReturning(List<Map<String, dynamic>> decisions, FakeBackend backend) => AgentRunner(
  contract: colegio(),
  model: (_) async => jsonEncode(decisions.removeAt(0)),
  gateway: backend.call,
  scopeGuard: () => true,
);

void main() {
  test('deleting by name previews the single match by its label', () async {
    final result = await runnerReturning([
      {'tag': 'delete_preview', 'entity': 'alumno', 'match': [{'field': 'nombre', 'value': 'beto'}]},
    ], colegioData()).run('borrá a Beto');

    expect(result.status, AgentRunStatus.deletePending);
    expect(result.deletePreview!.path, '/api/alumno/aaaaaaaa-0000-4000-8000-000000000002');
    expect(result.deleteLabels, ['Beto Rios']);
    expect(result.text, 'Confirmá que querés borrar: Beto Rios.');
  });

  test('several matches ask which one and show them with their refs', () async {
    final result = await runnerReturning([
      {'tag': 'delete_preview', 'entity': 'alumno', 'match': [{'field': 'curso.seccion', 'value': 'A'}]},
    ], colegioData()).run('borrá el alumno del A');

    expect(result.status, AgentRunStatus.clarification);
    expect(result.text, startsWith('Encontré 2 registros de alumno. ¿Cuál querés borrar?'));
    expect(result.text, contains('| 1 | Ana | Paz | A | 6 |'));
    expect(result.deletePreview, isNull);
  });

  test('"all" previews every match together, bounded by the bulk cap', () async {
    final all = await runnerReturning([
      {'tag': 'delete_preview', 'entity': 'alumno', 'match': [{'field': 'curso.seccion', 'value': 'A'}], 'all': true},
    ], colegioData()).run('borrá todos los alumnos del A');
    expect(all.status, AgentRunStatus.deletePending);
    expect(all.deletePreviews.map((p) => p.path), [
      '/api/alumno/aaaaaaaa-0000-4000-8000-000000000001',
      '/api/alumno/aaaaaaaa-0000-4000-8000-000000000002',
    ]);
    expect(all.deleteLabels, ['Ana Paz', 'Beto Rios']);

    final many = FakeBackend({
      ...colegioData().tables,
      'alumno': [
        for (var i = 0; i < 25; i++)
          {'id': 'aaaaaaaa-0000-4000-8000-${'$i'.padLeft(12, '0')}', 'nombre': 'N$i', 'apellido': 'X',
            'cursoId': cursoMananaA},
      ],
    });
    final capped = await runnerReturning([
      {'tag': 'delete_preview', 'entity': 'alumno', 'match': [{'field': 'curso.seccion', 'value': 'A'}], 'all': true},
    ], many).run('borrá todos');
    expect(capped.status, AgentRunStatus.clarification);
    expect(capped.text, contains('Son 25 registros de alumno; por seguridad borro como máximo 20'));
  });

  test('generated backend errors are translated for the chat', () {
    expect(BackendRejection.from(400, {'code': 'VALIDATION_FAILED', 'detail': 'nombre: must not be null'}).message,
        'Faltan datos o hay valores inválidos: nombre: must not be null.');
    expect(BackendRejection.from(400, {'code': 'INVALID_REFERENCE'}).message, 'El registro relacionado no existe.');
    expect(BackendRejection.from(400, {'code': 'DATA_CONSTRAINT_VIOLATION', 'detail': 'duplicate key value violates'}).message,
        'No se pudo: el registro está duplicado o lo usan otros registros.');
    expect(BackendRejection.from(404, null).message, 'Ese registro ya no existe.');
    expect(BackendRejection.from(500, 'boom').message, 'El backend rechazó la operación (500).');
  });

  test('one confirmation deletes every previewed record and reports them by name', () async {
    final backend = colegioData();
    final scope = ChatScope(
      platform: 'https://example.test',
      userId: 'u',
      projectId: 'p',
      deploymentId: 'd',
      contractHash: 'h',
    );
    final store = MemoryChatStore();
    final now = DateTime.utc(2026);
    final conversation = LocalConversation(id: 'c', scope: scope, title: 'T', createdAt: now, updatedAt: now);
    await store.saveConversation(conversation);
    final deleted = <String>[];
    final api = PlatformService(
      baseUri: Uri.parse(scope.platform),
      client: MockClient((request) async {
        final path = request.url.path;
        if (path.endsWith('/operations') && path.contains('/agent/')) {
          final payload = jsonDecode(request.body) as Map<String, dynamic>;
          final token = payload['confirmationToken'];
          if (token == null) {
            return http.Response(jsonEncode({
              ...payload,
              'state': 'awaiting_confirmation',
              'confirmationToken': 't-${payload['operationId']}',
            }), 200);
          }
          deleted.add(payload['path'] as String);
          return http.Response(jsonEncode({...payload, 'state': 'completed', 'status': 204, 'result': null}), 200);
        }
        if (path.endsWith('/operations')) {
          final payload = jsonDecode(request.body) as Map<String, dynamic>;
          final result = await backend.call(OperationProposal(
            method: payload['method'] as String,
            path: payload['path'] as String,
            body: payload['body'],
            query: [
              for (final p in (payload['query'] as List? ?? const []))
                QueryParameter((p as Map)['name'] as String, p['value'] as String),
            ],
          ));
          return http.Response(jsonEncode({'status': 200, 'body': result.data}), 200);
        }
        return http.Response('{"message":"history unavailable"}', 503);
      }),
    );
    final controller = ChatController(
      store: store,
      service: api,
      model: (_) async => jsonEncode({
        'tag': 'delete_preview',
        'entity': 'alumno',
        'match': [{'field': 'curso.seccion', 'value': 'A'}],
        'all': true,
      }),
    );
    await controller.bind(scope, 'fixture',
        contract: BackendContract(deploymentId: 'd', runId: 'r', contractHash: 'h', openApi: colegioContract()));
    await controller.open(conversation);

    await controller.sendMessage('borrá todos los alumnos del A');
    expect(controller.deletePreviews, hasLength(2));
    expect(controller.deleteLabels, ['Ana Paz', 'Beto Rios']);
    expect(deleted, isEmpty, reason: 'nothing is deleted before the explicit confirmation');

    await controller.confirmDelete();

    expect(deleted, [
      '/api/alumno/aaaaaaaa-0000-4000-8000-000000000001',
      '/api/alumno/aaaaaaaa-0000-4000-8000-000000000002',
    ]);
    expect(controller.messages.last.content, 'Eliminados 2 de 2 registros: Ana Paz, Beto Rios.');
    expect(controller.deletePreviews, isEmpty);
  });
}
