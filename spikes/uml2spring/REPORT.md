# Reporte del spike — UML a Spring Boot de cuatro capas

> **Resultado: la pregunta del spike se responde que sí, con límites declarados.** Se emitió de forma
> determinista un backend Spring Boot de cuatro capas que compila, arranca contra PostgreSQL, aplica su
> migración con Flyway y atiende un CRUD con la clave foránea resuelta.
>
> Toda cifra de este reporte sale de una corrida real del arnés. El comando está al final.

## 1. Resultado medido

| Paso | Resultado | Evidencia |
| --- | --- | --- |
| Determinismo | PASA | 12 archivos; dos corridas con la misma huella `e139a1ee54b0c94a1fcd67a58028c08d474c66059d4c814637d28d90755dbd2d` |
| Compilación | PASA | `[INFO] BUILD SUCCESS` con `mvn -B -o package` (sin red) |
| PostgreSQL | PASA | Contenedor `uml2spring-spike-pg` escuchando en 55432 |
| Arranque | PASA | `GET /actuator/health` → `{"status":"UP"}` |
| Migración Flyway | PASA | `Successfully applied 1 migration to schema "public", now at version v1` |
| CRUD crear autor | PASA | `HTTP 201 {"id":1,"nombre":"Borges","nacionalidad":"Argentina"}` |
| CRUD crear libro | PASA | `HTTP 201 {"id":1,"titulo":"Ficciones","anio":1944,"autor":{"id":1,"nombre":"Borges","nacionalidad":"Argentina"}}` |
| CRUD listar autor | PASA | `HTTP 200 [{"id":1,"nombre":"Borges","nacionalidad":"Argentina"}]` |
| CRUD listar libro | PASA | `HTTP 200 [{"id":1,"titulo":"Ficciones","anio":1944,"autor":{"id":1,...}}]` |
| Clave foránea resuelta | PASA | `libro.autor.id == 1` |
| Historial de Flyway | PASA | `1\|init\|true` |
| Esquema creado | PASA | `autor.id:NO autor.nombre:NO autor.nacionalidad:YES libro.id:NO libro.titulo:NO libro.anio:YES libro.autor_id:NO` |
| Limpieza | PASA | Contenedores `36 → 36`, `uml2spring-spike-pg` eliminado |

**13 pasos, 0 fallas.**

Dos cosas que la tabla dice y conviene leer despacio:

- `libro.autor_id:NO` significa **NOT NULL** en PostgreSQL. La asociación tenía `EXACTLY_ONE` en el extremo
  `Autor`, y la clave foránea terminó donde corresponde —en el lado muchos— con la nulabilidad correcta.
- La huella es idéntica en dos corridas **y** el build pasa sin red. El artefacto no depende del reloj, del
  disco ni de la descarga del momento.

## 2. Tres defectos reales que el spike encontró

Un spike que solo confirma no sirve. Estos tres aparecieron **ejecutando**, y ninguno se habría visto en una
revisión de escritorio:

### 2.1 `GET /api/libro` devolvía HTTP 500

Primera corrida de extremo a extremo: el `POST` andaba y el `GET` no. Causa: `@ManyToOne` perezoso
serializado **fuera** de la transacción, con `open-in-view: false`. La creación funcionaba porque la entidad
ya estaba en memoria; el listado no.

**Decisión tomada:** el extremo a uno se emite `EAGER`, con el costo declarado (N+1 al listar). Un generador
definitivo debería devolver DTO armados dentro de la transacción; queda como límite explícito, no como
descuido.

### 2.2 El build necesitaba red

Con Spring Boot **3.5.0** el `pom` gestiona Flyway **11.7.2**, que no estaba en la caché local, y el build
sin red fallaba. Se cambió a Spring Boot **3.4.5**, que gestiona Flyway 10.20.1, Hibernate 6.6.13 y el driver
42.7.5 — exactamente lo que la caché ya tenía.

**Por qué importa:** un spike que depende de la red no es reproducible. La versión se eligió por una razón
verificable, no por novedad, y la razón está en el código.

### 2.3 Un JVM huérfano bloqueaba el artefacto

La corrida que falló en el `GET` dejó su proceso vivo, y en Windows eso mantiene el jar bloqueado: el
`repackage` de Spring Boot no pudo renombrarlo y el build falló con un error que no tenía nada que ver con el
código.

**Corrección:** el arnés ahora termina procesos huérfanos del spike antes de compilar, y al final usa
`taskkill /F /T` **verificando** que el proceso murió, en vez de confiar en `terminate()`.
Fue detectado comprobando procesos, no adivinando: el `PID` y su línea de comandos probaron que era nuestro.

## 3. La regla que el spike existe para probar

El lado propietario de una asociación se elige por **multiplicidad y navegabilidad**, nunca por el orden en
que los extremos aparecen en el archivo. La suite lo verifica de la forma más directa posible: **invierte los
extremos en el modelo y exige que la huella del artefacto no cambie**.

Si el emisor mirara el orden, esa prueba fallaría y el artefacto tendría la clave foránea del lado equivocado.

## 4. Límites declarados

Esto es un spike, y lo que **no** prueba es tan importante como lo que prueba:

| Límite | Consecuencia |
| --- | --- |
| El emisor está en **Python** | Lo probado son las reglas de emisión y el determinismo del artefacto, no el runtime del generador. El generador real vivirá en el backend Java |
| Extremo a uno `EAGER` | N+1 al listar. Un generador definitivo devuelve DTO dentro de la transacción |
| Solo asociaciones **uno-a-muchos** | Un extremo a uno contra otro, o una asociación no navegable en el lado muchos, hacen fallar el emisor con error explícito. Falla cerrado en vez de elegir mal |
| Sin clases abstractas, sin enumeraciones, sin herencia | El emisor las rechaza; no se probaron |
| Un solo modelo de entrada | Dos clases y una asociación. Nada prueba aún un dominio más grande |
| El proyecto generado **no trae pruebas** | La verificación del artefacto generado es materia de CU-20, fuera de este spike |
| Rutas REST en singular (`/api/libro`) | Decisión para evitar un pluralizador. No es una decisión de producto |

## 5. Qué cambia para el generador definitivo

1. **La regla del lado propietario es implementable y verificable.** Se puede portar tal cual.
2. **La emisión determinista es alcanzable**: orden de rutas, LF, sin reloj ni rutas absolutas, y huella
   estable. Portar esto a Java es trabajo mecánico.
3. **El punto delicado no es la emisión, es la frontera transaccional.** El defecto del `GET` no fue de
   plantillas: fue de no decidir dónde se arma la respuesta. Eso debe resolverse en el contrato del generador
   antes de escribir plantillas.
4. **Los defectos aparecen ejecutando.** Tres de tres se encontraron corriendo, no leyendo.

## 6. Cómo se reproduce

Desde la raíz del repositorio:

```bash
python -m pytest spikes/uml2spring -q     # 20 pruebas: determinismo, regla del lado propietario, falla cerrada
python spikes/uml2spring/run_spike.py    # los 13 pasos de extremo a extremo
```

El arnés solo crea y borra su propio contenedor (`uml2spring-spike-pg`). Nunca ejecuta `prune` ni toca
contenedores, imágenes o volúmenes ajenos, y usa el puerto 18080 porque el 8080 de la máquina está ocupado.
