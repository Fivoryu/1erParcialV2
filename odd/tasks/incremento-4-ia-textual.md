# Incremento 4 — IA textual (lenguaje natural a comandos UML)

**Estado:** plan congelado el 2026-10-04 sobre `feat/incremento-4-ia-textual` (desde `main` = `4dac15e`).
**Fuente autoritativa de alcance:** `docs/guia-implementacion-incremento-4-ia.md` (guía futura, leída y reconciliada contra el código el 2026-10-04).
**Enunciado:** `docs/analisis-enunciado-parcial-1.md:369` — IA textual, referencias a elementos existentes, previsualización/confirmación/deshacer, manejo de ambigüedad.

## 0. Decisiones tomadas antes de congelar

| Decisión | Valor | Quién/cuándo |
| --- | --- | --- |
| Orden de arranque | **IA4-01 primero** (evaluar modelos locales) antes de IA4-02 | Usuario, 2026-10-04 |
| Motor de interpretación | Modelo **local** servido por contenedor interno (solo backend lo alcanza); base vigente Qwen3.5-0.8B, 2B propuesto a evaluación. Sin fallback pago. | Guía §4, confirmada por el usuario |
| Deshacer | **Compensación por comando inverso** sobre el log append-only (IA4-04), colaborativo, nunca snapshot (IA4-04). `CommandOrigin.UNDO/REDO` e `inverse_of_command_id` existen reservados sin consumir (`command/CommandOrigin.java:9-10`, `V3__commands_and_versions.sql:35`). | Guía §5, IA4-04 |
| Alcance del catálogo NL | **Subconjunto del dominio**: crear/renombrar/borrar clases, atributos, operaciones y relaciones + cambio de propiedades. XMI y geometría fuera del catálogo de este incremento. | Usuario, 2026-10-04 (refina DEC-I4-03) |
| Doc pendientes | DEC-I4-01 (modelo), 02 (runtime/imagen), 03 (catálogo cerrado), 04 (atomicidad), 05 (undo semántica), 06 (umbrales), 07 (estilo/ruta) se cierran en la unidad que las necesita; ninguna se cierra por anticipado. | Guía §7 |

## 1. Base verificada en el código (exploración 2026-10-04, con path:line)

- **Comandos:** sobre canónico `OperationEnvelope` (`command/OperationEnvelope.java:10-16`), 26 tipos registrados por `type()` en `CommandService.java:71-72`; pipeline transaccional con revisión optimista, idempotencia y precondiciones (`CommandService.java:134-237`).
- **Undo/redo:** NO existe consumo; solo reservado. El log `ModelCommand` es append-only con `baseRevision/resultRevision` (`V3__commands_and_versions.sql:47`).
- **IA:** cero ocurrencias de clientes LLM/HTTP salientes en `backend/src/main` y `web/src`; `CommandOrigin.AI` reservado sin uso (`command/CommandOrigin.java:4`).
- **Transporte:** comandos por `POST /projects/{id}/commands` (`api/ProjectController.java:49-52`); cliente centralizado en `web/src/api/envelope.ts:24-49` y `ProjectEditorPage.tsx:394-452` (lease del cliente + cola serializada). SSE para eventos (`ProjectEventController.java:36-55`).
- **Referencias por nombre:** no existen en el camino de comandos; solo en importación XMI (`XmiImportHandler.java:666-673`). No hay endpoint de búsqueda de elementos; `GET /projects/{id}/model` devuelve el recurso completo (`ProjectController.java:44-47`).
- **Concesión de edición:** puerta **solo del cliente** (`ProjectEditorPage.tsx:397`, `useEditingLease.ts:133`); el servidor no la chequea en el camino de comandos.
- **HTTP saliente backend:** ninguno (sin RestTemplate/WebClient/HttpClient). Config por `@Value` con env-default es el patrón existente (`InMemoryEditingLeaseService.java:35-37`).

