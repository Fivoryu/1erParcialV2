import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../domain/models.dart';
import '../domain/operation_proposal.dart';

class PlatformService {
  PlatformService({
    required this._baseUri,
    http.Client? client,
    this._timeout = const Duration(seconds: 15),
  }) : _client = client ?? http.Client() {
    if (_baseUri.scheme != 'https' &&
        !const bool.fromEnvironment(
          'ALLOW_INSECURE_HTTP',
          defaultValue: false,
        )) {
      throw ArgumentError(
        'La URL de la plataforma debe usar HTTPS. Usa ALLOW_INSECURE_HTTP solo para desarrollo local.',
      );
    }
  }
  final Duration _timeout;
  static const _maxBytes = 1024 * 1024;
  final Uri _baseUri;
  final http.Client _client;

  Future<UserSession> login(String username, String password) async =>
      _sessionFrom(
        await _send(
          'POST',
          '/auth/login',
          headers: _jsonHeaders,
          body: jsonEncode({'username': username, 'password': password}),
        ),
      );
  Future<AppUser> me(String token) async => _user(
    _object(await _send('GET', '/auth/me', headers: _authHeaders(token))),
  );
  Future<void> logout(String token) async {
    _object(await _send('POST', '/auth/logout', headers: _authHeaders(token)));
  }

  Future<List<PlatformProject>> projects(String token) async =>
      _array(await _send('GET', '/projects', headers: _authHeaders(token)))
          .map((value) {
            final item = Map<String, dynamic>.from(value as Map);
            return PlatformProject(
              id: item['id'] as String,
              name: item['name'] as String,
            );
          })
          .toList(growable: false);
  Future<List<MergeRequestSummary>> mergeRequests(
    String token,
    String projectId,
  ) async => _array(
    await _send(
      'GET',
      '/projects/${Uri.encodeComponent(projectId)}/vcs/merge-requests',
      headers: _authHeaders(token),
    ),
  ).map((value) {
    final item = Map<String, dynamic>.from(value as Map);
    return MergeRequestSummary(
      id: item['mergeRequestId'] as String,
      title: item['title'] as String,
      state: item['state'] as String,
      sourceBranch: item['sourceBranch'] as String,
      targetBranch: item['targetBranch'] as String,
    );
  }).toList(growable: false);

  Future<List<GeneratedBackend>> backends(
    String token,
    String projectId,
  ) async =>
      _array(
            await _send(
              'GET',
              '/projects/$projectId/mobile/backends',
              headers: _authHeaders(token),
            ),
          )
          .map((value) {
            final item = Map<String, dynamic>.from(value as Map);
            return GeneratedBackend(
              deploymentId: item['deploymentId'] as String,
              name: item['name'] as String,
              state: item['state'] as String,
              runId: item['runId'] as String,
              contractHash: item['contractHash'] as String,
            );
          })
          .toList(growable: false);
  Future<BackendContract> contract(
    String token,
    String projectId,
    String deploymentId,
  ) async {
    final json = _object(
      await _send(
        'GET',
        '/projects/$projectId/mobile/backends/$deploymentId/contract',
        headers: _authHeaders(token),
      ),
    );
    return BackendContract(
      deploymentId: json['deploymentId'] as String,
      runId: json['runId'] as String,
      contractHash: json['contractHash'] as String,
      openApi: Map<String, dynamic>.from(json['openApi'] as Map),
    );
  }

  /// Private history only: this interface never forwards generated operations.
  Future<Map<String, dynamic>> chatRequest(
    String token,
    String projectId,
    String deploymentId, {
    String? conversationId,
    Map<String, dynamic>? payload,
    int? afterSequence,
    String? cursor,
  }) async {
    String segment(String value) => Uri.encodeComponent(value);
    final suffix = conversationId == null
        ? ''
        : '/${segment(conversationId)}/messages';
    final query = afterSequence == null
        ? (cursor == null
              ? '?limit=100'
              : '?cursor=${segment(cursor)}&limit=100')
        : '?afterSequence=$afterSequence&limit=100';
    return _object(
      await _send(
        payload == null ? 'GET' : 'POST',
        '/projects/${segment(projectId)}/mobile/backends/${segment(deploymentId)}/chats$suffix$query',
        headers: _authHeaders(token),
        body: payload == null ? null : jsonEncode(payload),
      ),
    );
  }

  bool matchesChatPlatform(String platform) => Uri.parse(platform) == _baseUri;

  Future<Map<String, dynamic>> agentOperation(
    String token,
    String project,
    String deployment,
    Map<String, dynamic> payload,
  ) async => _object(
    await _send(
      'POST',
      '/projects/${Uri.encodeComponent(project)}/mobile/backends/${Uri.encodeComponent(deployment)}/agent/operations',
      headers: _authHeaders(token),
      body: jsonEncode(payload),
    ),
  );

