# IA colaborativa simultánea y foto → diagrama → Enterprise Architect
Rama: feat/ia-colaborativa-foto-ea (worktree, base f4a0bee) · Entrega: ask-on-risk · Pruebas: `mvn -o -B -f backend/pom.xml test -Dtest=...` (backend), `npx vitest run` en `web/` (web)

## Specs
- S1 (punto 3 del examen) "la aplicación permite a la IA local actuar como un desarrollador que trabaja de manera colaborativa simultánea atendiendo a varios desarrolladores".
- S2 (punto 5) "La herramienta permite tomar foto a un diagrama de clase para su correspondiente edición mediante IA permitiendo importar y exportar hacia architect".
- S3 Una foto adjunta en el chat se puede convertir en una PROPUESTA de cambios al diagrama (misma revisión humana de aceptar/rechazar que "Sugerir cambio"); la IA nunca escribe directo en el modelo.
- S4 El botón de adjuntar foto abre la cámara en el teléfono (`capture="environment"`).
- S5 Prueba automatizada del recorrido completo: exportar XMI y volver a importarlo conserva el modelo (ida y vuelta con EA).
- S6 El estado en vivo de la IA (`chat.ai.status`) dice quién hizo la pregunta, para que varios desarrolladores vean a quién atiende la IA.
- S8 (L3) "Exportar e importar, quiero que la IA pueda hacer eso. Sobre toda la de exportar [importar], si se le pasa un archivo XMI que lo importe al diagrama sea capaz de poder resolver problemas o conflictos, pero que la importaión al final este limpia y sin errores".
- S9 (supuesto, diseño del explorador) Importación asistida: la vista previa es un ensayo (lista de problemas BLOQUEANTE/AVISO); reparaciones deterministas con una sola respuesta correcta; la IA solo elige entre opciones cerradas (renombrar, omitir, vista a conservar); resultado verificado con cero bloqueantes y cero errores de `ModelValidator` antes de confirmar; la IA nunca elige REEMPLAZAR.
- S7 (supuesto) Propuestas obsoletas: no se cambia el comportamiento (decisión de diseño documentada: se aplica contra la revisión viva y la vista previa avisa).

## Tasks
| ID | Specs | Ruta | Estado | Commit |
| --- | --- | --- | --- | --- |
| T1 | S3, S4 | delegada (writer) + cableado web inline | hecho (ChatProposalApiTest 9, ChatAttachmentApiTest 7, web 187, tsc, lint) | ver git log |
| T2 | S5 | inline | hecho (XmiExportApiTest 6) | ver git log |
| T3 | S6 | inline | hecho (ChatAiApiTest 9, web chatState/ChatPanel) | ver git log |
| T5 | S8, S9 | delegada tras T1 (writer backend) + web | pendiente | — |
| T4 | S1, S2 | actualizar Google Doc + manual | pendiente | — |

## Log
- L1 (2026-11-10) "En un worktree haz el punto 3 y 5, es decir que analices lo que falta en el software y de ahí lo implementas, ya que otro agente se esta encargando de UML en el local."
- L2 Análisis (explorador): existe cola de IA (1 hilo, cola de 4, una petición pendiente por usuario), SSE por proyecto, propuestas con aceptación atómica, subida de fotos solo a respuestas de texto del chat; exportar/importar XMI con panel. Faltan: foto→propuesta, captura de cámara, prueba export→import encadenada, solicitante en el estado en vivo.
- L3 (2026-11-10) "Exportar e importar, quiero que la IA pueda hacer eso. Sobre toda la de exportar, si se le pasa un archivo XMI que lo importe al diagrama sea capaz de poder resolver problemas o conflictos, pero que la importaión al final este limpia y sin errores"
- L4 Mapa de importación (explorador): la vista previa no valida (solo lee); el handler rechaza duros: vistas != 1, figuras/relaciones sobre clasificadores no soportados, ids duplicados, nombres repetidos, anidamiento inválido, colisiones en AGREGAR; `ModelValidator` R1-R9 no se usa al importar; el asistente no tiene herramientas XMI; el banner web de colisiones nunca se activa (espera `NAME_COLLISION_BLOCKS`, el import emite `PRECONDITION_FAILED`); `/commands` genérico permite `xmi.import` sin checksum de bytes.
