# Referencia técnica del software

## Resumen

Producto colaborativo para diseñar diagramas de clases UML 2.5. El sistema separa el **estado semántico** del modelo UML de su **layout** visual, admite colaboración mediante comandos HTTP y eventos SSE, y ofrece superficies web, móvil, voz, fotografía, XMI y generación determinista de artefactos.

Esta referencia distingue entre diseño previsto, configuración y evidencia medida. El código, las migraciones y los reportes enlazados al final son la fuente de verdad operativa.

## Estado

| Área | Estado conocido | Evidencia o nota |
|---|---|---|
| Backend | Implementado | Spring Boot 3, Java 21, PostgreSQL 16, Flyway y JPA. |
| Web | Implementado | React + TypeScript + Vite; E2E medido: 8/8. |
| Colaboración/presencia | Verificada en el alcance medido | E2E de presencia: 5/5; propagación semántica aproximada: 2–4 s. |
| Móvil | Descriptor-driven con cola offline | Validación mediante comandos Flutter y entorno de integración en vivo. |
| Texto IA | Desactivado por defecto | `AI_TEXT_PROPOSALS_ENABLED=false`; proveedor predeterminado: `deterministic`. |
| Voz | Integrada con ASR local | Sólo `es-ES`; audio máximo de 25 MiB. |
| Foto/Qwen | Opt-in | Endpoint, modelo y timeouts de Qwen son configurables. |
| XMI | Parcial, no lossless | El roundtrip medido conserva elementos, pero pierde relaciones y geometría. |
| Generación | Evidencia positiva acotada | Dos ejecuciones exitosas con el mismo `artifactZipHash` y backend CRUD generado. |
| AWS real | No demostrado localmente | Floci es opt-in y no prueba AWS real. |
| Q9 | Abierto | Requiere resolución explícita; no se presenta como cerrado. |

## Mapa del repositorio

```text
backend/
  src/main/java/com/diagrammer/   # entrada principal, aplicación y controladores
  src/main/resources/db/migration/ # migraciones Flyway V1–V19
  photo-extractor/                # sidecar Python opcional para CU-12B
  README.md                       # operación del backend y sus fronteras de despliegue
web/                              # aplicación React + TypeScript + Vite
  src/                            # dominio, aplicación, infraestructura y UI
  README.md                       # alcance y estado del cliente web
mobile/                           # aplicación Flutter descriptor-driven
  lib/                            # UI, transporte, repositorios e IA local
  README.md                       # navegación, estados, pruebas e integración live
tools/
  local-asr/                      # sidecar faster-whisper CPU int8
  web-e2e/                        # pruebas E2E web, colaboración y geometría
  generation-e2e/                 # evidencia de generación y CRUD del backend generado
  xmi-roundtrip/                  # evidencia de roundtrip con Enterprise Architect
docs/
  analisis-enunciado-parcial-1.md
  esqueleto-proceso-desarrollo-parcial-2.md
  puds/                           # requisitos, análisis, diseño, datos y verificación
odd/                              # tareas, decisiones y runbooks del trabajo del repositorio
```

Documentación y evidencia navegable:

- [Backend](backend/README.md)
- [Web](web/README.md)
- [Móvil](mobile/README.md)
- [ASR local](tools/local-asr/README.md)
- [Extractor fotográfico](backend/photo-extractor/README.md)
- [Análisis del enunciado](docs/analisis-enunciado-parcial-1.md)
- [Esqueleto del proceso de desarrollo](docs/esqueleto-proceso-desarrollo-parcial-2.md)
- [Documentación PUDS](docs/puds/)
- [Tareas y runbooks ODD](odd/)
- [Reporte E2E web](tools/web-e2e/REPORT.md)
- [Reporte de presencia](tools/web-e2e/PRESENCE-PROPAGATION-REPORT.md)
- [Reporte de propagación de geometría](tools/web-e2e/GEOMETRY-PROPAGATION-REPORT.md)
- [Diagnóstico inicial de geometría](tools/web-e2e/GEOMETRY-REPORT.md)
- [Datos de latencia](tools/web-e2e/LATENCY-REPORT.json)
- [Reporte E2E de generación](tools/generation-e2e/REPORT.md)
- [Reporte de roundtrip XMI](tools/xmi-roundtrip/REPORT.md)

