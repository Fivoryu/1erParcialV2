import 'package:diagrammer_mobile/domain/agent_contract.dart';
import 'package:diagrammer_mobile/domain/models.dart';

/// OpenAPI contract in the exact shape the platform generator emits
/// (GenerationArtifactBundleBuilder): CRUD routes, /query, /values and
/// /aggregate helper routes, x-display-fields, x-query-fields and
/// x-agent-capabilities. Domain: a school (alumno -> curso).
Map<String, dynamic> colegioContract({bool queryCapable = true}) {
  Map<String, dynamic> crud(String dto) => {
    'get': {'summary': 'List'},
    'post': {
      'requestBody': {
        'required': true,
        'content': {
          'application/json': {
            'schema': {r'$ref': '#/components/schemas/$dto'},
          },
        },
      },
    },
  };
  Map<String, dynamic> item(String dto) => {
    'get': {'summary': 'Read'},
    'put': {
      'requestBody': {
        'content': {
          'application/json': {
            'schema': {r'$ref': '#/components/schemas/$dto'},
          },
        },
      },
    },
    'delete': {'summary': 'Delete'},
  };
  Map<String, dynamic> helpers(List<String> fields) => {
    'query': {
      'get': {
        'parameters': [
          {'name': 'filter', 'in': 'query', 'required': false, 'style': 'form', 'explode': true,
            'schema': {'type': 'array', 'items': {'type': 'string'}}},
          {'name': 'sort', 'in': 'query', 'required': false, 'schema': {'type': 'string'}},
          {'name': 'page', 'in': 'query', 'required': false, 'schema': {'type': 'integer', 'minimum': 0}},
          {'name': 'size', 'in': 'query', 'required': false,
            'schema': {'type': 'integer', 'minimum': 1, 'maximum': 200}},
        ],
      },
    },
    'values': {
      'get': {
        'parameters': [
          {'name': 'field', 'in': 'query', 'required': true, 'schema': {'type': 'string', 'enum': fields}},
          {'name': 'filter', 'in': 'query', 'required': false, 'style': 'form', 'explode': true,
            'schema': {'type': 'array', 'items': {'type': 'string'}}},
        ],
      },
    },
    'aggregate': {
      'get': {
        'parameters': [
          {'name': 'op', 'in': 'query', 'required': true,
            'schema': {'type': 'string', 'enum': ['count', 'sum', 'avg', 'min', 'max']}},
          {'name': 'field', 'in': 'query', 'required': false, 'schema': {'type': 'string', 'enum': fields}},
          {'name': 'groupBy', 'in': 'query', 'required': false, 'schema': {'type': 'string', 'enum': fields}},
          {'name': 'filter', 'in': 'query', 'required': false, 'style': 'form', 'explode': true,
            'schema': {'type': 'array', 'items': {'type': 'string'}}},
        ],
      },
    },
  };
  const alumnoFields = ['id', 'nombre', 'apellido', 'edad', 'cursoId', 'curso.id', 'curso.grado', 'curso.seccion', 'curso.turno'];
  const cursoFields = ['id', 'grado', 'seccion', 'turno'];
  final paths = <String, dynamic>{
    '/api/alumno': crud('AlumnoDto'),
    '/api/alumno/{id}': item('AlumnoDto'),
    '/api/curso': crud('CursoDto'),
    '/api/curso/{id}': item('CursoDto'),
    if (queryCapable)
      for (final entry in helpers(alumnoFields).entries) '/api/alumno/${entry.key}': entry.value,
    if (queryCapable)
      for (final entry in helpers(cursoFields).entries) '/api/curso/${entry.key}': entry.value,
  };
  return {
    'openapi': '3.0.3',
    'paths': paths,
    if (queryCapable)
      'x-agent-capabilities': {
        'version': 1,
        'filterSyntax': 'field:operator:value',
        'operators': ['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'contains', 'in', 'is_null'],
        'aggregates': ['count', 'sum', 'avg', 'min', 'max'],
        'defaultPageSize': 50,
        'maxPageSize': 200,
        'maxValues': 200,
      },
    'components': {
      'schemas': {
        'AlumnoDto': {
          'type': 'object',
          'properties': {
            'id': {'type': 'string', 'format': 'uuid'},
            'nombre': {'type': 'string'},
            'apellido': {'type': 'string'},
            'edad': {'type': 'integer', 'nullable': true},
            'cursoId': {'type': 'string', 'format': 'uuid'},
          },
          'required': ['nombre', 'apellido', 'cursoId'],
          if (queryCapable) 'x-display-fields': ['nombre', 'apellido'],
          if (queryCapable)
            'x-query-fields': [
              {'name': 'id', 'kind': 'UUID'},
              {'name': 'nombre', 'kind': 'STRING'},
              {'name': 'apellido', 'kind': 'STRING'},
              {'name': 'edad', 'kind': 'INTEGER'},
              {'name': 'cursoId', 'kind': 'UUID', 'references': 'curso'},
              {'name': 'curso.grado', 'kind': 'INTEGER'},
              {'name': 'curso.seccion', 'kind': 'STRING'},
              {'name': 'curso.turno', 'kind': 'ENUM', 'enum': ['MANANA', 'TARDE']},
            ],
          'x-uml-relationships': [
            {'field': 'curso', 'otherType': 'Curso', 'owning': true},
          ],
        },
        'CursoDto': {
          'type': 'object',
          'properties': {
            'id': {'type': 'string', 'format': 'uuid'},
            'grado': {'type': 'integer'},
            'seccion': {'type': 'string'},
            'turno': {'type': 'string', 'enum': ['MANANA', 'TARDE'], 'x-enum-name': 'Turno'},
          },
          'required': ['grado', 'seccion', 'turno'],
          if (queryCapable) 'x-display-fields': ['seccion'],
          if (queryCapable)
            'x-query-fields': [
              {'name': 'id', 'kind': 'UUID'},
              {'name': 'grado', 'kind': 'INTEGER'},
              {'name': 'seccion', 'kind': 'STRING'},
              {'name': 'turno', 'kind': 'ENUM', 'enum': ['MANANA', 'TARDE']},
            ],
        },
      },
    },
  };
}

/// The school contract wrapped as the agent sees one connected backend.
AgentContract colegio() => AgentContract(
  BackendContract(deploymentId: 'd', runId: 'r', contractHash: 'h', openApi: colegioContract()),
);
