# Incremento 0 — Contrato y pruebas de riesgo

> **Estado:** plan aprobado, sin tareas ejecutadas.
> **Producto:** diagramador colaborativo de clases UML 2.5 (PRIMER PARCIAL, Ingeniería de Software I).
> **Alcance del incremento:** cerrar el contrato mínimo verificable y derribar los cuatro riesgos tempranos
> **antes** de comprometer arquitectura o escribir código de producto.

## 1. Por qué existe este incremento

El análisis del enunciado (`docs/analisis-enunciado-parcial-1.md`, §11) define un "Incremento 0" con cuatro
frentes, y su §13 lista diez decisiones sin cerrar. Todas comparten la misma forma: si se responden mal
**después** de escribir producto, el costo es rehacer.

| Riesgo temprano | Consecuencia si no se cierra | Se cierra en |
| --- | --- | --- |
| Subconjunto UML 2.5 indefinido | Alcance incontrolable; se declara compatibilidad no demostrada | T2 |
| Dialecto XMI de Enterprise Architect desconocido | El roundtrip falla en la presentación | T3 |
| Transformación UML→Spring Boot no probada | Se rompe el flujo principal del parcial | T4 |
| IA local inviable en el hardware objetivo | El móvil offline (RF-15) no existe | T5 |

## 2. Autoridad de requisitos (material válido, dentro de `1erParcialV2/`)

| Documento | Qué aporta |
| --- | --- |
| `docs/analisis-enunciado-parcial-1.md` | Contexto, producto, actores, RF-01–RF-15, RNF-01–RNF-11, línea base UML, escenario de aceptación integral, estrategia incremental, riesgos y decisiones abiertas |
| `docs/puds/01-captura-de-requisitos.md` | 23 casos de uso, ciclos C1–C3, trazabilidad, Q1–Q10 (Q9 abierta) |
| `docs/puds/02-analisis.md` | Paquetes P01–P09, realizaciones conceptuales |
| `docs/puds/03-diseno.md` + `docs/puds/cierre/01-cierre-diseno.md` | Línea base de diseño D0–D6 y su acta de cierre |
| `docs/puds/contratos/01-…`, `02-…` | Contratos transversales y comparación de colaboración |
| `docs/puds/datos/01-…`, `02-esquema-fisico.sql` | Mapeo y esquema físico previstos |
| `docs/puds/generacion/01-diseno-generador.md` | Diseño del generador determinista |
| `docs/puds/verificacion/01-verificacion.md` | Estrategia de verificación |
| `docs/Parcial_Software_I_Esqueleto_Completo.docx` | Estructura obligatoria del entregable (secciones 1 a 5) |

**Fixtures válidos:**

- `docs/fixtures/xmi/Prueba3.xml` — export XMI 2.1 real de Enterprise Architect 16 (`exporterVersion="6.5"`),
  con UML 2.1 y extensiones de Sparx. Es la muestra que el Incremento 0 pedía conseguir.
- `docs/fixtures/ea-xmi21-diagram-dialect.xml` — recorte documentado del dialecto de diagrama.
- `docs/diagramas/arquitectura-despliegue.eapx` — proyecto de Enterprise Architect.

## 3. Frontera del trabajo (clean-room)

- **Permitido:** todo lo que está bajo `1erParcialV2/`, incluida la documentación heredada.
- **Prohibido:** `../1erParcial/**` y cualquier worktree hermano. Cero lectura de código de la implementación
  anterior.
- **Consecuencia declarada:** `SOFTWARE.md` se lee como *evidencia del intento anterior* (contrato de API y
  números medidos), nunca como diseño a copiar. La implementación se deriva de los requisitos, no del código viejo.
- **Muestras excluidas como oráculo:** `docs/xmi-backup/*.xmi` son salida de la implementación anterior y
  arrastran sus pérdidas conocidas. Sirven como síntoma, no como contrato.

## 4. Estado de partida medido (2026-09-28)

| Herramienta | Versión observada | Nota |
| --- | --- | --- |
| Java | 21.0.10 LTS | Cumple el mínimo del producto |
| Maven | 3.9.15 | Ejecuta y verifica el backend |
| Node / npm | 22.23.0 / 10.9.8 | Web React + TypeScript + Vite |
| Flutter / Dart | instalado / 3.11.5 | Cliente móvil |
| Docker / Compose | 29.8.0 / v5.5.1 | PostgreSQL y Testcontainers |
| Python | 3.14.6 | Sidecars y herramientas |
| git | 2.54.0 | Repositorio sin commits todavía |
| `psql` | ausente | PostgreSQL se ejecutará por contenedor |
| MCP Enterprise Architect | **timeout** | Bloquea la validación de roundtrip en vivo |

