"""Verificacion del inventario del dialecto XMI de Enterprise Architect.

Que garantiza
--------------
1. Que **ningun tag del fixture quede sin clasificar**. Si EA emite algo que el
   vocabulario no declara, la suite falla y obliga a clasificarlo.
2. Que **cada constructo de la linea base UML-BL-1 este evidenciado por el
   fixture o declarado sin evidencia, con su motivo**. Es el mismo invariante que
   usa `tools/contract-tests/test_uml_baseline.py`: declarar, no esconder.
3. Que las cifras del contrato no se muevan en silencio.

Presencia no es evidencia: `isStatic` aparece 33 veces en el fixture y las 33
valen `false`. Por eso la evidencia se expresa con **valores minimos**, no con la
mera aparicion de una etiqueta, y por eso los constructos que el fixture no
ejercita estan en `NOT_EVIDENCED` en vez de contarse como probados.

El oraculo esta escrito a mano a partir del analisis del enunciado y del
inventario de la linea base. No se deriva del inventario del dialecto.

Uso, desde la raiz del repositorio:

    python -m pytest tools/xmi-inventory -q
"""

from __future__ import annotations

import hashlib
import json
import pathlib
import re
import subprocess
import sys
from typing import Any

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from xmi_inventory import DEFAULT_FIXTURE, build_inventory, load_fixture  # noqa: E402

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / "tools" / "xmi-acceptance"))
from xmi_acceptance import (  # noqa: E402
    DECLARED_VOCABULARY_DEVIATIONS,
    inspect_document,
    unmeasured_vocabulary_tags,
)

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
BL1_PATH = REPO_ROOT / "docs" / "puds" / "contratos" / "03-linea-base-uml.v1.json"

# --------------------------------------------------------------------------
# Oracolo 1: evidencia del fixture por constructo de UML-BL-1.
#
# Cada entrada es (ruta dentro del inventario, valor minimo exigido). El valor
# minimo es a la vez la evidencia y el candado de regresion.
# --------------------------------------------------------------------------

BASELINE_EVIDENCE: dict[str, tuple[tuple[str, ...], int]] = {
    # Clasificadores
    "BL-CLS-01": (("semantic", "type:Class"), 8),
    "BL-CLS-03": (("semantic", "type:Interface"), 1),
    "BL-CLS-04": (("semantic", "type:Enumeration"), 1),
    "BL-CLS-05": (("findings", "enumerations"), 1),
    "BL-CLS-06": (("semantic", "type:Package"), 1),
    "BL-CLS-07": (("semantic", "typeReferencePrefixes", "EAJava"), 12),
    # Caracteristicas
    "BL-FEA-01": (("semantic", "tag:ownedAttribute"), 23),
    "BL-FEA-02": (("semantic", "tag:ownedOperation"), 11),
    "BL-FEA-03": (("semantic", "tag:ownedParameter"), 11),
    "BL-VIS-01": (("semantic", "visibility", "public"), 46),
    "BL-VIS-02": (("semantic", "visibility", "private"), 17),
    # Relaciones
    "BL-REL-01": (("semantic", "type:Association"), 6),
    "BL-REL-02": (("semantic", "aggregation", "shared"), 1),
    "BL-REL-03": (("semantic", "aggregation", "composite"), 1),
    "BL-REL-04": (("semantic", "type:Generalization"), 1),
    "BL-REL-05": (("semantic", "type:Realization"), 1),
    "BL-REL-07": (("semantic", "multiplicity", "upperValue"), 33),
    "BL-REL-08": (("findings", "associationEnds", "named"), 10),
    "BL-REL-10": (("findings", "associationEnds", "total"), 10),
    # Complementarios
    "BL-CMP-01": (("findings", "notes", "documentationAttributes"), 12),
    "BL-CMP-03": (("findings", "stereotypes", "blocks"), 32),
    "BL-CMP-04": (("semantic", "identifierPrefixes", "EAID"), 139),
    "BL-CMP-05": (("findings", "diagrams", "withGeometry"), 18),
}

