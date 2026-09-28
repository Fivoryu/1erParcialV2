# 4 BIBLIOGRAFÍA · 5 ANEXOS

> **Cómo usar este archivo.** Las imágenes están **insertadas**: se ven en la vista previa. Copiá desde
> la vista previa renderizada (`Ctrl+Shift+V`) para que tablas e imágenes lleguen al Word.
>
> Las fuentes de la bibliografía fueron **verificadas una por una** (título, versión y URL); no hay
> referencias inventadas. Los anexos usan los diagramas y las capturas que ya están en el repositorio,
> en `docs/diagramas/` y `docs/capturas/`.

---

# 4 Bibliografía

## 4.1 Normas y especificaciones

- Object Management Group. (2017). *Unified Modeling Language (UML), version 2.5.1* (documento
  formal/17-12-05). https://www.omg.org/spec/UML/2.5.1/
- Object Management Group. (2015). *XML Metadata Interchange (XMI), version 2.5.1* (documento
  formal/2015-06-07). https://www.omg.org/spec/XMI/2.5.1/
- Fette, I., & Melnikov, A. (2011). *The WebSocket Protocol* (RFC 6455). Internet Engineering Task
  Force. https://datatracker.ietf.org/doc/html/rfc6455

## 4.2 Herramientas y plataformas utilizadas

- Sparx Systems. (2021). *Enterprise Architect user guide 15.1 — XMI import and export*.
  https://sparxsystems.com/enterprise_architect_user_guide/15.1/model_publishing/importexport.html
- Redgate. (2026). *Flyway product documentation — PostgreSQL database*.
  https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database
- Spring. (2026). *Spring Boot reference documentation 3.4.5*. https://docs.spring.io/spring-boot/
- The PostgreSQL Global Development Group. (2026). *PostgreSQL 16 documentation*.
  https://www.postgresql.org/docs/16/
- Meta Open Source. (2026). *React documentation*. https://react.dev/
- Microsoft. (2026). *TypeScript documentation*. https://www.typescriptlang.org/docs/
- Vite. (2026). *Vite documentation*. https://vite.dev/
- Vitest. (2026). *Vitest documentation*. https://vitest.dev/
- Poimandres. (2026). *Zustand documentation*. https://zustand.docs.pmnd.rs/
- Google. (2026). *Flutter documentation*. https://docs.flutter.dev/
- Dart team. (2026). *Dart documentation*. https://dart.dev/guides
- Docker Inc. (2026). *Docker Compose documentation*. https://docs.docker.com/compose/
- Amazon Web Services. (2026). *AWS SDK for Java 2.x — developer guide*.
  https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/
- Testcontainers. (2026). *Testcontainers for Java*. https://java.testcontainers.org/

## 4.3 Inteligencia artificial local

- Qwen. (2026). *Qwen3.5-0.8B — model card*. Hugging Face.
  https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF
- gsmlg-app. (2026). *lib_llama_cpp 0.7.3 — Flutter package*. pub.dev.
  https://pub.dev/packages/lib_llama_cpp
- ggml-org. (2026). *llama.cpp*. GitHub. https://github.com/ggml-org/llama.cpp
- SYSTRAN. (2026). *faster-whisper: faster Whisper transcription with CTranslate2*. GitHub.
  https://github.com/SYSTRAN/faster-whisper

## 4.4 Emulación local de servicios en la nube

- Floci. (2026). *Floci — the AWS local emulator*. https://floci.io/floci/
- floci-io. (2026). *Floci: light, fluffy, and always free — the AWS local emulator*. GitHub.
  https://github.com/floci-io/floci

## 4.5 Proceso, modelado y arquitectura

- Jacobson, I., Booch, G., & Rumbaugh, J. (1999). *The unified software development process*.
  Addison-Wesley.
- Fowler, M. (2003). *UML distilled: A brief guide to the standard object modeling language*
  (3.ª ed.). Addison-Wesley.
- Brown, S. (2018). *The C4 model for visualising software architecture*. https://c4model.com/
- Shapiro, M., Preguiça, N., Baquero, C., & Zawirski, M. (2011). Conflict-free replicated data types.
  En *Stabilization, Safety, and Security of Distributed Systems (SSS 2011)* (pp. 386–400). Springer.
  https://doi.org/10.1007/978-3-642-24550-3_29

