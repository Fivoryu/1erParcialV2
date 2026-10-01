#!/usr/bin/env python3
"""Prove the whole deployment cut from outside: order, deploy with the operator, use it, stop it.

This is the sibling of `acceptance_generation.py`, and it goes through the **product path** rather than around
it. That other harness deploys a generated project directly with Compose to prove the artifact; this one orders
a deployment from the API and lets `tools/deploy-operator/` carry it out, which is what the editor's button
does.

  1. creates a project with two related classes and one finished generation run;
  2. orders a deployment of that run from the API;
  3. runs the operator once, which claims it, deploys and reports;
  4. checks the state, and `health` **from the loopback address and from this machine's address on the
     network**, because the network one is the address the phone will use;
  5. runs the generated CRUD against that address, including the foreign-key resolution;
  6. refuses a second order of the same destination;
  7. orders a stop, runs the operator again, and verifies that no container and no volume survived.

What it does **not** do: reach the backend from another device. That is the phone's own one-command test, and it
is declared in `docs/puds/generacion/04-despliegue-desde-el-editor.md` §3.

Usage:
    python tools/generation-acceptance/acceptance_deployment.py --base-url http://127.0.0.1:PORT
    python tools/generation-acceptance/acceptance_deployment.py --base-url URL --keep-running
    python tools/generation-acceptance/acceptance_deployment.py --stop <RUN_ID>
"""

from __future__ import annotations

import argparse
import json
import pathlib
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

HERE = pathlib.Path(__file__).resolve().parent
OPERATOR = HERE.parents[0] / "deploy-operator" / "deploy_operator.py"
STATE_DIR = HERE / ".state"
POSTGRES_IMAGE = "postgres:16-alpine"
RUNTIME_BASE_IMAGE = "eclipse-temurin:21-jre-alpine"
OWNER_ID = "00000000-0000-0000-0000-000000000001"
OWNER_SUBJECT = "deployment-acceptance"
HEALTH_TIMEOUT = 240
OPERATOR_TIMEOUT = 1800


class Failure(Exception):
    """The first failed assertion."""


def display(value):
    return value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, sort_keys=True)


def report(results, step, ok, expected, got):
    if not isinstance(ok, bool):
        # `a == b and c` returns `c`, not `True`. That makes an assertion print OK and still count as a failure,
        # which is exactly how a green transcript lies. Refusing anything that is not a bool ends that.
        raise TypeError(f"the assertion for {step!r} produced {type(ok).__name__}, not a bool: {ok!r}")
    results.append(ok)
    print(f"[{'OK' if ok else 'FAIL'}] {step}", flush=True)
    print(f"  expected: {display(expected)}", flush=True)
    print(f"  got:      {display(got)}", flush=True)


def require(results, step, ok, expected, got):
    report(results, step, ok, expected, got)
    if not ok:
        raise Failure(step)


def lan_address() -> str | None:
    try:
        # A UDP socket with a route picks the outbound interface without sending a single packet.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 9))
            address = probe.getsockname()[0]
        return None if address.startswith("127.") else address
    except OSError:
        return None


