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
| 2026-10-04 | — | Plan congelado; exploración de la capa de comandos con path:line; rama creada desde `4dac15e` | (este commit) |
