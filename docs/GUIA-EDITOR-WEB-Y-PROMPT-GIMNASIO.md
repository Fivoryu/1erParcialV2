# Guía: usar el editor web en la nube y pedirle un diagrama al asistente

> **El despliegue ya no existe.** El 2026-09-23, por decisión del usuario, se destruyó todo lo que facturaba en
> AWS: la instancia, su volumen, la IP elástica y los repos ECR. **La dirección `https://34.239.56.54.nip.io`
> ya no responde**, así que los pasos de esta guía describen algo que hay que volver a desplegar antes de poder
> seguirlos. Lo que sigue siendo válido sin nube es lo que está leído del código: las etiquetas de la interfaz,
> la gramática del asistente, el presupuesto de 12 operaciones y el prompt del gimnasio. Los modelos de los
> proyectos quedaron guardados en `docs/xmi-backup/`.
>
> Escrita el 2026-09-23, el mismo día en que el editor quedó publicado en la nube.
> El prompt de ejemplo está en [`prompts/gimnasio-simple.md`](prompts/gimnasio-simple.md).
>
> **Lo que está medido y lo que no**: las etiquetas de la interfaz, la gramática del asistente, el
> presupuesto de 12 operaciones y las credenciales están **leídos del código**, con archivo y línea. El
> **recorrido del editor en un navegador real está medido**: entrar, abrir un proyecto y llegar al lienzo con
> el asistente disponible, sin errores de identidad ni de persistencia. Lo que **no** está medido es el
> **resultado concreto del prompt**: no se ejecutó. Al final está dicho sin adornos, en la sección «Qué no
> está medido».

## 1. Qué necesitas

| Cosa | Valor |
| --- | --- |
| La dirección | **`https://34.239.56.54.nip.io`** |
| Un navegador | Cualquiera. No hace falta nada instalado |
| Tu cuenta | `user01@diagrammer.local` — contraseña `UserDemo01!` |
| La instancia encendida | Si la dirección no responde, está apagada: mira el paso 0 del [manual de uso](MANUAL-DE-USO-Y-VERIFICACION.md) |

> **Tiene que ser HTTPS, y no es un capricho.** El editor usa Web Crypto: construye su identidad con
> `crypto.randomUUID` y firma su persistencia local con `crypto.subtle.digest`. El navegador expone esas dos
> APIs **solo en contextos seguros** — HTTPS o `localhost` —, así que sobre `http://` en una IP el editor no
> inicializa. Medido en un navegador real: en `http://34.239.56.54:8080` el origen da `isSecureContext: false`
> y `crypto.randomUUID` es `undefined`. Por eso la dirección de trabajo es el nombre con certificado.
>
> El certificado lo emite **Let's Encrypt** para `34.239.56.54.nip.io`, un nombre que el servicio público
> `nip.io` resuelve a la IP de la instancia. No hubo que comprar un dominio.
>
> **Antes de que la uses, dos cosas.** Las cuentas son **sembradas de demostración** y sus contraseñas están
> escritas en el repositorio (`DemoUserSeeder.java:17-28`), así que cualquiera que lea el repositorio puede
> entrar; el tráfico ahora sí va cifrado, pero la credencial es pública. Y la instancia **factura cómputo
> mientras está encendida** (unos $0.079/hora). No es un servicio para dejar publicado: es una demostración
> que se enciende cuando se usa y se apaga cuando no.

## 2. Dónde vive cada cosa

El editor y la API comparten el mismo puerto, repartidos por ruta:

| Ruta | Qué hay |
| --- | --- |
| `/` | **El editor** |
| `/auth/*`, `/projects*`, `/actuator/*`, `/sync/*` | La API de la plataforma |
| `/apps/<slug>` | Cada backend generado que hayas desplegado |

Las dos direcciones sirven lo mismo: el nombre con certificado para el navegador, y `http://34.239.56.54:8080`
para el teléfono, que no necesita contexto seguro. Como el editor habla con la API por rutas relativas, todo
funciona sin configurar nada. La contrapartida es que **no hay enlaces profundos**: `/editor` no existe como
página. Se entra siempre por la raíz.

## 3. El recorrido, paso a paso