## 2. Unidades (orden de la guía, arranque decidido)

| Unidad | Título | Prerrequisitos | Estado |
| --- | --- | --- | --- |
| IA4-01 | Evaluar modelos locales (0.8B vs 2B, corpus fijo en español; 4B solo si 2B no alcanza) | Hardware fijado; no descargar sin permiso | **en curso** |
| IA4-02 | Plan tipado + validador determinista (contrato estricto, catálogo cerrado) | IA4-01, DEC-I4-03 | pendiente |
| IA4-03 | Aplicar planes por el ejecutor normal (preview/confirm/cancel con revisión y revalidación servidora) | IA4-02, DEC-I4-04 | pendiente |
| IA4-04 | Undo/redo colaborativo (compensación, no snapshot) | Inventario, IA4-03, DEC-I4-05 | pendiente |
| IA4-05 | Panel del asistente en la web (estados, aclaración, preview, progreso) | IA4-02/03, DEC-I4-06 | pendiente |
| IA4-06 | Herramientas y atajos manuales (selección, alineación, grilla, Ctrl+Z/Y) | Inventario del lienzo, IA4-04 | pendiente |
| IA4-07 | Rutas ortogonales y estilo por vista (requiere comando/migración nueva) | DEC-I4-07 | pendiente |
| IA4-08 | Aceptación integrada (los 5 escenarios del enunciado + colaboración + medición real) | IA4-01..07 | pendiente |

Regla de la guía §8: test-first donde exista escenario determinista (RED observado → GREEN → refactor); donde no lo haya, verificación proporcional declarada.

## 3. Protocolo de IA4-01 (fijado antes de medir)

- **Hardware objetivo:** esta máquina (única disponible para la demo; el host de presentación queda DEC abierta y no bloquea).
- **Artefactos:** `ggml-org/Qwen3.5-0.8B-GGUF` Q4_0 (563 MB) y `unsloth/Qwen3.5-2B-GGUF` Q4_K_M (1.28 GB). Conversión de Unsloth declarada como no oficial. Ninguna descarga sin permiso explícito del usuario.
- **Runtime:** candidato `llama.cpp` (binario Windows o contenedor). Docker solo vía `cmd.exe` (WSL no tiene Docker CLI). Sin inferencia paga, sin fallback silencioso.
- **Corpus fijo en español** (escrito y versionado antes de correr): mínimo los 5 escenarios de aceptación de IA4-08 + variantes de ambigüedad y rechazo. Cada caso con resultado esperado declarado; lo no medido se declara.
- **Métricas por modelo:** exactitud de esquema/plan sobre el corpus, ambigüedad bien manejada (pregunta en vez de mutar), carga fría/caliente, RAM pico de proceso y de sistema, latencia p50/p95. Registro de hardware/runtime/artefacto/cuantización exactos.
- **Salida:** informe en `docs/puds/verificacion/` + recomendación DEC-I4-01/02 para el usuario. La guía no fija umbral: DEC-I4-06 queda abierta hasta medir.

### 3.1 Artefactos y runtime descargados (2026-10-04, con autorización del usuario)

