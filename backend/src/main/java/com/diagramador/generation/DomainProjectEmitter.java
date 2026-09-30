package com.diagramador.generation;

import com.diagramador.generation.DomainModelIR.CollectionIntent;
import com.diagramador.generation.DomainModelIR.FieldDeclaration;
import com.diagramador.generation.DomainModelIR.LifecycleIntent;
import com.diagramador.generation.DomainModelIR.OperationSignature;
import com.diagramador.generation.DomainModelIR.RelationshipDescriptor;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.generation.DomainModelIR.TypeDeclaration;
import com.diagramador.generation.DomainModelIR.TypeUse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Emits a deterministic, compileable four-layer Spring Boot project from normalized IR. */
@Component
public final class DomainProjectEmitter {

    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Set<String> JAVA_RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_");
    private static final Pattern SQL_TYPE = Pattern.compile("[A-Z][A-Z0-9_ ]*(?:\\([0-9]+(?:,\\s*[0-9]+)?\\))?");
    private static final Pattern HOST_PATH = Pattern.compile(
            "(?i)(?:\\b[A-Z]:[\\\\/][^\\s\"'<>]*|(?:^|[\\s\"'=])/(?:home|Users|tmp|var/tmp|workspace|workspaces|mnt)/[^\\s\"'<>]*)");

    private final GenerationBaseline baseline;

    @Autowired
    public DomainProjectEmitter() {
        this(GenerationBaseline.load());
    }

    public DomainProjectEmitter(GenerationBaseline baseline) {
        this.baseline = Objects.requireNonNull(baseline, "baseline");
    }