## 5. Tareas

### T1 — Baseline del repositorio monorepo

- **Objetivo:** dejar el repositorio listo para trabajo de producto sin repetir el defecto de la versión
  anterior, donde `backend/`, `web/` y `mobile/` eran repositorios git anidados.
- **Entregable:** estructura raíz decidida y documentada; `.gitignore` definitivo; commit raíz con la
  documentación heredada; rama de trabajo del incremento.
- **Evidencia:** `git log --stat` del commit raíz; ausencia de directorios `.git` anidados; `git status`
  limpio; rama de trabajo creada desde el commit raíz.
- **Verificado:** commit raíz `90f4f4a`, 168 archivos, working tree limpio, `find . -name .git` sin
  resultados fuera de la raíz, `.gitattributes` con finales de línea normalizados y fixtures de EA
  byte-exactos (disco `\r\n` conservado, no convertido a `\n`).
- **Pendiente de verificar:** que `git rev-parse --show-toplevel` sea idéntico desde `backend/`, `web/` y
  `mobile/`. Esas carpetas todavía no existen; la comprobación se hace cuando T2–T5 creen la primera y se
  cierra formalmente en T6.
- **Depende de:** nada.
- **Gate:** requiere autorización explícita del usuario para commitear (política de seguridad del arnés).

### T2 — Línea base UML 2.5 explícita (UML-BL-1)

- **Objetivo:** fijar el subconjunto UML que el sistema soporta y **declara** soportar, para no atribuirse
  compatibilidad total no demostrada.
- **Entregable:**
  - `docs/puds/contratos/03-linea-base-uml.md` con el subconjunto, su triple mapeo y los huecos;
  - `docs/puds/contratos/03-linea-base-uml.v1.json` como inventario machine-checkable;
  - `docs/puds/contratos/03-linea-base-uml.schema.json` como esquema del inventario;
  - `tools/contract-tests/` con la suite que lo verifica.
- **Evidencia:** `python -m pytest tools/contract-tests -q` en verde, 0 fallos, y prueba de mutación que
  demuestra que la suite falla cuando el inventario elimina una cláusula obligatoria.
- **Verificado:** esquema válido contra draft 2020-12; 25 cláusulas cubiertas; 32 constructos con 23
  soportados y 9 no; 10 celdas de mapeo en `NONE` de 96, todas con motivo; ninguna cita de origen rota.
- **Hallazgo:** la sección 8.3 de los contratos sobredeclara. Declara 23 elementos soportados y, contra el
  modelo de datos, solo 18 se sostienen, 2 a medias y 3 no (enumeraciones sin literales, modificadores
  estáticos y abstractos, y multiplicidades sin cotas). Además omite las notas UML, que el análisis exige.
- **Verificación independiente:** delegada a `gentle-ai-verify` y devuelta **VERIFIED**. Confirmó las 13 citas
  de esquema físico, las 3 de mapeo lógico, las 3 ausencias declaradas (literales de enumeración, notas,
  estereotipos), los dos soportes parciales (multiplicidad y visibilidad) y las cifras del documento contra
  el artefacto. Reprodujo la mutación por su cuenta y restauró el artefacto con el mismo SHA-256.
- **Hallazgo nuevo de la verificación:** el documento lógico y el esquema físico divergen en varios nombres
  de columna. Se registra como GAP-09 en vez de resolverse en silencio.
- **Queda abierto:** ratificación del docente (GAP-01) y roundtrip contra Enterprise Architect (GAP-07).

### T3 — Dialecto XMI de Enterprise Architect caracterizado con fixture real

- **Objetivo:** saber exactamente qué emite EA 16 y qué puede sobrevivir un roundtrip según nuestra línea base.
- **Entregable:**
  - `tools/xmi-inventory/` — arnés propio que imprime el inventario del fixture;
  - `docs/puds/contratos/04-dialecto-xmi-ea.md` con la separación entre elementos semánticos UML y
    extensiones de Sparx (`style`, `styleex`, `coords`, `tags`, `xrefs`, `bounds`), la política de
    preservación/descarte y los huecos.