Este checkout no contiene un `README.md` en la raíz; por eso esta referencia enlaza la documentación específica de cada componente y los documentos técnicos concretos.

## Arquitectura

```text
┌───────────────┐      HTTP commands / SSE events      ┌─────────────────────┐
│ Web React/Vite│ ────────────────────────────────────▶│ Spring Boot backend │
└───────────────┘                                      └──────────┬──────────┘
┌───────────────┐                                                │
│ Flutter móvil │ ─────── comandos y cola offline ────────────────┘
└───────────────┘                                                │
                                              ┌───────────────────┴────────────┐
                                              │ PostgreSQL 16 + JPA + Flyway   │
                                              └───────────────────┬────────────┘
                                                                  │
                                                        S3 / Floci opt-in

                 ┌─────────────────────────────────────────────────────────────┐
                 │ local-asr: 127.0.0.1:8765/transcribe, faster-whisper CPU int8│
                 └─────────────────────────────────────────────────────────────┘
```

La colaboración actual se describe como **comandos HTTP más SSE/eventos**. No se debe documentar WebSocket como transporte vigente.

## Backend

### Stack y paquetes

| Componente | Uso |
|---|---|
| Spring Boot 3 | Aplicación HTTP, configuración y actuadores. |
| Java 21 | Runtime y lenguaje del backend. |
| JPA | Persistencia y mapeo del modelo. |
| PostgreSQL 16 | Base de datos principal. |
| Flyway | Versionado y ejecución de migraciones. |
| Código de entrada | `backend/src/main/java/com/diagrammer` |

### Modelo y límites de estado

El modelo se organiza alrededor de proyectos, diagramas, participantes y revisiones. El **estado semántico** contiene el significado UML —clases, atributos, operaciones, relaciones y sus extremos—; el **layout** contiene la presentación visual, como nodos y rutas. Ambos evolucionan de forma separada para evitar que mover una figura sea equivalente a cambiar el modelo UML.

Las revisiones y versiones permiten identificar la evolución del modelo y sus operaciones de sincronización. Las propuestas, conflictos, presencia, XMI y generación son superficies asociadas al proyecto, pero no deben confundirse con el estado semántico ni con la geometría.

### Migraciones

Flyway mantiene las migraciones `V1` a `V19`. En conjunto cubren:

- fundación;
- permisos;
- propuestas;
- estado semántico;
- revisión;
- voz y fotografía;
- participantes;
- conflictos;
- XMI;
- autenticación;
- generación;
- artefactos;
- deployments;
- layout.

La correspondencia exacta entre cada número y cada cambio debe verificarse en los scripts de migración; esta lista describe el alcance funcional, no reemplaza esos scripts.

### Comandos del backend

Desde `backend/`:

```powershell
cd backend
docker compose up --build -d
docker compose ps
curl.exe --fail http://127.0.0.1:8080/actuator/health
mvn test
mvn verify
```

`mvn verify` usa pruebas de integración con Testcontainers y requiere un entorno Docker disponible. Para habilitar únicamente el extractor fotográfico opcional:

```powershell
docker compose --profile photo up --build -d
curl.exe --fail http://127.0.0.1:8012/health
```

El camino local Qwen no forma parte del Compose base. Docker Model Runner se prepara explícitamente, por ejemplo:

```powershell
docker desktop enable model-runner --tcp=12434
docker model pull hf.co/ggml-org/Qwen3.5-0.8B-GGUF:Q4_0
docker model list
curl.exe --fail http://127.0.0.1:12434/engines/v1/models
```

### Variables de configuración

Las variables siguientes son las superficies de configuración observadas en `application.yml` y en los Compose del backend. Un valor opt-in no constituye evidencia de que el servicio externo esté disponible.

