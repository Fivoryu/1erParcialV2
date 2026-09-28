# Arnes de pruebas de contrato

Verifica que los contratos declarados en `docs/puds/contratos/` sean **artefactos
verificables** y no promesas en prosa.

## Como ejecutarlo

Desde la raiz del repositorio:

```bash
python -m pytest tools/contract-tests -q
```

Dependencias: `pytest` y `jsonschema` (version 4.26 o superior, por validacion de
JSON Schema draft 2020-12). No se instala nada dentro del repositorio.

## Resumen derivado del inventario

```bash
python tools/contract-tests/baseline_summary.py
```

Imprime conteos de constructos, cobertura de clausulas obligatorias y celdas de
mapeo `NONE`. Las cifras de `docs/puds/contratos/03-linea-base-uml.md` salen de
aqui, no se escriben a mano: un documento cuyos numeros se mantienen a mano
termina mintiendo en silencio.

> En consola de Windows los acentos pueden verse mal porque la consola no usa
> UTF-8 por defecto. Es un artefacto de la consola, no del archivo. Con
> `chcp 65001` se ve bien.

## Que cubre hoy

| Suite | Artifact bajo prueba | Que garantiza |
| --- | --- | --- |
| `test_uml_baseline.py` | `docs/puds/contratos/03-linea-base-uml.v1.json` contra su esquema `03-linea-base-uml.schema.json` | Que la linea base UML declare un mapeo por cada destino, que no haya constructos no soportados sin motivo, que ninguna clausula obligatoria del analisis quede sin cubrir y que el artefacto no se declare ratificado por si solo. |
## Por que el oraculo es independiente

`test_uml_baseline.py` transcribe a mano la lista de clausulas obligatorias de
`docs/analisis-enunciado-parcial-1.md` seccion 6. **No** la lee del inventario.

Es deliberado: una prueba que toma sus expectativas del archivo que valida pasa
siempre y no sirve para nada. El oraculo independiente es lo unico que hace
fallar la suite cuando alguien elimina una clausula obligatoria del inventario.

## Límite conocido de la suite

La suite prueba que **toda cláusula obligatoria del análisis esté cubierta** y que no se inventen
cláusulas. No prueba **completitud de la notación**: un elemento UML que no figure en la lista
obligatoria del análisis podría faltar del inventario sin que ninguna prueba falle.

Es un límite inherente a tener un oráculo, y por eso el inventario declara además sus exclusiones
explícitas: nombrar lo que queda fuera es lo único que evita que la ausencia se lea como olvido.

## Convenciones

- Los contratos y sus pruebas viven en el repositorio; no hay dependencias
  descargadas ni estado oculto.
- Ninguna prueba escribe archivos: son de solo lectura sobre los artefactos.
