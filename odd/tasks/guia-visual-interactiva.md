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

- T1 [done] S1-S3 · inline: guía visual reutilizable y recorrido de creación/edición con sus pruebas y documentación · Risk: item 3 (componentes compartidos) · commit `9378cbbaf6b07c299407b9b809537e233513c571`; verificación independiente de la función en T3.
- T2 [done] S1-S3 · inline: recorridos de los demás temas del editor y acceso desde la ayuda, con pruebas y documentación · Risk: item 3 (componentes y navegación compartidos) · commit `dd63708478281482c9af6d2f419b1903da908055`; verificación final en T3.
- T3 [in_progress] S1-S3 · inline + una verificación independiente por Risk item 3: suite, revisión de accesibilidad y prueba funcional de recorridos en navegador con datos aislados · Risk: item 3 (guía y selectores compartidos) · commit pendiente; depende de T1-T2.

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

L14 — La persona eligió la cadena sobre la rama de la función (`chain_strategy=feature-branch-chain`), sin autorizar publicación. Risk: item 3 por componentes compartidos; ASSESS previo con `escalate.item=3` mantuvo `nativeRisk: unassessable` y pidió una verificación independiente. El trabajo inline de la función recibirá una verificación independiente al final en T3.

L15 — T1 confirmado como `9378cbbaf6b07c299407b9b809537e233513c571`, primer corte desde `064fa9418fce8c59d991fc8e56325ba6943e17c3`: incluye comportamiento, pruebas y manual; `git diff --cached --check` pasó. Un primer intento de commit falló por el manejo de comillas de `cmd.exe` sin crear commit; se corrigió usando un archivo de mensaje local. ASSESS del rango confirmado devolvió `nativeRisk: medium`, `reviewDue: false`, `reviewDueReason: under_budget`, 470 líneas; la escalación del item 3 requiere verificación independiente en T3. No se inició revisión nativa ni publicación. T2 comienza.

L16 — RED de T2: `npm test -- src/help/tourSteps.test.ts src/help/HelpGuidePanel.test.tsx src/help/TourOverlay.test.tsx` salió 1 con cuatro fallos esperados y doce pruebas aprobadas. Las pruebas piden un recorrido para cada tema, acceso desde ayuda sin IA y aislamiento del tema elegido; la implementación y GREEN están pendientes.

L17 — GREEN inicial de T2: las cuatro suites enfocadas pasaron con 41 pruebas y `npm run lint` salió 0. Se añadieron pasos para los doce temas, selección desde Ayuda sin IA y montaje de la guía dentro del diálogo activo; estos últimos comportamientos aún requieren prueba funcional real y no cierran T2. El recorrido de clases añadió indicaciones sobre operaciones, relaciones y validación.

L18 — En navegador con estado simulado, Ayuda abrió y desplegó los recorridos sin solicitud adicional ni comandos; la selección de Intercambio XMI abrió su panel solo tras el clic y resaltó Exportar y el selector de archivo. La guía quedó dentro del diálogo accesible, Tab permaneció dentro y Escape restauró el foco al botón XMI; no se exportó, importó ni modificó el diagrama. Sigue pendiente comprobar los demás temas y el reinicio de un recorrido.

L19 — El navegador detectó que la tarjeta del recorrido tapaba el panel Ayuda al reabrirlo. La prueba de pausa falló en RED y pasó en GREEN (6/6); se pausa la guía mientras Ayuda está abierta y se reinicia la instancia al elegir un tema. Una prueba con recarga perdió su fixture porque la inyección CDP pertenecía a una conexión anterior, por lo que mostró el inicio de sesión y agotó el tiempo; no se trató como aprobación. Con el fixture reinyectado en la misma sesión, el navegador confirmó pausa, reselección del mismo tema desde su primer paso y cero comandos.

L20 — Un navegador con datos simulados abrió los doce temas desde Ayuda: once mostraron el control real resaltado y el menú contextual sin clase mostró la indicación de crear o seleccionar una. Los paneles guiados quedaron dentro de sus diálogos y no se registraron comandos nuevos ni otras mutaciones entre el inicio y el fin de la prueba. El manual ya describe los doce temas y el acceso sin IA. Una prueba RED reveló que el recorrido pedía crear clases a quien solo puede leer; se agregó una explicación específica y se impidió el avance automático de esos pasos. GREEN unitario 7/7; el navegador confirmó el aviso de lectura, pero la parte de invitaciones sigue pendiente porque la opción quedó fuera del área visible.

