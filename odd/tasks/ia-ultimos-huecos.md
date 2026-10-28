# IA: cerrar los huecos prioritarios (movimiento por IA y ensayo integral)

Branch: feat/vcs-diffs-and-authors. Delivery: ask-on-risk. Test runners: mvn (backend), vitest (web), flutter test (mobile).

## Specs

S1. "Mover una clase mediante IA — faltante confirmado. El catálogo de operaciones permitido al asistente no incluye movimiento ni geometría; su validador rechaza operaciones fuera de ese catálogo. El movimiento manual sí existe." (AssistantPlanCatalog.java:7)
S2. "Fotografía a propuesta UML. La ruta visual existe y se probó con una imagen UML sintética. Lo que no encontré demostrado es el recorrido completo: foto → propuesta editable → revisión → validación → aplicación al diagrama."
S3. "Voz aplicada al diagrama. El micrófono está conectado y agrega el dictado al chat, pero queda por demostrar que una instrucción hablada termine como un cambio validado del modelo."
S4. "Ensayo integral con un dominio nuevo. [...] colaboración, texto, voz, fotografía, XMI, backend generado, cliente móvil e IA sin conexión."
S5. "Dale con lo siguiente, hasta comprobar el último punto"

## Tasks

- T1 (S2) foto -> propuesta -> aplicar, verificar en el editor web — inline — estado: parcial (la visión lee la foto; "Sugerir cambio" no envía la imagen y devolvió prosa sin propuesta).
- T2 (S1) `classifier.move` en catálogo, validador, apply (geometry.set), prompt y etiqueta web — inline — Risk item 3 (catálogo consumido por el asistente; es un añadido, no un cambio de contrato).
- T3 (S3) voz -> cambio validado — inline.
- T4 (S4) ensayo integral dominio nuevo — inline.

## Log

L1 (2026-10-28) user: "Dale con lo siguiente, hasta comprobar el último punto" (tras la lista de huecos pegada con los puntos 1 a 5).
L2 (2026-10-28) user eligió (a): dejar la instancia AWS encendida aunque cobre tras el crédito.
L3 (2026-10-28 12:20) T2 hecho y verificado en AWS: `classifier.move` (catalogo, validador, apply -> geometry.set, prompt v7, etiqueta web); 86 tests backend de asistente+undo y 51 web en verde; la IA propuso "Mover clase Repartidor a (736, 316)" y la posicion quedo aplicada (rev 55 -> 56). Risk: item 3 (aditivo, prompt v7 tambien corrige Date -> LocalDate).
L4 T3 voz: verificado con un SpeechRecognition simulado (dictado -> borrador -> Sugerir cambio -> aplicar: Restaurante.telefono, rev 57); el audio real depende de la nube del navegador y NO se probo.
L5 T4 ensayo integral (dominio Biblioteca): texto->IA (clases, relaciones, atributos con LocalDate) por API del asistente, XMI exportar/importar a un proyecto nuevo (4/3/9 identicos), segundo usuario EDITOR que edita, backend generado y desplegado (UP, puerto 19002). PENDIENTE: pierna movil de Biblioteca (el telefono estaba en otra app).
L6 Hallazgo: un plan con una operacion sin destino se devuelve como REJECT y el chat muestra la explicacion del modelo ("Se han creado...") sin los diagnosticos; "Sugerir cambio" no envia imagenes adjuntas.
