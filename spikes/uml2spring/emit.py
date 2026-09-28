#!/usr/bin/env python3
"""Emisor determinista de un backend Spring Boot de cuatro capas desde un modelo UML.

Prueba de riesgo del Incremento 0. No es codigo de producto: lo que se prueba son
las reglas de emision y el determinismo del artefacto generado.

El nucleo es `render_files`, una funcion pura de modelo a diccionario de archivos.
Tenerla asi es lo que hace el determinismo verificable en vez de declarado: se
puede emitir dos veces y comparar, sin tocar el disco.

Determinismo (RNF-04):

1. los archivos salen en orden de ruta POSIX;
2. todo archivo termina en un unico salto de linea y usa LF;
3. ningun archivo generado contiene fecha, hora, ruta absoluta, usuario ni
   identificador aleatorio;
4. la huella es sha256 sobre, por cada archivo en orden,
   `ruta + "\\n" + sha256(contenido) + "\\n"`.

Uso, desde la raiz del repositorio:

    python spikes/uml2spring/emit.py \\
        --model spikes/uml2spring/model/biblioteca.json \\
        --out spikes/uml2spring/build/generated
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
import unicodedata

# Version elegida por una razon verificable, no por novedad: Spring Boot 3.4.5
# gestiona Flyway 10.20.1, Hibernate 6.6.13 y el driver de PostgreSQL 42.7.5, que
# son exactamente los artefactos que ya estan en la cache local de Maven. Con
# Boot 3.5.0 el build pide Flyway 11.7.2 y necesita red. Un spike que depende de
# la red no es reproducible.
SPRING_BOOT_VERSION = "3.4.5"

TO_ONE = ("EXACTLY_ONE", "ZERO_OR_ONE")
TO_MANY = ("ONE_OR_MANY", "ZERO_OR_MANY")

JAVA_TYPES = {
    "String": "String",
    "Integer": "Integer",
    "Long": "Long",
    "Boolean": "Boolean",
    "Double": "Double",
    "BigDecimal": "java.math.BigDecimal",
    "LocalDate": "java.time.LocalDate",
    "LocalDateTime": "java.time.LocalDateTime",
    "UUID": "java.util.UUID",
}

SQL_TYPES = {
    "String": "VARCHAR(255)",
    "Integer": "INTEGER",
    "Long": "BIGINT",
    "Boolean": "BOOLEAN",
    "Double": "DOUBLE PRECISION",
    "BigDecimal": "NUMERIC(19, 2)",
    "LocalDate": "DATE",
    "LocalDateTime": "TIMESTAMP",
    "UUID": "UUID",
}


class ModelError(Exception):
    """El modelo pide algo que este spike no sabe emitir. Falla cerrado."""


# --------------------------------------------------------------------------
# Nombres
# --------------------------------------------------------------------------


def ascii_safe(text: str) -> str:
    """Quita diacriticos: el dominio es en espanol y los identificadores SQL
    deben ser ASCII predecibles. Es una simplificacion del spike, declarada."""
    decomposed = unicodedata.normalize("NFKD", text)
    return "".join(ch for ch in decomposed if not unicodedata.combining(ch))


def snake(text: str) -> str:
    spaced = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", text)
    return re.sub(r"[^0-9a-zA-Z]+", "_", ascii_safe(spaced)).strip("_").lower()


def constant(text: str) -> str:
    return snake(text).upper()


def java_bool(value: bool) -> str:
    """Java escribe true/false en minuscula. Interpolar un bool de Python ponia
    `False` dentro del codigo generado, que no compila."""
    return "true" if value else "false"


# --------------------------------------------------------------------------
# Modelo
# --------------------------------------------------------------------------


def load_model(path: pathlib.Path) -> dict:
    model = json.loads(path.read_text(encoding="utf-8"))
    validate_model(model)
    return model


def validate_model(document: dict) -> None:
    """Falla cerrado ante formas que el spike no soporta. Preferimos un error claro
    antes que elegir en silencio un lado propietario equivocado."""
    model = document.get("model")
    if not model:
        raise ModelError("el documento no tiene 'model'")
    for field in ("name", "basePackage", "classes", "associations"):
        if field not in model:
            raise ModelError(f"al modelo le falta '{field}'")
    if not model["classes"]:
        raise ModelError("el modelo no tiene clases")

    by_id = {klass["id"]: klass for klass in model["classes"]}
    if len(by_id) != len(model["classes"]):
        raise ModelError("hay identificadores de clase repetidos")

    for klass in model["classes"]:
        if klass.get("abstract"):
            raise ModelError(f"'{klass['name']}': el spike no emite clases abstractas todavia")
        for attribute in klass["attributes"]:
            if attribute["type"] not in JAVA_TYPES:
                raise ModelError(f"'{klass['name']}.{attribute['name']}': tipo no soportado '{attribute['type']}'")

    for association in model["associations"]:
        ends = association["ends"]
        if len(ends) != 2:
            raise ModelError(f"'{association['name']}': se esperan exactamente dos extremos")
        for end in ends:
            if end["classId"] not in by_id:
                raise ModelError(f"'{association['name']}': extremo sobre una clase inexistente")
            if end["multiplicity"] not in TO_ONE + TO_MANY:
                raise ModelError(f"'{association['name']}': multiplicidad no soportada '{end['multiplicity']}'")
        kinds = [end["multiplicity"] in TO_ONE for end in ends]
        if all(kinds) or not any(kinds):
            raise ModelError(
                f"'{association['name']}': el spike solo emite asociaciones uno-a-muchos"
            )


def owning_side(association: dict) -> tuple[dict, dict]:
    """Devuelve (extremo muchos, extremo uno).

    La eleccion sale de la multiplicidad, nunca del orden en que los extremos
    aparecen en el archivo. Es la regla que el spike existe para probar.
    """
    ends = association["ends"]
    many = [end for end in ends if end["multiplicity"] in TO_MANY]
    one = [end for end in ends if end["multiplicity"] in TO_ONE]
    if len(many) != 1 or len(one) != 1:
        raise ModelError(f"'{association['name']}': no se pudo decidir el lado propietario")
    return many[0], one[0]


# --------------------------------------------------------------------------
# Emision
# --------------------------------------------------------------------------


def render_files(document: dict) -> dict[str, str]:
    """Modelo a archivos. Funcion pura: misma entrada, misma salida, siempre."""
    model = document["model"]
    package = model["basePackage"]
    package_path = package.replace(".", "/")
    application = f"{model['name']}Application"
    by_id = {klass["id"]: klass for klass in model["classes"]}

    # Que relacion toca a cada clase.
    relations: dict[str, list[dict]] = {klass["id"]: [] for klass in model["classes"]}
    for association in model["associations"]:
        many, one = owning_side(association)
        relations[many["classId"]].append(
            {
                "kind": "owning",
                "association": association,
                "target": by_id[one["classId"]],
                "field": one["role"],
                "nullable": one["multiplicity"] != "EXACTLY_ONE",
            }
        )
        if many["navigable"]:
            relations[one["classId"]].append(
                {
                    "kind": "inverse",
                    "association": association,
                    "target": by_id[many["classId"]],
                    "field": many["role"],
                    "mappedBy": one["role"],
                }
            )

    files: dict[str, str] = {
        "pom.xml": _pom(model),
        f"src/main/java/{package_path}/{application}.java": _application(package, application, model),
        "src/main/resources/application.yml": _application_yml(model),
        "src/main/resources/db/migration/V1__init.sql": _migration(model),
    }

    for klass in model["classes"]:
        name = klass["name"]
        entry = relations[klass["id"]]
        files[f"src/main/java/{package_path}/domain/{name}.java"] = _entity(package, klass, entry)
        files[f"src/main/java/{package_path}/repository/{name}Repository.java"] = _repository(package, klass)
        files[f"src/main/java/{package_path}/service/{name}Service.java"] = _service(package, klass, entry)
        files[f"src/main/java/{package_path}/web/{name}Controller.java"] = _controller(package, klass, entry)

    return files


def _pom(model: dict) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>{SPRING_BOOT_VERSION}</version>
    <relativePath/>
  </parent>

  <groupId>generated</groupId>
  <artifactId>{snake(model['name'])}</artifactId>
  <version>1.0.0</version>
  <name>{model['name']}</name>
  <description>Generado desde un modelo UML por el spike del Incremento 0.</description>

  <properties>
    <java.version>21</java.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
"""