## 4.6 Documentación interna del proyecto

- *Captura de requisitos del PUDS* (`docs/puds/01-captura-de-requisitos.md`).
- *Análisis del PUDS* (`docs/puds/02-analisis.md`).
- *Diseño del PUDS* (`docs/puds/03-diseno.md`) y *acta de cierre del diseño*
  (`docs/puds/cierre/01-cierre-diseno.md`).
- *Contratos transversales* (`docs/puds/contratos/01-contratos-transversales.md`) y *comparación de
  mecanismos de colaboración* (`docs/puds/contratos/02-comparacion-colaboracion.md`).
- *Mapeo de la base de datos* (`docs/puds/datos/01-mapeo-base-de-datos.md`) y *esquema físico*
  (`docs/puds/datos/02-esquema-fisico.sql`).
- *Diseño del generador* (`docs/puds/generacion/01-diseno-generador.md`) y *plan de verificación*
  (`docs/puds/verificacion/01-verificacion.md`).
- *Referencia técnica del software* (`SOFTWARE.md`).
- *Fixture canónico de interoperabilidad* (`docs/fixtures/xmi/Prueba3.xml`).
- Informes de evidencia: `tools/ai-xmi-roundtrip/REPORT.md`, `tools/xmi-roundtrip/REPORT.md`,
  `tools/generation-e2e/REPORT.md`, `tools/web-e2e/REPORT.md`,
  `tools/web-e2e/GEOMETRY-PROPAGATION-REPORT.md`, `tools/web-e2e/PRESENCE-PROPAGATION-REPORT.md` y
  `odd/demo-runbook.md`.

---

# 5 Anexos

## 5.1 Anexo A – Diagramas de Casos de Uso Individuales

Veintitrés diagramas, uno por caso de uso, con el actor iniciador y el caso correspondiente. Archivos
en `docs/diagramas/casos-de-uso/individuales/`.

| CU | Diagrama |
| --- | --- |
| CU-01 | ![CU-01](diagramas/casos-de-uso/individuales/CU-01.png) |
| CU-02 | ![CU-02](diagramas/casos-de-uso/individuales/CU-02.png) |
| CU-03 | ![CU-03](diagramas/casos-de-uso/individuales/CU-03.png) |
| CU-04 | ![CU-04](diagramas/casos-de-uso/individuales/CU-04.png) |
| CU-05 | ![CU-05](diagramas/casos-de-uso/individuales/CU-05.png) |
| CU-06 | ![CU-06](diagramas/casos-de-uso/individuales/CU-06.png) |
| CU-07 | ![CU-07](diagramas/casos-de-uso/individuales/CU-07.png) |
| CU-08 | ![CU-08](diagramas/casos-de-uso/individuales/CU-08.png) |
| CU-09 | ![CU-09](diagramas/casos-de-uso/individuales/CU-09.png) |
| CU-10 | ![CU-10](diagramas/casos-de-uso/individuales/CU-10.png) |
| CU-11 | ![CU-11](diagramas/casos-de-uso/individuales/CU-11.png) |
| CU-12 | ![CU-12](diagramas/casos-de-uso/individuales/CU-12.png) |
| CU-13 | ![CU-13](diagramas/casos-de-uso/individuales/CU-13.png) |
| CU-14 | ![CU-14](diagramas/casos-de-uso/individuales/CU-14.png) |
| CU-15 | ![CU-15](diagramas/casos-de-uso/individuales/CU-15.png) |
| CU-16 | ![CU-16](diagramas/casos-de-uso/individuales/CU-16.png) |
| CU-17 | ![CU-17](diagramas/casos-de-uso/individuales/CU-17.png) |
| CU-18 | ![CU-18](diagramas/casos-de-uso/individuales/CU-18.png) |
| CU-19 | ![CU-19](diagramas/casos-de-uso/individuales/CU-19.png) |
| CU-20 | ![CU-20](diagramas/casos-de-uso/individuales/CU-20.png) |
| CU-21 | ![CU-21](diagramas/casos-de-uso/individuales/CU-21.png) |
| CU-22 | ![CU-22](diagramas/casos-de-uso/individuales/CU-22.png) |
| CU-23 | ![CU-23](diagramas/casos-de-uso/individuales/CU-23.png) |

