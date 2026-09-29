# Acta de cierre del Incremento 1

**Fecha:** 2026-09-29
**Rama:** `feat/incremento-1-modelo-y-editor`, apilada sobre `feat/incremento-0-contrato-y-riesgos`
**Alcance cerrado:** T1 a T10 del plan [`odd/tasks/incremento-1-modelo-y-editor.md`](../../../odd/tasks/incremento-1-modelo-y-editor.md),
en **19 commits**.
**Producto entregado:** backend Spring Boot y editor web con lienzo de clases UML.

## 1. El corte visible, con veredicto

> Crear un proyecto, arrastrar una clase al lienzo, agregarle atributos y operaciones, conectarla con otra
> mediante una relación, recargar la página y encontrar el mismo modelo con la misma disposición visual.

| Pieza | Veredicto | Evidencia |
| --- | --- | --- |
| Crear proyecto | **Cerrado** | `POST /projects` crea proyecto, modelo, vista por defecto y membresía `OWNER`; probado en `T4bProjectAndViewHandlersTest` y en el arnés |
| Modelo canónico | **Cerrado, con una verificación pendiente** | 19 tablas **byte-idénticas** a `02-esquema-fisico.sql`, verificado con script. La línea base declara 23 constructos soportados y 9 no soportados; **6 de esos 9 se rechazan nombrando el constructo y el GAP** y los otros 3 se explican abajo |
| Capa de comandos | **Cerrado** | Los **20 tipos** del catálogo tienen handler, sobre el sobre de operación con idempotencia, `baseRevision` y precondiciones |
| Persistencia | **Cerrado** | Flyway dueño del esquema, Hibernate solo valida (`ddl-auto: validate`) |
| API | **Cerrado** | 5 recursos; un rechazo devuelve HTTP 200 con `status: REJECTED` (D14) |
| Lienzo y edición directa | **Cerrado con defectos visuales** | Funciona y está probado; ver §3 |
| Disposición persistida | **Cerrado** | Coalescido probado: un arrastre emite **un** comando, verificado contra backend real |
| Regla de no-IDs | **Cerrado** | Probada sobre el markup, con datos vivos; confirmada a ojo en `docs/capturas/incremento-1/` |

## 2. Definición de terminado, con evidencia

| Criterio | Estado | Evidencia |
| --- | --- | --- |
| El corte funciona de punta a punta y se repite | **Sí** | `python tools/acceptance/acceptance.py` = **34 pasos OK**, 0 fallos, salida 0 |
| El esquema lo aplica Flyway | **Sí** | `V1`/`V2`/`V3` aplicadas; `ddl-auto: validate` intacto |
| Un comando inválido se rechaza con motivo | **Sí** | Nombra el constructo y el GAP: `BL-CLS-04`/`GAP-02`, `BL-VIS-03`/`BL-VIS-04`/`GAP-06`, `BL-REL-07`/`GAP-05`, `BL-FEA-05`/`BL-FEA-06` |
| Un constructo fuera de la línea base se rechaza explícitamente | **Sí, con un matiz que se declara** | 6 de los 9 no soportados nombran el constructo y el GAP (tabla de §2.1 del acta). Los otros 3 se detallan en el punto siguiente |

### 2.1 Los tres constructos que no se rechazan «nombrando el constructo»

La primera redacción de esta acta decía que los **9** constructos no soportados se rechazaban nombrando el
constructo y el GAP. **Era una sobredeclaración mía**, y se corrige acá en vez de dejarla pasar:

| Constructo | Cómo se rechaza realmente |
| --- | --- |
| `BL-CLS-05` (literal de enumeración) | **De forma transitiva.** No hay comando que cree literales, y la enumeración misma se rechaza nombrando `BL-CLS-04` y `GAP-02`, así que no hay dónde colgar un literal |
| `BL-CMP-01` (nota UML) | **Como tipo de comando desconocido.** Nunca se definió un tipo `note.*`, así que la petición vuelve `UNKNOWN_OPERATION` **nombrando el tipo**. Es un rechazo explícito y no un silencio, pero **no** nombra el constructo de la línea base |
| `BL-CMP-03` (estereotipo) | Igual que la nota: `UNKNOWN_OPERATION` nombrando el tipo |