| Ítem | Valor medido |
| --- | --- |
| `Qwen3.5-0.8B-Q4_0.gguf` | 563.036.064 bytes; sha256 `57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf` (coincide con el LFS oid publicado) |
| `Qwen3.5-2B-Q4_K_M.gguf` | 1.280.835.840 bytes; sha256 `aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223` (coincide con el LFS oid publicado) |
| Ubicación | `D:\ai-eval\models\` (fuera del repo; futuro volumen del servicio IA) |
| Runtime | `llama.cpp` build `b11388`, `llama-b11388-bin-win-cpu-x64.zip`, en `D:\ai-eval\runtime\` (`llama-server.exe`, `llama-cli.exe`) |
| Elección CPU | GPU = Intel integrada (~2 GB compartidos, sin CUDA): el build CPU x64 elimina variables de offload y deja limpia la medición de RAM pico y latencia |
| Estado | **No corrido todavía**: el host tenía 1,4 GB de RAM disponibles al momento de la descarga; las corridas esperan a que libere RAM (decisión del usuario) |

## 4. Restricciones de método (de incrementos previos, vigentes)

- Docs en español rioplatense; código, commits y comentarios en inglés; sin atribución de IA.
- Toda cifra con el comando que la produjo; lo no medido se declara "no medido".
- Maven/Docker vía `cmd.exe`/`powershell.exe`; WSL no tiene maven/java/Docker; push con git de Windows.
- Una unidad cierra con: revisión nativa aprobada + autoridad quemada (cuando corresponda) + commit de unidad en inglés + commit docs(odd) + espejo Engram actualizado.
- Escritor delegado por unidad con bloque exacto `## Allowed edit surfaces`; verificación independiente antes de revisión nativa.
- Nunca tocar el worktree de otra sesión; las mutaciones de no-vacuidad se hacen en copias fuera del repo.

## 5. Registro de avance

| Fecha | Unidad | Evidencia | Commit |
| --- | --- | --- | --- |
| 2026-10-04 | — | Plan congelado; exploración de la capa de comandos con path:line; rama creada desde `4dac15e` | c97f0d1 |
| 2026-10-04 | IA4-01 | Corpus de 15 casos + protocolo versionados antes de correr modelos | bf58f0f |
| 2026-10-04 | IA4-01 | GGUF 0.8B/2B descargados y verificados por sha256; runtime llama.cpp b11388 CPU en `D:\ai-eval\`; corridas diferidas por RAM del host | 359502d |
| 2026-10-04 | IA4-01 | Corredor + juez con selftest sin RAM (27/27); corridas 0.8B y 2B con prompt v1; enmiendas de instrumento (thinking, sampler $pid) | 36534bc, d437c27, 824ce75 |
| 2026-10-04 | IA4-01 | **CERRADA**: iteración única de prompt (v2, few-shot con elementos ajenos al corpus); veredictos 0.8B 6/15 y 2B 11/15; informe y recomendación DEC-I4-01 (2B Q4_K_M base) y DEC-I4-02 (llama.cpp) en docs/puds/verificacion/03 | (este commit) |

## 6. Diseño congelado de IA4-02 — plan tipado + validador determinista

**Paquete nuevo** `com.diagramador.assistant` (backend). El modelo propone; el validador decide; acá **no se muta nada**.

- `AssistantPlanRequest` (record): `utterance` (no vacía), `baseRevision` (long), `selection` (UUID opcional).
- `AssistantPlanOperation` (record post-validación): `type`, `targetId` (UUID resuelto), `args`.
- `AssistantPlanResponse` (record): `intent` (PLAN|EXPLAIN|CLARIFY|REJECT), `operations` (resueltas), `question`, `explanation`, `diagnostics` (códigos: `UNKNOWN_OPERATION`, `AMBIGUOUS_TARGET`, `UNKNOWN_TARGET`, `NAME_COLLISION`, `REVIEW_REQUIRED`).
- `AssistantPlanCatalog`: catálogo cerrado del subconjunto decidido (classifier create/rename/delete/setAbstract, attribute add/update/remove, operation add, relationship create/update/delete). Fuera de esto: rechazo.
- `AiAssistantProvider` (interfaz) + `LlamaCppAssistantProvider` (HTTP a `diagrammer.assistant.url`, default `http://127.0.0.1:8099`; prompt v2; temp 0; thinking off; timeout configurable). Si `diagrammer.assistant.enabled=false` (default) el endpoint responde 503 sin llamar a nadie.
- `AssistantPlanService`: snapshot del modelo → contexto (misma forma que el fixture del corpus) → provider → parseo tolerante (mismas reglas del juez del arnés) → `AssistantPlanValidator` → respuesta tipada. El parseo no consumible es REJECT con diagnóstico, nunca error 500.
- `AssistantPlanValidator` (determinista, test-first): catálogo cerrado; resolución por nombre con normalización acentos/mayúsculas; ambiguo → CLARIFY; inexistente → REJECT; create con nombre colisionante → diagnóstico; ops destructivas sin destino resuelto → REJECT; `baseRevision` vencida → `REVIEW_REQUIRED` (guía §3.6: no reaplicar sobre datos nuevos).
- `AssistantPlanController`: `POST /api/projects/{projectId}/assistant/plan`, mismas convenciones de autorización que los comandos (miembros OWNER/EDITOR; el operador queda fuera por la lista blanca del interceptor, como ya ocurre con commands).
- Pruebas: validator (7 casos), service (parseo vallado/inválido/provider caído/disabled), controller (auth). RED observado antes de implementar cada grupo.