La interfaz está **en inglés**. Los textos de abajo son los literales que vas a ver en pantalla.

### Paso 1 — Entrar

Abre `https://34.239.56.54.nip.io` y llena el formulario:

- `Email address` → `user01@diagrammer.local`
- `Password` → `UserDemo01!`
- Pulsa **`Sign in`** (mientras entra dice `Signing in…`)

Los botones de cuentas de demostración **no aparecen en la nube**: están limitados al modo de desarrollo
(`LoginPage.tsx:76`), así que hay que escribir el correo y la contraseña a mano.

### Paso 2 — Crear el proyecto

Ya adentro ves `Diagrammer workspace` y la lista `Projects`. En el panel `New canvas` → **`Create a project`**:

- `Project name` → por ejemplo `Gimnasio simple`
- `Description (optional)` → opcional, sirve para acordarte después
- Pulsa **`Create project`** (mientras crea dice `Creating project…`)

Si todavía no tenías ninguno, la lista muestra `No projects yet`.

### Paso 3 — Abrirlo

En la tarjeta del proyecto, pulsa **`Open project`**. La tarjeta muestra también `Revision N` y un botón
`Members` para el panel de integrantes. Ahí ya estás en el lienzo.

### Paso 4 — Abrir el asistente

En la barra de menús, arriba: **`Extras` → `Text to diagram`**.

Se abre un panel lateral titulado `Text to diagram`. Si el menú `Extras` no aparece, es que el proyecto no
terminó de cargar; espera a que el lienzo esté dibujado.

### Paso 5 — Escribirle la instrucción

En el campo de texto hay un ejemplo puesto como pista, y **está en español a propósito**:

```text
crear una clase Cliente con atributos id: UUID y nombre: String
```

Escribe ahí tu instrucción y pulsa **`Interpret text`** (mientras trabaja dice `Interpreting…`). Los botones
`Use voice input` e `Interpret photo` probablemente estén deshabilitados: la voz y la foto no están habilitadas
en este despliegue.

### Paso 6 — Revisar la propuesta

Aparece la sección **`Review proposal`**, con lo que el asistente entendió. Esto es lo que trae una
propuesta **de texto**:

| Bloque | Qué te dice |
| --- | --- |
| `Current revision` / `Base revision` | La revisión del modelo cuando pediste la propuesta, y la de ahora |
| `Confidence` | **La señal más útil del panel**: ver abajo |
| `Provider` | Quién respondió |
| `Operations` | Las operaciones propuestas, una por una, con sus campos |
| `Selection` → `Selected operations` | Lo que vas a aplicar: marca las que quieras |
| `Alternatives` | Otras lecturas posibles, opcionales y nunca automáticas |
| `Required permissions` | Los permisos que exige la propuesta |
| `Warnings` / `Diagnostics` | Avisos y diagnósticos |
| `Apply proposal` | El botón, y el motivo cuando está deshabilitado |

En una operación de relación el panel muestra además la línea
`Relationship: <origen> (<mult>) → <destino> (<mult>)`.

**El `Confidence` te dice por dónde pasó tu texto**, y eso vale más que cualquier otra cosa del panel:

| Si dice | Significa |
| --- | --- |
| **`0.99`** | El asistente lo resolvió con su gramática, **sin usar el modelo**. Rápido y determinista |
| Otro número, o `Not provided` | El texto cayó al modelo. Puede haber tardado decenas de segundos y la calidad ya no está garantizada |

Si te sale algo distinto de `0.99`, reescribe la instrucción con la sección 4 en vez de aceptarla.

Los bloques `Coverage`, `Extracted facts`, `Approximate layout`, `Missing parts` y `Human confirmation`
pertenecen a las propuestas **de foto**, que en este despliegue están deshabilitadas: con texto no vas a
verlos.

### Paso 7 — Aplicar

Pulsa **`Apply proposal`** (mientras aplica dice `Applying…`). El servidor aplica el modelo y el editor se
recarga desde la instantánea del servidor: **el panel no toca tu lienzo por su cuenta**.

Si el botón está deshabilitado, el panel te dice por qué, con uno de estos textos:

| Texto | Qué significa |
| --- | --- |
| `Select at least one operation.` | No marcaste nada para aplicar |
| `Choose an alternative before applying the proposal.` | Hay varias lecturas y ninguna elegida |
| `This review is stale and must be reinterpreted.` | El modelo cambió desde que pediste la propuesta: vuelve a interpretar |
| `Only DRAFT proposals can be applied.` | La propuesta ya se decidió antes |
| `The server decision endpoint is unavailable.` | No se pudo hablar con la plataforma |

## 4. Cómo hay que escribirle al asistente

Esto es lo que hace la diferencia entre un diagrama en dos segundos y una respuesta pobre después de medio
minuto. El asistente **primero intenta entender una gramática concreta**, y solo si no la reconoce le pasa el
texto al modelo. Esa gramática es explícita (`Cu09StructuredInstructionResolver.java:16-60`):

| Quiero | Se escribe |
| --- | --- |
| Crear clases | `crea las clases Socio, Membresia, Pago` |
| Crear una clase con atributos | `crea clase Socio con atributos nombre: String` |
| Atributos de una clase | `atributos de Socio son nombre: String, email: String` |
| Agregar atributos a una clase | `agrega los atributos telefono: String a la clase Socio` |
| Relacionar dos clases | `conecta Socio con Membresia` |
| Elegir el tipo de relación | agrega al final `asociacion`, `herencia`, `composicion`, `agregacion` o `dependencia` |
| Poner multiplicidades | `conecta Socio con Membresia 1 0..*` |

Y las reglas que se incumplen sin querer:

1. **Separa las instrucciones con `;` o `,`. Los saltos de línea no separan nada**: para el asistente son
   espacios en blanco. Un mensaje escrito en varias líneas se lee como una sola instrucción.
2. **El presupuesto es de 12 operaciones por mensaje**, contando `clases + relaciones + atributos`. Si te
   pasas, el mensaje entero se va al modelo lento. Para agregar más, manda otro mensaje: el modelo en el
   lienzo se va acumulando.
3. **Los tipos de atributo son solo seis**: `String`, `Decimal`, `UUID`, `DateTime`, `Boolean`, `void`.
   Cualquier otro (`Integer`, `entero`, `fecha`) no se reconoce y manda el texto al modelo.
4. **No uses una palabra de la sintaxis como nombre de clase**: `clase`, `tipo`, `relacion`, `de`, `la`, `el`,
   `con`, `a`, `entre`, `y`. El asistente las borra de las relaciones antes de leer los nombres, así que una
   clase llamada `Clase` desaparece de cualquier relación que la mencione.
5. **Escribe los nombres sin acentos y sin espacios**, porque se convierten en clases Java cuando generes el
   backend desde el diagrama.

### El presupuesto, con números

Para el gimnasio de ejemplo, así se reparte:

| Mensaje | Clases | Atributos | Relaciones | Total |
| --- | ---: | ---: | ---: | ---: |
| 1 — el vocabulario | 5 | 4 | 0 | **9** |
| 2 — las relaciones | 0 | 0 | 4 | **4** |

Los dos entran por separado. Los dos **juntos en un solo mensaje no entrarían**: 5 + 4 + 4 = 13, y 13 > 12.
De ahí que sean dos mensajes, y de ahí que **el segundo se mande después de aplicar el primero**: las
relaciones necesitan que las clases existan ya en el modelo.

## 5. El diagrama del gimnasio

El prompt completo, listo para copiar y pegar, está en
[`prompts/gimnasio-simple.md`](prompts/gimnasio-simple.md). Es un gimnasio simple con cinco clases:

```text
Socio ──1──0..*── Membresia ──1──0..*── Pago
  │
  └──0..*──0..*── Sesion ──1──0..*── Entrenador
```

Pégalo en dos mensajes: el del vocabulario, aplicas, y después el de las relaciones.

### Vista previa de un cambio sugerido en el chat

En el chat de colaboración, cada **`Cambio sugerido`** pendiente muestra, debajo de la lista de operaciones,
una **vista previa tentativa** del diagrama tal como quedaría si se acepta. Se dibuja sobre una copia del
diagrama actual: no lo modifica, no envía comandos y no guarda posiciones.