# --------------------------------------------------------------------------
# Oracolo 2: constructos que este fixture NO puede evidenciar, y por que.
#
# No es una lista de pendientes: es una declaracion de limite. Un fixture sin
# valores predeterminados no prueba nada sobre valores predeterminados, ni a
# favor ni en contra.
# --------------------------------------------------------------------------

NOT_EVIDENCED: dict[str, str] = {
    "BL-CLS-02": "No hay ninguna uml:Class con isAbstract='true' en todo el documento. El unico clasificador abstracto del fixture es la interfaz ProcesadorPago, que ya es evidencia de BL-CLS-03.",
    "BL-FEA-04": "No existe ningun tag defaultValue en el documento, ni bloque de EA equiparable. El fixture no ejercita valores predeterminados.",
    "BL-FEA-05": "La bandera isStatic aparece 33 veces en el modelo y las 33 valen false. Presencia de la bandera no es evidencia de la capacidad.",
    "BL-FEA-06": "isAbstract solo vale true dos veces en todo el documento, sobre una interfaz y sobre un bloque properties de EA. Ningun miembro, ni atributo ni operacion, lo ejercita en true.",
    "BL-VIS-03": "El modelo solo lleva visibilidad public y private. No hay un solo valor protected.",
    "BL-VIS-04": "El modelo solo lleva visibilidad public y private. No hay un solo valor package.",
    "BL-REL-06": "No hay ningun elemento uml:Dependency en el subarbol semantico Model.",
    "BL-REL-09": "El atributo isNavigable no aparece ni una vez en el subarbol UML: vive solo en los bloques modifiers de EA, y en los 16 casos vale false.",
    "BL-CMP-02": "No hay ninguna restriccion UML. Los 16 bloques constraints de EA cuelgan de connector/source y connector/target, y estan vacios.",
}

# Constructos cuya evidencia depende de un valor en true. Si el fixture no
# ejerce el valor, el constructo no puede figurar como evidenciado.
VALUE_DEPENDENT = {
    "BL-FEA-05": "isStatic",
    "BL-FEA-06": "isAbstract",
}


@pytest.fixture(scope="module")
def inventory() -> dict[str, Any]:
    return build_inventory(DEFAULT_FIXTURE)


@pytest.fixture(scope="module")
def baseline_ids() -> set[str]:
    baseline = json.loads(BL1_PATH.read_text(encoding="utf-8"))
    return {construct["id"] for construct in baseline["constructs"]}


def fact(inventory: dict[str, Any], path: tuple[str, ...]) -> Any:
    node: Any = inventory
    for key in path:
        if not isinstance(node, dict) or key not in node:
            return None
        node = node[key]
    return node


# --------------------------------------------------------------------------
# 1. Ningun tag sin clasificar
# --------------------------------------------------------------------------


def test_no_tag_is_left_unclassified(inventory: dict[str, Any]) -> None:
    undeclared = inventory["undeclaredTags"]
    assert not undeclared, (
        "The fixture emits tags the dialect vocabulary does not declare. Classify "
        f"them deliberately: {undeclared}"
    )


def test_every_tag_belongs_to_a_declared_family(inventory: dict[str, Any]) -> None:
    known = {"plumbing", "uml-element", "uml-structure", "ea-proprietary"}
    unknown = {
        tag: families
        for tag, families in inventory["tagFamily"].items()
        if not set(families) <= known
    }
    assert not unknown, f"Tags with an unexpected family: {unknown}"


def test_context_dependent_tags_are_declared_ambiguous(inventory: dict[str, Any]) -> None:
    """`type` means Property.type in the model and something else inside EA's
    operation blocks. That ambiguity must be declared, not smoothed over."""
    ambiguous = inventory["ambiguousTags"]
    for tag in ("type", "element", "ownedEnd"):
        assert tag in ambiguous, f"{tag!r} should be reported as context-ambiguous"
    assert "type" in ambiguous and len(ambiguous["type"]) >= 2


# --------------------------------------------------------------------------
# 2. La particion de la linea base: evidenciado o declarado sin evidencia
# --------------------------------------------------------------------------


