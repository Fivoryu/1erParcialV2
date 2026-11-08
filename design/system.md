# Sistema visual observado

Instantánea parcial de los valores declarados, no un inventario exhaustivo de cada color local. Web y Flutter comparten ahora una paleta morado-rosa, pero conservan temas y componentes propios. Las maquetas son HTML y no reproducen necesariamente el renderizado Material de Flutter.

Fuentes sincronizadas: `web/src/styles.css`, `mobile/lib/presentation/app_theme.dart`, `mobile/lib/presentation/screens/login_screen.dart` y `mobile/lib/presentation/screens/workspace_screen.dart`. Los valores de los esquemas Material generados que no se fijan explícitamente no se inventan aquí.

## Colores

### Web: 13 propiedades de `:root`

Fuente de cada entrada: `web/src/styles.css`.

| Token | Valor | Uso observado |
|---|---|---|
| `--ink` | `#2d2032` | Texto principal y títulos |
| `--muted` | `#6d6070` | Texto secundario |
| `--soft-muted` | `#948697` | Texto menos prominente; comprobar contraste antes de usarlo |
| `--line` | `#e8dce8` | Bordes y separadores |
| `--paper` | `#ffffff` | Superficies |
| `--canvas` | `#f9f5f9` | Fondo de página |
| `--accent` | `#8b3c86` | Énfasis y foco |
| `--accent-dark` | `#68286f` | Acción primaria y énfasis oscuro |
| `--accent-wash` | `#f6e8f4` | Fondo violeta suave |
| `--danger` | `#9b3e37` | Texto y señal de error |
| `--danger-wash` | `#fbefed` | Fondo de error |
| `--rose` | `#a73d74` | Énfasis rosa |
| `--rose-wash` | `#fae9f1` | Fondo rosa suave |

### Web: vista previa de cambios

Fuente de cada entrada: `web/src/styles.css`, propiedades locales de `.proposal-preview`. Son colores de una vista previa existente; no demuestran que la revisión móvil ya esté implementada. Los estados se acompañan de texto, no se distinguen solo por color.

| Token | Valor | Uso observado |
|---|---|---|
| `--preview-added` | `#1a7f37` | Cambio agregado |
| `--preview-added-wash` | `#dafbe1` | Fondo de cambio agregado |
| `--preview-removed` | `#cf222e` | Cambio eliminado |
| `--preview-removed-wash` | `#ffebe9` | Fondo de cambio eliminado |
| `--preview-modified` | `#9a6700` | Cambio modificado |
| `--preview-modified-wash` | `#fff8c5` | Fondo de cambio modificado |

### Flutter

Fuente: `mobile/lib/presentation/app_theme.dart`. `AppPalette` fija 11 colores: `ink #2d2032`, `muted #6d6070`, `line #e8dce8`, `paper #ffffff`, `canvas #f9f5f9`, `accent #8b3c86`, `dark #68286f`, `wash #f6e8f4`, `rose #a73d74`, `roseWash #fae9f1` y `danger #9b3e37`. El tema claro fija varias funciones del esquema a esos colores; el oscuro se genera desde `#d7a6d2`. No se registran los demás valores generados por Material.

## Tipografía

Fuente: `web/src/styles.css`. Cuerpo: `Inter, 'Segoe UI', sans-serif`, peso base 400; marca y títulos: `Manrope, 'Segoe UI', sans-serif`. Código: `'Cascadia Mono', Consolas, monospace`; Markdown: `ui-monospace, SFMono-Regular, Menlo, Consolas, monospace`. Las familias declaradas no garantizan que las fuentes estén instaladas. Para maquetas autónomas se usa la alternativa de sistema `'Segoe UI', sans-serif`, sin descargas.

| Estilo web | Tamaño | Peso / interlineado | Fuente |
|---|---|---|---|
| h1 | `clamp(32px, 4vw, 43px)` | 600 / 1.12 | `web/src/styles.css` |
| h2 | 18px | 600 / 1.35 | `web/src/styles.css` |
| h3 | 14px | 600 / 1.4 | `web/src/styles.css` |
| lede | 14px | heredado / 1.65 | `web/src/styles.css` |
| etiqueta de campo | 11px | 600 | `web/src/styles.css` |

