import 'package:flutter/material.dart';

/// Purple-pink palette of design/system.md ("Propuesta morado-rosa para las
/// maquetas"), shared with the web editor so both apps read as one product.
abstract final class AppPalette {
  static const ink = Color(0xff2d2032);
  static const muted = Color(0xff6d6070);
  static const line = Color(0xffe8dce8);
  static const paper = Color(0xffffffff);
  static const canvas = Color(0xfff9f5f9);
  static const accent = Color(0xff8b3c86);
  static const dark = Color(0xff68286f);
  static const wash = Color(0xfff6e8f4);
  static const rose = Color(0xffa73d74);
  static const roseWash = Color(0xfffae9f1);
  static const danger = Color(0xff9b3e37);
}

ThemeData buildLightTheme() {
  final scheme = ColorScheme.fromSeed(seedColor: AppPalette.accent).copyWith(
    // Primary actions use the dark purple; the lighter accent marks emphasis.
    primary: AppPalette.dark,
    onPrimary: AppPalette.paper,
    primaryContainer: AppPalette.wash,
    onPrimaryContainer: AppPalette.dark,
    secondary: AppPalette.rose,
    secondaryContainer: AppPalette.roseWash,
    onSecondaryContainer: AppPalette.rose,
    tertiary: AppPalette.accent,
    surface: AppPalette.paper,
    onSurface: AppPalette.ink,
    onSurfaceVariant: AppPalette.muted,
    outline: AppPalette.line,
    outlineVariant: AppPalette.line,
    error: AppPalette.danger,
  );
  final rounded = RoundedRectangleBorder(borderRadius: BorderRadius.circular(9));
  return ThemeData(
    colorScheme: scheme,
    useMaterial3: true,
    scaffoldBackgroundColor: AppPalette.canvas,
    appBarTheme: const AppBarTheme(
      backgroundColor: AppPalette.paper,
      foregroundColor: AppPalette.ink,
      surfaceTintColor: Colors.transparent,
      shape: Border(bottom: BorderSide(color: AppPalette.line)),
    ),
    cardTheme: CardThemeData(
      color: AppPalette.paper,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: const BorderSide(color: AppPalette.line),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      border: const OutlineInputBorder(),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(8),
        borderSide: const BorderSide(color: AppPalette.line),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(8),
        borderSide: const BorderSide(color: AppPalette.accent, width: 2),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size.fromHeight(48),
        backgroundColor: AppPalette.dark,
        foregroundColor: AppPalette.paper,
        shape: rounded,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: AppPalette.ink,
        side: const BorderSide(color: AppPalette.line),
        shape: rounded,
      ),
    ),
    dividerTheme: const DividerThemeData(color: AppPalette.line),
  );
}

ThemeData buildDarkTheme() => ThemeData(
  colorScheme: ColorScheme.fromSeed(
    seedColor: const Color(0xffd7a6d2),
    brightness: Brightness.dark,
  ),
  useMaterial3: true,
);