| Variable | Uso y valor predeterminado conocido |
|---|---|
| `BACKEND_PORT` | Puerto publicado por Compose para el backend; `8080`. Los runbooks E2E suelen usar `8081`. |
| `DB_PORT` | Puerto publicado de PostgreSQL; `5432`. |
| `DB_NAME` | Base de datos de Compose; `umldiagrammer`. |
| `DB_URL` | JDBC de PostgreSQL; en Compose apunta a `db:5432`, fuera de Compose puede apuntar a `localhost` o a una base gestionada. |
| `DB_USERNAME` / `DB_PASSWORD` | Credenciales de la conexión JDBC; los valores locales por defecto son de desarrollo y no son secretos de producción. |
| `CORS_ALLOWED_ORIGINS` | Orígenes permitidos con credenciales; el valor local incluye `http://localhost:3000`, `http://localhost:15173`, `http://localhost:5173`, `http://127.0.0.1:5173`, `http://localhost:5174` y `http://127.0.0.1:5174`. |
| `SEED_DEMO_USERS` | Controla usuarios de demostración; el valor local por defecto es `true` y debe deshabilitarse en producción. Se refleja en `app.seed-demo-users`. |
| `AI_TEXT_PROPOSALS_ENABLED` | Activa propuestas textuales; `false`. |
| `AI_TEXT_PROPOSALS_PROVIDER` | Proveedor textual; `deterministic` por defecto. |
| `AI_QWEN_ENDPOINT` / `AI_QWEN_MODEL` | Endpoint OpenAI-compatible y modelo de Qwen; por defecto usan Docker Model Runner local en `12434`. |
| `AI_QWEN_CONNECT_TIMEOUT_MS` / `AI_QWEN_READ_TIMEOUT_MS` | Timeouts de conexión y lectura; `2000` y `120000` ms. |
| `AI_QWEN_MAX_REQUEST_BYTES` / `AI_QWEN_MAX_RESPONSE_BYTES` | Límites de request y response; `16000000` y `32768` bytes. |
| `AI_QWEN_MAX_CONTEXT_BYTES` / `AI_QWEN_MAX_COMPLETION_TOKENS` | Límites de contexto y completion; `8192` bytes y `768` tokens. |
| `AI_QWEN_CACHE_ENABLED` / `AI_QWEN_CACHE_MAX_ENTRIES` / `AI_QWEN_CACHE_TTL_MS` | Caché opcional de Qwen; `true`, `64` entradas y `10000` ms. |
| `AI_QWEN_WARMUP_ENABLED` | Warm-up de Qwen; `true` en Compose y configuración local. |
| `CU12_PHOTO_PYTHON_ENABLED` / `CU12_PHOTO_QWEN_ENABLED` | Integraciones fotográficas Python y Qwen; el proveedor Qwen está desactivado por defecto. |
| `PHOTO_EXTRACTOR_PORT` | Puerto publicado del sidecar fotográfico; `8012`. |
| `GENERATION_ARTIFACTS_STORE` | Almacenamiento de artefactos; `postgres` por defecto, `s3` en el perfil AWS opt-in. |
| `GENERATION_ARTIFACTS_S3_BUCKET` / `GENERATION_ARTIFACTS_S3_REGION` | Bucket y región para S3; `generation-artifacts` y `us-east-1`. |
| `GENERATION_ARTIFACTS_S3_ENDPOINT` / `GENERATION_ARTIFACTS_S3_KEY_PREFIX` | Endpoint explícito de emulación o despliegue y prefijo; endpoint vacío y `generation-artifacts` por defecto. |
| `FLOCI_PORT` / `FLOCI_UI_ENABLED` | Puerto y consola del emulador Floci; `4566` y `false`; sólo se usan con el perfil `aws`. |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | Valores del proveedor AWS para el emulador local; el Compose de desarrollo usa `test`, no credenciales reales. |
| `LOCAL_ASR_MODEL_PATH` | Ruta fuera del repositorio al modelo `faster-whisper` que carga el sidecar ASR. |
| `LOCAL_ASR_ALLOWED_ORIGINS` | Orígenes CORS del ASR; por defecto el README del sidecar lista los puertos `5173` de localhost y loopback. Si la web corre en `3000`, debe ajustarse explícitamente. |
| `VITE_API_BASE_URL` | Base URL del backend para la web; si falta, el cliente usa el origen actual. |
| `PLATFORM_BASE_URL` | Base URL del backend para la prueba de integración móvil; es configurable en `mobile/test/integration_test.dart`. |