**Fuera de alcance de IA4-02**: aplicar el plan (IA4-03), UI (IA4-05), streaming (innecesario: respuesta completa por petición).

## 7. IA4-02 cerrada + diseño congelado de IA4-03

**IA4-02 (73f9492, review-ca65fe77e0a5a736 aprobada y quemada)**: paquete `assistant` completo (9 archivos, 1.234 líneas), 15 pruebas nuevas, suite 370/0/0/0 con Docker arriba. Avisos informativos a la deuda visible: R3-001 (Validator:88), R3-002 (Validator:265), R3-003 (Catalog:25), R3-004 (Service:75-84). Provider deshabilitado por defecto (`diagrammer.assistant.enabled=false` → 503).

**IA4-03 — aplicar planes por el ejecutor normal (congelado)**:
- `AssistantApplyRequest`: lista de operaciones **tal como las devolvió el plan** (type, targetId, args) + baseRevision. Sin re-interpretación del texto: el apply es stateless.
- Revalidación servidora ANTES de ejecutar: revisión vencida → `REVIEW_REQUIRED` sin ejecutar nada; destino que ya no existe → `UNKNOWN_TARGET`; borrados con cascada → precondición `cascade_count` calculada por el servicio (CommandService la exige).
- `AssistantApplyService` construye `OperationEnvelope` por operación (origin=AI, idempotencyKey nuevo por paso, author del session, baseRevision re-leída tras cada paso) y ejecuta por `CommandService.execute`. **Política de atomicidad (cierra la guía §3.7): aplicación secuencial con corte al primer rechazo; la respuesta informa paso por paso (aceptado/rechazado + diagnostics); NUNCA se declara atómica.**
- Mapeo de payload por tipo: el worker debe leer cada handler y mapear exactamente (defaults defensivos declarados; argumento requerido faltante → paso rechazado con `MISSING_ARGUMENT`, no 500).
- `AssistantApplyResponse`: steps[{index, type, targetId, status, operationId, resultRevision, diagnostics}], finalRevision.
- Endpoint `POST /api/projects/{projectId}/assistant/apply`, mismas convenciones de auth que el plan.
- Web (transporte solamente): `web/src/api/assistant.ts` + `web/src/api/assistant.test.ts` con las convenciones de `client.ts`/`xmi.test.ts`. El panel completo es IA4-05.

## 8. IA4-03 cerrada + diseño congelado de IA4-04

**IA4-03 (7f7cab9, review-d5fbf8ccdabcd917 aprobada y quemada)**: apply por el ejecutor normal con revalidación servidora, corte al primer rechazo, cascade_count calculado; suite **381/0/0/0**, web 5/5 + tsc limpio; verificación independiente 7/7 HOLDS (incluye: el servicio no escribe repositorios, única escritura es commandService.execute). Avisos informativos a la deuda: R3-invalid-abstract-default (ApplyService:182-184), R3-stale-revision-rebase (:84-86).