def test_evidence_and_gaps_partition_the_baseline(baseline_ids: set[str]) -> None:
    evidenced = set(BASELINE_EVIDENCE)
    not_evidenced = set(NOT_EVIDENCED)

    assert not evidenced & not_evidenced, (
        f"Constructs listed as both evidenced and unevidenced: {sorted(evidenced & not_evidenced)}"
    )
    missing = sorted(baseline_ids - (evidenced | not_evidenced))
    assert not missing, (
        "Baseline constructs that this fixture neither evidences nor declares "
        f"unevidenced: {missing}. Every construct must be accounted for."
    )
    unknown = sorted((evidenced | not_evidenced) - baseline_ids)
    assert not unknown, f"Entries that do not correspond to a baseline construct: {unknown}"


def test_every_declared_unevidenced_construct_has_a_real_reason() -> None:
    weak = [
        construct_id
        for construct_id, reason in NOT_EVIDENCED.items()
        if len(reason.strip()) < 40
    ]
    assert not weak, f"Unevidenced constructs without a real reason: {weak}"


def test_declared_evidence_actually_holds(inventory: dict[str, Any]) -> None:
    problems = []
    for construct_id, (path, minimum) in BASELINE_EVIDENCE.items():
        value = fact(inventory, path)
        if value is None:
            problems.append(f"{construct_id}: inventory has no {'/'.join(path)}")
            continue
        # Some facts are collections rather than scalars: their size is the count.
        observed = len(value) if isinstance(value, (list, dict)) else value
        if observed < minimum:
            problems.append(
                f"{construct_id}: {'/'.join(path)}={observed} is below the declared minimum {minimum}"
            )
    assert not problems, "Declared evidence does not hold:\n" + "\n".join(
        f"  {problem}" for problem in problems
    )


def test_no_construct_is_evidenced_by_a_flag_that_is_never_true(
    inventory: dict[str, Any], baseline_ids: set[str]
) -> None:
    """The trap this suite exists to avoid: a boolean that is always false proves
    nothing. If a construct depends on a flag, that flag must appear in true."""
    flags_true = inventory["semantic"]["flagsTrue"]
    violations = []
    for construct_id, flag in VALUE_DEPENDENT.items():
        if construct_id in BASELINE_EVIDENCE and flag not in flags_true:
            violations.append(f"{construct_id} is evidenced by {flag}, which never appears true")
    assert not violations, "\n".join(violations)


# --------------------------------------------------------------------------
# 3. Los hallazgos que sostienen la politica de preservacion
# --------------------------------------------------------------------------


def test_ea_owns_the_large_majority_of_the_document(inventory: dict[str, Any]) -> None:
    families = inventory["familyCounts"]
    total = sum(families.values())
    share = families["ea-proprietary"] / total
    assert families["ea-proprietary"] == 953
    assert families["uml-element"] == 153
    assert share > 0.8, f"EA proprietary share dropped to {share:.1%}"


def test_navigability_never_reaches_the_uml_tree(inventory: dict[str, Any]) -> None:
    """Navigability decides the owning side of a generated relation, and EA keeps
    it only in its own blocks. Dropping the extension means losing it."""
    assert inventory["semantic"]["navigabilityAttributesInModel"] == 0
    assert "isNavigable" not in inventory["semantic"]["flagsTrue"]


def test_enumeration_literals_are_not_serialized_as_uml_literals(
    inventory: dict[str, Any],
) -> None:
    enumerations = inventory["findings"]["enumerations"]
    assert len(enumerations) == 1
    enumeration = enumerations[0]
    assert enumeration["name"] == "EstadoReserva"
    assert enumeration["literalShape"] == "ownedAttribute", (
        "If EA starts emitting ownedLiteral, the import rule in the contract must change"
    )
    assert enumeration["literals"] == ["PENDIENTE", "CONFIRMADA", "CANCELADA", "COMPLETADA"]


def test_stereotype_blocks_are_empty_scaffolding(inventory: dict[str, Any]) -> None:
    stereotypes = inventory["findings"]["stereotypes"]
    assert stereotypes["blocks"] == 32
    assert stereotypes["withPayload"] == 0
    assert stereotypes["byParent"] == {"attribute": 21, "operation": 11}


