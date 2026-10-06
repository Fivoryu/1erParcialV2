# Compartimentos del diagrama en Enterprise Architect (atributos invisibles al importar)

**Estado:** fix autorizado por el usuario el 2026-10-05. Unidad abierta sobre `feat/vista-compacta-editor`.
**Origen:** reporte del usuario con captura (`pi-clipboard-68d348f4-0d42-4406-91a7-17f4bcb3633f.png`): «Al exportar de la app y luego importar a EA, el diagrama se dibuja bien, pero no se ven los atributos».
**Relación:** reversión medida de un supuesto de `odd/tasks/xmi-intercambio.md` §3.4/§10 — el `<style1>` del diagrama **no** es metadata de proveedor que se pueda omitir: es el canal del que EA deriva la visibilidad de compartimentos.

## 0. Diagnóstico medido antes de tocar código

Pipeline: EA Trial de 32 bits por COM desde `SysWOW64\WindowsPowerShell\v1.0\powershell.exe`, sobre **copias** de `EABase.eapx` (la instancia del usuario, `sw1_p2`, nunca se tocó: se identifica por `MainWindowTitle`).

| # | Medición | Resultado |
| --- | --- | --- |
| M1 | Importar `backend/target/xmi-sample.xml` y leer el modelo por la API (`Element.Attributes`) | La semántica **llega**: `Feature` con 2 atributos (`label : String [1..1]`, `nickname : String [0..1]`), `Status` con `ACTIVE` |
| M2 | Control positivo: importar el export propio de EA (`docs/fixtures/xmi/Prueba3.xml`) y renderizar con el mismo pipeline | **Sí muestra atributos**, incluidos los privados (`- correo: String`): el render no es el culpable |
| M3 | Renderizar nuestro archivo (`PutDiagramImageToFile`, 3 argumentos: guid, png, ancho) | Reproduce el síntoma: figuras en su geometría, **cero features** |
| M4 | Columnas de `t_diagram` (Jet/OLEDB) | Nuestro import: `AttPub=False, AttPri=False, AttPro=False`. Export propio de EA: `True/True/True` |
| M5 | Setear a mano `AttPub/AttPri/AttPro=True` **sin reimportar** y volver a renderizar | Aparecen atributos **y** operación ⇒ causa directa, no correlación |
| M6 | Escribir `StyleEx`/`ExtendedStyle` después del import | No cambia nada: EA consume `style1` **en el import** |
| M7 | Importar nuestro archivo con `<style1 value="ShowPrivate=1;ShowProtected=1;ShowPublic=1;OpParams=1;"/>` | `AttPub/AttPri/AttPro=True/True/True` y el render muestra `- label: String = guest`, `~ nickname: String [0..1]` y `# transform(String)` |
| M8 | Igual que M7 sin `OpParams=1` | Los parámetros de operación desaparecen (`# transform()`) |

**Causa raíz:** `XmiSemanticEmitter.appendExtensionDiagram` emite `<style1/>` vacío (y lo declara como `loss("extension_diagram_style1_metadata")`). Sin él EA crea el diagrama con la visibilidad de features apagada y no dibuja **ninguna** feature, ni atributos ni operaciones. El fixture de EA escribe ese bloque con 50 claves; el subconjunto causal es el de M7.

## 1. Decisión del fix

| Decisión | Valor | Por qué |
| --- | --- | --- |
| XCE-01 Valor emitido | `<style1 value="ShowPrivate=1;ShowProtected=1;ShowPublic=1;OpParams=1;"/>` | Es el subconjunto **medido** suficiente (M5/M7/M8) sobre el canal `AttPub/AttPri/AttPro` |
| XCE-02 No copiar el bloque completo | No se emiten las 50 claves del fixture | Traería preferencias ajenas con efecto real (`DocSize.cx=826;DocSize.cy=1169`, `PaperSize=9`, `Zoom=100`): la app no modela preferencias de página |
| XCE-03 El loss de `style1` se retira | Sí | Ya no hay nada no representado que declarar; el valor pasa a ser medido, no inventado |
| XCE-04 `style2`, `swimlanes`, `matrixitems` siguen vacíos | Sí | Sin medición que los requiera: vacíos no impiden el render (M7) |

## 2. Plan de trabajo

