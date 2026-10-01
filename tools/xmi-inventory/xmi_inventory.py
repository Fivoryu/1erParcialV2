"""Inventario del dialecto XMI de Enterprise Architect.

Lee un archivo XMI exportado por EA y deriva el inventario **solo desde su
contenido**, sin suponer la forma del dialecto. El resultado alimenta el
contrato `docs/puds/contratos/04-dialecto-xmi-ea.md` y lo verifica
`tools/xmi-inventory/test_xmi_inventory.py`.

Por que existe
--------------
La perdida de un roundtrip XMI no se puede declarar si no se sabe que emite la
herramienta del otro lado. Este arnes clasifica todo el vocabulario del dialecto
y mide que carga real lleva cada bloque.

Familias de elementos:

- **uml-element**: el elemento lleva `xmi:type` con prefijo `uml:`. Es un
  elemento UML real, sin importar en que subarbol viva. Incluye los que EA
  declara dentro de su propia extension, como los tipos primitivos.
- **uml-structure**: etiqueta de serializacion de UML 2.1 sin `xmi:type` propio,
  como `packagedElement`, `memberEnd`, `lowerValue` o `general`.
- **ea-proprietary**: bloque de Sparx Systems, como `style`, `styleex`,
  `coords`, `tags`, `xrefs`, o cualquiera de los contenedores de su extension.
- **plumbing**: estructura del documento (`XMI`, `Documentation`, `Model`,
  `Extension`).
- **undeclared**: no esta en el vocabulario. La suite falla si aparece uno: es la
  senal de que el dialecto trae algo que no clasificamos.

Dos distinciones que importan mas de lo que parecen:

- Una **fuga** es un bloque propietario dentro del subarbol semantico `Model`, no
  dentro de la extension de EA. La fuga contamina el arbol UML.
- La clasificacion depende del **contexto**: `type` es `Property.type` en el
  modelo y el tipo de retorno de EA dentro de un bloque de operacion. Por eso la
  misma etiqueta puede caer en familias distintas y el arnes lo reporta.

Medir presencia no alcanza: una bandera que aparece siempre en `false` no prueba
nada. Por eso `semantic` cuenta valores, no solo etiquetas.

Uso, desde la raiz del repositorio:

    python tools/xmi-inventory/xmi_inventory.py
    python tools/xmi-inventory/xmi_inventory.py --json
"""

from __future__ import annotations

import collections
import hashlib
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_FIXTURE = REPO_ROOT / "docs" / "fixtures" / "xmi" / "Prueba3.xml"

XMI_NS = "http://schema.omg.org/spec/XMI/2.1"
UML_NS = "http://schema.omg.org/spec/UML/2.1"

# --------------------------------------------------------------------------
# Vocabulario declarado. Lo que no este aca, el arnes lo reporta como no
# declarado y la suite falla: obliga a clasificar en vez de ignorar.
# --------------------------------------------------------------------------

STRUCTURAL_TAGS = frozenset({"XMI", "Documentation", "Model", "Extension"})

UML_TAGS = frozenset(
    {
        "packagedElement",
        "ownedAttribute",
        "ownedEnd",
        "ownedOperation",
        "ownedParameter",
        "ownedLiteral",
        "ownedRule",
        "ownedComment",
        "memberEnd",
        "lowerValue",
        "upperValue",
        "type",
        "general",
        "body",
        "generalization",
        "generalizationSet",
        "interfaceRealization",
        "packageImport",
        "importedPackage",
        "redefinedProperty",
        "subsettedProperty",
    }
)

EA_TAGS = frozenset(
    {
        # Bloques que EA cuelga dentro de un elemento.
        "_lineStyle",
        "model",
        "properties",
        "documentation",
        "project",
        "code",
        "style",
        "styleex",
        "coords",
        "containment",
        "bounds",
        "options",
        "initial",
        "behaviour",
        "extendedProperties",
        "links",
        "tags",
        "tag",
        "xrefs",
        "stereotype",
        "constraints",
        # Contenedores y hojas de la extension de EA.
        "elements",
        "element",
        "connectors",
        "connector",
        "diagrams",
        "diagram",
        "primitivetypes",
        "profiles",
        "Profile",
        "attributes",
        "attribute",
        "operations",
        "operation",
        "parameters",
        "parameter",
        "source",
        "target",
        "role",
        "modifiers",
        "appearance",
        "labels",
        "parameterSubstitutions",
        "paths",
        "times",
        "flags",
        "packageproperties",
        "style1",
        "style2",
        "swimlanes",
        "matrixitems",
        # Espejo propietario de las relaciones, dentro del bloque links.
        "Aggregation",
        "Association",
        "Generalization",
        "Realisation",
    }
)