def test_constraints_travel_empty_in_the_connector_extension(
    inventory: dict[str, Any],
) -> None:
    constraints = inventory["findings"]["constraints"]
    assert constraints["blocks"] == 16
    assert constraints["withContent"] == 0
    assert constraints["byParent"] == {"source": 8, "target": 8}


def test_the_uml_comment_channel_is_absent_from_the_model(inventory: dict[str, Any]) -> None:
    """The standard channel is absent from the model. This does NOT mean the
    fixture carries no notes: see test_ea_does_carry_model_element_notes."""
    comments = inventory["findings"]["comments"]
    assert comments["total"] == 1
    assert comments["insideProfile"] == 1
    assert comments["onModelElements"] == 0


def test_ea_leaks_into_the_uml_subtree(inventory: dict[str, Any]) -> None:
    assert inventory["modelLeaks"] == {"_lineStyle": 8}


def test_some_type_references_have_no_target(inventory: dict[str, Any]) -> None:
    assert inventory["semantic"]["typeReferencesWithoutTarget"] == 4
    assert inventory["semantic"]["typeReferencePrefixes"]["EAnone"] == 2


def test_fixture_encoding_is_the_one_declared(inventory: dict[str, Any]) -> None:
    assert inventory["declaredEncoding"] == "windows-1252"
    assert inventory["xmiVersion"] == "2.1"
    assert inventory["exporter"]["exporter"] == "Enterprise Architect"
    assert inventory["extension"]["extenderID"] == "6.5"


def test_visibility_is_written_with_inconsistent_case(inventory: dict[str, Any]) -> None:
    """A case-sensitive importer silently loses 16 values. The import rule has to
    normalise before comparing, and this test keeps the hazard visible."""
    visibility = inventory["findings"]["visibilityCase"]
    assert visibility == {"public": 70, "private": 17, "Public": 16}


def test_the_two_elements_containers_are_not_lumped(inventory: dict[str, Any]) -> None:
    """Two different containers are called `elements`: the diagram's placed elements
    and the extension's metadata mirror. Counting them together produced a 29 that
    was neither figure. Same defect the classifier metric had."""
    diagrams = inventory["findings"]["diagrams"]
    assert diagrams["placedElements"] == 18
    assert diagrams["withGeometry"] == 18
    assert diagrams["metadataMirrorElements"] == 11
    assert diagrams["connectors"] == 8


def test_definition_and_duplicate_table_is_pinned(inventory: dict[str, Any]) -> None:
    """Section 2.1 of the contract quotes this table. An unasserted metric that
    backs a published figure is how the previous errors survived."""
    by_subtree = inventory["umlTypesBySubtree"]
    model = by_subtree["Model"]
    assert model["uml:Class"] == 8
    assert model["uml:Interface"] == 1
    assert model["uml:Enumeration"] == 1
    assert model["uml:Package"] == 1
    assert model["uml:Property"] == 33

    extension = by_subtree["Extension"]
    assert extension["uml:Class"] == 8
    assert extension["uml:Package"] == 4
    assert extension["uml:PrimitiveType"] == 10
    assert extension["uml:Property"] == 2
    assert extension["uml:Comment"] == 1
    assert extension["uml:Stereotype"] == 1


def test_the_partition_has_the_declared_size() -> None:
    """The counts quoted in the contract are pinned here. Hand-editing them in prose
    already produced one wrong figure, so the number lives where it can fail."""
    assert len(BASELINE_EVIDENCE) == 23
    assert len(NOT_EVIDENCED) == 9


def test_ea_blocks_are_measured_with_children_in_mind(inventory: dict[str, Any]) -> None:
    """An earlier version called `tags` empty because it only looked at attributes
    and text. It has 8 children. Emptiness means no attributes, no text and no
    children, and the aggregate is 43.4%, not 'mostly'."""
    totals = inventory["findings"]["eaTotals"]
    assert totals["elements"] == 953
    assert totals["empty"] == 414

    payload = inventory["eaBlockPayload"]
    assert payload["documentation"]["elements"] == 67
    assert payload["documentation"]["empty"] == 66
    assert payload["tags"]["elements"] == 78
    assert payload["tags"]["withChildren"] == 8
    assert payload["tags"]["empty"] == 70
    for tag, count in (
        ("styleex", 43),
        ("stereotype", 32),
        ("constraints", 16),
        ("options", 21),
        ("initial", 21),
        ("behaviour", 11),
    ):
        assert payload[tag]["empty"] == count, f"{tag} is not fully empty"


