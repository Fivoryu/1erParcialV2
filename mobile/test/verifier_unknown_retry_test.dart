import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/data/secure_store.dart';
import 'package:diagrammer_mobile/domain/models.dart';
import 'package:diagrammer_mobile/domain/operation_proposal.dart';
import 'package:diagrammer_mobile/presentation/workspace_controller.dart';
import 'package:flutter_test/flutter_test.dart';

class _Store implements SecureStore {
  final pending = <String, String>{};
  @override
  Future<void> clearAll() async {}
  @override
  Future<UserSession?> readSession() async => null;
  @override
  Future<String?> readPending(String scope) async => pending[scope];
  @override
  Future<void> savePending(String scope, String value) async =>
      pending[scope] = value;
  @override
  Future<void> saveSession(UserSession session) async {}
}

class _AmbiguousPlatform extends PlatformService {
  _AmbiguousPlatform() : super(baseUri: Uri.parse('https://example.test'));
  var calls = 0;
  @override
  Future<GatewayResult> execute(
    String token,
    String projectId,
    String deploymentId,
    String contractHash,
    OperationProposal proposal,
  ) async {
    calls++;
    throw StateError('connection lost after send');
  }
}

void main() {
  test(
    'unknown write outcome blocks a second confirmation until inspection',
    () async {
      final api = _AmbiguousPlatform();
      final controller = WorkspaceController(selectedRuntime: LocalRuntime.qwen, service: api, store: _Store())
        ..project = const PlatformProject(id: 'p', name: 'Project')
        ..backend = const GeneratedBackend(
          deploymentId: 'd',
          name: 'Backend',
          state: 'UP',
          runId: 'r',
          contractHash: 'h',
        )
        ..contract = BackendContract(
          deploymentId: 'd',
          runId: 'r',
          contractHash: 'h',
          openApi: {
            'paths': {
              '/orders': {
                'post': {
                  'requestBody': {
                    'content': {
                      'application/json': {
                        'schema': {
                          'type': 'object',
                          'required': ['quantity'],
                          'properties': {
                            'quantity': {'type': 'integer'},
                          },
                        },
                      },
                    },
                  },
                },
              },
            },
          },
        )
        ..setProposal(
          const OperationProposal(
            method: 'POST',
            path: '/orders',
            body: {'quantity': 1},
          ),
        );
      final session = UserSession(
        token: 't',
        expiresAt: DateTime.utc(2099),
        user: const AppUser(
          id: 'u',
          username: 'u',
          displayName: 'U',
          email: null,
        ),
      );

      await controller.confirm(session);
      await controller.confirm(session);

      expect(api.calls, 1);
      expect(controller.requiresInspection, isTrue);
    },
  );
}
