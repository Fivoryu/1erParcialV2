import 'package:diagrammer_mobile/presentation/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('the light theme uses the purple-pink palette of design/system.md', () {
    final theme = buildLightTheme();

    expect(theme.scaffoldBackgroundColor, const Color(0xfff9f5f9));
    expect(theme.colorScheme.primary, const Color(0xff68286f));
    expect(theme.colorScheme.secondary, const Color(0xffa73d74));
    expect(theme.colorScheme.onSurface, const Color(0xff2d2032));
    expect(theme.colorScheme.outline, const Color(0xffe8dce8));
    expect(theme.colorScheme.error, const Color(0xff9b3e37));
  });

  test('filled buttons, the primary action, use the dark purple', () {
    final style = buildLightTheme().filledButtonTheme.style!;

    expect(style.backgroundColor!.resolve({}), AppPalette.dark);
    expect(style.foregroundColor!.resolve({}), AppPalette.paper);
  });
}