## API HTTP

El inventario siguiente reproduce las rutas de los controladores actuales. `{projectId}`, `{modelId}`, `{viewId}`, `{userId}`, `{proposalId}`, `{operationId}`, `{runId}` y `{deploymentId}` son variables de ruta. Cuando se indica una variante, son dos mappings reales y no una familia abreviada.

### Autenticación

| Método | Ruta | Propósito |
|---|---|---|
| `POST` | `/auth/login` | Iniciar una sesión autenticada. |
| `POST` | `/auth/logout` | Invalidar la sesión actual. |
| `POST` | `/auth/demo-login` | Login de demostración; sólo se registra cuando `app.seed-demo-users=true`. |

### Proyectos y recuperación

| Método | Ruta | Propósito |
|---|---|---|
| `POST` | `/projects` | Crear un proyecto. |
| `GET` | `/projects` | Listar proyectos visibles. |
| `GET` | `/projects/{projectId}/model` | Abrir el estado recuperable del modelo. |
| `GET` | `/projects/{projectId}/versions` | Listar versiones del proyecto. |
| `GET` | `/projects/{projectId}/versions/{revision}` | Seleccionar una revisión concreta. |

### Permisos del proyecto

| Método | Ruta | Propósito |
|---|---|---|
| `POST` | `/projects/{projectId}/commands` | Ejecutar un comando de permisos del proyecto. |
| `GET` | `/projects/{projectId}/invitations` | Consultar invitaciones. |
| `GET` | `/projects/{projectId}/members` | Consultar miembros. |
| `GET` | `/projects/{projectId}/account-suggestions` | Buscar cuentas sugeribles. |
| `PATCH` | `/projects/{projectId}/members/{userId}` | Actualizar el miembro o su estado. |
| `PUT` | `/projects/{projectId}/members/{userId}/capabilities/create-diagram` | Cambiar la capacidad de crear diagramas. |
| `GET` | `/projects/{projectId}/permission-history` | Consultar el historial de permisos. |
| `GET` | `/projects/{projectId}/permissions/history` | Alias del historial de permisos. |

### Permisos del diagrama

| Método | Ruta | Propósito |
|---|---|---|
| `POST` | `/projects/{projectId}/diagrams` | Crear una vista o diagrama. |
| `POST` | `/projects/{projectId}/diagrams/{viewId}/collaboration` | Configurar la colaboración de una vista. |
| `PUT` | `/projects/{projectId}/diagrams/{viewId}/collaborators/{userId}` | Crear o actualizar un colaborador de la vista. |
| `DELETE` | `/projects/{projectId}/diagrams/{viewId}/collaborators/{userId}` | Eliminar un colaborador de la vista. |
| `PUT` | `/projects/{projectId}/diagrams/{viewId}/administrator` | Cambiar el administrador de la vista. |
| `GET` | `/projects/{projectId}/diagrams/{viewId}/access` | Consultar el acceso efectivo a la vista. |
| `GET` | `/projects/{projectId}/diagrams/{viewId}/permission-history` | Consultar el historial de permisos de la vista. |
| `GET` | `/projects/{projectId}/diagrams/{viewId}/permissions/history` | Alias del historial de permisos de la vista. |

### Propuestas y revisión

| Método | Ruta | Propósito |
|---|---|---|
| `POST` | `/projects/{projectId}/proposals` | Crear una propuesta de texto o voz. |
| `POST` | `/projects/{projectId}/proposals/photo` | Crear una propuesta a partir de una imagen multipart. |
| `GET` | `/projects/{projectId}/proposals/{proposalId}` | Consultar una propuesta. |
| `GET` | `/projects/{projectId}/proposals/{proposalId}/review` | Consultar el estado de revisión. |
| `POST` | `/projects/{projectId}/proposals/{proposalId}/review-decision` | Confirmar o descartar una propuesta. |