@pytest.fixture(scope="module")
def payload() -> dict:
    return build_inventory(DEFAULT_FIXTURE)["eaBlockPayload"]


@pytest.fixture(scope="module")
def notes() -> dict:
    return build_inventory(DEFAULT_FIXTURE)["findings"]["notes"]


def test_ea_does_carry_model_element_notes(notes: dict) -> None:
    """An earlier version of the contract asserted the fixture carries no model
    note. It does: EA describes model elements in a documentation ATTRIBUTE of
    its own element blocks, which is not an element and was never looked at."""
    assert notes["documentationAttributes"] == 12
    assert notes["byParent"] == {"element": 11, "diagram": 1}
    assert notes["documentationValueAttributes"] == 1
    assert notes["umlComments"] == 1
    assert notes["umlCommentsOnModelElements"] == 0
    assert len(notes["examples"]) == 3
    assert all(text.strip() for text in notes["examples"])


def test_no_abstract_class_exists_in_the_fixture(inventory: dict[str, Any]) -> None:
    """The single abstract classifier is an interface. An earlier version of the
    harness counted it as an abstract class by lumping every classifier kind into
    one metric, which made a false claim survive."""
    semantic = inventory["semantic"]
    assert semantic.get("classesAbstract") is None
    assert semantic["otherClassifiersAbstract"] == 1


def test_ownership_of_the_notes_channel_is_declared(notes: dict) -> None:
    assert notes["documentationAttributes"] > 0, "if notes disappear, the import rule must change"


def test_declared_owning_side_ends_all_carry_a_role_name(inventory: dict[str, Any]) -> None:
    ends = inventory["findings"]["associationEnds"]
    assert ends["total"] == 10
    assert ends["named"] == 10


# --------------------------------------------------------------------------
# 4. Oraculo ejecutable del intercambio y de la geometria del diagrama
# --------------------------------------------------------------------------


def test_fixture_identity_is_pinned_and_byte_exact(inventory: dict[str, Any]) -> None:
    raw = DEFAULT_FIXTURE.read_bytes()
    expected_sha256 = "336d13b1ae87da5643dc74903e9787899aaeb3896eb2ca30d5c5a74a2cd0dab2"

    assert len(raw) == 91440
    assert inventory["bytes"] == 91440
    declaration = re.search(rb'encoding="([^"]+)"', raw[:120])
    assert declaration is not None
    assert declaration.group(1).decode("ascii") == "windows-1252"
    assert inventory["declaredEncoding"] == "windows-1252"
    assert "Prueba didáctica" in inventory["findings"]["notes"]["examples"][0]
    assert hashlib.sha256(raw).hexdigest() == expected_sha256
    assert inventory["fixtureIdentity"]["sha256"] == expected_sha256

    attributes = (REPO_ROOT / ".gitattributes").read_text(encoding="utf-8")
    assert "docs/fixtures/** -text" in attributes.splitlines()
    head_blob = subprocess.run(
        ["git", "show", "HEAD:docs/fixtures/xmi/Prueba3.xml"],
        cwd=REPO_ROOT,
        check=True,
        capture_output=True,
    ).stdout
    assert head_blob == raw
    assert hashlib.sha256(head_blob).hexdigest() == expected_sha256


