import 'dart:convert';

class OperationProposal {
  const OperationProposal({
    required this.method,
    required this.path,
    required this.body,
  });
  final String method;
  final String path;
  final dynamic body;

  Map<String, dynamic> toJson() => {
    'method': method,
    'path': path,
    'body': body,
  };

  String encode() => jsonEncode(toJson());

  static OperationProposal parse(String value) {
    if (value.length > 32768) {
      throw const FormatException('The local proposal is too large.');
    }
    // Recognize one complete presentation wrapper, never extract or repair JSON.
    final trimmed = value.trim();
    final fence = RegExp(r'^```json\r?\n([\s\S]*)\r?\n```$')
        .firstMatch(trimmed);
    final decoded = jsonDecode(fence == null ? trimmed : fence.group(1)!);
    if (decoded is! Map<String, dynamic> ||
        decoded.keys.any((key) => !{'method', 'path', 'body'}.contains(key)) ||
        decoded['method'] is! String ||
        decoded['path'] is! String) {
      throw const FormatException(
        'The local model did not return the required proposal JSON.',
      );
    }
    return OperationProposal(
      method: decoded['method'] as String,
      path: decoded['path'] as String,
      body: decoded['body'],
    );
  }
}
