import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:diagrammer_mobile/domain/agent_models.dart';
import 'package:diagrammer_mobile/domain/agent_calculator.dart';

void main() {
  test('strict tags reject executable and unknown fields', () {
    expect(
      () => AgentAction.parse(
        jsonEncode({'tag': 'answer', 'text': 'ok', 'sql': 'x'}),
      ),
      throwsFormatException,
    );
    expect(() => AgentAction.parse('{"tag":"shell"}'), throwsFormatException);
  });
  test('bounded arithmetic rejects zero and nonfinite values', () {
    expect(
      AgentCalculator.evaluate({
        'op': 'add',
        'values': [2, 3],
      }),
      5,
    );
    expect(
      () => AgentCalculator.evaluate({
        'op': 'divide',
        'values': [2, 0],
      }),
      throwsFormatException,
    );
    expect(
      () => AgentCalculator.evaluate({
        'op': 'sum',
        'values': [double.infinity],
      }),
      throwsFormatException,
    );
  });
}