El h1 del editor tiene una regla posterior de `clamp(24px, 3vw, 32px)`. Flutter utiliza roles Material sin una familia o escala numérica propia fijada en las fuentes revisadas: `mobile/lib/presentation/app_theme.dart`, `mobile/lib/presentation/screens/login_screen.dart` y `mobile/lib/presentation/screens/workspace_screen.dart`.

## Espaciado

No se observó una escala global nombrada; son valores representativos, no una escala normativa.

| Valor | Uso | Fuente |
|---|---|---|
| 8px | Separación de formularios de propiedades | `web/src/styles.css` |
| 16px | Padding horizontal de botón y separación de secciones | `web/src/styles.css` |
| 22px | Separación de columnas de proyectos | `web/src/styles.css` |
| 26px | Padding de paneles de proyectos | `web/src/styles.css` |
| 24dp | Padding de inicio de sesión | `mobile/lib/presentation/screens/login_screen.dart` |
| 20dp | Padding de lista del espacio de trabajo | `mobile/lib/presentation/screens/workspace_screen.dart` |

## Radios

| Valor | Uso | Fuente |
|---|---|---|
| 14px / dp | Superficies web y tarjetas Flutter | `web/src/styles.css`, `mobile/lib/presentation/app_theme.dart` |
| 9px / dp | Botones web y Flutter | `web/src/styles.css`, `mobile/lib/presentation/app_theme.dart` |
| 8px / dp | Campos web y Flutter | `web/src/styles.css`, `mobile/lib/presentation/app_theme.dart` |
| 999px | Chips web | `web/src/styles.css` |

## Sombras

| Valor | Uso | Fuente |
|---|---|---|
| `0 1px 3px rgb(45 32 50 / 5%)` | `--shadow-soft`, superficies web | `web/src/styles.css` |
| `0 3px 10px rgb(45 32 50 / 8%)` | Tarjetas UML web | `web/src/styles.css` |
| `0 12px 32px rgb(45 32 50 / 18%)` | Menú contextual web | `web/src/styles.css` |
| Elevación `0` | Tarjetas Flutter | `mobile/lib/presentation/app_theme.dart` |

## Patrones de componentes

- **Web / superficie:** fondo `--paper`, borde `--line`, radio `--radius` y sombra suave. Botón primario morado oscuro, secundario blanco, altura mínima 42px y foco visible. Fuente: `web/src/styles.css`.
- **Web / editor:** lienzo con tarjetas UML, barra de herramientas, panel de propiedades y menú contextual. Fuente: `web/src/styles.css`.
- **Flutter / tema claro:** barra blanca, tarjetas blancas de radio 14 y borde, campos de radio 8 y botones llenos de radio 9 y altura mínima 48dp. Fuente: `mobile/lib/presentation/app_theme.dart`.
- **Flutter / acceso:** `SafeArea`, contenido centrado de máximo 440dp y campos etiquetados. Fuente: `mobile/lib/presentation/screens/login_screen.dart`.
- **Flutter / espacio de trabajo:** `AppBar`, lista de máximo 680dp y botones Material. Fuente: `mobile/lib/presentation/screens/workspace_screen.dart`.

## Antecedente de la propuesta morado-rosa

La paleta morado-rosa se registró anteriormente como propuesta solo para las maquetas `design/diagramador-web-movil/01-web-taller.html` y `design/diagramador-web-movil/02-movil-taller.html`. En las fuentes actuales revisadas, sus valores principales también están declarados en web y Flutter. Esta observación no implica que se haya elegido ninguna de esas maquetas para implementación.

## Límites

El tablero HTML usa fuentes de sistema y ejemplos aproximados; no es una renderización Material. Los valores de esquema generados, el contraste de cada combinación y la visualización en dispositivos requieren comprobación independiente. La captura describe solo los valores observados en las fuentes indicadas.