### Sincronización, conflictos y presencia

| Método | Ruta | Propósito |
|---|---|---|
| `POST` | `/sync/commands` | Enviar una operación de sincronización. |
| `POST` | `/sync/operations` | Alias de operaciones de sincronización. |
| `POST` | `/projects/{projectId}/sync/commands` | Enviar una operación con el proyecto en la ruta. |
| `POST` | `/projects/{projectId}/sync/operations` | Alias con el proyecto en la ruta. |
| `POST` | `/projects/{projectId}/models/{modelId}/sync/flush` | Vaciar un lote de operaciones pendientes. |
| `GET` | `/projects/{projectId}/models/{modelId}/sync/conflicts` | Listar conflictos del modelo. |
| `GET` | `/projects/{projectId}/models/{modelId}/sync/conflicts/{operationId}` | Consultar un conflicto concreto. |
| `POST` | `/projects/{projectId}/models/{modelId}/sync/conflicts/{operationId}/resolve` | Resolver un conflicto concreto. |
| `POST` | `/projects/{projectId}/sync/presence` | Publicar presencia; `modelId` se envía como query parameter. |
| `GET` | `/sync/snapshot` | Obtener un snapshot; `modelId` se envía como query parameter. |
| `GET` | `/projects/{projectId}/sync/snapshot` | Obtener un snapshot con el proyecto en la ruta. |
| `GET` | `/sync/events` | Consumir eventos SSE; `modelId` y opcionalmente `afterRevision` se envían como query parameters. |
| `GET` | `/projects/{projectId}/sync/events` | Consumir eventos SSE con el proyecto en la ruta. |

Las dos últimas rutas producen `text/event-stream`. El evento inicial es `snapshot`; los eventos de sincronización y de presencia se entregan en el mismo stream SSE.

### XMI

| Método | Ruta | Propósito |
|---|---|---|
| `POST` | `/projects/{projectId}/models/{modelId}/xmi/export` | Exportar el modelo a XMI. |
| `POST` | `/projects/{projectId}/models/{modelId}/xmi/import/preview` | Analizar una importación antes de confirmarla. |
| `POST` | `/projects/{projectId}/models/{modelId}/xmi/import/confirm` | Confirmar una importación previamente revisada. |

### Generación

| Método | Ruta | Propósito |
|---|---|---|
| `POST` | `/projects/{projectId}/generation-targets` | Crear un objetivo de generación. |
| `GET` | `/projects/{projectId}/generation-targets` | Listar objetivos de generación. |
| `GET` | `/projects/{projectId}/generation-profiles` | Listar perfiles y plantillas. |
| `POST` | `/projects/{projectId}/generations` | Iniciar una ejecución de generación. |
| `GET` | `/projects/{projectId}/generations/{runId}` | Consultar una ejecución. |
| `GET` | `/projects/{projectId}/generations/{runId}/artifacts` | Descargar el ZIP de artefactos. |

### Deployments

| Método | Ruta | Propósito |
|---|---|---|
| `POST` | `/projects/{projectId}/deployments` | Crear un deployment. |
| `GET` | `/projects/{projectId}/deployments` | Listar deployments. |
| `GET` | `/projects/{projectId}/deployments/{deploymentId}` | Consultar un deployment. |
| `PUT` | `/projects/{projectId}/deployments/{deploymentId}` | Actualizar un deployment. |
| `POST` | `/projects/{projectId}/deployments/{deploymentId}/disable` | Deshabilitar un deployment. |
| `POST` | `/projects/{projectId}/deployments/{deploymentId}/enable` | Habilitar un deployment. |
| `DELETE` | `/projects/{projectId}/deployments/{deploymentId}` | Eliminar un deployment. |

### Salud

| Método | Ruta | Propósito |
|---|---|---|
| `GET` | `/actuator/health` | Comprobar la salud del backend; los detalles no se exponen. |

## Autenticación y seguridad

