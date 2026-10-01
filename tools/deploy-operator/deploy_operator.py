#!/usr/bin/env python3
"""Run the deployments an editor ordered, without giving the API any Docker access.

The diagrammer records what should be running; this operator is the process on the host that makes it so. It
polls the API for orders, claims one atomically, downloads the frozen archive, compiles it offline, starts it
with its own Compose project and its own PostgreSQL, waits for `health` `UP` and reports back. It also carries
out stop orders.

It never asks the API for a privilege and the API never reaches Docker: the two only exchange deployment state.

What it keeps on the host, and never sends:

- its working directory per deployment, `<work root>/<deployment id>`, holding the archive, the extracted
  project and a generated `.env`;
- a state file per deployment, `<state dir>/<deployment id>.json`, so a later stop order knows where the stack
  lives. The database stores no host path, on purpose.

The database password is generated per deployment, written only into that `.env`, and never printed, never
reported and never sent to the API.

Usage:
    python tools/deploy-operator/deploy_operator.py --api http://127.0.0.1:PORT
    python tools/deploy-operator/deploy_operator.py --api URL --once
    python tools/deploy-operator/deploy_operator.py --api URL --once --deployment <id>
    python tools/deploy-operator/deploy_operator.py --api URL --operator host-a
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
STATE_DIR = HERE / ".state"
WORK_ROOT = HERE / ".work"
POSTGRES_IMAGE = "postgres:16-alpine"
RUNTIME_BASE_IMAGE = "eclipse-temurin:21-jre-alpine"
BUILD_TIMEOUT = 900
COMPOSE_TIMEOUT = 1800
HEALTH_TIMEOUT = 240
DEFAULT_POLL_SECONDS = 5
COMPOSE_NAME = re.compile(r"(?m)^name:\s*(\S+)\s*$")


class DeploymentFailure(Exception):
    """A deployment could not be carried out, with the code and reason the API can store."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class AlreadyTaken(Exception):
    """Another operator won the claim, or the order moved on before this one arrived."""


def log(level: str, message: str) -> None:
    print(f"[{level}] {message}", flush=True)


def install_interrupt_handlers() -> None:
    """Make Ctrl-C and a Windows console break take the same path.

    Python turns Ctrl-C into `KeyboardInterrupt`, but a console break arrives as `SIGBREAK` and its default
    action is to terminate the process on the spot: no cleanup, no report, containers left behind. Both are
    interruptions a person can cause, so both raise `KeyboardInterrupt` and land in the teardown path.

    `subprocess.run` kills its child when the parent is interrupted, so an in-flight `docker compose up` does
    not survive to create anything after the cleanup ran.
    """
    def as_interrupt(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGINT, as_interrupt)
    if hasattr(signal, "SIGBREAK"):
        signal.signal(signal.SIGBREAK, as_interrupt)