def test_extension_structure_and_model_root_are_pinned(inventory: dict[str, Any]) -> None:
    exchange = inventory["interchange"]
    extension = exchange["extension"]
    assert extension["directChildEntryCounts"] == {
        "elements": 11,
        "connectors": 8,
        "primitivetypes": 1,
        "profiles": 1,
        "diagrams": 1,
    }
    assert extension["directChildTags"] == [
        "elements",
        "connectors",
        "primitivetypes",
        "profiles",
        "diagrams",
    ]
    assert extension["connectorsIsDirectChild"] is True
    assert extension["connectorsInsideDiagrams"] == 0

    model = exchange["model"]
    assert model["name"] == "EA_Model"
    assert model["id"] == ""
    assert model["elementCountsByType"] == {
        "uml:Class": 8,
        "uml:PrimitiveType": 4,
        "uml:Interface": 1,
        "uml:Enumeration": 1,
        "uml:Package": 1,
    }
    assert model["featureCountsByKind"] == {
        "ownedAttribute": 23,
        "ownedOperation": 11,
        "ownedParameter": 11,
    }
    assert len(model["relations"]) == 8


def test_interchange_relations_measure_endpoints_roles_and_labels(
    inventory: dict[str, Any],
) -> None:
    exchange = inventory["interchange"]
    assert len(exchange["connectors"]) == 8
    assert exchange["labels"]["connectorsWithCompleteLabels"] >= 1
    assert exchange["labels"]["keyCounts"] == {
        "lt": 6,
        "rt": 6,
        "lb": 6,
        "rb": 6,
        "mt": 8,
    }
    assert all(relation["source"]["id"] for relation in exchange["connectors"])
    assert all(relation["target"]["id"] for relation in exchange["connectors"])
    assert exchange["roles"] == {
        "total": 16,
        "byParent": {"source": 8, "target": 8},
    }


def test_diagram_placements_are_split_by_geometry_and_subject_resolution(
    inventory: dict[str, Any],
) -> None:
    diagram = inventory["interchange"]["diagram"]
    assert diagram["count"] == 1
    assert diagram["placementCount"] == 18
    assert len(diagram["shapePlacements"]) == 10
    assert len(diagram["connectorPlacements"]) == 8
    assert diagram["unclassifiedPlacements"] == []
    assert all(
        set(placement["bounds"]) == {"Left", "Top", "Right", "Bottom"}
        and placement["seqno"]
        and re.fullmatch(r"[0-9A-F]{8}", placement["duid"])
        for placement in diagram["shapePlacements"]
    )
    assert all(
        all(f"{key}=" in placement["geometry"] for key in ("SX", "SY", "EX", "EY", "Path"))
        and not any(f"{key}=" in placement["geometry"] for key in ("Left", "Top", "Right", "Bottom"))
        and "seqno" not in placement
        for placement in diagram["connectorPlacements"]
    )
    assert len(diagram["shapePlacements"]) + len(diagram["connectorPlacements"]) == 18
    assert len(diagram["unresolvedSubjects"]) == 0
    assert all(
        placement["subjectResolved"]
        for placement in diagram["shapePlacements"] + diagram["connectorPlacements"]
    )

    # The fixture contradicts the prose claim that all eight Path values are empty.
    assert diagram["pathCounts"] == {"empty": 7, "nonempty": 1}
    assert diagram["nonemptyPathValues"] == ["622:-260$"]

    measured = inventory["findings"]["diagrams"]
    assert measured["withGeometry"] == 18  # legacy aggregate remains compatible
    assert measured["shapeWithGeometry"] == 10
    assert measured["connectorWithGeometry"] == 8
    assert inventory["interchange"]["notRepresentable"]["unresolvedTypeReferences"] == 4


def test_connector_anchors_match_shape_duids_in_the_style_attribute(
    inventory: dict[str, Any],
) -> None:
    diagram = inventory["interchange"]["diagram"]
    assert len(diagram["anchoredPlacements"]) >= 1
    anchor = diagram["anchoredPlacements"][0]
    assert anchor["mode"] == "3"
    assert anchor["sourceDuid"] in diagram["shapeDuids"]
    assert anchor["targetDuid"] in diagram["shapeDuids"]
    assert anchor["sourceMatchesShape"] is True
    assert anchor["targetMatchesShape"] is True

    placement = next(
        item
        for item in diagram["connectorPlacements"]
        if item["anchors"]["sourceDuid"] and item["anchors"]["targetDuid"]
    )
    assert "Mode=3" in placement["style"]
    assert "SOID=" in placement["style"] and "EOID=" in placement["style"]
    assert "Mode=" not in placement["geometry"]
    assert "SOID=" not in placement["geometry"] and "EOID=" not in placement["geometry"]


