#!/usr/bin/env python3
"""Check the measured XMI interchange shape against EA's file and our emitted sample.

This is a file-only harness. It reuses the read-only inventory oracle rather than
maintaining another namespace, tag, or geometry parser.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shlex
import sys
import xml.etree.ElementTree as ET
from typing import Any

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ORACLE_DIR = ROOT / "tools" / "xmi-inventory"
DEFAULT_EA_FIXTURE = ROOT / "docs" / "fixtures" / "xmi" / "Prueba3.xml"
DEFAULT_EMITTED_SAMPLE = ROOT / "backend" / "target" / "xmi-sample.xml"
EXPECTED_EA_FIXTURE_SHA256 = "336d13b1ae87da5643dc74903e9787899aaeb3896eb2ca30d5c5a74a2cd0dab2"

# Keep the oracle read-only and use its dialect inventory/parser as the single
# source for XML classification, connector placement, geometry, and resolution.
# Suppress bytecode output so importing the oracle does not write into its directory.
sys.dont_write_bytecode = True
sys.path.insert(0, str(ORACLE_DIR))
from xmi_inventory import build_inventory  # noqa: E402


UNMEASURED = (
    "Successful Enterprise Architect diagram creation from our emitted document is not established. "
    "The recorded imports recovered eight emitted elements with their types resolved, but created zero "
    "diagrams while the EA-authored control created one. Three single-variable hypotheses were refuted; "
    "the cause remains unidentified. This file-only harness does not run EA.",
    "Whether Enterprise Architect rejects value=\"*\" for an unbounded multiplicity has not been tested. "
    "EA writing -1 is not evidence that it rejects *; the emitter follows the measured -1 spelling as an "
    "evidence-based decision, not as a demonstrated acceptance rule.",
    "The meaning of the connector Path grammar has not been established. The EA fixture has one non-empty "
    "Path sample and the second recorded EA file has an empty one: they show how Path is written, not what "
    "a reader accepts or whether coordinates are absolute or relative. The emitter therefore writes Path empty.",
    "A live EA round-trip of the emitted document is not verified by this harness. It validates file "
    "structure only; the recorded COM import observations are historical evidence, not rerun here.",
)


def display(value: Any) -> str:
    return value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, sort_keys=True)


def current_command() -> str:
    return "python " + shlex.join(sys.argv)


def shown_path(path: pathlib.Path) -> str:
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def report(
    results: list[bool],
    step: str,
    ok: bool,
    expected: Any,
    observed: Any,
    command: str,
) -> None:
    if not isinstance(ok, bool):
        raise TypeError(f"the assertion for {step!r} produced {type(ok).__name__}, not bool")
    results.append(ok)
    print(f"[{'OK' if ok else 'FAIL'}] {step}", flush=True)
    print(f"  command:  {command}", flush=True)
    print(f"  expected: {display(expected)}", flush=True)
    print(f"  observed: {display(observed)}", flush=True)


def inspect_document(path: pathlib.Path, label: str, results: list[bool]) -> dict[str, Any] | None:
    command = f"{current_command()} (xmi_inventory.build_inventory({shown_path(path)}))"
    print(f"\n--- {label}: {shown_path(path)} ---", flush=True)

    if not path.is_file():
        report(results, f"Required document exists: {shown_path(path)}", False,
               "a readable required XMI document", "MISSING", command)
        return None

    try:
        raw_before = path.read_bytes()
        digest_before = hashlib.sha256(raw_before).hexdigest()
        inventory = build_inventory(path)
        raw_after = path.read_bytes()
        digest_after = hashlib.sha256(raw_after).hexdigest()
    except (OSError, UnicodeError, ValueError, KeyError, LookupError, ET.ParseError) as error:
        report(results, f"Read and inventory {shown_path(path)}", False,
               "a readable, parseable XMI document", f"{type(error).__name__}: {error}", command)
        return None

    oracle_digest = inventory.get("fixtureIdentity", {}).get("sha256")
    expected_digest = (
        EXPECTED_EA_FIXTURE_SHA256
        if path.resolve() == DEFAULT_EA_FIXTURE.resolve()
        else None
    )
    identity_ok = (
        digest_before == digest_after == oracle_digest
        and inventory.get("bytes") == len(raw_before)
        and (expected_digest is None or oracle_digest == expected_digest)
    )
    identity = {
        "bytes": inventory.get("bytes"),
        "sha256": oracle_digest,
    }
    expected_identity = {
        "stableBytes": True,
        "sha256MatchesOracle": True,
        "expectedSha256": expected_digest or "record this run's SHA-256",
    }
    report(results, f"Pin exact document identity: {shown_path(path)}", identity_ok,
           expected_identity, identity, command)

    interchange = inventory.get("interchange", {})
    extension = interchange.get("extension", {})
    model = interchange.get("model", {})
    diagram = interchange.get("diagram", {})
    connectors = interchange.get("connectors", [])
    undeclared = inventory.get("undeclaredTags", ["inventory missing undeclaredTags"])

    report(results, f"Declared dialect vocabulary: {shown_path(path)}", undeclared == [],
           "no tags outside the oracle's declared vocabulary", undeclared, command)

    model_present = bool(model.get("elements")) and bool(model.get("name"))
    extension_present = bool(extension.get("directChildTags"))
    report(results, f"Measured UML model and EA extension are present: {shown_path(path)}",
           model_present and extension_present,
           "a populated uml:Model and an Enterprise Architect Extension",
           {"modelName": model.get("name"), "modelElements": len(model.get("elements", [])),
            "extensionChildren": extension.get("directChildTags", [])}, command)

    connector_location_ok = (
        extension.get("connectorsIsDirectChild") is True
        and extension.get("connectorsInsideDiagrams") == 0
    )
    report(results, f"Measured connector container location: {shown_path(path)}", connector_location_ok,
           "connectors directly under Extension and not nested in diagrams",
           {"directChild": extension.get("connectorsIsDirectChild"),
            "insideDiagrams": extension.get("connectorsInsideDiagrams")}, command)

    placements = diagram.get("shapePlacements", []) + diagram.get("connectorPlacements", [])
    unresolved = diagram.get("unresolvedSubjects", ["inventory missing unresolvedSubjects"])
    unclassified = diagram.get("unclassifiedPlacements", ["inventory missing unclassifiedPlacements"])
    placement_ok = (
        diagram.get("count", 0) > 0
        and bool(diagram.get("shapePlacements"))
        and bool(diagram.get("connectorPlacements"))
        and not unclassified
        and not unresolved
        and all(item.get("subjectResolved") is True for item in placements)
    )
    report(results, f"Measured diagram placement classification and subject resolution: {shown_path(path)}",
           placement_ok,
           "at least one diagram with classified shape and connector placements, all subjects resolved",
           {"diagrams": diagram.get("count"), "shapes": len(diagram.get("shapePlacements", [])),
            "connectors": len(diagram.get("connectorPlacements", [])),
            "unclassified": unclassified, "unresolvedSubjects": unresolved}, command)

    model_relations = model.get("relations", [])
    model_relation_ids = {relation.get("id") for relation in model_relations if relation.get("id")}
    classifier_ids = {element.get("id") for element in model.get("elements", []) if element.get("id")}
    connector_ids = {relation.get("id") for relation in connectors if relation.get("id")}
    endpoints_ok = bool(connectors) and all(
        relation.get("id")
        and relation.get("source", {}).get("id") in classifier_ids
        and relation.get("target", {}).get("id") in classifier_ids
        for relation in connectors
    )
    links_ok = (
        endpoints_ok
        and connector_ids == model_relation_ids
        and all(relation.get("modelType") for relation in connectors)
    )
    report(results, f"Measured connector references resolve to model relations and classifiers: {shown_path(path)}",
           bool(links_ok),
           "each extension connector maps to one UML relation and has source/target classifier references",
           {"modelRelations": len(model_relation_ids), "extensionConnectors": len(connector_ids),
            "unmatchedRelationIds": sorted(model_relation_ids ^ connector_ids),
            "connectorsWithMissingOrUnresolvedEndpoints": sum(
                1 for relation in connectors
                if not relation.get("id") or relation.get("source", {}).get("id") not in classifier_ids
                or relation.get("target", {}).get("id") not in classifier_ids
                or not relation.get("modelType")
            )}, command)

    roles = interchange.get("roles", {})
    role_count = roles.get("total", 0)
    role_parents = roles.get("byParent", {})
    roles_ok = (
        bool(connectors)
        and role_count == 2 * len(connectors)
        and role_parents == {"source": len(connectors), "target": len(connectors)}
    )
    report(results, f"Measured connector roles have one entry per endpoint: {shown_path(path)}", roles_ok,
           "one role below source and one below target for each connector",
           {"connectors": len(connectors), "roles": role_count, "byParent": role_parents}, command)

    labels = interchange.get("labels", {})
    label_keys = labels.get("keyCounts", {})
    labels_ok = (
        bool(connectors)
        and labels.get("connectorsWithCompleteLabels", 0) > 0
        and label_keys.get("mt", 0) == len(connectors)
    )
    report(results, f"Measured connector labels include the complete observed form: {shown_path(path)}", labels_ok,
           "at least one complete label set and an mt label for every connector",
           {"completeLabelSets": labels.get("connectorsWithCompleteLabels", 0),
            "labelKeyCounts": label_keys}, command)

    anchors = diagram.get("anchoredPlacements", [])
    anchor_ok = bool(anchors) and all(
        item.get("sourceMatchesShape") is True and item.get("targetMatchesShape") is True
        for item in anchors
    )
    report(results, f"Recorded connector anchors resolve to measured shapes: {shown_path(path)}", anchor_ok,
           "at least one measured anchor pair, with every present pair matching shape DUIDs",
           {"checkedAnchorPairs": len(anchors), "mismatches": sum(
               1 for item in anchors
               if item.get("sourceMatchesShape") is not True or item.get("targetMatchesShape") is not True
           )}, command)

    measured = {
        "file": shown_path(path),
        "bytes": inventory.get("bytes"),
        "sha256": oracle_digest,
        "undeclaredTags": undeclared,
        "elementsByType": model.get("elementCountsByType", {}),
        "featuresByKind": model.get("featureCountsByKind", {}),
        "modelRelations": len(model_relations),
        "extensionConnectorRecords": len(connectors),
        "roleCounts": roles,
        "labelKeyCounts": label_keys,
        "anchoredPlacements": len(anchors),
        "diagrams": diagram.get("count", 0),
        "shapePlacements": len(diagram.get("shapePlacements", [])),
        "connectorPlacements": len(diagram.get("connectorPlacements", [])),
        "pathCounts": diagram.get("pathCounts", {}),
        "unresolvedTypeReferences": interchange.get("notRepresentable", {}).get(
            "unresolvedTypeReferences"
        ),
        "unresolvedDiagramSubjects": len(unresolved) if isinstance(unresolved, list) else unresolved,
    }
    print(f"[MEASURED] {label}", flush=True)
    print(f"  command:  {command}", flush=True)
    print(f"  observed: {display(measured)}", flush=True)

    return {
        "file": shown_path(path),
        "bytes": inventory.get("bytes"),
        "sha256": oracle_digest,
        "undeclaredTags": undeclared,
        "modelElements": len(model.get("elements", [])),
        "features": model.get("featureCountsByKind", {}),
        "relations": len(model.get("relations", [])),
        "extensionConnectorRecords": len(connectors),
        "diagrams": diagram.get("count", 0),
        "shapePlacements": len(diagram.get("shapePlacements", [])),
        "connectorPlacements": len(diagram.get("connectorPlacements", [])),
        "pathCounts": diagram.get("pathCounts", {}),
        "unresolvedTypeReferences": interchange.get("notRepresentable", {}).get(
            "unresolvedTypeReferences"
        ),
        "unresolvedDiagramSubjects": len(unresolved) if isinstance(unresolved, list) else unresolved,
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate EA's XMI fixture and the emitted sample with the measured dialect inventory."
    )
    parser.add_argument(
        "--ea-fixture", type=pathlib.Path, default=DEFAULT_EA_FIXTURE,
        help="EA-authored positive control (default: docs/fixtures/xmi/Prueba3.xml)",
    )
    parser.add_argument(
        "--emitted-sample", type=pathlib.Path, default=DEFAULT_EMITTED_SAMPLE,
        help="document written by XmiSemanticEmitterTest (default: backend/target/xmi-sample.xml)",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    results: list[bool] = []
    command = current_command()

    print("=== What was measured ===", flush=True)
    ea_result = inspect_document(args.ea_fixture, "EA-authored positive control", results)
    emitted_result = inspect_document(args.emitted_sample, "Emitted sample", results)

    print("\n--- Recorded Enterprise Architect import measurements (not rerun by this file-only harness) ---",
          flush=True)
    print("[MEASURED, RECORDED IN odd/tasks/xmi-intercambio.md section 7.1]", flush=True)
    print("  command:  EA.Repository.ImportPackageXMI(guid, 'docs/fixtures/xmi/Prueba3.xml', "
          "ImportDiagrams=1, StripGUID=0) on a copy of sw1_p2.eapx", flush=True)
    print("  observed: +10 elements and +1 diagram; import completed in about 1.5 seconds.", flush=True)
    print("[MEASURED, RECORDED IN odd/tasks/xmi-intercambio.md section 7.2; repeated twice]", flush=True)
    print("  command:  EA.Repository.ImportPackageXMI(guid, 'backend/target/xmi-sample.xml', "
          "ImportDiagrams=1, StripGUID=0) on a copy of sw1_p2.eapx", flush=True)
    print("  observed: +8 elements with types resolved and +0 diagrams; EA's own file produced +1 diagram.",
          flush=True)
    print("  identity: the record describes a 19,196-byte sample but gives no SHA-256; the current sample's "
          "pinned identity above cannot be proven to match that historical import.", flush=True)

    print("\n=== What was NOT measured ===", flush=True)
    for item in UNMEASURED:
        print(f"- {item}", flush=True)

    passed = sum(results)
    failed = len(results) - passed
    print(f"\n=== XMI acceptance summary: {passed} passed, {failed} failed ===", flush=True)
    if failed:
        print("[FAIL] The EA-authored positive control and emitted document are both required; "
              "no failed assertion was weakened or skipped.", flush=True)
    else:
        print("[OK] EA's own file passed as the positive control, and the emitted sample passed the same checks.",
              flush=True)
    # Preserve the exact command in the transcript even for a run where inputs failed before inventory.
    if ea_result is None or emitted_result is None:
        print(f"[INFO] Required input failure was evaluated under: {command}", flush=True)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