class Operator:
    def __init__(self, api: str, operator_id: str, poll_seconds: float, token: str) -> None:
        self.api = api.rstrip("/")
        self.operator_id = operator_id
        self.poll_seconds = poll_seconds
        self.token = token
        self.docker = shutil.which("docker")
        self.maven = shutil.which("mvn")

    # ---------------------------------------------------------------- HTTP

    def call(self, method: str, path: str, payload=None, expect: int | None = None):
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        headers = {"Content-Type": "application/json", "Accept": "application/json"} if data is not None \
            else {"Accept": "application/json"}
        headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(self.api + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, response.read(), response.headers
        except urllib.error.HTTPError as error:
            return error.code, error.read(), error.headers
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError) as error:
            raise DeploymentFailure("API_UNREACHABLE",
                                    f"The diagrammer at {self.api} could not be reached: "
                                    f"{type(error).__name__}: {error}") from error

    def json_call(self, method: str, path: str, payload=None):
        status, body, _ = self.call(method, path, payload)
        try:
            parsed = json.loads(body)
        except json.JSONDecodeError as error:
            raise DeploymentFailure("API_INVALID_RESPONSE",
                                    f"{method} {path} did not answer JSON: {body[:200]!r}") from error
        return status, parsed

    def api_error(self, status: int, parsed) -> DeploymentFailure:
        code = parsed.get("code", "API_ERROR") if isinstance(parsed, dict) else "API_ERROR"
        message = parsed.get("message", "The API refused the request.") if isinstance(parsed, dict) else str(parsed)
        return DeploymentFailure(str(code), f"HTTP {status}: {message}")

    # ------------------------------------------------------------ Responses

    def projects(self) -> list[dict]:
        status, parsed = self.json_call("GET", "/projects")
        if status != 200 or not isinstance(parsed, list):
            raise self.api_error(status, parsed)
        return parsed

    def deployments_of(self, project_id: str) -> list[dict]:
        status, parsed = self.json_call("GET", f"/projects/{project_id}/deployments")
        if status != 200 or not isinstance(parsed, list):
            raise self.api_error(status, parsed)
        return parsed

    def claim(self, deployment_id: str) -> dict:
        status, parsed = self.json_call("POST", f"/deployments/{deployment_id}/claim",
                                        {"operatorId": self.operator_id})
        if status == 409:
            raise AlreadyTaken(parsed.get("message", "The order is not pending any more."))
        if status != 200:
            raise self.api_error(status, parsed)
        return parsed

    def report(self, deployment_id: str, outcome: str, *, compose_project: str | None = None,
               failure_code: str | None = None, failure_message: str | None = None) -> dict:
        payload: dict[str, object] = {"outcome": outcome}
        if compose_project is not None:
            payload["composeProject"] = compose_project
        if failure_code is not None:
            payload["failureCode"] = failure_code
        if failure_message is not None:
            payload["failureMessage"] = failure_message
        status, parsed = self.json_call("POST", f"/deployments/{deployment_id}/report", payload)
        if status != 200:
            # The most likely refusal is a credential-shaped reason, which the API rejects on purpose.
            raise self.api_error(status, parsed)
        return parsed

    def target_of(self, project_id: str, target_id: str) -> dict:
        status, parsed = self.json_call("GET", f"/projects/{project_id}/generation-targets")
        if status != 200 or not isinstance(parsed, list):
            raise self.api_error(status, parsed)
        for target in parsed:
            if target.get("targetId") == target_id:
                return target
        raise DeploymentFailure("TARGET_NOT_FOUND",
                                f"The destination {target_id} is not among the targets of project {project_id}.")

    def download_archive(self, project_id: str, run_id: str, destination: pathlib.Path) -> pathlib.Path:
        status, body, headers = self.call("GET", f"/projects/{project_id}/generations/{run_id}/artifacts")
        content_type = headers.get("Content-Type", "")
        if status != 200 or "zip" not in content_type:
            try:
                parsed = json.loads(body)
                raise DeploymentFailure(parsed.get("code", "ARCHIVE_UNAVAILABLE"),
                                        parsed.get("message", "The archive could not be downloaded."))
            except json.JSONDecodeError as error:
                raise DeploymentFailure("ARCHIVE_UNAVAILABLE",
                                        f"HTTP {status} with content type '{content_type}'.") from error
        archive = destination / "generated.zip"
        archive.write_bytes(body)
        return archive

    # ------------------------------------------------------------- Docker

    def run(self, command: list[str], cwd: pathlib.Path | None = None, timeout: int = 120):
        resolved = [shutil.which(command[0]) or command[0], *command[1:]]
        return subprocess.run(resolved, cwd=cwd, capture_output=True, text=True, encoding="utf-8",
                              errors="replace", timeout=timeout, check=False, shell=False)

    def compose(self, directory: pathlib.Path, *args: str, timeout: int = COMPOSE_TIMEOUT):
        return self.run([self.docker, "compose", *args], cwd=directory, timeout=timeout)

    def container_count(self) -> int:
        result = self.run([self.docker, "ps", "-aq"])
        return len([line for line in result.stdout.splitlines() if line.strip()])

    def volume_count(self) -> int:
        result = self.run([self.docker, "volume", "ls", "-q"])
        return len([line for line in result.stdout.splitlines() if line.strip()])

    def stacks_of_project(self, project_name: str) -> list[str]:
        result = self.run([self.docker, "ps", "-a", "--filter",
                           f"label=com.docker.compose.project={project_name}", "--format", "{{.Names}}"])
        return [line.strip() for line in result.stdout.splitlines() if line.strip()]

    def volumes_of_project(self, project_name: str) -> list[str]:
        result = self.run([self.docker, "volume", "ls", "--format", "{{.Name}}"])
        return [line.strip() for line in result.stdout.splitlines()
                if line.strip().startswith(f"{project_name}_")]

    # --------------------------------------------------------------- State

    def state_path(self, deployment_id: str) -> pathlib.Path:
        return STATE_DIR / f"{deployment_id}.json"

    def read_state(self, deployment_id: str) -> dict | None:
        path = self.state_path(deployment_id)
        if not path.exists():
            return None
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            return None

    def write_state(self, deployment_id: str, state: dict) -> None:
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        self.state_path(deployment_id).write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")

    # ----------------------------------------------------------- Preflight

    def preflight(self) -> None:
        if not self.docker:
            raise DeploymentFailure("DOCKER_UNAVAILABLE", "docker was not found in PATH.")
        if not self.maven:
            raise DeploymentFailure("MAVEN_UNAVAILABLE", "mvn was not found in PATH.")
        for image in (POSTGRES_IMAGE, RUNTIME_BASE_IMAGE):
            present = self.run([self.docker, "image", "inspect", "--format={{.Id}}", image])
            if present.returncode != 0:
                # Reported instead of pulled: this operator never reaches the network, so a missing image is a
                # human decision and the message says exactly which one.
                raise DeploymentFailure("MISSING_LOCAL_IMAGE",
                                        f"The image {image} is not present locally; the operator never pulls.")
        status, _ = self.json_call("GET", "/actuator/health")
        if status != 200:
            raise DeploymentFailure("API_UNREACHABLE", f"The diagrammer health check answered HTTP {status}.")

    # -------------------------------------------------------------- Deploy

    def deploy(self, project_id: str, deployment: dict) -> str:
        deployment_id = deployment["deploymentId"]
        try:
            claimed = self.claim(deployment_id)
        except AlreadyTaken as taken:
            log("SKIP", f"{deployment_id} was taken by somebody else: {taken}")
            return "skipped"
        log("OK", f"claimed {deployment_id} as {self.operator_id} (ports "
                 f"{claimed['httpHostPort']}/{claimed['postgresHostPort']})")

        work = WORK_ROOT / deployment_id
        project_name = None
        try:
            self.preflight()
            if work.exists():
                shutil.rmtree(work, ignore_errors=True)
            work.mkdir(parents=True, exist_ok=True)

            target = self.target_of(project_id, deployment["targetId"])
            archive = self.download_archive(project_id, deployment["runId"], work)
            log("INFO", f"downloaded {archive.stat().st_size} bytes for {deployment['targetKey']}")
            with zipfile.ZipFile(archive) as bundle:
                bundle.extractall(work)
            project_name = self.compose_project_name(work)

            self.write_env(work, deployment, target)
            self.write_state(deployment_id, {
                "deploymentId": deployment_id, "projectId": project_id, "runId": deployment["runId"],
                "targetKey": deployment["targetKey"], "workDir": str(work), "composeProject": project_name,
                "httpHostPort": deployment["httpHostPort"], "postgresHostPort": deployment["postgresHostPort"],
                "operatorId": self.operator_id,
            })

            packaged = self.run([self.maven, "-o", "-B", "package"], cwd=work, timeout=BUILD_TIMEOUT)
            if packaged.returncode != 0:
                raise DeploymentFailure("GENERATED_BUILD_FAILED",
                                        f"mvn -o -B package failed with exit {packaged.returncode}: "
                                        f"{(packaged.stdout or packaged.stderr).strip()[-300:]}")
            log("OK", f"compiled the generated project for {deployment['targetKey']} offline")

            up = self.compose(work, "up", "-d", "--build", "--pull", "never")
            if up.returncode != 0:
                raise DeploymentFailure("COMPOSE_UP_FAILED",
                                        f"docker compose up failed with exit {up.returncode}: "
                                        f"{(up.stderr or up.stdout).strip()[-300:]}")

            health = self.wait_for_health(deployment["httpHostPort"])
            self.report(deployment_id, "UP", compose_project=project_name)
            log("OK", f"{deployment['targetKey']} is up: {health}")
            log("INFO", f"from another device on the same network: "
                        f"{self.lan_base_url(deployment['httpHostPort'])}/api/...")
            return "deployed"
        except KeyboardInterrupt:
            log("FAIL", "interrupted; taking the stack down before leaving")
            self.cleanup(work, project_name)
            self.try_report_failure(deployment_id, "OPERATOR_INTERRUPTED",
                                    "The operator was interrupted while deploying; the stack was taken down.")
            raise
        except DeploymentFailure as failure:
            # A failed attempt may still have created containers, so the stack comes down before reporting.
            self.cleanup(work, project_name)
            self.try_report_failure(deployment_id, failure.code, failure.message)
            log("FAIL", f"{deployment['targetKey']}: {failure.code}: {failure.message}")
            return "failed"

    def compose_project_name(self, work: pathlib.Path) -> str:
        compose_file = work / "docker-compose.yml"
        if not compose_file.exists():
            raise DeploymentFailure("ARCHIVE_INCOMPLETE", "The archive has no docker-compose.yml.")
        match = COMPOSE_NAME.search(compose_file.read_text(encoding="utf-8"))
        if not match:
            # The emitted file always declares it, and the operator has to know it to report and to tear down.
            raise DeploymentFailure("ARCHIVE_INCOMPLETE", "The emitted Compose file declares no project name.")
        return match.group(1)

    def write_env(self, work: pathlib.Path, deployment: dict, target: dict) -> None:
        database = target.get("databaseName") or f"{deployment['targetKey']}_db"
        password = secrets.token_urlsafe(24)
        (work / ".env").write_text(
            f"GENERATED_DB_NAME={database}\n"
            f"GENERATED_DB_USER={database}\n"
            f"GENERATED_DB_PASSWORD={password}\n"
            f"GENERATED_HTTP_HOST_PORT={deployment['httpHostPort']}\n"
            f"GENERATED_POSTGRES_HOST_PORT={deployment['postgresHostPort']}\n",
            encoding="utf-8")
        # The password above is written and never printed, never logged and never reported.

    def wait_for_health(self, http_port: int) -> str:
        deadline = time.monotonic() + HEALTH_TIMEOUT
        last = "health has not answered"
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{http_port}/actuator/health", timeout=5) as answer:
                    body = answer.read().decode("utf-8", errors="replace")
                    if answer.status == 200 and json.loads(body).get("status") == "UP":
                        return body
                    last = f"HTTP {answer.status}: {body[:120]}"
            except (urllib.error.URLError, ConnectionError, TimeoutError, OSError, json.JSONDecodeError) as error:
                last = f"{type(error).__name__}: {error}"
            time.sleep(2)
        raise DeploymentFailure("HEALTH_NEVER_UP", f"The generated backend never answered health UP: {last}")

    # ---------------------------------------------------------------- Stop

    def stop(self, project_id: str, deployment: dict) -> str:
        deployment_id = deployment["deploymentId"]
        if deployment.get("operatorId") and deployment["operatorId"] != self.operator_id:
            log("SKIP", f"{deployment_id} is held by {deployment['operatorId']}, not by {self.operator_id}")
            return "foreign"

        state = self.read_state(deployment_id)
        if state is None:
            # Another machine deployed it, or this operator lost its own record. Say so instead of stalling.
            self.try_report_failure(deployment_id, "UNKNOWN_DEPLOYMENT_DIRECTORY",
                                    "This operator has no record of that deployment's working directory.")
            log("FAIL", f"{deployment_id}: no local record of its working directory")
            return "failed"

        work = pathlib.Path(state["workDir"])
        project_name = state.get("composeProject")
        before = self.container_count()
        try:
            self.cleanup(work, project_name, required=True)
        except DeploymentFailure as failure:
            self.try_report_failure(deployment_id, failure.code, failure.message)
            log("FAIL", f"{deployment_id}: {failure.code}: {failure.message}")
            return "failed"
        survivors = self.stacks_of_project(project_name) if project_name else []
        volumes = self.volumes_of_project(project_name) if project_name else []
        if survivors or volumes:
            self.try_report_failure(deployment_id, "STOP_INCOMPLETE",
                                    f"Containers {survivors} or volumes {volumes} survived the teardown.")
            log("FAIL", f"{deployment_id}: teardown left {survivors} and {volumes}")
            return "failed"

        self.report(deployment_id, "STOPPED")
        log("OK", f"{deployment_id} stopped; containers back at {self.container_count()} from {before}")
        return "stopped"

    def cleanup(self, work: pathlib.Path, project_name: str | None, required: bool = False) -> None:
        if not (work / "docker-compose.yml").exists():
            if required:
                raise DeploymentFailure("UNKNOWN_DEPLOYMENT_DIRECTORY",
                                        f"There is no Compose file in {work}.")
            return
        down = self.compose(work, "down", "-v", "--remove-orphans")
        if down.returncode != 0 and required:
            raise DeploymentFailure("COMPOSE_DOWN_FAILED",
                                    f"docker compose down failed with exit {down.returncode}: "
                                    f"{(down.stderr or down.stdout).strip()[-300:]}")

    def try_report_failure(self, deployment_id: str, code: str, message: str) -> None:
        try:
            self.report(deployment_id, "FAILED", failure_code=code, failure_message=message)
        except Exception as error:  # noqa: BLE001 - never let reporting hide the original failure
            log("FAIL", f"could not report the failure of {deployment_id}: {type(error).__name__}: {error}")

    # ---------------------------------------------------------------- Loop

    def lan_base_url(self, http_port: int) -> str:
        address = self.lan_address()
        return f"http://{address}:{http_port}" if address else f"http://localhost:{http_port}"

    @staticmethod
    def lan_address() -> str | None:
        try:
            # A UDP socket with a route picks the outbound interface without sending a single packet.
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
                probe.connect(("192.0.2.1", 9))
                address = probe.getsockname()[0]
            return None if address.startswith("127.") else address
        except OSError:
            return None

    def work_for(self) -> list[tuple[str, dict]]:
        pending: list[tuple[str, dict]] = []
        for project in self.projects():
            project_id = project["id"]
            for deployment in self.deployments_of(project_id):
                if deployment.get("state") == "PENDING":
                    pending.append((project_id, deployment))
                elif deployment.get("state") == "STOPPING" and deployment.get("operatorId") == self.operator_id:
                    pending.append((project_id, deployment))
        return pending

    def handle(self, project_id: str, deployment: dict) -> str:
        if deployment.get("state") == "PENDING":
            return self.deploy(project_id, deployment)
        return self.stop(project_id, deployment)

    def run_once(self, deployment_id: str | None) -> str:
        if deployment_id:
            for project in self.projects():
                for deployment in self.deployments_of(project["id"]):
                    if deployment["deploymentId"] == deployment_id:
                        return self.handle(project["id"], deployment)
            raise DeploymentFailure("DEPLOYMENT_NOT_FOUND", f"No deployment {deployment_id} was found.")
        work = self.work_for()
        if not work:
            log("INFO", "nothing to do")
            return "idle"
        project_id, deployment = work[0]
        return self.handle(project_id, deployment)

    def loop(self) -> int:
        outcomes: dict[str, int] = {}
        while True:
            try:
                outcome = self.run_once(None)
            except DeploymentFailure as failure:
                log("FAIL", f"{failure.code}: {failure.message}")
                outcome = "failed"
            except KeyboardInterrupt:
                break
            outcomes[outcome] = outcomes.get(outcome, 0) + 1
            if outcome == "idle":
                time.sleep(self.poll_seconds)
        log("INFO", f"stopped after {outcomes}")
        return 0


