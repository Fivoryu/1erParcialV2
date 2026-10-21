# IA local del teléfono: usabilidad del agent

App móvil (worktree `codex/mobile-local-ai-gateway`, `mobile/`): Gemma 4 E2B vía LiteRT-LM 0.18 en dispositivo, agent `AgentRunner` + `AgentContract` + gateway de operaciones. Problemas reportados de lentitud, IDs en las respuestas, sin markdown y writes que fallan.

## Specs

S1. "Actualmente el modelo de la IA local del teléfono lanza respuestas muy lentas a peticiones simples." → reducir latencia: prompt compacto (contrato resumido, no OpenAPI completo), observaciones acotadas, max_tokens menor para decisiones simples, y streaming si LiteRT-LM 0.18 lo permite (decisión del usuario: "Ambas cosas").

S2. "al pedirle un dato te devuelve la id de dicho dato, no es inteligente Gemma." → las respuestas del agent deben citar valores de campos legibles, no UUIDs. Los UUIDs siguen siendo necesarios internamente para update/delete, pero el modelo no debe reproducirlos ni dependen de que los copie bien.

S3. "quiero cuando muestre se vea el formato md preview para que que se vea bien el mensaje." → el chat del teléfono renderiza markdown (renderer elegido por el usuario: flutter_markdown_plus).

S4. "no es capaz de crear datos, ni eliminar correctamente a la hora de correr." → la autorización de escritura acepta pedidos en español natural (decisión del usuario: "Gramática más amplia") manteniendo la garantía de diseño: los valores provienen del texto del usuario, nunca inferidos por el modelo. El delete conserva preview + confirmación explícita.

S5. "Esta conectado el teléfono con usb c a la pc" + decisión "Build + instalar" → al cerrar, compilar APK release (sabor litertTrial), instalarlo en el dispositivo R5CWA0BTAKH y verificar con el app real vía `adb reverse tcp:18090`.

## Tasks

T1 (S1) DONE — prompt compaction en `AgentRunner`: descriptor compacto tolerante a contratos arbitrarios ($ref, solo-lectura, malformados → soft). Commits: d56be11 (baseline app), 24a87b3 feat(mobile): compact agent prompt contract.
T2 (S2, S4) DONE — aliasing de registros (#1, #2…) en observaciones + resolución ref→UUID con binding a entidad; fail-closed en refs desconocidas; listas acotadas a 20. Commit: 8a1433b.
T3 (S4) DONE — authorize ampliado: acentos/plurales/artículos, pares campo=valor, texto libre al único campo string requerido, JSON; fail-closed en ambigüedad/mismatch. Commit: 0ff5438.
T4 (S3) DONE — MarkdownBody seleccionable solo para mensajes del assistant; usuarios en texto plano. Commit: 099a574 (128 tests).
T5 (S1) DONE — puente Kotlin con sendMessageAsync + MessageCallback (latch, gating epoch/deadline/delivery, decisión final desde el mensaje final del SDK, cancel intacta); servicio Dart con onPartial acotado; chat muestra un parcial transitorio que nunca persiste. Commit: 5902dd7 (130 tests).
T5b (S1) Defecto de release corregido — R8 ofuscaba JniMessageCallback y el callback del bridge (onMessage/onDone se resuelven por nombre literal vía JNI en nativeSendMessageAsync) → NoSuchMethodError + SIGABRT al primer mensaje en APK release. Fix: reglas `-keep class com.google.ai.edge.litertlm.** { *; }` y `-keepclassmembers class * implements com.google.ai.edge.litertlm.** { *; }` en `mobile/android/app/litert-trial-proguard.pro`.
T6 (S5) AL 90% — APK rebuild con --dart-define=PLATFORM_URL=http://127.0.0.1:18090 instalado (Success); sesión persistida; el agent del teléfono despachó GET /api/note 200 vía mobile_gateway (22:17-22:28) — extremo a extremo OK. Prueba PR #10; conflicto de esquema resuelto con renumeración V21/V22 (b0833ce) y SchemaOwnershipTest 9/9; MERGEABLE, falta aprobación humana. Interacción UI por adb es poco fiable en este dispositivo (ANR de foco conocido); verificación final interactiva con el usuario.

## Log

L1 (pedido original, verbatim): "Actualmente el modelo de la IA local del teléfono lanza respuestas muy lentas a peticiones simples. / Además de que al pedirle un dato te devuelve la id de dicho dato, no es inteligente Gemma. / Y quiero cuando muestre se vea el formato md preview para que que se vea bien el mensaje. / Preguntas?"
L2: "Y no es capaz de crear datos, ni eliminar correctamente a la hora de correr."
L3: "Esta conectado el teléfono con usb c a la pc"
L4 (decisiones del usuario por cuestionario): velocidad = "Ambas cosas"; escrituras = "Gramática más amplia"; markdown = "flutter_markdown_plus"; entrega = "Build + instalar".
L5: "Le pido algo al agente y se sale de la app, no responde la app :(" → crash en release por R8 (T5b); login funcional como `local_ai_80c8285f` (proyectos del fixture le pertenecen, no a seed-owner). Bugs menores anotados, no pedidos: la selección del modelo (Gemma) y del backend no persisten al recrear la pantalla.