BOOLEAN_FLAGS = (
    "isAbstract",
    "isStatic",
    "isLeaf",
    "isReadOnly",
    "isDerived",
    "isOrdered",
    "isUnique",
    "isNavigable",
    "isDerivedUnion",
)


def _tag(element: ET.Element) -> str:
    return element.tag.split("}")[-1]


def _namespace(element: ET.Element) -> str:
    return element.tag.split("}")[0].lstrip("{") if "}" in element.tag else ""


def _xmi_attribute(element: ET.Element, name: str) -> str:
    return element.attrib.get(f"{{{XMI_NS}}}{name}", "")


def _xmi_type(element: ET.Element) -> str:
    return _xmi_attribute(element, "type")


def _attributes(element: ET.Element) -> dict[str, str]:
    return {key.split("}")[-1]: value for key, value in element.attrib.items()}


def _declared_encoding(raw: bytes) -> str:
    declared = re.search(rb'''encoding=["']([^"']+)["']''', raw[:120])
    return declared.group(1).decode("ascii") if declared else "utf-8"


def _parse_fixture(raw: bytes) -> ET.Element:
    """Decodifica y parsea XML respetando la codificacion declarada."""
    encoding = _declared_encoding(raw)
    text = raw.decode(encoding, errors="strict")
    text = re.sub(r"^<\?xml[^>]*\?>", '<?xml version="1.0"?>', text, count=1)
    return ET.fromstring(text)


def load_fixture(path: pathlib.Path) -> ET.Element:
    """Carga el fixture respetando su codificacion declarada.

    El export de EA declara `windows-1252`, que expat no resuelve cuando se le
    pasan bytes y que ElementTree rechaza cuando se le pasa texto con
    declaracion de codificacion. Se decodifica a mano y se neutraliza la
    declaracion. El archivo en disco no se modifica.
    """
    return _parse_fixture(path.read_bytes())


def _classify(tag: str, xmi_type: str, incoming_context: str) -> str:
    if xmi_type.startswith("uml:"):
        return "uml-element"
    if tag in STRUCTURAL_TAGS:
        return "plumbing"
    if tag in EA_TAGS or incoming_context == "ea":
        return "ea-proprietary"
    if tag in UML_TAGS:
        return "uml-structure"
    return "undeclared"


