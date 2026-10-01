#!/usr/bin/env python3
"""Generate backends from a running diagrammer and deploy them without a human hand.

This harness proves the increment's visible cut end to end, from the outside:

  1. it creates a project and two related classes through the public command API;
  2. it creates two independent destinations and starts one generation per destination;
  3. it downloads each archive and checks the bytes against the hash the run persisted;
  4. it compiles each generated project offline (`mvn -o -B package`);
  5. it deploys each destination with its own Docker Compose project and its own PostgreSQL,
     asking Docker not to pull anything (`--pull never`);
  6. it waits for `health` `UP` and runs the CRUD smoke of both generated resources, including the
     foreign-key resolution, a missing parent (400, never 500) and an unknown id (404);
  7. it verifies the destinations do not share a database or a volume, and that both answer at once;
  8. it tears everything down and **verifies** that nothing survived: the process it started, the
     containers it created, and the container count back at its initial value.

It needs a running diagrammer and a packaged backend JAR (or a diagrammer already listening). Use
`tools/acceptance/acceptance.py --keep-running` to bring one up and print its base URL, or start the
packaged JAR yourself.

On Windows a live JVM locks the JAR it runs from, and the failure surfaces later as an unrelated
`repackage` error. That is why teardown verifies process death by marker and by PID rather than
assuming it.

Usage:
    python tools/generation-acceptance/acceptance_generation.py --base-url http://127.0.0.1:PORT
    python tools/generation-acceptance/acceptance_generation.py --base-url URL --keep-running
    python tools/generation-acceptance/acceptance_generation.py --stop <RUN_ID>
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
STATE_DIR = HERE / ".state"
WORK_ROOT = HERE / ".work"
POSTGRES_IMAGE = "postgres:16-alpine"
RUNTIME_BASE_IMAGE = "eclipse-temurin:21-jre-alpine"
OWNER_ID = "00000000-0000-0000-0000-000000000001"
OWNER_SUBJECT = "generation-acceptance"
HTTP_PORT_BASE = 19001
POSTGRES_PORT_BASE = 25433
HEALTH_TIMEOUT = 240
BUILD_TIMEOUT = 900
DIAGRAMMER_TIMEOUT = 180


class Failure(Exception):
    """Signals the first failed acceptance assertion."""


def display(value):
    return value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, sort_keys=True)


def report(results, step, ok, expected, got):
    if not isinstance(ok, bool):
        # `a == b and c` returns `c`, so an assertion can print OK and still be counted as a failure. The
        # deployment harness caught two of those in itself; this refuses the shape outright.
        raise TypeError(f"the assertion for {step!r} produced {type(ok).__name__}, not a bool: {ok!r}")
    results.append(ok)
    print(f"[{'OK' if ok else 'FAIL'}] {step}", flush=True)
    print(f"  expected: {display(expected)}", flush=True)
    print(f"  got:      {display(got)}", flush=True)


def require(results, step, ok, expected, got):
    report(results, step, ok, expected, got)
    if not ok:
        raise Failure(step)


def run_process(command, cwd=None, timeout=120, env=None):
    return subprocess.run(command, cwd=cwd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout, env=env, check=False, shell=False)


def resolve(executable):
    found = shutil.which(executable)
    if not found:
        raise Failure(f"{executable} was not found in PATH")
    return found


class GenerationAcceptance:
    def __init__(self, base_url, keep, destinations):
        self.results = []
        self.run_id = str(uuid.uuid4())
        # The token always starts with a letter. The emitter derives the Compose project, the service names and
        # the volume name from the target key's slug, and a segment that starts with a digit gets a `target`
        # prefix, so a token beginning with a digit would make every derived name differ from its target key.
        self.token = "t" + self.run_id.replace("-", "")[:5]
        self.base_url = base_url.rstrip("/")
        self.api_token = None
        self.auth_username = f"generation-{self.run_id.replace('-', '')}"
        self.keep = keep
        self.destination_count = destinations
        self.docker = shutil.which("docker")
        self.maven = shutil.which("mvn")
        self.containers_before = None
        self.compose_projects = []
        self.destinations = []
        self.ports = []
        self.manifest_path = STATE_DIR / f"{self.run_id}.json"

    # ---------------------------------------------------------------- API

    def _send_request(self, method, path, payload=None, timeout=60, token=None):
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        headers = {"Content-Type": "application/json", "Accept": "application/json"} if data is not None \
            else {"Accept": "application/json"}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(self.base_url + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError) as error:
            # A refused or unreachable diagrammer is an assertion failure with a readable reason, never an
            # unexpected crash: the report has to name what could not be reached.
            return None, json.dumps({"transport": f"{type(error).__name__}: {error}"}).encode("utf-8")

    def authenticate(self):
        if self.api_token is not None:
            return
        password = uuid.uuid4().hex
        status, body = self._send_request("POST", "/auth/register", {
            "username": self.auth_username, "password": password, "displayName": "Generation Acceptance",
        })
        require(self.results, "Register generation acceptance user", status == 201,
                "HTTP 201", {"HTTP": status, "body": body.decode("utf-8", errors="replace")[:400]})

        status, body = self._send_request("POST", "/auth/login", {
            "username": self.auth_username, "password": password,
        })
        require(self.results, "Log in generation acceptance user", status == 200,
                "HTTP 200 with a bearer token", {"HTTP": status})
        try:
            token = json.loads(body).get("token")
        except (json.JSONDecodeError, AttributeError):
            token = None
        require(self.results, "Read generation acceptance bearer token",
                isinstance(token, str) and bool(token), "a non-empty bearer token",
                "present" if token else "missing")
        self.api_token = token

    def request(self, method, path, payload=None, timeout=60):
        self.authenticate()
        return self._send_request(method, path, payload, timeout, self.api_token)

    def json_request(self, method, path, payload=None, timeout=60):
        status, body = self.request(method, path, payload, timeout)
        try:
            return status, json.loads(body)
        except json.JSONDecodeError:
            return status, {"raw": body.decode("utf-8", errors="replace")[:400]}

    def command(self, project_id, model_id, revision, parent, kind, payload, targets):
        operation_id = str(uuid.uuid4())
        envelope = {
            "operationId": operation_id, "type": kind,
            "author": {"userId": OWNER_ID, "externalSubject": OWNER_SUBJECT},
            "projectId": project_id, "modelId": model_id, "baseRevision": revision, "targets": targets,
            "preconditions": [{"kind": "model_valid", "expect": True}, {"kind": "permission", "expect": "EDITOR"}],
            "payload": payload, "origin": "MANUAL", "causalParentOperationId": parent,
            "idempotencyKey": f"generation-acceptance:{self.run_id}:{operation_id}",
        }
        status, result = self.json_request("POST", f"/projects/{project_id}/commands", envelope)
        accepted = (status == 200 and result.get("status") == "ACCEPTED"
                    and result.get("resultRevision") == revision + 1)
        require(self.results, f"Apply {kind} at revision {revision}", accepted,
                {"HTTP": 200, "status": "ACCEPTED", "resultRevision": revision + 1},
                {"HTTP": status, "body": result})
        return result["resultRevision"], operation_id

    # --------------------------------------------------------------- Docker

    def docker_run(self, *args, timeout=180):
        return run_process([self.docker, *args], timeout=timeout)

    def container_ids(self):
        result = self.docker_run("ps", "-aq", "--no-trunc")
        if result.returncode:
            raise Failure(f"docker ps failed: {(result.stderr or result.stdout).strip()}")
        return [line.strip() for line in result.stdout.splitlines() if line.strip()]

    def compose(self, project_dir, *args, timeout=BUILD_TIMEOUT):
        return run_process([self.docker, "compose", *args], cwd=project_dir, timeout=timeout)

    # ------------------------------------------------------------ Preflight

    def preflight(self):
        require(self.results, "Locate Docker", self.docker is not None, "docker available in PATH",
                self.docker or "not found")
        require(self.results, "Locate Maven", self.maven is not None,
                "mvn available in PATH; the generated project builds from the local cache",
                self.maven or "not found")
        # The baseline is taken before anything else, so a failure at any later check still closes with a
        # teardown assertion that can actually fail.
        if self.containers_before is None:
            self.containers_before = len(self.container_ids())
        print(f"[INFO] Containers before: {self.containers_before}", flush=True)

        image = self.docker_run("image", "inspect", "--format={{.Id}}", POSTGRES_IMAGE)
        image_id = image.stdout.strip()
        require(self.results, "Inspect the local PostgreSQL image",
                image.returncode == 0 and image_id.startswith("sha256:"),
                f"local {POSTGRES_IMAGE}; the run never pulls",
                image_id or (image.stderr or image.stdout).strip())

        runtime = self.docker_run("image", "inspect", "--format={{.Id}}", RUNTIME_BASE_IMAGE)
        runtime_id = runtime.stdout.strip()
        require(self.results, "Inspect the generated runtime base image",
                runtime.returncode == 0 and runtime_id.startswith("sha256:"),
                f"local {RUNTIME_BASE_IMAGE}; otherwise the generated image needs one declared download",
                runtime_id or (runtime.stderr or runtime.stdout).strip())

        status, health = self.json_request("GET", "/actuator/health", timeout=20)
        require(self.results, "Reach the running diagrammer", status == 200 and health.get("status") == "UP",
                {"HTTP": 200, "status": "UP"},
                {"HTTP": status, "base": self.base_url, "body": health,
                 "hint": "start one with tools/acceptance/acceptance.py --keep-running"})

        used = []
        for index in range(self.destination_count):
            http_port = HTTP_PORT_BASE + index
            postgres_port = POSTGRES_PORT_BASE + index
            for port in (http_port, postgres_port):
                if not self.port_is_free(port):
                    used.append(port)
            self.ports.append((http_port, postgres_port))
        require(self.results, "Reserve the destination host ports", not used,
                f"ports {HTTP_PORT_BASE}..{HTTP_PORT_BASE + self.destination_count - 1} and "
                f"{POSTGRES_PORT_BASE}.. free",
                used or "all free")

    @staticmethod
    def port_is_free(port):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.settimeout(1)
            return sock.connect_ex(("127.0.0.1", port)) != 0

    # ------------------------------------------------------------- The model

    def create_model(self):
        status, project = self.json_request("POST", "/projects", {
            "name": f"Generation acceptance {self.token}",
            "description": "Created by tools/generation-acceptance"})
        require(self.results, "Create the project over HTTP", status == 201 and "id" in project,
                "HTTP 201 with a project id", {"HTTP": status, "body": project})
        project_id = project["id"]

        status, model = self.json_request("GET", f"/projects/{project_id}/model")
        initial_version = model.get("versionId")
        require(self.results, "Read the initial model",
                status == 200 and bool(initial_version),
                {"HTTP": 200, "classifiers": 0, "versionId": "a UUID"},
                {"HTTP": status, "classifiers": len(model.get("classifiers", [])),
                 "versionId": initial_version, "body": model if status != 200 else "as expected"})
        model_id, revision = model["id"], model["revision"]

        customer, order = str(uuid.uuid4()), str(uuid.uuid4())
        email, display_name, total, describe, relationship = (str(uuid.uuid4()) for _ in range(5))
        parent = None
        revision, parent = self.command(project_id, model_id, revision, parent, "classifier.create",
                                        {"id": customer, "name": "Customer", "kind": "CLASS"}, [])
        revision, parent = self.command(project_id, model_id, revision, parent, "classifier.create",
                                        {"id": order, "name": "Order", "kind": "CLASS"}, [])
        revision, parent = self.command(project_id, model_id, revision, parent, "attribute.add",
                                        {"id": email, "name": "email", "type": {"primitiveName": "String"},
                                         "visibility": "PRIVATE", "multiplicity": "EXACTLY_ONE"},
                                        [{"scope": "CLASSIFIER", "id": customer}])
        revision, parent = self.command(project_id, model_id, revision, parent, "attribute.add",
                                        {"id": display_name, "name": "displayName",
                                         "type": {"primitiveName": "String"}, "visibility": "PRIVATE",
                                         "multiplicity": "EXACTLY_ONE", "defaultValue": "Guest"},
                                        [{"scope": "CLASSIFIER", "id": customer}])
        revision, parent = self.command(project_id, model_id, revision, parent, "attribute.add",
                                        {"id": total, "name": "total",
                                         "type": {"primitiveName": "BigDecimal"}, "visibility": "PRIVATE",
                                         "multiplicity": "EXACTLY_ONE"},
                                        [{"scope": "CLASSIFIER", "id": order}])
        revision, parent = self.command(project_id, model_id, revision, parent, "operation.add",
                                        {"id": describe, "name": "describe",
                                         "returnType": {"primitiveName": "String"}, "visibility": "PUBLIC"},
                                        [{"scope": "CLASSIFIER", "id": customer}])
        revision, parent = self.command(project_id, model_id, revision, parent, "relationship.create", {
            "id": relationship, "kind": "ASSOCIATION", "name": "orders",
            "source": {"classifierId": customer, "role": "customer", "multiplicity": "EXACTLY_ONE",
                       "navigability": "NAVIGABLE"},
            "target": {"classifierId": order, "role": "orders", "multiplicity": "ZERO_OR_MANY",
                       "navigability": "NAVIGABLE"},
        }, [{"scope": "CLASSIFIER", "id": customer}, {"scope": "CLASSIFIER", "id": order}])

        status, model = self.json_request("GET", f"/projects/{project_id}/model")
        version_id = model.get("versionId")
        # Every accepted command moves the model's current version, so the identity to generate from is the
        # one read after the model is complete, never the one read before it was built.
        require(self.results, "Read the built model back and take its current version",
                status == 200 and isinstance(version_id, str) and bool(version_id)
                and version_id != initial_version,
                {"HTTP": 200, "versionId": "a new UUID, different from the initial one"},
                {"HTTP": status, "initial": initial_version, "current": version_id,
                 "revision": model.get("revision")})
        print(f"[INFO] Model {model_id} at revision {model['revision']}, version {version_id}", flush=True)
        return project_id, model_id, version_id

    # -------------------------------------------------------- Destinations

    def create_destinations(self, project_id):
        names = [("reporting", "Reporting service"), ("billing", "Billing service"),
                 ("auditing", "Auditing service"), ("notification", "Notification service")]
        destinations = []
        for index in range(self.destination_count):
            stem, label = names[index % len(names)]
            target_key = f"{stem}-service-{self.token}"
            database = f"{stem}_{self.token}_db"
            status, outcome = self.json_request("POST", f"/projects/{project_id}/generation-targets", {
                "targetKey": target_key, "name": f"{label} {self.token}", "databaseEngine": "POSTGRESQL",
                "databaseName": database, "databaseSchema": "public", "connectionConfigRef": None})
            accepted = (status == 200 and outcome.get("outcome") == "ACCEPTED"
                        and bool(outcome.get("result")))
            require(self.results, f"Create destination {target_key}", accepted,
                    {"HTTP": 200, "outcome": "ACCEPTED"},
                    {"HTTP": status, "body": outcome})
            destinations.append({"targetKey": target_key, "name": f"{label} {self.token}",
                                 "database": database, "targetId": outcome["result"]["targetId"]})
        return destinations

    # ------------------------------------------------------- Generate

    def generate(self, project_id, model_id, version_id, destination):
        status, profiles = self.json_request("GET", f"/projects/{project_id}/generation-profiles")
        require(self.results, "Read the published generation profile",
                status == 200 and isinstance(profiles, list) and bool(profiles),
                "HTTP 200 with at least one profile carrying its exact revision and template",
                {"HTTP": status, "body": profiles})
        profile = profiles[0]
        status, outcome = self.json_request("POST", f"/projects/{project_id}/generations", {
            "modelId": model_id, "versionId": version_id, "targetId": destination["targetId"],
            "profileId": profile["profileId"], "templateId": profile["templateId"],
            "idempotencyKey": f"generation-acceptance:{self.run_id}:{destination['targetKey']}"})
        run = outcome.get("result") or {}
        require(self.results, f"Start and finish the generation for {destination['targetKey']}",
                status == 200 and run.get("state") == "SUCCEEDED",                {"HTTP": 200, "state": "SUCCEEDED", "blockingDiagnostics": 0},
                {"HTTP": status, "outcome": outcome.get("outcome"), "code": outcome.get("code"),
                 "state": run.get("state"),
                 "diagnostics": [{"code": d["code"], "feature": d["feature"], "blocking": d["blocking"]}
                                 for d in run.get("diagnostics", [])]})
        status, body = self.request("GET", f"/projects/{project_id}/generations/{run['runId']}/artifacts")
        digest = hashlib.sha256(body).hexdigest()
        is_zip = body[:2] == b"PK"
        require(self.results, f"Download the archive for {destination['targetKey']}",
                status == 200 and is_zip and digest == run.get("artifactZipHash"),
                {"HTTP": 200, "starts with PK": True, "sha256": run.get("artifactZipHash")},
                {"HTTP": status, "starts with PK": is_zip, "sha256": digest, "bytes": len(body)})

        archive = self.work_dir(destination["targetKey"]) / "generated.zip"
        archive.parent.mkdir(parents=True, exist_ok=True)
        archive.write_bytes(body)
        with zipfile.ZipFile(archive) as bundle:
            entries = bundle.namelist()
            bundle.extractall(archive.parent)
        destination.update({"runId": run["runId"], "artifactSetHash": run["artifactSetHash"],
                            "artifactZipHash": run["artifactZipHash"], "zipBytes": len(body),
                            "artifacts": entries, "profile": profile})
        required = {"docker-compose.yml", ".env.example", "Dockerfile", "pom.xml", "README.md",
                    "src/main/resources/db/migration/V1__init.sql"}
        missing = sorted(required - set(entries))
        print(f"[INFO] {destination['targetKey']}: {len(entries)} artifacts, {len(body)} bytes, "
              f"artifactSetHash={run['artifactSetHash']}", flush=True)
        require(self.results, f"The archive carries the deployment artifacts for {destination['targetKey']}",
                not missing, sorted(required), missing or "all present")

    # ---------------------------------------------------------- Deploy

    def deploy(self, destination, ports):
        http_port, postgres_port = ports
        project_dir = self.work_dir(destination["targetKey"])
        database = destination["database"]

        packaged = run_process([self.maven, "-o", "-B", "package"], cwd=project_dir, timeout=BUILD_TIMEOUT)
        jars = sorted((project_dir / "target").glob("*.jar"))
        require(self.results, f"Compile the generated project for {destination['targetKey']} offline",
                packaged.returncode == 0 and bool(jars),
                "exit 0 with a packaged jar, no network",
                {"exit": packaged.returncode, "jars": [jar.name for jar in jars],
                 "tail": (packaged.stdout or "")[-300:] if packaged.returncode else ""})

        (project_dir / ".env").write_text(
            f"GENERATED_DB_NAME={database}\n"
            f"GENERATED_DB_USER={database}\n"
            f"GENERATED_DB_PASSWORD=acceptance_{self.token}\n"
            f"GENERATED_HTTP_HOST_PORT={http_port}\n"
            f"GENERATED_POSTGRES_HOST_PORT={postgres_port}\n", encoding="utf-8")

        rendered = self.compose(project_dir, "config", timeout=180)
        require(self.results, f"The emitted Compose file resolves for {destination['targetKey']}",
                rendered.returncode == 0, "docker compose config exit 0",
                {"exit": rendered.returncode, "tail": (rendered.stderr or rendered.stdout)[-300:]})

        up = self.compose(project_dir, "up", "-d", "--build", "--pull", "never")
        self.compose_projects.append(destination["targetKey"])
        require(self.results, f"Deploy {destination['targetKey']} with its own database",
                up.returncode == 0, "docker compose up exit 0, no image pulled",
                {"exit": up.returncode, "tail": (up.stderr or up.stdout)[-400:]})
        self.record_stack(destination, project_dir)

        health = None
        deadline = time.monotonic() + HEALTH_TIMEOUT
        last = "health has not answered"
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{http_port}/actuator/health", timeout=5) as answer:
                    last = answer.read().decode("utf-8", errors="replace")
                    if answer.status == 200 and json.loads(last).get("status") == "UP":
                        health = last
                        break
            except (urllib.error.URLError, ConnectionError, TimeoutError, json.JSONDecodeError) as error:
                last = f"{type(error).__name__}: {error}"
            time.sleep(2)
        require(self.results, f"{destination['targetKey']} answers health UP on its own base",
                health is not None, {"HTTP": 200, "status": "UP"}, last)
        destination.update({"httpPort": http_port, "postgresPort": postgres_port, "health": health})

    def smoke(self, destination):
        base = f"http://127.0.0.1:{destination['httpPort']}"

        def call(method, path, payload=None):
            data = json.dumps(payload).encode("utf-8") if payload is not None else None
            request = urllib.request.Request(base + path, data=data, method=method,
                                             headers={"Content-Type": "application/json"} if data else {})
            try:
                with urllib.request.urlopen(request, timeout=20) as answer:
                    return answer.status, answer.read().decode("utf-8", errors="replace")
            except urllib.error.HTTPError as error:
                return error.code, error.read().decode("utf-8", errors="replace")

        status, customer = call("POST", "/api/customer", {"email": "acceptance@example.org",
                                                          "displayName": "Acceptance"})
        require(self.results, f"POST /api/customer on {destination['targetKey']}", status == 201,
                "HTTP 201 with the created resource", {"HTTP": status, "body": customer})
        customer_id = json.loads(customer)["id"]

        status, order = call("POST", "/api/order", {"total": 19.99, "customerId": customer_id})
        created = json.loads(order) if status == 201 else {}
        require(self.results, f"POST /api/order resolves the parent reference on {destination['targetKey']}",
                status == 201 and created.get("customerId") == customer_id,
                {"HTTP": 201, "customerId": customer_id}, {"HTTP": status, "body": order})

        status, body = call("GET", f"/api/order/{created.get('id')}")
        require(self.results, f"GET /api/order/{{id}} keeps the reference on {destination['targetKey']}",
                status == 200 and json.loads(body).get("customerId") == customer_id,
                {"HTTP": 200, "customerId": customer_id}, {"HTTP": status, "body": body})

        status, body = call("GET", "/api/order")
        require(self.results, f"GET /api/order lists on {destination['targetKey']}",
                status == 200 and len(json.loads(body)) == 1, "HTTP 200 with one element",
                {"HTTP": status, "body": body})

        status, body = call("POST", "/api/order", {"total": 1.0,
                                                  "customerId": "11111111-1111-4111-8111-111111111111"})
        require(self.results, f"A missing parent is 400 and never 500 on {destination['targetKey']}",
                status == 400, {"HTTP": 400}, {"HTTP": status, "body": body})

        status, body = call("GET", "/api/order/22222222-2222-4222-8222-222222222222")
        require(self.results, f"An unknown id is 404 on {destination['targetKey']}",
                status == 404, {"HTTP": 404}, {"HTTP": status, "body": body})

        destination["customerId"] = customer_id

    def record_stack(self, destination, project_dir):
        """Read the deployed stack's real names from Docker instead of deriving them.

        The Compose project, the service names and the data volume name all come from the emitted slug, which
        is not always the target key. Reading them back removes any chance of a check that agrees with itself
        because both sides were guessed the same way.
        """
        listed = self.compose(project_dir, "ps", "-q", timeout=120)
        containers = [line.strip() for line in listed.stdout.splitlines() if line.strip()]
        require(self.results, f"The {destination['targetKey']} stack reports its containers",
                listed.returncode == 0 and bool(containers),
                "docker compose ps lists at least one container",
                {"exit": listed.returncode, "containers": containers,
                 "tail": (listed.stderr or listed.stdout)[-300:]})
        labels = self.docker_run("inspect", "--format",
                                 "{{index .Config.Labels \"com.docker.compose.project\"}}|"
                                 "{{index .Config.Labels \"com.docker.compose.service\"}}", *containers)
        projects, services = set(), []
        for line in labels.stdout.splitlines():
            if "|" not in line:
                continue
            project, service = line.strip().split("|", 1)
            if project:
                projects.add(project)
            if service:
                services.append(service)
        mounts = self.docker_run("inspect", "--format", "{{range .Mounts}}{{.Name}} {{end}}", *containers)
        volumes = sorted({token for token in mounts.stdout.split() if token})
        require(self.results, f"The {destination['targetKey']} stack declares one Compose project",
                len(projects) == 1, "exactly one Compose project", sorted(projects))
        destination.update({"project": sorted(projects)[0], "services": sorted(set(services)),
                            "volumes": volumes})

    def db_service(self, destination):
        """The database service of the deployed stack, as the emitted Compose file names it."""
        return next((name for name in destination.get("services", []) if name.endswith("-db")), None)

    # -------------------------------------------------------- Isolation

    def verify_isolation(self, destinations):
        # The two destinations must differ in identity and never share a database or a volume.
        all_volumes = {line.strip() for line in self.docker_run("volume", "ls", "--format",
                                                               "{{.Name}}").stdout.splitlines()
                       if line.strip()}
        for destination in destinations:
            project_dir = self.work_dir(destination["targetKey"])
            service = self.db_service(destination)
            require(self.results, f"{destination['targetKey']} declares a database service ending in -db",
                    service is not None, "a service named <slug>-db",
                    destination.get("services") or "none recorded")
            # The data volume must exist for real: this is the positive control for the teardown check that
            # asserts its absence afterwards.
            require(self.results, f"{destination['targetKey']} declares a real data volume",
                    bool(destination["volumes"]) and set(destination["volumes"]).issubset(all_volumes),
                    "at least one volume belonging to this destination exists",
                    {"declared": destination["volumes"],
                     "present": sorted(set(destination["volumes"]) & all_volumes)})

            probe = self.compose(project_dir, "exec", "-T", service, "psql",
                                 "-U", destination["database"], "-d", destination["database"], "-tAc",
                                 "select current_database() || '|' || (select count(*) from customer);",
                                 timeout=180)
            value = probe.stdout.strip()
            expected = f"{destination['database']}|1"
            require(self.results, f"{destination['targetKey']} uses its own database", value == expected,
                    expected, value or (probe.stderr or probe.stdout).strip())

        if len(destinations) >= 2:
            left, right = destinations[0], destinations[1]
            require(self.results, "Two destinations carry two different set hashes",
                    left["artifactSetHash"] != right["artifactSetHash"],
                    "two independent artifact sets",
                    {"left": left["artifactSetHash"], "right": right["artifactSetHash"]})
            require(self.results, "Two destinations carry two different database names",
                    left["database"] != right["database"], "two independent databases",
                    {"left": left["database"], "right": right["database"]})
            left_customer, right_customer = left["customerId"], right["customerId"]
            require(self.results, "Two destinations hold two different rows",
                    left_customer != right_customer, "two independent identities",
                    {"left": left_customer, "right": right_customer})
            left_volumes, right_volumes = set(left["volumes"]), set(right["volumes"])
            require(self.results, "Two destinations use two different volumes",
                    bool(left_volumes) and bool(right_volumes) and not (left_volumes & right_volumes),
                    "two disjoint, existing data volumes",
                    {"left": sorted(left_volumes), "right": sorted(right_volumes),
                     "shared": sorted(left_volumes & right_volumes)})
            live = []
            for destination in destinations:
                try:
                    with urllib.request.urlopen(
                            f"http://127.0.0.1:{destination['httpPort']}/api/customer", timeout=10) as answer:
                        if answer.status == 200:
                            live.append(destination["targetKey"])
                except (urllib.error.URLError, ConnectionError, TimeoutError):
                    pass
            require(self.results, "Both destinations answer at the same time",
                    sorted(live) == sorted(d["targetKey"] for d in destinations),
                    sorted(d["targetKey"] for d in destinations), live)

    # --------------------------------------------------------- Teardown

    def work_dir(self, target_key):
        return WORK_ROOT / self.run_id / target_key

    def write_manifest(self):
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        self.manifest_path.write_text(json.dumps({
            "runId": self.run_id, "status": "running" if self.keep else "finished",
            "baseUrl": self.base_url, "containersBefore": self.containers_before,
            "composeProjects": self.compose_projects,
            "destinations": [{"targetKey": destination["targetKey"], "database": destination["database"],
                              "project": destination.get("project"),
                              "services": destination.get("services"),
                              "volumes": destination.get("volumes", [])}
                             for destination in self.destinations],
            "ports": self.ports,
        }, indent=2) + "\n", encoding="utf-8")

    def cleanup(self):
        if self.keep:
            self.write_manifest()
            print(f"[INFO] Left running by request. Stop it with:", flush=True)
            print(f"  python tools/generation-acceptance/acceptance_generation.py --stop {self.run_id}",
                  flush=True)
            return
        stop_destinations(self.results, self.docker, self.compose_projects,
                          {project: self.work_dir(project) for project in self.compose_projects})
        self.verify_cleanup()

    def verify_cleanup(self):
        after = len(self.container_ids())
        require(self.results, "Container count returned to its initial value",
                self.containers_before is not None and after == self.containers_before,
                self.containers_before, after)
        recorded = getattr(self, "destinations", [])
        volumes = {line.strip() for line in self.docker_run("volume", "ls", "--format",
                                                            "{{.Name}}").stdout.splitlines()
                   if line.strip()}
        survivors = sorted({name for destination in recorded for name in destination.get("volumes", [])}
                           & volumes)
        require(self.results, "No data volume survived the teardown",
                not survivors, "every volume recorded during this run is gone",
                survivors or "none")
        declared = sorted({destination["project"] for destination in recorded})
        present = self.docker_run("ps", "-a", "--filter", "label=com.docker.compose.project",
                                  "--format", "{{.Label \"com.docker.compose.project\"}}")
        projects = {line.strip() for line in present.stdout.splitlines() if line.strip()}
        require(self.results, "No destination container survived",
                not (set(declared) & projects), "none of this run's Compose projects remain",
                sorted(set(declared) & projects) or "none")


def stop_destinations(results, docker, projects, directories):
    for project in projects:
        directory = directories.get(project)
        if not directory or not directory.exists():
            report(results, f"Stop {project}", False, "the recorded work directory exists",
                   str(directory))
            continue
        down = run_process([docker, "compose", "down", "-v", "--remove-orphans"], cwd=directory,
                           timeout=600)
        report(results, f"Stop {project}", down.returncode == 0,
               "docker compose down -v exit 0, container and volume removed",
               {"exit": down.returncode, "tail": (down.stderr or down.stdout)[-300:]})


def stop_run(run_id):
    """Stop a run left by --keep-running, using only the resources it recorded."""
    manifest_path = STATE_DIR / f"{run_id}.json"
    if not manifest_path.exists():
        print(f"[FAIL] No recorded run {run_id} in {STATE_DIR}", flush=True)
        return 1
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    docker = shutil.which("docker")
    results = []
    projects = manifest.get("composeProjects", [])
    directories = {project: WORK_ROOT / manifest["runId"] / project for project in projects}
    recorded_volumes = [volume for destination in manifest.get("destinations", [])
                        for volume in destination.get("volumes", [])]
    print(f"[INFO] Stopping run {run_id}: {len(projects)} destinations, "
          f"{len(recorded_volumes)} recorded volumes", flush=True)

    # Positive control first: a volume that was never created cannot prove anything by its absence.
    present = {line.strip() for line in run_process([docker, "volume", "ls", "--format",
                                                     "{{.Name}}"]).stdout.splitlines() if line.strip()}
    report(results, "The recorded volumes exist before the stop",
           bool(recorded_volumes) and set(recorded_volumes).issubset(present),
           sorted(recorded_volumes) or "at least one recorded volume",
           {"present": sorted(set(recorded_volumes) & present)})

    stop_destinations(results, docker, projects, directories)

    current = [line.strip() for line in run_process([docker, "ps", "-aq", "--no-trunc"]).stdout.splitlines()
               if line.strip()]
    before = manifest.get("containersBefore")
    report(results, "Container count returned to its initial value", len(current) == before,
           before, len(current))
    volumes = {line.strip() for line in run_process([docker, "volume", "ls", "--format",
                                                    "{{.Name}}"]).stdout.splitlines() if line.strip()}
    survivors = sorted(set(recorded_volumes) & volumes)
    report(results, "No data volume survived the stop", not survivors,
           "every volume recorded by this run is gone", survivors or "none")
    manifest["status"] = "stopped"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    passed = sum(1 for value in results if value is True)
    print(f"\n=== Stop summary: {passed} passed, {len(results) - passed} failed ===", flush=True)
    return 0 if all(value is True for value in results) else 1


def parse_args(argv):
    parser = argparse.ArgumentParser(description="Generate and deploy backends without a human hand.")
    parser.add_argument("--base-url", help="Base URL of the running diagrammer")
    parser.add_argument("--keep-running", action="store_true",
                        help="leave the deployed destinations running and print the stop command")
    parser.add_argument("--stop", metavar="RUN_ID", help="stop a run left by --keep-running")
    parser.add_argument("--destinations", type=int, default=2,
                        help="how many independent destinations to generate and deploy (default 2)")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    if args.stop:
        return stop_run(args.stop)
    if not args.base_url:
        print("[FAIL] --base-url is required; start a diagrammer with "
              "`python tools/acceptance/acceptance.py --keep-running`", flush=True)
        return 2
    if args.destinations < 1 or args.destinations > 4:
        print("[FAIL] --destinations must be between 1 and 4", flush=True)
        return 2

    harness = GenerationAcceptance(args.base_url, args.keep_running, args.destinations)
    print(f"[INFO] Generation acceptance run {harness.run_id}", flush=True)
    WORK_ROOT.mkdir(parents=True, exist_ok=True)
    try:
        harness.preflight()
        project_id, model_id, version_id = harness.create_model()
        destinations = harness.create_destinations(project_id)
        harness.destinations = destinations
        for destination in destinations:
            harness.generate(project_id, model_id, version_id, destination)
        for destination, ports in zip(destinations, harness.ports):
            harness.deploy(destination, ports)
        for destination in destinations:
            harness.smoke(destination)
        harness.verify_isolation(destinations)
    except Failure as failure:
        print(f"\n[FAIL] Stopped at: {failure}", flush=True)
    except Exception as error:  # noqa: BLE001 - the harness must always report and always clean up
        report(harness.results, "Unexpected error", False, "no unexpected error",
               f"{type(error).__name__}: {error}")
    finally:
        try:
            harness.cleanup()
        except Exception as error:  # noqa: BLE001
            report(harness.results, "Teardown", False, "cleanup completed",
                   f"{type(error).__name__}: {error}")

    passed = sum(1 for value in harness.results if value is True)
    failed = len(harness.results) - passed
    if failed:
        offenders = [value for value in harness.results if value is not True]
        print(f"[INFO] Failing entries: {[display(value) for value in offenders]}", flush=True)
    print(f"\n=== Generation acceptance summary: {passed} passed, {failed} failed ===", flush=True)
    if not harness.keep and failed == 0:
        print("[INFO] Teardown verified: the container count is back at its initial value.", flush=True)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
