# Sesión vencida y recarga del editor (dos bugs con la misma raíz visible)

**Estado:** plan congelado el 2026-10-05 sobre `feat/vista-compacta-editor` (punta `b403f41`, empujada).
**Origen:** reporte del usuario con captura. Textual: *"cuando entro de nuevo al diagrama después de minutos sin entrar"* + captura del navegador mostrando JSON crudo, y *"cuando entro en el inicio tengo que entrar de nuevo al proyecto"*.
**Autorización:** el usuario lo reporta como bug a arreglar.

## 0. Diagnóstico medido

La captura muestra, en el navegador, el cuerpo crudo de una respuesta del backend:

```json
{"code":"UNAUTHENTICATED","message":"Authentication is required.","details":[],"operationId":null}
```

Dos defectos distintos, uno principal y uno menor.

### Bug principal — el proxy de desarrollo se come la ruta del SPA

`web/vite.config.ts:16-19` proxya `/projects` al backend **sin ninguna excepción**, y la ruta de cliente del editor es exactamente `/projects/:id` (`web/src/App.tsx`, `Route path="/projects/:projectId"`).

Consecuencia: una **navegación dura o recarga** de `/projects/<id>` no llega a la aplicación. El proxy la reenvía al backend, que responde 401 (sin credencial válida) o 404 (con ella, porque `GET /projects/{id}` no es un endpoint). El navegador muestra ese JSON y **el manejo de 401 del cliente nunca corre**, porque la app nunca se cargó.

Esto explica **los dos síntomas** con una sola causa: la app no se recupera sola porque nunca se ejecuta, y el usuario tiene que ir a la raíz, ser mandado a login y volver a entrar al proyecto.

**Ya estaba identificado y documentado como hallazgo preexistente** durante la verificación de la vista compacta (`odd/tasks/vista-compacta-editor.md` §3.1, VC-06), y en su momento se declaró fuera de alcance. Ahora afecta al usuario en su flujo real, así que entra.

### Bug menor — la sesión vencida no se detecta al leerla

`web/src/api/session.ts:37-60`: `loadSession()` valida la **forma** del objeto almacenado, pero **no mira `expiresAt`**. Devuelve una sesión vencida como si fuera válida.

El backend emite el token con **12 horas** de vida (`backend/src/main/java/com/diagramador/identity/IdentityService.java:89`, `OffsetDateTime.now().plusHours(12)`).

Impacto real, medido y **acotado**: el cliente **sí** limpia la sesión ante un 401 en vuelo (`web/src/api/client.ts:173-177`, `clearSessionOnUnauthorized` → `clearSession()`), y `clearSession()` notifica por `subscribeToSession`, así que `App.tsx` re-renderiza y muestra el login. O sea que la app **se auto-repara al primer pedido**. El costo es un parpadeo y un pedido desperdiciado antes de llegar al login, no un estado roto permanente. No inflar la gravedad de esto.

### Lo que NO es un bug de este repo

El **despliegue** sirve cuatro caminos exactos y no tiene fallback a `index.html` (`docs/MANUAL-DE-USO-Y-VERIFICACION.md`), así que un enlace profundo tampoco funciona en producción. Eso es configuración de despliegue (`tools/aws-deploy/`), queda **fuera de alcance** y se reporta; no se toca.

## 1. Decisión de diseño

**El discriminador del proxy es la cabecera `Accept`, no el método ni el cuerpo.**

Una navegación dura del navegador manda `Accept: text/html,...`; cualquier `fetch` de la aplicación manda `Accept: */*` o `application/json`. Así que el proxy puede distinguir "quiero el documento" de "quiero datos" sin listar rutas ni romper ningún endpoint. El `bypass` de Vite devuelve `/index.html` y el router de cliente toma el control.

Se descarta enumerar rutas del SPA en el proxy: se rompería al agregar cualquier ruta nueva, que es exactamente el tipo de acoplamiento que produjo este bug.

## 2. Unidades

| Unidad | Título | Prerrequisitos | Estado |
| --- | --- | --- | --- |
| SE-01 | `loadSession()` trata una sesión vencida como ausente (limpia y devuelve `null`) | — | pendiente |
| SE-02 | `bypass` por `Accept` en la entrada `/projects` del proxy de Vite | — | pendiente |
| SE-03 | Verificación: navegación dura con sesión válida y con sesión vencida, más suite y lint | SE-01, SE-02 | pendiente |

Regla test-first: SE-01 tiene escenario determinista (función pura sobre `localStorage` inyectable, y `session.test.ts` ya existe) → RED observado antes de GREEN. SE-02 es configuración de build: su verificación es en navegador, no hay test posible.

## 3. Riesgos declarados

