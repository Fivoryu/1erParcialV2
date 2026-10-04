#!/usr/bin/env python3
"""Exercise the two-session collaboration journey against the real Compose stack."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
COMPOSE_FILE = ROOT / "docker-compose.yml"
COMPOSE_PROJECT = "collaboration-acceptance"
PROJECT_LABEL = f"com.docker.compose.project={COMPOSE_PROJECT}"
ZERO_UUID = "00000000-0000-0000-0000-000000000000"
CONFLICT_FIELDS = {
    "editorDisplayName",
    "targetType",
    "targetId",
    "reclaimable",
    "retryAfterSeconds",
}


def display(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)


def report(
    results: list[bool],
    step: str,
    ok: bool,
    expected: object,
    observed: object,
    command: str | None = None,
) -> None:
    if not isinstance(ok, bool):
        raise TypeError(f"the assertion for {step!r} produced {type(ok).__name__}, not bool")
    results.append(ok)
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
    results: list[bool],
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
            cwd=ROOT,
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


def compose_base() -> list[str]:
    return [
        "docker",
        "compose",
        "-p",
        COMPOSE_PROJECT,
        "-f",
        str(COMPOSE_FILE),
        "--project-directory",
        str(ROOT),
    ]


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
    results: list[bool],
    step: str,
    arguments: list[str],
    environment: dict[str, str],
    timeout: int,
) -> bool:
    command = compose_base() + arguments
    command_text = " ".join(command)
    try:
        completed = subprocess.run(
            command,
            cwd=ROOT,
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
    report(results, step, ok, {"exitCode": 0, "command": command_text}, observed, command_text)
    return ok


def backend_log_tail(args: argparse.Namespace) -> None:
    command = compose_base() + ["logs", "--no-color", "--tail", "120", "diagrammer"]
    try:
        result = subprocess.run(
            command,
            cwd=ROOT,
            env=compose_environment(args),
            capture_output=True,
            text=True,
            check=False,
            timeout=30,
        )
        output = (result.stdout or result.stderr).strip()
        if output:
            print(f"--- backend logs for the failed generation setup (project-scoped) ---\n{output[-16000:]}",
                  flush=True)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f"[DIAGNOSTIC ERROR] Could not read this project's backend logs: {type(error).__name__}: {error}",
              flush=True)


def json_object(value: object) -> dict[str, object] | None:
    return value if isinstance(value, dict) else None


def nonempty_string(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def nested_object(body: object, outer: str) -> dict[str, object] | None:
    value = json_object(body)
    if value is None:
        return None
    return json_object(value.get(outer))


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
    results: list[bool], client: HttpClient, password: str, timeout: float
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
        f"Register {client.label}",
        response,
        201,
        registered_user,
        {"id": "non-empty UUID", "username": client.username},
        "POST /auth/register",
    )


def record_login(
    results: list[bool], client: HttpClient, password: str, timeout: float
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
        f"Log in {client.label}",
        response,
        200,
        logged_in,
        {"token": "non-empty", "user.displayName": client.display_name},
        "POST /auth/login",
    )


def sse_socket(response):
    stream = getattr(response, "fp", None)
    raw = getattr(stream, "raw", None)
    return getattr(raw, "_sock", None)


def read_first_sse_event(response, timeout: float) -> dict[str, object]:
    """Read exactly one bounded SSE event block; never consume later/live events."""
    deadline = time.monotonic() + timeout
    socket = sse_socket(response)
    event_name = "message"
    data_lines: list[str] = []
    bytes_read = 0
    for _ in range(80):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(f"no initial SSE event arrived within {timeout:g}s")
        if socket is not None:
            socket.settimeout(remaining)
        raw_line = response.readline()
        if not raw_line:
            raise EOFError("SSE stream ended before its first event")
        bytes_read += len(raw_line)
        if bytes_read > 262144:
            raise ValueError("initial SSE event exceeded 256 KiB")
        line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
        if line == "":
            if data_lines:
                data = "\n".join(data_lines)
                try:
                    parsed: object = json.loads(data)
                except (json.JSONDecodeError, ValueError):
                    parsed = data
                return {"event": event_name, "data": parsed}
            event_name = "message"
            data_lines = []
            continue
        if line.startswith(":"):
            continue
        field, separator, value = line.partition(":")
        if separator and value.startswith(" "):
            value = value[1:]
        if field == "event":
            event_name = value
        elif field == "data":
            data_lines.append(value)
    raise ValueError("initial SSE event exceeded 80 lines")


def open_initial_sse(
    client: HttpClient, path: str, timeout: float
) -> dict[str, object]:
    headers = {"Accept": "text/event-stream"}
    if client.token:
        headers["Authorization"] = f"Bearer {client.token}"
    request = Request(f"{client.base_url}{path}", headers=headers, method="GET")
    response = None
    try:
        response = urlopen(request, timeout=timeout)
        status = response.getcode()
        event = read_first_sse_event(response, timeout)
        return {"status": status, "event": event, "response": response, "error": None}
    except HTTPError as error:
        try:
            body = parse_body(error.read())
        except OSError:
            body = None
        return {"status": error.code, "event": None, "response": None, "body": body, "error": None}
    except (URLError, TimeoutError, EOFError, OSError, ValueError) as error:
        if response is not None:
            response.close()
        return {
            "status": None if response is None else response.getcode(),
            "event": None,
            "response": None,
            "error": f"{type(error).__name__}: {error}",
        }


def sse_presence(event_result: dict[str, object]) -> tuple[dict[str, object], list[str]]:
    event = json_object(event_result.get("event"))
    data = None if event is None else json_object(event.get("data"))
    payload = None if data is None else json_object(data.get("payload"))
    entries = None if payload is None else payload.get("presence")
    people = entries if isinstance(entries, list) else []
    names = [
        str(person.get("displayName"))
        for person in people
        if isinstance(person, dict) and nonempty_string(person.get("displayName"))
    ]
    info = {
        "event": None if event is None else event.get("event"),
        "type": None if data is None else data.get("type"),
        "schemaVersion": None if data is None else data.get("schemaVersion"),
        "presenceCount": len(people),
        "displayNames": names,
    }
    return info, names


def check_sse_snapshot(
    results: list[bool],
    step: str,
    event_result: dict[str, object],
    expected_status: int,
    expected_names: set[str],
    command: str,
) -> bool:
    info, names = sse_presence(event_result)
    event = json_object(event_result.get("event"))
    ok = (
        event_result.get("status") == expected_status
        and event is not None
        and event.get("event") == "presence.updated"
        and isinstance(event.get("data"), dict)
        and json_object(event.get("data")) is not None
        and json_object(event.get("data")).get("type") == "presence.updated"
        and expected_names.issubset(set(names))
    )
    report(
        results,
        step,
        ok,
        {
            "httpStatus": expected_status,
            "event": "presence.updated",
            "payload.presence.displayName includes": sorted(expected_names),
        },
        {"httpStatus": event_result.get("status"), "snapshot": info, "error": event_result.get("error")},
        command,
    )
    return ok


def conflict_details(body: object) -> tuple[bool, object]:
    value = json_object(body)
    details = None if value is None else value.get("details")
    first = details[0] if isinstance(details, list) and details else None
    first_object = json_object(first)
    valid = first_object is not None and CONFLICT_FIELDS.issubset(first_object.keys())
    return valid, {
        "code": None if value is None else value.get("code"),
        "detailsFirst": first_object,
        "allRequiredKeysPresent": valid,
    }


def check_conflict_error(
    results: list[bool],
    step: str,
    response: dict[str, object],
    expected_status: int,
    expected_code: str,
    editor_name: str,
    command: str,
) -> bool:
    details_ok, details_observed = conflict_details(response.get("body"))
    body = json_object(response.get("body"))
    first = None
    if body is not None and isinstance(body.get("details"), list) and body["details"]:
        first = json_object(body["details"][0])
    body_ok = (
        body is not None
        and body.get("code") == expected_code
        and details_ok
        and first is not None
        and first.get("editorDisplayName") == editor_name
    )
    status_ok = response.get("status") == expected_status
    report(
        results,
        step,
        status_ok and body_ok,
        {
            "httpStatus": expected_status,
            "code": expected_code,
            "details[0].keys": sorted(CONFLICT_FIELDS),
            "details[0].editorDisplayName": editor_name,
        },
        {
            "httpStatus": response.get("status"),
            "body": summarize_body(response.get("body")),
            "detailsAssertion": details_observed,
            "error": response.get("error"),
        },
        command,
    )
    return status_ok and body_ok


def check_generation_conflict(
    results: list[bool], response: dict[str, object], editor_name: str, command: str
) -> None:
    body = json_object(response.get("body"))
    result = None if body is None else json_object(body.get("result"))
    keys_present = result is not None and CONFLICT_FIELDS.issubset(result.keys())
    ok = (
        response.get("status") == 200
        and body is not None
        and body.get("outcome") == "REJECTED"
        and body.get("code") == "EXPORT_BLOCKED_EDITING"
        and keys_present
        and result is not None
        and result.get("editorDisplayName") == editor_name
    )
    report(
        results,
        "B generation attempt is rejected while A holds the lease",
        ok,
        {
            "httpStatus": 200,
            "outcome": "REJECTED",
            "code": "EXPORT_BLOCKED_EDITING",
            "result.keys": sorted(CONFLICT_FIELDS),
            "result.editorDisplayName": editor_name,
        },
        {
            "httpStatus": response.get("status"),
            "body": summarize_body(response.get("body")),
            "allRequiredKeysPresent": keys_present,
            "error": response.get("error"),
        },
        command,
    )


def run_journey(results: list[bool], args: argparse.Namespace, suffix: str) -> None:
    base_url = f"http://127.0.0.1:{args.http_port}"
    timeout = args.request_timeout
    password = "CollaborationAcceptance-2026"
    client_a = HttpClient("session A (creator)", "Session A", f"harness-a-{suffix}", base_url)
    client_b = HttpClient("session B (invitee)", "Session B", f"harness-b-{suffix}", base_url)

    record_registration(results, client_a, password, timeout)
    record_registration(results, client_b, password, timeout)
    record_login(results, client_a, password, timeout)
    record_login(results, client_b, password, timeout)
    distinct_tokens = (
        nonempty_string(client_a.token)
        and nonempty_string(client_b.token)
        and client_a.token != client_b.token
    )
    report(
        results,
        "Session A and session B hold distinct bearer tokens",
        distinct_tokens,
        {"two non-empty, distinct tokens": True},
        {"sessionATokenPresent": bool(client_a.token), "sessionBTokenPresent": bool(client_b.token),
         "tokensDiffer": client_a.token != client_b.token},
    )

    project_response = client_a.request(
        "POST",
        "/projects",
        {"name": f"Collaboration acceptance {suffix}", "description": "Two-session API acceptance run."},
        timeout,
    )

    def project_created(body: object):
        value = json_object(body)
        ok = value is not None and nonempty_string(value.get("id")) and nonempty_string(value.get("name"))
        return ok, {"idPresent": value is not None and nonempty_string(value.get("id")),
                    "name": None if value is None else value.get("name")}

    check_http(results, "A creates a project", project_response, 201, project_created,
               {"id": "non-empty UUID", "name": "non-empty"}, "POST /projects")
    project_id = get_string(project_response.get("body"), "id")
    project_path = f"/projects/{project_id}"

    invitation_response = client_a.request(
        "POST",
        f"{project_path}/invitations",
        {"username": client_b.username, "role": "EDITOR"},
        timeout,
    )

    def invitation_created(body: object):
        value = json_object(body)
        ok = (
            value is not None
            and nonempty_string(value.get("id"))
            and value.get("role") == "EDITOR"
            and value.get("status") == "PENDING"
        )
        return ok, {"idPresent": value is not None and nonempty_string(value.get("id")),
                    "role": None if value is None else value.get("role"),
                    "status": None if value is None else value.get("status")}

    check_http(results, "A invites B as EDITOR", invitation_response, 201, invitation_created,
               {"id": "non-empty UUID", "role": "EDITOR", "status": "PENDING"},
               "POST /projects/{projectId}/invitations")
    invitation_id = get_string(invitation_response.get("body"), "id")
    accepted_response = client_b.request(
        "POST", f"{project_path}/invitations/{invitation_id}/accept", None, timeout
    )

    def invitation_accepted(body: object):
        value = json_object(body)
        ok = (
            value is not None
            and value.get("displayName") == client_b.display_name
            and value.get("role") == "EDITOR"
            and value.get("status") == "ACTIVE"
        )
        return ok, {"displayName": None if value is None else value.get("displayName"),
                    "role": None if value is None else value.get("role"),
                    "status": None if value is None else value.get("status")}

    check_http(results, "B accepts the invitation", accepted_response, 200, invitation_accepted,
               {"displayName": client_b.display_name, "role": "EDITOR", "status": "ACTIVE"},
               "POST /projects/{projectId}/invitations/{invitationId}/accept")

    # A classifier is needed for a real generation run. The API fixture uses the same accepted command shape.
    initial_model = client_a.request("GET", f"{project_path}/model", None, timeout)

    def initial_model_ready(body: object):
        value = json_object(body)
        ok = value is not None and nonempty_string(value.get("id")) and isinstance(value.get("revision"), int)
        return ok, {"modelId": None if value is None else value.get("id"),
                    "revision": None if value is None else value.get("revision")}

    check_http(results, "Read the initial model identity before preparing the generation fixture", initial_model,
               200, initial_model_ready, {"id": "model UUID", "revision": "integer"},
               "GET /projects/{id}/model")
    model_id = get_string(initial_model.get("body"), "id")
    initial_model_body = json_object(initial_model.get("body"))
    base_revision = (initial_model_body.get("revision") if initial_model_body is not None
                     and isinstance(initial_model_body.get("revision"), int) else 1)
    operation_id = uuid_from_seed(f"{suffix}-classifier-operation")
    classifier_id = uuid_from_seed(f"{suffix}-classifier-id")
    command_response = client_a.request(
        "POST",
        f"{project_path}/commands",
        {
            "operationId": operation_id,
            "type": "classifier.create",
            "projectId": project_id,
            "modelId": model_id,
            "baseRevision": base_revision,
            "targets": [],
            "preconditions": [],
            "payload": {"id": classifier_id, "name": "AcceptanceClass", "kind": "CLASS"},
            "origin": "MANUAL",
            "idempotencyKey": operation_id,
        },
        timeout,
    )

    def classifier_created(body: object):
        value = json_object(body)
        ok = value is not None and value.get("status") == "ACCEPTED"
        return ok, {"status": None if value is None else value.get("status"),
                    "resultRevision": None if value is None else value.get("resultRevision")}

    check_http(results, "A creates a classifier so the seed generation has a supported model", command_response,
               200, classifier_created, {"status": "ACCEPTED"},
               "POST /projects/{id}/commands")

    # Re-read after the accepted command: generation freezes the updated version, not the initial one.
    model_response = client_a.request("GET", f"{project_path}/model", None, timeout)

    def model_ready(body: object):
        value = json_object(body)
        ok = (value is not None and value.get("id") == model_id
              and nonempty_string(value.get("versionId"))
              and isinstance(value.get("revision"), int)
              and value.get("revision", 0) > base_revision)
        return ok, {"modelId": None if value is None else value.get("id"),
                    "versionId": None if value is None else value.get("versionId"),
                    "revision": None if value is None else value.get("revision")}

    check_http(results, "Read the updated model version for the seed run", model_response, 200,
               model_ready, {"id": model_id, "versionId": "updated version UUID",
                             "revision": f"> {base_revision}"},
               "GET /projects/{id}/model")
    version_id = get_string(model_response.get("body"), "versionId")

    # The API provisions its V1 profile and template when this real route is first read.
    profile_response = client_a.request("GET", f"{project_path}/generation-profiles", None, timeout)

    def profile_ready(body: object):
        profiles = body if isinstance(body, list) else []
        first = profiles[0] if profiles else None
        value = json_object(first)
        ok = value is not None and nonempty_string(value.get("profileId")) and nonempty_string(value.get("templateId"))
        return ok, {"profileCount": len(profiles),
                    "profileId": None if value is None else value.get("profileId"),
                    "templateId": None if value is None else value.get("templateId")}

    check_http(results, "Provision/read the project generation profile and template", profile_response, 200,
               profile_ready, "non-empty list with profileId and templateId", "GET /projects/{id}/generation-profiles")
    profiles = profile_response.get("body") if isinstance(profile_response.get("body"), list) else []
    first_profile = json_object(profiles[0]) if profiles else None
    profile_id = ZERO_UUID if first_profile is None else get_string(first_profile, "profileId")
    template_id = ZERO_UUID if first_profile is None else get_string(first_profile, "templateId")

    target_response = client_a.request(
        "POST",
        f"{project_path}/generation-targets",
        {
            "targetKey": f"acceptance-{suffix}",
            "name": "Collaboration acceptance target",
            "databaseEngine": "POSTGRESQL",
            "databaseName": "collaboration_acceptance",
            "databaseSchema": "public",
            "connectionConfigRef": None,
        },
        timeout,
    )

    def target_created(body: object):
        value = json_object(body)
        target = None if value is None else json_object(value.get("result"))
        ok = (value is not None and value.get("outcome") == "ACCEPTED"
              and value.get("code") == "TARGET_CREATED"
              and target is not None and nonempty_string(target.get("targetId")))
        return ok, {"outcome": None if value is None else value.get("outcome"),
                    "code": None if value is None else value.get("code"),
                    "targetId": None if target is None else target.get("targetId")}

    check_http(results, "A creates a generation target for the pre-lease seed run", target_response, 200,
               target_created, {"outcome": "ACCEPTED", "code": "TARGET_CREATED", "result.targetId": "UUID"},
               "POST /projects/{projectId}/generation-targets")
    target_obj = nested_object(target_response.get("body"), "result")
    target_id = get_string(target_obj, "targetId") if target_obj is not None else ZERO_UUID

    generation_payload = {
        "modelId": model_id,
        "versionId": version_id,
        "targetId": target_id,
        "profileId": profile_id,
        "templateId": template_id,
        "idempotencyKey": f"collaboration-acceptance-{suffix}-seed",
    }
    seed_generation = client_a.request(
        "POST", f"{project_path}/generations", generation_payload, timeout
    )

    def seed_run_created(body: object):
        value = json_object(body)
        run = None if value is None else json_object(value.get("result"))
        ok = value is not None and value.get("status") != "ERROR" and run is not None and nonempty_string(run.get("runId"))
        return ok, {"outcome": None if value is None else value.get("outcome"),
                    "code": None if value is None else value.get("code"),
                    "runId": None if run is None else run.get("runId"),
                    "state": None if run is None else run.get("state")}

    check_http(results, "A creates a real generation run before acquiring the lease", seed_generation, 200,
               seed_run_created, {"result.runId": "non-empty UUID", "final state": "not constrained"},
               "POST /projects/{projectId}/generations")
    if isinstance(seed_generation.get("status"), int) and seed_generation["status"] >= 500:
        backend_log_tail(args)
    run_obj = nested_object(seed_generation.get("body"), "result")
    run_id = get_string(run_obj, "runId") if run_obj is not None else ZERO_UUID

    acquire_a = client_a.request("POST", f"{project_path}/editing-lease", {}, timeout)

    def lease_names_a(body: object):
        envelope = json_object(body)
        lease = None if envelope is None else json_object(envelope.get("lease"))
        ok = lease is not None and lease.get("editorDisplayName") == client_a.display_name
        return ok, {"editorDisplayName": None if lease is None else lease.get("editorDisplayName")}

    check_http(results, "A acquires the project editing lease", acquire_a, 200, lease_names_a,
               {"lease.editorDisplayName": client_a.display_name}, "POST /projects/{projectId}/editing-lease")

    blocked_generation_payload = dict(generation_payload)
    blocked_generation_payload["idempotencyKey"] = f"collaboration-acceptance-{suffix}-blocked"
    blocked_generation = client_b.request(
        "POST", f"{project_path}/generations", blocked_generation_payload, timeout
    )
    check_generation_conflict(results, blocked_generation, client_a.display_name,
                              "POST /projects/{projectId}/generations")

    artifact_response = client_b.request(
        "GET", f"{project_path}/generations/{run_id}/artifacts", None, timeout
    )
    check_conflict_error(results, "B artifact download is blocked by A's lease", artifact_response, 409,
                         "EXPORT_BLOCKED_EDITING", client_a.display_name,
                         "GET /projects/{projectId}/generations/{runId}/artifacts")

    xmi_response = client_b.request("POST", f"{project_path}/xmi/export", None, timeout)
    check_conflict_error(results, "B XMI export is blocked by A's lease", xmi_response, 409,
                         "EXPORT_BLOCKED_EDITING", client_a.display_name,
                         "POST /projects/{projectId}/xmi/export")

    message_text = f"C25 collaboration message {suffix}"
    message_response = client_a.request(
        "POST", f"{project_path}/messages", {"body": message_text}, timeout
    )

    def message_created(body: object):
        value = json_object(body)
        ok = (value is not None and value.get("body") == message_text
              and value.get("authorDisplayName") == client_a.display_name
              and nonempty_string(value.get("id")))
        return ok, {"idPresent": value is not None and nonempty_string(value.get("id")),
                    "body": None if value is None else value.get("body"),
                    "authorDisplayName": None if value is None else value.get("authorDisplayName")}

    check_http(results, "A posts a chat message", message_response, 201, message_created,
               {"body": message_text, "authorDisplayName": client_a.display_name, "id": "UUID"},
               "POST /projects/{id}/messages")
    message_id = get_string(message_response.get("body"), "id")

    history_response = client_b.request("GET", f"{project_path}/messages", None, timeout)

    def history_contains_message(body: object):
        messages = body if isinstance(body, list) else []
        matching = [message for message in messages
                    if isinstance(message, dict)
                    and message.get("id") == message_id
                    and message.get("body") == message_text
                    and message.get("authorDisplayName") == client_a.display_name]
        return bool(matching), {"messageCount": len(messages), "matchingMessageCount": len(matching),
                                "authorDisplayName": client_a.display_name if matching else None}

    check_http(results, "B reads A's persisted chat message and author", history_response, 200,
               history_contains_message,
               {"message.id": message_id, "message.body": message_text,
                "message.authorDisplayName": client_a.display_name},
               "GET /projects/{id}/messages")

    sse_connections: list[object] = []
    try:
        events_path = f"{project_path}/events"
        first_a = open_initial_sse(client_a, events_path, timeout)
        if first_a.get("response") is not None:
            sse_connections.append(first_a["response"])
        check_sse_snapshot(results, "A opens the events route and reads its initial presence snapshot",
                           first_a, 200, {client_a.display_name}, "GET /projects/{id}/events (SSE)")

        first_b = open_initial_sse(client_b, events_path, timeout)
        if first_b.get("response") is not None:
            sse_connections.append(first_b["response"])
        check_sse_snapshot(results, "B opens the events route and reads the initial two-person roster",
                           first_b, 200, {client_a.display_name, client_b.display_name},
                           "GET /projects/{id}/events (SSE)")

        presence_a = client_a.request("POST", f"{project_path}/presence", {}, timeout)
        presence_b = client_b.request("POST", f"{project_path}/presence", {}, timeout)

        roster_a = open_initial_sse(client_a, events_path, timeout)
        try:
            check_presence_report_and_roster(
                results, "A reports presence and reads the two-person initial roster", presence_a, roster_a,
                client_a.display_name, {client_a.display_name, client_b.display_name},
                "POST /projects/{id}/presence + GET /projects/{id}/events (SSE)",
            )
        finally:
            close_sse(roster_a)

        roster_b = open_initial_sse(client_b, events_path, timeout)
        try:
            check_presence_report_and_roster(
                results, "B reports presence and reads the two-person initial roster", presence_b, roster_b,
                client_b.display_name, {client_a.display_name, client_b.display_name},
                "POST /projects/{id}/presence + GET /projects/{id}/events (SSE)",
            )
        finally:
            close_sse(roster_b)
    finally:
        for connection in sse_connections:
            close_response(connection)

    current_lease = client_b.request("GET", f"{project_path}/editing-lease", None, timeout)

    def current_names_a(body: object):
        envelope = json_object(body)
        lease = None if envelope is None else json_object(envelope.get("lease"))
        ok = lease is not None and lease.get("editorDisplayName") == client_a.display_name
        return ok, {"editorDisplayName": None if lease is None else lease.get("editorDisplayName")}

    check_http(results, "B reads the current lease and sees A as editor", current_lease, 200,
               current_names_a, {"lease.editorDisplayName": client_a.display_name},
               "GET /projects/{projectId}/editing-lease")

    release_a = client_a.request("DELETE", f"{project_path}/editing-lease", None, timeout)

    def lease_is_null(body: object):
        envelope = json_object(body)
        ok = envelope is not None and "lease" in envelope and envelope.get("lease") is None
        return ok, {"leaseFieldPresent": envelope is not None and "lease" in envelope,
                    "lease": None if envelope is None else envelope.get("lease")}

    check_http(results, "A releases the lease", release_a, 200, lease_is_null,
               {"lease": None}, "DELETE /projects/{projectId}/editing-lease")

    acquire_b = client_b.request("POST", f"{project_path}/editing-lease", {}, timeout)

    def lease_names_b(body: object):
        envelope = json_object(body)
        lease = None if envelope is None else json_object(envelope.get("lease"))
        ok = lease is not None and lease.get("editorDisplayName") == client_b.display_name
        return ok, {"editorDisplayName": None if lease is None else lease.get("editorDisplayName")}

    check_http(results, "B acquires the released lease", acquire_b, 200, lease_names_b,
               {"lease.editorDisplayName": client_b.display_name},
               "POST /projects/{projectId}/editing-lease")

    release_b = client_b.request("DELETE", f"{project_path}/editing-lease", None, timeout)
    check_http(results, "B releases the lease before teardown", release_b, 200, lease_is_null,
               {"lease": None}, "DELETE /projects/{projectId}/editing-lease")


def close_response(response: object) -> None:
    close = getattr(response, "close", None)
    if callable(close):
        try:
            close()
        except OSError:
            pass


def close_sse(result: dict[str, object]) -> None:
    close_response(result.get("response"))


def check_presence_report_and_roster(
    results: list[bool],
    step: str,
    presence_response: dict[str, object],
    roster_response: dict[str, object],
    reporting_name: str,
    expected_names: set[str],
    command: str,
) -> None:
    snapshot_info, observed_names = sse_presence(roster_response)
    event = json_object(roster_response.get("event"))
    event_data = None if event is None else json_object(event.get("data"))
    roster_ok = (
        roster_response.get("status") == 200
        and event is not None
        and event.get("event") == "presence.updated"
        and event_data is not None
        and event_data.get("type") == "presence.updated"
        and expected_names.issubset(set(observed_names))
    )
    post_ok = presence_response.get("status") == 204 and presence_response.get("body") is None
    report(
        results,
        step,
        post_ok and roster_ok,
        {
            "presencePostStatus": 204,
            "presencePostBody": "empty (NO_CONTENT)",
            "initialSseStatus": 200,
            "event": "presence.updated",
            "payload.presence.displayName includes": sorted(expected_names),
        },
        {
            "presencePost": {"httpStatus": presence_response.get("status"),
                             "body": presence_response.get("body"),
                             "reportingUser": reporting_name},
            "initialSse": {"httpStatus": roster_response.get("status"), "snapshot": snapshot_info,
                           "error": roster_response.get("error")},
        },
        command,
    )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the two-session collaboration acceptance journey against an isolated Compose stack."
    )
    parser.add_argument("--db-name", default="collaboration_acceptance",
                        help="DIAGRAMMER_DB_NAME value (default: collaboration_acceptance)")
    parser.add_argument("--db-user", default="harness",
                        help="DIAGRAMMER_DB_USER value (default: harness)")
    parser.add_argument("--db-password", default="harness",
                        help="DIAGRAMMER_DB_PASSWORD value (default: harness)")
    parser.add_argument("--http-port", type=int, default=18091,
                        help="DIAGRAMMER_HTTP_HOST_PORT (default: 18091)")
    parser.add_argument("--postgres-port", type=int, default=25433,
                        help="DIAGRAMMER_POSTGRES_HOST_PORT (default: 25433)")
    parser.add_argument("--request-timeout", type=float, default=15.0,
                        help="per-request and initial SSE event timeout in seconds (default: 15)")
    parser.add_argument("--compose-timeout", type=int, default=1200,
                        help="timeout for each Compose up/down command in seconds (default: 1200)")
    return parser.parse_args(argv)


def valid_args(args: argparse.Namespace) -> bool:
    return (
        1 <= args.http_port <= 65535
        and 1 <= args.postgres_port <= 65535
        and args.http_port != args.postgres_port
        and args.request_timeout > 0
        and args.compose_timeout > 0
        and bool(args.db_name)
        and bool(args.db_user)
        and bool(args.db_password)
    )


def print_not_measured() -> None:
    print("\n=== What was NOT measured ===", flush=True)
    print("- No browsers are opened; the two-browser visible cut is covered by the web integration tests.", flush=True)
    print("- ongoing/live SSE delivery is not measured here; only the initial snapshot on connect is read to obtain the presence roster", flush=True)
    print("- Lease TTL expiry timing is not measured here (unit/integration territory with a controlled clock).", flush=True)
    print("- The EA round-trip remains unverifiable (GAP-07); it is owned by the XMI harness.", flush=True)


def print_summary(results: list[bool]) -> int:
    passed = sum(1 for result in results if result)
    failed = len(results) - passed
    exit_code = 0 if failed == 0 else 1
    print_not_measured()
    print(f"\n=== Collaboration acceptance summary: {passed} passed, {failed} failed ===", flush=True)
    print(f"Planned exit code: {exit_code}", flush=True)
    return exit_code


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    results: list[bool] = []
    print("=== What was measured ===", flush=True)

    # This label-scoped guard is deliberately the first Docker operation. Never adopt or remove leftovers.
    before_code, before_containers, before_error = project_container_list()
    before_command = "docker ps -a --filter label=com.docker.compose.project=collaboration-acceptance"
    before_ok = before_code == 0 and before_error is None and len(before_containers) == 0
    report(
        results,
        "Start-of-run labeled container count (hard refusal guard)",
        before_ok,
        {"count": 0, "label": PROJECT_LABEL},
        {"count": None if before_code != 0 else len(before_containers),
         "containers": before_containers, "error": before_error},
        before_command,
    )
    if before_code != 0 or before_error is not None:
        print("[REFUSED] Could not verify the collaboration-acceptance label; refusing to start Compose.", flush=True)
        return print_summary(results)
    if before_containers:
        print("[REFUSED] Existing collaboration-acceptance containers were found; refusing to start or tear down.",
              flush=True)
        return print_summary(results)

    if not valid_args(args):
        report(results, "Validate harness arguments", False, "valid ports, timeouts, and non-empty DB settings",
               {"dbName": args.db_name, "dbUser": args.db_user, "dbPassword": "<redacted>",
                "httpPort": args.http_port, "postgresPort": args.postgres_port,
                "requestTimeout": args.request_timeout, "composeTimeout": args.compose_timeout})
        return print_summary(results)

    environment = compose_environment(args)
    started = False
    suffix = hashlib.sha256(str(time.time_ns()).encode("ascii")).hexdigest()[:10]
    try:
        started = True
        up_ok = run_compose(
            results,
            "Start and build the collaboration-acceptance stack",
            ["up", "-d", "--build", "--wait"],
            environment,
            args.compose_timeout,
        )

        during_code, during_containers, during_error = project_container_list()
        during_ok = during_code == 0 and during_error is None and len(during_containers) == 2
        report(
            results,
            "During-run labeled container count (database + backend)",
            during_ok,
            {"count": 2, "label": PROJECT_LABEL},
            {"count": None if during_code != 0 else len(during_containers),
             "containers": during_containers, "error": during_error},
            "docker ps -a --filter label=com.docker.compose.project=collaboration-acceptance",
        )

        if not up_ok:
            print("[NOT RUN] API journey not attempted because docker compose up failed; no API checks were skipped silently.",
                  flush=True)
        else:
            base_url = f"http://127.0.0.1:{args.http_port}"
            health = make_request(base_url, "GET", "/actuator/health", timeout=args.request_timeout)

            def health_up(body: object):
                value = json_object(body)
                ok = value is not None and value.get("status") == "UP"
                return ok, {"status": None if value is None else value.get("status")}

            healthy = check_http(results, "Waited backend health endpoint", health, 200, health_up,
                                 {"status": "UP"}, "GET /actuator/health")
            if healthy:
                run_journey(results, args, suffix)
            else:
                print("[NOT RUN] API journey not attempted because the backend health response was not UP.", flush=True)
    except Exception as error:
        report(results, "Harness execution did not raise an unreported exception", False,
               "journey checks complete or a prior check failure", {"error": f"{type(error).__name__}: {error}"})
    finally:
        if started:
            run_compose(
                results,
                "Teardown collaboration-acceptance stack (including volumes)",
                ["down", "-v"],
                environment,
                args.compose_timeout,
            )
            after_code, after_containers, after_error = project_container_list()
            after_ok = after_code == 0 and after_error is None and len(after_containers) == 0
            report(
                results,
                "End-of-run labeled container count after teardown",
                after_ok,
                {"count": 0, "label": PROJECT_LABEL},
                {"count": None if after_code != 0 else len(after_containers),
                 "containers": after_containers, "error": after_error},
                "docker ps -a --filter label=com.docker.compose.project=collaboration-acceptance",
            )

    return print_summary(results)


if __name__ == "__main__":
    sys.exit(main())
