"""Pruebas del emisor del spike UML a Spring Boot.

Que garantiza
--------------
1. **Determinismo real**: dos emisiones del mismo modelo dan la misma huella, y
   ningun archivo generado lleva fecha, ruta absoluta ni identificador aleatorio.
2. **La regla que el spike existe para probar**: el lado propietario de una
   asociacion se elige por multiplicidad y navegabilidad, **no** por el orden en
   que los extremos aparecen en el archivo. Si el emisor mirara el orden, invertir
   los extremos cambiaria el artefacto, y esta suite fallaria.
3. **Falla cerrado**: una forma que el spike no soporta levanta un error claro en
   vez de elegir en silencio un mapeo equivocado.
4. **Coherencia entre capas**: el SQL y las anotaciones dicen lo mismo sobre la
   nulabilidad de la clave foranea.

No compila Java ni levanta contenedores: eso es otra etapa y otro instrumento.

Uso, desde la raiz del repositorio:

    python -m pytest spikes/uml2spring -q
"""

from __future__ import annotations

import copy
import json
import pathlib
import re
import sys

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from emit import (  # noqa: E402
    ModelError,
    artifact_hash,
    load_model,
    render_files,
    validate_model,
    write_tree,
)

MODEL_PATH = pathlib.Path(__file__).resolve().parent / "model" / "biblioteca.json"

# Patrones que un archivo generado no debe contener nunca.
#
# Ojo con el escapado: la version anterior tenia `r"[A-Za-z]:\\\\"`, que matchea
# `C:\\` pero NO `C:\Users\HP`, asi que el guardian no disparaba nunca. Una
# verificacion independiente lo comprobo; hay un control positivo mas abajo.
FORBIDDEN_PATTERNS = (
    re.compile(r"\b20\d\d-\d\d-\d\d\b"),  # fecha ISO
    re.compile(r"\b\d\d:\d\d:\d\d\b"),  # hora
    re.compile(r"[A-Za-z]:\\"),  # ruta absoluta de Windows: C:\...
    re.compile(r"\\\\[A-Za-z0-9_.-]+\\"),  # ruta UNC
    re.compile(r"\bUsers\\"),  # perfil de usuario
    re.compile(r"\b(Math\.random|UUID\.randomUUID|LocalDateTime\.now|Instant\.now)\b"),
    re.compile(r"\b[0-9a-f]{32,}\b"),  # identificador aleatorio o hash embebido
)


@pytest.fixture()
def document() -> dict:
    return load_model(MODEL_PATH)


def files(document: dict) -> dict[str, str]:
    return render_files(document)


# --------------------------------------------------------------------------
# Determinismo
# --------------------------------------------------------------------------


def test_two_renders_of_the_same_model_are_identical(document: dict) -> None:
    assert artifact_hash(files(document)) == artifact_hash(files(document))


def test_hash_changes_when_the_model_changes(document: dict) -> None:
    """Una huella que nunca cambia no prueba determinismo, prueba indiferencia."""
    other = copy.deepcopy(document)
    other["model"]["classes"][0]["attributes"].append(
        {"id": "attr-autor-premios", "name": "premios", "type": "Integer", "visibility": "private", "required": False}
    )
    assert artifact_hash(files(document)) != artifact_hash(files(other))


def test_every_generated_file_uses_lf_and_ends_with_one_newline(document: dict) -> None:
    for path, content in files(document).items():
        assert "\r" not in content, f"{path} contains CRLF"
        assert content.endswith("\n"), f"{path} does not end with a newline"
        assert not content.endswith("\n\n"), f"{path} ends with a blank line"