1. **`bypass` mal usado rompería la API en desarrollo.** Si el predicado fuera demasiado amplio, los `fetch` de la app recibirían HTML en vez de JSON y todo fallaría de forma confusa. Mitigación: el predicado mira **solo** `Accept: text/html`, y la verificación debe confirmar que un pedido normal de datos sigue llegando al backend.
2. **Reloj del cliente.** La comparación de expiración usa el reloj del navegador, que puede estar desfasado respecto del servidor. Mitigación declarada: un margen de tolerancia pequeño en vez de comparar al segundo, para no cerrar sesiones que aún sirven. El servidor sigue siendo la autoridad final, y el 401 en vuelo sigue cubriendo el caso contrario.
3. **`expiresAt` no parseable.** La validación actual solo exige que sea `string`. Un valor no parseable no debe dejar la sesión viva para siempre ni borrarla sin motivo: se declara la conducta elegida y se testea.
4. **Esto no arregla producción.** El fallback de `index.html` en el despliegue es otro trabajo, en otro lugar, y se reporta como tal.

## 4. Cierre

**Verificación en tres pruebas, con Windows `curl.exe` contra el Vite del usuario y una corrida de navegador acotada (cada comando envuelto en `timeout`):**

| Prueba | Escenario | Medición |
| --- | --- | --- |
| P1 | `Accept: text/html` sobre `/projects/<uuid>` | `200` + **shell de la SPA** (`<!doctype html>`, `@vite/client`) — antes devolvía el JSON del backend |
| P2 | `Accept: application/json` sobre `/projects` | `401` + **JSON del backend**. El pedido de datos **no cambió de comportamiento** |
| P3 | `Accept: */*` sobre `/projects/<uuid>/model` | `401` + **JSON del backend**. Idem |
| T1 | Navegación dura a `/projects/<id>` con **sesión válida** | **El editor renderiza**: `h1 "Verify Deep Nav 2"`, lienzo `.react-flow`, sin JSON y sin estado de error |
| T2 | Navegación dura con **sesión vencida** (`expiresAt` = ahora − 1 h) | La SPA renderiza y cae en **login**; y `localStorage` de `class-diagrammer.session.v1` quedó en **null**, o sea la entrada vencida se borró |
| T3 | Camino de datos en vivo | Todos los pedidos 2xx: `/model` 200 (×3), `/editing-lease` 200, `/messages` 200, `/events` 200, `/presence` 204; sin banner de error |

P2 y P3 son las que importan como **no-regresión**: el riesgo declarado del `bypass` era que un predicado demasiado amplio devolviera HTML en vez de JSON a toda la aplicación. Está descartado con medición.

Suite: **331 passed / 4 skipped / 0 failed** (325 de base + 6 tests nuevos de expiración). `lint` limpio.

**Límite honesto de la verificación**: T1 y T3 corrieron sobre un proyecto vacío recién creado, así que el lienzo renderizó su interfaz pero **sin nodos**. No se observó un lienzo poblado en esta unidad; eso ya estaba cubierto por las verificaciones anteriores de la vista compacta.

### 4.1 Hallazgo colateral: una bomba de tiempo en la suite

SE-01 puso en rojo **8 tests preexistentes** de tres archivos, porque fijaban `expiresAt: '2026-10-01T12:00:00Z'` — una fecha **ya pasada** al momento del arreglo. Solo pasaban porque nadie miraba la expiración.

Se verificó que **no era una dependencia del comportamiento viejo**, y la prueba está en el propio repo: `web/src/api/assistant.test.ts:130` ya usaba el centinela `'2999-01-01T00:00:00Z'` y `web/src/api/undoRedo.test.ts:57` usaba `'2099-01-01T00:00:00Z'`. Los `2026-10-01` eran los flojos. Se actualizaron a esa misma convención (literal fijo lejano, **no** una expresión dinámica, para no introducir un centinela nuevo).

Se confirmó además que **ninguna aserción dependía de que se aceptara una sesión vencida**: los tests afectados sembraban una sesión solo para probar que el pedido lleva el header `Authorization`, o que la respuesta de login se persiste. Siguen probando lo mismo, ahora con una sesión genuinamente válida.

**No se tocaron** los fixtures de la **concesión de edición** (`collaboration/editingLease.test.ts`, `EditingLeaseNotice.test.tsx`), que usan fechas vencidas **a propósito** y con un `now` inyectado: son otro dominio y sí cubren la expiración de la concesión.

Lección registrada: un fixture con fecha absoluta es una bomba de tiempo que **no explota hasta que alguien arregla el código que lo dejaba pasar**. El verde de esos 8 tests no probaba nada.

### 4.2 Riesgo residual

**El despliegue sigue sin fallback a `index.html`**, así que un enlace profundo no funciona en producción. Fuera de alcance, en `tools/aws-deploy/`, reportado al usuario y no tocado.
