#!/usr/bin/env python3
"""Seed a presentable class diagram into a running backend.

Why this exists: the increment's visual evidence has to be reproducible. A
screenshot whose scene was created by hand is not evidence of anything, so the
scene is created by this script and the screenshot is taken of the result.

It creates one project with a class, an abstract class and an interface, an
association and an interface realization, and persists the geometry of the
three boxes, so the canvas renders a real diagram rather than an empty grid.

Usage:
    python tools/acceptance/seed_demo_diagram.py --base-url http://127.0.0.1:PORT

It prints the editor URL for the seeded project. It never deletes anything, and
it is safe to run more than once: each run creates a new project.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid

SEED_OWNER_ID = "00000000-0000-0000-0000-000000000001"
SEED_OWNER_SUBJECT = "seed-owner-local"


def request(base_url: str, method: str, path: str, body: dict | None = None) -> dict:
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        f"{base_url.rstrip('/')}{path}",
        data=data,
        method=method,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        raise SystemExit(f"{method} {path} failed with HTTP {error.code}: {detail}") from error


def envelope(
    *,
    kind: str,
    project_id: str,
    model_id: str,
    base_revision: int,
    payload: dict,
    targets: list[dict] | None = None,
) -> dict:
    return {
        "operationId": str(uuid.uuid4()),
        "type": kind,
        "author": {"userId": SEED_OWNER_ID, "externalSubject": SEED_OWNER_SUBJECT},
        "projectId": project_id,
        "modelId": model_id,
        "baseRevision": base_revision,
        "targets": targets or [],
        "preconditions": [],
        "payload": payload,
        "origin": "MANUAL",
        "causalParentOperationId": None,
        "idempotencyKey": str(uuid.uuid4()),
    }


class Seeder:
    def __init__(self, base_url: str, project_id: str) -> None:
        self.base_url = base_url
        self.project_id = project_id
        self.revision = 0
        self.commands = 0

    def send(self, kind: str, payload: dict, targets: list[dict] | None = None) -> dict:
        body = envelope(
            kind=kind,
            project_id=self.project_id,
            model_id=self.model_id,
            base_revision=self.revision,
            payload=payload,
            targets=targets,
        )
        result = request(self.base_url, "POST", f"/projects/{self.project_id}/commands", body)
        if result.get("status") != "ACCEPTED":
            diagnostics = "; ".join(
                f"{item.get('code')}: {item.get('message')}" for item in result.get("diagnostics", [])
            )
            raise SystemExit(f"{kind} was rejected: {diagnostics}")
        self.revision = result["resultRevision"]
        self.commands += 1
        return result

    def run(self, model: dict) -> None:
        self.model_id = model["id"]
        self.revision = model["revision"]
        view_id = model["views"][0]["id"]

        payable = self.classifier("Payable", "INTERFACE")
        order = self.classifier("Order", "ABSTRACT_CLASS")
        customer = self.classifier("Customer", "CLASS")

        for name, type_name in (("id", "String"), ("name", "String")):
            self.attribute(customer, name, type_name, "PRIVATE")
        self.attribute(customer, "email", "String", "PRIVATE", default="'unknown@example.test'")
        self.attribute(order, "placedAt", "Date", "PRIVATE")
        self.attribute(order, "total", "double", "PRIVATE")

        self.operation(customer, "confirm", "boolean")
        self.operation(order, "submit", "void")
        self.operation(order, "cancel", "void")
        self.operation(payable, "pay", "boolean")

        self.constraint(customer, "email is not empty")

        self.send(
            "relationship.create",
            {
                "id": str(uuid.uuid4()),
                "kind": "ASSOCIATION",
                "name": "places",
                "source": {
                    "classifierId": customer,
                    "role": "customer",
                    "multiplicity": "EXACTLY_ONE",
                    "navigability": "NAVIGABLE",
                },
                "target": {
                    "classifierId": order,
                    "role": "orders",
                    "multiplicity": "ZERO_OR_MANY",
                    "navigability": "NOT_NAVIGABLE",
                },
            },
            targets=[{"scope": "classifier", "id": customer}, {"scope": "classifier", "id": order}],
        )
        self.send(
            "relationship.create",
            {
                "id": str(uuid.uuid4()),
                "kind": "INTERFACE_REALIZATION",
                "source": {"classifierId": order, "navigability": "NAVIGABLE"},
                "target": {"classifierId": payable, "navigability": "NOT_NAVIGABLE"},
            },
            targets=[{"scope": "classifier", "id": order}, {"scope": "classifier", "id": payable}],
        )

        for target_id, x, y, w, h, z in (
            (payable, 540, 60, 240, 120, 1),
            (order, 540, 300, 280, 230, 2),
            (customer, 120, 300, 300, 270, 3),
        ):
            self.send(
                "geometry.set",
                {
                    "viewId": view_id,
                    "targetType": "CLASSIFIER",
                    "targetId": target_id,
                    "x": x,
                    "y": y,
                    "w": w,
                    "h": h,
                    "zOrder": z,
                },
                targets=[{"scope": "classifier", "id": target_id}],
            )

    def classifier(self, name: str, kind: str) -> str:
        classifier_id = str(uuid.uuid4())
        self.send(
            "classifier.create",
            {"id": classifier_id, "name": name, "kind": kind},
            targets=[],
        )
        return classifier_id

    def attribute(
        self,
        classifier_id: str,
        name: str,
        type_name: str,
        visibility: str,
        default: str | None = None,
    ) -> None:
        payload = {
            "id": str(uuid.uuid4()),
            "name": name,
            "type": {"primitiveName": type_name},
            "visibility": visibility,
        }
        if default is not None:
            payload["defaultValue"] = default
        self.send("attribute.add", payload, targets=[{"scope": "classifier", "id": classifier_id}])

    def operation(self, classifier_id: str, name: str, return_type: str) -> None:
        self.send(
            "operation.add",
            {
                "id": str(uuid.uuid4()),
                "name": name,
                "returnType": {"primitiveName": return_type},
                "visibility": "PUBLIC",
            },
            targets=[{"scope": "classifier", "id": classifier_id}],
        )

    def constraint(self, classifier_id: str, expression: str) -> None:
        self.send(
            "constraint.add",
            {
                "id": str(uuid.uuid4()),
                "ownerType": "CLASSIFIER",
                "ownerId": classifier_id,
                "expression": expression,
                "severity": "ERROR",
            },
            targets=[{"scope": "classifier", "id": classifier_id}],
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="Base URL of a running backend")
    parser.add_argument("--project-name", default=f"Payments domain {uuid.uuid4().hex[:6]}")
    arguments = parser.parse_args()

    created = request(
        arguments.base_url,
        "POST",
        "/projects",
        {"name": arguments.project_name, "description": "Seeded demonstration diagram"},
    )
    project_id = created["id"]
    model = request(arguments.base_url, "GET", f"/projects/{project_id}/model")

    seeder = Seeder(arguments.base_url, project_id)
    seeder.run(model)

    final = request(arguments.base_url, "GET", f"/projects/{project_id}/model")
    print(f"project:  {project_id}")
    print(f"revision: {final['revision']} after {seeder.commands} commands")
    print(f"elements: {len(final['classifiers'])} classifiers, "
          f"{len(final['relationships'])} relationships")
    print(f"geometry: {len(final['views'][0]['element_geometry'])} placements")
    print(f"editor:   http://localhost:5173/projects/{project_id}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