class Harness:
    def __init__(self, base_url: str, keep: bool, operator_id: str) -> None:
        self.results: list[bool] = []
        self.run_id = str(uuid.uuid4())
        self.base_url = base_url.rstrip("/")
        self.keep = keep
        self.operator_id = operator_id
        self.docker = shutil.which("docker")
        self.containers_before: int | None = None
        self.volumes_before: int | None = None
        self.project_id: str | None = None
        self.deployment_id: str | None = None
        self.manifest_path = STATE_DIR / f"{self.run_id}.json"

    # ---------------------------------------------------------------- API

    def call(self, method: str, path: str, payload=None):
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        request = urllib.request.Request(
            self.base_url + path, data=data, method=method,
            headers={"Content-Type": "application/json", "Accept": "application/json"} if data is not None
            else {"Accept": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read())
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError) as error:
            raise Failure(f"the diagrammer at {self.base_url} could not be reached: "
                          f"{type(error).__name__}: {error}") from error

    def command(self, model_id: str, revision: int, parent: str | None, kind: str, payload, targets):
        operation = str(uuid.uuid4())
        status, result = self.call("POST", f"/projects/{self.project_id}/commands", {
            "operationId": operation, "type": kind,
            "author": {"userId": OWNER_ID, "externalSubject": OWNER_SUBJECT},
            "projectId": self.project_id, "modelId": model_id, "baseRevision": revision, "targets": targets,
            "preconditions": [{"kind": "model_valid", "expect": True},
                              {"kind": "permission", "expect": "EDITOR"}],
            "payload": payload, "origin": "MANUAL", "causalParentOperationId": parent,
            "idempotencyKey": f"deployment-acceptance:{self.run_id}:{operation}"})
        require(self.results, f"Apply {kind} at revision {revision}",
                status == 200 and result.get("status") == "ACCEPTED"
                and result.get("resultRevision") == revision + 1,
                {"HTTP": 200, "status": "ACCEPTED", "resultRevision": revision + 1},
                {"HTTP": status, "body": result})
        return result["resultRevision"], operation

    # ------------------------------------------------------------- Docker

    def count(self, what: str) -> int:
        args = ["ps", "-aq"] if what == "containers" else ["volume", "ls", "-q"]
        result = subprocess.run([self.docker, *args], capture_output=True, text=True, timeout=120)
        return len([line for line in result.stdout.splitlines() if line.strip()])

    def operator(self, *extra: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(OPERATOR), "--api", self.base_url, "--operator", self.operator_id, *extra],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=OPERATOR_TIMEOUT)

    # ----------------------------------------------------------- Preflight

    def preflight(self) -> None:
        require(self.results, "Locate Docker", self.docker is not None, "docker available in PATH",
                self.docker or "not found")
        require(self.results, "Locate Maven", shutil.which("mvn") is not None,
                "mvn available in PATH; the operator compiles from the local cache",
                shutil.which("mvn") or "not found")
        for image in (POSTGRES_IMAGE, RUNTIME_BASE_IMAGE):
            present = subprocess.run([self.docker, "image", "inspect", "--format={{.Id}}", image],
                                     capture_output=True, text=True, timeout=120)
            require(self.results, f"Inspect the local image {image}", present.returncode == 0,
                    "present locally; this run never pulls", present.stdout.strip() or "missing")
        status, health = self.call("GET", "/actuator/health")
        require(self.results, "Reach the running diagrammer", status == 200 and health.get("status") == "UP",
                {"HTTP": 200, "status": "UP"},
                {"HTTP": status, "body": health,
                 "hint": "start one with tools/acceptance/acceptance.py --keep-running"})
        self.containers_before = self.count("containers")
        self.volumes_before = self.count("volumes")
        print(f"[INFO] Containers before: {self.containers_before}, volumes: {self.volumes_before}", flush=True)

    # --------------------------------------------------------------- Cut

    def create_finished_run(self) -> str:
        status, project = self.call("POST", "/projects", {
            "name": f"Deployment acceptance {self.run_id[:8]}", "description": "Deployment acceptance"})
        require(self.results, "Create the project over HTTP",
                status == 201 and bool(project.get("id")),
                "HTTP 201 with a project id", {"HTTP": status, "body": project})
        self.project_id = project["id"]

        status, model = self.call("GET", f"/projects/{self.project_id}/model")
        model_id, revision = model["id"], model["revision"]
        customer, order = str(uuid.uuid4()), str(uuid.uuid4())
        revision, parent = self.command(model_id, revision, None, "classifier.create",
                                        {"id": customer, "name": "Customer", "kind": "CLASS"}, [])
        revision, parent = self.command(model_id, revision, parent, "classifier.create",
                                        {"id": order, "name": "Order", "kind": "CLASS"}, [])
        revision, parent = self.command(model_id, revision, parent, "attribute.add",
                                        {"id": str(uuid.uuid4()), "name": "displayName",
                                         "type": {"primitiveName": "String"}, "visibility": "PRIVATE",
                                         "multiplicity": "EXACTLY_ONE", "defaultValue": "Guest"},
                                        [{"scope": "CLASSIFIER", "id": customer}])
        revision, parent = self.command(model_id, revision, parent, "attribute.add",
                                        {"id": str(uuid.uuid4()), "name": "total",
                                         "type": {"primitiveName": "BigDecimal"}, "visibility": "PRIVATE",
                                         "multiplicity": "EXACTLY_ONE"},
                                        [{"scope": "CLASSIFIER", "id": order}])
        revision, parent = self.command(model_id, revision, parent, "relationship.create", {
            "id": str(uuid.uuid4()), "kind": "ASSOCIATION", "name": "orders",
            "source": {"classifierId": customer, "role": "customer", "multiplicity": "EXACTLY_ONE",
                       "navigability": "NAVIGABLE"},
            "target": {"classifierId": order, "role": "orders", "multiplicity": "ZERO_OR_MANY",
                       "navigability": "NAVIGABLE"},
        }, [{"scope": "CLASSIFIER", "id": customer}, {"scope": "CLASSIFIER", "id": order}])

        status, model = self.call("GET", f"/projects/{self.project_id}/model")
        target_key = f"acceptance-{self.run_id[:5]}"
        status, target = self.call("POST", f"/projects/{self.project_id}/generation-targets", {
            "targetKey": target_key, "name": "Acceptance destination", "databaseEngine": "POSTGRESQL",
            "databaseName": f"acceptance_{self.run_id[:5]}_db", "databaseSchema": "public",
            "connectionConfigRef": None})
        require(self.results, "Create the destination",
                status == 200 and bool(target.get("result")),
                "HTTP 200 with a destination", {"HTTP": status, "body": target})
        status, profiles = self.call("GET", f"/projects/{self.project_id}/generation-profiles")
        profile = profiles[0]
        status, generation = self.call("POST", f"/projects/{self.project_id}/generations", {
            "modelId": model_id, "versionId": model["versionId"], "targetId": target["result"]["targetId"],
            "profileId": profile["profileId"], "templateId": profile["templateId"],
            "idempotencyKey": f"deployment-acceptance:{self.run_id}"})
        run = generation.get("result") or {}
        require(self.results, "Generate a backend for the destination",
                run.get("state") == "SUCCEEDED",
                {"state": "SUCCEEDED", "blockingDiagnostics": 0},
                {"state": run.get("state"), "code": generation.get("code"), "body": generation.get("result")})
        print(f"[INFO] run={run['runId']} artifactSetHash={run['artifactSetHash']}", flush=True)
        self.target_key = target_key
        return run["runId"]

    def deploy(self, run_id: str) -> dict:
        status, ordered = self.call("POST", f"/projects/{self.project_id}/deployments", {"runId": run_id})
        require(self.results, "Order the deployment from the API",
                status == 201 and ordered.get("state") == "PENDING",
                {"HTTP": 201, "state": "PENDING", "ports": "1900N/2543N"},
                {"HTTP": status, "state": ordered.get("state"),
                 "ports": f"{ordered.get('httpHostPort')}/{ordered.get('postgresHostPort')}"})
        self.deployment_id = ordered["deploymentId"]
        print(f"[INFO] deployment={self.deployment_id} "
              f"ports={ordered['httpHostPort']}/{ordered['postgresHostPort']}", flush=True)

        outcome = self.operator("--once", "--deployment", self.deployment_id)
        print(outcome.stdout, flush=True)
        require(self.results, "The operator deploys what the API ordered", outcome.returncode == 0,
                0, {"exit": outcome.returncode, "tail": (outcome.stderr or outcome.stdout)[-300:]})

        status, listed = self.call("GET", f"/projects/{self.project_id}/deployments")
        deployment = next((item for item in listed if item["deploymentId"] == self.deployment_id), None)
        require(self.results, "The deployment is UP and names its Compose project",
                deployment is not None and deployment["state"] == "UP" and bool(deployment["composeProject"]),
                {"state": "UP", "composeProject": "a name"},
                {"state": deployment and deployment["state"],
                 "composeProject": deployment and deployment["composeProject"],
                 "failure": deployment and deployment["failureMessage"]})
        return deployment

    def reachable_from_both_addresses(self, deployment: dict) -> None:
        port = deployment["httpHostPort"]
        for label, host in (("loopback", "127.0.0.1"), ("the machine's network address", lan_address())):
            if host is None:
                report(self.results, f"health UP from {label}", False, "an address on the network",
                       "no non loopback address was found on this machine")
                continue
            base = f"http://{host}:{port}"
            body, last = None, "health has not answered"
            deadline = time.monotonic() + HEALTH_TIMEOUT
            while time.monotonic() < deadline:
                try:
                    with urllib.request.urlopen(f"{base}/actuator/health", timeout=5) as answer:
                        last = answer.read().decode("utf-8", errors="replace")
                        if answer.status == 200 and json.loads(last).get("status") == "UP":
                            body = last
                            break
                except (urllib.error.URLError, ConnectionError, TimeoutError, OSError,
                        json.JSONDecodeError) as error:
                    last = f"{type(error).__name__}: {error}"
                time.sleep(2)
            require(self.results, f"health UP from {label} at {base}", body is not None,
                    {"HTTP": 200, "status": "UP"}, last)
        deployment["baseUrl"] = f"http://{lan_address() or '127.0.0.1'}:{port}"

    def crud_over_the_network(self, deployment: dict) -> None:
        base = deployment["baseUrl"]

        def call(method: str, path: str, payload=None):
            data = json.dumps(payload).encode("utf-8") if payload is not None else None
            request = urllib.request.Request(base + path, data=data, method=method,
                                             headers={"Content-Type": "application/json"} if data else {})
            try:
                with urllib.request.urlopen(request, timeout=30) as answer:
                    return answer.status, answer.read().decode("utf-8", errors="replace")
            except urllib.error.HTTPError as error:
                return error.code, error.read().decode("utf-8", errors="replace")

        status, customer = call("POST", "/api/customer",
                                {"displayName": "Acceptance", "email": "acceptance@example.org"})
        require(self.results, f"POST /api/customer against {base}", status == 201,
                "HTTP 201 with the created resource", {"HTTP": status, "body": customer})
        customer_id = json.loads(customer)["id"]

        status, order = call("POST", "/api/order", {"total": 42.5, "customerId": customer_id})
        created = json.loads(order) if status == 201 else {}
        require(self.results, "POST /api/order resolves the parent reference",
                status == 201 and created.get("customerId") == customer_id,
                {"HTTP": 201, "customerId": customer_id}, {"HTTP": status, "body": order})

        status, body = call("GET", f"/api/order/{created.get('id')}")
        require(self.results, "GET /api/order/{id} keeps the reference",
                status == 200 and json.loads(body).get("customerId") == customer_id,
                {"HTTP": 200, "customerId": customer_id}, {"HTTP": status, "body": body})

        status, body = call("GET", "/api/order")
        require(self.results, "GET /api/order lists from another host address",
                status == 200 and len(json.loads(body)) == 1, "HTTP 200 with one element",
                {"HTTP": status, "body": body})

        status, body = call("POST", "/api/order",
                            {"total": 1.0, "customerId": "11111111-1111-4111-8111-111111111111"})
        require(self.results, "A missing parent is 400 and never 500", status == 400, "HTTP 400",
                {"HTTP": status, "body": body})

        status, body = call("GET", "/api/order/22222222-2222-4222-8222-222222222222")
        require(self.results, "An unknown id is 404", status == 404, "HTTP 404",
                {"HTTP": status, "body": body})

    def refuse_a_second_order(self, run_id: str) -> None:
        status, body = self.call("POST", f"/projects/{self.project_id}/deployments", {"runId": run_id})
        require(self.results, "A second order of the same destination is refused", status == 409,
                {"HTTP": 409, "code": "DEPLOYMENT_ALREADY_ACTIVE"},
                {"HTTP": status, "code": body.get("code")})

    def stop(self) -> None:
        status, stopping = self.call("POST", f"/deployments/{self.deployment_id}/stop")
        require(self.results, "Order the stop", status == 200 and stopping["state"] == "STOPPING",
                {"HTTP": 200, "state": "STOPPING"}, {"HTTP": status, "state": stopping.get("state")})
        outcome = self.operator("--once", "--deployment", self.deployment_id)
        print(outcome.stdout, flush=True)
        status, listed = self.call("GET", f"/projects/{self.project_id}/deployments")
        deployment = next((item for item in listed if item["deploymentId"] == self.deployment_id), None)
        require(self.results, "The deployment ends STOPPED",
                deployment is not None and deployment["state"] == "STOPPED",
                {"state": "STOPPED"},
                {"state": deployment and deployment["state"],
                 "failure": deployment and deployment["failureMessage"]})

    def verify_cleanup(self) -> None:
        containers, volumes = self.count("containers"), self.count("volumes")
        require(self.results, "Container count returned to its initial value",
                containers == self.containers_before, self.containers_before, containers)
        require(self.results, "Volume count returned to its initial value",
                volumes == self.volumes_before, self.volumes_before, volumes)

    def write_manifest(self) -> None:
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        self.manifest_path.write_text(json.dumps({
            "runId": self.run_id, "status": "running" if self.keep else "finished",
            "baseUrl": self.base_url, "projectId": self.project_id, "deploymentId": self.deployment_id,
            "operatorId": self.operator_id, "containersBefore": self.containers_before,
            "volumesBefore": self.volumes_before,
        }, indent=2) + "\n", encoding="utf-8")