    /** Returns paths in POSIX lexical order and contents normalized to UTF-8/LF with a final newline. */
    public Map<String, String> emit(String targetKey, DomainModelIR ir) {
        Objects.requireNonNull(ir, "ir");
        if (targetKey == null || targetKey.isBlank()) {
            throw new IllegalArgumentException("targetKey must not be blank");
        }
        if (ir.hasBlockingDiagnostics()) {
            throw new IllegalArgumentException("Cannot emit a complete backend while blocking IR diagnostics exist");
        }
        if (ir.types().isEmpty()) {
            throw new IllegalArgumentException("Cannot emit a backend without domain classes");
        }

        TargetNames target = targetNames(targetKey);
        Map<UUID, EntityPlan> plans = createPlans(target.basePackage(), ir.types());
        attachRelationships(plans, ir.relationships());
        validatePlans(plans.values());

        TreeMap<String, String> files = new TreeMap<>();
        put(files, ".gitignore", lines(
                "/target/",
                ".idea/",
                ".vscode/",
                "*.iml"));
        put(files, "Dockerfile", dockerfile());
        put(files, "README.md", readme(target));
        put(files, "pom.xml", pom(target));
        put(files, javaPath(target.basePackage(), "GeneratedApplication"), application(target));

        List<EntityPlan> orderedPlans = plans.values().stream()
                .sorted((left, right) -> left.qualifiedName.compareTo(right.qualifiedName)).toList();
        for (EntityPlan plan : orderedPlans) {
            put(files, entityPath(plan), entitySource(plan, plans));
            put(files, dtoPath(plan, target.basePackage()), dtoSource(plan, target.basePackage()));
            put(files, repositoryPath(plan, target.basePackage()), repositorySource(plan, target.basePackage()));
            put(files, servicePath(plan, target.basePackage()), serviceSource(plan, target.basePackage()));
            put(files, controllerPath(plan, target.basePackage()), controllerSource(plan, target.basePackage()));
        }
        put(files, "src/main/resources/application.yml", applicationYaml());
        put(files, "src/main/resources/db/migration/V1__init.sql", migration(orderedPlans));

        files.forEach((path, content) -> {
            if (!path.startsWith("/") && !path.contains("\\") && !path.contains("../")
                    && content.endsWith("\n") && !content.contains("\r")
                    && !HOST_PATH.matcher(content).find()) {
                return;
            }
            throw new IllegalStateException("Generated artifact is not portable/deterministic: " + path);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(files));
    }

    private Map<UUID, EntityPlan> createPlans(String rootPackage, List<TypeDeclaration> types) {
        Map<UUID, EntityPlan> plans = new HashMap<>();
        for (TypeDeclaration type : types) {
            requireJavaIdentifier(type.javaName(), "class name");
            requireSqlIdentifier(type.tableName(), "table name");
            String packageName = entityPackage(rootPackage, type.javaPackageName());
            EntityPlan previous = plans.put(type.sourceId(), new EntityPlan(type, packageName,
                    packageName + "." + type.javaName()));
            if (previous != null) {
                throw new IllegalArgumentException("IR contains duplicate type identity " + type.sourceId());
            }
        }
        for (EntityPlan plan : plans.values()) {
            for (FieldDeclaration field : plan.type.fields()) {
                requireJavaIdentifier(field.javaName(), "attribute name");
                EntityPlan referencedType = field.type().sourceClassifierId() == null
                        ? null
                        : requirePlan(plans, field.type().sourceClassifierId());
                if (!field.type().resolved()) {
                    throw new IllegalArgumentException("Cannot emit unresolved attribute type for " + field.javaName());
                }
                if (field.collectionIntent() == CollectionIntent.UNRESOLVED) {
                    throw new IllegalArgumentException("Cannot emit unresolved collection intent for " + field.javaName());
                }
                if (field.collectionIntent() == CollectionIntent.COLLECTION && referencedType != null) {
                    throw new IllegalArgumentException("Entity-valued collection attributes need a relationship descriptor");
                }
                String javaType = referencedType == null
                        ? requireJavaType(field.type().javaTypeName(), field.javaName())
                        : referencedType.qualifiedName;
                String sqlType = referencedType == null ? requireSqlType(field.type().sqlTypeName(), field.javaName())
                        : "UUID";
                String columnName = snakeCase(field.javaName());
                requireSqlIdentifier(columnName, "attribute column");
                plan.attributes.add(new AttributePlan(field, javaType, sqlType, columnName, referencedType,
                        javaDefaultValue(field, javaType, referencedType), sqlDefaultValue(field, sqlType, referencedType)));
            }
        }
        return plans;
    }

    private void attachRelationships(Map<UUID, EntityPlan> plans, List<RelationshipDescriptor> relationships) {
        for (RelationshipDescriptor relationship : relationships) {
            EntityPlan owner = requirePlan(plans, relationship.owningEnd().classifierSourceId());
            EntityPlan referenced = requirePlan(plans, relationship.referencedEnd().classifierSourceId());
            requireJavaIdentifier(relationship.owningFieldName(), "owning relationship field");
            requireSqlIdentifier(snakeCase(relationship.owningFieldName()) + "_id", "relationship column");
            owner.owningRelationships.add(new OwningRelationshipPlan(relationship, referenced));
            if (relationship.inverseCollectionEmitted()) {
                requireJavaIdentifier(relationship.inverseCollectionFieldName(), "inverse relationship field");
                referenced.inverseRelationships.add(new InverseRelationshipPlan(relationship, owner));
            }
        }
    }

    private void validatePlans(Iterable<EntityPlan> plans) {
        for (EntityPlan plan : plans) {
            Set<String> names = new TreeSet<>();
            names.add("id");
            plan.attributes.forEach(attribute -> requireUnique(names, attribute.source().javaName(), plan));
            plan.owningRelationships.forEach(relationship -> requireUnique(names,
                    relationship.descriptor().owningFieldName(), plan));
            plan.inverseRelationships.forEach(relationship -> requireUnique(names,
                    relationship.descriptor().inverseCollectionFieldName(), plan));
            for (OperationSignature operation : plan.type.operations()) {
                requireJavaIdentifier(operation.javaName(), "operation name");
                if (operation.isAbstract() && operation.isStatic()) {
                    throw new IllegalArgumentException("An operation cannot be both abstract and static");
                }
            }
        }
    }

    private String entitySource(EntityPlan plan, Map<UUID, EntityPlan> plans) {
        TreeSet<String> imports = new TreeSet<>(Set.of(
                "jakarta.persistence.Column", "jakarta.persistence.Entity", "jakarta.persistence.FetchType",
                "jakarta.persistence.GeneratedValue", "jakarta.persistence.GenerationType", "jakarta.persistence.Id",
                "jakarta.persistence.JoinColumn", "jakarta.persistence.ManyToOne", "jakarta.persistence.Table",
                "java.util.UUID"));
        if (!plan.inverseRelationships.isEmpty()) {
            imports.add("jakarta.persistence.OneToMany");
        }
        if (plan.attributes.stream().anyMatch(attribute -> attribute.source().collectionIntent() == CollectionIntent.COLLECTION)
                || !plan.inverseRelationships.isEmpty()) {
            imports.add("java.util.ArrayList");
            imports.add("java.util.List");
        }
        if (plan.attributes.stream().anyMatch(attribute -> attribute.source().collectionIntent() == CollectionIntent.COLLECTION)) {
            imports.add("jakarta.persistence.CollectionTable");
            imports.add("jakarta.persistence.ElementCollection");
        }
        plan.attributes.stream().filter(attribute -> attribute.referencedType() != null)
                .forEach(attribute -> imports.add(attribute.referencedType().qualifiedName));
        plan.owningRelationships.forEach(relationship -> imports.add(relationship.referenced().qualifiedName));
        plan.inverseRelationships.forEach(relationship -> imports.add(relationship.owning().qualifiedName));
        plan.attributes.stream().filter(attribute -> attribute.referencedType() == null)
                .map(AttributePlan::javaType).filter(DomainProjectEmitter::isImportable)
                .forEach(imports::add);

        StringBuilder source = new StringBuilder();
        source.append("package ").append(plan.packageName).append(";\n\n");
        imports.forEach(name -> source.append("import ").append(name).append(";\n"));
        boolean abstractClass = plan.type.isAbstract() || plan.type.classifierKind() == ClassifierKind.ABSTRACT_CLASS
                || plan.type.operations().stream().anyMatch(OperationSignature::isAbstract);
        source.append("\n@Entity\n@Table(name = ").append(javaString(quoted(plan.type.tableName()))).append(")\n")
                .append("public ").append(abstractClass ? "abstract " : "").append("class ")
                .append(plan.type.javaName()).append(" {\n")
                .append("    @Id\n")
                .append("    @GeneratedValue(strategy = GenerationType.UUID)\n")
                .append("    @Column(name = ").append(javaString(quoted("id")))
                .append(", nullable = false, updatable = false)\n")
                .append("    private UUID id;\n");

        for (AttributePlan attribute : plan.attributes) {
            FieldDeclaration field = attribute.source();
            source.append('\n');
            if (field.collectionIntent() == CollectionIntent.COLLECTION) {
                String collectionTable = plan.type.tableName() + "_" + attribute.columnName();
                source.append("    @ElementCollection(fetch = FetchType.LAZY)\n")
                        .append("    @CollectionTable(name = ").append(javaString(quoted(collectionTable)))
                        .append(", joinColumns = @JoinColumn(name = ")
                        .append(javaString(quoted(plan.type.tableName() + "_id"))).append("))\n")
                        .append("    @Column(name = ").append(javaString(quoted("value")))
                        .append(", nullable = ").append(field.nullable()).append(")\n");
            } else if (attribute.referencedType() != null) {
                source.append("    @ManyToOne(fetch = FetchType.LAZY, optional = ").append(field.nullable())
                        .append(")\n")
                        .append("    @JoinColumn(name = ").append(javaString(quoted(attribute.columnName() + "_id")))
                        .append(", nullable = ").append(field.nullable()).append(")\n");
            } else {
                source.append("    @Column(name = ").append(javaString(quoted(attribute.columnName())))
                        .append(", nullable = ").append(field.nullable()).append(")\n");
            }
            String declarationType = field.collectionIntent() == CollectionIntent.COLLECTION
                    ? "List<" + boxed(attribute.javaType()) + ">"
                    : attribute.javaType();
            source.append("    private ").append(declarationType).append(' ').append(field.javaName());
            if (field.collectionIntent() == CollectionIntent.COLLECTION) {
                source.append(" = new ArrayList<>()");
            } else if (attribute.javaDefaultValue() != null) {
                source.append(" = ").append(attribute.javaDefaultValue());
            }
            source.append(";\n");
        }

        for (OwningRelationshipPlan relation : plan.owningRelationships) {
            RelationshipDescriptor descriptor = relation.descriptor();
            source.append('\n')
                    .append("    @ManyToOne(fetch = FetchType.LAZY, optional = ")
                    .append(descriptor.owningFieldNullable()).append(")\n")
                    .append("    @JoinColumn(name = ")
                    .append(javaString(quoted(snakeCase(descriptor.owningFieldName()) + "_id")))
                    .append(", nullable = ").append(descriptor.owningFieldNullable()).append(")\n")
                    .append("    private ").append(relation.referenced().qualifiedName).append(' ')
                    .append(descriptor.owningFieldName()).append(";\n");
        }
        for (InverseRelationshipPlan relation : plan.inverseRelationships) {
            RelationshipDescriptor descriptor = relation.descriptor();
            source.append('\n').append("    @OneToMany(fetch = FetchType.LAZY, mappedBy = ")
                    .append(javaString(descriptor.owningFieldName()));
            if (descriptor.lifecycleIntent() == LifecycleIntent.COMPOSITE) {
                source.append(", cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true");
            }
            source.append(")\n")
                    .append("    private List<").append(relation.owning().qualifiedName).append("> ")
                    .append(descriptor.inverseCollectionFieldName()).append(" = new ArrayList<>();\n");
        }

        source.append("\n    protected ").append(plan.type.javaName()).append("() {\n    }\n")
                .append("\n    public UUID getId() {\n        return id;\n    }\n");
        for (AttributePlan attribute : plan.attributes) {
            String type = attribute.source().collectionIntent() == CollectionIntent.COLLECTION
                    ? "List<" + boxed(attribute.javaType()) + ">"
                    : attribute.javaType();
            appendGetter(source, type, attribute.source().javaName());
        }
        for (OwningRelationshipPlan relation : plan.owningRelationships) {
            appendGetter(source, relation.referenced().qualifiedName, relation.descriptor().owningFieldName());
        }
        for (InverseRelationshipPlan relation : plan.inverseRelationships) {
            appendGetter(source, "List<" + relation.owning().qualifiedName + ">",
                    relation.descriptor().inverseCollectionFieldName());
        }
        appendOperationStubs(source, plan, plans);
        source.append("}\n");
        return source.toString();
    }

    private String dtoSource(EntityPlan plan, String rootPackage) {
        TreeSet<String> imports = new TreeSet<>(Set.of("java.util.UUID", plan.qualifiedName));
        if (plan.attributes.stream().anyMatch(attribute -> attribute.source().collectionIntent() == CollectionIntent.COLLECTION)) {
            imports.add("java.util.List");
        }
        plan.attributes.stream().filter(attribute -> attribute.referencedType() == null
                && attribute.source().collectionIntent() != CollectionIntent.COLLECTION)
                .map(AttributePlan::javaType).filter(DomainProjectEmitter::isImportable).forEach(imports::add);

        String dtoPackage = rootPackage + ".api.dto";
        String dtoName = plan.type.javaName() + "Dto";
        List<String> components = new ArrayList<>();
        components.add("UUID id");
        List<String> expressions = new ArrayList<>();
        expressions.add("source.getId()");
        for (AttributePlan attribute : plan.attributes) {
            FieldDeclaration field = attribute.source();
            String componentName = field.javaName();
            String componentType;
            String expression = "source.get" + capitalize(field.javaName()) + "()";
            if (attribute.referencedType() != null) {
                componentName += "Id";
                componentType = "UUID";
                expression = expression + " == null ? null : " + expression + ".getId()";
            } else if (field.collectionIntent() == CollectionIntent.COLLECTION) {
                componentType = "List<" + boxed(attribute.javaType()) + ">";
                expression = "List.copyOf(" + expression + ")";
            } else {
                componentType = attribute.javaType();
            }
            components.add(componentType + " " + componentName);
            expressions.add(expression);
        }

        StringBuilder source = new StringBuilder("package ").append(dtoPackage).append(";\n\n");
        imports.forEach(name -> source.append("import ").append(name).append(";\n"));
        source.append("\npublic record ").append(dtoName).append('(').append(String.join(", ", components))
                .append(") {\n")
                .append("    public static ").append(dtoName).append(" from(").append(plan.type.javaName())
                .append(" source) {\n")
                .append("        return new ").append(dtoName).append('(').append(String.join(", ", expressions))
                .append(");\n    }\n}\n");
        return source.toString();
    }

    private String repositorySource(EntityPlan plan, String rootPackage) {
        String packageName = rootPackage + ".repository";
        String repositoryName = plan.type.javaName() + "Repository";
        return lines(
                "package " + packageName + ";",
                "",
                "import " + plan.qualifiedName + ";",
                "import java.util.UUID;",
                "import org.springframework.data.jpa.repository.JpaRepository;",
                "",
                "public interface " + repositoryName + " extends JpaRepository<" + plan.type.javaName()
                        + ", UUID> {",
                "}");
    }

    private String serviceSource(EntityPlan plan, String rootPackage) {
        String packageName = rootPackage + ".service";
        String dtoName = plan.type.javaName() + "Dto";
        String repositoryName = plan.type.javaName() + "Repository";
        return lines(
                "package " + packageName + ";",
                "",
                "import " + rootPackage + ".api.dto." + dtoName + ";",
                "import " + rootPackage + ".repository." + repositoryName + ";",
                "import java.util.List;",
                "import java.util.Optional;",
                "import java.util.UUID;",
                "import org.springframework.stereotype.Service;",
                "import org.springframework.transaction.annotation.Transactional;",
                "",
                "@Service",
                "@Transactional(readOnly = true)",
                "public class " + plan.type.javaName() + "Service {",
                "    private final " + repositoryName + " repository;",
                "",
                "    public " + plan.type.javaName() + "Service(" + repositoryName + " repository) {",
                "        this.repository = repository;",
                "    }",
                "",
                "    public List<" + dtoName + "> list() {",
                "        return repository.findAll().stream().map(" + dtoName + "::from).toList();",
                "    }",
                "",
                "    public Optional<" + dtoName + "> findById(UUID id) {",
                "        return repository.findById(id).map(" + dtoName + "::from);",
                "    }",
                "}");
    }

    private String controllerSource(EntityPlan plan, String rootPackage) {
        String dtoName = plan.type.javaName() + "Dto";
        String serviceName = plan.type.javaName() + "Service";
        return lines(
                "package " + rootPackage + ".api;",
                "",
                "import " + rootPackage + ".api.dto." + dtoName + ";",
                "import " + rootPackage + ".service." + serviceName + ";",
                "import java.util.List;",
                "import java.util.UUID;",
                "import org.springframework.http.ResponseEntity;",
                "import org.springframework.web.bind.annotation.GetMapping;",
                "import org.springframework.web.bind.annotation.PathVariable;",
                "import org.springframework.web.bind.annotation.RequestMapping;",
                "import org.springframework.web.bind.annotation.RestController;",
                "",
                "@RestController",
                "@RequestMapping(\"/api/" + plan.type.tableName() + "\")",
                "public class " + plan.type.javaName() + "Controller {",
                "    private final " + serviceName + " service;",
                "",
                "    public " + plan.type.javaName() + "Controller(" + serviceName + " service) {",
                "        this.service = service;",
                "    }",
                "",
                "    @GetMapping",
                "    public List<" + dtoName + "> list() {",
                "        return service.list();",
                "    }",
                "",
                "    @GetMapping(\"/{id}\")",
                "    public ResponseEntity<" + dtoName + "> findById(@PathVariable UUID id) {",
                "        return service.findById(id).map(ResponseEntity::ok)"
                        + ".orElseGet(() -> ResponseEntity.<" + dtoName + ">notFound().build());",
                "    }",
                "}");
    }

    private String application(TargetNames target) {
        return lines(
                "package " + target.basePackage() + ";",
                "",
                "import org.springframework.boot.SpringApplication;",
                "import org.springframework.boot.autoconfigure.SpringBootApplication;",
                "",
                "@SpringBootApplication",
                "public class GeneratedApplication {",
                "    public static void main(String[] args) {",
                "        SpringApplication.run(GeneratedApplication.class, args);",
                "    }",
                "}");
    }

    private String pom(TargetNames target) {
        return lines(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"",
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"",
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">",
                "  <modelVersion>4.0.0</modelVersion>",
                "  <parent>",
                "    <groupId>org.springframework.boot</groupId>",
                "    <artifactId>spring-boot-starter-parent</artifactId>",
                "    <version>" + baseline.springBootVersion() + "</version>",
                "    <relativePath/>",
                "  </parent>",
                "  <groupId>com.generated</groupId>",
                "  <artifactId>generated-" + target.slug() + "</artifactId>",
                "  <version>0.1.0</version>",
                "  <name>generated-" + target.slug() + "</name>",
                "  <properties>",
                "    <java.version>" + baseline.javaVersion() + "</java.version>",
                "    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>",
                "  </properties>",
                "  <dependencies>",
                "    <dependency>",
                "      <groupId>org.springframework.boot</groupId>",
                "      <artifactId>spring-boot-starter-web</artifactId>",
                "    </dependency>",
                "    <dependency>",
                "      <groupId>org.springframework.boot</groupId>",
                "      <artifactId>spring-boot-starter-data-jpa</artifactId>",
                "    </dependency>",
                "    <dependency>",
                "      <groupId>org.springframework.boot</groupId>",
                "      <artifactId>spring-boot-starter-validation</artifactId>",
                "    </dependency>",
                "    <dependency>",
                "      <groupId>org.flywaydb</groupId>",
                "      <artifactId>flyway-core</artifactId>",
                "    </dependency>",
                "    <dependency>",
                "      <groupId>org.flywaydb</groupId>",
                "      <artifactId>flyway-database-postgresql</artifactId>",
                "    </dependency>",
                "    <dependency>",
                "      <groupId>org.postgresql</groupId>",
                "      <artifactId>postgresql</artifactId>",
                "      <scope>runtime</scope>",
                "    </dependency>",
                "    <!-- spring-boot-starter-security is omitted: version " + baseline.springBootVersion()
                        + " is unavailable in the local offline dependency cache. -->",
                "  </dependencies>",
                "  <build>",
                "    <plugins>",
                "      <plugin>",
                "        <groupId>org.springframework.boot</groupId>",
                "        <artifactId>spring-boot-maven-plugin</artifactId>",
                "      </plugin>",
                "    </plugins>",
                "  </build>",
                "</project>");
    }

    private String applicationYaml() {
        return lines(
                "spring:",
                "  datasource:",
                "    url: ${SPRING_DATASOURCE_URL}",
                "    username: ${SPRING_DATASOURCE_USERNAME}",
                "    password: ${SPRING_DATASOURCE_PASSWORD}",
                "  jpa:",
                "    hibernate:",
                "      ddl-auto: validate",
                "    open-in-view: false",
                "  flyway:",
                "    enabled: true",
                "    locations: classpath:db/migration");
    }

    private String dockerfile() {
        return lines(
                "FROM " + baseline.runtimeBaseImage(),
                "WORKDIR /app",
                "COPY target/*.jar /app/app.jar",
                "EXPOSE 8080",
                "ENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]");
    }

    private String readme(TargetNames target) {
        return lines(
                "# Generated backend for target `" + target.slug() + "`",
                "",
                "This project was emitted from a normalized UML domain IR. Its package and artifact names derive from the generation target key.",
                "",
                "## Build and database configuration",
                "",
                "Build with `mvn -o -B package` when the Spring Boot " + baseline.springBootVersion()
                        + " dependency set is present in the local Maven cache.",
                "Set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` in the runtime environment.",
                "Flyway applies `src/main/resources/db/migration/V1__init.sql` at startup.",
                "List and lookup services map entities to DTOs inside read-only transactions; the API does not serialize lazy entities.",
                "",
                "## Security limitation",
                "",
                "The Spring Boot security starter is intentionally omitted because version " + baseline.springBootVersion()
                        + " is unavailable in the configured local offline Maven cache. This emitted backend has no generated authentication or authorization layer and must not be exposed to untrusted networks until an approved security dependency and policy are supplied.");
    }

    private String migration(List<EntityPlan> plans) {
        List<String> statements = new ArrayList<>();
        statements.add("-- Initial schema generated from the normalized domain IR.");
        for (EntityPlan plan : plans) {
            List<String> columns = new ArrayList<>();
            columns.add(quoted("id") + " UUID NOT NULL PRIMARY KEY");
            for (AttributePlan attribute : plan.attributes) {
                if (attribute.source().collectionIntent() == CollectionIntent.COLLECTION) {
                    continue;
                }
                String columnName = attribute.referencedType() == null
                        ? attribute.columnName()
                        : attribute.columnName() + "_id";
                columns.add(quoted(columnName) + " " + attribute.sqlType()
                        + (attribute.sqlDefaultValue() == null ? "" : " DEFAULT " + attribute.sqlDefaultValue())
                        + (attribute.source().nullable() ? "" : " NOT NULL"));
            }
            for (OwningRelationshipPlan relationship : plan.owningRelationships) {
                RelationshipDescriptor descriptor = relationship.descriptor();
                String columnName = snakeCase(descriptor.owningFieldName()) + "_id";
                columns.add(quoted(columnName) + " UUID"
                        + (descriptor.owningFieldNullable() ? "" : " NOT NULL"));
            }
            for (AttributePlan attribute : plan.attributes) {
                if (attribute.referencedType() != null
                        && attribute.source().collectionIntent() != CollectionIntent.COLLECTION) {
                    String columnName = attribute.columnName() + "_id";
                    String constraint = foreignKeyName(plan.type.tableName(), columnName);
                    columns.add("CONSTRAINT " + quoted(constraint) + " FOREIGN KEY (" + quoted(columnName)
                            + ") REFERENCES " + quoted(attribute.referencedType().type.tableName()) + " (" + quoted("id") + ")");
                }
            }
            for (OwningRelationshipPlan relationship : plan.owningRelationships) {
                String columnName = snakeCase(relationship.descriptor().owningFieldName()) + "_id";
                String constraint = foreignKeyName(plan.type.tableName(), columnName);
                columns.add("CONSTRAINT " + quoted(constraint) + " FOREIGN KEY (" + quoted(columnName)
                        + ") REFERENCES " + quoted(relationship.referenced().type.tableName()) + " (" + quoted("id") + ")");
            }
            statements.add("CREATE TABLE " + quoted(plan.type.tableName()) + " (\n  "
                    + String.join(",\n  ", columns) + "\n);");
        }
        for (EntityPlan plan : plans) {
            for (AttributePlan attribute : plan.attributes) {
                if (attribute.source().collectionIntent() != CollectionIntent.COLLECTION) {
                    continue;
                }
                String table = plan.type.tableName() + "_" + attribute.columnName();
                statements.add("CREATE TABLE " + quoted(table) + " (\n  "
                        + quoted(plan.type.tableName() + "_id") + " UUID NOT NULL REFERENCES "
                        + quoted(plan.type.tableName()) + " (" + quoted("id") + "),\n  "
                        + quoted("value") + " " + attribute.sqlType()
                        + (attribute.source().nullable() ? "" : " NOT NULL") + "\n);");
            }
        }
        return lines(statements.toArray(String[]::new));
    }

    private void appendOperationStubs(StringBuilder source, EntityPlan plan, Map<UUID, EntityPlan> plans) {
        for (OperationSignature operation : plan.type.operations()) {
            String returnType = operationType(operation.returnType(), plans);
            List<String> parameters = operation.parameters().stream()
                    .map(parameter -> operationType(parameter.type(), plans) + " " + parameter.javaName()).toList();
            String visibility = switch (operation.visibility().toUpperCase(Locale.ROOT)) {
                case "PRIVATE" -> "private";
                case "PROTECTED" -> "protected";
                default -> "public";
            };
            source.append('\n').append("    ").append(visibility).append(' ');
            if (operation.isStatic()) {
                source.append("static ");
            }
            if (operation.isAbstract()) {
                source.append("abstract ");
            }
            source.append(returnType).append(' ').append(operation.javaName()).append('(')
                    .append(String.join(", ", parameters)).append(')');
            if (operation.isAbstract()) {
                source.append(";\n");
            } else {
                source.append(" {\n        throw new UnsupportedOperationException(\"Business behavior is not defined by the UML signature.\");\n    }\n");
            }
        }
    }

    private String operationType(TypeUse type, Map<UUID, EntityPlan> plans) {
        if (!type.resolved()) {
            throw new IllegalArgumentException("Cannot emit an operation with an unresolved type");
        }
        if (type.sourceClassifierId() != null) {
            return requirePlan(plans, type.sourceClassifierId()).qualifiedName;
        }
        return requireJavaType(type.javaTypeName(), "operation type");
    }

    private String entityPath(EntityPlan plan) {
        return javaPath(plan.packageName, plan.type.javaName());
    }

    private String dtoPath(EntityPlan plan, String rootPackage) {
        return javaPath(rootPackage + ".api.dto", plan.type.javaName() + "Dto");
    }

    private String repositoryPath(EntityPlan plan, String rootPackage) {
        return javaPath(rootPackage + ".repository", plan.type.javaName() + "Repository");
    }

    private String servicePath(EntityPlan plan, String rootPackage) {
        return javaPath(rootPackage + ".service", plan.type.javaName() + "Service");
    }

    private String controllerPath(EntityPlan plan, String rootPackage) {
        return javaPath(rootPackage + ".api", plan.type.javaName() + "Controller");
    }

    private static String javaPath(String packageName, String className) {
        return "src/main/java/" + packageName.replace('.', '/') + "/" + className + ".java";
    }

    private static String entityPackage(String rootPackage, String modelPackage) {
        String suffix = modelPackage == null || modelPackage.isBlank()
                ? ""
                : "." + packageSegments(modelPackage);
        return rootPackage + ".domain" + suffix;
    }

    private static String packageSegments(String value) {
        List<String> parts = new ArrayList<>();
        for (String raw : value.split("\\.")) {
            if (raw.isBlank()) {
                continue;
            }
            String safe = raw.replaceAll("[^A-Za-z0-9_$]", "_");
            if (safe.isEmpty() || !Character.isJavaIdentifierStart(safe.charAt(0))) {
                safe = "p_" + safe;
            }
            safe = safe.toLowerCase(Locale.ROOT);
            if (JAVA_RESERVED.contains(safe)) {
                safe = "p_" + safe;
            }
            if (!JAVA_IDENTIFIER.matcher(safe).matches()) {
                throw new IllegalArgumentException("Invalid package segment in IR: " + raw);
            }
            parts.add(safe);
        }
        return String.join(".", parts);
    }

    private static TargetNames targetNames(String targetKey) {
        List<String> parts = new ArrayList<>();
        for (String raw : targetKey.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.isBlank()) {
                continue;
            }
            parts.add(Character.isDigit(raw.charAt(0)) || JAVA_RESERVED.contains(raw) ? "target" + raw : raw);
        }
        if (parts.isEmpty()) {
            parts.add("target");
        }
        String slug = String.join("-", parts);
        return new TargetNames(slug, "com.generated." + String.join(".", parts));
    }