---

## 5.2 Anexo B – Modelos Generales de Casos de Uso

Los tres modelos por ciclo y la vista general. Archivos en `docs/diagramas/casos-de-uso/`.

### Ciclo 1 — Fundación del proyecto y edición manual UML

![Ciclo 1](diagramas/casos-de-uso/CU-Ciclo-1.png)

### Ciclo 2 — Edición asistida, colaboración e interoperabilidad

![Ciclo 2](diagramas/casos-de-uso/CU-Ciclo-2.png)

### Ciclo 3 — Generación determinista, clientes y demostración

![Ciclo 3](diagramas/casos-de-uso/CU-Ciclo-3.png)

### Vista general

![General](diagramas/casos-de-uso/CU-General.png)

---

## 5.3 Anexo C – Diagramas del Flujo de Análisis

### C.1 Paquetes lógicos P01–P09

Un diagrama por paquete, con su responsabilidad. Archivos en `docs/diagramas/analisis/paquetes/`.

| Paquete | Diagrama |
| --- | --- |
| P01 Gestión de proyectos y acceso | ![P01](diagramas/analisis/paquetes/P01.png) |
| P02 Núcleo UML y validación | ![P02](diagramas/analisis/paquetes/P02.png) |
| P03 Comandos, historial y versionado | ![P03](diagramas/analisis/paquetes/P03.png) |
| P04 Colaboración y sincronización offline | ![P04](diagramas/analisis/paquetes/P04.png) |
| P05 Entradas multimodales e IA | ![P05](diagramas/analisis/paquetes/P05.png) |
| P06 Interoperabilidad XMI | ![P06](diagramas/analisis/paquetes/P06.png) |
| P07 Generación y verificación de backend | ![P07](diagramas/analisis/paquetes/P07.png) |
| P08 Contratos y servicios generados | ![P08](diagramas/analisis/paquetes/P08.png) |
| P09 Clientes y demostración | ![P09](diagramas/analisis/paquetes/P09.png) |

### C.2 Relación paquete ↔ casos de uso

Archivos en `docs/diagramas/analisis/relaciones/`.

| Paquete | Casos de uso | Diagrama |
| --- | --- | --- |
| P01 | CU-01, CU-02, CU-03, CU-13 | ![R P01](diagramas/analisis/relaciones/P01.png) |
| P02 | CU-04, CU-05, CU-06, CU-08 | ![R P02](diagramas/analisis/relaciones/P02.png) |
| P03 | CU-07, CU-10 | ![R P03](diagramas/analisis/relaciones/P03.png) |
| P04 | CU-14, CU-15 | ![R P04](diagramas/analisis/relaciones/P04.png) |
| P05 | CU-09, CU-11, CU-12 | ![R P05](diagramas/analisis/relaciones/P05.png) |
| P06 | CU-16, CU-17 | ![R P06](diagramas/analisis/relaciones/P06.png) |
| P07 | CU-18, CU-19, CU-20 | ![R P07](diagramas/analisis/relaciones/P07.png) |
| P08 | CU-18 a CU-21 (contrato de apoyo) | ![R P08](diagramas/analisis/relaciones/P08.png) |
| P09 | CU-21, CU-22, CU-23 | ![R P09](diagramas/analisis/relaciones/P09.png) |

### C.3 Diagramas de comunicación

Un diagrama por caso de uso relevante, con actor, objetos `boundary`/`control`/`entity` y mensajes
numerados. Archivos en `docs/diagramas/comunicacion/`.

| Caso de uso | Diagrama |
| --- | --- |
| CU-04 Gestionar clasificadores UML | ![Com CU-04](diagramas/comunicacion/CU-04-Gestionar-clasificadores-UML.png) |
| CU-09 Interpretar instrucciones textuales con IA | ![Com CU-09](diagramas/comunicacion/CU-09-Interpretar-instrucciones-textuales-con-IA.png) |
| CU-14 Sincronizar cambios en colaboración | ![Com CU-14](diagramas/comunicacion/CU-14-Sincronizar-cambios-en-colaboracion.png) |
| CU-16 Importar modelo XMI | ![Com CU-16](diagramas/comunicacion/CU-16-Importar-modelo-XMI.png) |
| CU-19 Generar backend determinista | ![Com CU-19](diagramas/comunicacion/CU-19-Generar-backend-determinista.png) |
| CU-23 Ejecutar demostración integral | ![Com CU-23](diagramas/comunicacion/CU-23-Ejecutar-demostracion-integral.png) |