def test_forbidden_patterns_actually_fire() -> None:
    """Control positivo: si los patrones no detectan un caso real, el barrido de
    abajo pasa por vacio y no prueba nada. Paso de verdad."""
    assert FORBIDDEN_PATTERNS[2].search(r"C:\Users\HP\proyecto"), "la ruta de Windows no se detecta"
    assert FORBIDDEN_PATTERNS[4].search(r"Users\HP\proyecto"), "el perfil de usuario no se detecta"
    assert FORBIDDEN_PATTERNS[0].search("2026-09-28"), "la fecha no se detecta"
    assert FORBIDDEN_PATTERNS[1].search("18:14:34"), "la hora no se detecta"
    assert FORBIDDEN_PATTERNS[5].search("LocalDateTime.now()"), "el reloj no se detecta"
    assert FORBIDDEN_PATTERNS[6].search("a" * 40), "el identificador largo no se detecta"


def test_no_generated_file_contains_time_paths_or_users(document: dict) -> None:
    for path, content in files(document).items():
        for pattern in FORBIDDEN_PATTERNS:
            assert not pattern.search(content), f"{path} matches {pattern.pattern}"


def test_written_bytes_are_clean(document: dict, tmp_path: pathlib.Path) -> None:
    """Las propiedades se verifican sobre los BYTES escritos, no sobre los strings en
    memoria: es lo unico que prueba que el archivo en disco este bien."""
    write_tree(files(document), tmp_path)
    scanned = 0
    for path in sorted(tmp_path.rglob("*")):
        if not path.is_file():
            continue
        raw = path.read_bytes()
        where = path.relative_to(tmp_path).as_posix()
        assert b"\r" not in raw, f"{where}: contiene CR"
        assert raw.endswith(b"\n"), f"{where}: no termina en salto de linea"
        assert not raw.endswith(b"\n\n"), f"{where}: termina en linea vacia"
        text = raw.decode("utf-8")  # si no es UTF-8 valido, esto falla
        for pattern in FORBIDDEN_PATTERNS:
            assert not pattern.search(text), f"{where} matches {pattern.pattern}"
        scanned += 1
    assert scanned == len(files(document)), "no se escanearon todos los archivos"


def test_generated_java_has_no_python_booleans(document: dict) -> None:
    """Interpolar un bool de Python pone `False` en el codigo Java, que no compila."""
    for path, content in files(document).items():
        if path.endswith(".java"):
            assert "= True" not in content and "= False" not in content, path
            assert "True)" not in content and "False)" not in content, path


# --------------------------------------------------------------------------
# La regla del lado propietario
# --------------------------------------------------------------------------


def test_owning_side_follows_multiplicity_not_file_order(document: dict) -> None:
    """Invertir los extremos en el archivo no debe cambiar el artefacto."""
    reversed_document = copy.deepcopy(document)
    ends = reversed_document["model"]["associations"][0]["ends"]
    ends.reverse()

    assert artifact_hash(files(document)) == artifact_hash(files(reversed_document))


def test_foreign_key_lands_on_the_many_side(document: dict) -> None:
    content = files(document)
    migration = content["src/main/resources/db/migration/V1__init.sql"]

    assert "ALTER TABLE libro" in migration
    assert "REFERENCES autor (id)" in migration
    assert "ALTER TABLE autor" not in migration, "la clave foranea quedo del lado equivocado"


def test_required_association_is_not_null_in_sql(document: dict) -> None:
    migration = files(document)["src/main/resources/db/migration/V1__init.sql"]
    assert "ADD COLUMN autor_id BIGINT NOT NULL," in migration


def test_sql_and_annotations_agree_on_nullability(document: dict) -> None:
    """Con EXACTLY_ONE en el extremo destino, el SQL dice NOT NULL y la anotacion
    tiene que decir nullable = false. Decir cosas distintas es un defecto."""
    content = files(document)
    migration = content["src/main/resources/db/migration/V1__init.sql"]
    entity = content["src/main/java/com/example/biblioteca/domain/Libro.java"]

    assert "ADD COLUMN autor_id BIGINT NOT NULL" in migration
    assert '@JoinColumn(name = "autor_id", nullable = false)' in entity
    assert "optional = false" in entity


