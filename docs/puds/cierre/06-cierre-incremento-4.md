# Acta de cierre — Incremento 4: IA textual

**Fecha:** 2026-10-04 · **Rama:** `feat/incremento-4-ia-textual` (desde `main` = `4dac15e`) · **Estado:** cerrado con una quema de revisión pendiente por infraestructura (§6).

## 1. Qué quedó entregado

| Pieza | Evidencia |
| --- | --- |
| Evaluación de modelos locales (IA4-01) | Corpus de 15 casos versionado antes de medir (`tools/ai-eval/corpus.json`); 0.8B descartado (6/15 con prompt v2), **2B Q4_K_M elegido base (11/15)**; informe `docs/puds/verificacion/03-evaluacion-modelos-ia4-01.md`; DEC-I4-01/02 cerradas |
| Contrato de plan tipado + validador determinista (IA4-02) | Paquete `assistant` completo; provider deshabilitado por defecto; `73f9492` |
| Aplicación por el ejecutor normal (IA4-03) | `/assistant/plan` + `/assistant/apply` con revalidación servidora y corte al primer rechazo; `7f7cab9` |
| Undo/redo colaborativo (IA4-04) | Comando inverso por el pipeline normal, `undo_log` (V11), pasos de IA incluidos; `b3752aa` |
| Panel del asistente (IA4-05) | Estados observables, aclaración, preview con invalidación por revisión, guardas de desmontaje; `f81ed5a` + `7cbd917` |
| Atajos manuales (IA4-06, alcance recortado) | Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z → servidores; Escape; botones con hints; `ef461b7` |
| Estilo por vista + rutas ortogonales (IA4-07) | Comando versionado `style.set` + V12; generador ortogonal puro sin avoidance; `bf348a0` |
| Aceptación integrada (IA4-08) | Arnés de 55 checks: **54/55 en dos corridas** (`79cbb88`); único fallo = límite declarado del modelo (§4) |

**Defecto de fondo corregido por el arnés:** el validador rechazaba los planes que crean una clase y le agregan atributos en el mismo borrador (`10cd291`, simulación hacia adelante); el contrato de args quedó fijado en prompt v3 con tolerancia declarada (`2123ace`); el re-borrado de elementos recreados chocaba con `uq_tombstone_target` y se corrigió por migración V13 (unicidad por grupo de cascada).

## 2. Estado medido al corte

- Backend: `mvn verify` = **427 pruebas, 0 fallos, 0 errores, 0 omitidas**, BUILD SUCCESS.
- Web: **222 pasan / 4 omiten**, eslint + tsc + build Vite limpios.
- Arnés de aceptación: **54/55 checks, dos corridas idénticas, exit 0**; teardown `down -v` verificado con conteo 0.
- Evaluación de modelos: 2B p50 11,2 s por plan (CPU, ctx 2048); RAM del proceso observada en vivo 1.243 MB commit.
- El arnés de aceptación corre con el modelo real 2B y Docker compose reales; no usa dobles.

## 3. Los cinco escenarios del enunciado

| Escenario | Resultado |
| --- | --- |
| "Cree una clase Factura con el atributo fecha: Date y asóciela con Cliente" genera preview | ✅ plan de 3 operaciones en un solo borrador + apply ACCEPTED + modelo verificado |
| "Explique la relación" no muta | ✅ intent EXPLAIN sin operaciones; revisión sin cambios |
| "Elimine la clase" ambigua pregunta | ⚠️ **el 2B rechaza en vez de preguntar** (falla del lado seguro); declarado como límite del modelo, no del backend |
| "Elimine la clase Inexistente" rechaza | ✅ REJECT sin mutación |
| Cambio remoto entre preview y confirm exige revalidación | ✅ REVIEW_REQUIRED y el plan obsoleto no se reaplica |

## 4. Límites y deudas visibles (todas declaradas, ninguna oculta)

- **Modelo:** ambigüedad por nombre parcial → REJECT en vez de CLARIFY (2B); prompt v3 es la segunda y última iteración declarada; el 4B queda como opción solo si un umbral formal (DEC-I4-06) exigiera más.
- **Recorte de IA4-06 por decisión de alcance:** alineación/distribución/grilla quedan para trabajo posterior.
- **Undo:** `classifier.delete`, `relationship.delete`, `geometry.remove` y `xmi.import` no deshacibles; cascade-count calculado en tres lugares (deuda de duplicación); waypoints no restaurables por el inverso de borrado.
- **~15 avisos informativos** de las revisiones nativas (todos listados en `odd/tasks/incremento-4-ia-textual.md` §10–§12); ninguno abrió corrección salvo el CRÍTICO R3-001 del panel, corregido y validado.
- `docs/puds/datos/02-esquema-fisico.sql` todavía documenta la restricción vieja de tumbstones (docs fuera de superficie del escritor; pendiente de actualización doc).
- **Sin capturas de pantalla** (misma convención que el incremento 3: no hay entorno de navegador).

## 5. Revisiones nativas del incremento

| Unidad | Linaje | Estado |
| --- | --- | --- |
| IA4-02 | `review-ca65fe77e0a5a736` | aprobada y **quemada** |
| IA4-03 | `review-d5fbf8ccdabcd917` | aprobada y **quemada** |
| IA4-04 | `review-dcca86811555d2c5` | aprobada y **quemada** |
| IA4-05 | `review-c43ba1f19cee34e4` | aprobada (con 1 corrección acotada CRÍTICA) y **quemada** |
| IA4-06 | `review-ff765df940a23637` | aprobada y **quemada** |
| IA4-07 | `review-3072e75ccb094da9` | aprobada, **burn PENDIENTE**: la compuerta DACL del host rechaza el acknowledge ("ace-type", 6+ intentos). Decisión del usuario: saltar y seguir; reintentable con `gentle_review acknowledge-approved --lineage review-3072e75ccb094da9` |

El arnés de aceptación (IA4-08) y las correcciones de contrato (`10cd291`, `2123ace`) no pasaron por revisión nativa individual: quedan cubiertos por la suite (427/0) y las dos corridas del arnés, y declarados acá.

## 6. Pendiente al cierre

1. Reintentar el burn de IA4-07 cuando la compuerta DACL afloje (acción única, sin rediseño).
2. Actualizar `docs/puds/datos/02-esquema-fisico.sql` con la restricción V13.
3. Push de la rama y merge: decisión del usuario.
4. El escenario de ambigüedad queda como criterio de evaluación para el 4B si se lo evaluara.