### C.4 Análisis de paquetes

![Análisis de paquetes](diagramas/analisis/pkg-Diagrama-General-de-Paquetes.png)

---

## 5.4 Anexo D – Diagramas del Flujo de Diseño

### D.1 Diseño físico (despliegue)

![Despliegue](diagramas/diseno/despliegue.png)

### D.2 Arquitectura lógica organizada en capas

![Capas](diagramas/diseno/capas.png)

### D.3 Diagramas C4

> **Pendiente.** Los tres diagramas C4 (contexto, contenedores y componentes) los elabora el equipo.
> Cuando estén, van aquí.

### D.4 Diagrama de clases del sistema

![Clases](diagramas/diseno/clases.png)

### D.5 Diagramas de secuencia

| Escenario | Casos de uso | Diagrama |
| --- | --- | --- |
| Crear y validar clasificador | CU-04, CU-05, CU-08 | ![S1](diagramas/diseno/secuencia-01-crear-y-validar-clasificador.png) |
| Revisar y confirmar propuesta IA | CU-09, CU-10, CU-12 | ![S2](diagramas/diseno/secuencia-02-revisar-y-confirmar-propuesta-ia.png) |
| Conflicto y delete-wins | CU-15 | ![S3](diagramas/diseno/secuencia-03-conflicto-y-delete-wins.png) |
| Offline y revocación | CU-14, CU-15, CU-22 | ![S4](diagramas/diseno/secuencia-04-offline-y-revocacion.png) |
| Importar XMI | CU-16, CU-17 | ![S5](diagramas/diseno/secuencia-05-importar-xmi.png) |
| Generación y verificación | CU-18, CU-19, CU-20 | ![S6](diagramas/diseno/secuencia-06-generacion-y-verificacion.png) |

### D.6 Flujo integral esperado

![Flujo integral](diagramas/diseno/flujo-integral.png)

### D.7 Implementación de la arquitectura

| Vista | Diagrama |
| --- | --- |
| Arquitectura del sistema | ![Impl sistema](diagramas/diseno/implementacion-sistema.png) |
| Subsistema backend | ![Impl backend](diagramas/diseno/implementacion-backend.png) |
| Subsistema editor web | ![Impl web](diagramas/diseno/implementacion-editor-web.png) |
| Subsistema cliente Flutter | ![Impl móvil](diagramas/diseno/implementacion-cliente-flutter.png) |

---

## 5.5 Anexo E – Evidencias de Interoperabilidad XMI

### E.1 Perfil y fixture

| Elemento | Valor |
| --- | --- |
| Perfil primario | Enterprise Architect 15.0.1514 build 1514 |
| Formato | XMI 2.1 / UML 2.1, extensiones EA 6.5, información de diagrama |
| Fixture canónico | `docs/fixtures/xmi/Prueba3.xml` (preservado byte a byte) |
| Instrumento | `tools/xmi-roundtrip/run.ps1` y `tools/ai-xmi-roundtrip/run-domain.mjs` |

### E.2 Resultado del ida y vuelta (tres dominios)

| Aspecto | Antes de las correcciones | Después |
| --- | ---: | ---: |
| Atributos con tipo resuelto | 0 / 24 | **24 / 24** |
| Asociaciones conservadas tras EA | 0 / 7 | **7 / 7** |
| Extremos de asociación con referencia de tipo | 0 / 14 | **14 / 14** |
| Igualdad estricta plataforma ↔ EA | No | **EQUAL** en los dominios A, B y C |

### E.3 Matriz de pérdidas declaradas

| Código | Cuándo se emite | Severidad | Comportamiento |
| --- | --- | --- | --- |
| `UNSUPPORTED_TYPE` | Un atributo no tiene referencia de tipo resoluble | Advertencia | Se exporta sin tipo y **se declara la pérdida** en el reporte |
| Elemento sin mapeo al perfil | El elemento no pertenece al subconjunto soportado | Advertencia | Queda en el reporte; nunca se afirma conservación total |
| ID duplicado | Dos elementos comparten identificador | Error | Bloquea la importación o pide revisión |

