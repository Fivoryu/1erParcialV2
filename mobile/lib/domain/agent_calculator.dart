class AgentCalculator {
  static num evaluate(Map<String, dynamic> spec) {
    if (spec.keys.any((k) => !{'op', 'values'}.contains(k))) {
      throw const FormatException('Los campos del cálculo no son compatibles.');
    }
    final op = spec['op'];
    final raw = spec['values'];
    if (raw is! List ||
        raw.isEmpty ||
        raw.length > 1000 ||
        raw.any((v) => v is! num || !v.isFinite)) {
      throw const FormatException('Se requieren valores numéricos finitos.');
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
          throw const FormatException('Se requieren dos valores.');
        }
        result = values[0] - values[1];
      case 'divide':
        if (values.length != 2 || values[1] == 0) {
          throw const FormatException(
            'Se requieren dos valores y un divisor distinto de cero.',
          );
        }
        result = values[0] / values[1];
      default:
        throw const FormatException('El cálculo no es compatible.');
    }
    if (!result.isFinite) throw const FormatException('El resultado no es finito.');
    return result;
  }

  static num aggregate(List<dynamic> records, String field, String op) {
    if (records.length > 1000) {
      throw const FormatException('Se superó el límite de registros.');
    }
    final values = records.map((record) {
      if (record is! Map || !record.containsKey(field)) {
        throw const FormatException('Falta el campo numérico.');
      }
      return record[field];
    }).toList();
    return evaluate({'op': op, 'values': values});
  }
}
