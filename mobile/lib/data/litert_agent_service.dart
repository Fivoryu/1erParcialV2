import 'package:flutter/services.dart';

import '../domain/agent_models.dart';

class LiteRtAgentStatus {
  const LiteRtAgentStatus({
    required this.available,
    required this.modelPresent,
    required this.loaded,
    required this.message,
    this.backend,
  });
  final bool available, modelPresent, loaded;
  final String message;

  /// Native compute backend in use ("gpu" or "cpu"); null when unknown.
  final String? backend;
}

/// Thrown when a native generation exceeded its bounded wait.
class LiteRtTimeoutException implements Exception {
  const LiteRtTimeoutException(this.message);
  final String message;
  @override
  String toString() => 'LiteRtTimeoutException: $message';
}

abstract interface class LiteRtAgentService {
  Future<LiteRtAgentStatus> status();
  Future<LiteRtAgentStatus> load();
  Future<String> generateAgent({required String prompt, int maxTokens = 512});
  Future<void> cancel();
  Future<void> release();
}

abstract interface class LiteRtStreamingAgentService
    implements LiteRtAgentService {
  Future<String> generateAgent({
    required String prompt,
    int maxTokens = 512,
    void Function(String partial)? onPartial,
  });
}

abstract interface class LiteRtSessionAgentService
    implements LiteRtStreamingAgentService {
  /// Creates (or keeps, when scope and systemInstruction are unchanged) the persistent conversation.
  Future<void> openSession({
    required String scope,
    required String systemInstruction,
  });

  /// Sends one user message in the open session for [scope]. Throws
  /// [LiteRtTimeoutException] after [timeout] (generation cancelled, session
  /// discarded), [StateError] when no session is open for [scope].
  Future<String> sendInSession({
    required String scope,
    required String message,
    int maxTokens = 256,
    Duration timeout = const Duration(seconds: 30),
    void Function(String partial)? onPartial,
  });
  Future<void> closeSession();
}