def _application(package: str, application: str, model: dict) -> str:
    return f"""package {package};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class {application} {{

    public static void main(String[] args) {{
        SpringApplication.run({application}.class, args);
    }}
}}
"""


def _application_yml(model: dict) -> str:
    """El esquema lo posee Flyway. Credenciales solo por variable de entorno: no se
    escribe ninguna en el codigo generado."""
    return f"""spring:
  application:
    name: {snake(model['name'])}
  datasource:
    url: ${{DB_URL:jdbc:postgresql://localhost:55432/{snake(model['name'])}}}
    username: ${{DB_USERNAME}}
    password: ${{DB_PASSWORD}}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true

server:
  port: ${{SERVER_PORT:18080}}

management:
  endpoints:
    web:
      exposure:
        include: health
"""


def _migration(model: dict) -> str:
    lines = [
        "-- Esquema generado desde el modelo UML. Flyway lo posee: JPA solo valida.",
        "",
    ]
    for klass in model["classes"]:
        table = snake(klass["name"])
        columns = [
            "    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,",
        ]
        for attribute in klass["attributes"]:
            not_null = " NOT NULL" if attribute["required"] else ""
            columns.append(
                f"    {snake(attribute['name'])} {SQL_TYPES[attribute['type']]}{not_null},"
            )
        body = "\n".join(columns).rstrip(",")
        lines.append(f"CREATE TABLE {table} (")
        lines.append(body)
        lines.append(");")
        lines.append("")

    for association in model["associations"]:
        many, one = owning_side(association)
        child_table = snake(next(k["name"] for k in model["classes"] if k["id"] == many["classId"]))
        parent_table = snake(next(k["name"] for k in model["classes"] if k["id"] == one["classId"]))
        not_null = " NOT NULL" if one["multiplicity"] == "EXACTLY_ONE" else ""
        lines.append(f"ALTER TABLE {child_table}")
        lines.append(f"    ADD COLUMN {snake(one['role'])}_id BIGINT{not_null},")
        lines.append(
            f"    ADD CONSTRAINT fk_{child_table}_{snake(one['role'])}"
            f" FOREIGN KEY ({snake(one['role'])}_id) REFERENCES {parent_table} (id);"
        )
        lines.append("")

    return "\n".join(lines).rstrip("\n") + "\n"


