import 'package:diagrammer_mobile/domain/operation_proposal.dart';
import 'package:diagrammer_mobile/domain/openapi_validator.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const validator = OpenApiValidator();
  final contract = <String, dynamic>{
    'paths': {
      '/tasks': {
        'post': {
          'requestBody': {
            'required': true,
            'content': {
              'application/json': {
                'schema': {
                  'type': 'object',
                  'required': ['id', 'title'],
                  'additionalProperties': false,
                  'properties': {
                    'id': {'type': 'string', 'format': 'uuid'},
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

  test('rejects unknown routes, fields, and invalid UUIDs', () {
    expect(
      () => validator.validate(
        const OperationProposal(method: 'POST', path: '/unknown', body: {}),
        contract,
      ),
      throwsFormatException,
    );
    expect(
      () => validator.validate(
        const OperationProposal(
          method: 'POST',
          path: '/tasks',
          body: {'id': 'not-a-uuid', 'title': 'Plan', 'extra': true},
        ),
        contract,
      ),
      throwsFormatException,
    );
  });

  test('resolves generated DTO refs and UUID path templates', () {
    final generated = <String, dynamic>{
      'components': {
        'schemas': {
          'TaskDto': {
            'type': 'object',
            'required': ['id', 'title'],
            'properties': {
              'id': {'type': 'string', 'format': 'uuid'},
              'title': {'type': 'string'},
              'note': {'type': 'string', 'nullable': true},
            },
          },
        },
      },
      'paths': {
        '/api/tasks/{id}': {
          'put': {
            'requestBody': {
              'content': {
                'application/json': {
                  'schema': {'\$ref': '#/components/schemas/TaskDto'},
                },
              },
            },
          },
        },
      },
    };
    expect(
      () => validator.validate(
        const OperationProposal(
          method: 'PUT',
          path: '/api/tasks/81b832ec-3ec1-4ec4-a0d9-747c75e2e22c',
          body: {
            'id': '81b832ec-3ec1-4ec4-a0d9-747c75e2e22c',
            'title': 'Valid',
            'extra': true,
          },
        ),
        generated,
      ),
      throwsFormatException,
    );
    expect(
      () => validator.validate(
        const OperationProposal(
          method: 'PUT',
          path: '/api/tasks/not-a-uuid',
          body: {
            'id': '81b832ec-3ec1-4ec4-a0d9-747c75e2e22c',
            'title': 'Valid',
          },
        ),
        generated,
      ),
      throwsFormatException,
    );
    expect(
      () => validator.validate(
        const OperationProposal(
          method: 'PUT',
          path: '/api/tasks/81b832ec-3ec1-4ec4-a0d9-747c75e2e22c',
          body: {
            'id': '81b832ec-3ec1-4ec4-a0d9-747c75e2e22c',
            'title': 'Valid',
            'note': null,
          },
        ),
        generated,
      ),
      returnsNormally,
    );
  });
}