  Future<Map<String, dynamic>> agentReceipt(
    String token,
    String project,
    String deployment,
    String operationId,
    String contractHash,
  ) async => _object(
    await _send(
      'GET',
      '/projects/${Uri.encodeComponent(project)}/mobile/backends/${Uri.encodeComponent(deployment)}/agent/operations/${Uri.encodeComponent(operationId)}?contractHash=${Uri.encodeComponent(contractHash)}',
      headers: _authHeaders(token),
    ),
  );

  Future<GatewayResult> execute(
    String token,
    String projectId,
    String deploymentId,
    String contractHash,
    OperationProposal proposal,
  ) async {
    final response = await _send(
      'POST',
      '/projects/$projectId/mobile/backends/$deploymentId/operations',
      headers: _authHeaders(token),
      body: jsonEncode({
        'contractHash': contractHash,
        'method': proposal.method,
        'path': proposal.path,
        'body': proposal.body,
        'confirmed': true,
        if (proposal.query.isNotEmpty)
          'query': [for (final parameter in proposal.query) parameter.toJson()],
      }),
    );
    if (response.statusCode >= 400) {
      dynamic value;
      try {
        value = jsonDecode(response.body);
      } on FormatException {
        value = response.body;
      }
      if (value is! Map || value['status'] is! int) {
        return GatewayResult(status: response.statusCode, body: value);
      }
    }
    final envelope = _jsonObject(response);
    final status = envelope['status'];
    if (status is! int) {
      throw const FormatException(
        'La puerta de enlace devolvió una respuesta de operación no válida.',
      );
    }
    return GatewayResult(status: status, body: envelope['body']);
  }

  Future<http.Response> _send(
    String method,
    String path, {
    required Map<String, String> headers,
    String? body,
  }) async {
    final request = http.Request(method, _baseUri.resolve(path))
      ..followRedirects = false
      ..maxRedirects = 0
      ..headers.addAll(headers);
    if (body != null) request.body = body;
    try {
      return await (() async {
        final streamed = await _client.send(request);
        final bytes = await streamed.stream.fold<List<int>>(<int>[], (
          all,
          chunk,
        ) {
          if (all.length + chunk.length > _maxBytes) {
            throw const PlatformTransportException(
              'La respuesta de la plataforma supera 1 MiB.',
            );
          }
          all.addAll(chunk);
          return all;
        });
        if (streamed.isRedirect) {
          throw const PlatformTransportException(
            'No se permiten redirecciones de la plataforma.',
          );
        }
        return http.Response.bytes(
          bytes,
          streamed.statusCode,
          headers: streamed.headers,
          request: streamed.request,
        );
      })().timeout(_timeout);
    } on TimeoutException {
      throw const PlatformTransportException(
        'La solicitud a la plataforma agotó el tiempo de espera. Revisa los datos antes de volver a intentar una escritura.',
      );
    }
  }

  Map<String, String> get _jsonHeaders => const {
    'content-type': 'application/json',
  };
  Map<String, String> _authHeaders(String token) => {
    ..._jsonHeaders,
    'authorization': 'Bearer $token',
  };
  UserSession _sessionFrom(http.Response response) {
    final json = _object(response);
    return UserSession(
      token: json['token'] as String,
      expiresAt: DateTime.parse(json['expiresAt'] as String),
      user: _user(Map<String, dynamic>.from(json['user'] as Map)),
    );
  }

  AppUser _user(Map<String, dynamic> json) => AppUser(
    id: json['id'] as String,
    username: json['username'] as String,
    displayName: json['displayName'] as String,
    email: json['email'] as String?,
  );
  Map<String, dynamic> _jsonObject(http.Response response) {
    final value = jsonDecode(response.body);
    if (value is! Map) {
      throw const FormatException('La plataforma devolvió una respuesta no válida.');
    }
    return Map<String, dynamic>.from(value);
  }

  Map<String, dynamic> _object(http.Response response) {
    if (response.statusCode >= 400) throw PlatformException.from(response);
    return _jsonObject(response);
  }

  List<dynamic> _array(http.Response response) {
    if (response.statusCode >= 400) throw PlatformException.from(response);
    final value = jsonDecode(response.body);
    if (value is! List) {
      throw const FormatException('La plataforma devolvió una lista no válida.');
    }
    return value;
  }
}

class PlatformTransportException implements Exception {
  const PlatformTransportException(this.message);
  final String message;
  @override
  String toString() => message;
}

class PlatformException implements Exception {
  const PlatformException(this.status, this.message);
  final int status;
  final String message;
  factory PlatformException.from(http.Response response) => PlatformException(
    response.statusCode,
    'La solicitud a la plataforma falló (${response.statusCode}).',
  );
  bool get sessionExpired => status == 401;
}