- [x] **T1 (RED)** `XmiSemanticEmitterTest`: pinnear que `style1` lleva el valor medido (el bucle de «sin inventar» pasa a cubrir solo `style2`, `swimlanes`, `matrixitems`) y sacar `extension_diagram_style1_metadata` de la lista esperada. **Rojo observado**: `AssertionFailedError: [style1 carries the measured EA feature visibility keys] expected: "ShowPrivate=1;ShowProtected=1;ShowPublic=1;OpParams=1;" but was: ""` — `Tests run: 8, Failures: 1, Errors: 0`.
- [x] **T2 (GREEN)** `XmiSemanticEmitter`: constante `EA_DIAGRAM_FEATURE_VISIBILITY` (`:62-70`) con el comentario que cita M1–M8, emisión del bloque en `:713-720` y retiro del loss. **Verde observado**: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`, y `backend/target/xmi-sample.xml` regenerado con el valor (verificado por `grep`).
- [x] **T3 (verificación)** Verificador independiente: `mvn -o -B -f backend/pom.xml verify` = **427 pruebas, 0 fallos, 0 errores, 0 salteadas, BUILD SUCCESS** (con el Maven de Windows: WSL no tiene JDK); `python -m pytest tools -q` = **54 pasan**; arnés de aceptación XMI = **18 pasan, 0 fallan**, con sha256 de la emisión `c40a6927c052020047e58fc0be6cb8738d7ac87a9c3cec43e3fdaca47db1f214`; y barrido de expectativas viejas sin ocurrencias de `extension_diagram_style1_metadata`.
- [x] **T4 (verificación en vivo)** Sample regenerado reimportado en un EA limpio con la sonda de la skill (`sample-after.xml`): `t_diagram` pasa de `AttPub=False, AttPri=False, AttPro=False` a **`True/True/True`**, y el render muestra `- label: String = guest`, `~ nickname: String [0..1]` y `# transform(String)`. Antes: `…\ea-probe\before\diagram-20261005-160934-Default.png` (`Feature` vacío, el síntoma del usuario). Después: `…\ea-probe\after\diagram-20261005-161828-Default.png`.
- [x] **T5 (docs)** M1–M8, la reversión y las decisiones de XCE-01..04 quedaron en `odd/tasks/xmi-intercambio.md` §16, con la tabla de compuertas ya asentada con los números de T3 y T4.
- [x] **T6 (skill)** `C:\Users\HP\.pi\agent\skills\enterprise-architect-headless\` con `SKILL.md`, `assets/probe.ps1` y `references/mediciones.md`; la sonda se probó end-to-end y se le corrigieron dos defectos propios (`Set-StrictMode` contra las superficies variables de COM, y el conteo de colecciones que EA a veces no responde).

## 3. Fuera de alcance, y por qué

| Ítem | Motivo |
| --- | --- |
| `{bag}` junto a cada atributo | Hallazgo aparte: nuestros `<ownedAttribute>` omiten `isStatic/isReadOnly/isDerived/isOrdered/isUnique/isDerivedUnion` que EA sí escribe (`isUnique="true"` en el fixture). EA lee la ausencia como no-único. Requiere su propia medición |
| `package_name`/`ea_localid`/`style` del registro de extensión por elemento | Medido irrelevante para M4–M7 (los elementos y sus features se dibujan sin ellos) |
| `xmlns="…UML/2.1"` redundante en cada `packagedElement` | Artefacto de serialización; EA lo tolera (M1: la semántica entra completa) |
| `Path=` de conectores | Sigue como en §3.4 de `xmi-intercambio.md`: no medido, y no se inventa |

## 4. Evidencia de la medición (archivos temporales)

`C:\Users\HP\AppData\Local\Temp\ea-probe\`: `probe*.ps1` (pipeline), `z10-baseline.png` (síntoma), `v4-min2.png` (fix mínimo verificado), `control-Prueba___Diagrama_de_Clases_Completo.png` (control positivo de EA), `probe4.eapx`/`probeA.eapx`/`probeC.eapx`/`probeD.eapx` (repos de prueba).

## 5. Cierre

**Commits** en `feat/vista-compacta-editor`: `436efd9` (el fix con su prueba) y `b778527` (estos documentos). El árbol queda limpio salvo `docs/CUENTAS-DE-ACCESO.md`, que ya estaba sin trackear y no es de esta unidad.

**Compuertas medidas:** prueba focalizada rojo → verde (`8/1` → `8/0`); `mvn -o -B -f backend/pom.xml verify` (Maven de Windows: WSL no tiene JDK) **427 pruebas, 0 fallos, 0 errores, 0 salteadas**; `python -m pytest tools -q` **54**; arnés de aceptación XMI **18/0**; importación en vivo con la sonda de la skill con `AttPub/AttPri/AttPro` pasando de `False/False/False` a **`True/True/True`** y el render mostrando las tres features.

**Revisión nativa (RDD encendido, decidida por el humano).** ASSESS devolvió `risk: unassessable` y el plan `writerSelfVerification + independentVerifier`, cumplido por la evidencia del escritor y por las compuertas del verificador. El preflight abrió el linaje **`review-2e33d09fd5542378`**: tier `medium`, una lente (`review-reliability`), 14 paths / 590 líneas, presupuesto de corrección 200, **aprobada al primer intento** y autoridad **quemada** (`gentle-ai.review-acknowledged/v1`, revisión consumida `sha256:a4d04a8b…`).

**El alcance de esa revisión es más ancho que esta unidad, y conviene que se lea así.** El proveedor derivó la proyección del rango de la **rama** (`a293c6d..HEAD`), así que 12 de los 14 paths son el trabajo web previo de la rama; los archivos de esta unidad son 2 de esos 14. Por eso el único hallazgo —**`R3-overlapping-canvas-fits`**, lente de confiabilidad, `WARNING`, disposición `informational`, en `web/src/diagram/UmlCanvas.tsx:361`— **no pertenece a esta unidad**: el propio cierre del proveedor lo declara no bloqueante, sin corrección abierta, y pide tratarlo como trabajo posterior separado, **nunca** como motivo para repetir la revisión de este candidato. Queda anotado acá y no se toca.

**Higiene pendiente.** El cierre difirió la limpieza de la vista de candidato (`candidate-view-cleanup-failed`): quedan directorios en `.git/gentle-ai/candidate-views/`. El proveedor indica reintentar la limpieza o quitarla fuera de banda; **no se tocó**.