**IA4-04 — undo/redo colaborativo (congelado)**:
- **Semántica**: deshacer = aplicar un COMANDO INVERSO nuevo (origin UNDO) por el pipeline normal de CommandService (revisión, membresía, idempotencia). Nunca snapshot: los cambios ajenos intercalados no se pierden porque el inverso se aplica hacia adelante sobre el estado actual.
- **Captura del valor previo**: hook en `api/ProjectApiService.executeCommand` (única entrada HTTP de comandos) que ANTES de ejecutar toma un snapshot de los targets del sobre y lo guarda en `undo_log` junto al command aceptado. Sin tocar CommandService.
- `undo_log` (migración nueva): id, model_id, user_id, command_id, type, pre_snapshot JSONB, undone_at nullable, redone_at nullable.
- `UndoService.undo(model)`: última entrada no deshecha del usuario; construye inverso por tipo: create→delete (si cascada >1 → RECHAZO CASCADE_REQUIRED declarado, sin trabajar), rename/setAbstract/update→set valor previo del snapshot, add→remove, remove→add (payload del snapshot), relationship create→delete / delete→create (extremos del snapshot). `xmi.import`: NO deshacible, declarado. El inverso se ejecuta por CommandService; al aceptarse se marca undone_at.
- `UndoService.redo(model)`: última entrada deshecha no rehecha (LIFO); re-aplica el payload ORIGINAL como comando nuevo (origin REDO, idempotencyKey NUEVO — replay con la misma clave devolvería el resultado viejo sin mutar); marca redone_at.
- Endpoints `POST /api/projects/{projectId}/undo` y `/redo`, auth idéntica a commands (miembros; operador excluido por la lista blanca).
- Pruebas test-first: inverso por tipo (8), undo de create con dependientes → CASCADE_REQUIRED, undo tras cambio ajeno intercalado no pierde el cambio ajeno, redo restaura, LIFO, xmi.import rechazado, auth.

## 9. IA4-04 cerrada + diseño congelado de IA4-05

**IA4-04 (b3752aa, review-dcca86811555d2c5 aprobada y quemada)**: undo/redo por comando inverso con captura de pre-estado en `undo_log` (V11); los pasos de la IA TAMBIÉN quedan capturados (desviación (b) cerrada con ROJO conductual: 3 tests que fallaban antes del arreglo); suite **403/0/0/0**. SchemaOwnershipTest: solo las dos entradas del mapa (grant acotado). Avisos informativos a la deuda: R3-null-attribute-restore (UndoService:174), R3-relationship-end-state (:229-230), R3-stale-redo-branch (:104). Deuda declarada: classifier.delete y relationship.delete no deshacibles; cascade-count calculado en 3 lugares; waypoints no restaurables.

**IA4-05 — panel del asistente en la web (congelado)**:
- `web/src/assistant/AssistantPanel.tsx` + `useAssistantPanel.ts`: estados observables listo/analizando/aclaración/vista previa/aplicando/completado/cancelado/error-recuperable (guía §5). Sin inferencia por frame: operaciones asincrónicas con `requestPlan`/`applyPlan` de `web/src/api/assistant.ts`.
- Aclaración: la pregunta del modelo se muestra y re-consulta con la respuesta del usuario como nueva utterance. Preview: lista de operaciones con etiquetas humanas + destino resuelto; **Confirmar solo habilitado para intent PLAN con operaciones**; Cancelar descarta sin mutar (la petición en vuelo se reconcilia, no se repite a ciegas — guía §3.8).
- Invalidación de preview obsoleto: si la revisión del modelo cambia (evento SSE `operation.applied` o dispatch propio) mientras hay preview, se marca STALE y obliga a re-planificar (guía §3.6). El conflicto 409 de la concesión se muestra con el contrato existente (`ApiTransportError.code`).
- Guardas de desmontaje: `mountedRef` en TODOS los escritores de estado (lección C21 del incremento 3, 13/13).
- Integración: montaje en `ProjectEditorPage.tsx` junto al panel de chat (edición acotada: el punto de montaje).
- Pruebas: markup/estados estáticos según la convención del proyecto (sin navegador); mutación de no-vacuidad para la guarda de desmontaje.