def test_render_includes_the_interchange_measurements(inventory: dict[str, Any]) -> None:
    from xmi_inventory import render

    output = render(inventory)
    assert "Intercambio XMI:" in output
    assert "10 figuras" in output
    assert "8 conectores" in output
    assert "Path de conectores" in output
    assert "labels" in output


def _write_synthetic_xmi(
    path: pathlib.Path,
    relations: list[dict[str, Any]],
    extra_model_tags: str = "",
) -> pathlib.Path:
    relation_xml = []
    connector_xml = []
    placement_xml = [
        '<element subject="C1" geometry="Left=0;Top=0;Right=10;Bottom=10;" '
        'seqno="1" style="DUID=AAAAAAAA;"/>',
        '<element subject="C2" geometry="Left=20;Top=0;Right=30;Bottom=10;" '
        'seqno="2" style="DUID=BBBBBBBB;"/>',
    ]
    for relation in relations:
        name = relation.get("name")
        name_attribute = f' name="{name}"' if name is not None else ""
        relation_xml.append(
            f'<packagedElement xmi:type="uml:{relation["type"]}" '
            f'xmi:id="{relation["id"]}"{name_attribute}/>'
        )
        labels = " ".join(
            f'{key}="{value}"' for key, value in relation.get("labels", {}).items()
        )
        connector_xml.append(
            f'<connector xmi:idref="{relation["id"]}"{name_attribute}>'
            '<source xmi:idref="C1"><model name="A" type="Class"/>'
            '<role name="source"/><type multiplicity="1"/></source>'
            '<target xmi:idref="C2"><model name="B" type="Class"/>'
            '<role name="target"/><type multiplicity="1"/></target>'
            f'<properties ea_type="{relation["type"]}"/><labels {labels}/></connector>'
        )
        placement_xml.append(
            f'<element subject="{relation["id"]}" '
            'geometry="SX=0;SY=0;EX=0;EY=0;Path=;" '
            'style="Mode=3;SOID=AAAAAAAA;EOID=BBBBBBBB;"/>'
        )

    document = (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<xmi:XMI xmlns:xmi="http://schema.omg.org/spec/XMI/2.1" '
        'xmlns:uml="http://schema.omg.org/spec/UML/2.1">'
        '<uml:Model xmi:type="uml:Model" name="Synthetic">'
        '<packagedElement xmi:type="uml:Class" xmi:id="C1" name="A"/>'
        '<packagedElement xmi:type="uml:Class" xmi:id="C2" name="B"/>'
        + "".join(relation_xml)
        + extra_model_tags
        + '</uml:Model><xmi:Extension extender="Enterprise Architect"><connectors>'
        + "".join(connector_xml)
        + '</connectors><diagrams><diagram><elements>'
        + "".join(placement_xml)
        + '</elements></diagram></diagrams></xmi:Extension></xmi:XMI>'
    )
    path.write_text(document, encoding="utf-8")
    return path


def _harness_output(path: pathlib.Path, capsys: pytest.CaptureFixture[str]) -> str:
    results: list[bool] = []
    assert inspect_document(path, "Synthetic XMI", results) is not None
    return capsys.readouterr().out


def test_dependency_is_counted_as_a_model_relation(tmp_path: pathlib.Path) -> None:
    path = _write_synthetic_xmi(
        tmp_path / "dependency.xmi",
        [{"id": "D1", "type": "Dependency", "name": "uses", "labels": {"mt": "uses"}}],
    )

    relations = build_inventory(path)["interchange"]["model"]["relations"]

    assert relations == [{"id": "D1", "type": "uml:Dependency", "name": "uses"}]


def test_dependency_connector_matches_its_model_relation(tmp_path: pathlib.Path) -> None:
    path = _write_synthetic_xmi(
        tmp_path / "dependency-connector.xmi",
        [{"id": "D1", "type": "Dependency", "name": "uses", "labels": {"mt": "uses"}}],
    )

    interchange = build_inventory(path)["interchange"]

    assert interchange["connectors"][0]["id"] == "D1"
    assert interchange["connectors"][0]["modelType"] == "uml:Dependency"


