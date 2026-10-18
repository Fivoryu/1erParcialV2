import 'package:flutter/services.dart';

import '../domain/models.dart';

abstract interface class MnnService {
  Future<MnnStatus> loadModel(String directory);
  Future<String> generate({required String prompt, int maxTokens = 512});
  Future<void> release();
}

abstract interface class MnnAgentService {
  Future<String> generateAgent({required String prompt, int maxTokens = 512});
}

abstract interface class MnnModelDiscovery {
  Future<String?> discoverModel();
}

class MethodChannelMnnService
    implements MnnService, MnnModelDiscovery, MnnAgentService {
  const MethodChannelMnnService([MethodChannel? channel])
    : _channel = channel ?? const MethodChannel('diagrammer/mnn');
  final MethodChannel _channel;

  @override
  Future<String?> discoverModel() async {
    try {
      return await _channel.invokeMethod<String>('discoverModel');
    } on MissingPluginException {
      throw const MnnUnavailableException(
        'Local AI discovery is unavailable in this build.',
      );
    } on PlatformException catch (error) {
      throw MnnUnavailableException(
        error.message ?? 'Could not detect the installed local model.',
      );
    }
  }

  @override
  Future<MnnStatus> loadModel(String directory) async {
    if (directory.trim().isEmpty) {
      throw const MnnUnavailableException(
        'Choose the local Qwen model directory first.',
      );
    }
    try {
      final value = await _channel.invokeMapMethod<String, dynamic>(
        'loadModel',
        {'directory': directory},
      );
      return MnnStatus(
        modelDirectory: directory,
        isLoaded: value?['loaded'] == true,
        message:
            value?['message'] as String? ?? 'Local model status is unknown.',
      );
    } on MissingPluginException {
      throw const MnnUnavailableException(
        'The Android MNN bridge is not installed. Local AI is unavailable until the verified JNI bridge is added.',
      );
    } on PlatformException catch (error) {
      throw MnnUnavailableException(
        error.message ??
            'The local MNN runtime could not load the selected model.',
      );
    }
  }

  @override
  Future<String> generate({required String prompt, int maxTokens = 512}) =>
      _generate(prompt, maxTokens, 'generate');

  @override
  Future<String> generateAgent({required String prompt, int maxTokens = 512}) =>
      _generate(prompt, maxTokens, 'generateAgent');

  Future<String> _generate(String prompt, int maxTokens, String method) async {
    if (prompt.length > 32768 || maxTokens < 1 || maxTokens > 512) {
      throw const MnnUnavailableException(
        'The local prompt exceeds the safety limit.',
      );
    }
    try {
      final response = await _channel.invokeMethod<String>(method, {
        'prompt': prompt,
        'maxTokens': maxTokens,
      });
      if (response == null || response.length > 32768) {
        throw const MnnUnavailableException(
          'The local runtime returned no usable proposal.',
        );
      }
      return response;
    } on MissingPluginException {
      throw const MnnUnavailableException(
        'Local AI is unavailable because the Android MNN bridge is not installed.',
      );
    } on PlatformException catch (error) {
      throw MnnUnavailableException(
        error.message ?? 'The local MNN runtime failed.',
      );
    }
  }

  @override
  Future<void> release() async {
    try {
      await _channel.invokeMethod<void>('release');
    } on MissingPluginException {
      // Releasing is best-effort because the native bridge is optional.
    }
  }
}

class MnnUnavailableException implements Exception {
  const MnnUnavailableException(this.message);
  final String message;
  @override
  String toString() => message;
}