    private static String javaDefaultValue(FieldDeclaration field, String javaType, EntityPlan referencedType) {
        String value = field.defaultValue();
        if (value == null) {
            return null;
        }
        value = requireSafeDefault(value, field.javaName());
        if (field.collectionIntent() == CollectionIntent.COLLECTION || referencedType != null) {
            throw new IllegalArgumentException("Default values for collections and entity references are unsupported: "
                    + field.javaName());
        }
        return switch (javaType) {
            case "String" -> javaStringLiteral(value);
            case "Integer" -> integralDefault(value, field.javaName());
            case "Long" -> integralDefault(value, field.javaName()) + "L";
            case "Double" -> decimalDefault(value, field.javaName()) + "d";
            case "Boolean" -> booleanDefault(value, field.javaName());
            case "java.math.BigDecimal" -> "new java.math.BigDecimal(" + javaStringLiteral(decimalDefault(value,
                    field.javaName())) + ")";
            case "java.time.LocalDate" -> "java.time.LocalDate.parse(" + javaStringLiteral(dateDefault(value,
                    field.javaName())) + ")";
            case "java.time.LocalDateTime" -> "java.time.LocalDateTime.parse(" + javaStringLiteral(dateTimeDefault(value,
                    field.javaName())) + ")";
            case "java.util.UUID" -> "java.util.UUID.fromString(" + javaStringLiteral(uuidDefault(value,
                    field.javaName())) + ")";
            default -> throw new IllegalArgumentException("Unsupported default value type for " + field.javaName()
                    + ": " + javaType);
        };
    }

