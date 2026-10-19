# Acta de cierre — Guía de la app: tour determinista + chat de ayuda con navegación

**Fecha:** 2026-10-16 · **Rama:** `feat/guia-asistente-app` (desde `main` = `eb5d9d7`) · **Plan:** [`odd/tasks/guia-asistente-app.md`](../../odd/tasks/guia-asistente-app.md)

## 1. El corte visible, con veredicto

> Un usuario que entra por primera vez a un proyecto ve un tour determinista de cinco pasos que le muestra
> qué hace cada parte del editor; puede reabrirlo cuando quiera. Un botón flotante de ayuda abre un panel
> propio donde pregunta «¿cómo exporto a XMI?» y recibe una respuesta corta basada en el inventario real de
> la app, con botones para abrir la sección relevante; nada de esto muta el modelo UML ni toca el chat del
> diagrama.

| Pieza | Veredicto | Evidencia |
| --- | --- | --- |
| Corpus cerrado de la app (T1) | **Cerrado** | `0ea918e`: 10 temas como módulo de datos puro, pinneado en las dos direcciones contra `editorSections.ts` (cada sección cubierta; ningún tema nombra una sección inexistente); regla de no-identificadores con prueba propia. 5 pruebas, RED→GREEN |
| Tour determinista (T2) | **Cerrado** | `782aa18`: pasos derivados del corpus (fuente única), progreso por proyecto en `localStorage` con clave versionada, apertura automática la primera vez que carga el modelo, botón «Show editor tour» para reabrir; 14 pruebas de ayuda, sin jsdom |
| Panel de ayuda + endpoint (T3) | **Cerrado** | `1f26f08`: `POST /help/guide` stateless autenticado por el filtro bearer; el corpus viaja como dato desde la web (sin duplicación); doble restricción de slugs (cliente y servidor); errores explícitos `PROVIDER_DISABLED` / `GUIDE_PROVIDER_FAILED` / `GUIDE_RESPONSE_INVALID`; panel con estado propio, independiente del chat del diagrama. `assistant/**` intacto |
| Navegación propuesta (T4) | **Cerrado** | `86529c8`: los slugs sugeridos se vuelven botones «Open …» solo para secciones reales; omite la ya abierta y deduplica; la navegación solo actualiza `openSection`, nunca el modelo; sin navegación automática |

## 2. Estado medido al cierre

| Superficie | Medición |
| --- | --- |
| Backend | `mvn -o -B verify` = **518 pruebas, 0 fallos**, 1 salteada, BUILD SUCCESS (sin red; vía `cmd.exe` porque WSL no define `JAVA_HOME`) |
| Editor web | vitest = **573 pruebas pasan, 4 saltadas** (56 archivos); `npm run lint` = 0 errores; `npm run build` en verde |
| Revisión nativa T1+T2 | `review-212619c7a21deb1c` = **approved**, autoridad quemada (`sha256:6af20076…`); 1 hallazgo informativo |
| Revisión nativa T1+T2+T3 | `review-271d53270ad5453d` = **approved**, autoridad quemada (`sha256:2c32454c…`); 5 hallazgos informativos |

## 3. Límites declarados, sin adorno

- **El guía corre sobre el modelo local 2B** (llama.cpp), con el corpus en contexto y respuesta breve.
  Si el proveedor está deshabilitado, el panel lo dice en vez de fallar en silencio. Sin fallback a nube.
- **El corpus describe la UI de hoy.** Si una sección cambia de nombre o desaparece, las pruebas del corpus
  fallan a propósito: actualizar el corpus es parte del cambio de UI.
- **Hallazgos informativos de las dos revisiones nativas** (7 en total, ninguno bloqueante): quedan como
  trabajo posterior en el Log del plan (L5), no como deuda de esta unidad.
- **Limpieza de vistas candidatas diferida** en las dos revisiones (`candidate-view-cleanup-failed`,
  diagnóstico nativo): reintentar o remover la vista fuera de banda. No afecta el producto.

## 4. Qué NO se verificó

- El guía contra el runtime real de llama.cpp en vivo (las pruebas usan proveedor stubbeado; el arnés del
  chat IA ya midió el runtime con 5 usuarios, y este endpoint reutiliza el mismo proveedor).
- El tour con navegadores sin `localStorage` disponible (modo privado estricto): el progreso simplemente no
  persiste y el tour reaparece; no se probó explícitamente ese entorno.