### E.4 Evidencia cruda

- `tools/ai-xmi-roundtrip/REPORT.md` (informe completo, con la sección 8 de correcciones y re-medición).
- `tools/xmi-roundtrip/REPORT.md` (ronda mínima EA → aplicación → XMI → EA).
- `tools/ai-xmi-roundtrip/evidence/20260922T040934Z-A-fixed3/`, `…T040958Z-B-fixed3/` y
  `…T041019Z-C-fixed3/` (las tres corridas corregidas, con el XMI y las comparaciones).

---

## 5.6 Anexo F – Evidencias de Implementación y Pruebas

### F.1 Suites automatizadas

| Suite | Herramienta | Ubicación | Resultado medido |
| --- | --- | --- | --- |
| Backend | JUnit 5 + Spring Boot Test + Testcontainers | `backend/src/test/java/com/diagrammer/**` | 535 pruebas, 0 fallos, 0 errores, 0 omitidas |
| Editor web | Vitest 4.1.11 | `web/src/test/**` | 55 archivos, 574 pruebas, todas correctas |
| Cliente móvil | `flutter_test` | `mobile/test/**` | 216 pruebas correctas, 1 omitida |

### F.2 Colaboración medida desde el navegador

| Corrida | Resultado | Evidencia |
| --- | --- | --- |
| Flujo de extremo a extremo del editor | **8 / 8 pasos** | `tools/web-e2e/REPORT.md` |
| Propagación de presencia | **5 / 5 pasos** | `tools/web-e2e/PRESENCE-PROPAGATION-REPORT.md` |
| Propagación de geometría (corregida) | **5 / 5 pasos** | `tools/web-e2e/GEOMETRY-PROPAGATION-REPORT.md` |
| Propagación de geometría (primera medición) | 3 / 5 pasos — **el arrastre no movía el elemento** | `tools/web-e2e/GEOMETRY-REPORT.md` |
| Latencia de adopción de un comando | 154–285 ms de aceptación; 106–326 ms de adopción | `tools/web-e2e/LATENCY-REPORT.json` |

La corrida que falló se conserva a propósito: documenta el defecto que se encontró y que la corrida
posterior verificó corregido.

### F.3 Generación determinista

| Evidencia | Resultado |
| --- | --- |
| Run 1 | `SUCCEEDED` — `artifactZipHash` `7b239f01…ff452` |
| Run 2 | `SUCCEEDED` — `artifactZipHash` `7b239f01…ff452` |
| ZIP 1 vs ZIP 2 | `sha256` **idéntico** |
| Backend generado | Puerto `8094`, `health` `200` con `{"status":"UP"}` |
| Base de datos propia | PostgreSQL 16.14, contenedor `wu-a3-lighthouse-db`, base `e2e_lighthouse` |

![Salud del backend generado](capturas/uso/13-health-backend-generado.png)

### F.4 Capturas de uso

Las 29 capturas de la sección 3 están en `docs/capturas/uso/`, y su trazabilidad de toma en
`capturas-report.json` y `capturas-report-2.json`.

---

## 5.7 Anexo G – Evidencias de Despliegue, Docker, AWS, Floci e IA Local

### G.1 Contenedores en ejecución durante la verificación

| Contenedor | Función | Puerto publicado |
| --- | --- | --- |
| `backend-main-integration-backend-1` | Plataforma (Spring Boot) | `8081` → 8080 |
| `backend-main-integration-db-1` | PostgreSQL de la plataforma | `15432` → 5432 |
| `backend-main-integration-floci-1` | **Floci**, emulador local de AWS | `4566` |
| `e2e-a-fixed-backend-1` | Backend generado del dominio A | `8094` |
| `e2e-b-backend-1` / `e2e-c-backend-1` | Backends generados de los dominios B y C | `8095` / `8096` |
| `generated-1to1-v2` / `generated-peluqueria` | Backends generados de otros dominios | `8092` / `8093` |
| `photo-loop` | Sidecar de extracción fotográfica | interno |