def test_anonymous_relation_may_omit_mt_while_named_relation_keeps_it(
    tmp_path: pathlib.Path, capsys: pytest.CaptureFixture[str]
) -> None:
    path = _write_synthetic_xmi(
        tmp_path / "anonymous-relation.xmi",
        [
            {
                "id": "R1",
                "type": "Association",
                "name": "named",
                "labels": {"lt": "+source", "rt": "+target", "lb": "1", "rb": "1", "mt": "named"},
            },
            {
                "id": "R2",
                "type": "Generalization",
                "name": None,
                "labels": {"lt": "+source", "rt": "+target", "lb": "1", "rb": "1"},
            },
        ],
    )

    output = _harness_output(path, capsys)

    assert "[OK] Measured connector labels include the complete observed form: " in output
    assert '"namedRelationsMissingMt": 0' in output


def test_named_relation_without_mt_fails_even_when_another_label_set_is_complete(
    tmp_path: pathlib.Path, capsys: pytest.CaptureFixture[str]
) -> None:
    path = _write_synthetic_xmi(
        tmp_path / "named-relation-without-mt.xmi",
        [
            {
                "id": "R1",
                "type": "Association",
                "name": "complete",
                "labels": {"lt": "+source", "rt": "+target", "lb": "1", "rb": "1", "mt": "complete"},
            },
            {
                "id": "R2",
                "type": "Association",
                "name": "named-without-mt",
                "labels": {"lt": "+source", "rt": "+target", "lb": "1", "rb": "1"},
            },
        ],
    )

    output = _harness_output(path, capsys)

    assert "[FAIL] Measured connector labels include the complete observed form: " in output
    assert '"completeLabelSets": 1' in output
    assert '"namedRelationsMissingMt": 1' in output


def test_declared_standard_vocabulary_deviations_are_accepted(
    tmp_path: pathlib.Path, capsys: pytest.CaptureFixture[str]
) -> None:
    path = _write_synthetic_xmi(
        tmp_path / "declared-deviations.xmi",
        [{"id": "R1", "type": "Association", "name": "named", "labels": {"mt": "named"}}],
        extra_model_tags="<defaultValue/><specification/>",
    )

    output = _harness_output(path, capsys)

    assert "[OK] Declared dialect vocabulary: " in output


def test_declared_vocabulary_deviations_are_exact_and_traceable() -> None:
    assert set(DECLARED_VOCABULARY_DEVIATIONS) == {"defaultValue", "specification"}
    assert all(
        "odd/tasks/xmi-intercambio.md sections 7.5 and 13.2" in reason and "\n" not in reason
        for reason in DECLARED_VOCABULARY_DEVIATIONS.values()
    )


def test_measured_vocabulary_covers_the_committed_ea_artifacts() -> None:
    ea_artifacts = (
        "Prueba3.xml",
        "ea-export-default-1.1.xml",
        "ea-export-uml-2.2.xml",
        "ea-independent-with-diagram.xmi",
        "ea-roundtrip-uml-2.1.xml",
    )

    for artifact in ea_artifacts:
        root = load_fixture(REPO_ROOT / "docs" / "fixtures" / "xmi" / artifact)
        observed_tags = {element.tag.split("}")[-1] for element in root.iter()}
        assert unmeasured_vocabulary_tags({"tagCounts": dict.fromkeys(observed_tags, 1)}) == [], artifact


def test_injected_unmeasured_tag_still_fails_vocabulary_assertion(
    tmp_path: pathlib.Path, capsys: pytest.CaptureFixture[str]
) -> None:
    path = _write_synthetic_xmi(
        tmp_path / "unmeasured-tag.xmi",
        [{"id": "R1", "type": "Association", "name": "named", "labels": {"mt": "named"}}],
        extra_model_tags="<unmeasuredDialectTag/>",
    )

    output = _harness_output(path, capsys)

    assert "[FAIL] Declared dialect vocabulary: " in output
    assert '"unmeasuredDialectTag"' in output
