#!/usr/bin/env python3
"""Arnes de extremo a extremo del spike UML a Spring Boot.

Corre la prueba completa y deja la evidencia: emite dos veces y compara huellas,
compila el proyecto generado, levanta PostgreSQL en un contenedor propio, arranca
la aplicacion, comprueba salud, ejercita el CRUD y limpia todo.

Es reproducible: el mismo comando produce la misma evidencia. Nada de lo que
informa esta escrito a mano.

Uso, desde la raiz del repositorio:

    python spikes/uml2spring/run_spike.py

Limites de seguridad que respeta a proposito:

- solo crea y borra su propio contenedor (`uml2spring-spike-pg`);
- nunca ejecuta `prune` ni toca contenedores, imagenes o volumenes ajenos;
- usa el puerto 18080 para la aplicacion porque el 8080 de la maquina esta ocupado,
  y el 55432 para PostgreSQL para no chocar con nada.
"""

from __future__ import annotations

import json
import pathlib
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
MODEL = HERE / "model" / "biblioteca.json"
BUILD = HERE / "build"

CONTAINER = "uml2spring-spike-pg"
PG_IMAGE = "postgres:16-alpine"
PG_PORT = 55432
DB_NAME = "biblioteca"
DB_USER = "biblioteca"
DB_PASSWORD = "biblioteca"
APP_PORT = 18080
APP_ENV = {
    "DB_URL": f"jdbc:postgresql://localhost:{PG_PORT}/{DB_NAME}",
    "DB_USERNAME": DB_USER,
    "DB_PASSWORD": DB_PASSWORD,
    "SERVER_PORT": str(APP_PORT),
}

results: list[tuple[str, bool, str]] = []


def record(step: str, ok: bool, evidence: str) -> None:
    results.append((step, ok, evidence))
    print(f"[{'OK ' if ok else 'FALLA'}] {step}: {evidence}", flush=True)


