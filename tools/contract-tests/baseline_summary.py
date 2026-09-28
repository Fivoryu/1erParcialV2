"""Resumen derivado del inventario de la linea base UML.

Las cifras de `docs/puds/contratos/03-linea-base-uml.md` se generan con este
script en vez de escribirse a mano. Un documento cuyos numeros se mantienen a
mano termina mintiendo en silencio; este existe para que la afirmacion y el
artefacto no puedan separarse.

Uso, desde la raiz del repositorio:

    python tools/contract-tests/baseline_summary.py
"""

from __future__ import annotations

import collections
import json
import pathlib
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
BASELINE_PATH = REPO_ROOT / "docs" / "puds" / "contratos" / "03-linea-base-uml.v1.json"

CATEGORIES = ("classifier", "feature", "relationship", "complementary")
CATEGORY_LABEL = {
    "classifier": "Clasificadores",
    "feature": "Caracteristicas",
    "relationship": "Relaciones",
    "complementary": "Complementarios",
}


def main() -> int:
    baseline = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
    constructs = baseline["constructs"]

    supported = [c for c in constructs if c["supported"]]
    unsupported = [c for c in constructs if not c["supported"]]

    print(f"Linea base   : {baseline['baselineId']} v{baseline['version']} ({baseline['status']})")
    print(f"Ratificacion : {baseline['ratification']['status']}")
    print(f"Constructos  : {len(constructs)}  |  soportados {len(supported)}  |  no soportados {len(unsupported)}")
    print()

    print("Por categoria:")
    for category in CATEGORIES:
        group = [c for c in constructs if c["category"] == category]
        yes = sum(1 for c in group if c["supported"])
        print(f"  {CATEGORY_LABEL[category]:16} {yes:2}/{len(group):2} soportados")
    print()

    # Clause coverage: a clause counts as supported only when at least one of the
    # constructs covering it is supported. This is what keeps a supported
    # container from hiding an unsupported concept.
    verdicts: dict[str, list[bool]] = collections.defaultdict(list)
    for construct in constructs:
        for clause in construct["covers"]:
            verdicts[clause].append(construct["supported"])

    totally_unsupported = sorted(k for k, v in verdicts.items() if not any(v))
    partially = sorted(k for k, v in verdicts.items() if any(v) and not all(v))

    print(f"Clausulas obligatorias cubiertas : {len(verdicts)}")
    print(f"  sin ningun constructo soportado: {len(totally_unsupported)} -> {', '.join(totally_unsupported)}")
    print(f"  con soporte parcial            : {len(partially)} -> {', '.join(partially) if partially else 'ninguna'}")
    print()

    print("Constructos no soportados:")
    for construct in unsupported:
        print(f"  {construct['id']:11} {construct['name']}")
    print()

    none_cells = collections.Counter(
        mapping["target"]
        for construct in constructs
        for mapping in construct["mappings"]
        if mapping["value"] == "NONE"
    )
    total_cells = len(constructs) * len(baseline["targets"])
    print(
        f"Celdas de mapeo en NONE : {sum(none_cells.values())}/{total_cells} "
        f"(persistencia {none_cells['persistence']}, generacion {none_cells['generation']}, xmi {none_cells['xmi']})"
    )
    print(f"Exclusiones declaradas  : {len(baseline['excluded'])}")
    print(f"Huecos abiertos         : {len(baseline['openGaps'])}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
