import 'package:flutter/services.dart';

import '../domain/agent_models.dart';

class LiteRtAgentStatus {
  const LiteRtAgentStatus({
    required this.available,
    required this.modelPresent,
    required this.loaded,
    required this.message,
  });
  final bool available, modelPresent, loaded;
  final String message;
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

class MethodChannelLiteRtAgentService implements LiteRtStreamingAgentService {
  MethodChannelLiteRtAgentService({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel('diagrammer/litert_agent');
  final MethodChannel _channel;
  void Function(String partial)? _onPartial;
  bool _acceptPartials = false;

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
          value.length != 4 ||
          value.keys.any(
            (k) =>
                !{'available', 'modelPresent', 'loaded', 'message'}.contains(k),
          ) ||
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