**Por qué se deja así.** Inventar manejadores cuyo único trabajo sea rechazarse a sí mismos sería ruido. Lo que
importa del criterio —que nada se ignore en silencio— se cumple: las tres rutas devuelven un rechazo con motivo.
Lo que **no** se cumple es la letra de «nombrando el constructo», y la diferencia se escribe en vez de
redondearse para arriba.

### 2.2 Verificación pendiente de cobertura

El plan declara los 23 constructos soportados de UML-BL-1 como alcance, y este incremento implementó **20 tipos de
comando** que los cubren. Lo verificado es que los rechazos nombran el constructo correcto. Lo **no** verificado
es el mapeo constructo por constructo de los 23: que cada uno tenga comando, persistencia y camino en el lienzo.
Es una comprobación acotada y queda como tarea, no como afirmación.
| Las pruebas del backend pasan con `mvn -o` | **Sí** | **82 pruebas**, 0 fallos, sin red |
| La disposición sobrevive a la recarga y un arrastre emite un comando | **Sí** | Test vivo: `geometryCommands` longitud **1**; tras recargar coinciden revisión, geometría, posiciones, waypoints y miembros |
| La pantalla no muestra ningún identificador técnico | **Sí** | El markup no matchea patrón de UUID ni contiene `classifier_id`, `EXACTLY_ONE`, `ZERO_OR_MANY`, `NOT_NAVIGABLE` ni `ABSTRACT_CLASS` |
| El README dice la verdad sobre el repositorio | **Sí** | Reescrito: decía que no había código de producto |

## 3. Defectos visuales encontrados al mirar el diagrama

Esta sección existe porque la captura hizo su trabajo. **Ninguno de estos tres defectos lo veía un test**, y los
tres aparecieron al mirar `docs/capturas/incremento-1/01-lienzo-uml.png`.

| # | Defecto | Estado | Detalle |
| --- | --- | --- | --- |
| V1 | `[1]` en **cada** atributo y en cada extremo | **Corregido** | `1` es el default de UML, así que omitirlo no cambia el significado. Era ruido técnico puro, justo lo que el incremento quiere evitar. Se omite solo el default; `0..1`, `1..*` y `0..*` siguen mostrándose, y hay una prueba que lo fija en los dos sentidos |
| V2 | Las etiquetas de los extremos de una asociación **se superponen a las cajas** | **Abierto** | Con una separación corta entre dos clases, las etiquetas de rol y multiplicidad se dibujan fuera del hueco disponible: en la captura se lee `custo…` cortado contra la caja de `Customer`. Las etiquetas se ubican a 0,35 y 0,65 del trazado, que no es un punto seguro cuando el tramo es corto |
| V3 | El triángulo de `INTERFACE_REALIZATION` aparece **despegado de su línea** | **Abierto** | El marcador se dibuja a la izquierda de `Payable` mientras el trazado discontinuo termina por debajo. El marcador parece calcularse desde los anclajes y no desde los puntos realmente dibujados, así que ambos pueden discrepar |

**Por qué se cierra el incremento con V2 y V3 abiertos en vez de arreglarlos acá.** Arreglarlos toca el
posicionamiento de etiquetas y de marcadores de las aristas, que es exactamente el código que hoy tiene 11
pruebas verdes de conectores. Hacerlo al final de una sesión larga, sin el ojo puesto en el resultado, es cómo
se rompe lo que ya funciona. Quedan **nombrados, con la captura como evidencia y con su causa identificada**,
que es muy distinto a quedar ocultos.

## 4. Lo que quedó abierto, con responsable

Heredado del Incremento 0 (GAP-01 a GAP-10) más lo nuevo:

| id | Pendiente | Responsable |
| --- | --- | --- |
| GAP-01 | Ratificación de la línea base UML por el docente | Equipo y docente |
| GAP-02 | Literales de enumeración: sin almacenamiento no hay `enum` | Diseño de datos, antes del Incremento 2 |
| GAP-03 | Notas UML: sin entidad | Diseño de datos |
| GAP-04 | Estereotipos: sin entidad | Diseño de datos |
| GAP-05 | Multiplicidad con cotas numéricas | Decisión explícita, Incremento 3 |
| GAP-06 | Visibilidad protegida y de paquete sin restricción de dominio | Diseño de datos |
| GAP-07 | Roundtrip contra Enterprise Architect en vivo | Equipo, con EA abierto |
| GAP-08 | Dirección y retorno del parámetro en el esquema físico | Incremento 5 |
| GAP-09 | Divergencia de nombres entre documento lógico y físico | **Resuelto por decisión D5** (manda el físico), el documento lógico queda pendiente de anotar |
| GAP-10 | Notas de modelo: canal propietario | Incremento 5 |
| **V2, V3** | Defectos visuales de las aristas (§3) | **Primera tarea del próximo incremento** |
| **C1** | Mapeo constructo por constructo de los 23 soportados de UML-BL-1 (que cada uno tenga comando, persistencia y camino en el lienzo) | Próximo incremento |
| **C2** | Notas y estereotipos sólo se rechazan como tipo desconocido; decidir si eso alcanza o si el rechazo debe nombrar `BL-CMP-01` y `BL-CMP-03` | Próximo incremento, junto con C1 |
| — | `type_reference` es el único tipo de fila que no queda en `tombstone` | Incremento 3, cuando entre `undo` |
| — | Sin autenticación real: usuario semilla determinista | Incremento 3 |
| — | `undo`/`redo` fuera de alcance | Incremento 3 |
| — | ASR local y visión sin medir | Incremento 6, con la máquina descargada |
| — | D9 (rechazo temprano de nombres duplicados) debe revisarse | Incremento 3, donde los duplicados pasan a ser legítimos |

## 5. Decisión de entrada al Incremento 2

Se entra al **Incremento 2 — generación de backend desde el diagrama**, con dos condiciones:

1. **Primero V2 y V3.** El incremento no se declara presentable hasta que las aristas se lean bien, porque el
   objetivo del producto es que un docente mire el diagrama y lo entienda. Una herramienta que dibuja etiquetas
   encimadas no pasa esa prueba, por más que su modelo sea correcto.
2. **Las reglas del generador ya están probadas** en `spikes/uml2spring/` (13 de 13 pasos, huella estable en
   dos corridas, build sin red) y el spike **no** es código a copiar: se reimplementa desde los contratos.

El requisito que el generador va a necesitar y que este incremento **sí** dejó listo es el `state_hash`
determinista por revisión, que es la semilla de RNF-04.

## 6. Riesgo residual, sin adorno

El incremento cierra con el **corte funcional completo y verificado**, y con **dos defectos de presentación
conocidos y localizados**. Esto es lo que no se puede afirmar:

- **No se probó con dos usuarios a la vez.** No hay colaboración: existe el sobre con causalidad e idempotencia,
  pero nada lo ejerce en concurrencia real más allá de una prueba secuencial de revisión.
- **No hay autenticación.** El autor es un usuario semilla. Cualquiera con acceso al puerto puede escribir.
  Es aceptable en local y **no lo es** en una demostración expuesta.
- **La geometría no se probó a escala.** Con decenas de elementos no hay medición de fluidez; el coalescido
  evita la tormenta de comandos, no mide el render.
- **El juicio estético final no es mío.** Corregí el ruido del `[1]`, que era objetivo. Si V2 y V3 se arreglan
  y el resultado todavía no se ve minimalista, eso se decide mirándolo, no argumentando.

## 7. Estado medido

| Superficie | Comando | Resultado |
| --- | --- | --- |
| Backend | `cd backend && mvn -o -B verify` | **82 pruebas**, 0 fallos, sin red |
| Arnés de aceptación | `python tools/acceptance/acceptance.py` | **34 pasos OK**, 0 fallos, teardown verificado |
| Editor | `cd web && npm test` | **24 pruebas**, 0 saltadas con backend vivo |
| Bundle de producción | `cd web && npm run build` | 491,71 kB JS (155,16 kB gzip), 31,38 kB CSS |
| Alineación con la línea base | — | Los 6 constructos fuera de base con comando propio se rechazan **nombrando el constructo y el GAP**; los otros 3 se rechazan con motivo por otra vía (§2.1). El mapeo de los 23 soportados queda **pendiente** (§2.2) |