def run(command: list[str], timeout: int = 900, **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(
        command, capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace", **kwargs
    )


def emit(out_dir: pathlib.Path) -> tuple[str, int]:
    completed = run([sys.executable, str(HERE / "emit.py"), "--model", str(MODEL), "--out", str(out_dir)])
    if completed.returncode != 0:
        raise RuntimeError(f"emit fallo: {completed.stderr.strip()}")
    lines = completed.stdout.strip().splitlines()
    digest = next(line.split(": ", 1)[1] for line in lines if line.startswith("artifactSetHash: "))
    count = int(next(line.split(": ", 1)[1] for line in lines if line.startswith("files: ")))
    return digest, count


def docker(*args: str, timeout: int = 180) -> subprocess.CompletedProcess:
    return run(["docker", *args], timeout=timeout)


def container_count() -> int:
    completed = docker("ps", "-aq", timeout=60)
    return len([line for line in completed.stdout.split() if line])


PS_LIST_JAVA = (
    "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' } | "
    "ForEach-Object { $_.ProcessId.ToString() + '|' + $_.CommandLine }"
)


def stale_app_pids() -> list[int]:
    """JVM de corridas anteriores sobre este mismo spike.

    En Windows un JVM que no muere mantiene bloqueado el jar y el repackage de
    Spring Boot falla al renombrarlo. Paso de verdad: la primera corrida de
    extremo a extremo dejo un proceso vivo.
    """
    completed = run(["powershell", "-NoProfile", "-Command", PS_LIST_JAVA], timeout=120)
    pids = []
    for line in completed.stdout.splitlines():
        pid, _, command = line.partition("|")
        if command and "uml2spring" in command and pid.strip().isdigit():
            pids.append(int(pid.strip()))
    return pids


def kill_stale_apps() -> None:
    pids = stale_app_pids()
    for pid in pids:
        run(["taskkill", "/PID", str(pid), "/F", "/T"], timeout=60)
    if pids:
        print(f"procesos huerfanos del spike terminados: {pids}", flush=True)


def stop_app(app: subprocess.Popen | None) -> None:
    """terminate() no alcanzo en Windows: se mata el arbol y se verifica."""
    if app is not None:
        run(["taskkill", "/PID", str(app.pid), "/F", "/T"], timeout=60)
    for _ in range(15):
        if not stale_app_pids():
            return
        time.sleep(1)


def remove_own_container() -> None:
    docker("rm", "-f", CONTAINER, timeout=120)


def start_postgres() -> None:
    remove_own_container()
    completed = docker(
        "run", "-d", "--name", CONTAINER,
        "-e", f"POSTGRES_DB={DB_NAME}",
        "-e", f"POSTGRES_USER={DB_USER}",
        "-e", f"POSTGRES_PASSWORD={DB_PASSWORD}",
        "-p", f"{PG_PORT}:5432",
        PG_IMAGE,
        timeout=300,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"no se pudo crear el contenedor: {completed.stderr.strip()}")


def wait_for_postgres(deadline: float = 120) -> None:
    started = time.time()
    while time.time() - started < deadline:
        completed = docker("exec", CONTAINER, "pg_isready", "-U", DB_USER, "-d", DB_NAME, timeout=60)
        if completed.returncode == 0 and "accepting connections" in completed.stdout:
            return
        time.sleep(2)
    raise RuntimeError("PostgreSQL no acepto conexiones a tiempo")


def wait_for_health(log_path: pathlib.Path, deadline: float = 180) -> tuple[str, str]:
    """Espera a que la aplicacion responda. Devuelve (cuerpo de salud, log)."""
    started = time.time()
    last = ""
    while time.time() - started < deadline:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{APP_PORT}/actuator/health", timeout=5) as response:
                body = response.read().decode("utf-8")
            if '"status":"UP"' in body.replace(" ", ""):
                return body, log_path.read_text(encoding="utf-8", errors="replace")
        except (urllib.error.URLError, ConnectionError, TimeoutError) as error:
            last = str(error)
        if log_path.exists() and "APPLICATION FAILED TO START" in log_path.read_text(encoding="utf-8", errors="replace"):
            break
        time.sleep(2)
    log = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
    raise RuntimeError(f"la aplicacion no llego a UP ({last})\n--- log ---\n{log[-4000:]}")


def post(path: str, payload: dict) -> tuple[int, str]:
    request = urllib.request.Request(
        f"http://127.0.0.1:{APP_PORT}{path}",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    return _send(request)


def get(path: str) -> tuple[int, str]:
    return _send(urllib.request.Request(f"http://127.0.0.1:{APP_PORT}{path}", method="GET"))


def _send(request: urllib.request.Request) -> tuple[int, str]:
    """Un error HTTP es un resultado, no una excepcion: si el 500 escapara, el
    arnes moriria antes de imprimir el resumen y no se sabria que paso."""
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", errors="replace")[:400]


def main() -> int:
    BUILD.mkdir(parents=True, exist_ok=True)
    log_path = BUILD / "app.log"
    app: subprocess.Popen | None = None
    containers_before = container_count()
    print(f"contenedores antes: {containers_before}", flush=True)
    kill_stale_apps()

    try:
        # 1. Determinismo
        hash_a, files_a = emit(BUILD / "run-a")
        hash_b, files_b = emit(BUILD / "run-b")
        record(
            "Determinismo",
            hash_a == hash_b and files_a == files_b,
            f"{files_a} archivos, huella {'igual' if hash_a == hash_b else 'DISTINTA'}: {hash_a}",
        )

        # 2. Compilacion
        maven = shutil.which("mvn") or shutil.which("mvn.cmd")
        if not maven:
            record("Compilacion", False, "no se encontro mvn en PATH")
            return 1
        completed = run([maven, "-B", "-o", "package"], cwd=BUILD / "run-a", timeout=1800)
        outcome = [line for line in completed.stdout.splitlines() if "BUILD SUCCESS" in line or "BUILD FAILURE" in line]
        # 3. PostgreSQL
        if completed.returncode != 0:
            record("Compilacion", False, (completed.stdout + completed.stderr)[-2500:])
            return 1
        record("Compilacion", True, outcome[-1].strip() if outcome else "package sin linea BUILD")

        jars = sorted((BUILD / "run-a" / "target").glob("*.jar"))
        jars = [jar for jar in jars if "original" not in jar.name and "sources" not in jar.name]
        if not jars:
            record("Arranque", False, "no se genero jar ejecutable")
            return 1

        # 4. Contenedor de base de datos
        start_postgres()
        wait_for_postgres()
        record("PostgreSQL", True, f"contenedor {CONTAINER} escuchando en {PG_PORT}")

        # 5. Arranque de la aplicacion
        import os

        environment = {**os.environ, **APP_ENV}
        with log_path.open("w", encoding="utf-8") as log:
            app = subprocess.Popen(
                ["java", "-jar", str(jars[0])], stdout=log, stderr=subprocess.STDOUT, env=environment
            )
        body, app_log = wait_for_health(log_path)
        flyway_lines = [
            line.strip()
            for line in app_log.splitlines()
            if "Migrating schema" in line or "Successfully applied" in line
        ]
        record("Arranque", True, f"GET /actuator/health -> {body.strip()}")
        record("Migracion Flyway", bool(flyway_lines), flyway_lines[-1] if flyway_lines else "sin linea de migracion")

        # 6. CRUD
        status, autor = post("/api/autor", {"nombre": "Borges", "nacionalidad": "Argentina"})
        autor_id = json.loads(autor)["id"]
        record("CRUD crear autor", status == 201, f"HTTP {status} {autor.strip()}")

        status, libro = post("/api/libro", {"titulo": "Ficciones", "anio": 1944, "autorId": autor_id})
        record("CRUD crear libro", status == 201, f"HTTP {status} {libro.strip()}")

        status, autores = get("/api/autor")
        status_libros, libros = get("/api/libro")
        record("CRUD listar autor", status == 200, f"HTTP {status} {autores.strip()}")
        record("CRUD listar libro", status_libros == 200, f"HTTP {status_libros} {libros.strip()}")

        resolved = json.loads(libros)[0].get("autor", {}).get("id") == autor_id
        record("Clave foranea resuelta", resolved, f"libro.autor.id == {autor_id}: {resolved}")

        # 7. Historial de Flyway en la base
        history = docker(
            "exec", CONTAINER, "psql", "-U", DB_USER, "-d", DB_NAME, "-tAc",
            "select version || '|' || description || '|' || success from flyway_schema_history order by installed_rank",
            timeout=90,
        )
        record("Historial Flyway", "1|" in history.stdout, history.stdout.strip() or history.stderr.strip()[:200])

        # 8. Columnas reales
        columns = docker(
            "exec", CONTAINER, "psql", "-U", DB_USER, "-d", DB_NAME, "-tAc",
            "select table_name || '.' || column_name || ':' || is_nullable from information_schema.columns "
            "where table_schema='public' and table_name in ('autor','libro') order by table_name, ordinal_position",
            timeout=90,
        )
        record("Esquema creado", "libro.autor_id:NO" in columns.stdout, columns.stdout.strip().replace("\n", " "))

    finally:
        stop_app(app)
        remove_own_container()
        containers_after = container_count()
        record(
            "Limpieza",
            containers_after == containers_before,
            f"contenedores {containers_before} -> {containers_after}, {CONTAINER} eliminado",
        )

    failed = [step for step, ok, _ in results if not ok]
    print("\n=== resumen ===")
    for step, ok, evidence in results:
        print(f"{'PASA ' if ok else 'FALLA'}  {step:26} {evidence[:110]}")
    print(f"\n{len(results) - len(failed)} pasos OK, {len(failed)} con falla")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