L21 — El espacio excesivo procedía de `.quiet-state` (altura mínima de 105 px), heredado en el panel compacto. Se reprodujo visualmente, se agregó una prueba RED y GREEN (7/7) y se limitó el estilo en Ayuda y las pistas del recorrido. El navegador mostró la opción de integrantes dentro del panel; con rol `VIEWER` simulado no apareció el formulario exclusivo de propietarios, la guía explicó el permiso requerido y el contador de comandos permaneció en cero.

L22 — T2, comprobaciones previas al commit: `npm test -- src/help/tourSteps.test.ts src/help/HelpGuidePanel.test.tsx src/help/TourOverlay.test.tsx src/diagram/ToolRail.test.tsx src/diagram/uml-node.test.ts` pasó 49 pruebas; `npm run lint` salió 0. Prueba funcional: inicios de los doce temas, selector de XMI, foco y rol lector en navegador con servidor simulado. La suite completa, la revisión independiente, accesibilidad y el resto de pasos siguen en T3. El segundo corte preparado suma 548 líneas añadidas o eliminadas (aproximadamente 37 % sobre el objetivo de 400) por reunir el catálogo, la integración, sus pruebas y el manual; no se eliminaron pruebas ni documentación para reducirlo. La reversión del corte no debe tocar cambios de backend o mobile del árbol principal.

L23 — T2 confirmado como `dd63708478281482c9af6d2f419b1903da908055`, segundo corte desde `9378cbbaf6b07c299407b9b809537e233513c571`. ASSESS del rango confirmó `nativeRisk: medium`, `reviewDue: false`, `reviewDueReason: under_budget`; Risk item 3 mantiene la verificación independiente en T3. No se inició revisión nativa ni publicación. T3 comienza: suite completa, build, navegador en tamaños reducidos y verificación independiente están pendientes.

L24 — En el worktree, la suite web completa pasó con 804 pruebas aprobadas y 4 omitidas (81 archivos aprobados, uno omitido); `npm run build` salió 0. En un navegador aislado de 375 × 812, el primer control resaltado quedó fuera de la tarjeta, la animación calculada fue `none` con movimiento reducido y no se registraron comandos. La verificación independiente T3 se lanzó como tarea `mvtwgdbb-2-8t0t`; subpasos, teclado y su veredicto siguen pendientes.

L25 — Un recorrido automatizado visitó los 41 subpasos de los 12 temas en datos aislados, con texto y resaltado o alternativa y sin comandos ni mutaciones nuevas; las verificaciones de cada objetivo permanecen acotadas a ese fixture. Otra prueba de navegador RED reveló que a 375 × 812 la tarjeta se superpone al botón de Ayuda y recibe su clic: Ayuda queda inaccesible mientras el recorrido está abierto. Cero comandos. Se notificó al verificador; la corrección y GREEN están pendientes.

L26 — Con teclado, Enter activó Siguiente, Anterior y Finalizar. El primer chequeo falló al exigir cero solicitudes absolutas: el fixture ya había registrado durante el arranque un `DELETE` de `editing-lease`. Tras comparar contra la línea de base observada, la repetición pasó sin solicitudes adicionales ni comandos (0/0 comandos, 1/1 otras solicitudes). Se mantiene la falla móvil abierta y la verificación independiente en curso.

L27 — La comprobación RED a 320 × 568 también mostró el botón de Ayuda tapado. Tras centrar el botón de clase, la comprobación de clic lo encontró inaccesible aunque su rectángulo no se cruzaba con la tarjeta; se está identificando qué elemento lo intercepta antes de elegir la corrección. Los resultados en móvil siguen pendientes.

L28 — El verificador independiente devolvió S1 y S2 parciales, S3 aprobado, 49 pruebas enfocadas aprobadas; confirmó la superposición móvil y señaló una carrera en la que el rol todavía es `null` y se muestran instrucciones de edición a una posible persona lectora. La reproducción del rol retrasado y una corrección conjunta están pendientes. En la inspección posterior de 320 × 568, el clic sí llegó al hijo del botón de clase: el chequeo inicial tomó la posición durante el desplazamiento, por lo que no prueba un segundo bloqueo del control.

L29 — El navegador reprodujo la carrera: con dos respuestas de integrantes pendientes y cero comandos, la guía indicaba crear una clase antes de conocer el rol. Se escribieron pruebas RED para rol desconocido y posición móvil; Vitest salió 1 (dos aserciones fallidas y un módulo de ubicación aún ausente, siete pruebas previas aprobadas). Se implementó un aviso neutral mientras no se conozca el rol y una posición adaptable que deja espacio a Ayuda. Las pruebas GREEN, tres navegadores y la revalidación independiente están pendientes.