def stop_run(run_id: str) -> int:
    manifest_path = STATE_DIR / f"{run_id}.json"
    if not manifest_path.exists():
        print(f"[FAIL] No recorded run {run_id} in {STATE_DIR}", flush=True)
        return 1
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    harness = Harness(manifest["baseUrl"], False, manifest["operatorId"])
    harness.deployment_id = manifest["deploymentId"]
    harness.project_id = manifest["projectId"]
    harness.containers_before = manifest["containersBefore"]
    harness.volumes_before = manifest["volumesBefore"]
    print(f"[INFO] Stopping deployment {harness.deployment_id}", flush=True)
    try:
        harness.call("POST", f"/deployments/{harness.deployment_id}/stop")
        outcome = harness.operator("--once", "--deployment", harness.deployment_id)
        print(outcome.stdout, flush=True)
        harness.verify_cleanup()
    except Failure as failure:
        print(f"[FAIL] Stopped at: {failure}", flush=True)
    passed = sum(1 for value in harness.results if value is True)
    manifest["status"] = "stopped"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"\n=== Stop summary: {passed} passed, {len(harness.results) - passed} failed ===", flush=True)
    return 0 if all(value is True for value in harness.results) else 1


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Prove the deployment cut end to end, through the operator.")
    parser.add_argument("--base-url", help="base URL of the running diagrammer")
    parser.add_argument("--keep-running", action="store_true",
                        help="leave the deployment up and print the stop command")
    parser.add_argument("--stop", metavar="RUN_ID", help="stop a deployment left by --keep-running")
    parser.add_argument("--operator", default="deployment-acceptance",
                        help="the operator identity this run claims with")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    if args.stop:
        return stop_run(args.stop)
    if not args.base_url:
        print("[FAIL] --base-url is required; start a diagrammer with "
              "`python tools/acceptance/acceptance.py --keep-running`", flush=True)
        return 2

    harness = Harness(args.base_url, args.keep_running, args.operator)
    print(f"[INFO] Deployment acceptance run {harness.run_id}", flush=True)
    try:
        harness.preflight()
        run_id = harness.create_finished_run()
        deployment = harness.deploy(run_id)
        harness.reachable_from_both_addresses(deployment)
        harness.crud_over_the_network(deployment)
        harness.refuse_a_second_order(run_id)
        if harness.keep:
            harness.write_manifest()
            print(f"\n[INFO] Left running by request. The backend answers at {deployment['baseUrl']}", flush=True)
            print(f"[INFO] Stop it with: python tools/generation-acceptance/acceptance_deployment.py "
                  f"--stop {harness.run_id}", flush=True)
        else:
            harness.stop()
            harness.verify_cleanup()
    except Failure as failure:
        print(f"\n[FAIL] Stopped at: {failure}", flush=True)
    except Exception as error:  # noqa: BLE001 - always report, and always leave a clear state
        report(harness.results, "Unexpected error", False, "no unexpected error",
               f"{type(error).__name__}: {error}")

    passed = sum(1 for value in harness.results if value is True)
    failed = len(harness.results) - passed
    if failed:
        print(f"[INFO] Failing entries: "
              f"{[display(value) for value in harness.results if value is not True]}", flush=True)
    print(f"\n=== Deployment acceptance summary: {passed} passed, {failed} failed ===", flush=True)
    if not harness.keep and failed == 0:
        print("[INFO] Teardown verified: no container and no volume survived.", flush=True)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