- **Evidencia:** salida reproducible del inventario sobre `docs/fixtures/xmi/Prueba3.xml`, con conteo por tipo
  de elemento, fijada por un test.
- **Verificado:** 0 tags sin clasificar; familias medidas (953 propietarios de EA, 153 UML, 66 estructura, 3
  andamiaje = 81,1 % de carga propietaria); 23 constructos de UML-BL-1 evidenciados con valor y 9 declarados
  sin evidencia con motivo (fijado en el oráculo del test); suites de contrato y de inventario en verde, 0
  fallos; prueba de mutación
  que falla por dos lados al declarar evidenciado un constructo cuyo indicador nunca vale `true`.
- **Hallazgos que cambian decisiones:**
  1. las constantes de enumeración se serializan como `ownedAttribute` y no como `ownedLiteral`, que no aparece
     ni una vez: un importador estándar las pierde en silencio, y la persistencia tampoco las guarda;
  2. `isNavigable` no existe en el árbol UML, solo en bloques `modifiers` de EA, y siempre en `false`: quien
     descarte la extensión elige mal el lado propietario de la relación generada;
  3. una parte grande de la extensión es andamiaje vacío: **414 de 953 elementos propietarios (43,4 %)**:
     `styleex` 43/43, `stereotype` 32/32, `options` 21/21, `initial` 21/21, `constraints` 16/16, `behaviour`
     11/11, `documentation` 66/67 y `tags` 70/78. Que exista un bloque `constraints` **no** significa que haya
     restricciones;
  4. `visibility` se escribe `public` 70 veces, `private` 17 y `Public` 16: una comparación sensible a
     mayúsculas pierde 16 valores;
  5. hay 4 referencias de tipo sin destino, y los tipos apuntan a tres espacios (`EAJava_`, `EAID_`, `EAnone_`)
     con el mismo concepto escrito de dos formas;
  6. `Model` no está limpio: 8 bloques `_lineStyle` de EA viven dentro del subárbol UML;
  7. las notas de modelo **sí** existen y viajan en un canal propietario: 12 atributos `documentation` en
     bloques de elemento y de diagrama, con textos reales como «Cliente que realiza reservas de inmuebles.»,
     y el canal estándar `uml:Comment` no aparece en el modelo, así que un consumidor que solo lea UML pierde
     las notas sin enterarse;
  8. `tags` no está vacío: 8 de sus 78 bloques llevan `_lineStyle="orthogonalR"`, que es ruteo de conector y
     pertenece al layout.
- **Verificación independiente:** falsificó **tres afirmaciones** y las tres se corrigieron en el arnés y en
  el contrato:
  1. `BL-CLS-02` (clase abstracta) estaba mal evidenciado: no hay ninguna `uml:Class` abstracta; la métrica
     mezclaba todas las clases de clasificador y por eso el test no podía verlo. Ahora es una métrica separada
     y el constructo pasó a la lista de no evidenciados;
  2. «andamiaje vacío» medía solo atributos y texto, **ignorando hijos**: llamaba vacío a `tags`, que lleva 8.
     La medición ahora exige sin atributos, sin texto **y** sin hijos, y el titular pasó de «la mayoría» a
     «43,4 %»;
  3. «el modelo no lleva notas» era **falso**: se buscaba solo elementos llamados `documentation` y se ignoraba
     que la descripción viaja como **atributo**. Es el hallazgo 7.
- **Segunda ronda de verificación:** confirmó las tres correcciones con conteos propios sobre el XML crudo, y
  encontró dos defectos más. Uno nuevo: el contrato llegó a mostrar tres cifras distintas de pruebas para la
  misma suite (32, 18 y 22, con 23 reales), así que los conteos salieron de la prosa. Y uno heredado del mismo
  vicio que ya se había corregido: `findings.diagrams.elements = 29` mezclaba los 18 elementos colocados en el
  diagrama con los 11 del espejo de metadatos, porque **dos contenedores distintos se llaman `elements`**.
  Ahora la métrica está separada, el desglose está fijado por un test y la tabla de definición/duplicado
  también dejó de ser una cifra sin assert.
  También se corrigió el ámbito de las cifras de `visibility` (46 en el modelo, 70 en todo el documento) y
  desaparecieron los conteos de pruebas escritos en prosa, que llegaron a estar mal de tres formas distintas
  para la misma suite.