L30 — GREEN enfocado: 11 pruebas de rol y ubicación móvil aprobadas. En navegador, con rol retrasado se mostró el aviso neutral, y al resolver como `VIEWER` se mostró el aviso de lectura, con cero comandos; a 375 × 812 Ayuda quedó accesible y la guía se pausó al abrirla. La prueba a 320 × 568 siguió fallando al verificar el clic; tras cambiar la espera del probe, agotó 10 segundos y sigue en diagnóstico. Suite, lint, build y la revalidación independiente están pendientes.

L31 — Tras esas correcciones, la suite pasó con 808 pruebas aprobadas y 4 omitidas; lint y build salieron 0. El diagnóstico con el viewport de 320 × 568 en la misma conexión identificó que `scrollIntoView` centra la clase debajo del encabezado fijo: el punto de clic recibe «Integrantes», no la clase. Las inspecciones posteriores fuera de esa conexión no demuestran el tamaño móvil. También se identificó que el selector de validación apunta a un atributo inexistente aunque `.validation-strip` está presente. Hay pruebas RED y correcciones de estos dos hallazgos pendientes; las comprobaciones previas no prueban el siguiente cambio.

L32 — RED: la prueba de offset bajo encabezado falló (1/3) y el navegador encontró la franja de validación sin resaltar, con cero comandos. Se corrigió el desplazamiento y el selector, y GREEN enfocada pasó 12 pruebas. En navegador aislado de 320 × 568, «Mostrar dónde está» dejó accesibles tanto la clase como Ayuda y no emitió comandos; el objetivo de validación real quedó resaltado. La espera agotada anterior se conserva como fallo histórico. Se añadió una regresión unitaria del selector; suite/lint/build finales y revalidación independiente siguen pendientes.

L33 — RED adicional en navegador de 375 × 812: dentro de Intercambio XMI, la exportación estaba accesible, pero la tarjeta del recorrido cubría el botón de cerrar diálogo. No se ejecutaron comandos. Se notificó al verificador en curso y se está corrigiendo en el mismo lote; la suite que comenzó antes del ajuste ya no servirá como prueba final del nuevo contenido.

L34 — RED unitaria del cierre móvil observada (1 falla). La tarjeta ahora ocupa espacio dentro del diálogo en lugar de superponerse; GREEN enfocado pasó 13 pruebas. En navegador aislado de 375 × 812 se verificó que Exportar y Cerrar diálogo estaban accesibles, y a 320 × 568 seguían accesibles la clase y Ayuda; cero comandos en ambos. El verificador pidió probes sin capturas escritas en el repositorio y se prepararon copias locales sin escritura. Faltan la revalidación y suite/lint/build definitivos.

L35 — La suite, lint y build finales están en curso (`job-77`) y se entregaron los probes sin escritura al verificador (`mvtxgq7e-3-2y6q`). ASSESS del lote final agotó el tiempo y devolvió `nativeRisk: unassessable`, sin `reviewDue`; no se reintentó ni se inventó una revisión. Mantiene la verificación independiente por Risk item 3. El mensaje de respuesta directa al verificador devolvió `query is unavailable`; se encoló la misma información mediante su canal de mensajes.

L36 — La suite definitiva del worktree salió 0: 810 pruebas aprobadas y 4 omitidas (82 archivos aprobados y uno omitido); `npm run lint` y `npm run build` salieron 0. La revalidación independiente sigue pendiente. El `timeout` de ASSESS sigue registrado como no disponible; no se inició revisión nativa ni publicación.

L37 — La revalidación independiente puntual aprobó los bloqueos de Ayuda y clase en móvil, cierre XMI, rol desconocido/lector y objetivo de validación; sus pruebas enfocadas pasaron 13/13 y sus cinco probes sin escritura no emitieron comandos. S1 y S2 aprobaron para esos bloqueos y S3 para el worktree. Límite: la ausencia de avance automático mientras el rol es desconocido se corroboró por el guard del código, no con un clic en ese objetivo; no se repitieron todos los temas en esa revalidación. El padre está repitiendo una prueba puntual antes del commit final.

L38 — La prueba puntual repetida por el padre después del verificador pasó 13/13. Las correcciones y pruebas de T3 están preparadas para su commit; 4 pruebas de la suite permanecen omitidas y ASSESS del lote final no estuvo disponible por timeout. No hubo push ni PR.
