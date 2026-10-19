import 'package:diagrammer_mobile/domain/operation_proposal.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const raw =
      '{"method":"POST","path":"/api/notes","body":{"title":"USB offline test"}}';
  const fenced = '```json\n$raw\n```';

  test('accepts the exact complete JSON fence observed on the device', () {
    final expected = OperationProposal.parse(raw);
    expect(OperationProposal.parse(fenced).toJson(), expected.toJson());
    expect(OperationProposal.parse('  $fenced\r\n').encode(), raw);
    expect(OperationProposal.parse('```json\r\n$raw\r\n```').encode(), raw);
  });

  test(
    'rejects prose, multiple blocks, other languages, and malformed JSON',
    () {
      for (final output in [
        'Explanation\n$fenced',
        '$fenced\nDone',
        '$fenced\n$fenced',
        '```\n$raw\n```',
        '```javascript\n$raw\n```',
        '```json $raw```',
        '```json\n$raw',
        '```json\n{"method":POST}\n```',
        '```json\n[$raw]\n```',
        '```json\n{"method":"POST","path":"/api/notes","extra":true}\n```',
      ]) {
        expect(() => OperationProposal.parse(output), throwsFormatException);
      }
    },
  );

  test('preserves raw JSON validation and bounds without repairing data', () {
    expect(OperationProposal.parse(raw).encode(), raw);
    expect(OperationProposal.parse('  $raw\n').encode(), raw);
    for (final output in [
      '{"method":123,"path":"/api/notes"}',
      '{"method":"GET","path":null}',
      '{"method":"GET","path":"/api/notes","extra":true}',
      '$raw $raw',
      'x' * 32769,
      '```json\n${' ' * 32769}$raw\n```',
    ]) {
      expect(() => OperationProposal.parse(output), throwsFormatException);
    }
  });
}
