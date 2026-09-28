# Acta de cierre del Incremento 0

**Fecha:** 2026-09-28
**Rama:** `feat/incremento-0-contrato-y-riesgos`
**Alcance cerrado:** T1 a T5 del plan [`odd/tasks/incremento-0-contrato-y-riesgos.md`](../../../odd/tasks/incremento-0-contrato-y-riesgos.md).

## 1. Los cuatro riesgos, con veredicto

| Riesgo | Veredicto | Evidencia |
| --- | --- | --- |
| Línea base UML indefinida | **Cerrado** | `docs/puds/contratos/03-linea-base-uml.{md,v1.json,schema.json}`: 32 constructos, 23 soportados, 9 declarados sin soporte con motivo |
| Dialecto XMI de Enterprise Architect desconocido | **Cerrado con límite** | `docs/puds/contratos/04-dialecto-xmi-ea.md`: 0 tags sin clasificar, 23 constructos evidenciados por medición, 9 declarados sin evidencia |
| Transformación UML→Spring Boot no probada | **Cerrado** | `spikes/uml2spring/REPORT.md`: 13 de 13 pasos, huella estable en dos corridas, build sin red |
| IA local inviable en el hardware objetivo | **Cerrado con condiciones** | `docs/puds/verificacion/02-viabilidad-ia-local.md`: latencia ≈ 2,2 s + tokens/32; respuesta simple en 3,04 s |

## 2. Lo que quedó abierto, con responsable

Heredado de la línea base (GAP-01 a GAP-10) más lo nuevo de este incremento:

| id | Pendiente | Responsable |
| --- | --- | --- |
| GAP-01 | Ratificación de la línea base UML por el docente | Equipo y docente |
| GAP-02 | Almacenamiento de literales de enumeración | Ciclo 1 (diseño de datos) |
| GAP-03 | Entidad para notas UML | Ciclo 1 |
| GAP-04 | Entidad de estereotipo aplicado | Ciclo 1 |
| GAP-05 | Multiplicidad sin cotas numéricas | Ciclo 1 (decisión explícita) |
| GAP-06 | Visibilidad sin restricción de dominio ni cobertura | Ciclo 1 |
| GAP-07 | Roundtrip contra Enterprise Architect en vivo | Equipo, con EA abierto |
| GAP-08 | Dirección y retorno del parámetro en el esquema físico | Ciclo 1 |
| GAP-09 | Divergencia de nombres entre el documento lógico y el físico | Ciclo 1 |
| GAP-10 | Notas de modelo: canal propietario, roundtrip sin verificar | Ciclo 3 |
| — | ASR local y visión sin medir | Ciclo 3, con la máquina descargada |
| — | Restricción por esquema (grammar) no disponible vía Docker Model Runner | Ciclo 2, decisión registrada |

## 3. Decisión de entrada al Incremento 1

Se entra al **Incremento 1 — Modelo y editor manual** con el plan
[`odd/tasks/incremento-1-modelo-y-editor.md`](../../../odd/tasks/incremento-1-modelo-y-editor.md).

La línea base **manda sobre el alcance**: solo se implementa lo que UML-BL-1 declara soportado. Un constructo
marcado `supported: false` **no se implementa a medias**: o se cierra su hueco primero, o queda fuera y se dice.
En particular, la enumeración **no entra** en este incremento mientras no exista dónde guardar sus literales
(GAP-02).

## 4. Riesgo residual de este incremento, dicho sin adorno

El Incremento 0 se cierra **sin una sola línea de código de producto**. Los cuatro riesgos técnicos están
derribados y los instrumentos quedaron, pero el sistema no existe: `backend/`, `web/` y `mobile/` no están
creados.

Eso deja dos consecuencias que conviene tener presentes:

1. **El documento del parcial describe hoy un sistema que este repositorio no tiene.** Las secciones 2.4, 2.5,
   3 y 4-5 son heredadas y hablan del intento anterior. Hasta que exista código, ese documento no puede
   presentarse como estado de este trabajo.
2. **La proporción se fue de mano.** El contrato UML son 985 líneas de JSON y esquema; T3 necesitó dos rondas de
   corrección. Fue defendible verificar tanto, pero no mientras el producto tenía cero líneas.

**Compromiso para el Incremento 1:** instrumentación proporcional. Pruebas donde pagan, verificación
independiente solo para contratos y pruebas de riesgo, y el resto a construir.