def parse_args(argv):
    parser = argparse.ArgumentParser(description="Deploy the backends an editor ordered, from the host.")
    parser.add_argument("--api", required=True, help="base URL of the running diagrammer")
    parser.add_argument("--token", default=os.environ.get("DIAGRAMMER_OPERATOR_TOKEN"),
                        help="operator bearer token (default: DIAGRAMMER_OPERATOR_TOKEN)")
    parser.add_argument("--operator", default=None,
                        help="this operator's identity, reported on every claim (default host-pid)")
    parser.add_argument("--once", action="store_true", help="carry out one unit of work and exit")
    parser.add_argument("--deployment", metavar="ID", help="work on this deployment only")
    parser.add_argument("--poll-seconds", type=float, default=DEFAULT_POLL_SECONDS,
                        help=f"how long to wait between polls in the loop (default {DEFAULT_POLL_SECONDS})")
    return parser.parse_args(argv)


def default_operator_id() -> str:
    return f"{socket.gethostname()}-{os.getpid()}"


def main(argv=None) -> int:
    args = parse_args(argv)
    if not args.token or not args.token.strip():
        log("FAIL", "No operator token is available; set DIAGRAMMER_OPERATOR_TOKEN or pass --token.")
        return 2
    install_interrupt_handlers()
    operator = Operator(args.api, args.operator or default_operator_id(), args.poll_seconds, args.token)

    try:
        if args.once:
            outcome = operator.run_once(args.deployment)
            log("INFO", f"once -> {outcome}")
            return 0 if outcome in {"deployed", "stopped", "idle", "skipped"} else 1
        return operator.loop()
    except DeploymentFailure as failure:
        log("FAIL", f"{failure.code}: {failure.message}")
        return 1
    except KeyboardInterrupt:
        # The deployment and stop paths take the stack down before letting this through, so reaching here
        # means nothing was left half started.
        log("INFO", "interrupted")
        return 130


if __name__ == "__main__":
    sys.exit(main())
