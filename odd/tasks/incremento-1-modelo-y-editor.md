# Incremento 1 — Modelo y editor manual

> **Estado:** plan definido, sin empezar. Este documento es la entrada del Incremento 0 y la unidad de
> trabajo de la próxima sesión.

## 1. Corte visible que define el incremento

El incremento se cierra cuando se puede hacer esto, de punta a punta, a mano:

> **Crear un proyecto, crear una clase desde una pantalla, agregarle un atributo, recargar y encontrar el mismo
> modelo.**

Ese recorrido es RF-01, RF-02 y parte de RF-03. Todo lo demás del incremento existe para que ese corte sea
sólido, no para lucirse.

## 2. Alcance

| Pieza | Qué entra |
| --- | --- |
| Modelo canónico | `Project`, `Model` con revisión, `Classifier` (clase y clase abstracta), `Attribute`, y la separación explícita entre semántica y layout |
| Capa de comandos | Sobre de operación reducido con `operationId`, autor, `baseRevision` y objetivos estables; comandos `classifier.create`, `classifier.rename`, `attribute.add`, `attribute.remove` |
| Persistencia | PostgreSQL 16 con migraciones Flyway que **poseen el esquema**; JPA solo valida |
| Validación | Nombres de clasificador únicos por proyecto, tipos de atributo resolubles, revisiones monotónicas |
| API | Crear y listar proyectos, abrir el modelo, aplicar comandos, recuperar el modelo |
| Interfaz mínima | Lista de proyectos, apertura de uno, alta de clase y de atributo, y recarga del modelo |

## 3. Fuera de alcance, y por qué

La línea base manda. Un constructo marcado `supported: false` en UML-BL-1 **no se implementa a medias**:

| Fuera | Motivo |
| --- | --- |
| **Enumeraciones** | No hay dónde guardar sus literales (GAP-02). Se cierra el hueco primero o no entra |
| Notas UML | Sin entidad (GAP-03) |
| Estereotipos | Sin entidad (GAP-04) |
| Multiplicidad con cotas | Solo se soportan las cuatro categorías (GAP-05) |
| Colaboración, presencia, conflictos | Incremento 3 |
| IA, voz, fotografía | Incrementos 4 y 6 |
| XMI | Incremento 5 |
| Generación de backend | Incremento 2. Las reglas ya están probadas en `spikes/uml2spring/`, pero el spike no es código de producto |
| Lienzo gráfico con geometría | El corte visible es semántico. La disposición visual entra cuando la semántica esté firme |

## 4. Etapas

| Etapa | Contenido | Evidencia de cierre |
| --- | --- | --- |
| **A** | Backend, migraciones Flyway y persistencia del modelo | `mvn -o test` en verde con round-trip de proyecto a base |
| **B** | Capa de comandos y validación | Comandos aplicados sobre el modelo persistido, con rechazo explícito de los inválidos |
| **C** | API REST | El recorrido completo por HTTP, sin interfaz |
| **D** | Interfaz mínima | El corte visible hecho a mano en el navegador, con captura |

## 5. Compromiso de proporción

El Incremento 0 se fue de mano. Acá rige lo contrario:

- Pruebas **donde pagan**: round-trip de persistencia, aplicación y rechazo de comandos, validaciones.
- **Sin** verificación independiente por etapa. Se reserva para contratos y pruebas de riesgo.
- **Sin** documentos nuevos salvo este y los que la entrega exija.
- Cada etapa cierra con **un commit de unidad de trabajo**, con sus pruebas al lado.

## 6. Qué se aprovecha de lo ya hecho

| Artefacto del Incremento 0 | Uso en este incremento |
| --- | --- |
| `docs/puds/contratos/03-linea-base-uml.v1.json` | Define qué constructos se pueden implementar y cuáles no |
| `docs/puds/contratos/01-contratos-transversales.md` §2 y §3 | Forma del sobre de operación y de los recursos |
| `docs/puds/datos/02-esquema-fisico.sql` | Referencia, con la divergencia de nombres de GAP-09 a resolver |
| `spikes/uml2spring/` | Reglas de emisión probadas para el Incremento 2, no código a copiar |

## 7. Definición de terminado

- [ ] El corte visible funciona de punta a punta y se puede repetir.
- [ ] El esquema lo aplica Flyway, no JPA.
- [ ] Un comando inválido se rechaza con motivo, no con un error genérico.
- [ ] Un constructo fuera de la línea base se rechaza explícitamente.
- [ ] Las pruebas pasan sin red.