- **Correcciones al artefacto de T2:** las notas de BL-CMP-01, BL-CMP-02 y BL-CMP-03 se ajustaron con lo
  medido, incluida una sobredeclaración propia (los 32 bloques `stereotype` están vacíos y cuelgan de atributo
  y operación, no de clasificadores). GAP-10 se reescribió: el canal de notas está caracterizado, lo que falta
  es la persistencia y el roundtrip.
- **Depende de:** T1 (para ubicar el arnés).
- **Queda abierto:** roundtrip contra EA en vivo (GAP-07); notas de modelo sin caracterizar (GAP-10); 9
  constructos sin evidencia, que necesitan un fixture que los ejerza o una decisión explícita de no soportarlos.

### T4 — Spike determinista UML→Spring Boot de cuatro capas

- **Objetivo:** probar el requisito de mayor peso (RF-10, RF-11, RNF-04) con el mínimo irreducible, y tirarlo.
- **Entregable:** `spikes/uml2spring/` que, partiendo de un modelo de dos clases con una asociación, emite un
  proyecto Spring Boot 3 de cuatro capas —entidad JPA, repositorio, servicio, controlador— más migración
  Flyway y configuración PostgreSQL; compila, arranca y responde.
- **Evidencia:** `mvn package` en verde; arranque con `GET /actuator/health` = `UP`; dos corridas con el mismo
  modelo producen el mismo hash del árbol generado (RNF-04); CRUD básico contra PostgreSQL en contenedor.
- **Depende de:** T1; idealmente T2, porque T2 define qué clases son legales.
- **Fuera de alcance:** plantillas definitivas, perfiles de generación, targets, empaquetado de artefactos.

### T5 — Viabilidad de IA local, voz y visión en este hardware

- **Objetivo:** decidir con números —no con opiniones— si RNF-10 y RF-15 son alcanzables en la máquina de la
  demostración.
- **Entregable:** `docs/puds/verificacion/02-viabilidad-ia-local.md` con CPU, RAM, disco y GPU medidos; runtime
  local candidato; latencia y memoria de un modelo pequeño; viabilidad de ASR local y de lectura de imágenes.
- **Evidencia:** números medidos y comandos usados, o un veredicto explícito de "no evaluable" con su motivo.
- **Riesgo:** si no es viable, RF-14 y RF-15 deben replantearse **antes** de construir el móvil.

### T6 — Acta de cierre del Incremento 0

- **Objetivo:** cerrar el incremento con veredicto por riesgo y contrato congelado de lo que sigue.
- **Entregable:** `docs/puds/cierre/02-cierre-incremento-0.md`; este documento actualizado; entrada al
  Incremento 1 definida.
- **Evidencia:** los cuatro riesgos con veredicto (cerrado / abierto / no evaluable) y su evidencia enlazada;
  decisiones registradas; pendientes con responsable.

## 6. Lo que este incremento NO cierra

- **Ratificación del docente** sobre la línea base UML: requiere sesión con el docente.
- **Roundtrip real contra Enterprise Architect**: el servidor MCP de EA devuelve timeout.
- **Q9**: la captura de requisitos la difiere explícitamente al Ciclo 3.
- **La implementación por ciclos C1→C2→C3**: fuera de este incremento.

## 7. Definición de terminado

- Los cuatro riesgos tienen veredicto explícito: cerrado, abierto o no evaluable, cada uno con evidencia o motivo.
- Los contratos nuevos quedan en `docs/puds/` enlazados desde este documento.
- El acta de cierre existe y define la entrada al Incremento 1.

## 8. Registro de progreso

| Tarea | Estado | Evidencia |
| --- | --- | --- |
| T1 — Baseline del repositorio | **hecho** | Commit raíz `90f4f4a` en `main`; rama `feat/incremento-0-contrato-y-riesgos`; `.gitignore` + `.gitattributes` + `README.md` |
| T2 — Línea base UML 2.5 | **hecho** | `03-linea-base-uml.{md,v1.json,schema.json}`; `tools/contract-tests/` 15/15; mutación verificada |
| T3 — Dialecto XMI de EA | **hecho** | `tools/xmi-inventory/` (suite en verde, 0 tags sin clasificar); `04-dialecto-xmi-ea.md`; 23 evidenciados / 9 sin evidencia; 3 afirmaciones falsificadas y corregidas |
| T4 — Spike UML→Spring Boot | pendiente | — |
| T5 — Viabilidad de IA local | pendiente | — |
| T6 — Acta de cierre | pendiente | — |