def build_inventory(path: pathlib.Path = DEFAULT_FIXTURE) -> dict:
    raw = path.read_bytes()
    root = _parse_fixture(raw)

    family_counts: collections.Counter[str] = collections.Counter()
    tag_counts: collections.Counter[str] = collections.Counter()
    tag_family: dict[str, set[str]] = collections.defaultdict(set)
    uml_types_by_subtree: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    ea_block_counts: collections.Counter[str] = collections.Counter()
    ea_payload: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    leak_counts: collections.Counter[str] = collections.Counter()
    namespaces: set[str] = set()

    # Mediciones del subarbol semantico: que lleva el modelo, con valores.
    semantic: collections.Counter[str] = collections.Counter()
    semantic_visibility: collections.Counter[str] = collections.Counter()
    semantic_aggregation: collections.Counter[str] = collections.Counter()
    semantic_flags: collections.Counter[str] = collections.Counter()
    semantic_multipicity: collections.Counter[str] = collections.Counter()
    type_ref_prefixes: collections.Counter[str] = collections.Counter()
    id_prefixes: collections.Counter[str] = collections.Counter()
    type_refs_without_target = 0

    # Mediciones puntuales para la politica de preservacion.
    totals: collections.Counter[str] = collections.Counter()
    stereotype_parents: collections.Counter[str] = collections.Counter()
    constraint_paths: collections.Counter[str] = collections.Counter()
    comments: list[dict] = []
    visibility_case: collections.Counter[str] = collections.Counter()

    # Canales de notas. Buscar solo elementos llamados `documentation` fue un error
    # real de la primera version: EA tambien describe elementos del modelo con un
    # ATRIBUTO documentation, que no es un elemento y por eso nunca se miraba.
    note_attribute_parents: collections.Counter[str] = collections.Counter()
    note_examples: list[str] = []
    enumerations: list[dict] = []
    profile_stereotypes: list[str] = []

    def walk(
        element: ET.Element,
        subtree: str,
        context: str,
        parent_tag: str,
        inside_diagram: bool,
    ) -> None:
        nonlocal type_refs_without_target

        tag = _tag(element)
        xmi_type = _xmi_type(element)
        namespace = _namespace(element)
        if namespace:
            namespaces.add(namespace)

        if tag in ("Model", "Extension"):
            subtree = tag
        # Dos contenedores distintos se llaman `elements`: el del diagrama, con los
        # elementos colocados, y el espejo de metadatos de la extension. Contarlos
        # juntos producia un 29 que no era ni uno ni otro.
        if tag == "diagram":
            inside_diagram = True

        family = _classify(tag, xmi_type, context)
        family_counts[family] += 1
        tag_counts[tag] += 1
        tag_family[tag].add(family)

        attributes = _attributes(element)
        if xmi_type:
            uml_types_by_subtree[subtree or "root"][xmi_type] += 1

        if family == "ea-proprietary":
            ea_block_counts[tag] += 1
            stats = ea_payload[tag]
            stats["elements"] += 1
            if attributes:
                stats["withAttributes"] += 1
            for key in attributes:
                stats[f"attr:{key}"] += 1
            if (element.text or "").strip():
                stats["withText"] += 1
            if len(element):
                stats["withChildren"] += 1
            # Vacio significa sin atributos, sin texto Y sin hijos. Mirar solo
            # atributos y texto hacia que `tags` pareciera vacio con 8 hijos dentro.
            if not attributes and not (element.text or "").strip() and not len(element):
                stats["empty"] += 1
            if subtree == "Model":
                leak_counts[tag] += 1

        # ---- subarbol semantico: que lleva el modelo, con valores ----
        if subtree == "Model":
            semantic[f"tag:{tag}"] += 1
            identifier = _xmi_attribute(element, "id")
            if identifier:
                id_prefixes[identifier.split("_")[0]] += 1
            if xmi_type:
                short = xmi_type.split(":")[-1]
                semantic[f"type:{short}"] += 1
                if xmi_type == "uml:Class":
                    semantic["classes"] += 1
                    if attributes.get("isAbstract") == "true":
                        semantic["classesAbstract"] += 1
                elif xmi_type in ("uml:Interface", "uml:Enumeration", "uml:Package"):
                    if attributes.get("isAbstract") == "true":
                        semantic["otherClassifiersAbstract"] += 1
                if xmi_type in ("uml:Property", "uml:Operation") and attributes.get("isAbstract") == "true":
                    semantic["membersAbstract"] += 1
                if xmi_type in ("uml:Property", "uml:Operation") and attributes.get("isStatic") == "true":
                    semantic["membersStatic"] += 1
            for flag in BOOLEAN_FLAGS:
                if attributes.get(flag, "").lower() == "true":
                    semantic_flags[flag] += 1
            if attributes.get("visibility"):
                semantic_visibility[attributes["visibility"]] += 1
            if "isNavigable" in attributes:
                semantic["navigabilityAttributesInModel"] += 1
            if attributes.get("aggregation"):
                semantic_aggregation[attributes["aggregation"]] += 1
            if tag in ("lowerValue", "upperValue"):
                semantic_multipicity[tag] += 1
            if xmi_type in ("uml:LiteralInteger", "uml:LiteralUnlimitedNatural"):
                semantic_multipicity[xmi_type] += 1
            if tag == "type":
                idref = _xmi_attribute(element, "idref")
                if idref:
                    type_ref_prefixes[idref.split("_")[0]] += 1
                else:
                    type_refs_without_target += 1

        # ---- mediciones puntuales ----
        if "visibility" in attributes:
            visibility_case[attributes["visibility"]] += 1

        # ---- canales de notas, buscados en atributos ademas de elementos ----
        note_text = attributes.get("documentation", "").strip()
        if note_text:
            note_attribute_parents[parent_tag] += 1
            if len(note_examples) < 3:
                note_examples.append(note_text)

        if tag == "documentation":
            totals["documentation_blocks"] += 1
            if attributes.get("value"):
                totals["documentation_with_value_attribute"] += 1

        if tag == "stereotype":
            stereotype_parents[parent_tag] += 1
            if (element.text or "").strip():
                totals["stereotype_with_payload"] += 1

        if tag == "constraints":
            constraint_paths[parent_tag] += 1
            if attributes or (element.text or "").strip() or len(element):
                totals["constraint_blocks_with_content"] += 1

        if xmi_type == "uml:Comment":
            comments.append(
                {
                    "id": _xmi_attribute(element, "id"),
                    "insideProfile": parent_tag == "Profile",
                    "annotatedElement": attributes.get("annotatedElement", ""),
                }
            )

        if xmi_type == "uml:Stereotype":
            profile_stereotypes.append(attributes.get("name", ""))

        if xmi_type == "uml:Enumeration" and subtree == "Model":
            shapes: collections.Counter[str] = collections.Counter()
            literals: list[str] = []
            for child in element:
                child_tag = _tag(child)
                if child_tag in ("ownedLiteral", "ownedAttribute"):
                    shapes[child_tag] += 1
                    if child_tag == "ownedAttribute":
                        literals.append(_attributes(child).get("name", ""))
            enumerations.append(
                {
                    "name": attributes.get("name", ""),
                    "literalShape": shapes.most_common(1)[0][0] if shapes else "none",
                    "literalCount": sum(shapes.values()),
                    "literals": literals,
                }
            )

        if xmi_type == "uml:Property" and subtree == "Model" and tag == "ownedEnd":
            totals["associationEnds"] += 1
            if attributes.get("name", "").strip():
                totals["namedAssociationEnds"] += 1

        if subtree == "Extension" and tag == "element":
            if inside_diagram:
                totals["diagram_placed_elements"] += 1
                if attributes.get("geometry"):
                    totals["diagram_placed_with_geometry"] += 1
            else:
                totals["metadata_mirror_elements"] += 1
        if subtree == "Extension" and tag == "connector":
            totals["connectors"] += 1

        next_context = "ea" if family == "ea-proprietary" else context
        if family == "uml-element":
            next_context = "uml"
        for child in element:
            walk(child, subtree, next_context, tag, inside_diagram)

    walk(root, "", "", "", False)
    interchange = _build_interchange_inventory(root, type_refs_without_target)
    totals["diagram_shape_with_geometry"] = interchange["diagram"]["shapeGeometryCount"]
    totals["diagram_connector_with_geometry"] = interchange["diagram"]["connectorGeometryCount"]

    declared = STRUCTURAL_TAGS | UML_TAGS | EA_TAGS
    undeclared = sorted(tag for tag in tag_counts if tag not in declared)
    ambiguous = {
        tag: sorted(families) for tag, families in sorted(tag_family.items()) if len(families) > 1
    }

    return {
        "source": str(path.relative_to(REPO_ROOT)) if path.is_relative_to(REPO_ROOT) else str(path),
        "bytes": len(raw),
        "declaredEncoding": _declared_encoding(raw),
        "fixtureIdentity": {"sha256": hashlib.sha256(raw).hexdigest()},
        "interchange": interchange,
        "xmiVersion": root.attrib.get(f"{{{XMI_NS}}}version", ""),
        "exporter": _child_attributes(root, "Documentation"),
        "extension": _child_attributes(root, "Extension"),
        "namespaces": sorted(namespaces),
        "familyCounts": dict(family_counts.most_common()),
        "tagCounts": dict(tag_counts.most_common()),
        "tagFamily": {tag: sorted(families) for tag, families in sorted(tag_family.items())},
        "ambiguousTags": ambiguous,
        "umlTypesBySubtree": {
            subtree: dict(counter.most_common())
            for subtree, counter in sorted(uml_types_by_subtree.items())
        },
        "eaBlockCounts": dict(ea_block_counts.most_common()),
        "eaBlockPayload": {tag: dict(stats.most_common()) for tag, stats in sorted(ea_payload.items())},
        "modelLeaks": dict(leak_counts.most_common()),
        "undeclaredTags": undeclared,
        "semantic": {
            **dict(semantic.most_common()),
            "flagsTrue": dict(semantic_flags.most_common()),
            "visibility": dict(semantic_visibility.most_common()),
            "aggregation": dict(semantic_aggregation.most_common()),
            "multiplicity": dict(semantic_multipicity.most_common()),
            "typeReferencePrefixes": dict(type_ref_prefixes.most_common()),
            "typeReferencesWithoutTarget": type_refs_without_target,
            "identifierPrefixes": dict(id_prefixes.most_common()),
            "navigabilityAttributesInModel": semantic.get("navigabilityAttributesInModel", 0),
        },
        "findings": {
            "eaTotals": {
                "elements": sum(stats.get("elements", 0) for stats in ea_payload.values()),
                "empty": sum(stats.get("empty", 0) for stats in ea_payload.values()),
            },
            "documentation": {
                # Una sola definicion de vacio en todo el artefacto: la del payload,
                # que exige sin atributos, sin texto y sin hijos. Tener dos medidas
                # distintas de lo mismo ya produjo un informe que se contradecia.
                "blocks": ea_payload["documentation"].get("elements", 0),
                "empty": ea_payload["documentation"].get("empty", 0),
                "withValueAttribute": totals["documentation_with_value_attribute"],
            },
            "notes": {
                "documentationAttributes": sum(note_attribute_parents.values()),
                "byParent": dict(note_attribute_parents.most_common()),
                "documentationValueAttributes": totals["documentation_with_value_attribute"],
                "examples": note_examples,
                "umlComments": len(comments),
                "umlCommentsOnModelElements": sum(1 for c in comments if not c["insideProfile"]),
            },
            "stereotypes": {
                "blocks": sum(stereotype_parents.values()),
                "byParent": dict(stereotype_parents.most_common()),
                "withPayload": totals["stereotype_with_payload"],
                "definedInProfile": profile_stereotypes,
            },
            "constraints": {
                "blocks": sum(constraint_paths.values()),
                "byParent": dict(constraint_paths.most_common()),
                "withContent": totals["constraint_blocks_with_content"],
            },
            "comments": {
                "total": len(comments),
                "insideProfile": sum(1 for c in comments if c["insideProfile"]),
                "onModelElements": sum(1 for c in comments if not c["insideProfile"]),
            },
            "visibilityCase": dict(visibility_case.most_common()),
            "enumerations": enumerations,
            "associationEnds": {
                "total": totals["associationEnds"],
                "named": totals["namedAssociationEnds"],
            },
            "diagrams": {
                "placedElements": totals["diagram_placed_elements"],
                "withGeometry": totals["diagram_placed_with_geometry"],
                "shapeWithGeometry": totals["diagram_shape_with_geometry"],
                "connectorWithGeometry": totals["diagram_connector_with_geometry"],
                "metadataMirrorElements": totals["metadata_mirror_elements"],
                "connectors": totals["connectors"],
            },
        },
    }