## 10. IA4-05 cerrada + diseño congelado de IA4-06

**IA4-05 (f81ed5a + corrección 7cbd917, review-c43ba1f19cee34e4 aprobada y quemada tras UNA corrección acotada)**: panel del asistente completo (estados idle/analyzing/clarify/preview+stale/applying/done/cancelled/error, aclaración con re-consulta, confirmar solo para PLAN con operaciones, invalidación por revisión, guardas mountedRef). El refutador confirmó un hallazgo CRÍTICO REAL (R3-001: cambiar de proyecto dejaba viva la preview del anterior → se podía aplicar al proyecto equivocado); corregido con reset por cambio de projectId + prueba no-vacua (15/15, lint/tsc/build limpios). Avisos informativos nuevos a la deuda: R3-002 (assistantController:123-124), R3-003 (:134). Web total: 188+1 pruebas.

**IA4-06 — herramientas y atajos manuales (congelado)**:
- Atajos en el lienzo (`web/src/diagram/UmlCanvas.tsx` o el hook del página): **Ctrl+Z → POST /projects/{id}/undo; Ctrl+Y y Ctrl+Shift+Z → /redo** (por el mismo transporte autenticado; la lógica es servidora). Escape deselecciona si ya existe. Delete/Backspace ya existe — no se toca.
- **Límites**: atajos solo con foco en el lienzo, jamás dentro de inputs/textarea/contentEditable (chequeo de event.target), y no interceptan combos reservadas del navegador. Tras undo/redo la página refresca el modelo por el camino existente (SSE o refetch directo) para mantener la revisión coherente.
- Herramientas contextuales mínimas en la barra existente: botones Undo/Redo (deshabilitados cuando el servidor responde NOTHING_TO_UNDO/REDO — el resultado se recuerda por sesión), alineación/distribución de la selección y ajuste a grilla **quedan para un incremento posterior** si el presupuesto de revisión aprieta: lo declaro AHORA — IA4-06 entrega SOLO undo/redo + Escape y la alternativa de teclado documentada; alineación/distribución/grilla van a la deuda visible con el visto bueno del usuario implícito en "hasta el final" (la guía las lista como IA4-06 pero la unidad se recorta declarando el corte).
- Pruebas test-first: hook del atajo (keydown sintético: Ctrl+Z llama undo solo con foco lienzo y no en input), estados disabled, mutación de no-vacuidad.

## 11. IA4-06 cerrada + diseño congelado de IA4-07

**IA4-06 (ef461b7, review-ff765df940a23637 aprobada y quemada)**: Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z → undo/redo servidores con guardas de foco (inputs/contentEditable/reservadas), Escape deselecciona, botones Undo/Redo con hints best-effort por sesión (el servidor es autoridad), refresco del modelo por el camino SSE existente. Alcance RECORTADO declarado: alineación/distribución/grilla quedan como deuda visible. Web 208/0, lint/build limpios. Avisos informativos: R3-stale-project-hints (useUndoRedoShortcuts:214-232), R3-unreported-rejections (:128-146). Nota: undoRedo.ts duplica el mapeo de error de client.ts porque el helper no es exportable (fuera de superficie) — deuda menor.

