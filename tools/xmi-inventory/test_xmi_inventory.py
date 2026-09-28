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

import json
import pathlib
import sys
from typing import Any

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from xmi_inventory import DEFAULT_FIXTURE, build_inventory  # noqa: E402

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