def test_to_one_side_is_eager(document: dict) -> None:
    """Con open-in-view en false, un to-one perezoso serializado fuera de la
    transaccion da HTTP 500. Se midio: la primera corrida de extremo a extremo
    fallo en GET /api/libro. El emisor resuelve con EAGER y declara el costo."""
    entity = files(document)["src/main/java/com/example/biblioteca/domain/Libro.java"]
    assert "@ManyToOne(fetch = FetchType.EAGER" in entity
    assert "@ManyToOne(fetch = FetchType.LAZY" not in entity


def test_inverse_side_stays_lazy_and_hidden(document: dict) -> None:
    entity = files(document)["src/main/java/com/example/biblioteca/domain/Autor.java"]
    assert "@OneToMany(mappedBy = \"autor\", fetch = FetchType.LAZY)" in entity
    assert "@JsonIgnore" in entity


def test_inverse_side_is_mapped_by_the_owning_field(document: dict) -> None:
    entity = files(document)["src/main/java/com/example/biblioteca/domain/Autor.java"]
    assert '@OneToMany(mappedBy = "autor"' in entity
    assert "List<Libro> libros" in entity


# --------------------------------------------------------------------------
# Falla cerrado
# --------------------------------------------------------------------------


def test_two_to_one_association_is_rejected(document: dict) -> None:
    broken = copy.deepcopy(document)
    broken["model"]["associations"][0]["ends"][1]["multiplicity"] = "EXACTLY_ONE"
    with pytest.raises(ModelError, match="uno-a-muchos"):
        validate_model(broken)


def test_unknown_type_is_rejected(document: dict) -> None:
    broken = copy.deepcopy(document)
    broken["model"]["classes"][0]["attributes"][0]["type"] = "Money"
    with pytest.raises(ModelError, match="tipo no soportado"):
        validate_model(broken)


def test_end_on_a_missing_class_is_rejected(document: dict) -> None:
    broken = copy.deepcopy(document)
    broken["model"]["associations"][0]["ends"][0]["classId"] = "cls-fantasma"
    with pytest.raises(ModelError, match="clase inexistente"):
        validate_model(broken)


def test_unsupported_multiplicity_is_rejected(document: dict) -> None:
    broken = copy.deepcopy(document)
    broken["model"]["associations"][0]["ends"][1]["multiplicity"] = "RANGE"
    with pytest.raises(ModelError, match="multiplicidad no soportada"):
        validate_model(broken)


# --------------------------------------------------------------------------
# Superficie del contrato de salida
# --------------------------------------------------------------------------


def test_output_contract_lists_every_expected_file(document: dict) -> None:
    content = files(document)
    expected = {
        ".gitignore",
        "pom.xml",
        "src/main/java/com/example/biblioteca/BibliotecaApplication.java",
        "src/main/java/com/example/biblioteca/domain/Autor.java",
        "src/main/java/com/example/biblioteca/domain/Libro.java",
        "src/main/java/com/example/biblioteca/repository/AutorRepository.java",
        "src/main/java/com/example/biblioteca/repository/LibroRepository.java",
        "src/main/java/com/example/biblioteca/service/AutorService.java",
        "src/main/java/com/example/biblioteca/service/LibroService.java",
        "src/main/java/com/example/biblioteca/web/AutorController.java",
        "src/main/java/com/example/biblioteca/web/LibroController.java",
        "src/main/resources/application.yml",
        "src/main/resources/db/migration/V1__init.sql",
    }
    assert set(content) == expected


def test_duplicate_class_names_are_rejected(document: dict) -> None:
    """Dos clases con el mismo nombre colapsaban rutas y duplicaban el CREATE TABLE
    en silencio, con salida incompleta y sin error."""
    broken = copy.deepcopy(document)
    broken["model"]["classes"][1]["name"] = "Autor"
    with pytest.raises(ModelError, match="nombres de clase repetidos"):
        validate_model(broken)