def _direct_child(element: ET.Element, tag: str) -> ET.Element | None:
    return next((child for child in element if _tag(child) == tag), None)


def _field_values(value: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    for part in value.split(";"):
        if "=" in part:
            key, field_value = part.split("=", 1)
            fields[key] = field_value
    return fields


def _build_interchange_inventory(root: ET.Element, unresolved_type_refs: int) -> dict:
    extension = next(
        (
            child
            for child in root
            if _tag(child) == "Extension"
            and child.attrib.get("extender") == "Enterprise Architect"
        ),
        None,
    )
    model = next(
        (
            child
            for child in root
            if _tag(child) == "Model" and _xmi_type(child) == "uml:Model"
        ),
        None,
    )
    if extension is None or model is None:
        return {"extension": {}, "model": {}, "connectors": [], "diagram": {}}

    extension_children = collections.Counter(_tag(child) for child in extension)
    direct_child_counts = {
        _tag(child): len(child) for child in extension
    }
    extension_connectors = _direct_child(extension, "connectors")
    connector_nodes = (
        [child for child in extension_connectors if _tag(child) == "connector"]
        if extension_connectors is not None
        else []
    )
    diagrams_container = _direct_child(extension, "diagrams")
    diagram_nodes = (
        [child for child in diagrams_container if _tag(child) == "diagram"]
        if diagrams_container is not None
        else []
    )
    connectors_inside_diagrams = [
        node
        for diagram in root.iter()
        if _tag(diagram) == "diagram"
        for node in diagram.iter()
        if _tag(node) == "connectors"
    ]

    model_ids = {
        _xmi_attribute(node, "id")
        for node in model.iter()
        if _xmi_attribute(node, "id")
    }
    classifier_types = {
        "uml:Class",
        "uml:Interface",
        "uml:Enumeration",
        "uml:Package",
        "uml:PrimitiveType",
    }
    relation_types = {"uml:Association", "uml:Generalization", "uml:Realization"}
    elements: list[dict] = []
    features: list[dict] = []
    model_relations: dict[str, dict] = {}

    def collect_model(node: ET.Element, owner: dict | None = None) -> None:
        node_type = _xmi_type(node)
        identifier = _xmi_attribute(node, "id")
        attributes = _attributes(node)
        current_owner = owner
        if node_type in classifier_types:
            current_owner = {
                "id": identifier,
                "name": attributes.get("name", ""),
            }
            elements.append(
                {
                    "id": identifier,
                    "type": node_type,
                    "name": attributes.get("name", ""),
                    "visibility": attributes.get("visibility", ""),
                }
            )
        if node_type in relation_types and identifier:
            model_relations[identifier] = {
                "id": identifier,
                "type": node_type,
                "name": attributes.get("name", ""),
            }
        child_owner = current_owner
        if _tag(node) in {"ownedAttribute", "ownedOperation", "ownedParameter"}:
            type_node = _direct_child(node, "type")
            features.append(
                {
                    "id": identifier,
                    "kind": _tag(node),
                    "name": attributes.get("name", ""),
                    "ownerId": current_owner.get("id", "") if current_owner else "",
                    "ownerName": current_owner.get("name", "") if current_owner else "",
                    "typeId": _xmi_attribute(type_node, "idref") if type_node is not None else "",
                }
            )
            if _tag(node) == "ownedOperation" and identifier:
                child_owner = {"id": identifier, "name": attributes.get("name", "")}
        for child in node:
            collect_model(child, child_owner)

    collect_model(model)
    element_type_counts = collections.Counter(element["type"] for element in elements)
    feature_kind_counts = collections.Counter(feature["kind"] for feature in features)

    relationships: list[dict] = []
    label_key_counts: collections.Counter[str] = collections.Counter()
    connectors_with_complete_labels = 0
    for connector in connector_nodes:
        labels_node = _direct_child(connector, "labels")
        labels = dict(labels_node.attrib) if labels_node is not None else {}
        label_key_counts.update(labels.keys())
        if {"lt", "rt", "lb", "rb", "mt"} <= labels.keys():
            connectors_with_complete_labels += 1

        endpoints = {}
        for side in ("source", "target"):
            endpoint = _direct_child(connector, side)
            role = _direct_child(endpoint, "role") if endpoint is not None else None
            end_type = _direct_child(endpoint, "type") if endpoint is not None else None
            endpoint_model = _direct_child(endpoint, "model") if endpoint is not None else None
            endpoints[side] = {
                "id": _xmi_attribute(endpoint, "idref") if endpoint is not None else "",
                "name": endpoint_model.attrib.get("name", "") if endpoint_model is not None else "",
                "type": endpoint_model.attrib.get("type", "") if endpoint_model is not None else "",
                "role": role.attrib.get("name", "") if role is not None else "",
                "multiplicity": (
                    end_type.attrib.get("multiplicity", "") if end_type is not None else ""
                ),
            }
        relation_id = _xmi_attribute(connector, "idref")
        properties = _direct_child(connector, "properties")
        relationships.append(
            {
                "id": relation_id,
                "name": connector.attrib.get("name", ""),
                "modelType": model_relations.get(relation_id, {}).get("type", ""),
                "eaType": properties.attrib.get("ea_type", "") if properties is not None else "",
                "source": endpoints["source"],
                "target": endpoints["target"],
                "labels": labels,
            }
        )

    role_parent_counts: collections.Counter[str] = collections.Counter()
    for parent in root.iter():
        role_parent_counts.update(
            _tag(parent) for child in parent if _tag(child) == "role"
        )

    placement_nodes = [
        child
        for diagram in diagram_nodes
        for container in diagram
        if _tag(container) == "elements"
        for child in container
        if _tag(child) == "element"
    ]
    shape_placements: list[dict] = []
    connector_placements: list[dict] = []
    unclassified_placements: list[dict] = []
    for placement in placement_nodes:
        attributes = _attributes(placement)
        geometry = attributes.get("geometry", "")
        style = attributes.get("style", "")
        geometry_fields = _field_values(geometry)
        style_fields = _field_values(style)
        subject = attributes.get("subject", "")
        rectangle_fields = {"Left", "Top", "Right", "Bottom"}
        connector_fields = {"SX", "SY", "EX", "EY", "Path"}
        duid = style_fields.get("DUID", "")
        if (
            rectangle_fields <= geometry_fields.keys()
            and attributes.get("seqno")
            and duid
        ):
            shape_placements.append(
                {
                    "subject": subject,
                    "geometry": geometry,
                    "bounds": {key: geometry_fields[key] for key in ("Left", "Top", "Right", "Bottom")},
                    "seqno": attributes["seqno"],
                    "duid": duid,
                    "subjectResolved": subject in model_ids,
                }
            )
        elif (
            connector_fields <= geometry_fields.keys()
            and "seqno" not in attributes
            and not (rectangle_fields & geometry_fields.keys())
        ):
            path = geometry_fields["Path"]
            connector_placements.append(
                {
                    "subject": subject,
                    "geometry": geometry,
                    "style": style,
                    "path": path,
                    "pathEmpty": path == "",
                    "subjectResolved": subject in model_ids,
                    "anchors": {
                        "mode": style_fields.get("Mode", ""),
                        "sourceDuid": style_fields.get("SOID", ""),
                        "targetDuid": style_fields.get("EOID", ""),
                    },
                }
            )
        else:
            unclassified_placements.append(
                {"subject": subject, "geometry": geometry, "style": style}
            )

    shape_duids = {placement["duid"] for placement in shape_placements}
    anchored_placements = [
        {
            "subject": placement["subject"],
            **placement["anchors"],
            "sourceMatchesShape": placement["anchors"]["sourceDuid"] in shape_duids,
            "targetMatchesShape": placement["anchors"]["targetDuid"] in shape_duids,
        }
        for placement in connector_placements
        if placement["anchors"]["sourceDuid"] and placement["anchors"]["targetDuid"]
    ]
    unresolved_subjects = [
        placement["subject"]
        for placement in shape_placements + connector_placements
        if not placement["subjectResolved"]
    ]
    paths = [placement["path"] for placement in connector_placements]

    return {
        "extension": {
            "directChildTags": [_tag(child) for child in extension],
            "directChildEntryCounts": direct_child_counts,
            "directChildBlockCounts": dict(extension_children),
            "connectorsIsDirectChild": extension_connectors is not None,
            "connectorsInsideDiagrams": len(connectors_inside_diagrams),
        },
        "model": {
            "name": model.attrib.get("name", ""),
            "id": _xmi_attribute(model, "id"),
            "elements": elements,
            "elementCountsByType": dict(element_type_counts.most_common()),
            "features": features,
            "featureCountsByKind": dict(feature_kind_counts.most_common()),
            "relations": list(model_relations.values()),
        },
        "connectors": relationships,
        "labels": {
            "connectorsWithCompleteLabels": connectors_with_complete_labels,
            "keyCounts": {
                key: label_key_counts.get(key, 0)
                for key in ("lt", "rt", "lb", "rb", "mt")
            },
        },
        "roles": {
            "total": sum(role_parent_counts.values()),
            "byParent": dict(role_parent_counts.most_common()),
        },
        "diagram": {
            "count": len(diagram_nodes),
            "placementCount": len(placement_nodes),
            "shapePlacements": shape_placements,
            "shapeGeometryCount": sum(bool(item["geometry"]) for item in shape_placements),
            "connectorPlacements": connector_placements,
            "connectorGeometryCount": sum(bool(item["geometry"]) for item in connector_placements),
            "unclassifiedPlacements": unclassified_placements,
            "unresolvedSubjects": unresolved_subjects,
            "shapeDuids": sorted(shape_duids),
            "anchoredPlacements": anchored_placements,
            "pathCounts": {
                "empty": sum(path == "" for path in paths),
                "nonempty": sum(path != "" for path in paths),
            },
            "nonemptyPathValues": [path for path in paths if path != ""],
        },
        "notRepresentable": {
            "unresolvedTypeReferences": unresolved_type_refs,
            "unresolvedDiagramSubjects": len(unresolved_subjects),
        },
    }


def _child_attributes(root: ET.Element, tag: str) -> dict:
    for child in root:
        if _tag(child) == tag:
            return dict(child.attrib)
    return {}


def render(inventory: dict) -> str:
    lines: list[str] = []
    exporter = inventory["exporter"]
    lines.append(f"Archivo          : {inventory['source']} ({inventory['bytes']} bytes)")
    lines.append(f"Codificacion     : {inventory['declaredEncoding']}")
    lines.append(
        f"Procedencia      : {exporter.get('exporter', '?')} {exporter.get('exporterVersion', '?')}"
        f"  |  XMI {inventory['xmiVersion']}"
    )
    lines.append(f"Tags no declarados: {len(inventory['undeclaredTags'])} {inventory['undeclaredTags']}")
    lines.append("")

    lines.append("Familias de elementos:")
    total = sum(inventory["familyCounts"].values())
    for name, count in inventory["familyCounts"].items():
        lines.append(f"  {count:5}  {name:16} ({100 * count / total:.1f}%)")
    lines.append("")

    lines.append("Tipos UML por subarbol (definicion en Model vs duplicado en Extension):")
    for subtree, counts in inventory["umlTypesBySubtree"].items():
        lines.append(f"  {subtree}:")
        for name, count in counts.items():
            lines.append(f"    {count:4}  {name}")
    lines.append("")

    semantic = inventory["semantic"]
    lines.append("Lo que el modelo semantico realmente lleva:")
    for key, value in semantic.items():
        lines.append(f"  {key:28} {value}")
    lines.append("")

    leaks = inventory["modelLeaks"]
    lines.append(f"Fugas de EA dentro de Model: {leaks or 'ninguna'}")
    lines.append(f"Tags ambiguos por contexto: {inventory['ambiguousTags']}")
    lines.append("")

    lines.append("Carga real de los bloques de EA (elementos | con atributos | con hijos | vacios):")
    for tag, stats in inventory["eaBlockPayload"].items():
        keys = sorted(key[5:] for key in stats if key.startswith("attr:"))
        lines.append(
            f"  {tag:22} {stats.get('elements', 0):4} | {stats.get('withAttributes', 0):4} |"
            f" {stats.get('withChildren', 0):3} | {stats.get('empty', 0):4}  {', '.join(keys[:4])}"
        )
    lines.append("")

    findings = inventory["findings"]
    doc = findings["documentation"]
    lines.append(f"documentation: {doc['blocks']} bloques, {doc['empty']} vacios, {doc['withValueAttribute']} con value")
    notes = findings["notes"]
    lines.append(
        f"NOTAS        : {notes['documentationAttributes']} atributos documentation en {notes['byParent']}"
    )
    lines.append(
        f"               {notes['documentationValueAttributes']} con value, {notes['umlComments']} uml:Comment"
        f" (en elementos del modelo {notes['umlCommentsOnModelElements']})"
    )
    for example in notes["examples"]:
        lines.append(f"               ejemplo: {example[:78]!r}")
    ste = findings["stereotypes"]
    lines.append(f"stereotype   : {ste['blocks']} bloques, payload en {ste['withPayload']}, padres {ste['byParent']}")
    con = findings["constraints"]
    lines.append(f"constraints  : {con['blocks']} bloques, con contenido {con['withContent']}, padres {con['byParent']}")
    com = findings["comments"]
    lines.append(f"uml:Comment  : {com['total']} (perfil {com['insideProfile']}, modelo {com['onModelElements']})")
    lines.append(f"visibility   : {findings['visibilityCase']}  (ojo: distingue mayusculas)")
    for enumeration in findings["enumerations"]:
        lines.append(
            f"Enumeracion  : {enumeration['name']} forma={enumeration['literalShape']}"
            f" ({enumeration['literalCount']}) {enumeration['literals']}"
        )
    ends = findings["associationEnds"]
    lines.append(f"Extremos     : {ends['total']} totales, {ends['named']} con nombre de rol")
    dia = findings["diagrams"]
    lines.append(
        f"Diagrama     : {dia['placedElements']} colocaciones ({dia['shapeWithGeometry']} figuras,"
        f" {dia['connectorWithGeometry']} conectores con geometria);"
        f" {dia['metadataMirrorElements']} entradas del espejo de metadatos"
    )

    exchange = inventory["interchange"]
    identity = inventory["fixtureIdentity"]
    extension = exchange["extension"]
    model = exchange["model"]
    diagram = exchange["diagram"]
    lines.append("")
    lines.append("Intercambio XMI:")
    lines.append(f"  Fixture SHA-256 : {identity['sha256']}")
    lines.append(f"  Extension       : {extension['directChildEntryCounts']}")
    lines.append(
        f"  connectors      : hijo directo={extension['connectorsIsDirectChild']},"
        f" dentro de diagram={extension['connectorsInsideDiagrams']} bloques"
    )
    lines.append(
        f"  Model           : {model['name']!r}, xmi:id={model['id']!r};"
        f" elementos={len(model['elements'])}, caracteristicas={len(model['features'])},"
        f" relaciones={len(model['relations'])}"
    )
    lines.append(f"  Tipos de elemento: {model['elementCountsByType']}")
    lines.append(f"  Caracteristicas : {model['featureCountsByKind']}")
    for relation in exchange["connectors"]:
        source = relation["source"]
        target = relation["target"]
        lines.append(
            f"  Relacion {relation['name']!r}: {source['role'] or source['name'] or source['id']}"
            f" [{source['multiplicity'] or '?'}] -> {target['role'] or target['name'] or target['id']}"
            f" [{target['multiplicity'] or '?'}]; labels={relation['labels']}"
        )
    lines.append(
        f"  Colocaciones    : {len(diagram['shapePlacements'])} figuras +"
        f" {len(diagram['connectorPlacements'])} conectores;"
        f" subjects sin resolver={len(diagram['unresolvedSubjects'])}"
    )
    for placement in diagram["shapePlacements"]:
        lines.append(
            f"    Figura {placement['subject']}: {placement['bounds']}"
            f" seqno={placement['seqno']} DUID={placement['duid']}"
        )
    for placement in diagram["connectorPlacements"]:
        lines.append(
            f"    Conector {placement['subject']}: geometry={placement['geometry']}"
            f" Path={placement['path']!r} anchors(style)={placement['anchors']}"
        )
    lines.append(
        f"  Path de conectores: {diagram['pathCounts']};"
        f" valores no vacios={diagram['nonemptyPathValues']}"
    )
    lines.append(f"  Keys de labels   : {exchange['labels']['keyCounts']}")
    lines.append(f"  Roles             : {exchange['roles']}")
    lines.append(f"  No representable : {exchange['notRepresentable']}")

    return "\n".join(lines)


def main(argv: list[str]) -> int:
    as_json = "--json" in argv
    positional = [a for a in argv[1:] if not a.startswith("--")]
    target = pathlib.Path(positional[0]) if positional else DEFAULT_FIXTURE

    inventory = build_inventory(target)
    print(json.dumps(inventory, ensure_ascii=False, indent=2) if as_json else render(inventory))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
