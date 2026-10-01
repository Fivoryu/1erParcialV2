#!/usr/bin/env python3
"""Run the UML visible-cut acceptance flow against a local PostgreSQL container."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import shutil
import signal
import socket
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.request
import uuid
from typing import Any

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
BACKEND_JARS = ROOT / "backend" / "target"
STATE_DIR = HERE / ".state"
POSTGRES_IMAGE = "postgres:16"
DATABASE = "diagramador"
DATABASE_USER = "diagramador"
DATABASE_PASSWORD = "diagramador"
OWNER_ID = "00000000-0000-0000-0000-000000000001"
OWNER_SUBJECT = "seed-owner-local"
POSTGRES_TIMEOUT = 120
BACKEND_TIMEOUT = 180


class AcceptanceFailure(Exception):
    """Signals the first failed acceptance assertion."""


def display(value: Any) -> str:
    return value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def report(results: list[bool], step: str, ok: bool, expected: Any, got: Any) -> None:
    results.append(ok)
    print(f"[{'OK' if ok else 'FAIL'}] {step}", flush=True)
    print(f"  expected: {display(expected)}", flush=True)
    print(f"  got:      {display(got)}", flush=True)


def require(results: list[bool], step: str, ok: bool, expected: Any, got: Any) -> None:
    report(results, step, ok, expected, got)
    if not ok:
        raise AcceptanceFailure(step)


def run_process(command: list[str], timeout: int = 60, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command, capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=timeout, env=env, check=False,
    )


def docker(executable: str | None, *args: str, timeout: int = 60) -> subprocess.CompletedProcess[str]:
    if not executable:
        raise RuntimeError("Docker executable was not found in PATH")
    return run_process([executable, *args], timeout=timeout)


def container_ids(executable: str | None) -> list[str]:
    result = docker(executable, "ps", "-aq", "--no-trunc")
    if result.returncode:
        raise RuntimeError(f"docker ps failed: {(result.stderr or result.stdout).strip()}")
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def owned_container_ids(executable: str | None, run_id: str) -> list[str]:
    result = docker(executable, "ps", "-aq", "--no-trunc", "--filter", f"label=gentle-ai.acceptance.run={run_id}")
    if result.returncode:
        raise RuntimeError(f"could not inspect acceptance containers: {(result.stderr or result.stdout).strip()}")
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def container_count(executable: str | None) -> int:
    return len(container_ids(executable))


def process_start_token(pid: int | None) -> str | None:
    """Return a PID's start identity without inspecting its command line."""
    if pid is None:
        return None
    if os.name == "nt":
        powershell = shutil.which("powershell") or shutil.which("pwsh")
        if not powershell:
            return None
        command = f"$p=Get-Process -Id {int(pid)} -ErrorAction SilentlyContinue; if ($p) {{ $p.StartTime.ToUniversalTime().Ticks }}"
        try:
            result = run_process([powershell, "-NoProfile", "-NonInteractive", "-Command", command], timeout=20)
        except (OSError, subprocess.TimeoutExpired):
            return None
        token = result.stdout.strip()
        return token if result.returncode == 0 and token.isdigit() else None

    stat_path = pathlib.Path(f"/proc/{pid}/stat")
    if stat_path.is_file():
        try:
            return stat_path.read_text(encoding="utf-8").rsplit(")", 1)[1].split()[19]
        except (OSError, IndexError):
            return None
    try:
        result = run_process(["ps", "-p", str(pid), "-o", "lstart="], timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        return None
    token = result.stdout.strip()
    return token if result.returncode == 0 and token else None


def query_process_ids_for_run_id(run_id: str) -> tuple[list[int], bool]:
    """Return processes carrying this run's Java marker and whether the query succeeded."""
    marker = f"-Dacceptance.runId={run_id}"
    if os.name == "nt":
        powershell = shutil.which("powershell") or shutil.which("pwsh")
        if not powershell:
            return [], False
        escaped_marker = marker.replace("'", "''")
        command = (
            "$ErrorActionPreference = 'Stop'; "
            f"$marker = '{escaped_marker}'; $ownPid = $PID; "
            "Get-CimInstance Win32_Process | "
            "Where-Object { $_.ProcessId -ne $ownPid -and $_.CommandLine -and $_.CommandLine.Contains($marker) } | "
            "ForEach-Object { $_.ProcessId }"
        )
        arguments = [powershell, "-NoProfile", "-NonInteractive", "-Command", command]
    else:
        ps = shutil.which("ps")
        if not ps:
            return [], False
        arguments = [ps, "-eo", "pid=,args="]

    try:
        result = run_process(arguments, timeout=20)
    except (OSError, subprocess.TimeoutExpired):
        return [], False
    if result.returncode != 0:
        return [], False

    pids: list[int] = []
    if os.name == "nt":
        for line in result.stdout.splitlines():
            value = line.strip()
            if value.isdigit():
                pids.append(int(value))
    else:
        for line in result.stdout.splitlines():
            parts = line.strip().split(None, 1)
            if len(parts) == 2 and marker in parts[1]:
                try:
                    pids.append(int(parts[0]))
                except ValueError:
                    continue
    return sorted(set(pids)), True


def process_ids_for_run_id(run_id: str) -> list[int]:
    """Return PIDs carrying the run marker; return an empty list if querying is unavailable."""
    pids, _ = query_process_ids_for_run_id(run_id)
    return pids


def recorded_process_absent(pid: int, start_token: str | None) -> bool:
    """Use the recorded PID only as a fallback when command-line inspection is unavailable."""
    current_token = process_start_token(pid)
    if current_token is not None:
        return start_token is not None and current_token != start_token
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return True
    except PermissionError:
        return False
    except OSError:
        return True
    return False


def backend_run_processes_absent(
    run_id: str, pid: int | None, start_token: str | None, backend: subprocess.Popen[bytes] | None = None,
) -> tuple[bool, dict[str, Any]]:
    pids, query_available = query_process_ids_for_run_id(run_id)
    if query_available:
        return not pids, {"runId": run_id, "matchingPids": pids, "queryAvailable": True}
    if pid is not None:
        absent = recorded_process_absent(pid, start_token)
    else:
        absent = backend is None or backend.poll() is not None
    return absent, {
        "runId": run_id, "matchingPids": [], "queryAvailable": False,
        "recordedPid": pid, "recordedProcessAbsent": absent,
    }


def is_uuid(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    try:
        uuid.UUID(value)
        return True
    except ValueError:
        return False


def model_projection(model: Any) -> Any:
    """Keep contract fields while normalizing server-sorted collections."""
    if not isinstance(model, dict):
        return model

    def rows(values: Any, keys: tuple[str, ...], sort_key: str = "id") -> Any:
        if not isinstance(values, list):
            return values
        selected = [{key: item.get(key) for key in keys} if isinstance(item, dict) else item for item in values]
        return sorted(selected, key=lambda item: str(item.get(sort_key, "")) if isinstance(item, dict) else str(item))

    classifiers = model.get("classifiers")
    if isinstance(classifiers, list):
        classifiers = [
            {
                "id": item.get("id"), "name": item.get("name"), "kind": item.get("kind"),
                "isAbstract": item.get("isAbstract"), "namespaceId": item.get("namespaceId"),
                "attributes": rows(item.get("attributes"),
                                    ("id", "name", "kind", "type", "visibility", "multiplicity", "defaultValue", "ordinal")),
                "operations": rows(item.get("operations"),
                                    ("id", "name", "visibility", "isAbstract", "isStatic", "returnType", "ordinal", "parameters")),
                "constraints": item.get("constraints"),
            } if isinstance(item, dict) else item for item in classifiers
        ]
        classifiers.sort(key=lambda item: str(item.get("id", "")) if isinstance(item, dict) else str(item))

    relationships = model.get("relationships")
    if isinstance(relationships, list):
        relationships = [
            {
                "id": item.get("id"), "kind": item.get("kind"), "name": item.get("name"),
                "ends": rows(item.get("ends"),
                             ("classifierId", "role", "multiplicity", "navigability", "aggregationKind", "ordinal"),
                             sort_key="ordinal"),
            } if isinstance(item, dict) else item for item in relationships
        ]
        relationships.sort(key=lambda item: str(item.get("id", "")) if isinstance(item, dict) else str(item))

    views = model.get("views")
    if isinstance(views, list):
        views = [
            {
                "id": item.get("id"), "name": item.get("name"), "is_default": item.get("is_default"),
                "element_geometry": rows(item.get("element_geometry"),
                                          ("targetType", "targetId", "x", "y", "w", "h", "zOrder"),
                                          sort_key="targetId"),
                "connector_path": rows(item.get("connector_path"),
                                        ("relationshipId", "ordinal", "x", "y"), sort_key="ordinal"),
            } if isinstance(item, dict) else item for item in views
        ]
        views.sort(key=lambda item: str(item.get("id", "")) if isinstance(item, dict) else str(item))

    return {
        "id": model.get("id"), "projectId": model.get("projectId"), "revision": model.get("revision"),
        "classifiers": classifiers, "relationships": relationships, "views": views,
    }


def model_diff(expected: Any, actual: Any, path: str = "$model") -> list[str]:
    if isinstance(expected, dict) and isinstance(actual, dict):
        differences = []
        for key in sorted(set(expected) | set(actual)):
            child = f"{path}.{key}"
            if key not in expected:
                differences.append(f"{child}: unexpected value {actual[key]!r}")
            elif key not in actual:
                differences.append(f"{child}: missing; expected {expected[key]!r}")
            else:
                differences.extend(model_diff(expected[key], actual[key], child))
        return differences
    if isinstance(expected, list) and isinstance(actual, list):
        differences = [] if len(expected) == len(actual) else [f"{path}: expected {len(expected)} entries, got {len(actual)}"]
        for index, (left, right) in enumerate(zip(expected, actual)):
            differences.extend(model_diff(left, right, f"{path}[{index}]"))
        return differences
    return [] if expected == actual else [f"{path}: expected {expected!r}, got {actual!r}"]


def model_counts(model: Any) -> dict[str, int]:
    if not isinstance(model, dict):
        return {key: 0 for key in ("elements", "classifiers", "attributes", "operations", "relationships",
                                   "relationshipEnds", "geometry", "classifierRectangles", "relationshipWaypoints")}
    classifiers = model.get("classifiers") if isinstance(model.get("classifiers"), list) else []
    relationships = model.get("relationships") if isinstance(model.get("relationships"), list) else []
    views = model.get("views") if isinstance(model.get("views"), list) else []
    attributes = sum(len(item.get("attributes", [])) for item in classifiers if isinstance(item, dict) and isinstance(item.get("attributes", []), list))
    operations = sum(len(item.get("operations", [])) for item in classifiers if isinstance(item, dict) and isinstance(item.get("operations", []), list))
    ends = sum(len(item.get("ends", [])) for item in relationships if isinstance(item, dict) and isinstance(item.get("ends", []), list))
    rectangles = sum(len(item.get("element_geometry", [])) for item in views if isinstance(item, dict) and isinstance(item.get("element_geometry", []), list))
    waypoints = sum(len(item.get("connector_path", [])) for item in views if isinstance(item, dict) and isinstance(item.get("connector_path", []), list))
    classifier_count, relationship_count = len(classifiers), len(relationships)
    return {
        "elements": classifier_count + attributes + operations + relationship_count + ends,
        "classifiers": classifier_count, "attributes": attributes, "operations": operations,
        "relationships": relationship_count, "relationshipEnds": ends,
        "geometry": rectangles + waypoints, "classifierRectangles": rectangles,
        "relationshipWaypoints": waypoints,
    }


def format_counts(value: dict[str, int]) -> str:
    return (f"elements={value['elements']} (classifiers={value['classifiers']}, attributes={value['attributes']}, "
            f"operations={value['operations']}, relationships={value['relationships']}, "
            f"relationshipEnds={value['relationshipEnds']}); geometry={value['geometry']} "
            f"(classifierRectangles={value['classifierRectangles']}, relationshipWaypoints={value['relationshipWaypoints']})")


class Harness:
    def __init__(self) -> None:
        self.results: list[bool] = []
        self.docker_executable = shutil.which("docker")
        self.run_id = str(uuid.uuid4())
        self.container_name = f"uml-acceptance-pg-{self.run_id[:12]}"
        self.container_id: str | None = None
        self.launch_attempted = False
        self.containers_before: int | None = None
        self.backend: subprocess.Popen[bytes] | None = None
        self.backend_start: str | None = None
        self.base_url: str | None = None
        self.api_token: str | None = None
        self.auth_username = f"acceptance-{self.run_id.replace('-', '')}"
        self.log_path = STATE_DIR / f"{self.run_id}-backend.log"
        self.keep = False

    def check(self, step: str, ok: bool, expected: Any, got: Any) -> None:
        require(self.results, step, ok, expected, got)

    def record(self, step: str, ok: bool, expected: Any, got: Any) -> None:
        report(self.results, step, ok, expected, got)

    def start_services(self) -> None:
        self.check("Locate Docker", self.docker_executable is not None, "docker available in PATH", self.docker_executable or "not found")
        self.containers_before = container_count(self.docker_executable)
        print(f"[INFO] Containers before: {self.containers_before}", flush=True)

        image = docker(self.docker_executable, "image", "inspect", "--format={{.Id}}", POSTGRES_IMAGE)
        image_id = image.stdout.strip()
        self.check("Inspect local PostgreSQL image", image.returncode == 0 and image_id.startswith("sha256:"),
                   f"local {POSTGRES_IMAGE} image ID; no pull or network access", image_id or image.stderr.strip())
        jars = sorted(path for path in BACKEND_JARS.glob("*.jar")
                      if not any(word in path.name for word in ("original", "sources", "javadoc")))
        self.check("Locate packaged backend", len(jars) == 1,
                   f"one executable JAR in {BACKEND_JARS}; build with `cd backend && mvn -o -B package` if absent",
                   [str(path) for path in jars])

        self.launch_attempted = True
        started = docker(
            self.docker_executable, "run", "--detach", "--pull=never", "--name", self.container_name,
            "--label", f"gentle-ai.acceptance.run={self.run_id}", "--publish", "127.0.0.1::5432",
            "--env", f"POSTGRES_DB={DATABASE}", "--env", f"POSTGRES_USER={DATABASE_USER}",
            "--env", f"POSTGRES_PASSWORD={DATABASE_PASSWORD}", image_id, timeout=180,
        )
        self.container_id = started.stdout.strip().splitlines()[-1] if started.stdout.strip() else None
        self.check("Start local PostgreSQL 16 container", started.returncode == 0 and bool(self.container_id),
                   "a container created from the inspected local image with --pull=never",
                   self.container_id or (started.stderr or started.stdout).strip())
        postgres_port = self.wait_for_postgres()
        version = docker(self.docker_executable, "exec", self.container_id, "postgres", "--version")
        version_text = version.stdout.strip()
        version_number = version_text.split()[-1] if version_text.split() else ""
        self.check("Verify PostgreSQL major version", version.returncode == 0 and version_number.startswith("16."),
                   "PostgreSQL 16.x", version_text or version.stderr.strip())
        self.start_backend(jars[0], postgres_port)
        print(f"[INFO] Backend base URL: {self.base_url}", flush=True)

    def wait_for_postgres(self) -> int:
        deadline = time.monotonic() + POSTGRES_TIMEOUT
        last = "not ready yet"
        while time.monotonic() < deadline:
            result = docker(self.docker_executable, "exec", self.container_id or "", "pg_isready",
                            "-U", DATABASE_USER, "-d", DATABASE, timeout=20)
            last = (result.stdout or result.stderr).strip()
            if result.returncode == 0 and "accepting connections" in result.stdout.lower():
                break
            time.sleep(1)
        else:
            self.check("Wait for PostgreSQL readiness", False, f"pg_isready accepts connections within {POSTGRES_TIMEOUT}s", last)
        port_result = docker(self.docker_executable, "port", self.container_id or "", "5432/tcp")
        mapping = next((line.strip() for line in port_result.stdout.splitlines() if line.strip()), "")
        try:
            port = int(mapping.rsplit(":", 1)[1])
        except (IndexError, ValueError):
            self.check("Resolve PostgreSQL host port", False, "127.0.0.1:<ephemeral port>", mapping or port_result.stderr.strip())
            raise AssertionError("unreachable")
        self.check("Wait for PostgreSQL readiness", True, "pg_isready accepts connections", last)
        self.check("Resolve PostgreSQL host port", port_result.returncode == 0 and 0 < port < 65536,
                   "a published local TCP port", mapping)
        return port

    @staticmethod
    def free_port() -> int:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.bind(("127.0.0.1", 0))
            return int(sock.getsockname()[1])

    def start_backend(self, jar: pathlib.Path, postgres_port: int) -> None:
        java = shutil.which("java")
        self.check("Locate Java runtime", java is not None, "java available in PATH", java or "not found")
        app_port = self.free_port()
        self.base_url = f"http://127.0.0.1:{app_port}"
        environment = os.environ.copy()
        environment.update({
            "DIAGRAMADOR_DB_URL": f"jdbc:postgresql://127.0.0.1:{postgres_port}/{DATABASE}",
            "SPRING_DATASOURCE_USERNAME": DATABASE_USER,
            "SPRING_DATASOURCE_PASSWORD": DATABASE_PASSWORD,
            "SERVER_PORT": str(app_port),
        })
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        with self.log_path.open("wb") as log:
            self.backend = subprocess.Popen(
                [java, f"-Dacceptance.runId={self.run_id}", "-jar", str(jar)], cwd=ROOT, env=environment,
                stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
            )
        self.backend_start = process_start_token(self.backend.pid)
        self.check("Pin backend process handle", self.backend_start is not None,
                   "Java PID and process start identity captured", {"pid": self.backend.pid, "start": self.backend_start})
        health, evidence = self.wait_for_backend()
        self.check("Wait for backend readiness", health is not None,
                   f"GET {self.base_url}/actuator/health returns HTTP 200 and status UP", health or evidence)

    def wait_for_backend(self) -> tuple[dict[str, Any] | None, str]:
        assert self.backend is not None and self.base_url is not None
        deadline, last = time.monotonic() + BACKEND_TIMEOUT, "health endpoint has not answered"
        while time.monotonic() < deadline:
            if self.backend.poll() is not None:
                return None, f"backend exited with code {self.backend.returncode}\n{self.read_log()[-3000:]}"
            try:
                with urllib.request.urlopen(f"{self.base_url}/actuator/health", timeout=3) as response:
                    body = response.read().decode("utf-8", errors="replace")
                    payload = json.loads(body)
                    if response.status == 200 and isinstance(payload, dict) and payload.get("status") == "UP":
                        return payload, body
                    last = f"HTTP {response.status}: {body}"
            except urllib.error.HTTPError as error:
                last = f"HTTP {error.code}: {error.read().decode('utf-8', errors='replace')}"
            except (urllib.error.URLError, TimeoutError, ConnectionError, json.JSONDecodeError) as error:
                last = str(error)
            time.sleep(1)
        return None, f"last health result: {last}\n{self.read_log()[-3000:]}"

    def read_log(self) -> str:
        try:
            return self.log_path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return ""

    def _send_request(self, method: str, path: str, payload: dict[str, Any] | None = None,
                      token: str | None = None) -> tuple[int | None, str]:
        assert self.base_url is not None
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        headers = {"Content-Type": "application/json"} if data is not None else {}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return response.status, response.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode("utf-8", errors="replace")
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            return None, f"{type(error).__name__}: {error}"

    def authenticate(self) -> None:
        if self.api_token is not None:
            return
        password = uuid.uuid4().hex
        status, body = self._send_request("POST", "/auth/register", {
            "username": self.auth_username, "password": password, "displayName": "Acceptance Harness",
        })
        self.check("Register acceptance user", status == 201, "HTTP 201", {"HTTP": status, "body": body})

        status, body = self._send_request("POST", "/auth/login", {
            "username": self.auth_username, "password": password,
        })
        self.check("Log in acceptance user", status == 200, "HTTP 200 with a bearer token", f"HTTP {status}")
        try:
            token = json.loads(body).get("token")
        except (json.JSONDecodeError, AttributeError):
            token = None
        self.check("Read acceptance bearer token", isinstance(token, str) and bool(token),
                   "a non-empty bearer token", "present" if token else "missing")
        self.api_token = token

    def request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> tuple[int | None, str]:
        self.authenticate()
        return self._send_request(method, path, payload, self.api_token)

    def response_json(self, step: str, status: int | None, body: str, expected_status: int) -> dict[str, Any]:
        self.check(step, status == expected_status, f"HTTP {expected_status}", {"HTTP": status, "body": body})
        try:
            value = json.loads(body)
        except json.JSONDecodeError as error:
            self.check(step, False, "a JSON object", f"{body!r}; {error}")
            raise AssertionError("unreachable")
        self.check(step, isinstance(value, dict), "a JSON object", value)
        return value

    def create_project(self) -> str:
        name = f"UML acceptance {self.run_id[:8]}"
        status, body = self.request("POST", "/projects", {"name": name, "description": "Acceptance harness"})
        project = self.response_json("Create project over HTTP", status, body, 201)
        self.check("Verify created project", project.get("name") == name and is_uuid(project.get("id")),
                   {"name": name, "id": "UUID"}, {"name": project.get("name"), "id": project.get("id")})
        return str(project["id"])

    def initial_model(self, project_id: str) -> tuple[str, int, str]:
        status, body = self.request("GET", f"/projects/{project_id}/model")
        model = self.response_json("Read initial project model", status, body, 200)
        views = model.get("views") if isinstance(model.get("views"), list) else []
        defaults = [view for view in views if isinstance(view, dict) and view.get("is_default") is True]
        view_id = defaults[0].get("id") if len(defaults) == 1 else None
        valid = (is_uuid(model.get("id")) and model.get("projectId") == project_id and model.get("revision") == 1
                 and len(defaults) == 1 and defaults[0].get("name") == "Class Diagram" and is_uuid(view_id)
                 and model.get("classifiers") == [] and model.get("relationships") == [])
        self.check("Verify initial model and default view", valid,
                   {"projectId": project_id, "revision": 1, "defaultView": "Class Diagram", "modelContents": "empty"},
                   {"id": model.get("id"), "projectId": model.get("projectId"), "revision": model.get("revision"),
                    "views": views, "classifiers": model.get("classifiers"), "relationships": model.get("relationships")})
        return str(model["id"]), int(model["revision"]), str(view_id)

    def send_command(self, project_id: str, model_id: str, revision: int, parent_id: str | None,
                     kind: str, payload: dict[str, Any], targets: list[dict[str, str]]) -> tuple[int, str]:
        operation_id = str(uuid.uuid4())
        envelope = {
            "operationId": operation_id, "type": kind,
            "author": {"userId": OWNER_ID, "externalSubject": OWNER_SUBJECT},
            "projectId": project_id, "modelId": model_id, "baseRevision": revision,
            "targets": targets,
            "preconditions": [{"kind": "model_valid", "expect": True}, {"kind": "permission", "expect": "EDITOR"}],
            "payload": payload, "origin": "MANUAL", "causalParentOperationId": parent_id,
            "idempotencyKey": f"acceptance:{self.run_id}:{operation_id}",
        }
        status, body = self.request("POST", f"/projects/{project_id}/commands", envelope)
        try:
            result: Any = json.loads(body)
        except json.JSONDecodeError:
            result = body
        next_revision = revision + 1
        valid = (status == 200 and isinstance(result, dict) and result.get("operationId") == operation_id
                 and result.get("status") == "ACCEPTED" and result.get("resultRevision") == next_revision)
        self.check(f"Apply {kind} at revision {revision}", valid,
                   {"HTTP": 200, "operationId": operation_id, "status": "ACCEPTED", "resultRevision": next_revision},
                   {"HTTP": status, "body": result})
        return next_revision, operation_id

    def run_visible_cut(self) -> None:
        project_id = self.create_project()
        model_id, revision, view_id = self.initial_model(project_id)
        parent: str | None = None
        progress = {key: 0 for key in ("elements", "classifiers", "attributes", "operations", "relationships",
                                       "relationshipEnds", "geometry", "classifierRectangles", "relationshipWaypoints")}
        print(f"[INFO] After project creation: {format_counts(progress)}", flush=True)

        def apply(kind: str, payload: dict[str, Any], targets: list[dict[str, str]], step: str, delta: dict[str, int]) -> None:
            nonlocal revision, parent
            revision, parent = self.send_command(project_id, model_id, revision, parent, kind, payload, targets)
            for key, amount in delta.items():
                progress[key] += amount
            print(f"[INFO] After {step}: {format_counts(progress)}", flush=True)

        customer, subscription = str(uuid.uuid4()), str(uuid.uuid4())
        attribute, operation, relationship = (str(uuid.uuid4()) for _ in range(3))
        classifier_delta = {"elements": 1, "classifiers": 1}
        apply("classifier.create", {"id": customer, "name": "Customer", "kind": "CLASS"}, [],
              "classifier.create Customer", classifier_delta)
        apply("classifier.create", {"id": subscription, "name": "Subscription", "kind": "ABSTRACT_CLASS"}, [],
              "classifier.create Subscription", classifier_delta)
        apply("attribute.add", {"id": attribute, "name": "email", "type": {"primitiveName": "String"},
                                "visibility": "PRIVATE", "multiplicity": "EXACTLY_ONE"},
              [{"scope": "CLASSIFIER", "id": customer}], "attribute.add email", {"elements": 1, "attributes": 1})
        apply("operation.add", {"id": operation, "name": "validate", "returnType": {"primitiveName": "boolean"},
                                 "visibility": "PUBLIC"},
              [{"scope": "CLASSIFIER", "id": customer}], "operation.add validate", {"elements": 1, "operations": 1})
        apply("relationship.create", {
            "id": relationship, "kind": "ASSOCIATION", "name": "subscriptions",
            "source": {"classifierId": customer, "role": "customer", "multiplicity": "EXACTLY_ONE", "navigability": "NAVIGABLE"},
            "target": {"classifierId": subscription, "role": "subscriptions", "multiplicity": "ZERO_OR_MANY", "navigability": "NOT_NAVIGABLE"},
        }, [{"scope": "CLASSIFIER", "id": customer}, {"scope": "CLASSIFIER", "id": subscription}],
           "relationship.create subscriptions", {"elements": 3, "relationships": 1, "relationshipEnds": 2})

        customer_geometry = {"x": 80, "y": 100, "w": 240, "h": 160, "zOrder": 1}
        subscription_geometry = {"x": 420, "y": 100, "w": 260, "h": 180, "zOrder": 2}
        apply("geometry.set", {"targetType": "CLASSIFIER", "viewId": view_id, "targetId": customer, **customer_geometry},
              [{"scope": "CLASSIFIER", "id": customer}], "geometry.set Customer", {"geometry": 1, "classifierRectangles": 1})
        apply("geometry.set", {"targetType": "CLASSIFIER", "viewId": view_id, "targetId": subscription, **subscription_geometry},
              [{"scope": "CLASSIFIER", "id": subscription}], "geometry.set Subscription", {"geometry": 1, "classifierRectangles": 1})
        waypoints = [{"x": 340, "y": 160}, {"x": 390, "y": 160}]
        apply("geometry.set", {"targetType": "RELATIONSHIP", "viewId": view_id, "targetId": relationship, "waypoints": waypoints},
              [{"scope": "RELATIONSHIP", "id": relationship}], "geometry.set relationship waypoints",
              {"geometry": 2, "relationshipWaypoints": 2})

        expected = {
            "id": model_id, "projectId": project_id, "revision": revision,
            "classifiers": [
                {"id": customer, "name": "Customer", "kind": "CLASS", "isAbstract": False, "namespaceId": None,
                 "attributes": [{"id": attribute, "name": "email", "kind": "ATTRIBUTE", "type": "String",
                                 "visibility": "PRIVATE", "multiplicity": "EXACTLY_ONE", "defaultValue": None, "ordinal": 0}],
                 "operations": [{"id": operation, "name": "validate", "visibility": "PUBLIC", "isAbstract": False,
                                 "isStatic": False, "returnType": "boolean", "ordinal": 0, "parameters": []}],
                 "constraints": []},
                {"id": subscription, "name": "Subscription", "kind": "ABSTRACT_CLASS", "isAbstract": True,
                 "namespaceId": None, "attributes": [], "operations": [], "constraints": []},
            ],
            "relationships": [{"id": relationship, "kind": "ASSOCIATION", "name": "subscriptions", "ends": [
                {"classifierId": customer, "role": "customer", "multiplicity": "EXACTLY_ONE", "navigability": "NAVIGABLE",
                 "aggregationKind": "NONE", "ordinal": 1},
                {"classifierId": subscription, "role": "subscriptions", "multiplicity": "ZERO_OR_MANY", "navigability": "NOT_NAVIGABLE",
                 "aggregationKind": "NONE", "ordinal": 2},
            ]}],
            "views": [{"id": view_id, "name": "Class Diagram", "is_default": True,
                       "element_geometry": [
                           {"targetType": "CLASSIFIER", "targetId": customer, **customer_geometry},
                           {"targetType": "CLASSIFIER", "targetId": subscription, **subscription_geometry},
                       ],
                       "connector_path": [{"relationshipId": relationship, "ordinal": i, **point}
                                          for i, point in enumerate(waypoints)]}],
        }
        expected["classifiers"].sort(key=lambda item: item["id"])
        expected["views"][0]["element_geometry"].sort(key=lambda item: item["targetId"])
        before = model_counts(expected)
        print(f"[INFO] Expected sent-state before re-read: {format_counts(before)}", flush=True)
        status, body = self.request("GET", f"/projects/{project_id}/model")
        model = self.response_json("Re-read persisted project model", status, body, 200)
        differences = model_diff(expected, model_projection(model))
        self.check("Compare re-read model field by field", not differences,
                   "classifier names/kinds/abstractness; attribute type/visibility; operation; relationship kind, ends and multiplicities; geometry",
                   "All expected fields match" if not differences else "Readable diff:\n" + "\n".join(differences))
        after = model_counts(model_projection(model))
        self.check("Compare element and geometry counts before/after re-read", before == after,
                   format_counts(before), format_counts(after))
        digest = hashlib.sha256(json.dumps(model, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        self.check("Hash re-read model", len(digest) == 64, "SHA-256 digest of canonical re-read JSON", digest)
        print(f"[INFO] Re-read counts: {format_counts(after)}", flush=True)
        print(f"[INFO] Re-read model SHA-256: {digest}", flush=True)

    def keep_services(self) -> None:
        assert self.container_id is not None and self.backend is not None and self.containers_before is not None
        ids = container_ids(self.docker_executable)
        count = len(ids)
        expected_count = self.containers_before + 1
        alive = self.backend.poll() is None and self.container_id in ids
        self.check("Keep-running resource verification", alive and count == expected_count,
                   {"container present": True, "backend running": True, "container count": expected_count},
                   {"container present": self.container_id in ids, "backend exit": self.backend.poll(), "container count": count})
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        manifest = {
            "runId": self.run_id, "status": "running", "containerId": self.container_id,
            "containerName": self.container_name, "backendPid": self.backend.pid,
            "backendStartToken": self.backend_start, "baseUrl": self.base_url,
            "logPath": str(self.log_path), "containersBefore": self.containers_before,
        }
        manifest_path = STATE_DIR / f"{self.run_id}.json"
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        self.keep = True
        print(f"[INFO] Left running by request: {self.container_name} ({self.container_id})", flush=True)
        print(f"[INFO] Left running by request: backend PID {self.backend.pid} at {self.base_url}", flush=True)
        print(f"[INFO] Cleanup command: python tools/acceptance/acceptance.py --stop {self.run_id}", flush=True)
        print(f"[INFO] Pinned handles: {manifest_path}", flush=True)

    def cleanup(self) -> None:
        if self.keep:
            return
        self.stop_backend()
        self.stop_container()
        self.verify_cleanup()

    def stop_backend(self) -> None:
        pid = self.backend.pid if self.backend is not None else None
        stopped, evidence = stop_backend_processes(
            self.run_id, backend=self.backend, recorded_pid=pid, start_token=self.backend_start,
        )
        self.record("Backend process teardown", stopped,
                    f"no process carrying -Dacceptance.runId={self.run_id} remains", evidence)

    def stop_container(self) -> None:
        if not self.launch_attempted:
            self.record("PostgreSQL container teardown", True, "no container was launched", "no container handle exists")
            return
        try:
            labels = owned_container_ids(self.docker_executable, self.run_id)
            all_ids = container_ids(self.docker_executable)
            if self.container_id and self.container_id in all_ids and self.container_id not in labels:
                self.record("PostgreSQL container teardown", False, "remove only a run-owned container",
                            f"refused to remove unlabelled container {self.container_id}")
            else:
                targets = labels or ([self.container_id] if self.container_id and self.container_id in all_ids else [])
                for target in targets:
                    result = docker(self.docker_executable, "rm", "--force", target, timeout=120)
                    self.record("Remove acceptance PostgreSQL container", result.returncode == 0,
                                f"remove run-owned container {target}", (result.stdout or result.stderr).strip())
        except Exception as error:
            self.record("PostgreSQL container teardown", False, "remove only this run's container", str(error))

    def verify_cleanup(self) -> None:
        try:
            ids = container_ids(self.docker_executable)
            labels = owned_container_ids(self.docker_executable, self.run_id) if self.launch_attempted else []
            absent = (self.container_id is None or self.container_id not in ids) and not labels
            self.record("Verify no acceptance container survived", absent,
                        "pinned container ID and run label are absent", {"containerId": self.container_id, "matching": labels})
        except Exception as error:
            self.record("Verify no acceptance container survived", False, "pinned container ID and run label are absent", str(error))
        try:
            after = container_count(self.docker_executable)
            self.record("Verify container count returned to baseline",
                        self.containers_before is not None and after == self.containers_before,
                        self.containers_before, after)
        except Exception as error:
            self.record("Verify container count returned to baseline", False, self.containers_before, str(error))
        pid = self.backend.pid if self.backend is not None else None
        absent_process, evidence = backend_run_processes_absent(
            self.run_id, pid, self.backend_start, self.backend,
        )
        if self.backend is not None:
            evidence["exitCode"] = self.backend.poll()
        self.record("Verify no backend process survived", absent_process,
                    f"no process carrying -Dacceptance.runId={self.run_id} remains", evidence)


def pid_is_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return True
    return True


def stop_pinned_process(pid: int, start_token: str) -> tuple[bool, str]:
    current = process_start_token(pid)
    if current is None and not pid_is_alive(pid):
        return True, f"backend PID {pid} is already absent"
    if current is not None and current != start_token:
        return True, f"PID {pid} belongs to another process; the recorded backend exited and was not signalled"

    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return True, f"backend PID {pid} exited before cleanup"
    except OSError as error:
        return False, str(error)

    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if not pid_is_alive(pid):
            return True, f"backend PID {pid} exited"
        current = process_start_token(pid)
        if current is not None and current != start_token:
            return True, f"backend PID {pid} exited"
        time.sleep(0.25)

    if os.name == "nt":
        taskkill = shutil.which("taskkill")
        if not taskkill:
            return False, f"taskkill is unavailable; recorded backend PID {pid} may still be running"
        try:
            result = run_process([taskkill, "/PID", str(pid), "/F"], timeout=15)
        except (OSError, subprocess.TimeoutExpired) as error:
            return not pid_is_alive(pid), str(error)
        return not pid_is_alive(pid), f"taskkill exit code {result.returncode}"

    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        return True, f"backend PID {pid} exited during forced stop"
    except OSError as error:
        return False, str(error)
    return not pid_is_alive(pid), f"backend PID {pid} did not exit"


def stop_run_marker_process(pid: int, run_id: str) -> tuple[bool, str]:
    if os.name == "nt":
        taskkill = shutil.which("taskkill")
        if not taskkill:
            pids, available = query_process_ids_for_run_id(run_id)
            if available and pid not in pids:
                return True, f"PID {pid} already exited"
            return False, "taskkill is unavailable"
        try:
            result = run_process([taskkill, "/PID", str(pid), "/F"], timeout=15)
        except (OSError, subprocess.TimeoutExpired) as error:
            detail = str(error)
        else:
            detail = f"taskkill exit code {result.returncode}"
    else:
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            return True, f"PID {pid} already exited"
        except OSError as error:
            return False, str(error)
        detail = "sent SIGTERM"

    deadline = time.monotonic() + (5 if os.name != "nt" else 3)
    while time.monotonic() < deadline:
        pids, available = query_process_ids_for_run_id(run_id)
        if available and pid not in pids:
            return True, detail or f"PID {pid} already exited"
        if not available and not pid_is_alive(pid):
            return True, detail or f"PID {pid} already exited"
        time.sleep(0.25)

    if os.name != "nt":
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            return True, f"PID {pid} exited during forced stop"
        except OSError as error:
            return False, str(error)
    pids, available = query_process_ids_for_run_id(run_id)
    gone = pid not in pids if available else not pid_is_alive(pid)
    return gone, detail or f"PID {pid} did not exit"


def stop_backend_processes(
    run_id: str, backend: subprocess.Popen[bytes] | None, recorded_pid: int | None, start_token: str | None,
) -> tuple[bool, dict[str, Any]]:
    recorded_stop: Any = "no recorded backend PID"
    if backend is not None:
        try:
            if backend.poll() is None:
                backend.terminate()
                try:
                    backend.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    backend.kill()
                    backend.wait(timeout=10)
            recorded_stop = {"pid": backend.pid, "exitCode": backend.poll()}
        except (OSError, subprocess.TimeoutExpired) as error:
            recorded_stop = {"pid": backend.pid, "error": str(error)}
    elif recorded_pid is not None:
        if start_token is None:
            recorded_stop = {"pid": recorded_pid, "error": "recorded process start identity is missing"}
        else:
            try:
                stopped, detail = stop_pinned_process(recorded_pid, start_token)
                recorded_stop = {"pid": recorded_pid, "stopped": stopped, "detail": detail}
            except (OSError, subprocess.TimeoutExpired) as error:
                recorded_stop = {"pid": recorded_pid, "error": str(error)}

    matching_pids, query_available = query_process_ids_for_run_id(run_id)
    marker_found = list(matching_pids)
    marker_stops: list[dict[str, Any]] = []
    deadline = time.monotonic() + 60
    while query_available and matching_pids and time.monotonic() < deadline:
        for pid in matching_pids:
            if pid not in marker_found:
                marker_found.append(pid)
            try:
                stopped, detail = stop_run_marker_process(pid, run_id)
            except (OSError, subprocess.TimeoutExpired) as error:
                stopped, detail = False, str(error)
            marker_stops.append({"pid": pid, "stopped": stopped, "detail": detail})
        matching_pids, query_available = query_process_ids_for_run_id(run_id)

    absent, verification = backend_run_processes_absent(run_id, recorded_pid, start_token, backend)
    if marker_found and not verification["queryAvailable"]:
        absent = False
        verification["markerQueryUnavailableAfterDiscovery"] = True
    return absent, {
        "recordedStop": recorded_stop, "markerProcessesFound": marker_found,
        "markerStops": marker_stops, **verification,
    }


def stop_keep_running(run_id: str) -> int:
    path = STATE_DIR / f"{run_id}.json"
    results: list[bool] = []
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
        if manifest.get("runId") != run_id:
            raise ValueError("manifest run ID does not match")
    except (OSError, ValueError, json.JSONDecodeError) as error:
        report(results, "Read pinned run handles", False, f"manifest for {run_id}", str(error))
        return 1

    executable = shutil.which("docker")
    try:
        before: int | None = container_count(executable)
        print(f"[INFO] Containers before cleanup: {before}", flush=True)
    except Exception as error:
        before = None
        report(results, "Inspect Docker state before cleanup", False, "container count", str(error))
    try:
        pid = int(manifest["backendPid"])
    except (KeyError, TypeError, ValueError):
        pid = None
    start_token = manifest.get("backendStartToken")
    start_token = str(start_token) if start_token is not None else None
    stopped, evidence = stop_backend_processes(run_id, None, pid, start_token)
    report(results, "Stop backend processes", stopped,
           f"no process carrying -Dacceptance.runId={run_id} remains", evidence)

    container_id = str(manifest.get("containerId", ""))
    present: bool | None = None
    try:
        ids, owned = container_ids(executable), owned_container_ids(executable, run_id)
        present = bool(container_id) and container_id in ids
        if present and container_id in owned:
            result = docker(executable, "rm", "--force", container_id, timeout=120)
            remove_ok, removal = result.returncode == 0, (result.stdout or result.stderr).strip()
        elif present:
            remove_ok, removal = False, "refused to remove a container without this run's ownership label"
        else:
            remove_ok, removal = not owned, "container already absent" if not owned else f"other run-owned container(s) remain: {owned}"
        remaining = container_ids(executable)
        remaining_owned = owned_container_ids(executable, run_id)
        gone = bool(container_id) and container_id not in remaining and not remaining_owned
    except Exception as error:
        remove_ok, removal, gone = False, str(error), False
    report(results, "Remove pinned PostgreSQL container", remove_ok and gone,
           "the recorded run-owned container is removed and absent", {"containerId": container_id, "result": removal})

    try:
        after = container_count(executable)
        expected_count: Any = before - (1 if present else 0) if before is not None and present is not None else "unverifiable"
        count_ok = before is not None and present is not None and after == expected_count
    except Exception as error:
        after, expected_count, count_ok = str(error), "count changes only by the removed container", False
    report(results, "Verify container count after cleanup", count_ok, expected_count, after)
    process_absent, process_evidence = backend_run_processes_absent(run_id, pid, start_token)
    report(results, "Verify no backend process survived cleanup", stopped and process_absent,
           f"no process carrying -Dacceptance.runId={run_id} remains",
           {"stopSucceeded": stopped, **process_evidence})
    try:
        remaining_owned = owned_container_ids(executable, run_id)
        no_container = gone and not remaining_owned
    except Exception as error:
        remaining_owned, no_container = str(error), False
    report(results, "Verify no acceptance container survived cleanup", no_container,
           "recorded container ID and run label are absent", {"containerId": container_id, "matching": remaining_owned})

    if all(results):
        manifest["status"] = "stopped"
        manifest["stoppedAtUnix"] = int(time.time())
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        print("[OK] Keep-running services have been stopped and verified.", flush=True)
    return 0 if all(results) else 1


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Exercise the UML 2.5 visible cut over HTTP with local PostgreSQL 16.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--keep-running", action="store_true", help="leave PostgreSQL and the backend running after acceptance")
    mode.add_argument("--stop", metavar="RUN_ID", help="stop services left by --keep-running")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if args.stop:
        try:
            run_id = str(uuid.UUID(args.stop))
        except ValueError:
            print(f"[FAIL] Validate run ID\n  expected: UUID\n  got:      {args.stop}", flush=True)
            return 2
        return stop_keep_running(run_id)

    harness = Harness()
    try:
        harness.start_services()
        harness.run_visible_cut()
        if args.keep_running:
            harness.keep_services()
    except KeyboardInterrupt:
        harness.record("Acceptance run interrupted", False, "complete or tear down on Ctrl-C", "KeyboardInterrupt")
    except AcceptanceFailure:
        pass
    except Exception as error:
        harness.record("Harness execution", False, "visible-cut acceptance completes",
                       f"{type(error).__name__}: {error}\n{traceback.format_exc()}")
    finally:
        harness.cleanup()

    passed = sum(harness.results)
    failed = len(harness.results) - passed
    print(f"\n=== Acceptance summary: {passed} passed, {failed} failed ===", flush=True)
    if harness.keep:
        print("Services remain running by request; use the printed --stop command when finished.", flush=True)
    elif failed == 0:
        print("Backend process exited; acceptance container removed; container count verified at baseline.", flush=True)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
