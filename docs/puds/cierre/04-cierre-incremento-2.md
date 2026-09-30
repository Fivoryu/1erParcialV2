# Acta de cierre del Incremento 2

**Fecha:** 2026-09-30
**Rama:** `feat/incremento-2-generacion-y-exportacion`, apilada sobre `feat/incremento-1-modelo-y-editor`
**Alcance cerrado:** T1 a T14 del plan
[`odd/tasks/incremento-2-generacion-y-exportacion.md`](../../../odd/tasks/incremento-2-generacion-y-exportacion.md).
Las unidades de trabajo de este incremento son las de `git log --oneline 955d3ec..HEAD`: **no se cita el número**
por la misma razón que en el acta anterior —un documento que dice cuántos commits tiene el rango cambia ese
número al commitearse.
**Producto entregado:** generación de un backend Spring Boot desde el diagrama, con su descarga como ZIP desde
el editor, su propia base, su despliegue verificado y su preparación AWS documentada.

## 1. El corte visible, con veredicto

> Desde el editor, elegir o crear **un destino** del modelo actual, generar el backend, ver el estado y los
> diagnósticos, descargar el ZIP, y que ese ZIP compile sin red, se levante con **su propia** base y resuelva su
> CRUD. Y que **dos destinos del mismo modelo** den dos backends independientes que corran a la vez sin pisarse.

| Pieza | Veredicto | Evidencia |
| --- | --- | --- |
| Snapshot e identidad de corrida | **Cerrado** | Mover una clase no cambia `inputSnapshotHash`; renombrarla sí. Los ocho grupos de la proyección tienen prueba propia |
| Reglas de mapeo | **Cerrado, con el techo declarado** | Propietario = extremo muchos; `nullable` exacto; colección inversa solo si el extremo muchos es navegable. Una característica no soportada se rechaza **nombrando la característica** |
| Perfil, plantilla y destinos versionados | **Cerrado** | Cambiar una opción crea revisión nueva; la anterior se relee con su hash. La `connectionConfigRef` se rechaza si tiene forma de credencial |
| Emisión de artefactos | **Cerrado** | Cuatro capas, 24 artefactos, LF y salto final; dos emisiones byte-idénticas; el proyecto generado **compila offline dentro de la suite** |
| Procedencia, hashes y OpenAPI | **Cerrado** | Dos ZIP byte-idénticos, mismo `artifactSetHash` y mismo `artifactZipHash`; el OpenAPI declara las cinco operaciones CRUD por clase |
| Verificación registrada por corrida | **Cerrado** | 5 checks `PASSED` y 16 `NOT_RUN` **con su motivo**; generación y verificación quedan etapas separadas |
| API de generación y descarga | **Cerrado** | Una corrida incompleta devuelve `RUN_NOT_COMPLETE` en JSON **sin un byte de ZIP**; repetir la misma generación devuelve la misma corrida |
| Panel de generación en el editor | **Cerrado** | Lista **los dos destinos**, crea uno nuevo, lanza la corrida, muestra el diagnóstico bloqueante **nombrando la característica** y descarga el ZIP solo si la corrida terminó completa. Cero identificadores en pantalla |
| Despliegue propio y preparación AWS | **Cerrado, con límites declarados** | Dos destinos arriba a la vez con su base y su volumen, `health` `UP`, CRUD con la foránea resuelta. Preparación AWS ejecutada contra el emulador. Ver §2 y §4 |
| Arnés de aceptación | **Cerrado** | `tools/generation-acceptance/` = **66 aserciones, 0 fallos**, con teardown verificado |

## 2. Definición de terminado, con evidencia

Los criterios de §8 del plan, con lo que **se observó** en esta sesión. Las cifras son valores medidos; el
detalle de cada uno está en §9 del plan.

