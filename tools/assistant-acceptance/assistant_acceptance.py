#!/usr/bin/env python3
"""Exercise the IA4 assistant journey (plan/apply, undo/redo) against the real Compose stack
plus a local llama-server. Structure mirrors tools/collaboration-acceptance/collaboration_acceptance.py."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import socket
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
COMPOSE_FILE = ROOT / "docker-compose.yml"
COMPOSE_PROJECT = "assistant-acceptance"
PROJECT_LABEL = f"com.docker.compose.project={COMPOSE_PROJECT}"
ZERO_UUID = "00000000-0000-0000-0000-000000000000"

# The backend reads these through @Value("${diagrammer.assistant.enabled}") and
# @Value("${diagrammer.assistant.url}") (LlamaCppAssistantProvider.java). docker-compose.yml does not
# interpolate them, so the harness writes a Compose override file in the system temp dir (never in the
# repository) that injects them into the `diagrammer` service. Spring relaxed binding maps the env var
# names below onto those properties.
ASSISTANT_ENVIRONMENT = {
    "DIAGRAMMER_ASSISTANT_ENABLED": "true",
    "DIAGRAMMER_ASSISTANT_URL": "http://host.docker.internal:18099",
}
OVERRIDE_YAML = """services:
  diagrammer:
    environment:
      DIAGRAMMER_ASSISTANT_ENABLED: "true"
      DIAGRAMMER_ASSISTANT_URL: http://host.docker.internal:18099
    extra_hosts:
      - "host.docker.internal:host-gateway"
