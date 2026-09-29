# Arnés de aceptación del corte visible

Ejecutá el arnés desde la raíz del repositorio:

```sh
python tools/acceptance/acceptance.py
```

Requiere Python 3, Java 21, Docker y la imagen `postgres:16` ya disponible localmente. El arnés inspecciona esa imagen y usa `--pull=never`, por lo que no descarga nada. También requiere el JAR ejecutable del backend en `backend/target`; si todavía no está construido, generálo en modo offline con:

```sh
cd backend && mvn -o -B package
```

El recorrido crea un proyecto, dos clasificadores, un atributo, una operación y una asociación por HTTP; guarda los rectángulos de ambos clasificadores y los waypoints de la relación; luego vuelve a leer el modelo. Compara los campos enviados con el modelo persistido —incluidos tipos, visibilidad, abstractitud, extremos y multiplicidades, y geometrías—, informa los conteos antes y después de la relectura y calcula el SHA-256 del JSON recuperado. El conteo de elementos incluye clasificadores, atributos, operaciones, relaciones y extremos; el de geometría suma rectángulos y waypoints. No usa el frontend.

Por defecto, al terminar (también ante un error o Ctrl-C) detiene el backend y elimina únicamente el contenedor creado por esa ejecución. Verifica ambos resultados y que el conteo total de contenedores vuelva al valor inicial.

Para dejar backend y base disponibles mientras se desarrolla el editor:

```sh
python tools/acceptance/acceptance.py --keep-running
```

El arnés imprime la URL base y una instrucción `--stop <RUN_ID>`. Ejecutá esa instrucción cuando quieras detener los servicios. Para el backend, el arnés busca y detiene todos los procesos cuya línea de comandos contiene el marcador único `-Dacceptance.runId=<RUN_ID>`; el PID guardado en el manifiesto queda como respaldo si la consulta de procesos de la plataforma no está disponible. La verificación prioriza que no quede ningún proceso con ese marcador. Para PostgreSQL conserva el identificador del contenedor y elimina únicamente el contenedor etiquetado para esa ejecución.
