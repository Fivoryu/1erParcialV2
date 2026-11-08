import 'dart:convert';
import 'package:diagrammer_mobile/data/platform_service.dart';
import 'package:diagrammer_mobile/presentation/screens/merge_request_detail_screen.dart';
import 'package:diagrammer_mobile/presentation/session_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'workspace_switch_test.dart' as f;
void main() {
 for (final status in [401,403,500]) { for(final isDiff in [false,true]) {
 testWidgets('independent HTTP $status diff=$isDiff', (t) async {
 final calls=<String>[]; final store=f.SwitchStore(savedSession:f.switchSession); final session=SessionController(store,f.SwitchPlatform()); await session.restore();
 final api=PlatformService(baseUri:Uri.parse('https://example.test'),client:MockClient((r) async {calls.add(r.url.path); if(r.url.path.endsWith('/diff')==isDiff) return http.Response('',status); return http.Response(jsonEncode({'title':'X','state':'OPEN','sourceBranch':'a','targetBranch':'b','approvals':[]}),200);}));
 await t.pumpWidget(MaterialApp(home:MergeRequestDetailScreen(session:session,workspace:f.switchController(f.SwitchPlatform(),f.SwitchStore()),service:api,requestId:'x'))); await t.pumpAndSettle();
 expect(session.authenticated,status!=401); expect(calls.length,isDiff?2:1); if(status!=401) expect(find.text('La solicitud a la plataforma falló ($status).'),findsOneWidget); expect(t.takeException(),isNull);
 }); }}
 testWidgets('independent partial activity with no commits or approvals', (t) async {
 final session=SessionController(f.SwitchStore(savedSession:f.switchSession),f.SwitchPlatform()); await session.restore();
 final api=PlatformService(baseUri:Uri.parse('https://example.test'),client:MockClient((r) async => http.Response(jsonEncode(r.url.path.endsWith('/diff')?{'changes':[],'commits':[]}:{'title':'Empty','state':'OPEN','sourceBranch':'a','targetBranch':'b','approvals':[],'openedByDisplayName':'Sofia'}),200)));
 await t.pumpWidget(MaterialApp(home:MergeRequestDetailScreen(session:session,workspace:f.switchController(f.SwitchPlatform(),f.SwitchStore()),service:api,requestId:'x'))); await t.pumpAndSettle();
 await t.scrollUntilVisible(find.text('Actividad parcial'),200); expect(find.text('Actividad parcial'),findsOneWidget); expect(find.text('Datos disponibles de la solicitud y sus commits; no es una línea de tiempo completa.'),findsOneWidget); expect(find.text('Solicitud abierta por Sofia'),findsOneWidget); expect(find.textContaining('Commit:'),findsNothing); expect(find.textContaining('Aprobación:'),findsNothing); expect(t.takeException(),isNull);
 });
}