### G.2 Frontera de despliegue

- **Floci** es un emulador local de AWS, gratuito y sin cuenta: se apunta el SDK o la CLI a
  `http://localhost:4566` y no se crean recursos facturables.
- El **objetivo real** es AWS con el backend en contenedor y PostgreSQL gestionado. Su preparación
  está declarada, pero **el despliegue sobre una cuenta real no se ha demostrado todavía** y no debe
  presentarse como cerrado.
- El endpoint de S3 queda **vacío por defecto** para que un despliegue real resuelva el endpoint
  estándar del servicio; el emulador se indica de forma explícita.

### G.3 Los dos runtimes de inteligencia artificial

| Runtime | Dónde corre | Modelo | Estado |
| --- | --- | --- | --- |
| Backend | Contenedor con API compatible con OpenAI | Qwen 3.5 0.8B (GGUF Q4_0) | Desactivado por defecto (`AI_TEXT_PROPOSALS_ENABLED=false`); en la plataforma de verificación estuvo activo y produjo la propuesta de la sección 3 |
| Cliente | En el dispositivo, con lib_llama_cpp 0.7.3 | Qwen 3.5 0.8B | Medido con la red cortada: 2 de 3 frases forzadas resueltas (29 y 28 tokens) |
| Voz | ASR local con faster-whisper (CPU, int8) | `Systran/faster-whisper-base` | Solo `es-ES`, audio máximo 25 MiB |

![Modelo local en el dispositivo](capturas/uso/15-modelo-en-dispositivo.png)

### G.4 Variables de configuración del despliegue

| Variable | Valor por defecto | Función |
| --- | --- | --- |
| `GENERATION_ARTIFACTS_STORE` | `postgres` | Almacén de los ZIP: `postgres` o `s3` |
| `GENERATION_ARTIFACTS_S3_BUCKET` | `generation-artifacts` | Bucket de destino |
| `GENERATION_ARTIFACTS_S3_REGION` | `us-east-1` | Región |
| `GENERATION_ARTIFACTS_S3_ENDPOINT` | *(vacío)* | Vacío a propósito para un despliegue real |
| `SEED_DEMO_USERS` | `true` | Debe ser `false` en un entorno real |
| `AI_QWEN_ENDPOINT` | `http://127.0.0.1:12434/engines` | Endpoint del runtime local |
| `AI_QWEN_MODEL` | `huggingface.co/ggml-org/qwen3.5-0.8b-gguf:Q4_0` | Identidad del modelo |
| `AI_QWEN_READ_TIMEOUT_MS` | `120000` | Tiempo de lectura validado para una respuesta real |
| `QWEN_MODEL_DIR` | `D:/AI/qwen` | Volumen del host con los pesos |

---

## 5.8 Anexo H – Repositorios, QR y Documentación Complementaria

### H.1 Repositorios

| Repositorio | URL | Contenido |
| --- | --- | --- |
| Principal | https://github.com/Fivoryu/sw1_p1_2026_1 | Documentación PUDS, modelo de EA, fixtures, herramientas y runbooks |
| Backend | https://github.com/Fivoryu/sw1_p1_2026_1-backend | Servicio Spring Boot |
| Editor web | https://github.com/Fivoryu/sw1_p1_2026_1-web | Aplicación React + TypeScript |
| Cliente móvil | https://github.com/Fivoryu/sw1_p1_2026_1-mobile | Aplicación Flutter |

### H.2 Códigos QR

| Principal | Backend |
| --- | --- |
| ![QR principal](capturas/anexos/qr-repo-principal.png) | ![QR backend](capturas/anexos/qr-repo-backend.png) |

| Editor web | Cliente móvil |
| --- | --- |
| ![QR web](capturas/anexos/qr-repo-web.png) | ![QR móvil](capturas/anexos/qr-repo-mobile.png) |

### H.3 Documentación complementaria

