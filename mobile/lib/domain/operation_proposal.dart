import 'dart:convert';

/// One structured query parameter; the platform gateway encodes it, so a path
/// never carries a raw query string.
class QueryParameter {
  const QueryParameter(this.name, this.value);
  final String name;
  final String value;

  Map<String, dynamic> toJson() => {'name': name, 'value': value};

  @override
  bool operator ==(Object other) =>
      other is QueryParameter && other.name == name && other.value == value;

  @override
  int get hashCode => Object.hash(name, value);
}

class OperationProposal {
  const OperationProposal({
    required this.method,
    required this.path,
    required this.body,
    this.query = const [],
  });
  final String method;
  final String path;
  final dynamic body;
  final List<QueryParameter> query;

  /// `query` is serialized only when present, so proposals without one keep
  /// the exact encoding write authorizations were issued for.
  Map<String, dynamic> toJson() => {
    'method': method,
    'path': path,
    'body': body,
    if (query.isNotEmpty) 'query': [for (final parameter in query) parameter.toJson()],
  };

  String encode() => jsonEncode(toJson());

  /// Stable key for caching a read: the path plus its encoded query.
  String get cacheKey => query.isEmpty
      ? path
      : '$path?${query.map((p) => '${Uri.encodeQueryComponent(p.name)}=${Uri.encodeQueryComponent(p.value)}').join('&')}';

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
