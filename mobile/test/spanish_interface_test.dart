import 'package:diagrammer_mobile/presentation/screens/login_screen.dart';
import 'package:diagrammer_mobile/presentation/session_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'workspace_switch_test.dart' as f;

void main() {
  testWidgets('login form uses Spanish without changing input behavior', (tester) async {
    final controller = SessionController(f.SwitchStore(), f.SwitchPlatform());
    await tester.pumpWidget(MaterialApp(home: LoginScreen(controller: controller)));
    expect(find.text('Conectate a tu plataforma'), findsOneWidget);
    expect(find.text('Usuario'), findsOneWidget);
    expect(find.text('Contraseña'), findsOneWidget);
    expect(find.text('Iniciar sesión'), findsOneWidget);
    expect(find.text('Sign in'), findsNothing);
    final password = tester.widget<TextField>(find.byType(TextField).last);
    expect(password.obscureText, isTrue);
  });
}