- `/auth/login`, `/auth/logout` y `/auth/demo-login` forman la superficie de autenticación expuesta.
- `X-User-Id` es una identidad de **bootstrap** para desarrollo o integración; no es autenticación de producción.
- Las decisiones de permisos deben pasar por la superficie de permisos del proyecto y no depender únicamente de una cabecera controlada por el cliente.
- Las integraciones de foto, Qwen, S3 y Floci son opt-in y deben permanecer explícitas en la configuración del entorno.
- Los reportes de E2E y la configuración desplegada son la evidencia para afirmar una garantía concreta; la existencia de un endpoint no equivale por sí sola a una garantía de producción.

## IA: texto, voz, fotografía y Qwen

### Texto

La configuración predeterminada es segura y determinista:

```properties
AI_TEXT_PROPOSALS_ENABLED=false
AI_TEXT_PROPOSALS_PROVIDER=deterministic
```

Con esta configuración no se debe presentar el proveedor de texto IA como activo. Las propuestas de texto pueden habilitarse mediante configuración explícita y deben conservar su trazabilidad como propuestas sujetas a revisión.

### Qwen y fotografía

Qwen es una integración configurable y opt-in. El endpoint, el modelo y los timeouts se pueden definir por configuración; no deben hardcodearse como una dependencia obligatoria. La decisión de revisión fotográfica usa `POST /projects/{projectId}/proposals/{proposalId}/review-decision`. La activación de foto/Qwen no constituye evidencia de que un servicio remoto real esté disponible en todos los entornos.

### Voz

La voz utiliza el contrato ASR local descrito en la siguiente sección. El texto transcrito puede entrar al flujo de propuestas, pero la transcripción y su propuesta posterior son etapas distintas.

## Contrato ASR local

| Campo | Contrato |
|---|---|
| Motor | `faster-whisper` |
| Ejecución | CPU, cuantización `int8` |
| Endpoint | `http://127.0.0.1:8765/transcribe` |
| Idioma admitido | `es-ES` únicamente |
| Tamaño máximo de audio | 25 MiB |
| Componente | `tools/local-asr` |

El endpoint local no debe documentarse como un servicio multilingüe ni como una capacidad ilimitada. El formato exacto de payload y respuesta queda definido por la implementación del sidecar y sus reportes asociados.

## Web

La aplicación web vive en `web/` y usa React, TypeScript y Vite. Su arquitectura cliente consume el backend mediante comandos HTTP, consulta snapshots y conflictos, y recibe actualizaciones por eventos SSE. El puerto normal del backend es `8080`; los runbooks publicados para E2E usan el mapeo `8081` para el backend y `3000` para la web.

### Atajos

| Atajo | Acción |
|---|---|
| `Ctrl+S` | Guardar o persistir cambios. |
| `Ctrl+Z` | Deshacer. |
| `Ctrl+Y` | Rehacer. |
| `Ctrl+Shift+Z` | Rehacer en la variante habitual del navegador. |
| `Ctrl+C` | Copiar. |
| `Ctrl+V` | Pegar. |
| `Ctrl+D` | Duplicar. |
| `Delete` | Eliminar la selección. |

### Comandos exactos

Desde `web/`:

```bash
cd web
npm ci
npm run dev -- --port 3000
npm run test:run
npm run typecheck
npm run lint
npm run build
```

El servidor de desarrollo de los E2E debe exponerse en `3000`, porque los runbooks usan `http://localhost:3000` y el backend debe incluir ese origen en `CORS_ALLOWED_ORIGINS`. `VITE_API_BASE_URL` sólo es necesario cuando la API no está en el mismo origen de la web.

## Móvil Flutter

`mobile/` contiene una aplicación Flutter **descriptor-driven**: el descriptor determina la forma de representar y operar sobre capacidades del modelo, mientras que la sincronización conserva la separación entre semántica y layout.

La aplicación móvil debe tolerar desconexiones mediante una **cola offline**. Los comandos se almacenan localmente, se envían cuando vuelve la conectividad y se someten al flujo de flush y conflictos del backend. La cola no convierte automáticamente un comando en una revisión confirmada: la confirmación pertenece al protocolo de sincronización.

### Comandos exactos

Desde `mobile/`:

```bash
cd mobile
flutter pub get
flutter test
RUN_LIVE_INTEGRATION=1 flutter test test/integration_test.dart
```

