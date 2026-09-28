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
- **Evidencia:** `git log --stat` del commit raíz; `git rev-parse --show-toplevel` idéntico desde
  `backend/`, `web/` y `mobile/`; ausencia de directorios `.git` anidados; `git status` limpio.
- **Depende de:** nada.
- **Gate:** requiere autorización explícita del usuario para commitear (política de seguridad del arnés).

### T2 — Línea base UML 2.5 explícita (UML-BL-1)

- **Objetivo:** fijar el subconjunto UML que el sistema soporta y **declara** soportar, para no atribuirse
  compatibilidad total no demostrada.
- **Entregable:**
  - `docs/puds/contratos/03-linea-base-uml.md` con el subconjunto y su tabla de triple mapeo: persistencia,
    generación Java, y XMI;
  - esquema machine-checkable del modelo canónico;
  - test que valida el esquema contra el inventario de la línea base.
- **Evidencia:** test verde; tabla de cobertura que enumera explícitamente lo que queda **fuera**.
- **Depende de:** nada (paralelizable con T3).
- **Queda abierto:** ratificación de la línea base por el docente.

### T3 — Dialecto XMI de Enterprise Architect caracterizado con fixture real

- **Objetivo:** saber exactamente qué emite EA 16 y qué puede sobrevivir un roundtrip según nuestra línea base.
- **Entregable:**
  - `tools/xmi-inventory/` — arnés propio que imprime el inventario del fixture;
  - `docs/puds/contratos/04-dialecto-xmi-ea.md` con la separación entre elementos semánticos UML y
    extensiones de Sparx (`style`, `styleex`, `coords`, `tags`, `xrefs`, `bounds`), la política de
    preservación/descarte y los huecos.
- **Evidencia:** salida reproducible del inventario sobre `docs/fixtures/xmi/Prueba3.xml`, con conteo por tipo
  de elemento, fijada por un test.
- **Depende de:** T1 (para ubicar el arnés).
- **Queda abierto:** roundtrip contra EA en vivo — el servidor MCP no responde.

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
| T1 — Baseline del repositorio | pendiente | — |
| T2 — Línea base UML 2.5 | pendiente | — |
| T3 — Dialecto XMI de EA | pendiente | — |
| T4 — Spike UML→Spring Boot | pendiente | — |
| T5 — Viabilidad de IA local | pendiente | — |
| T6 — Acta de cierre | pendiente | — |
