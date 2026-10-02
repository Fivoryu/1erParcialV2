# Arnés de aceptación del intercambio XMI

Ejecutalo desde la raíz del repositorio:

```sh
python tools/xmi-acceptance/xmi_acceptance.py
```

El arnés requiere **los dos archivos**: el control positivo escrito por Enterprise Architect,
`docs/fixtures/xmi/Prueba3.xml`, y la muestra escrita por `XmiSemanticEmitterTest`,
`backend/target/xmi-sample.xml`. Si falta cualquiera, informa cuál y termina distinto de cero; nunca convierte
una entrada ausente en un check omitido.

También podés indicar ambos explícitamente:

```sh
python tools/xmi-acceptance/xmi_acceptance.py \
  --ea-fixture docs/fixtures/xmi/Prueba3.xml \
  --emitted-sample backend/target/xmi-sample.xml
```

## Qué comprueba

Para **ambos** documentos ejecuta los mismos checks, usando `tools/xmi-inventory/xmi_inventory.py` como único
parser e inventario del dialecto. No copia sus vocabularios de namespaces/tags ni su clasificador. Reutiliza sus
mediciones de tags declarados, ubicación de `connectors`, estructura de relaciones, clasificación geométrica de
figuras y conectores, resolución de `subject` y anclas `DUID`.

Cada identidad incluye bytes y SHA-256 calculados por el inventario sobre el documento parseado. El arnés fija
el control EA al SHA-256 medido en el registro (§3.1) y exige que el hash coincida con lecturas antes y después
del inventario, para detectar cambios durante la corrida. Para la emisión, registra el SHA-256 exacto de la muestra
actual sin suponer que coincide con una corrida histórica. La salida **What was measured** presenta los hashes,
los recuentos observados, el comando que los produjo
y el resultado de cada check. El fixture de EA es obligatorio: si no pasa, se informa el hallazgo y no se relajan
las aserciones para acomodar la emisión.

La salida **What was NOT measured** conserva los límites registrados en `odd/tasks/xmi-intercambio.md`: EA todavía
no crea un diagrama a partir de la emisión; no se probó si rechaza `value="*"`; y no se conoce el significado de la
gramática `Path`. También aclara que este arnés no ejecuta EA ni mide una importación en vivo.

El historial de §7.2 registra dos importaciones del archivo emitido con `+8` elementos y `+0` diagramas, pero lo
identifica como una muestra de 19.196 bytes y no guarda su SHA-256. Por eso el arnés informa ese resultado como
medición histórica, sin atribuírselo a la identidad pinneada del archivo actual.

## Límites y entorno

- Solo lee los dos archivos y llama al inventario local. No arranca la aplicación ni necesita Docker, base de datos,
  red, servidor, EA, ni dependencias Python adicionales.
- No es una prueba de interoperabilidad en vivo. La observación de EA sobre semántica y diagramas es histórica y
  está separada de los checks estructurales del arnés.
- Si la muestra emitida todavía no existe, regenerala con la prueba indicada por la tarea:

  ```sh
  mvn -o -B -f backend/pom.xml -Dtest=XmiSemanticEmitterTest test
  ```

- La suite de herramientas se ejecuta desde la raíz con:

  ```sh
  python -m pytest tools -q
  ```