| Criterio | Estado | Evidencia |
| --- | --- | --- |
| El corte se repite desde cero | **Sí** | Dos comandos: levantar el diagramador y correr el arnés. **66 aserciones, 0 fallos**, conteo de contenedores de vuelta a su valor inicial |
| Las migraciones reproducen los índices de la autoridad | **Sí** | Guardián con control en las dos direcciones, sobre las 25 tablas de `V1`–`V5` |
| El ZIP compila con `mvn -o`, sin red | **Sí** | Empaquetado offline dentro de la suite **y** en el despliegue, exit 0 en los dos destinos |
| Dos generaciones del mismo estado dan el mismo `artifactSetHash` | **Sí** | `T7_REPEAT`: `artifactSetHash1 = artifactSetHash2 = 7ffe9dda…`, `byteIdentical=true` |
| Dos destinos dan dos backends independientes | **Sí** | Dos `artifactSetHash`, dos bases, dos volúmenes, dos filas distintas, **los dos contestando a la vez** |
| Mover una clase no cambia el snapshot; renombrarla sí | **Sí** | Valores observados en T2; el `modelStateHash` cambia y el `inputSnapshotHash` no |
| Los ocho grupos semánticos contribuyen al hash | **Sí** | Una prueba por grupo, más una que exige que el fixture pueble los ocho |
| Una característica no soportada se rechaza nombrando la característica | **Sí** | Verificado en el despliegue real: `Primitive type 'boolean' has no mapping in profile option 'primitiveTypeMappings.boolean'` |
| El ZIP se entrega solo si la corrida terminó completa | **Sí** | La API rechaza con `RUN_NOT_COMPLETE` sin bytes de ZIP; el panel solo ofrece la descarga con la corrida completa y sin bloqueantes |
| El backend generado arranca contra su PostgreSQL con Flyway y responde `health` `UP` y el CRUD | **Sí** | Flyway `1 - init` en el contenedor; `{"status":"UP"}`; CRUD con la foránea resuelta en los dos destinos |
| La colección Postman está versionada y su corrida documentada | **Sí, con el límite declarado** | La colección y el entorno están versionados y la corrida está transcripta; **la evidencia es una transcripción de terminal, no una captura de la GUI de Postman** |
| Ningún secreto viaja en el ZIP, la configuración, los diagnósticos, los logs ni los hashes | **Sí** | El `.env.example` emitido no trae valor usable, el `.gitignore` emitido excluye `.env`, y el check `target_safe_configuration` más el escaneo de credenciales de los artefactos pasan en cada verificación |
| La preparación AWS/Floci se ejecutó sin crear recursos facturables | **Sí, con el límite declarado** | Ver §4: no se usó credencial real de AWS ni se creó nada fuera del emulador; **la subida de la imagen al registro no la completa el emulador** |
| La pantalla del editor no muestra ningún identificador técnico | **Sí** | Búsqueda estática y comprobación sobre el render real, ver §2.1 |
| El acta declara lo que no se verificó | **Sí** | §7 |

### 2.1 La regla de no-IDs, cómo se comprobó

Es la regla heredada del Incremento 1 y este incremento le agregó una superficie nueva que muestra destinos,
corridas y diagnósticos, así que se comprobó de dos formas.

**Estática, sobre `web/src/**`:** no hay una sola interpolación JSX de un identificador como texto ni como valor
de atributo. Lo que aparece son `htmlFor`/`id` de campos de formulario con los valores que genera `useId` de
React —`«r0»`, no un UUID—, valores de la prop `key` (`key={target.targetId}`), que **no llegan al DOM**, y
pasajes de identificadores a funciones de la API, que no renderizan. Nada más.

**Sobre el render real:** el test `never puts an identifier, a key or a table name on the screen` recorre el
markup del panel con el patrón de UUID y con `run_id`, `target_id`, `profile_id`, `template_id`,
`model_version`, `generation_run` y `artifact_set_hash`, con una corrida fallida y un diagnóstico bloqueante:
**cero coincidencias**. Y la captura versionada
(`web/scripts/capture-generation-panel.mjs`) **falla** si el texto del panel trae un identificador, un nombre
técnico, menos destinos de los esperados o la acción de generar deshabilitada. **Esa prueba encontró una fuga
real antes del commit:** el selector de perfil era un `<select>` y su `value` era el `profileId`, o sea el UUID
en el DOM. Se cambió por una lista de botones sin `value`.

**Alcance declarado de la comprobación sobre el render:** cubre el panel de generación con datos vivos. La
búsqueda estática cubre todo `web/src/**`, incluido el lienzo.

## 3. Defectos encontrados y corregidos

Cinco defectos propios, **todos encontrados ejecutando** y ninguno leyendo. Cada corrección lleva una prueba que
falla sin ella, verificada reponiendo el defecto, observando el fallo y restaurando desde una copia con hash
comprobado.

| # | Defecto | Cómo se encontró | Corrección |
| --- | --- | --- | --- |
| D1 | El emisor salía sólo de lectura y sin manejo de errores, mientras el mensaje de commit afirmaba lo contrario | Lectura del alcance del enunciado contra el código emitido | `a614c7f`: CRUD completo y un manejador de errores único, 404 y 400, nunca 500 |
| D2 | La API de generación no era manejable desde afuera: no exponía el `versionId` ni aprovisionaba el perfil | El arnés externo intentando arrancar una generación | `ea81e09`: lo que ya estaba congelado se hizo descubrible, sin agregar defaults que escondieran las identidades |
| D3 | La descarga devolvía `RUN_NOT_COMPLETE` para una corrida `SUCCEEDED` | El despliegue contra `postgres:16`, cuya colación no es la de la suite | `d3b420f`: la comparación del manifiesto es igualdad de conjuntos por path |
| D4 | El `docker-compose.yml` emitido no era YAML: comillas internas sin escapar en el healthcheck | `docker compose config` rechazándolo | `6cf719b`: el comando ya no necesita comillas internas, y la suite **parsea** el archivo con un parser YAML real |
| D5 | El selector de perfil ponía el `profileId` en el DOM | La propia prueba de no-identificadores, antes del commit | `1ec6b68`: lista de botones sin `value` |