| Documento | Ruta | Contenido |
| --- | --- | --- |
| Referencia técnica | `SOFTWARE.md` | Estado por área, mapa del repositorio y evidencia medida |
| Captura de requisitos | `docs/puds/01-captura-de-requisitos.md` | Actores, RF-01 a RF-16, RNF-01 a RNF-11 y las 23 fichas de caso de uso |
| Análisis | `docs/puds/02-analisis.md` | Invariantes, paquetes P01–P09 y mensajes conceptuales |
| Diseño | `docs/puds/03-diseno.md` | Cortes D0–D6, artefactos y condiciones de cierre |
| Contratos | `docs/puds/contratos/01-contratos-transversales.md` | Comandos, API, eventos, conflictos, XMI, IA y generación |
| Comparación de colaboración | `docs/puds/contratos/02-comparacion-colaboracion.md` | Registro de comandos + merge por campos frente a CRDT |
| Base de datos | `docs/puds/datos/01-mapeo-base-de-datos.md` y `02-esquema-fisico.sql` | Mapeo relacional y DDL del esquema `diagrammer` |
| Inicialización de la base | `docs/inicializacion-base-de-datos.sql` | Consolidado de las 19 migraciones Flyway |
| Diseño del generador | `docs/puds/generacion/01-diseno-generador.md` | Contrato de fuente, target, snapshot y verificación |
| Plan de verificación | `docs/puds/verificacion/01-verificacion.md` | Matriz de verificación y esquemas JSON |
| Runbook de la demo | `odd/demo-runbook.md` | Recorrido integral y su veredicto medido |

---

## 5.9 Anexo I – Matriz de Trazabilidad Completa

### I.1 Requisitos funcionales

| RF | Casos de uso | Paquete principal | Artefacto de diseño | Prueba o evidencia |
| --- | --- | --- | --- | --- |
| RF-01 | CU-01, CU-02, CU-23 | P01 | Clases `Project`, `ProjectMember`, `ModelVersion`; secuencia 01 | `CreateProjectServiceTest`, `ProjectRecoveryServiceTest` (19) |
| RF-02 | CU-04, CU-05, CU-06, CU-08, CU-16, CU-23 | P02 | Clases `Classifier`, `Attribute`, `Operation`, `Relationship`; secuencia 01 | `classifier.test.ts`, `reducer.test.ts`, `Cu16XmiParserTest` |
| RF-03 | CU-04, CU-05, CU-06, CU-07, CU-23 | P02, P03 | Clases `ElementGeometry`, `ConnectorPath` | `classifier.test.ts`, `placement.test.ts` |
| RF-04 | CU-03, CU-13, CU-14, CU-15, CU-23 | P01, P04 | Clases `ProjectMember`, `Conflict`, `Tombstone`; secuencia 03 | `Cu14SynchronizationB3Test` (40), propagación de presencia 5/5 |
| RF-05 | CU-09, CU-10, CU-23 | P05 | Clases `AIProposal`, `ModelCommand`; secuencia 02 | `Cu09ProposalValidatorTest` (13), `Cu09ProposalSanitizerTest` (11) |
| RF-06 | CU-04 a CU-17, CU-23 | P02 | `UMLModel` como única representación semántica | Suite de dominio del editor (14 archivos) y suite de sincronización |
| RF-07 | CU-11, CU-23 | P05 | Secuencia 02; ASR local | `Cu11VoiceProposalTest` (4) |
| RF-08 | CU-12, CU-23 | P05 | Clase `DetectedElement` | `Cu12PhotoProposalTest` (9), `Cu12PhotoImageNormalizerTest` (10) |
| RF-09 | CU-16, CU-17, CU-23 | P06 | Clases `XMIExchange`, `XMILossItem`; secuencia 05 | `Cu17XmiExporterTest` (11), `Cu16XmiParserTest` (8), ida y vuelta 24/24 y 7/7 |
| RF-10 | CU-19, CU-20, CU-23 | P07 | Clases `GenerationRun`, `GeneratedArtifact`; secuencia 06 | `GenerationServiceTest` (16), `GeneratedProjectCompilerTest` (8) |
| RF-11 | CU-18, CU-19, CU-20, CU-23 | P07 | Clases `GenerationProfile`, `TemplateVersion` | `DeterministicArtifactWriterTest` (6), `GeneratedProjectRendererTest` (20) |
| RF-12 | CU-01, CU-04, CU-08, CU-16, CU-18 a CU-21, CU-23 | P07, P09 | Secuencia 06; runbook de la demo | Prueba integral del dominio A: 9 de 9 criterios |
| RF-13 | CU-21, CU-23 | P09 | Clase `DeploymentInfo` | `widget_test.dart`, recorrido del dominio A con CRUD verificado en la base |
| RF-14 | CU-22, CU-23 | P09 | Clase `OperacionPendiente` | `local_llama_intent_fallback_test.dart` |
| RF-15 | CU-22, CU-23 | P09 | Runtime local en el dispositivo | `offline_write_queue_test.dart`, medición con la red cortada (2 de 3 frases) |
| RF-16 | CU-04 a CU-07, CU-09, CU-10, CU-14 a CU-17, CU-23 | P02, P03 | Barra de herramientas y atajos documentados | `classifier.test.ts`, diálogo `Keyboard shortcuts` |