def _entity(package: str, klass: dict, relations: list[dict]) -> str:
    name = klass["name"]
    table = snake(name)
    lines = [
        f"package {package}.domain;",
        "",
        "import com.fasterxml.jackson.annotation.JsonIgnore;",
        "import jakarta.persistence.CascadeType;",
        "import jakarta.persistence.Column;",
        "import jakarta.persistence.Entity;",
        "import jakarta.persistence.FetchType;",
        "import jakarta.persistence.GeneratedValue;",
        "import jakarta.persistence.GenerationType;",
        "import jakarta.persistence.Id;",
        "import jakarta.persistence.JoinColumn;",
        "import jakarta.persistence.ManyToOne;",
        "import jakarta.persistence.OneToMany;",
        "import jakarta.persistence.Table;",
        "import jakarta.validation.constraints.NotBlank;",
        "import jakarta.validation.constraints.NotNull;",
        "import java.util.ArrayList;",
        "import java.util.List;",
        "",
        "@Entity",
        f'@Table(name = "{table}")',
        f"public class {name} {{",
        "",
        "    @Id",
        "    @GeneratedValue(strategy = GenerationType.IDENTITY)",
        "    private Long id;",
        "",
    ]

    for attribute in klass["attributes"]:
        column = snake(attribute["name"])
        if attribute["required"]:
            # @NotBlank ya implica no nulo: no hace falta apilar los dos.
            lines.append("    @NotBlank" if attribute["type"] == "String" else "    @NotNull")
        lines.append(f'    @Column(name = "{column}", nullable = {java_bool(not attribute["required"])})')
        lines.append(f"    private {JAVA_TYPES[attribute['type']]} {attribute['name']};")
        lines.append("")

    for relation in relations:
        if relation["kind"] == "owning":
            target = relation["target"]["name"]
            field = relation["field"]
            # `nullable` significa "la clave foranea puede ser nula", que es
            # exactamente si el extremo destino no es EXACTLY_ONE. El SQL y la
            # anotacion tienen que decir lo mismo.
            optional = java_bool(relation["nullable"])
            # EAGER a proposito: con open-in-view en false, serializar un proxy
            # perezoso fuera de la transaccion tira HTTP 500. Se detecto en la
            # primera corrida de extremo a extremo. Un generador definitivo deberia
            # devolver DTO armados dentro de la transaccion; este spike lo declara
            # como simplificacion con su costo (N+1 al listar).
            lines.append(f"    @ManyToOne(fetch = FetchType.EAGER, optional = {optional})")
            lines.append(
                f'    @JoinColumn(name = "{snake(field)}_id", nullable = {optional})'
            )
            lines.append(f"    private {target} {field};")
            lines.append("")
        else:
            target = relation["target"]["name"]
            field = relation["field"]
            lines.append(f'    @OneToMany(mappedBy = "{relation["mappedBy"]}", fetch = FetchType.LAZY)')
            lines.append("    @JsonIgnore")
            lines.append(f"    private List<{target}> {field} = new ArrayList<>();")
            lines.append("")

    lines.append(f"    protected {name}() {{")
    lines.append("    }")
    lines.append("")

    parameters = []
    for attribute in klass["attributes"]:
        parameters.append(f"{JAVA_TYPES[attribute['type']]} {attribute['name']}")
    for relation in relations:
        if relation["kind"] == "owning":
            parameters.append(f"{relation['target']['name']} {relation['field']}")
    signature = ", ".join(parameters)

    lines.append(f"    public {name}({signature}) {{")
    for attribute in klass["attributes"]:
        lines.append(f"        this.{attribute['name']} = {attribute['name']};")
    for relation in relations:
        if relation["kind"] == "owning":
            lines.append(f"        this.{relation['field']} = {relation['field']};")
    lines.append("    }")
    lines.append("")

    lines.append("    public Long getId() {")
    lines.append("        return id;")
    lines.append("    }")
    lines.append("")
    for attribute in klass["attributes"]:
        capital = attribute["name"][0].upper() + attribute["name"][1:]
        lines.append(f"    public {JAVA_TYPES[attribute['type']]} get{capital}() {{")
        lines.append(f"        return {attribute['name']};")
        lines.append("    }")
        lines.append("")
    for relation in relations:
        capital = relation["field"][0].upper() + relation["field"][1:]
        if relation["kind"] == "owning":
            lines.append(f"    public {relation['target']['name']} get{capital}() {{")
        else:
            lines.append(f"    public List<{relation['target']['name']}> get{capital}() {{")
        lines.append(f"        return {relation['field']};")
        lines.append("    }")
        lines.append("")

    lines.append("}")
    return "\n".join(lines) + "\n"