Tres defectos propios más, del arnés de aceptación, también encontrados corriéndolo: el token del run podía
empezar con dígito y el slug del emisor le antepone `target`, así que los nombres derivados no eran los reales;
la verificación de volúmenes derivaba los nombres a mano y por eso **pasaba vacua**; y un diagramador
inalcanzable salía como «Unexpected error» en lugar de una aserción con el motivo legible. Los tres están
corregidos y el arnés tiene ahora **control positivo** en la comprobación de volúmenes: primero afirma que los
volúmenes grabados existen, y recién después que no quedan.

## 4. La preparación AWS/Floci, sin adorno

**Ejecutada sólo contra el emulador local en `127.0.0.1:4566`, que usa otro proyecto: se consumió, no se
administró.** No se usó ninguna credencial real de AWS y no se creó nada fuera del emulador.

| Paso | Observado |
| --- | --- |
| Identidad | `Account 000000000000`, `arn:aws:iam::000000000000:root` |
| Bucket preexistente `generation-artifacts` | **Sólo listado, no tocado**: es de otro proyecto |
| Parámetro SSM por destino (`/generated-<slug>/deployment`) | Escrito y releído idéntico (267 y 261 bytes), **sin ningún campo secreto** |
| Repositorio ECR por destino (`generated-<slug>`) | Creado, con `repositoryUri` del emulador |
| Etiquetado local de las dos imágenes | Hecho |
| `docker push` al registro **del emulador** | **Rechazado por el emulador**: con el host del URI, `no such host`; contra `127.0.0.1:4566`, `400 Bad Request` en la subida de blobs |
| `docker push` a un registro **real** (`registry:2` local, sin descargar nada) | **Completado**: las dos repositorios en el catálogo, las dos etiquetas `0.1.0`, manifiesto `sha256:57d44498…`; la imagen se borró localmente, se trajo de vuelta con **el mismo image id** y **arrancó** con `health` `UP` y el CRUD resolviendo la foránea. **19 aserciones, 0 fallos** |

El push contra el emulador queda **documentado y no reclamado**; el push contra un registro real está **ejecutado**
(transcripción en `docs/capturas/incremento-2/17-registro-real.txt`, que incluye la corrida del arnés que
construye las dos imágenes). Nada de ECS, EC2, RDS o IAM se aprovisionó, y no se afirma ningún costo.

**Vuelta a la línea base, verificada:** contenedores **36 → 36**, volúmenes **68 → 68**, y el emulador de vuelta
a `ssm=[]`, `ecr=[]` y su bucket ajeno intacto.

## 5. Lo que quedó abierto, con responsable

| # | Abierto | Por qué queda abierto | Responsable |
| --- | --- | --- | --- |
| A1 | Push a un **ECR de AWS real** | El push contra un registro real **ya está cerrado** (§4); falta el proveedor con credenciales reales | Equipo, con una cuenta AWS |
| A2 | Despliegue en AWS real: base gestionada, orquestador, definición de tarea | Fuera del alcance del incremento; el camino queda documentado con **un solo flag** de diferencia | Equipo |
| A3 | La colección Postman con capturas de la GUI | La corrida se documentó como transcripción; el guion sí es repetible | Equipo, abriendo Postman |
| A4 | `spring-boot-starter-security` no se emite | La versión 3.4.5 no está en la caché local sin red | Equipo, con la dependencia aprobada |
| A5 | El vocabulario de primitivos del perfil es PascalCase | Rechaza `boolean` nombrando la característica, que es el comportamiento diseñado; unificar el vocabulario es una decisión de perfil, no un arreglo | Equipo, al definir el perfil V2 |
| A6 | Roundtrip contra Enterprise Architect en vivo | Heredado del Incremento 1 (`GAP-07`) | Equipo, con EA abierto |
| A7 | Unificación de la numeración de requisitos: el enunciado numera el determinismo **RNF-04** y los documentos PUDS lo citan como **RNF-03** | Hay que unificar antes de la matriz final del informe | Equipo |

## 6. Riesgo residual, sin adorno

- **El generador no conoce ningún dominio, pero su techo sí.** El subconjunto soportado es un techo declarado:
  generalización, realización, dependencia, uno-a-uno, muchos-a-muchos y extremo a uno no navegable se
  **rechazan** con diagnóstico bloqueante. Un modelo del mundo real que use herencia no va a generar. Eso está
  declarado en §2.1 del plan y en el perfil V1, y no es un defecto: es el alcance elegido.
