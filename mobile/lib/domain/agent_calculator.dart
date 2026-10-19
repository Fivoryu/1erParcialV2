class AgentCalculator {
  static num evaluate(Map<String, dynamic> spec) {
    if (spec.keys.any((k) => !{'op', 'values'}.contains(k))) {
      throw const FormatException('Unsupported calculation fields.');
    }
    final op = spec['op'];
    final raw = spec['values'];
    if (raw is! List ||
        raw.isEmpty ||
        raw.length > 1000 ||
        raw.any((v) => v is! num || !v.isFinite)) {
      throw const FormatException('Finite numeric inputs required.');
    }
    final values = raw.cast<num>();
    num result;
    switch (op) {
      case 'sum':
      case 'add':
        result = values.reduce((a, b) => a + b);
      case 'mean':
        result = values.reduce((a, b) => a + b) / values.length;
      case 'min':
        result = values.reduce((a, b) => a < b ? a : b);
      case 'max':
        result = values.reduce((a, b) => a > b ? a : b);
      case 'multiply':
        result = values.reduce((a, b) => a * b);
      case 'subtract':
        if (values.length != 2) {
          throw const FormatException('Two inputs required.');
        }
        result = values[0] - values[1];
      case 'divide':
        if (values.length != 2 || values[1] == 0) {
          throw const FormatException(
            'Two inputs and nonzero divisor required.',
          );
        }
        result = values[0] / values[1];
      default:
        throw const FormatException('Unsupported calculation.');
    }
    if (!result.isFinite) throw const FormatException('Nonfinite result.');
    return result;
  }

  static num aggregate(List<dynamic> records, String field, String op) {
    if (records.length > 1000) {
      throw const FormatException('Record limit exceeded.');
    }
    final values = records.map((record) {
      if (record is! Map || !record.containsKey(field)) {
        throw const FormatException('Missing numeric field.');
      }
      return record[field];
    }).toList();
    return evaluate({'op': op, 'values': values});
  }
}