"""

DEFAULT_LLAMA_BINARY = r"D:\ai-eval\runtime\llama-server.exe"
DEFAULT_LLAMA_MODEL = r"D:\ai-eval\models\Qwen3.5-2B-Q4_K_M.gguf"


def display(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)


def report(
    results: list[tuple[str, bool]],
    scenario: str,
    step: str,
    ok: bool,
    expected: object,
    observed: object,
    command: str | None = None,
) -> None:
    if not isinstance(ok, bool):
        raise TypeError(f"the assertion for {step!r} produced {type(ok).__name__}, not bool")
    results.append((scenario, ok))
    print(f"[{'OK' if ok else 'FAIL'}] {step}", flush=True)
    if command:
        print(f"  command:  {command}", flush=True)
    print(f"  expected: {display(expected)}", flush=True)
    print(f"  observed: {display(observed)}", flush=True)


def summarize_body(body: object) -> object:
    if not isinstance(body, dict):
        return body
    result = dict(body)
    if "token" in result:
        result["token"] = "<redacted>" if result["token"] else result["token"]
    return result


def parse_body(raw: bytes) -> object:
    if not raw:
        return None
    text = raw.decode("utf-8", errors="replace")
    try:
        return json.loads(text)
    except (json.JSONDecodeError, ValueError):
        return text


def make_request(
    base_url: str,
    method: str,
    path: str,
    token: str | None = None,
    payload: object | None = None,
    timeout: float = 10.0,
) -> dict[str, object]:
    headers = {"Accept": "application/json"}
    body = None
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = Request(f"{base_url}{path}", data=body, headers=headers, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            raw = response.read()
            return {
                "status": response.getcode(),
                "body": parse_body(raw),
                "error": None,
            }
    except HTTPError as error:
        try:
            raw = error.read()
        except OSError:
            raw = b""
        return {
            "status": error.code,
            "body": parse_body(raw),
            "error": None,
        }
    except (URLError, TimeoutError, OSError, ValueError) as error:
        return {"status": None, "body": None, "error": f"{type(error).__name__}: {error}"}


def check_http(
    results: list[tuple[str, bool]],
    scenario: str,
    step: str,
    response: dict[str, object],
    expected_status: int,
    body_check,
    expected_body: object,
    command: str,
) -> bool:
    body_ok, body_observed = body_check(response.get("body"))
    status_ok = response.get("status") == expected_status
    ok = status_ok and body_ok
    report(
        results,
        scenario,
        step,
        ok,
        {"httpStatus": expected_status, "body": expected_body},
        {
            "httpStatus": response.get("status"),
            "body": summarize_body(response.get("body")),
            "bodyAssertion": body_observed,
            "error": response.get("error"),
        },
        command,
    )
    return ok


def project_container_list() -> tuple[int | None, list[str], str | None]:
    command = [
        "docker",
        "ps",
        "-a",
        "--filter",
        f"label={PROJECT_LABEL}",
        "--format",
        "{{.ID}}\t{{.Names}}\t{{.Status}}",
    ]
    try:
        result = subprocess.run(
            command,
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            check=False,
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return None, [], f"{type(error).__name__}: {error}"
    if result.returncode != 0:
        return result.returncode, [], (result.stderr or result.stdout).strip()
    containers = [line for line in result.stdout.splitlines() if line.strip()]
    return 0, containers, None


def compose_base(override_path: str | None) -> list[str]:
    command = [
        "docker",
        "compose",
        "-p",
        COMPOSE_PROJECT,
        "-f",
        str(COMPOSE_FILE),
    ]
    if override_path:
        command += ["-f", override_path]
    return command + ["--project-directory", str(ROOT)]


def compose_environment(args: argparse.Namespace) -> dict[str, str]:
    environment = os.environ.copy()
    environment.update(
        {
            "DIAGRAMMER_DB_NAME": args.db_name,
            "DIAGRAMMER_DB_USER": args.db_user,
            "DIAGRAMMER_DB_PASSWORD": args.db_password,
            "DIAGRAMMER_HTTP_HOST_PORT": str(args.http_port),
            "DIAGRAMMER_POSTGRES_HOST_PORT": str(args.postgres_port),
        }
    )
    return environment


def run_compose(
    results: list[tuple[str, bool]],
    scenario: str,
    step: str,
    arguments: list[str],
    environment: dict[str, str],
    override_path: str | None,
    timeout: int,
) -> bool:
    command = compose_base(override_path) + arguments
    command_text = " ".join(command)
    try:
        completed = subprocess.run(
            command,
            cwd=str(ROOT),
            env=environment,
            capture_output=True,
            text=True,
            check=False,
            timeout=timeout,
        )
        output = "\n".join(part for part in (completed.stdout, completed.stderr) if part).strip()
        if output:
            print(f"--- {step} output ---\n{output}", flush=True)
        ok = completed.returncode == 0
        observed: object = {"exitCode": completed.returncode}
        if not ok:
            observed = {"exitCode": completed.returncode, "output": output[-8000:]}
    except subprocess.TimeoutExpired as error:
        output = error.stdout or ""
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        stderr = error.stderr or ""
        if isinstance(stderr, bytes):
            stderr = stderr.decode("utf-8", errors="replace")
        output = "\n".join(part for part in (output, stderr) if part)
        if output:
            print(f"--- {step} output before timeout ---\n{output}", flush=True)
        ok = False
        observed = {"error": f"timed out after {timeout}s", "output": output[-8000:]}
    except OSError as error:
        ok = False
        observed = {"error": f"{type(error).__name__}: {error}"}
    report(results, scenario, step, ok, {"exitCode": 0, "command": command_text}, observed, command_text)
    return ok


def json_object(value: object) -> dict[str, object] | None:
    return value if isinstance(value, dict) else None


def nonempty_string(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def get_string(body: object, field: str, fallback: str = ZERO_UUID) -> str:
    value = json_object(body)
    candidate = None if value is None else value.get(field)
    return candidate if nonempty_string(candidate) else fallback


def uuid_from_seed(seed: str) -> str:
    digits = list(hashlib.sha256(seed.encode("utf-8")).hexdigest()[:32])
    digits[12] = "4"
    digits[16] = "89ab"[int(digits[16], 16) % 4]
    compact = "".join(digits)
    return f"{compact[:8]}-{compact[8:12]}-{compact[12:16]}-{compact[16:20]}-{compact[20:]}"


def port_is_occupied(port: int) -> bool | None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(1.0)
        code = probe.connect_ex(("127.0.0.1", port))
    return None if code < 0 else code == 0


class HttpClient:
    def __init__(self, label: str, display_name: str, username: str, base_url: str):
        self.label = label
        self.display_name = display_name
        self.username = username
        self.base_url = base_url
        self.token: str | None = None

    def request(self, method: str, path: str, payload: object | None, timeout: float):
        return make_request(self.base_url, method, path, self.token, payload, timeout)


def record_registration(
    results: list[tuple[str, bool]],
    scenario: str,
    client: HttpClient,
    password: str,
    timeout: float,
) -> None:
    response = client.request(
        "POST",
        "/auth/register",
        {
            "username": client.username,
            "password": password,
            "displayName": client.display_name,
            "email": f"{client.username}@example.test",
        },
        timeout,
    )

    def registered_user(body: object):
        value = json_object(body)
        ok = value is not None and nonempty_string(value.get("id")) and value.get("username") == client.username
        return ok, {
            "idPresent": value is not None and nonempty_string(value.get("id")),
            "username": None if value is None else value.get("username"),
        }

    check_http(
        results,
        scenario,
        f"Register {client.label}",
        response,
        201,
        registered_user,
        {"id": "non-empty UUID", "username": client.username},
        "POST /auth/register",
    )


def record_login(
    results: list[tuple[str, bool]],
    scenario: str,
    client: HttpClient,
    password: str,
    timeout: float,
) -> None:
    response = client.request(
        "POST",
        "/auth/login",
        {"username": client.username, "password": password},
        timeout,
    )
    body = json_object(response.get("body"))
    client.token = None if body is None or not nonempty_string(body.get("token")) else str(body["token"])

    def logged_in(value: object):
        data = json_object(value)
        session_user = None if data is None else json_object(data.get("user"))
        ok = (
            data is not None
            and nonempty_string(data.get("token"))
            and session_user is not None
            and session_user.get("displayName") == client.display_name
        )
        return ok, {
            "tokenPresent": data is not None and nonempty_string(data.get("token")),
            "userDisplayName": None if session_user is None else session_user.get("displayName"),
        }

    check_http(
        results,
        scenario,
        f"Log in {client.label}",
        response,
        200,
        logged_in,
        {"token": "non-empty", "user.displayName": client.display_name},
        "POST /auth/login",
    )


# --- model inspection helpers -------------------------------------------------------


def model_revision(body: object) -> int | None:
    value = json_object(body)
    revision = None if value is None else value.get("revision")
    return revision if isinstance(revision, int) else None


def classifiers_of(body: object) -> list[dict[str, object]]:
    value = json_object(body)
    classifiers = None if value is None else value.get("classifiers")
    if not isinstance(classifiers, list):
        return []
    return [item for item in classifiers if isinstance(item, dict)]


def find_classifier(body: object, name: str) -> dict[str, object] | None:
    for classifier in classifiers_of(body):
        if classifier.get("name") == name:
            return classifier
    return None


def attributes_of(classifier: dict[str, object] | None) -> list[dict[str, object]]:
    if classifier is None:
        return []
    attributes = classifier.get("attributes")
    if not isinstance(attributes, list):
        return []
    return [item for item in attributes if isinstance(item, dict)]


def relationships_of(body: object) -> list[dict[str, object]]:
    value = json_object(body)
    relationships = None if value is None else value.get("relationships")
    if not isinstance(relationships, list):
        return []
    return [item for item in relationships if isinstance(item, dict)]


def relationship_between(body: object, id_a: str, id_b: str) -> dict[str, object] | None:
    for relationship in relationships_of(body):
        ends = relationship.get("ends")
        if not isinstance(ends, list):
            continue
        ids = [end.get("classifierId") for end in ends if isinstance(end, dict)]
        if id_a in ids and id_b in ids:
            return relationship
    return None


def plan_operation_types(plan_body: object) -> list[str]:
    value = json_object(plan_body)
    operations = None if value is None else value.get("operations")
    if not isinstance(operations, list):
        return []
    return [str(operation.get("type")) for operation in operations if isinstance(operation, dict)]


def apply_payload(plan_body: object, base_revision: int) -> dict[str, object]:
    """Echo the plan operations exactly as the plan endpoint returned them (type, targetId, args)."""
    value = json_object(plan_body)
    operations = None if value is None else value.get("operations")
    echoed = []
    for operation in operations if isinstance(operations, list) else []:
        if isinstance(operation, dict):
            echoed.append(
                {
                    "type": operation.get("type"),
                    "targetId": operation.get("targetId"),
                    "args": operation.get("args") if isinstance(operation.get("args"), dict) else {},
                }
            )
    return {"operations": echoed, "baseRevision": base_revision}


def steps_status_summary(apply_body: object) -> dict[str, object]:
    value = json_object(apply_body)
    steps = None if value is None else value.get("steps")
    statuses = [
        str(step.get("status"))
        for step in (steps if isinstance(steps, list) else [])
        if isinstance(step, dict)
    ]
    diagnostics = [
        [str(diagnostic.get("code")) for diagnostic in step.get("diagnostics", []) if isinstance(diagnostic, dict)]
        for step in (steps if isinstance(steps, list) else [])
        if isinstance(step, dict)
    ]
    return {
        "stepCount": len(statuses) if isinstance(steps, list) else 0,
        "stepStatuses": statuses,
        "stepDiagnostics": diagnostics,
        "finalRevision": None if value is None else value.get("finalRevision"),
    }


# --- llama-server process management -------------------------------------------------


def start_llama(args: argparse.Namespace) -> tuple[subprocess.Popen | None, str | None]:
    command = [
        str(args.llama_binary),
        "--host",
        "127.0.0.1",
        "--port",
        str(args.llama_port),
        "-m",
        str(args.llama_model),
        "-c",
        "2048",
    ]
    try:
        process = subprocess.Popen(
            command,
            cwd=str(ROOT),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return process, None
    except OSError as error:
        return None, f"{type(error).__name__}: {error}"


def wait_for_llama_health(port: int, timeout: float) -> tuple[bool, object]:
    deadline = time.monotonic() + timeout
    last_observation: object = "no attempt made"
    while time.monotonic() < deadline:
        response = make_request(f"http://127.0.0.1:{port}", "GET", "/health", timeout=5.0)
        if response.get("status") == 200:
            return True, {"httpStatus": 200, "body": response.get("body")}
        last_observation = response.get("error") or {"httpStatus": response.get("status")}
        time.sleep(1.0)
    return False, {"lastObservation": last_observation, "waitedSeconds": timeout}


def stop_llama(process: subprocess.Popen | None) -> bool:
    if process is None:
        return True
    if process.poll() is not None:
        return True
    process.terminate()
    try:
        process.wait(timeout=15)
        return True
    except subprocess.TimeoutExpired:
        process.kill()
        try:
            process.wait(timeout=15)
            return True
        except subprocess.TimeoutExpired:
            return False


# --- the journey ----------------------------------------------------------------------


class AssistantJourney:
    def __init__(self, results: list[tuple[str, bool]], args: argparse.Namespace, suffix: str):
        self.results = results
        self.args = args
        self.suffix = suffix
        self.base_url = f"http://127.0.0.1:{args.http_port}"
        self.timeout = args.request_timeout
        self.assistant_timeout = args.assistant_timeout
        self.password = "AssistantAcceptance-2026"
        self.client_a = HttpClient("user A (creator)", "User A", f"harness-a-{suffix}", self.base_url)
        self.client_b = HttpClient("user B (editor)", "User B", f"harness-b-{suffix}", self.base_url)
        self.project_id = ZERO_UUID
        self.project_path = ""
        self.model_id = ZERO_UUID
        self.revisions: list[int] = []
        # (mutation label, accepted?) pairs for EVERY attempted mutation, plus revision samples taken
        # ONLY from mutations the harness verified as ACCEPTED. The monotonic check samples only the
        # accepted ones, so rejected/model-behavior branches never poison the bookkeeping.
        self.mutations: list[tuple[str, bool]] = []
        self.revision_samples: list[tuple[str, int]] = []

    # -- shared helpers --

    def read_model(self, client: HttpClient) -> dict[str, object]:
        return client.request("GET", f"{self.project_path}/model", None, self.timeout)

    def record_revision(self, body: object) -> int | None:
        revision = model_revision(body)
        if isinstance(revision, int):
            self.revisions.append(revision)
        return revision

    def record_mutation(self, label: str, accepted: bool, model_body: object) -> None:
        """Record a (mutation, accepted?) pair; only verified-ACCEPTED mutations feed the monotonic
        revision sample, taken from the model read immediately after the mutation."""
        self.mutations.append((label, bool(accepted)))
        if not accepted:
            return
        revision = model_revision(json_object(model_body))
        if isinstance(revision, int):
            self.revision_samples.append((label, revision))

    def plan(self, client: HttpClient, utterance: str) -> tuple[dict[str, object], int | None]:
        """POST /assistant/plan with the fresh baseRevision the endpoint requires; returns (response, revision)."""
        model = self.read_model(client)
        revision = model_revision(model.get("body"))
        response = client.request(
            "POST",
            f"{self.project_path}/assistant/plan",
            {"utterance": utterance, "baseRevision": revision},
            self.assistant_timeout,
        )
        return response, revision

    def apply(
        self, client: HttpClient, plan_body: object, base_revision: int | None
    ) -> dict[str, object]:
        return client.request(
            "POST",
            f"{self.project_path}/assistant/apply",
            apply_payload(plan_body, base_revision if base_revision is not None else 0),
            self.assistant_timeout,
        )

    def undo(self, client: HttpClient) -> dict[str, object]:
        return client.request("POST", f"{self.project_path}/undo", None, self.timeout)

    def redo(self, client: HttpClient) -> dict[str, object]:
        return client.request("POST", f"{self.project_path}/redo", None, self.timeout)

    def manual_classifier_create(
        self, client: HttpClient, name: str, seed: str
    ) -> tuple[dict[str, object], str]:
        """Seed one classifier through the same manual command shape the collaboration harness uses."""
        model = self.read_model(client)
        model_body = json_object(model.get("body"))
        model_id = get_string(model_body, "id") if model_body is not None else ZERO_UUID
        revision = model_revision(model_body) or 1
        classifier_id = uuid_from_seed(f"{self.suffix}-{seed}-id")
        response = client.request(
            "POST",
            f"{self.project_path}/commands",
            {
                "operationId": uuid_from_seed(f"{self.suffix}-{seed}-operation"),
                "type": "classifier.create",
                "projectId": self.project_id,
                "modelId": model_id,
                "baseRevision": revision,
                "targets": [],
                "preconditions": [],
                "payload": {"id": classifier_id, "name": name, "kind": "CLASS"},
                "origin": "MANUAL",
                "idempotencyKey": uuid_from_seed(f"{self.suffix}-{seed}-idempotency"),
            },
            self.timeout,
        )
        return response, classifier_id

    # -- setup --

    def setup(self) -> None:
        scenario = "setup"
        results = self.results
        args = self.args
        record_registration(results, scenario, self.client_a, self.password, self.timeout)
        record_registration(results, scenario, self.client_b, self.password, self.timeout)
        record_login(results, scenario, self.client_a, self.password, self.timeout)
        record_login(results, scenario, self.client_b, self.password, self.timeout)
        distinct_tokens = (
            nonempty_string(self.client_a.token)
            and nonempty_string(self.client_b.token)
            and self.client_a.token != self.client_b.token
        )
        report(
            results,
            scenario,
            "User A and user B hold distinct bearer tokens",
            distinct_tokens,
            {"two non-empty, distinct tokens": True},
            {
                "userATokenPresent": bool(self.client_a.token),
                "userBTokenPresent": bool(self.client_b.token),
                "tokensDiffer": self.client_a.token != self.client_b.token,
            },
        )

        project_response = self.client_a.request(
            "POST",
            "/projects",
            {"name": f"Assistant acceptance {self.suffix}", "description": "IA4 assistant API acceptance run."},
            self.timeout,
        )

        def project_created(body: object):
            value = json_object(body)
            ok = value is not None and nonempty_string(value.get("id")) and nonempty_string(value.get("name"))
            return ok, {
                "idPresent": value is not None and nonempty_string(value.get("id")),
                "name": None if value is None else value.get("name"),
            }

        check_http(results, scenario, "A creates a project", project_response, 201, project_created,
                   {"id": "non-empty UUID", "name": "non-empty"}, "POST /projects")
        self.project_id = get_string(project_response.get("body"), "id")
        self.project_path = f"/projects/{self.project_id}"

        invitation_response = self.client_a.request(
            "POST",
            f"{self.project_path}/invitations",
            {"username": self.client_b.username, "role": "EDITOR"},
            self.timeout,
        )

        def invitation_created(body: object):
            value = json_object(body)
            ok = (
                value is not None
                and nonempty_string(value.get("id"))
                and value.get("role") == "EDITOR"
                and value.get("status") == "PENDING"
            )
            return ok, {
                "idPresent": value is not None and nonempty_string(value.get("id")),
                "role": None if value is None else value.get("role"),
                "status": None if value is None else value.get("status"),
            }

        check_http(results, scenario, "A invites B as EDITOR", invitation_response, 201, invitation_created,
                   {"id": "non-empty UUID", "role": "EDITOR", "status": "PENDING"},
                   "POST /projects/{projectId}/invitations")
        invitation_id = get_string(invitation_response.get("body"), "id")
        accepted_response = self.client_b.request(
            "POST", f"{self.project_path}/invitations/{invitation_id}/accept", None, self.timeout
        )

        def invitation_accepted(body: object):
            value = json_object(body)
            ok = (
                value is not None
                and value.get("displayName") == self.client_b.display_name
                and value.get("role") == "EDITOR"
                and value.get("status") == "ACTIVE"
            )
            return ok, {
                "displayName": None if value is None else value.get("displayName"),
                "role": None if value is None else value.get("role"),
                "status": None if value is None else value.get("status"),
            }

        check_http(results, scenario, "B accepts the invitation", accepted_response, 200, invitation_accepted,
                   {"displayName": self.client_b.display_name, "role": "EDITOR", "status": "ACTIVE"},
                   "POST /projects/{projectId}/invitations/{invitationId}/accept")

        seed_response, seed_id = self.manual_classifier_create(self.client_a, "Cliente", "cliente-seed")

        def command_accepted(body: object):
            value = json_object(body)
            ok = value is not None and value.get("status") == "ACCEPTED"
            return ok, {
                "status": None if value is None else value.get("status"),
                "resultRevision": None if value is None else value.get("resultRevision"),
            }

        seed_ok = check_http(results, scenario, "A seeds the Cliente classifier with a manual command", seed_response, 200,
                   command_accepted, {"status": "ACCEPTED"}, "POST /projects/{id}/commands")

        seeded_model = self.read_model(self.client_a)
        model_body = json_object(seeded_model.get("body"))
        self.model_id = get_string(model_body, "id")
        self.record_revision(seeded_model.get("body"))
        self.record_mutation("setup: manual seed Cliente", seed_ok, seeded_model.get("body"))

        def seeded_model_ready(body: object):
            value = json_object(body)
            ok = (
                value is not None
                and value.get("id") == self.model_id
                and find_classifier(value, "Cliente") is not None
                and get_string(find_classifier(value, "Cliente"), "id") == seed_id
                and isinstance(value.get("revision"), int)
            )
            return ok, {
                "modelId": None if value is None else value.get("id"),
                "hasCliente": find_classifier(value, "Cliente") is not None,
                "revision": None if value is None else value.get("revision"),
            }

        check_http(results, scenario, "Model read shows the seeded Cliente classifier", seeded_model, 200,
                   seeded_model_ready, {"id": self.model_id, "hasCliente": True, "revision": "integer"},
                   "GET /projects/{id}/model")

    # -- scenario 1: multi-command preview --

    def scenario_1(self) -> None:
        scenario = "escenario-1"
        results = self.results
        utterance = "Creá una clase Factura con el atributo fecha: Date y asociala con Cliente"
        plan_response, base_revision = self.plan(self.client_a, utterance)

        def plan_factura(body: object):
            value = json_object(body)
            operations = None if value is None else value.get("operations")
            ops = [op for op in (operations or []) if isinstance(op, dict)]
            types = sorted(str(op.get("type")) for op in ops)
            expected_types = sorted(["classifier.create", "attribute.add", "relationship.create"])
            create = next((op for op in ops if op.get("type") == "classifier.create"), None)
            attribute = next((op for op in ops if op.get("type") == "attribute.add"), None)
            relationship = next((op for op in ops if op.get("type") == "relationship.create"), None)
            ok = (
                value is not None
                and value.get("intent") == "PLAN"
                and len(ops) == 3
                and types == expected_types
                and isinstance(create, dict)
                and (create.get("args") or {}).get("name") == "Factura"
                and isinstance(attribute, dict)
                and (attribute.get("args") or {}).get("name") == "fecha"
                and str((attribute.get("args") or {}).get("type", "")).upper() == "DATE"
                and isinstance(relationship, dict)
            )
            return ok, {
                "intent": None if value is None else value.get("intent"),
                "operationTypes": types,
                "createName": None if create is None else (create.get("args") or {}).get("name"),
                "attribute": None if attribute is None else {
                    "name": (attribute.get("args") or {}).get("name"),
                    "type": (attribute.get("args") or {}).get("type"),
                },
                "relationshipPresent": isinstance(relationship, dict),
                "diagnosticCodes": [
                    str(diagnostic.get("code"))
                    for diagnostic in ((value or {}).get("diagnostics") or [])
                    if isinstance(diagnostic, dict)
                ],
            }

        plan_ok = check_http(
            results, scenario,
            "Plan for 'Creá una clase Factura con el atributo fecha: Date y asociala con Cliente' returns "
            "intent PLAN with classifier.create + attribute.add + relationship.create",
            plan_response, 200, plan_factura,
            {"intent": "PLAN", "operationTypes": expected_types_sorted()},
            "POST /projects/{id}/assistant/plan",
        )

        plan_body = json_object(plan_response.get("body"))
        intent = None if plan_body is None else plan_body.get("intent")
        apply_ok = False
        primary_applied = False
        if plan_ok and intent == "PLAN":
            primary_applied = True
            apply_response = self.apply(self.client_a, plan_body, base_revision)

            def applied(body: object):
                summary = steps_status_summary(body)
                ok = (
                    summary["stepCount"] == 3
                    and all(status == "ACCEPTED" for status in summary["stepStatuses"])
                )
                return ok, summary

            apply_ok = check_http(results, scenario, "Apply of the Factura plan accepts every step",
                       apply_response, 200, applied,
                       {"stepStatuses": ["ACCEPTED", "ACCEPTED", "ACCEPTED"]},
                       "POST /projects/{id}/assistant/apply")
        else:
            # Historical note: before commit 10cd291, AssistantPlanValidator resolved attribute.add and
            # relationship.create targets against the CURRENT model, where Factura did not exist yet,
            # forcing REJECT with UNKNOWN_TARGET, so this deterministic split was the only way to keep the
            # journey exercisable. The validator now supports plan-local create references by forward
            # simulation, so the single-plan journey above is the contract; the split below is kept only
            # as a resilience fallback for when the primary plan check fails (which stays recorded).
            diagnostics = [
                str(diagnostic.get("code"))
                for diagnostic in ((plan_body or {}).get("diagnostics") or [])
                if isinstance(diagnostic, dict)
            ]
            print(
                "[FALLBACK] The primary single-plan Factura journey check failed above "
                f"(intent={intent}, diagnostics={diagnostics}); the failure stays recorded. Running the "
                "deterministic split plan (one plan+apply per operation, in dependency order) so the rest "
                "of the journey can still be measured.",
                flush=True,
            )
            if "PROVIDER_DISABLED" in str(diagnostics):
                print(
                    "[HINT] The plan endpoint answered PROVIDER_DISABLED: verify that "
                    "DIAGRAMMER_ASSISTANT_ENABLED/DIAGRAMMER_ASSISTANT_URL reached the backend container "
                    "and that llama-server is healthy on port 18099.",
                    flush=True,
                )
            self.apply_factura_in_three_plans(scenario)

        model = self.read_model(self.client_a)
        self.record_revision(model.get("body"))
        if primary_applied:
            self.record_mutation("escenario-1: apply plan Factura", apply_ok, model.get("body"))
        factura = find_classifier(model.get("body"), "Factura")
        cliente = find_classifier(model.get("body"), "Cliente")

        def model_has_factura(body: object):
            attribute = next(
                (item for item in attributes_of(factura) if item.get("name") == "fecha"), None
            )
            ok = (
                factura is not None
                and cliente is not None
                and attribute is not None
                and str(attribute.get("type", "")).upper() == "DATE"
                and relationship_between(body, get_string(factura, "id"), get_string(cliente, "id")) is not None
            )
            return ok, {
                "hasFactura": factura is not None,
                "hasCliente": cliente is not None,
                "fechaAttribute": None if attribute is None else {
                    "name": attribute.get("name"), "type": attribute.get("type"),
                },
                "relationshipFacturaCliente": relationship_between(
                    body, get_string(factura, "id"), get_string(cliente, "id")
                ) is not None,
                "revision": model_revision(body),
            }

        check_http(results, scenario, "Model read shows Factura with the fecha: Date attribute and the "
                   "relationship to Cliente", model, 200, model_has_factura,
                   {"hasFactura": True, "hasCliente": True, "fechaAttribute": {"name": "fecha", "type": "Date"},
                    "relationshipFacturaCliente": True},
                   "GET /projects/{id}/model")

    def apply_factura_in_three_plans(self, scenario: str) -> None:
        """Resilience fallback, used only when the primary single-plan check fails: one plan+apply per
        operation, in dependency order."""
        results = self.results
        steps = [
            ("Creá la clase Factura", "classifier.create", "Factura"),
            ("Agregá el atributo fecha: Date a la clase Factura", "attribute.add", "fecha"),
            ("Asociá Factura con Cliente", "relationship.create", None),
        ]
        for index, (utterance, expected_type, _marker) in enumerate(steps):
            plan_response, base_revision = self.plan(self.client_a, utterance)

            def plan_one(body: object, expected_type: str = expected_type):
                value = json_object(body)
                operations = None if value is None else value.get("operations")
                ops = [op for op in (operations or []) if isinstance(op, dict)]
                ok = value is not None and value.get("intent") == "PLAN" and len(ops) == 1 \
                    and str(ops[0].get("type")) == expected_type
                return ok, {
                    "intent": None if value is None else value.get("intent"),
                    "operationTypes": [str(op.get("type")) for op in ops],
                }

            check_http(results, scenario, f"Fallback plan {index + 1}/3 ({expected_type})",
                       plan_response, 200, plan_one, {"intent": "PLAN", "operationTypes": [expected_type]},
                       "POST /projects/{id}/assistant/plan")
            plan_body = json_object(plan_response.get("body"))
            if plan_body is None or plan_body.get("intent") != "PLAN":
                return
            apply_response = self.apply(self.client_a, plan_body, base_revision)

            def applied(body: object):
                summary = steps_status_summary(body)
                ok = summary["stepCount"] == 1 and all(
                    status == "ACCEPTED" for status in summary["stepStatuses"]
                )
                return ok, summary

            apply_ok = check_http(results, scenario, f"Fallback apply {index + 1}/3 ({expected_type})",
                       apply_response, 200, applied, {"stepStatuses": ["ACCEPTED"]},
                       "POST /projects/{id}/assistant/apply")
            model_after = self.read_model(self.client_a)
            self.record_revision(model_after.get("body"))
            self.record_mutation(f"escenario-1 fallback: apply {expected_type} {index + 1}/3", apply_ok,
                                 model_after.get("body"))

    # -- scenario 2: explain does not mutate --

    def scenario_2(self) -> None:
        scenario = "escenario-2"
        results = self.results
        model_before = self.read_model(self.client_a)
        revision_before = model_revision(model_before.get("body"))
        plan_response, _ = self.plan(self.client_a, "Explicá la relación entre Cliente y Factura")

        def explain_plan(body: object):
            value = json_object(body)
            operations = None if value is None else value.get("operations")
            ops = operations if isinstance(operations, list) else []
            ok = (
                value is not None
                and value.get("intent") in ("EXPLAIN", "CLARIFY")
                and len(ops) == 0
            )
            return ok, {
                "intent": None if value is None else value.get("intent"),
                "operationCount": len(ops),
            }

        check_http(results, scenario, "Plan for 'Explicá la relación entre Cliente y Factura' returns EXPLAIN "
                   "(or CLARIFY) with zero operations", plan_response, 200, explain_plan,
                   {"intent": "EXPLAIN|CLARIFY", "operationCount": 0},
                   "POST /projects/{id}/assistant/plan")

        model_after = self.read_model(self.client_a)
        revision_after = model_revision(model_after.get("body"))

        def unchanged(value: object):
            ok = model_revision(value) == revision_before
            return ok, {"revisionBefore": revision_before, "revisionAfter": model_revision(value)}

        check_http(results, scenario, "Model revision unchanged after the explain plan (apply is never invoked)",
                   model_after, 200, unchanged, {"revisionAfter": revision_before},
                   "GET /projects/{id}/model")
        # Note: by construction this harness never calls /assistant/apply in scenario 2; the plan itself
        # carries no operations, so there is nothing to apply.

    # -- scenario 3: ambiguity asks a question --

    def scenario_3(self) -> None:
        scenario = "escenario-3"
        results = self.results
        seed_response, _ = self.manual_classifier_create(
            self.client_a, "ClienteInternacional", "cliente-internacional"
        )

        def command_accepted(body: object):
            value = json_object(body)
            ok = value is not None and value.get("status") == "ACCEPTED"
            return ok, {
                "status": None if value is None else value.get("status"),
                "resultRevision": None if value is None else value.get("resultRevision"),
            }

        seed_ok = check_http(results, scenario, "A creates ClienteInternacional with a manual command", seed_response, 200,
                   command_accepted, {"status": "ACCEPTED"}, "POST /projects/{id}/commands")
        model_after_seed = self.read_model(self.client_a)
        self.record_revision(model_after_seed.get("body"))
        self.record_mutation("escenario-3: manual seed ClienteInternacional", seed_ok,
                             model_after_seed.get("body"))

        # The plan's own fresh model read is the revision anchor immediately before the "unchanged"
        # assertion (a plan never mutates).
        plan_response, revision_before = self.plan(self.client_a, "Eliminá la clase ClienteIntern")

        def clarify_plan(body: object):
            value = json_object(body)
            operations = None if value is None else value.get("operations")
            ops = operations if isinstance(operations, list) else []
            question = None if value is None else value.get("question")
            ok = (
                value is not None
                and value.get("intent") == "CLARIFY"
                and len(ops) == 0
                and nonempty_string(question)
            )
            return ok, {
                "intent": None if value is None else value.get("intent"),
                "operationCount": len(ops),
                "question": question,
            }

        check_http(results, scenario, "Plan for 'Eliminá la clase ClienteIntern' returns CLARIFY with a question",
                   plan_response, 200, clarify_plan,
                   {"intent": "CLARIFY", "operationCount": 0, "question": "non-empty"},
                   "POST /projects/{id}/assistant/plan")

        model_after = self.read_model(self.client_a)

        def unchanged(value: object):
            ok = model_revision(value) == revision_before
            return ok, {"revisionBefore": revision_before, "revisionAfter": model_revision(value)}

        check_http(results, scenario, "Model revision unchanged after the clarify plan", model_after, 200,
                   unchanged, {"revisionAfter": revision_before}, "GET /projects/{id}/model")

    # -- scenario 4: nonexistent target is rejected --

    def scenario_4(self) -> None:
        scenario = "escenario-4"
        results = self.results
        plan_response, revision_before = self.plan(self.client_a, "Eliminá la clase Inexistente")

        def reject_plan(body: object):
            value = json_object(body)
            operations = None if value is None else value.get("operations")
            ops = operations if isinstance(operations, list) else []
            ok = value is not None and value.get("intent") == "REJECT" and len(ops) == 0
            return ok, {
                "intent": None if value is None else value.get("intent"),
                "operationCount": len(ops),
            }

        check_http(results, scenario, "Plan for 'Eliminá la clase Inexistente' returns REJECT with zero operations",
                   plan_response, 200, reject_plan, {"intent": "REJECT", "operationCount": 0},
                   "POST /projects/{id}/assistant/plan")

        model_after = self.read_model(self.client_a)

        def unchanged(value: object):
            ok = model_revision(value) == revision_before
            return ok, {"revisionBefore": revision_before, "revisionAfter": model_revision(value)}

        check_http(results, scenario, "Model revision unchanged after the rejected plan", model_after, 200,
                   unchanged, {"revisionAfter": revision_before}, "GET /projects/{id}/model")

    # -- scenario 5: remote change between preview and confirm --

    def scenario_5(self) -> None:
        scenario = "escenario-5"
        results = self.results
        plan_response, base_revision = self.plan(self.client_a, "Creá la clase Marca")

        def plan_marca(body: object):
            value = json_object(body)
            operations = None if value is None else value.get("operations")
            ops = [op for op in (operations or []) if isinstance(op, dict)]
            ok = (
                value is not None
                and value.get("intent") == "PLAN"
                and len(ops) == 1
                and str(ops[0].get("type")) == "classifier.create"
                and (ops[0].get("args") or {}).get("name") == "Marca"
            )
            return ok, {
                "intent": None if value is None else value.get("intent"),
                "operationTypes": [str(op.get("type")) for op in ops],
                "createName": None if not ops else (ops[0].get("args") or {}).get("name"),
            }

        check_http(results, scenario, "A plans 'Creá la clase Marca' before B touches the model",
                   plan_response, 200, plan_marca, {"intent": "PLAN", "operationTypes": ["classifier.create"]},
                   "POST /projects/{id}/assistant/plan")
        plan_body = json_object(plan_response.get("body"))

        pais_response, _ = self.manual_classifier_create(self.client_b, "Pais", "pais")

        def command_accepted(body: object):
            value = json_object(body)
            ok = value is not None and value.get("status") == "ACCEPTED"
            return ok, {
                "status": None if value is None else value.get("status"),
                "resultRevision": None if value is None else value.get("resultRevision"),
            }

        pais_ok = check_http(results, scenario, "B (EDITOR) creates Pais with a manual command, bumping the revision",
                   pais_response, 200, command_accepted, {"status": "ACCEPTED"},
                   "POST /projects/{id}/commands")

        model_after_b = self.read_model(self.client_a)
        revision_after_b = self.record_revision(model_after_b.get("body"))
        self.record_mutation("escenario-5: B manual create Pais", pais_ok, model_after_b.get("body"))

        def b_state(body: object):
            ok = find_classifier(body, "Pais") is not None and model_revision(body) == revision_after_b
            return ok, {
                "hasPais": find_classifier(body, "Pais") is not None,
                "revision": model_revision(body),
            }

        check_http(results, scenario, "Model read confirms B's state before the stale apply", model_after_b, 200,
                   b_state, {"hasPais": True, "revision": revision_after_b}, "GET /projects/{id}/model")

        stale_apply = self.apply(self.client_a, plan_body, base_revision)

        def review_required(body: object):
            value = json_object(body)
            steps = None if value is None else value.get("steps")
            steps_list = steps if isinstance(steps, list) else []
            first = steps_list[0] if steps_list and isinstance(steps_list[0], dict) else None
            diagnostics = [] if first is None else first.get("diagnostics", [])
            codes = [
                str(diagnostic.get("code"))
                for diagnostic in (diagnostics if isinstance(diagnostics, list) else [])
                if isinstance(diagnostic, dict)
            ]
            ok = (
                value is not None
                and len(steps_list) == 1
                and first is not None
                and first.get("status") == "REJECTED"
                and "REVIEW_REQUIRED" in codes
                and value.get("finalRevision") == revision_after_b
            )
            return ok, {
                "stepCount": len(steps_list),
                "firstStatus": None if first is None else first.get("status"),
                "firstDiagnosticCodes": codes,
                "finalRevision": None if value is None else value.get("finalRevision"),
            }

        check_http(results, scenario, "A applies the stale plan and the first step is REJECTED with "
                   "REVIEW_REQUIRED", stale_apply, 200, review_required,
                   {"stepCount": 1, "firstStatus": "REJECTED", "firstDiagnosticCodes": ["REVIEW_REQUIRED"],
                    "finalRevision": revision_after_b},
                   "POST /projects/{id}/assistant/apply")

        model_final = self.read_model(self.client_a)

        def no_partial_mutation(body: object):
            ok = (
                find_classifier(body, "Marca") is None
                and find_classifier(body, "Pais") is not None
                and model_revision(body) == revision_after_b
            )
            return ok, {
                "hasMarca": find_classifier(body, "Marca") is not None,
                "hasPais": find_classifier(body, "Pais") is not None,
                "revision": model_revision(body),
            }

        check_http(results, scenario, "Model read matches B's state with nothing of the stale plan applied",
                   model_final, 200, no_partial_mutation,
                   {"hasMarca": False, "hasPais": True, "revision": revision_after_b},
                   "GET /projects/{id}/model")

    # -- scenario 6: undo/redo over assistant-created changes --

    def scenario_6(self) -> None:
        scenario = "escenario-6"
        results = self.results
        model_before = self.read_model(self.client_a)
        cliente = find_classifier(model_before.get("body"), "Cliente")
        cliente_id = get_string(cliente, "id")
        revision_before = model_revision(model_before.get("body"))

        plan_response, _ = self.plan(self.client_a, "Renombrá la clase Cliente a ClientePrincipal")

        def plan_rename(body: object):
            value = json_object(body)
            operations = None if value is None else value.get("operations")
            ops = [op for op in (operations or []) if isinstance(op, dict)]
            first = ops[0] if len(ops) == 1 else {}
            args = first.get("args") or {}
            new_name = args.get("name") or args.get("newName")
            ok = (
                value is not None
                and value.get("intent") == "PLAN"
                and len(ops) == 1
                and str(first.get("type")) == "classifier.rename"
                and first.get("targetId") == cliente_id
                and new_name == "ClientePrincipal"
            )
            return ok, {
                "intent": None if value is None else value.get("intent"),
                "operationTypes": [str(op.get("type")) for op in ops],
                "targetId": first.get("targetId"),
                "newName": new_name,
            }

        check_http(results, scenario, "Plan for 'Renombrá la clase Cliente a ClientePrincipal' resolves the "
                   "rename", plan_response, 200, plan_rename,
                   {"intent": "PLAN", "operationTypes": ["classifier.rename"], "targetId": cliente_id,
                    "newName": "ClientePrincipal"},
                   "POST /projects/{id}/assistant/plan")
        plan_body = json_object(plan_response.get("body"))
        if plan_body is None or plan_body.get("intent") != "PLAN":
            print("[NOT RUN] Undo/redo scenario not attempted because the rename plan failed; nothing was "
                  "skipped silently.", flush=True)
            return

        apply_response = self.apply(self.client_a, plan_body, revision_before)

        def applied(body: object):
            summary = steps_status_summary(body)
            ok = summary["stepCount"] == 1 and all(
                status == "ACCEPTED" for status in summary["stepStatuses"]
            )
            return ok, summary

        apply_ok = check_http(results, scenario, "Apply of the rename accepts its single step", apply_response, 200,
                   applied, {"stepStatuses": ["ACCEPTED"]}, "POST /projects/{id}/assistant/apply")

        renamed_model = self.read_model(self.client_a)
        self.record_revision(renamed_model.get("body"))
        self.record_mutation("escenario-6: apply rename", apply_ok, renamed_model.get("body"))

        def shows_rename(body: object):
            ok = find_classifier(body, "ClientePrincipal") is not None and find_classifier(body, "Cliente") is None
            return ok, {"hasClientePrincipal": find_classifier(body, "ClientePrincipal") is not None,
                        "hasCliente": find_classifier(body, "Cliente") is not None}

        def shows_revert(body: object):
            ok = find_classifier(body, "ClientePrincipal") is None and find_classifier(body, "Cliente") is not None
            return ok, {"hasClientePrincipal": find_classifier(body, "ClientePrincipal") is not None,
                        "hasCliente": find_classifier(body, "Cliente") is not None}

        check_http(results, scenario, "Model read shows the rename applied", renamed_model, 200, shows_rename,
                   {"hasClientePrincipal": True, "hasCliente": False}, "GET /projects/{id}/model")

        undo_response = self.undo(self.client_a)
        undo_ok = check_http(results, scenario, "A undoes the assistant rename", undo_response, 200,
                             lambda body: undo_accepted(body), {"status": "ACCEPTED"},
                             "POST /projects/{projectId}/undo")
        restored_model = self.read_model(self.client_a)
        self.record_revision(restored_model.get("body"))
        self.record_mutation("escenario-6: undo rename", undo_ok, restored_model.get("body"))
        check_http(results, scenario, "Model read shows the rename reverted",
                   restored_model, 200, shows_revert, {"hasClientePrincipal": False, "hasCliente": True},
                   "GET /projects/{id}/model")

        redo_response = self.redo(self.client_a)
        redo_ok = check_http(results, scenario, "A redoes the rename", redo_response, 200,
                   lambda body: undo_accepted(body), {"status": "ACCEPTED"},
                   "POST /projects/{projectId}/redo")
        redone_model = self.read_model(self.client_a)
        self.record_revision(redone_model.get("body"))
        self.record_mutation("escenario-6: redo rename", redo_ok, redone_model.get("body"))
        check_http(results, scenario, "Model read shows the rename restored",
                   redone_model, 200, shows_rename, {"hasClientePrincipal": True, "hasCliente": False},
                   "GET /projects/{id}/model")

        second_redo = self.redo(self.client_a)
        second_redo_ok = check_http(results, scenario, "Redo with nothing undone is rejected with NOTHING_TO_REDO",
                   second_redo, 200, lambda body: undo_rejected(body, "NOTHING_TO_REDO"),
                   {"status": "REJECTED", "code": "NOTHING_TO_REDO"},
                   "POST /projects/{projectId}/redo")
        # Rejected mutation: recorded as (mutation, accepted=False) only; no monotonic sample.
        # NOTE: second_redo_ok only proves the REJECTED response matched the expectation; acceptance
        # for the bookkeeping must come from the response body itself.
        second_redo_accepted, _ = undo_accepted(second_redo.get("body"))
        self.record_mutation("escenario-6: second redo (expected NOTHING_TO_REDO)", second_redo_accepted,
                             redone_model.get("body"))

        # The undo log is per-user and LIFO, so removing the assistant-created Factura requires undoing
        # A's later entries first (rename, ClienteInternacional.create, then the Factura steps).
        removed = False
        for attempt in range(1, 9):
            current_model = self.read_model(self.client_a)
            if find_classifier(current_model.get("body"), "Factura") is None:
                removed = True
                self.record_revision(current_model.get("body"))
                break
            undo_response = self.undo(self.client_a)

            def undo_step_ok(body: object, attempt: int = attempt):
                accepted, _ = undo_accepted(body)
                return accepted, {"attempt": attempt, "result": undo_summary(body)}

            undo_attempt_ok = check_http(results, scenario, f"Undo #{attempt} accepted while unwinding to the Factura entries",
                       undo_response, 200, undo_step_ok, {"status": "ACCEPTED"},
                       "POST /projects/{projectId}/undo")
            after_model = self.read_model(self.client_a)
            self.record_revision(after_model.get("body"))
            self.record_mutation(f"escenario-6: undo #{attempt} unwinding to Factura", undo_attempt_ok,
                                 after_model.get("body"))
        if not removed:
            check_http(
                results, scenario, "Factura removed through successive undos",
                {"status": None, "body": None, "error": "undo bound of 8 attempts exhausted"},
                200, lambda body: (False, {"body": body}), {"facturaRemoved": True},
                "POST /projects/{projectId}/undo (loop)",
            )
        else:
            report(results, scenario, "Factura removed through successive undos", True,
                   {"facturaRemoved": True}, {"facturaRemoved": True})

        # The seeded Cliente classifier is A's only remaining entry; undo it too so the log ends empty.
        undo_response = self.undo(self.client_a)
        final_undo_ok = check_http(results, scenario, "A undoes the seeded Cliente classifier", undo_response, 200,
                   lambda body: undo_accepted(body), {"status": "ACCEPTED"},
                   "POST /projects/{projectId}/undo")
        final_undo_model = self.read_model(self.client_a)
        self.record_revision(final_undo_model.get("body"))
        self.record_mutation("escenario-6: undo seeded Cliente", final_undo_ok, final_undo_model.get("body"))

        def cliente_removed(body: object):
            ok = find_classifier(body, "Cliente") is None
            return ok, {"hasCliente": find_classifier(body, "Cliente") is not None,
                        "revision": model_revision(body)}

        check_http(results, scenario, "Model read shows the seeded Cliente removed", final_undo_model, 200,
                   cliente_removed, {"hasCliente": False}, "GET /projects/{id}/model")

        final_undo = self.undo(self.client_a)
        final_nothing_ok = check_http(results, scenario, "Undo with an empty log is rejected with NOTHING_TO_UNDO",
                   final_undo, 200, lambda body: undo_rejected(body, "NOTHING_TO_UNDO"),
                   {"status": "REJECTED", "code": "NOTHING_TO_UNDO"},
                   "POST /projects/{projectId}/undo")
        # Rejected mutation: recorded as (mutation, accepted=False) only; no monotonic sample.
        # NOTE: final_nothing_ok only proves the REJECTED response matched the expectation; acceptance
        # for the bookkeeping must come from the response body itself.
        final_undo_accepted, _ = undo_accepted(final_undo.get("body"))
        self.record_mutation("escenario-6: undo with empty log (expected NOTHING_TO_UNDO)", final_undo_accepted,
                             final_undo_model.get("body"))

    # -- coherence: full re-read and monotonic revisions --

    def scenario_coherence(self) -> None:
        scenario = "coherencia"
        results = self.results
        model = self.read_model(self.client_a)
        last_recorded = self.revisions[-1] if self.revisions else None

        def re_read_coherent(body: object):
            names = {classifier.get("name") for classifier in classifiers_of(body)}
            # A's mutations were fully undone; B's Pais is the only classifier left.
            ok = names == {"Pais"} and model_revision(body) == last_recorded
            return ok, {
                "classifierNames": sorted(name for name in names if isinstance(name, str)),
                "revision": model_revision(body),
                "lastRecordedRevision": last_recorded,
            }

        check_http(results, scenario, "Full model re-read matches the final expected state", model, 200,
                   re_read_coherent, {"classifierNames": ["Pais"], "revision": last_recorded},
                   "GET /projects/{id}/model")

        # Monotonic sampling: ONLY mutations the harness verified as ACCEPTED (the (mutation, accepted?)
        # pairs in self.mutations), each sampled from the model read immediately after the mutation.
        # Model-behavior branches (fallback plans, expected rejections) are tolerated: rejected or
        # not-attempted mutations never produce a sample.
        sample_revisions = [revision for _, revision in self.revision_samples]
        strictly_increasing = all(
            later > earlier for earlier, later in zip(sample_revisions, sample_revisions[1:])
        )
        report(
            results,
            scenario,
            "Recorded revisions are strictly monotonic across every verified-accepted mutation",
            strictly_increasing and len(sample_revisions) >= 2,
            {"strictlyIncreasing": True, "acceptedSamples": len(sample_revisions)},
            {"revisionSamples": self.revision_samples, "mutationLog": self.mutations},
        )


def undo_accepted(body: object) -> tuple[bool, object]:
    value = json_object(body)
    ok = value is not None and value.get("status") == "ACCEPTED"
    return ok, {
        "status": None if value is None else value.get("status"),
        "resultRevision": None if value is None else value.get("resultRevision"),
    }


def undo_summary(body: object) -> object:
    value = json_object(body)
    return {
        "status": None if value is None else value.get("status"),
        "code": None if value is None else value.get("code"),
        "resultRevision": None if value is None else value.get("resultRevision"),
    }


def undo_rejected(body: object, expected_code: str) -> tuple[bool, object]:
    value = json_object(body)
    ok = value is not None and value.get("status") == "REJECTED" and value.get("code") == expected_code
    return ok, {
        "status": None if value is None else value.get("status"),
        "code": None if value is None else value.get("code"),
        "message": None if value is None else value.get("message"),
    }


def expected_types_sorted() -> list[str]:
    return sorted(["classifier.create", "attribute.add", "relationship.create"])


# --- orchestration --------------------------------------------------------------------


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the IA4 assistant acceptance journey against an isolated Compose stack and a local "
                    "llama-server."
    )
    parser.add_argument("--db-name", default="assistant_acceptance",
                        help="DIAGRAMMER_DB_NAME value (default: assistant_acceptance)")
    parser.add_argument("--db-user", default="harness",
                        help="DIAGRAMMER_DB_USER value (default: harness)")
    parser.add_argument("--db-password", default="harness",
                        help="DIAGRAMMER_DB_PASSWORD value (default: harness)")
    parser.add_argument("--http-port", type=int, default=18092,
                        help="DIAGRAMMER_HTTP_HOST_PORT (default: 18092)")
    parser.add_argument("--postgres-port", type=int, default=25434,
                        help="DIAGRAMMER_POSTGRES_HOST_PORT (default: 25434)")
    parser.add_argument("--request-timeout", type=float, default=15.0,
                        help="per-request timeout for non-assistant endpoints in seconds (default: 15)")
    parser.add_argument("--assistant-timeout", type=float, default=240.0,
                        help="per-request timeout for /assistant/plan and /assistant/apply in seconds "
                             "(default: 240; the backend provider waits up to 120s on llama-server)")
    parser.add_argument("--compose-timeout", type=int, default=1200,
                        help="timeout for each Compose up/down command in seconds (default: 1200)")
    parser.add_argument("--llama-binary", default=DEFAULT_LLAMA_BINARY,
                        help="path to the llama-server binary (default: %(default)s)")
    parser.add_argument("--llama-model", default=DEFAULT_LLAMA_MODEL,
                        help="path to the GGUF model file (default: %(default)s)")
    parser.add_argument("--llama-port", type=int, default=18099,
                        help="local llama-server port (default: 18099; DIAGRAMMER_ASSISTANT_URL targets it)")
    parser.add_argument("--llama-start-timeout", type=float, default=180.0,
                        help="seconds to wait for llama-server /health (default: 180)")
    return parser.parse_args(argv)


def valid_args(args: argparse.Namespace) -> bool:
    return (
        1 <= args.http_port <= 65535
        and 1 <= args.postgres_port <= 65535
        and args.http_port != args.postgres_port
        and 1 <= args.llama_port <= 65535
        and args.llama_port not in (args.http_port, args.postgres_port)
        and args.request_timeout > 0
        and args.assistant_timeout > 0
        and args.compose_timeout > 0
        and args.llama_start_timeout > 0
        and bool(args.db_name)
        and bool(args.db_user)
        and bool(args.db_password)
        and bool(args.llama_binary)
        and bool(args.llama_model)
    )


def print_not_measured() -> None:
    print("\n=== What was NOT measured ===", flush=True)
    print("- Assistant latency is informational only; no plan/apply duration is asserted.", flush=True)
    print("- SSE live delivery is not exercised (same convention as the collaboration harness).", flush=True)
    print("- Prompt-contract quality (ia4-01-prompt-v3) belongs to tools/ai-eval; this harness asserts the "
          "backend plan/apply/undo contract.", flush=True)
    print("- No browsers are opened.", flush=True)


def print_summary(results: list[tuple[str, bool]]) -> int:
    passed = sum(1 for _, ok in results if ok)
    failed = len(results) - passed
    exit_code = 0 if failed == 0 else 1
    groups: list[tuple[str, list[bool]]] = []
    for scenario, ok in results:
        if groups and groups[-1][0] == scenario:
            groups[-1][1].append(ok)
        else:
            groups.append((scenario, [ok]))
    print("\n=== Resumen por escenario ===", flush=True)
    for scenario, oks in groups:
        group_passed = sum(1 for ok in oks if ok)
        marker = "" if group_passed == len(oks) else "  <-- FAILED checks"
        print(f"{scenario:<14} {group_passed}/{len(oks)} passed{marker}", flush=True)
    print_not_measured()
    print(f"\n=== Assistant acceptance summary: {passed} passed, {failed} failed ===", flush=True)
    print(f"Planned exit code: {exit_code}", flush=True)
    return exit_code


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    results: list[tuple[str, bool]] = []
    print("=== What was measured ===", flush=True)

    # This label-scoped guard is deliberately the first Docker operation. Never adopt or remove leftovers.
    before_code, before_containers, before_error = project_container_list()
    before_command = f"docker ps -a --filter label={PROJECT_LABEL}"
    before_ok = before_code == 0 and before_error is None and len(before_containers) == 0
    report(
        results,
        "preflight",
        "Start-of-run labeled container count (hard refusal guard)",
        before_ok,
        {"count": 0, "label": PROJECT_LABEL},
        {"count": None if before_code != 0 else len(before_containers),
         "containers": before_containers, "error": before_error},
        before_command,
    )
    if before_code != 0 or before_error is not None:
        print(f"[REFUSED] Could not verify the {COMPOSE_PROJECT} label; refusing to start Compose.", flush=True)
        return print_summary(results)
    if before_containers:
        print("[REFUSED] Existing assistant-acceptance containers were found; refusing to start or tear down.",
              flush=True)
        return print_summary(results)

    if not valid_args(args):
        report(results, "preflight", "Validate harness arguments", False,
               "valid ports, timeouts, and non-empty DB and llama settings",
               {"dbName": args.db_name, "dbUser": args.db_user, "dbPassword": "<redacted>",
                "httpPort": args.http_port, "postgresPort": args.postgres_port, "llamaPort": args.llama_port,
                "requestTimeout": args.request_timeout, "assistantTimeout": args.assistant_timeout,
                "composeTimeout": args.compose_timeout, "llamaStartTimeout": args.llama_start_timeout})
        return print_summary(results)

    binary_ok = pathlib.Path(args.llama_binary).is_file()
    model_ok = pathlib.Path(args.llama_model).is_file()
    report(
        results,
        "preflight",
        "llama-server binary and model file exist on this machine",
        binary_ok and model_ok,
        {"binary": True, "model": True},
        {"binary": str(args.llama_binary), "binaryFound": binary_ok,
         "model": str(args.llama_model), "modelFound": model_ok},
    )
    llama_busy = port_is_occupied(args.llama_port)
    report(
        results,
        "preflight",
        f"Local llama port {args.llama_port} is free before starting llama-server",
        llama_busy is False,
        {"occupied": False},
        {"occupied": llama_busy},
    )
    if not (binary_ok and model_ok) or llama_busy is not False:
        print("[REFUSED] Local model runtime is not available as configured; refusing to start the stack.",
              flush=True)
        return print_summary(results)

    environment = compose_environment(args)
    suffix = hashlib.sha256(str(time.time_ns()).encode("ascii")).hexdigest()[:10]
    override_fd, override_path = tempfile.mkstemp(
        prefix="assistant-acceptance-override-", suffix=".yml", text=True
    )
    override_created = False
    llama_process: subprocess.Popen | None = None
    started = False
    try:
        with os.fdopen(override_fd, "w", encoding="utf-8") as handle:
            handle.write(OVERRIDE_YAML)
        override_created = True
        started = True
        up_ok = run_compose(
            results,
            "setup",
            "Start and build the assistant-acceptance stack (assistant enabled via override)",
            ["up", "-d", "--build", "--wait"],
            environment,
            override_path,
            args.compose_timeout,
        )

        during_code, during_containers, during_error = project_container_list()
        during_ok = during_code == 0 and during_error is None and len(during_containers) == 2
        report(
            results,
            "setup",
            "During-run labeled container count (database + backend)",
            during_ok,
            {"count": 2, "label": PROJECT_LABEL},
            {"count": None if during_code != 0 else len(during_containers),
             "containers": during_containers, "error": during_error},
            f"docker ps -a --filter label={PROJECT_LABEL}",
        )

        healthy = False
        if not up_ok:
            print("[NOT RUN] API journey not attempted because docker compose up failed; no API checks were "
                  "skipped silently.", flush=True)
        else:
            base_url = f"http://127.0.0.1:{args.http_port}"
            health = make_request(base_url, "GET", "/actuator/health", timeout=args.request_timeout)

            def health_up(body: object):
                value = json_object(body)
                ok = value is not None and value.get("status") == "UP"
                return ok, {"status": None if value is None else value.get("status")}

            healthy = check_http(results, "setup", "Waited backend health endpoint", health, 200, health_up,
                                 {"status": "UP"}, "GET /actuator/health")

        llama_ok = False
        if healthy:
            llama_process, spawn_error = start_llama(args)
            report(
                results,
                "setup",
                "Start llama-server locally (subprocess)",
                llama_process is not None,
                {"spawned": True, "port": args.llama_port, "contextTokens": 2048},
                {"spawned": llama_process is not None, "error": spawn_error},
                f"{args.llama_binary} --host 127.0.0.1 --port {args.llama_port} -m {args.llama_model} -c 2048",
            )
            if llama_process is not None:
                llama_ok, llama_observed = wait_for_llama_health(args.llama_port, args.llama_start_timeout)
                report(
                    results,
                    "setup",
                    "Waited llama-server /health",
                    llama_ok,
                    {"httpStatus": 200},
                    llama_observed,
                    f"GET http://127.0.0.1:{args.llama_port}/health",
                )

        if up_ok and healthy and llama_ok:
            journey = AssistantJourney(results, args, suffix)
            journey.setup()
            journey.scenario_1()
            journey.scenario_2()
            journey.scenario_3()
            journey.scenario_4()
            journey.scenario_5()
            journey.scenario_6()
            journey.scenario_coherence()
        else:
            print("[NOT RUN] Assistant journey not attempted because the stack, backend health, or the local "
                  "model runtime failed; nothing was skipped silently.", flush=True)
    except Exception as error:
        report(results, "preflight", "Harness execution did not raise an unreported exception", False,
               "journey checks complete or a prior check failure", {"error": f"{type(error).__name__}: {error}"})
    finally:
        if llama_process is not None:
            stopped = stop_llama(llama_process)
            report(results, "teardown", "Local llama-server stopped", stopped, {"stopped": True},
                   {"stopped": stopped, "exitCode": llama_process.poll()})
        if started:
            run_compose(
                results,
                "teardown",
                "Teardown assistant-acceptance stack (including volumes)",
                ["down", "-v"],
                environment,
                override_path,
                args.compose_timeout,
            )
            after_code, after_containers, after_error = project_container_list()
            after_ok = after_code == 0 and after_error is None and len(after_containers) == 0
            report(
                results,
                "teardown",
                "End-of-run labeled container count after teardown",
                after_ok,
                {"count": 0, "label": PROJECT_LABEL},
                {"count": None if after_code != 0 else len(after_containers),
                 "containers": after_containers, "error": after_error},
                f"docker ps -a --filter label={PROJECT_LABEL}",
            )
        if override_created:
            try:
                os.unlink(override_path)
            except OSError:
                pass

    return print_summary(results)


if __name__ == "__main__":
    sys.exit(main())