    private static String sqlDefaultValue(FieldDeclaration field, String sqlType, EntityPlan referencedType) {
        String value = field.defaultValue();
        if (value == null) {
            return null;
        }
        value = requireSafeDefault(value, field.javaName());
        if (field.collectionIntent() == CollectionIntent.COLLECTION || referencedType != null) {
            throw new IllegalArgumentException("Default values for collections and entity references are unsupported: "
                    + field.javaName());
        }
        if (sqlType.startsWith("VARCHAR") || sqlType.startsWith("CHAR") || sqlType.equals("TEXT")) {
            return "'" + value.replace("'", "''") + "'";
        }
        if (sqlType.equals("BOOLEAN")) {
            return booleanDefault(value, field.javaName());
        }
        if (sqlType.equals("INTEGER") || sqlType.equals("BIGINT")) {
            return integralDefault(value, field.javaName());
        }
        if (sqlType.startsWith("NUMERIC") || sqlType.equals("DOUBLE PRECISION")) {
            return decimalDefault(value, field.javaName());
        }
        if (sqlType.equals("DATE")) {
            return "'" + dateDefault(value, field.javaName()) + "'";
        }
        if (sqlType.equals("TIMESTAMP")) {
            return "'" + dateTimeDefault(value, field.javaName()) + "'";
        }
        if (sqlType.equals("UUID")) {
            return "'" + uuidDefault(value, field.javaName()) + "'";
        }
        throw new IllegalArgumentException("Unsupported SQL default value type for " + field.javaName() + ": "
                + sqlType);
    }

