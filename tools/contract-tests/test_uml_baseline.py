"""Contract tests for the UML-BL-1 baseline inventory.

Why these tests exist
---------------------
"Compatible with UML 2.5" is an unbounded claim. The baseline exists to bound it,
and these tests exist so the bound is verifiable instead of promised in prose.

The oracle below is deliberately INDEPENDENT from the inventory: the mandatory
clause list is transcribed by hand from the assignment analysis, not derived from
the JSON under test. That is the whole point. If someone silently drops a
mandatory clause from the inventory, `test_every_mandatory_clause_is_covered`
fails. A test that read its expectations from the file it validates would always
pass and would be worthless.

Run from the repository root:

    python -m pytest tools/contract-tests -q
"""

from __future__ import annotations

import json
import pathlib
from typing import Any

import pytest
from jsonschema import Draft202012Validator

# --------------------------------------------------------------------------
# Locations. Resolved from this file so the suite runs from any directory.
# --------------------------------------------------------------------------

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTRATOS_DIR = REPO_ROOT / "docs" / "puds" / "contratos"
SCHEMA_PATH = CONTRATOS_DIR / "03-linea-base-uml.schema.json"
BASELINE_PATH = CONTRATOS_DIR / "03-linea-base-uml.v1.json"

TARGETS = ("persistence", "generation", "xmi")

# One construct per visibility value. The UML token is an English keyword and the
# construct name is Spanish prose, so the token is asserted against `umlElement`
# and never against `name`. An earlier version of this test looked for the token
# in the display name and failed on its own translation, which was a defect in the
# test, not in the baseline.
REQUIRED_VISIBILITY_CONSTRUCTS = (
    ("BL-VIS-01", "public"),
    ("BL-VIS-02", "private"),
    ("BL-VIS-03", "protected"),
    ("BL-VIS-04", "package"),
)

# --------------------------------------------------------------------------
# Oracle — transcribed from `docs/analisis-enunciado-parcial-1.md` section 6
# "Línea base de UML 2.5". Grouped exactly as the source groups it.
#
# One deliberate deviation: the source bullet "notas y restricciones" is a
# single bullet covering two different UML concepts. It is split into NOTES and
# CONSTRAINTS here so that neither concept can hide behind the other.
# --------------------------------------------------------------------------

MANDATORY_CLAUSES = frozenset(
    {
        # Clasificadores
        "CLASS",
        "ABSTRACT_CLASS",
        "INTERFACE",
        "ENUMERATION",
        "PACKAGE",
        # Características
        "ATTRIBUTE",
        "OPERATION",
        "PARAMETER_AND_TYPE",
        "VISIBILITY",
        "STATIC_MEMBER",
        "ABSTRACT_MEMBER",
        "DEFAULT_VALUE",
        # Relaciones
        "ASSOCIATION",
        "AGGREGATION",
        "COMPOSITION",
        "GENERALIZATION",
        "INTERFACE_REALIZATION",
        "DEPENDENCY",
        "MULTIPLICITY",
        "ROLES_AND_NAVIGABILITY",
        # Elementos complementarios
        "NOTES",
        "CONSTRAINTS",
        "STEREOTYPES",
        "STABLE_IDS",
        "LAYOUT_SEPARATION",
    }
)

# Markers that prove the scope text refuses to claim complete UML 2.5 coverage.
SUBSET_MARKERS = ("subconjunto", "no equivale")


# --------------------------------------------------------------------------
# Fixtures
# --------------------------------------------------------------------------


@pytest.fixture(scope="module")
def schema() -> dict[str, Any]:
    return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def baseline() -> dict[str, Any]:
    return json.loads(BASELINE_PATH.read_text(encoding="utf-8"))


def _constructs(baseline: dict[str, Any]) -> list[dict[str, Any]]:
    return baseline["constructs"]


def _mapping_of(construct: dict[str, Any], target: str) -> dict[str, Any] | None:
    for mapping in construct["mappings"]:
        if mapping["target"] == target:
            return mapping
    return None


def _source_paths(baseline: dict[str, Any]) -> list[tuple[str, str]]:
    """Every (construct id, source path) pair, with any #anchor stripped."""
    pairs = []
    for construct in _constructs(baseline):
        for source in construct["sources"]:
            pairs.append((construct["id"], source.split("#", 1)[0]))
    return pairs


# --------------------------------------------------------------------------
# The artifact is well formed
# --------------------------------------------------------------------------


def test_schema_is_a_valid_json_schema(schema: dict[str, Any]) -> None:
    Draft202012Validator.check_schema(schema)


def test_inventory_validates_against_schema(
    schema: dict[str, Any], baseline: dict[str, Any]
) -> None:
    errors = sorted(
        Draft202012Validator(schema).iter_errors(baseline),
        key=lambda error: list(error.absolute_path),
    )
    assert not errors, "Inventory does not satisfy its schema:\n" + "\n".join(
        f"  {list(e.absolute_path)}: {e.message}" for e in errors
    )


def test_construct_ids_are_unique(baseline: dict[str, Any]) -> None:
    ids = [construct["id"] for construct in _constructs(baseline)]
    duplicates = sorted({item for item in ids if ids.count(item) > 1})
    assert not duplicates, f"Duplicated construct ids: {duplicates}"


def test_targets_are_the_three_declared_columns(baseline: dict[str, Any]) -> None:
    assert tuple(baseline["targets"]) == TARGETS


# --------------------------------------------------------------------------
# No construct may hide a missing column
# --------------------------------------------------------------------------