### I.2 Requisitos no funcionales

| RNF | Atributo | Paquete | Cómo se verifica | Evidencia |
| --- | --- | --- | --- | --- |
| RNF-01 | Consistencia | P02, P03 | El validador rechaza alternativas incompatibles y estas no entran al canon | `classifier.test.ts`, `reducer.test.ts` |
| RNF-02 | Trazabilidad y auditoría | P03 | Cada operación conserva autor, objetivos, revisión base y resultado | `ProjectRecoveryServiceTest`, tabla `audit_log` |
| RNF-03 | Determinismo | P07 | Dos corridas con las mismas entradas producen el mismo ZIP | `artifactZipHash` idéntico en `tools/generation-e2e/REPORT.md` |
| RNF-04 | Interoperabilidad | P06 | Ida y vuelta por EA con pérdidas declaradas | 24/24 atributos con tipo y 7/7 asociaciones |
| RNF-05 | Seguridad | P01 | La autorización se decide en el servidor en cada operación | `PermissionServiceTest` (5), `ProjectPermissionControllerTest` (3) |
| RNF-06 | Validación de entradas | P02, P05 | Se valida antes de modificar el modelo canónico | `Cu09ProposalValidatorTest`, `classifier.test.ts` |
| RNF-07 | Independencia del cliente | P08 | El contrato no depende de React, TypeScript ni Flutter | `Cu09ContractFixtureTest` (3), contrato OpenAPI del backend generado |
| RNF-08 | Disponibilidad local | P09 | La interacción de IA funciona sin servicio remoto | `local_llama_intent_fallback_test.dart`, `offline_write_queue_test.dart` |
| RNF-09 | Demostrabilidad | P09 | El recorrido integral se ejecuta en el tiempo previsto | Prueba integral del dominio A, `odd/demo-runbook.md` |
| RNF-10 | Despliegue reproducible | P07 | El backend generado levanta con su propia base y responde salud | `health` `200` en el puerto `8094` |
| RNF-11 | Colaboración medible | P04 | Se miden latencia, presencia y propagación de geometría | `LATENCY-REPORT.json`, presencia 5/5 y geometría 5/5 |

### I.3 Cobertura

- **RF-01 a RF-16**: los 16 requisitos funcionales tienen al menos un caso de uso asignado y al menos
  una prueba o evidencia que los verifica.
- **RNF-01 a RNF-11**: los 11 requisitos de calidad tienen una forma de verificación declarada.
- **CU-01 a CU-23**: los 23 casos de uso están cubiertos por la matriz y tienen su ficha en la captura.

### I.4 Pendientes que la matriz no puede declarar cerrados

| Pendiente | Estado |
| --- | --- |
| Diagramas C4 (contexto, contenedores, componentes) | Los elabora el equipo |
| Despliegue sobre una cuenta real de AWS | No demostrado; solo el contrato de configuración y el emulador local |
| Despliegue continuo | No implementado; solo integración continua en el editor web |
| Propuesta fotográfica completa | El proveedor es opt-in y está desactivado por defecto |
| QA de calidad del modelo de IA | Las pruebas cubren contrato y validación, no la exactitud de la respuesta |
| Prueba de carga y concurrencia (RNF-11 con dataset) | Definida en el plan de verificación, no ejecutada |
| Dominios B y C de la prueba integral | Solo llegaron a la fase de ida y vuelta por EA |
| Q9 (eficiencia integral, secuencia e interacción del asistente) | Abierta; no se presenta como cerrada |