| Color | Etiqueta | Qué significa |
| --- | --- | --- |
| Verde | `+ Añadido` | Clase, atributo, operación o relación nueva |
| Rojo | `− Eliminado` | Elemento que se borraría; queda visible tachado, como fantasma |
| Amarillo | `~ Modificado` | Elemento renombrado o con campos cambiados |

La vista avisa cuando no puede ser exacta:

- **Revisión distinta**: el diagrama cambió desde que se generó la sugerencia; la vista usa el diagrama actual.
- **Vista previa parcial**: un paso no se puede previsualizar (o el servidor lo rechazaría, como un nombre de
  clase repetido); ni ese paso ni los siguientes se muestran, igual que al aplicar, donde un paso rechazado
  detiene los siguientes.

Las clases sin posición guardada aparecen en una fila debajo del diagrama. Una relación va en línea recta
cuando el camino está libre y, si otra clase está en medio, la rodea con tramos en ángulo recto; su nombre
siempre se dibuja por encima de las clases. Solo una persona editora o propietaria puede pulsar **`Aceptar y aplicar`**.

## 6. Después del diagrama

Con el modelo en el lienzo ya puedes generar el backend desde la plataforma y desplegarlo detrás del mismo
ingreso, sin abrir ningún puerto nuevo. Ese recorrido, con sus comandos, está en el
[manual de uso y verificación](MANUAL-DE-USO-Y-VERIFICACION.md), pasos 5 a 7.

## 7. Qué no está medido

Dicho sin adornos, porque importa más que lo que sí funciona:

- **El prompt no se ejecutó contra la nube.** Está construido sobre la gramática y el orden de intentos
  leídos en el código, y respeta el presupuesto contando operaciones a mano. Que el asistente lo acepte y
  produzca exactamente el diagrama de la sección 5 **no está comprobado**: es lo primero que vas a ver cuando
  lo pegues.
- **La calidad del modelo es el límite**, y está medido en este proyecto: Qwen3.5-0.8B cuantizado a Q4_0. Si un
  mensaje cae al modelo, la respuesta puede tardar decenas de segundos y ser pobre. La defensa contra eso es
  escribir con la gramática de la sección 4, que no pasa por el modelo.
- **No se midió la latencia en la nube** para este prompt. El servidor del modelo mide unas 19 señales por
  segundo en CPU, y el presupuesto de respuesta de la plataforma es de 768 señales.
- **El asistente guarda en caché las respuestas del modelo**, así que repetir exactamente una instrucción que
  cayó al modelo puede responder más rápido la segunda vez. Las instrucciones estructuradas no pasan por esa
  caché: se resuelven solas, antes de que la caché se consulte.

## 8. Si algo sale mal

| Síntoma | Causa más probable | Qué hacer |
| --- | --- | --- |
| La dirección no abre nada | La instancia está apagada | Enciéndela: paso 0 del manual |
| El editor no inicializa y la consola dice `crypto.randomUUID is required` | Estás entrando por `http://` en vez de por el nombre con certificado | Usa `https://34.239.56.54.nip.io` |
| `We could not check your session.` | Se perdió la conexión con la plataforma | `Try again` |
| `The project service returned an unexpected error.` | La plataforma no reconoció el origen de la petición | Revisa que la URL sea la del certificado; si persiste, la plataforma necesita `SERVER_FORWARD_HEADERS_STRATEGY=framework` y hay que recrearla |
| El panel no aparece en `Extras` | El proyecto todavía no terminó de cargar | Espera a que el lienzo esté dibujado |
| `Interpret text` no responde | El texto cayó al modelo y está tardando | Espera; si pasa de un minuto, acorta la instrucción |
| `Confidence` no dice `0.99` | El texto no siguió la gramática y lo tomó el modelo | Reescríbelo con la sección 4 |
| La propuesta no trae nada útil | El texto no siguió la gramática | Reescríbelo con la sección 4 |
| `This review is stale and must be reinterpreted.` | El modelo cambió después de pedir la propuesta | Vuelve a interpretar |
| No aparecen las clases al aplicar | No marcaste operaciones | Marca en `Selection` antes de `Apply proposal` |