def test_every_construct_maps_each_target_exactly_once(baseline: dict[str, Any]) -> None:
    problems = []
    for construct in _constructs(baseline):
        seen = [mapping["target"] for mapping in construct["mappings"]]
        if sorted(seen) != sorted(TARGETS):
            problems.append(f"{construct['id']}: maps {seen}, expected one of each {list(TARGETS)}")
    assert not problems, "Constructs with an incomplete or duplicated mapping:\n" + "\n".join(
        f"  {problem}" for problem in problems
    )


def test_none_mappings_declare_a_reason(baseline: dict[str, Any]) -> None:
    """A missing representation is allowed. A missing representation *and* no
    reason is exactly the silent gap this baseline exists to prevent."""
    problems = []
    for construct in _constructs(baseline):
        for mapping in construct["mappings"]:
            if mapping["value"] == "NONE":
                reason = mapping.get("reason", "")
                if len(reason.strip()) < 20:
                    problems.append(f"{construct['id']}/{mapping['target']}: NONE with reason={reason!r}")
    assert not problems, "NONE mappings without a real reason:\n" + "\n".join(
        f"  {problem}" for problem in problems
    )


# --------------------------------------------------------------------------
# The declaration is honest about what it does and does not support
# --------------------------------------------------------------------------


def test_every_mandatory_clause_is_covered(baseline: dict[str, Any]) -> None:
    covered: set[str] = set()
    for construct in _constructs(baseline):
        covered.update(construct["covers"])
    missing = sorted(MANDATORY_CLAUSES - covered)
    assert not missing, (
        "Mandatory clauses from the assignment analysis are not covered by any "
        f"construct: {missing}"
    )


def test_no_construct_invents_a_clause(baseline: dict[str, Any]) -> None:
    covered: set[str] = set()
    for construct in _constructs(baseline):
        covered.update(construct["covers"])
    invented = sorted(covered - MANDATORY_CLAUSES)
    assert not invented, (
        "Constructs reference clause keys that do not exist in the mandatory "
        f"clause list: {invented}"
    )


def test_unsupported_constructs_declare_a_reason(baseline: dict[str, Any]) -> None:
    problems = [
        construct["id"]
        for construct in _constructs(baseline)
        if not construct["supported"] and len(construct.get("unsupportedReason", "").strip()) < 20
    ]
    assert not problems, f"Unsupported constructs without a real reason: {problems}"


def test_supported_constructs_have_at_least_one_concrete_mapping(
    baseline: dict[str, Any]
) -> None:
    """Nothing may be declared supported if it maps to nothing anywhere.
    This is the anti-overclaim rule: support must be materializable somewhere."""
    problems = []
    for construct in _constructs(baseline):
        if not construct["supported"]:
            continue
        concrete = [m for m in construct["mappings"] if m["value"] != "NONE"]
        if not concrete:
            problems.append(construct["id"])
    assert not problems, (
        "Constructs declared supported but mapped to NONE in all three targets: "
        f"{problems}"
    )


def test_every_visibility_value_is_enumerated(baseline: dict[str, Any]) -> None:
    """The analysis names four visibilities: public, private, protected, package.
    A blanket "visibility" entry would hide an unsupported value, so each one
    must exist as its own construct and must carry its own UML token."""
    by_id = {construct["id"]: construct for construct in _constructs(baseline)}
    missing = [
        construct_id
        for construct_id, _ in REQUIRED_VISIBILITY_CONSTRUCTS
        if construct_id not in by_id
    ]
    assert not missing, f"Visibility is not enumerated per value; missing constructs: {missing}"

    problems = []
    for construct_id, uml_value in REQUIRED_VISIBILITY_CONSTRUCTS:
        element = by_id[construct_id]["umlElement"]
        if uml_value not in element:
            problems.append(f"{construct_id}: umlElement={element!r} does not carry {uml_value!r}")
    assert not problems, (
        "Visibility constructs do not carry their UML token in umlElement:\n"
        + "\n".join(f"  {problem}" for problem in problems)
    )


def test_declared_sources_exist_on_disk(baseline: dict[str, Any]) -> None:
    missing = sorted(
        {
            source
            for _, source in _source_paths(baseline)
            if not (REPO_ROOT / source).is_file()
        }
    )
    assert not missing, f"Constructs cite sources that do not exist: {missing}"


def test_proposed_baseline_is_not_declared_ratified(baseline: dict[str, Any]) -> None:
    """The analysis states the baseline must be validated with the docente. Until
    that happens, the artifact must not claim to be ratified."""
    if baseline["status"] == "proposed":
        assert baseline["ratification"]["status"] == "PENDING", (
            "status is 'proposed' but ratification claims "
            f"{baseline['ratification']['status']!r}"
        )
        assert baseline["ratification"]["evidence"] is None, (
            "status is 'proposed' but ratification carries evidence"
        )


def test_scope_refuses_to_claim_complete_uml(baseline: dict[str, Any]) -> None:
    scope = baseline["scope"].lower()
    assert any(marker in scope for marker in SUBSET_MARKERS), (
        "The scope text must state that this is a subset of UML 2.5, not full "
        f"coverage. Scope was: {baseline['scope']!r}"
    )


def test_open_gaps_have_an_owner_and_name_what_they_block(baseline: dict[str, Any]) -> None:
    problems = []
    for gap in baseline["openGaps"]:
        if len(gap["owner"].strip()) < 2:
            problems.append(f"{gap['id']}: no owner")
        if not gap["blocks"]:
            problems.append(f"{gap['id']}: does not name what it blocks")
    assert not problems, "Gaps that are complaints rather than gaps:\n" + "\n".join(
        f"  {problem}" for problem in problems
    )