    private static String requireSafeDefault(String value, String fieldName) {
        if (value.contains("\n") || value.contains("\r") || value.indexOf('\0') >= 0
                || HOST_PATH.matcher(value).find()) {
            throw new IllegalArgumentException("Unsafe default value for " + fieldName);
        }
        return value;
    }

    private static String integralDefault(String value, String fieldName) {
        if (!value.matches("[+-]?[0-9]+")) {
            throw new IllegalArgumentException("Invalid integer default for " + fieldName);
        }
        return value;
    }

    private static String decimalDefault(String value, String fieldName) {
        if (!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
            throw new IllegalArgumentException("Invalid decimal default for " + fieldName);
        }
        return value;
    }

    private static String booleanDefault(String value, String fieldName) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return value.toLowerCase(Locale.ROOT);
        }
        throw new IllegalArgumentException("Invalid boolean default for " + fieldName);
    }

    private static String dateDefault(String value, String fieldName) {
        try {
            return java.time.LocalDate.parse(value).toString();
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid date default for " + fieldName, exception);
        }
    }

    private static String dateTimeDefault(String value, String fieldName) {
        try {
            return java.time.LocalDateTime.parse(value).toString();
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid timestamp default for " + fieldName, exception);
        }
    }

    private static String uuidDefault(String value, String fieldName) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid UUID default for " + fieldName, exception);
        }
    }

    private static String requireJavaType(String value, String description) {
        if (value == null || !value.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
            throw new IllegalArgumentException("Unsupported Java type for " + description + ": " + value);
        }
        return value;
    }

    private static String requireSqlType(String value, String description) {
        if (value == null) {
            throw new IllegalArgumentException("Missing SQL type for " + description);
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!SQL_TYPE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Unsupported SQL type for " + description + ": " + value);
        }
        return normalized;
    }

    private static void requireJavaIdentifier(String value, String description) {
        if (value == null || !JAVA_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Java " + description + ": " + value);
        }
    }

    private static void requireSqlIdentifier(String value, String description) {
        if (value == null || !value.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid SQL " + description + ": " + value);
        }
    }

    private static String quoted(String identifier) {
        requireSqlIdentifier(identifier, "identifier");
        return "\"" + identifier + "\"";
    }

    private static String foreignKeyName(String tableName, String columnName) {
        return "fk_" + tableName + "_" + columnName;
    }

    private static String snakeCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String boxed(String javaType) {
        return switch (javaType) {
            case "boolean" -> "Boolean";
            case "byte" -> "Byte";
            case "short" -> "Short";
            case "int" -> "Integer";
            case "long" -> "Long";
            case "float" -> "Float";
            case "double" -> "Double";
            case "char" -> "Character";
            default -> javaType;
        };
    }

    private static boolean isImportable(String name) {
        return name != null && name.contains(".") && !name.startsWith("java.lang.");
    }

    private static String javaString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void appendGetter(StringBuilder source, String type, String fieldName) {
        source.append("\n    public ").append(type).append(" get").append(capitalize(fieldName)).append("() {\n")
                .append("        return ").append(fieldName).append(";\n    }\n");
    }

    private static String capitalize(String value) {
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private static void requireUnique(Set<String> names, String name, EntityPlan plan) {
        if (!names.add(name)) {
            throw new IllegalArgumentException("Duplicate generated property '" + name + "' in " + plan.qualifiedName);
        }
    }

    private static EntityPlan requirePlan(Map<UUID, EntityPlan> plans, UUID id) {
        EntityPlan plan = plans.get(id);
        if (plan == null) {
            throw new IllegalArgumentException("IR references unknown classifier " + id);
        }
        return plan;
    }

    private static void put(Map<String, String> files, String path, String content) {
        if (!path.startsWith("src/") && !Set.of(".gitignore", "Dockerfile", "README.md", "pom.xml")
                .contains(path)) {
            throw new IllegalArgumentException("Invalid emitted path " + path);
        }
        if (files.putIfAbsent(path, content) != null) {
            throw new IllegalArgumentException("IR produces duplicate artifact path " + path);
        }
    }

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static String javaStringLiteral(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static final class EntityPlan {
        private final TypeDeclaration type;
        private final String packageName;
        private final String qualifiedName;
        private final List<AttributePlan> attributes = new ArrayList<>();
        private final List<OwningRelationshipPlan> owningRelationships = new ArrayList<>();
        private final List<InverseRelationshipPlan> inverseRelationships = new ArrayList<>();

        private EntityPlan(TypeDeclaration type, String packageName, String qualifiedName) {
            this.type = type;
            this.packageName = packageName;
            this.qualifiedName = qualifiedName;
        }
    }

    private record AttributePlan(FieldDeclaration source, String javaType, String sqlType, String columnName,
            EntityPlan referencedType, String javaDefaultValue, String sqlDefaultValue) {
    }

    private record OwningRelationshipPlan(RelationshipDescriptor descriptor, EntityPlan referenced) {
    }

    private record InverseRelationshipPlan(RelationshipDescriptor descriptor, EntityPlan owning) {
    }

    private record TargetNames(String slug, String basePackage) {
    }
}
