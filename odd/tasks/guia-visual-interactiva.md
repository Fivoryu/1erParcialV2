# Guía visual interactiva del editor web

Rama: `feat/guia-visual-interactiva` · Worktree: `.atl/worktrees/guia-visual-interactiva` · Base: `064fa9418fce8c59d991fc8e56325ba6943e17c3`.
Estrategia de entrega elegida: `chain_strategy=feature-branch-chain` · Previsión inicial: más de 400 líneas · Verificación: Vitest, lint/build y navegador en estado aislado. Esta elección no autoriza publicar ni crear PRs.

## Specs

### S1
> Es completa?
> Es decir quiero que le pueda mostrar como una especie de animación donde se encuentran los botones o como añadir algo, que sea totalmente amigable al usuario no común.

Criterio derivado de la propuesta aceptada: una guía visual del editor web señala controles reales y explica acciones en lenguaje sencillo; acompaña las acciones de la persona sin ejecutar modificaciones por su cuenta. Ofrece volver, avanzar/saltar y cerrar; respeta movimiento reducido y controla objetivos ausentes o no disponibles.

### S2
> Sip, que sea completa :D

Interpretación de alcance: cubrir los temas del editor presentes en `web/src/help/helpCorpus.ts`, con indicaciones paso a paso y acceso a recorridos específicos desde la ayuda, sin depender de que la IA esté disponible. Conservar la ayuda separada del chat del diagrama, sus permisos y la confirmación explícita de acciones.

### S3
> Eso hazlo en un worktree

Realizar los cambios y las pruebas de la funcionalidad en el worktree indicado arriba, sin modificar los cambios existentes del árbol principal.

## Tasks

- T1 [in_progress] S1-S3 · inline: guía visual reutilizable y recorrido de creación/edición con sus pruebas y documentación · Risk: item 3 (los componentes compartidos también renderizan operaciones, literales y herramientas que no son clases) · commit pendiente.
- T2 [pending] S1-S3 · inline: recorridos de los demás temas del editor y acceso desde la ayuda, con pruebas y documentación · commit pendiente; depende de T1.
- T3 [pending] S1-S3 · inline + una verificación independiente por Risk item 3: suite, revisión de accesibilidad y prueba funcional de recorridos en navegador con datos aislados · commit pendiente; depende de T1-T2.

## Log

L1 — Solicitud original, textual:

> Es completa?
> Es decir quiero que le pueda mostrar como una especie de animación donde se encuentran los botones o como añadir algo, que sea totalmente amigable al usuario no común.

L2 — Confirmación textual: «Sip, que sea completa :D».

L3 — Ubicación textual: «Eso hazlo en un worktree».

L4 — Estado inicial: la suite web del árbol original pasó con 794 pruebas aprobadas y 4 omitidas; worktree creado desde `064fa94`. El árbol principal tenía cambios ajenos en backend y mobile, que se preservan. La guía anterior usa un corpus fijo, una ayuda separada y navegación confirmada; su recorrido no resalta controles.

L5 — El worktree quedó registrado. El intento de enlazar dependencias falló sin crear el enlace; `npm ci --offline --no-audit --no-fund` instaló 253 paquetes en el worktree (exit 0, advertencia de versión obsoleta de ESLint). T1 sigue en curso y sus pruebas RED y GREEN están pendientes.

L6 — RED observado en el worktree: `npm test -- src/help/tourSteps.test.ts src/help/TourOverlay.test.tsx src/diagram/ToolRail.test.tsx` salió 1 con 3 fallos esperados y 9 pruebas previas aprobadas; faltan implementación, GREEN y verificación funcional. T2 y T3 permanecen pendientes.

L7 — GREEN enfocado en el worktree: `npm test -- src/help/tourSteps.test.ts src/help/TourOverlay.test.tsx src/diagram/ToolRail.test.tsx src/diagram/uml-node.test.ts` salió 0 con 37 pruebas aprobadas. El recorrido de clases está implementado, pero faltan lint, documentación y prueba visual/funcional; T1 no se considera cerrado. T2 y T3 siguen pendientes.

L8 — `npm run lint` salió 0 y se actualizó el manual del recorrido de clases. La comprobación visual y funcional en navegador aún no se hizo, por lo que T1 continúa en curso; T2 y T3 continúan pendientes.

L9 — El chequeo de disponibilidad desde WSL falló (curl exit 7); el servidor Vite sí informó listo en Windows y `cmd.exe /c "curl --silent --show-error --fail --max-time 3 http://127.0.0.1:4177/ -o NUL"` salió 0. La prueba visual sigue pendiente y debe usar el navegador de Windows.

L10 — Ego Browser abrió el editor del worktree con un backend simulado en memoria y mostró la instrucción y el contorno del botón «Clase»; captura local `.atl/checks/guide-initial.png`. Falta ejecutar y verificar las acciones del recorrido, por lo que T1 continúa en curso.

L11 — En el navegador aislado, el clic creó una clase y resaltó su nombre; la persona de prueba la renombró «Cliente», agregó el atributo y lo renombró «correo». Se observaron una vez cada uno de `classifier.create`, `classifier.rename`, `attribute.add` y `attribute.update`, más `geometry.set`; el servidor es simulado en memoria. La captura reveló que la tarjeta era transparente porque `--surface` no existe en el tema: tras una prueba RED específica se cambió a `--paper`, la prueba enfocada pasó (4/4) y el navegador confirmó fondo opaco. Falta verificar navegación y cierre antes de cerrar T1.

L12 — En el mismo navegador se probaron «Anterior», «Siguiente» entre subpasos y temas, y «Finalizar recorrido»: no apareció ningún comando nuevo y se registró `seen` en la memoria simulada del navegador. `gentle-ai review mode status` indica RDD activo. La evaluación nativa previa al commit devolvió `nativeRisk: unassessable` porque hay archivos nuevos sin declarar; no autoriza inferir un nivel bajo. La evaluación del rango confirmado queda pendiente para después del commit.

L13 — Las cuatro suites enfocadas pasaron con 38 pruebas. El lint posterior falló con `TS2307` para `node:fs` en la prueba nueva; se aplicó la misma anotación de tipos usada en las pruebas existentes y `npm run lint` volvió a pasar (exit 0).

L14 — La persona eligió la cadena sobre la rama de la función (`chain_strategy=feature-branch-chain`), sin autorizar publicación. El primer corte previsto contiene T1 con pruebas y manual; se registrará su commit y el límite de la primera revisión al confirmarlo. Risk: item 3 por componentes compartidos; ASSESS con `escalate.item=3` mantuvo `nativeRisk: unassessable` y pidió una verificación independiente. El trabajo inline de la función recibirá una verificación independiente al final en T3.