La prueba live acepta `PLATFORM_BASE_URL` para elegir el backend, por ejemplo `PLATFORM_BASE_URL=http://localhost:8081`. En PowerShell, la misma configuración se expresa como `$env:RUN_LIVE_INTEGRATION='1'; $env:PLATFORM_BASE_URL='http://localhost:8081'` antes de ejecutar Flutter. No se debe confundir una prueba Flutter local con la evidencia de una integración live completa.

## Colaboración, presencia y sincronización

- La unidad de colaboración es un comando HTTP que modifica o sincroniza el estado.
- `POST /projects/{projectId}/models/{modelId}/sync/flush` procesa un lote de comandos pendientes.
- `GET /projects/{projectId}/models/{modelId}/sync/conflicts` y `GET /projects/{projectId}/models/{modelId}/sync/conflicts/{operationId}` exponen conflictos para su resolución explícita.
- `POST /projects/{projectId}/models/{modelId}/sync/conflicts/{operationId}/resolve` registra la resolución.
- `POST /projects/{projectId}/sync/presence` publica presencia de participantes.
- `GET /sync/snapshot` y `GET /projects/{projectId}/sync/snapshot` entregan una vista sincronizable.
- `GET /sync/events` y `GET /projects/{projectId}/sync/events` exponen eventos mediante SSE/eventos HTTP.
- La presencia no es el estado semántico: informa actividad o participantes, pero no sustituye una revisión del modelo.
- La propagación semántica medida se sitúa aproximadamente entre 2 y 4 segundos en el escenario validado.

No se debe afirmar que la aplicación usa WebSocket como transporte actual.

## XMI

El flujo XMI se divide en preview, confirmación y exportación:

```text
POST /projects/{projectId}/models/{modelId}/xmi/import/preview
  → revisión humana o de cliente
POST /projects/{projectId}/models/{modelId}/xmi/import/confirm
POST /projects/{projectId}/models/{modelId}/xmi/export
```

### Evidencia de roundtrip

| Elemento | Conservado |
|---|---:|
| Clases | 8/8 |
| Atributos | 23/23 |
| Operaciones | 11/11 |
| Relaciones | 1/8 |
| Extremos de relaciones | 2/16 |
| Nodos de geometría | 0/10 |
| Rutas | 0/8 |

La pérdida no está declarada completamente. Por tanto, XMI **no es lossless** y no debe anunciarse como roundtrip completo. Q9 permanece abierto.

## Generación determinista

La generación se organiza mediante objetivos, perfiles, ejecuciones y artefactos:

```text
/projects/{projectId}/generation-targets
  → /projects/{projectId}/generation-profiles
  → /projects/{projectId}/generations
  → /projects/{projectId}/generations/{runId}/artifacts
```

La evidencia disponible registra dos ejecuciones exitosas con el mismo `artifactZipHash`. Ambas produjeron un backend CRUD generado. Esto demuestra reproducibilidad en esas ejecuciones concretas; no demuestra por sí solo cobertura universal de todos los modelos, targets o perfiles.

## Despliegue y servicios

### Backend y base de datos

- El backend normalmente escucha en `8080`.
- Docker Compose puede mapear el puerto de host mediante `BACKEND_PORT`.
- Los runbooks publicados para E2E usan `8081` como puerto publicado del backend y `3000` para la web.
- PostgreSQL 16 es la base de datos principal.
- Flyway aplica las migraciones `V1`–`V19`.
- La comprobación de salud está disponible en `/actuator/health`.

### S3 y Floci

S3 representa el almacenamiento de objetos usado por los flujos que requieren artefactos u objetos externos. Floci puede habilitarse de forma optativa para ejecución local compatible con AWS. Floci local **no prueba** conectividad, permisos, comportamiento o disponibilidad de AWS real.

### Arranque local

```bash
docker compose up --build -d
```

Después del arranque, comprobar la salud en el puerto publicado del backend, por ejemplo:

```text
http://localhost:8080/actuator/health
```

Si Compose define `BACKEND_PORT`, debe usarse ese puerto; en los runbooks E2E publicados se usa `8081`.