def _repository(package: str, klass: dict) -> str:
    name = klass["name"]
    return f"""package {package}.repository;

import {package}.domain.{name};
import org.springframework.data.jpa.repository.JpaRepository;

public interface {name}Repository extends JpaRepository<{name}, Long> {{
}}
"""


def _service(package: str, klass: dict, relations: list[dict]) -> str:
    name = klass["name"]
    owning = [relation for relation in relations if relation["kind"] == "owning"]

    parameters = []
    for attribute in klass["attributes"]:
        parameters.append(f"{JAVA_TYPES[attribute['type']]} {attribute['name']}")
    for relation in owning:
        parameters.append(f"Long {relation['field']}Id")

    lines = [
        f"package {package}.service;",
        "",
        f"import {package}.domain.{name};",
        f"import {package}.repository.{name}Repository;",
    ]
    for relation in owning:
        target = relation["target"]["name"]
        lines.append(f"import {package}.domain.{target};")
        lines.append(f"import {package}.repository.{target}Repository;")
    lines.append("import org.springframework.stereotype.Service;")
    lines.append("import org.springframework.transaction.annotation.Transactional;")
    lines.append("import java.util.List;")
    lines.append("import java.util.NoSuchElementException;")
    lines.append("")
    lines.append("@Service")
    lines.append(f"public class {name}Service {{")
    lines.append("")
    lines.append(f"    private final {name}Repository repository;")
    for relation in owning:
        target = relation["target"]["name"]
        lines.append(f"    private final {target}Repository {relation['field']}Repository;")
    lines.append("")
    dependencies = [f"{name}Repository repository"]
    for relation in owning:
        target = relation["target"]["name"]
        dependencies.append(f"{target}Repository {relation['field']}Repository")
    lines.append(f"    public {name}Service({', '.join(dependencies)}) {{")
    lines.append("        this.repository = repository;")
    for relation in owning:
        lines.append(f"        this.{relation['field']}Repository = {relation['field']}Repository;")
    lines.append("    }")
    lines.append("")
    lines.append("    @Transactional")
    lines.append(f"    public {name} create({', '.join(parameters)}) {{")
    for relation in owning:
        field = relation["field"]
        target = relation["target"]["name"]
        lines.append(
            f"        {target} {field} = {field}Repository.findById({field}Id)"
        )
        lines.append("                .orElseThrow(() -> new NoSuchElementException(")
        lines.append(f'                        "{target} " + {field}Id + " no existe"));')
    arguments = [attribute["name"] for attribute in klass["attributes"]]
    arguments += [relation["field"] for relation in owning]
    lines.append(f"        return repository.save(new {name}({', '.join(arguments)}));")
    lines.append("    }")
    lines.append("")
    lines.append("    @Transactional(readOnly = true)")
    lines.append(f"    public List<{name}> list() {{")
    lines.append("        return repository.findAll();")
    lines.append("    }")
    lines.append("}")
    return "\n".join(lines) + "\n"


