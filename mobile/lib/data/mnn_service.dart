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
        'La detección de IA local no está disponible en esta compilación.',
      );
    } on PlatformException catch (error) {
      throw MnnUnavailableException(
        error.message ?? 'No se pudo detectar el modelo local instalado.',
      );
    }
  }

  @override
  Future<MnnStatus> loadModel(String directory) async {
    if (directory.trim().isEmpty) {
      throw const MnnUnavailableException(
        'Primero selecciona el directorio del modelo local Qwen.',
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
            _localizeMnnStatus(
              value?['message'] as String? ?? 'Se desconoce el estado del modelo local.',
            ),
      );
    } on MissingPluginException {
      throw const MnnUnavailableException(
        'El puente MNN de Android no está instalado. La IA local no estará disponible hasta que se añada el puente JNI verificado.',
      );
    } on PlatformException catch (error) {
      throw MnnUnavailableException(
        error.message ??
            'El entorno MNN local no pudo cargar el modelo seleccionado.',
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
        'El mensaje para el modelo local supera el límite de seguridad.',
      );
    }
    try {
      final response = await _channel.invokeMethod<String>(method, {
        'prompt': prompt,
        'maxTokens': maxTokens,
      });
      if (response == null || response.length > 32768) {
        throw const MnnUnavailableException(
          'El entorno local no devolvió una propuesta utilizable.',
        );
      }
      return response;
    } on MissingPluginException {
      throw const MnnUnavailableException(
        'La IA local no está disponible porque el puente MNN de Android no está instalado.',
      );
    } on PlatformException catch (error) {
      throw MnnUnavailableException(
        error.message ?? 'Falló el entorno MNN local.',
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

String _localizeMnnStatus(String message) => message
    .replaceAll('device-local CPU', 'local en el dispositivo · CPU')
    .replaceAll('device-local GPU', 'local en el dispositivo · GPU')
    .replaceAll(
      'The local runtime has been released.',
      'Se liberó el entorno de ejecución local.',
    )
    .replaceAll('Local inference failed.', 'Falló la inferencia local.')
    .replaceAll(
      'The native MNN library is unavailable for this device ABI.',
      'La biblioteca nativa de MNN no está disponible para esta arquitectura de dispositivo.',
    );