## Pruebas y evidencia

| Evidencia | Resultado | Naturaleza |
|---|---:|---|
| Web E2E | 8/8 | Medida. |
| Presencia E2E | 5/5 | Medida. |
| Propagación semántica | aproximadamente 2–4 s | Medida en el escenario validado. |
| Generación determinista | 2 ejecuciones exitosas | Medida; mismo `artifactZipHash`. |
| Backend CRUD generado | Presente en ambas ejecuciones | Medida junto con la evidencia de generación. |
| XMI roundtrip | Conservación parcial | Medida; ver tabla XMI. |

Comandos de verificación del backend:

```bash
mvn test
mvn verify
```

Estos comandos expresan la verificación ejecutable del módulo. Sus resultados concretos dependen del entorno y deben conservarse en los reportes de ejecución.

## Limitaciones y riesgos conocidos

1. XMI no es lossless: se conservan 8/8 clases, 23/23 atributos y 11/11 operaciones, pero sólo 1/8 relaciones, 2/16 extremos, 0/10 nodos de geometría y 0/8 rutas.
2. Las pérdidas XMI no están completamente declaradas; Q9 sigue abierto.
3. `X-User-Id` no es autenticación de producción.
4. El texto IA está desactivado por defecto y usa proveedor determinista; Qwen y fotografía requieren opt-in.
5. El ASR local sólo admite `es-ES` y audio de hasta 25 MiB.
6. El transporte actual es HTTP más SSE/eventos; no se debe asumir WebSocket.
7. La evidencia de generación se limita a dos ejecuciones exitosas y no sustituye pruebas para todos los targets.
8. Floci permite pruebas locales opt-in, pero no demuestra AWS real.
9. La propagación de 2–4 segundos corresponde al escenario medido, no a un SLA general.

## Referencias

- [README del backend](backend/README.md)
- [README web](web/README.md)
- [README móvil](mobile/README.md)
- [README del ASR local](tools/local-asr/README.md)
- [README del extractor fotográfico](backend/photo-extractor/README.md)
- [Análisis del enunciado](docs/analisis-enunciado-parcial-1.md)
- [Esqueleto del proceso de desarrollo](docs/esqueleto-proceso-desarrollo-parcial-2.md)
- [Documentación PUDS](docs/puds/)
- [Tareas y runbooks ODD](odd/)
- [Reporte E2E web](tools/web-e2e/REPORT.md)
- [Reporte de presencia](tools/web-e2e/PRESENCE-PROPAGATION-REPORT.md)
- [Reporte de propagación de geometría](tools/web-e2e/GEOMETRY-PROPAGATION-REPORT.md)
- [Diagnóstico inicial de geometría](tools/web-e2e/GEOMETRY-REPORT.md)
- [Datos de latencia](tools/web-e2e/LATENCY-REPORT.json)
- [Reporte E2E de generación](tools/generation-e2e/REPORT.md)
- [Reporte de roundtrip XMI](tools/xmi-roundtrip/REPORT.md)

## Glosario

| Término | Definición |
|---|---|
| ASR | Automatic Speech Recognition; conversión de voz a texto. |
| CRUD | Create, Read, Update, Delete. |
| Descriptor-driven | Arquitectura móvil guiada por descriptores de capacidades o modelo. |
| E2E | Prueba end-to-end. |
| Floci | Servicio local opt-in para emular o facilitar integraciones AWS en desarrollo; no es AWS real. |
| Layout | Geometría y presentación visual del diagrama. |
| PUDS | Documentación del diseño o especificación del proyecto ubicada en `docs/puds`. |
| Q9 | Pregunta o asunto pendiente identificado por la evidencia del proyecto. |
| SSE | Server-Sent Events; flujo de eventos enviado sobre HTTP. |
| XMI | XML Metadata Interchange, formato de intercambio de modelos. |
| `artifactZipHash` | Huella usada para comparar la reproducibilidad de artefactos generados. |

> Nota final: el código, las migraciones y los reportes son la fuente de verdad. Esta referencia resume los hechos verificados y no sustituye esas fuentes.