def _controller(package: str, klass: dict, relations: list[dict]) -> str:
    name = klass["name"]
    table = snake(name)
    owning = [relation for relation in relations if relation["kind"] == "owning"]

    record_fields = []
    for attribute in klass["attributes"]:
        record_fields.append(f"{JAVA_TYPES[attribute['type']]} {attribute['name']}")
    for relation in owning:
        record_fields.append(f"Long {relation['field']}Id")

    calls = [f"request.{attribute['name']}()" for attribute in klass["attributes"]]
    calls += [f"request.{relation['field']}Id()" for relation in owning]

    lines = [
        f"package {package}.web;",
        "",
        f"import {package}.domain.{name};",
        f"import {package}.service.{name}Service;",
        "import jakarta.validation.Valid;",
        "import jakarta.validation.constraints.NotNull;",
        "import org.springframework.http.HttpStatus;",
        "import org.springframework.web.bind.annotation.GetMapping;",
        "import org.springframework.web.bind.annotation.PostMapping;",
        "import org.springframework.web.bind.annotation.RequestBody;",
        "import org.springframework.web.bind.annotation.RequestMapping;",
        "import org.springframework.web.bind.annotation.ResponseStatus;",
        "import org.springframework.web.bind.annotation.RestController;",
        "import java.util.List;",
        "",
        "@RestController",
        f'@RequestMapping("/api/{table}")',
        f"public class {name}Controller {{",
        "",
        f"    private final {name}Service service;",
        "",
        f"    public {name}Controller({name}Service service) {{",
        "        this.service = service;",
        "    }",
        "",
        f"    public record {name}Request({', '.join(record_fields)}) {{",
        "    }",
        "",
        "    @PostMapping",
        "    @ResponseStatus(HttpStatus.CREATED)",
        f"    public {name} create(@Valid @RequestBody {name}Request request) {{",
        f"        return service.create({', '.join(calls)});",
        "    }",
        "",
        "    @GetMapping",
        f"    public List<{name}> list() {{",
        "        return service.list();",
        "    }",
        "}",
    ]
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------
# Escritura y huella
# --------------------------------------------------------------------------


def artifact_hash(files: dict[str, str]) -> str:
    digest = hashlib.sha256()
    for path in sorted(files):
        content = files[path].encode("utf-8")
        digest.update(path.encode("utf-8"))
        digest.update(b"\n")
        digest.update(hashlib.sha256(content).hexdigest().encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def write_tree(files: dict[str, str], out_dir: pathlib.Path) -> list[str]:
    """Escribe en orden de ruta y con LF. Devuelve las rutas escritas."""
    written = []
    for path in sorted(files):
        target = out_dir / path
        target.parent.mkdir(parents=True, exist_ok=True)
        # newline="\n" evita que Windows convierta a CRLF y rompa el determinismo.
        with target.open("w", encoding="utf-8", newline="\n") as handle:
            handle.write(files[path])
        written.append(path)
    return written


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Emite un backend Spring Boot desde un modelo UML.")
    parser.add_argument("--model", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--list", action="store_true", help="imprime las rutas emitidas")
    args = parser.parse_args(argv[1:])

    document = load_model(pathlib.Path(args.model))
    files = render_files(document)
    written = write_tree(files, pathlib.Path(args.out))

    if args.list:
        for path in written:
            print(path)
    print(f"files: {len(written)}")
    print(f"artifactSetHash: {artifact_hash(files)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