def test_unsupported_feature_is_rejected_not_ignored(document: dict) -> None:
    """Una verificacion independiente comprobo que enumeraciones y generalizaciones se
    ignoraban en silencio mientras la documentacion decia que se rechazaban. Ahora
    cualquier clave que el spike no soporta falla ruidosamente."""
    with_enum = copy.deepcopy(document)
    with_enum["model"]["classes"][0]["stereotype"] = "enumeration"
    with pytest.raises(ModelError, match="no soporta"):
        validate_model(with_enum)

    with_generalization = copy.deepcopy(document)
    with_generalization["model"]["generalizations"] = [{"parent": "Autor", "child": "Libro"}]
    with pytest.raises(ModelError, match="no soporta"):
        validate_model(with_generalization)

    with_extra_attribute_key = copy.deepcopy(document)
    with_extra_attribute_key["model"]["classes"][0]["attributes"][0]["default"] = "x"
    with pytest.raises(ModelError, match="no soporta"):
        validate_model(with_extra_attribute_key)


def test_non_navigable_to_one_end_is_rejected(document: dict) -> None:
    """Antes se ignoraba: un extremo a uno no navegable significa que no habria clave
    foranea, y el emisor lo omitia sin decir nada."""
    broken = copy.deepcopy(document)
    broken["model"]["associations"][0]["ends"][0]["navigable"] = False
    with pytest.raises(ModelError, match="no navegable"):
        validate_model(broken)


def test_non_navigable_many_end_omits_the_inverse_collection(document: dict) -> None:
    """El otro extremo no navegable SI es valido: significa no poder recorrer de uno a
    muchos, y se refleja omitiendo la coleccion. No es un error."""
    other = copy.deepcopy(document)
    other["model"]["associations"][0]["ends"][1]["navigable"] = False
    validate_model(other)
    entity = files(other)["src/main/java/com/example/biblioteca/domain/Autor.java"]
    assert "List<Libro>" not in entity
    assert artifact_hash(files(document)) != artifact_hash(files(other))


def test_class_without_attributes_gets_exactly_one_constructor(document: dict) -> None:
    """Una clase sin atributos y sin relacion propietaria emitia DOS constructores sin
    argumentos y javac rechazaba el archivo."""
    document["model"]["classes"].append(
        {"id": "cls-genero", "name": "Genero", "abstract": False, "attributes": []}
    )
    validate_model(document)
    entity = files(document)["src/main/java/com/example/biblioteca/domain/Genero.java"]
    assert entity.count("    public Genero() {") == 1
    assert "protected Genero()" not in entity, "dos constructores sin argumentos rompen javac"


def test_many_side_lower_bound_is_declared_as_ignored(document: dict) -> None:
    """1..* y 0..* en el lado muchos producen el mismo artefacto: la cota inferior no
    se refleja en el esquema. Es un limite declarado, no un accidente."""
    one_or_many = copy.deepcopy(document)
    one_or_many["model"]["associations"][0]["ends"][1]["multiplicity"] = "ONE_OR_MANY"
    assert artifact_hash(files(document)) == artifact_hash(files(one_or_many))


def test_no_credential_is_written_into_generated_source(document: dict) -> None:
    yml = files(document)["src/main/resources/application.yml"]
    assert "${SERVER_PORT:18080}" in yml, "el puerto debe ser configurable: el 8080 de la maquina esta ocupado"

    credential_lines = [
        line.strip()
        for line in yml.splitlines()
        if line.strip().startswith(("password:", "username:"))
    ]
    assert credential_lines, "se esperaban credenciales por variable de entorno"
    for line in credential_lines:
        value = line.split(":", 1)[1].strip()
        assert value.startswith("${") and value.endswith("}"), (
            f"credencial literal en el archivo generado: {line!r}"
        )


def test_schema_is_owned_by_flyway(document: dict) -> None:
    yml = files(document)["src/main/resources/application.yml"]
    assert "ddl-auto: validate" in yml


def test_model_file_is_the_documented_interface() -> None:
    document = json.loads(MODEL_PATH.read_text(encoding="utf-8"))
    assert document["schemaVersion"] == "spike-1"
    classes = document["model"]["classes"]
    assert [klass["name"] for klass in classes] == ["Autor", "Libro"]
    assert len(document["model"]["associations"]) == 1