class MethodChannelLiteRtAgentService implements LiteRtSessionAgentService {
  MethodChannelLiteRtAgentService({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel('diagrammer/litert_agent');
  final MethodChannel _channel;
  void Function(String partial)? _onPartial;
  bool _acceptPartials = false;
  String? _sessionScope;
  String? _sessionInstruction;
  bool _nativeSessionOpen = false;

  Future<Object?> _handleMethodCall(MethodCall call) async {
    if (call.method != 'onPartial' || !_acceptPartials) return null;
    final arguments = call.arguments;
    if (arguments is! Map ||
        arguments.length != 1 ||
        arguments['text'] is! String) {
      return null;
    }
    final text = arguments['text'] as String;
    if (text.isEmpty || text.length > 32768) return null;
    _onPartial?.call(text);
    return null;
  }

  Future<LiteRtAgentStatus> _status(String method) async {
    try {
      final value = await _channel.invokeMethod<Object?>(method);
      if (value is! Map ||
          value.length < 4 ||
          value.length > 5 ||
          value.keys.any(
            (k) => !{
              'available',
              'modelPresent',
              'loaded',
              'message',
              'backend',
            }.contains(k),
          ) ||
          (value.containsKey('backend') &&
              !{'gpu', 'cpu'}.contains(value['backend'])) ||
          value['available'] is! bool ||
          value['modelPresent'] is! bool ||
          value['loaded'] is! bool ||
          value['message'] is! String ||
          (value['message'] as String).length > 2048 ||
          (value['loaded'] == true &&
              (value['available'] != true || value['modelPresent'] != true))) {
        throw const FormatException('Invalid local runtime status.');
      }
      return LiteRtAgentStatus(
        available: value['available'] as bool,
        modelPresent: value['modelPresent'] as bool,
        loaded: value['loaded'] as bool,
        message: value['message'] as String,
        backend: value['backend'] as String?,
      );
    } on MissingPluginException {
      return const LiteRtAgentStatus(
        available: false,
        modelPresent: false,
        loaded: false,
        message: 'Optional Gemma runtime is unavailable in this build.',
      );
    }
  }

  @override
  Future<LiteRtAgentStatus> status() => _status('status');
  @override
  Future<LiteRtAgentStatus> load() => _status('load');
  @override
  Future<String> generateAgent({
    required String prompt,
    int maxTokens = 512,
    void Function(String partial)? onPartial,
  }) async {
    if (prompt.isEmpty ||
        prompt.length > 32768 ||
        maxTokens < 1 ||
        maxTokens > 512) {
      throw ArgumentError('Invalid local model input limit.');
    }
    _onPartial = onPartial;
    _acceptPartials = true;
    _channel.setMethodCallHandler(_handleMethodCall);
    try {
      final value = await _channel.invokeMethod<Object?>('generateAgent', {
        'prompt': prompt,
        'maxTokens': maxTokens,
      });
      _acceptPartials = false;
      if (value is! String) {
        throw const FormatException(
          'Local runtime returned no strict decision.',
        );
      }
      AgentAction.parse(value);
      return value;
    } finally {
      _acceptPartials = false;
      _onPartial = null;
      _channel.setMethodCallHandler(null);
    }
  }

  @override
  Future<void> openSession({
    required String scope,
    required String systemInstruction,
  }) async {
    if (scope.isEmpty ||
        scope.length > 256 ||
        systemInstruction.isEmpty ||
        systemInstruction.length > 24000) {
      throw ArgumentError('Invalid local session input limit.');
    }
    if (_nativeSessionOpen &&
        _sessionScope == scope &&
        _sessionInstruction == systemInstruction) {
      return;
    }
    _sessionScope = scope;
    _sessionInstruction = systemInstruction;
    _nativeSessionOpen = false;
    await _channel.invokeMethod<void>('openSession', {
      'scope': scope,
      'systemInstruction': systemInstruction,
    });
    _nativeSessionOpen = true;
  }

  @override
  Future<String> sendInSession({
    required String scope,
    required String message,
    int maxTokens = 256,
    Duration timeout = const Duration(seconds: 30),
    void Function(String partial)? onPartial,
  }) async {
    if (message.isEmpty ||
        message.length > 16000 ||
        maxTokens < 1 ||
        maxTokens > 512 ||
        timeout.inMilliseconds < 1000 ||
        timeout.inMilliseconds > 120000) {
      throw ArgumentError('Invalid local session input limit.');
    }
    final instruction = _sessionInstruction;
    if (_sessionScope != scope || instruction == null) {
      throw StateError('No local session is open for this scope.');
    }
    if (!_nativeSessionOpen) {
      // The native side discarded the session after a timeout or error.
      await openSession(scope: scope, systemInstruction: instruction);
    }
    _onPartial = onPartial;
    _acceptPartials = true;
    _channel.setMethodCallHandler(_handleMethodCall);
    try {
      final value = await _channel
          .invokeMethod<Object?>('sendInSession', {
            'scope': scope,
            'message': message,
            'maxTokens': maxTokens,
            'timeoutMs': timeout.inMilliseconds,
          })
          // Backstop: native owns the bounded wait; never hang on the channel.
          .timeout(
            timeout + const Duration(seconds: 10),
            onTimeout: () => throw const LiteRtTimeoutException(
              'Local model did not answer in time.',
            ),
          );
      _acceptPartials = false;
      if (value is! String || value.trim().isEmpty || value.length > 32768) {
        throw const FormatException('Local runtime returned no text.');
      }
      return value;
    } on PlatformException catch (error) {
      _nativeSessionOpen = false;
      if (error.code == 'LITERT_AGENT_TIMEOUT') {
        throw LiteRtTimeoutException(
          error.message ?? 'Local model did not answer in time.',
        );
      }
      rethrow;
    } on LiteRtTimeoutException {
      _nativeSessionOpen = false;
      await cancel();
      rethrow;
    } finally {
      _acceptPartials = false;
      _onPartial = null;
      _channel.setMethodCallHandler(null);
    }
  }

  @override
  Future<void> closeSession() async {
    _sessionScope = null;
    _sessionInstruction = null;
    _nativeSessionOpen = false;
    try {
      await _channel.invokeMethod<void>('closeSession');
    } on MissingPluginException {
      /* No optional engine holds a session. */
    }
  }

  @override
  Future<void> cancel() async {
    try {
      await _channel.invokeMethod<void>('cancel');
    } on MissingPluginException {
      /* No optional engine exists to cancel. */
    }
  }

  @override
  Future<void> release() async {
    try {
      await _channel.invokeMethod<void>('release');
    } on MissingPluginException {
      /* Optional engine has no retained resources. */
    }
  }
}