**IA4-07 — rutas ortogonales y estilo por vista (congelado; DEC-I4-07 se cierra acá)**:
- **DEC-I4-07 cerrada**: la apariencia por vista persiste en un comando versionado NUEVO `style.set` con migración `V12__style_sets.sql` (tabla `view_style` 1:1 con (view_id, targetType, targetId): routing VARCHAR, cornerRadius INT, strokeWidth INT, dashed BOOLEAN), NO en campos nuevos de `geometry.set` (la guía lo recomienda explícitamente: "acordar comando/campos versionados y migración en vez de enviar campos que se rechazan"). Handler con whitelist de campos; SchemaOwnershipTest actualizado (grant acotado ya establecido como práctica).
- Modos de ruta: recto (default actual), libre (waypoints existentes), ortogonal (generación client-side con esquinas; SIN prometer avoidance de nodos — guía: "no prometer avoidance sin algoritmo medido").
- Edición de recorrido: waypoints existentes ya editables (incremento previo); lo NUEVO es el modo ortogonal y el reset del recorrido (borrar waypoints).
- Etiquetas/extremos/marcadores/multiplicidad/navegabilidad: PRESERVADOS por diseño (estilo viaja separado de la semántica; el emitter/lector XMI no cambian).
- Pruebas: handler style.set (whitelist, campos inválidos 400, idempotencia), SchemaOwnership, determinismo del generador ortogonal, preservación semántica tras guardar/recargar.

## 12. IA4-07 cerrada (con burn pendiente) + arranque de IA4-08

**IA4-07 (bf348a0, review-3072e75ccb094da9)**: comando versionado `style.set` + migración V12__view_style.sql (nombre declarado como desviación cosmética del §11), handler con whitelist, vista de estilos en la lectura del modelo, routing STRAIGHT/FREE/ORTHOGONAL con generador ortogonal puro y determinista (sin avoidance), reset de recorrido, panel de propiedades con selector. Suite **414/0/0/0** backend, **222/0** web, lint/build limpios. Dos corridas de escritor (la primera abortada a los ~40 min por tamaño de unidad; la segunda audito/completó el backend ya completo; la tercera terminó la web y cazó un TDZ real). Revisión **aprobada** con avisos informativos R3-001 (orthogonal-routing:27-31) y R3-002 (V12:11).
- **PENDIENTE DE QUEMA**: el acknowledge-approved de este linaje está bloqueado por la compuerta DACL intermitente del host (5 intentos, "ace-type"). Estado durable: linaje review-3072e75ccb094da9, estado approved, store_revision sha256:4bd9d0c300b22fa090b33ae198f533e12a82351f970f15e7cf86e79caf47d6a1. Reintentar antes del cierre del incremento; la aprobación NO se considera quemada hasta el burn.

**IA4-08 — aceptación integrada (arranca)**: arnés Python `tools/assistant-acceptance/` (stdlib, patrón del arnés de colaboración) contra compose real + llama-server con el modelo 2B elegido: los 5 escenarios del enunciado (preview de plan multi-comando; explicar no muta; ambigüedad pregunta; inexistente rechaza; cambio remoto entre preview y confirm exige revalidación) + ciclo undo/redo + recarga. Se escribe primero, se corre dos veces después.

**Decisión del usuario (2026-10-04)**: ante la falla persistente de la compuerta DACL en el burn de review-3072e75ccb094da9, se SALTA el reintento por ahora y se continúa con IA4-08. El acknowledge-approved queda como acción reintentable al cierre; la unidad IA4-07 cierra con "aprobada, burn pendiente" (no se declara quemada).

## 13. INCREMENTO 4 CERRADO

- IA4-08 cerrada: arnés de 55 checks, **54/55 en dos corridas idénticas, exit 0** (`79cbb88`); 3 bugs de aserción del propio arnés corregidos con diagnóstico; único fallo = límite del modelo 2B (REJECT en vez de CLARIFY ante nombres parciales, del lado seguro).
- Los 5 escenarios del enunciado: 4 verdes, 1 con límite declarado del modelo (§4 del acta).
- Acta: `docs/puds/cierre/06-cierre-incremento-4.md` (estado medido, revisiones nativas, deudas, pendientes).
- Pendientes al cierre: burn de review-3072e75ccb094da9 (DACL), actualizar 02-esquema-fisico.sql (V13), push/merge (decisión del usuario).
- Suites al corte: backend 427/0/0/0 · web 222/0 · arnés 54/55 × 2.