- **La verificación por corrida es honesta y por eso mismo incompleta.** De los 21 checks, **16 quedan
  `NOT_RUN` con su motivo**. El ZIP no viene con una garantía de que el backend sea correcto: viene con un
  registro de qué se comprobó y qué no. Quien lea la procedencia tiene que leer también los `NOT_RUN`.
- **El backend generado no tiene seguridad.** Sin `spring-boot-starter-security` no hay autenticación ni
  autorización. Está declarado en el POM, en el README del proyecto generado y acá.
- **La caché local de Maven es una dependencia de construcción.** `mvn -o` funciona porque el conjunto de
  dependencias está en la caché; en una máquina limpia el camino offline no está disponible.
- **El arnés de aceptación no levanta el diagramador.** Necesita uno corriendo: son dos comandos, no uno, y el
  primero pertenece al Incremento 1.

## 7. Lo que NO se verificó

Esta lista existe para que nadie la tenga que deducir.

- ~~No se verificó la subida de la imagen a un registro~~: **verificada contra un registro real** en
  `docs/capturas/incremento-2/17-registro-real.txt` (19 aserciones, 0 fallos, con la imagen borrada localmente y
  traída de vuelta con el mismo image id). Lo que **no** se verificó es la subida a un **ECR de AWS real** con
  credenciales reales.
- **No se verificó ningún despliegue fuera de esta máquina**: ni nube, ni base gestionada, ni orquestador.
- **No se verificó que un tercero recorra el corte con las manos.** La repetición sin manos humanas sí está
  verificada por el arnés, que es lo que T10 agregó; lo que no se verificó es otra persona ejecutándolo.
- **No se verificó la seguridad del backend generado**: no hay capa de autenticación que verificar.
- **No se verificó el comportamiento del backend generado bajo concurrencia**, ni con volúmenes de datos
  reales, ni con migraciones posteriores a `V1`.
- **No se verificó la interoperabilidad con Enterprise Architect** en vivo.
- **La corrida de Postman no se verificó en la GUI**: la evidencia es una transcripción de terminal.
- **No se verificó el consumo de disco del VHDX** más allá del conteo de contenedores y volúmenes: la imagen
  base del runtime se descargó (287 MB en disco) y las imágenes de los dos destinos se construyeron, pero no se
  midió la variación del VHDX.

## 8. Estado medido

Medido en esta sesión, sobre la rama `feat/incremento-2-generacion-y-exportacion`.

| Superficie | Medición |
| --- | --- |
| Backend | `mvn -o -B verify` = **179 pruebas**, 0 fallos, 0 errores, 0 saltadas, sin red |
| Editor web | `npm run lint` = 0 errores; `npx vitest run` = **71 pruebas, 0 fallos**, 4 saltadas (las 3 de lienzo vivo y la de cliente vivo, todas por backend ausente); `npm run build` en verde |
| Arnés del corte visible | `python tools/acceptance/acceptance.py` = **30 pasos OK**, 0 fallos |
| Arnés de generación y despliegue | `python tools/generation-acceptance/acceptance_generation.py` = **66 aserciones, 0 fallos**, teardown verificado |
| Subida a un registro real | `python` del cierre de registro = **19 aserciones, 0 fallos**: push, borrado local, pull de vuelta con el mismo image id y arranque con `health` `UP` y CRUD |
| Proyecto generado | Compila offline dentro de la suite y en el despliegue; **24 artefactos**; dos ZIP de **42.086** y **41.900** bytes, byte-deterministas |
| Docker al terminar | **36 contenedores**, **68 volúmenes**: exactamente la línea base del inicio |

## 9. Decisión de entrada al Incremento 3

El incremento se cierra con **el corte completo y verificado**, y con **siete puntos abiertos** con responsable
en §5, de los cuales ninguno bloquea la demostración. Lo que sí conviene resolver antes de mostrarlo:

1. **A1 y A2** si la defensa exige un despliegue en la nube. El camino está documentado y a **un flag** de
   distancia, y el paso de transferencia de imagen ya está probado contra un registro real; falta el proveedor.
2. **A3** si la defensa exige la captura de la GUI de Postman; es abrir Postman y correr una colección que ya
   está versionada.
3. **A7**, la unificación de la numeración de requisitos, antes de armar la matriz final del informe.

El siguiente incremento es el de **colaboración** (CU-18, CU-19 y CU-20): los checks que este incremento deja
`NOT_RUN` son, en su mayoría, los que ese incremento tiene que pasar a `PASSED` en lugar de declarar. La
precondición que este incremento sí entrega es que el backend generado se levanta con su propia base y responde,
que es lo que un cliente necesita para consumirlo.
