package com.diagramador.generation;

import com.diagramador.generation.DomainModelIR.CollectionIntent;
import com.diagramador.generation.DomainModelIR.EnumDeclaration;
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
        Map<UUID, EnumPlan> enumPlans = createEnumPlans(target.basePackage(), ir.enums());
        Map<UUID, EntityPlan> plans = createPlans(target.basePackage(), ir.types(), enumPlans);
        attachRelationships(plans, ir.relationships());
        validatePlans(plans.values());

        TreeMap<String, String> files = new TreeMap<>();
        put(files, ".env.example", envExample(target));
        put(files, ".gitignore", lines(
                "/target/",
                ".env",
                ".idea/",
                ".vscode/",
                "*.iml"));
        put(files, "Dockerfile", dockerfile());
        put(files, "README.md", readme(target));
        put(files, "docker-compose.yml", compose(target));
        put(files, "pom.xml", pom(target));
        put(files, javaPath(target.basePackage() + ".api", "ApiErrorResponse"), apiErrorResponse(target));
        put(files, javaPath(target.basePackage() + ".api", "GeneratedApiException"), generatedApiException(target));
        put(files, javaPath(target.basePackage() + ".api", "GeneratedApiExceptionHandler"),
                generatedApiExceptionHandler(target));
        put(files, javaPath(target.basePackage(), "GeneratedApplication"), application(target));
        put(files, javaPath(target.basePackage() + ".api", "QuerySupport"), querySupport(target));

        List<EntityPlan> orderedPlans = plans.values().stream()
                .sorted((left, right) -> left.qualifiedName.compareTo(right.qualifiedName)).toList();
        for (EntityPlan plan : orderedPlans) {
            put(files, entityPath(plan), entitySource(plan, plans, enumPlans));
            put(files, dtoPath(plan, target.basePackage()), dtoSource(plan, target.basePackage()));
            put(files, repositoryPath(plan, target.basePackage()), repositorySource(plan, target.basePackage()));
            put(files, servicePath(plan, target.basePackage()), serviceSource(plan, target.basePackage(),
                    QueryFieldCatalog.fields(plan.type, ir), enumPlans));
            put(files, controllerPath(plan, target.basePackage()), controllerSource(plan, target.basePackage()));
        }
        for (EnumPlan enumPlan : enumPlans.values()) {
            put(files, javaPath(enumPlan.packageName(), enumPlan.declaration().javaName()), enumSource(enumPlan));
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

    private Map<UUID, EnumPlan> createEnumPlans(String rootPackage, List<EnumDeclaration> enums) {
        Map<UUID, EnumPlan> plans = new LinkedHashMap<>();
        for (EnumDeclaration declaration : enums) {
            requireJavaIdentifier(declaration.javaName(), "enumeration name");
            if (declaration.literals().isEmpty()) {
                throw new IllegalArgumentException("Cannot emit an enumeration without literals: "
                        + declaration.javaName());
            }
            declaration.literals().forEach(literal -> requireJavaIdentifier(literal, "enumeration literal"));
            String packageName = entityPackage(rootPackage, declaration.javaPackageName());
            plans.put(declaration.sourceId(), new EnumPlan(declaration, packageName,
                    packageName + "." + declaration.javaName()));
        }
        return plans;
    }

    private static String enumSource(EnumPlan plan) {
        return "package " + plan.packageName() + ";\n\npublic enum " + plan.declaration().javaName() + " {\n    "
                + String.join(",\n    ", plan.declaration().literals()) + "\n}\n";
    }

    private Map<UUID, EntityPlan> createPlans(String rootPackage, List<TypeDeclaration> types,
            Map<UUID, EnumPlan> enumPlans) {
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
                EnumPlan enumType = field.type().sourceClassifierId() == null
                        ? null
                        : enumPlans.get(field.type().sourceClassifierId());
                EntityPlan referencedType = field.type().sourceClassifierId() == null || enumType != null
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
                String javaType = enumType != null ? enumType.qualifiedName()
                        : referencedType == null
                        ? requireJavaType(field.type().javaTypeName(), field.javaName())
                        : referencedType.qualifiedName;
                String sqlType = referencedType == null ? requireSqlType(field.type().sqlTypeName(), field.javaName())
                        : "UUID";
                String columnName = snakeCase(field.javaName());
                requireSqlIdentifier(columnName, "attribute column");
                String javaDefault = enumType != null ? enumDefault(field, enumType)
                        : javaDefaultValue(field, javaType, referencedType);
                plan.attributes.add(new AttributePlan(field, javaType, sqlType, columnName, referencedType,
                        javaDefault, sqlDefaultValue(field, sqlType, referencedType), enumType));
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

    private String entitySource(EntityPlan plan, Map<UUID, EntityPlan> plans,
            Map<UUID, EnumPlan> enumPlans) {
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
        if (plan.attributes.stream().anyMatch(attribute -> attribute.enumType() != null)) {
            imports.add("jakarta.persistence.EnumType");
            imports.add("jakarta.persistence.Enumerated");
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
            if (attribute.enumType() != null) {
                source.append("    @Enumerated(EnumType.STRING)\n");
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

        source.append("\n    public ").append(plan.type.javaName()).append("() {\n    }\n")
                .append("\n    public UUID getId() {\n        return id;\n    }\n");
        for (AttributePlan attribute : plan.attributes) {
            String type = attribute.source().collectionIntent() == CollectionIntent.COLLECTION
                    ? "List<" + boxed(attribute.javaType()) + ">"
                    : attribute.javaType();
            appendGetter(source, type, attribute.source().javaName());
            appendSetter(source, type, attribute.source().javaName());
        }
        for (OwningRelationshipPlan relation : plan.owningRelationships) {
            appendGetter(source, relation.referenced().qualifiedName, relation.descriptor().owningFieldName());
            appendSetter(source, relation.referenced().qualifiedName, relation.descriptor().owningFieldName());
        }
        for (InverseRelationshipPlan relation : plan.inverseRelationships) {
            appendGetter(source, "List<" + relation.owning().qualifiedName + ">",
                    relation.descriptor().inverseCollectionFieldName());
        }
        appendOperationStubs(source, plan, plans, enumPlans);
        source.append("}\n");
        return source.toString();
    }

    private String dtoSource(EntityPlan plan, String rootPackage) {
        TreeSet<String> imports = new TreeSet<>(Set.of("java.util.UUID", plan.qualifiedName));
        boolean hasCollections = plan.attributes.stream()
                .anyMatch(attribute -> attribute.source().collectionIntent() == CollectionIntent.COLLECTION);
        boolean hasRequiredFields = plan.attributes.stream().anyMatch(attribute -> !attribute.source().nullable())
                || plan.owningRelationships.stream().anyMatch(value -> !value.descriptor().owningFieldNullable());
        if (hasCollections) {
            imports.add("java.util.List");
        }
        if (hasRequiredFields) {
            imports.add("jakarta.validation.constraints.NotNull");
        }
        plan.attributes.stream().filter(attribute -> attribute.referencedType() == null)
                .map(AttributePlan::javaType).filter(DomainProjectEmitter::isImportable).forEach(imports::add);

        String dtoPackage = rootPackage + ".api.dto";
        String dtoName = plan.type.javaName() + "Dto";
        List<String> components = new ArrayList<>();
        List<String> expressions = new ArrayList<>();
        Set<String> componentNames = new TreeSet<>();
        addDtoComponent(components, expressions, componentNames, "UUID", "id", "source.getId()", false);
        for (AttributePlan attribute : plan.attributes) {
            FieldDeclaration field = attribute.source();
            String componentName = field.javaName();
            String componentType;
            String getter = "source.get" + capitalize(field.javaName()) + "()";
            String expression = getter;
            if (attribute.referencedType() != null) {
                componentName += "Id";
                componentType = "UUID";
                expression = getter + " == null ? null : " + getter + ".getId()";
            } else if (field.collectionIntent() == CollectionIntent.COLLECTION) {
                componentType = "List<" + boxed(attribute.javaType()) + ">";
                expression = getter + " == null ? List.of() : List.copyOf(" + getter + ")";
            } else {
                componentType = attribute.javaType();
            }
            addDtoComponent(components, expressions, componentNames, componentType, componentName, expression,
                    !field.nullable());
        }
        for (OwningRelationshipPlan relationship : plan.owningRelationships) {
            RelationshipDescriptor descriptor = relationship.descriptor();
            String name = descriptor.owningFieldName() + "Id";
            String getter = "source.get" + capitalize(descriptor.owningFieldName()) + "()";
            addDtoComponent(components, expressions, componentNames, "UUID", name,
                    getter + " == null ? null : " + getter + ".getId()", !descriptor.owningFieldNullable());
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

    private static void addDtoComponent(List<String> components, List<String> expressions, Set<String> names,
            String type, String name, String expression, boolean required) {
        requireJavaIdentifier(name, "DTO component");
        if (!names.add(name)) {
            throw new IllegalArgumentException("Duplicate generated DTO component: " + name);
        }
        components.add((required ? "@NotNull " : "") + type + " " + name);
        expressions.add(expression);
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
                "import org.springframework.data.jpa.repository.JpaSpecificationExecutor;",
                "",
                "public interface " + repositoryName + " extends JpaRepository<" + plan.type.javaName()
                        + ", UUID>, JpaSpecificationExecutor<" + plan.type.javaName() + "> {",
                "}");
    }

    private String serviceSource(EntityPlan plan, String rootPackage, List<QueryFieldCatalog.QueryField> queryFields,
            Map<UUID, EnumPlan> enumPlans) {
        String packageName = rootPackage + ".service";
        String dtoName = plan.type.javaName() + "Dto";
        String repositoryName = plan.type.javaName() + "Repository";
        boolean hasReferences = plan.attributes.stream().anyMatch(attribute -> attribute.referencedType() != null)
                || !plan.owningRelationships.isEmpty();
        StringBuilder source = new StringBuilder();
        source.append("package ").append(packageName).append(";\n\n")
                .append("import ").append(rootPackage).append(".api.QuerySupport;\n")
                .append("import ").append(rootPackage).append(".api.dto.").append(dtoName).append(";\n")
                .append("import ").append(rootPackage).append(".repository.").append(repositoryName).append(";\n")
                .append("import ").append(plan.qualifiedName).append(";\n")
                .append("import jakarta.persistence.EntityManager;\n")
                .append("import jakarta.persistence.PersistenceContext;\n")
                .append("import java.util.List;\n")
                .append("import java.util.Map;\n")
                .append("import java.util.Optional;\n")
                .append("import java.util.UUID;\n")
                .append("import org.springframework.data.domain.PageRequest;\n")
                .append("import org.springframework.stereotype.Service;\n")
                .append("import org.springframework.transaction.annotation.Transactional;\n");
        if (hasReferences) {
            source.append("import ").append(rootPackage).append(".api.GeneratedApiException;\n");
        }
        source.append("\n@Service\n@Transactional(readOnly = true)\npublic class ")
                .append(plan.type.javaName()).append("Service {\n")
                .append("    private static final Map<String, QuerySupport.Field> QUERY_FIELDS = QuerySupport.index(");
        for (int index = 0; index < queryFields.size(); index++) {
            QueryFieldCatalog.QueryField field = queryFields.get(index);
            String enumClass = field.enumeration() == null ? "null"
                    : enumPlans.get(field.enumeration().sourceId()).qualifiedName() + ".class";
            source.append(index == 0 ? "\n" : ",\n").append("            new QuerySupport.Field(")
                    .append(javaString(field.name())).append(", ").append(javaString(field.jpaPath()))
                    .append(", QuerySupport.Kind.").append(field.kind().name()).append(", ").append(enumClass)
                    .append(')');
        }
        source.append(");\n\n")
                .append("    private final ").append(repositoryName).append(" repository;\n")
                .append("\n    @PersistenceContext\n    private EntityManager entityManager;\n");
        source.append("\n    public ").append(plan.type.javaName()).append("Service(").append(repositoryName)
                .append(" repository) {\n        this.repository = repository;\n    }\n")
                .append("\n    public List<").append(dtoName).append("> list() {\n")
                .append("        return repository.findAll().stream().map(").append(dtoName).append("::from).toList();\n")
                .append("    }\n")
                .append("\n    public QuerySupport.Page<").append(dtoName)
                .append("> query(List<String> filters, String sort, Integer page, Integer size) {\n")
                .append("        int pageNumber = QuerySupport.page(page);\n")
                .append("        int pageSize = QuerySupport.size(size);\n")
                .append("        var result = repository.findAll(QuerySupport.where(QUERY_FIELDS, filters),\n")
                .append("                PageRequest.of(pageNumber, pageSize, QuerySupport.sort(QUERY_FIELDS, sort)));\n")
                .append("        return new QuerySupport.Page<>(result.map(").append(dtoName)
                .append("::from).getContent(), result.getTotalElements(),\n")
                .append("                pageNumber, pageSize);\n    }\n")
                .append("\n    public List<Object> values(String field, List<String> filters) {\n")
                .append("        return QuerySupport.distinct(entityManager, ").append(plan.type.javaName())
                .append(".class, QUERY_FIELDS, field, filters);\n    }\n")
                .append("\n    public List<QuerySupport.Bucket> aggregate(String op, String field, String groupBy,")
                .append(" List<String> filters) {\n")
                .append("        return QuerySupport.aggregate(entityManager, ").append(plan.type.javaName())
                .append(".class, QUERY_FIELDS, op, field, groupBy,\n                filters);\n    }\n")
                .append("\n    public Optional<").append(dtoName).append("> findById(UUID id) {\n")
                .append("        return repository.findById(id).map(").append(dtoName).append("::from);\n")
                .append("    }\n")
                .append("\n    @Transactional\n    public ").append(dtoName).append(" create(").append(dtoName)
                .append(" request) {\n        ").append(plan.type.javaName()).append(" entity = new ")
                .append(plan.type.javaName()).append("();\n        applyRequest(entity, request);\n")
                .append("        return ").append(dtoName).append(".from(repository.saveAndFlush(entity));\n    }\n")
                .append("\n    @Transactional\n    public Optional<").append(dtoName).append("> update(UUID id, ")
                .append(dtoName).append(" request) {\n")
                .append("        return repository.findById(id).map(entity -> {\n")
                .append("            applyRequest(entity, request);\n")
                .append("            return ").append(dtoName).append(".from(repository.saveAndFlush(entity));\n")
                .append("        });\n    }\n")
                .append("\n    @Transactional\n    public boolean delete(UUID id) {\n")
                .append("        if (!repository.existsById(id)) {\n            return false;\n        }\n")
                .append("        repository.deleteById(id);\n        return true;\n    }\n")
                .append("\n    private void applyRequest(").append(plan.type.javaName()).append(" entity, ")
                .append(dtoName).append(" request) {\n");
        for (AttributePlan attribute : plan.attributes) {
            FieldDeclaration field = attribute.source();
            String component = field.javaName() + (attribute.referencedType() == null ? "" : "Id");
            String value = "request." + component + "()";
            if (attribute.referencedType() != null) {
                value = "resolveReference(" + value + ", \"" + component + "\", "
                        + attribute.referencedType().qualifiedName + ".class)";
            } else if (field.collectionIntent() == CollectionIntent.COLLECTION) {
                value = value + " == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(" + value + ")";
            }
            source.append("        entity.set").append(capitalize(field.javaName())).append('(')
                    .append(value).append(");\n");
        }
        for (OwningRelationshipPlan relationship : plan.owningRelationships) {
            String component = relationship.descriptor().owningFieldName() + "Id";
            String value = "resolveReference(request." + component + "(), \"" + component + "\", "
                    + relationship.referenced().qualifiedName + ".class)";
            source.append("        entity.set").append(capitalize(relationship.descriptor().owningFieldName()))
                    .append('(').append(value).append(");\n");
        }
        source.append("    }\n");
        if (hasReferences) {
            source.append("\n    private <T> T resolveReference(UUID id, String field, Class<T> type) {\n")
                    .append("        if (id == null) {\n            return null;\n        }\n")
                    .append("        T referenced = entityManager.find(type, id);\n")
                    .append("        if (referenced == null) {\n")
                    .append("            throw GeneratedApiException.invalidReference(field + \"=\" + id);\n")
                    .append("        }\n        return referenced;\n    }\n");
        }
        source.append("}\n");
        return source.toString();
    }

    private String controllerSource(EntityPlan plan, String rootPackage) {
        String dtoName = plan.type.javaName() + "Dto";
        String serviceName = plan.type.javaName() + "Service";
        return lines(
                "package " + rootPackage + ".api;",
                "",
                "import " + rootPackage + ".api.dto." + dtoName + ";",
                "import " + rootPackage + ".service." + serviceName + ";",
                "import " + rootPackage + ".api.GeneratedApiException;",
                "import jakarta.validation.Valid;",
                "import java.util.List;",
                "import java.util.UUID;",
                "import org.springframework.http.HttpStatus;",
                "import org.springframework.http.ResponseEntity;",
                "import org.springframework.web.bind.annotation.DeleteMapping;",
                "import org.springframework.web.bind.annotation.GetMapping;",
                "import org.springframework.web.bind.annotation.PathVariable;",
                "import org.springframework.web.bind.annotation.PostMapping;",
                "import org.springframework.web.bind.annotation.PutMapping;",
                "import org.springframework.web.bind.annotation.RequestBody;",
                "import org.springframework.web.bind.annotation.RequestMapping;",
                "import org.springframework.web.bind.annotation.RequestParam;",
                "import org.springframework.web.bind.annotation.RestController;",
                "import org.springframework.util.MultiValueMap;",
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
                "    // Filters are read raw from the query string: a typed List<String> would split values on commas.",
                "    @GetMapping(\"/query\")",
                "    public QuerySupport.Page<" + dtoName + "> query(@RequestParam MultiValueMap<String, String> params,",
                "            @RequestParam(required = false) String sort, @RequestParam(required = false) Integer page,",
                "            @RequestParam(required = false) Integer size) {",
                "        return service.query(filters(params), sort, page, size);",
                "    }",
                "",
                "    @GetMapping(\"/values\")",
                "    public List<Object> values(@RequestParam MultiValueMap<String, String> params,",
                "            @RequestParam(required = false) String field) {",
                "        return service.values(field, filters(params));",
                "    }",
                "",
                "    @GetMapping(\"/aggregate\")",
                "    public List<QuerySupport.Bucket> aggregate(@RequestParam MultiValueMap<String, String> params,",
                "            @RequestParam(required = false) String op, @RequestParam(required = false) String field,",
                "            @RequestParam(required = false) String groupBy) {",
                "        return service.aggregate(op, field, groupBy, filters(params));",
                "    }",
                "",
                "    private static List<String> filters(MultiValueMap<String, String> params) {",
                "        List<String> filters = params.get(\"filter\");",
                "        return filters == null ? List.of() : filters;",
                "    }",
                "",
                "    @PostMapping",
                "    public ResponseEntity<" + dtoName + "> create(@Valid @RequestBody " + dtoName + " request) {",
                "        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));",
                "    }",
                "",
                "    @GetMapping(\"/{id}\")",
                "    public ResponseEntity<" + dtoName + "> findById(@PathVariable UUID id) {",
                "        return ResponseEntity.ok(service.findById(id).orElseThrow(() ->",
                "                GeneratedApiException.notFound(\"" + plan.type.javaName() + " id=\" + id)));",
                "    }",
                "",
                "    @PutMapping(\"/{id}\")",
                "    public ResponseEntity<" + dtoName + "> update(@PathVariable UUID id,",
                "            @Valid @RequestBody " + dtoName + " request) {",
                "        return ResponseEntity.ok(service.update(id, request).orElseThrow(() ->",
                "                GeneratedApiException.notFound(\"" + plan.type.javaName() + " id=\" + id)));",
                "    }",
                "",
                "    @DeleteMapping(\"/{id}\")",
                "    public ResponseEntity<Void> delete(@PathVariable UUID id) {",
                "        if (!service.delete(id)) {",
                "            throw GeneratedApiException.notFound(\"" + plan.type.javaName() + " id=\" + id);",
                "        }",
                "        return ResponseEntity.noContent().build();",
                "    }",
                "}");
    }

    private String querySupport(TargetNames target) {
        return QUERY_SUPPORT_TEMPLATE.replace("{{package}}", target.basePackage() + ".api")
                .replace("{{defaultPageSize}}", Integer.toString(QueryFieldCatalog.DEFAULT_PAGE_SIZE))
                .replace("{{maxPageSize}}", Integer.toString(QueryFieldCatalog.MAX_PAGE_SIZE))
                .replace("{{maxValues}}", Integer.toString(QueryFieldCatalog.MAX_VALUES));
    }

    /** Generated allowlisted filtering, paging, distinct values and aggregation for the query endpoints. */
    private static final String QUERY_SUPPORT_TEMPLATE = """
            package {{package}};

            import jakarta.persistence.EntityManager;
            import jakarta.persistence.criteria.CriteriaBuilder;
            import jakarta.persistence.criteria.CriteriaQuery;
            import jakarta.persistence.criteria.Expression;
            import jakarta.persistence.criteria.Path;
            import jakarta.persistence.criteria.Predicate;
            import jakarta.persistence.criteria.Root;
            import java.math.BigDecimal;
            import java.time.LocalDate;
            import java.time.LocalDateTime;
            import java.util.ArrayList;
            import java.util.LinkedHashMap;
            import java.util.List;
            import java.util.Locale;
            import java.util.Map;
            import java.util.UUID;
            import org.springframework.data.domain.Sort;
            import org.springframework.data.jpa.domain.Specification;

            /**
             * Filters are {@code field:operator:value}; only allowlisted fields of the entity, its to-one reference
             * ids and their scalars one level deep can be queried. {@code ne} also matches missing values, and
             * {@code in} separates values with {@code |}.
             */
            public final class QuerySupport {
                public enum Kind { STRING, INTEGER, LONG, DOUBLE, DECIMAL, BOOLEAN, DATE, DATETIME, UUID, ENUM }

                public record Field(String name, String jpaPath, Kind kind, Class<? extends Enum<?>> enumType) {
                }

                public record Page<T>(List<T> items, long total, int page, int size) {
                }

                public record Bucket(Object group, Object value) {
                }

                public static final int DEFAULT_PAGE_SIZE = {{defaultPageSize}};
                public static final int MAX_PAGE_SIZE = {{maxPageSize}};
                public static final int MAX_VALUES = {{maxValues}};
                private static final List<String> AGGREGATES = List.of("count", "sum", "avg", "min", "max");

                private record Condition(Field field, String operator, String raw) {
                }

                private QuerySupport() {
                }

                public static Map<String, Field> index(Field... fields) {
                    Map<String, Field> index = new LinkedHashMap<>();
                    for (Field field : fields) {
                        index.put(field.name(), field);
                    }
                    return index;
                }

                public static Field field(Map<String, Field> fields, String name) {
                    Field field = name == null ? null : fields.get(name);
                    if (field == null) {
                        throw invalid("Unknown field '" + name + "'. Allowed: " + fields.keySet());
                    }
                    return field;
                }

                public static int page(Integer page) {
                    if (page == null) {
                        return 0;
                    }
                    if (page < 0) {
                        throw invalid("page must be 0 or greater");
                    }
                    return page;
                }

                public static int size(Integer size) {
                    if (size == null) {
                        return DEFAULT_PAGE_SIZE;
                    }
                    if (size < 1 || size > MAX_PAGE_SIZE) {
                        throw invalid("size must be between 1 and " + MAX_PAGE_SIZE);
                    }
                    return size;
                }

                public static Sort sort(Map<String, Field> fields, String sort) {
                    if (sort == null || sort.isBlank()) {
                        return Sort.by("id");
                    }
                    String[] parts = sort.split(",", 2);
                    Field field = field(fields, parts[0].trim());
                    String direction = parts.length == 1 ? "asc" : parts[1].trim().toLowerCase(Locale.ROOT);
                    if (!direction.equals("asc") && !direction.equals("desc")) {
                        throw invalid("sort direction must be asc or desc");
                    }
                    return Sort.by(direction.equals("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, field.jpaPath())
                            .and(Sort.by("id"));
                }

                /** Validates every filter before returning, so a bad filter is a 400 and never reaches the database. */
                public static <T> Specification<T> where(Map<String, Field> fields, List<String> filters) {
                    List<Condition> conditions = new ArrayList<>();
                    for (String filter : filters == null ? List.<String>of() : filters) {
                        conditions.add(parse(fields, filter));
                    }
                    return (root, query, builder) -> builder.and(conditions.stream()
                            .map(condition -> predicate(root, builder, condition)).toArray(Predicate[]::new));
                }

                public static <T> List<Object> distinct(EntityManager entityManager, Class<T> type,
                        Map<String, Field> fields, String name, List<String> filters) {
                    if (name == null || name.isBlank()) {
                        throw invalid("field is required");
                    }
                    Field field = field(fields, name);
                    Specification<T> specification = where(fields, filters);
                    CriteriaBuilder builder = entityManager.getCriteriaBuilder();
                    CriteriaQuery<Object> query = builder.createQuery(Object.class);
                    Root<T> root = query.from(type);
                    Path<?> path = path(root, field.jpaPath());
                    query.select(path).distinct(true)
                            .where(builder.and(specification.toPredicate(root, query, builder), builder.isNotNull(path)))
                            .orderBy(builder.asc(path));
                    return entityManager.createQuery(query).setMaxResults(MAX_VALUES).getResultList();
                }

                @SuppressWarnings({"unchecked", "rawtypes"})
                public static <T> List<Bucket> aggregate(EntityManager entityManager, Class<T> type,
                        Map<String, Field> fields, String op, String name, String groupBy, List<String> filters) {
                    if (op == null || !AGGREGATES.contains(op)) {
                        throw invalid("op must be one of " + AGGREGATES);
                    }
                    Field target = name == null || name.isBlank() ? null : field(fields, name);
                    if (target == null && !op.equals("count")) {
                        throw invalid(op + " needs a field");
                    }
                    if (target != null && (op.equals("sum") || op.equals("avg")) && !numeric(target.kind())) {
                        throw invalid(op + " needs a numeric field, not " + target.name());
                    }
                    if (target != null && (op.equals("min") || op.equals("max")) && !numeric(target.kind())
                            && target.kind() != Kind.DATE && target.kind() != Kind.DATETIME) {
                        throw invalid(op + " needs a numeric or date field, not " + target.name());
                    }
                    Field group = groupBy == null || groupBy.isBlank() ? null : field(fields, groupBy);
                    Specification<T> specification = where(fields, filters);
                    CriteriaBuilder builder = entityManager.getCriteriaBuilder();
                    CriteriaQuery<Object[]> query = builder.createQuery(Object[].class);
                    Root<T> root = query.from(type);
                    Expression<?> value;
                    if (target == null) {
                        value = builder.count(root);
                    } else {
                        Path<?> path = path(root, target.jpaPath());
                        value = switch (op) {
                            case "count" -> builder.count(path);
                            case "sum" -> builder.sum((Expression<Number>) path);
                            case "avg" -> builder.avg((Expression<Number>) path);
                            case "min" -> builder.least((Expression<Comparable>) path);
                            default -> builder.greatest((Expression<Comparable>) path);
                        };
                    }
                    query.where(specification.toPredicate(root, query, builder));
                    List<Bucket> buckets = new ArrayList<>();
                    if (group == null) {
                        query.select(builder.array(value));
                        Object[] row = entityManager.createQuery(query).getSingleResult();
                        buckets.add(new Bucket(null, row[0]));
                        return buckets;
                    }
                    Path<?> groupPath = path(root, group.jpaPath());
                    query.select(builder.array(groupPath, value)).groupBy(groupPath).orderBy(builder.asc(groupPath));
                    for (Object[] row : entityManager.createQuery(query).setMaxResults(MAX_VALUES).getResultList()) {
                        buckets.add(new Bucket(row[0], row[1]));
                    }
                    return buckets;
                }

                private static Condition parse(Map<String, Field> fields, String filter) {
                    String[] parts = filter == null ? new String[0] : filter.split(":", 3);
                    if (parts.length != 3) {
                        throw invalid("filter must be field:operator:value, got '" + filter + "'");
                    }
                    Field field = field(fields, parts[0]);
                    String operator = parts[1];
                    switch (operator) {
                        case "eq", "ne", "in", "is_null" -> {
                        }
                        case "gt", "gte", "lt", "lte" -> {
                            if (field.kind() == Kind.BOOLEAN || field.kind() == Kind.UUID || field.kind() == Kind.ENUM) {
                                throw invalid(operator + " does not apply to " + field.name());
                            }
                        }
                        case "contains" -> {
                            if (field.kind() != Kind.STRING) {
                                throw invalid("contains only applies to text fields, not " + field.name());
                            }
                        }
                        default -> throw invalid("Unknown operator '" + operator
                                + "'. Allowed: eq, ne, gt, gte, lt, lte, contains, in, is_null");
                    }
                    Condition condition = new Condition(field, operator, parts[2]);
                    values(condition);
                    return condition;
                }

                private static List<Object> values(Condition condition) {
                    if (condition.operator().equals("is_null")) {
                        if (!condition.raw().equals("true") && !condition.raw().equals("false")) {
                            throw invalid("is_null takes true or false");
                        }
                        return List.of();
                    }
                    if (condition.operator().equals("contains")) {
                        return List.of(condition.raw());
                    }
                    String[] raws = condition.operator().equals("in")
                            ? condition.raw().split("\\\\|")
                            : new String[] {condition.raw()};
                    List<Object> values = new ArrayList<>();
                    for (String raw : raws) {
                        values.add(convert(condition.field(), raw));
                    }
                    return values;
                }

                @SuppressWarnings({"unchecked", "rawtypes"})
                private static Predicate predicate(Root<?> root, CriteriaBuilder builder, Condition condition) {
                    Path<?> path = path(root, condition.field().jpaPath());
                    List<Object> values = values(condition);
                    Expression<Comparable> comparable = (Expression<Comparable>) path;
                    return switch (condition.operator()) {
                        case "eq" -> builder.equal(path, values.get(0));
                        case "ne" -> builder.or(builder.notEqual(path, values.get(0)), builder.isNull(path));
                        case "gt" -> builder.greaterThan(comparable, (Comparable) values.get(0));
                        case "gte" -> builder.greaterThanOrEqualTo(comparable, (Comparable) values.get(0));
                        case "lt" -> builder.lessThan(comparable, (Comparable) values.get(0));
                        case "lte" -> builder.lessThanOrEqualTo(comparable, (Comparable) values.get(0));
                        case "in" -> path.in(values);
                        case "is_null" -> condition.raw().equals("true") ? builder.isNull(path) : builder.isNotNull(path);
                        default -> builder.like(builder.lower(path.as(String.class)),
                                "%" + escape(condition.raw().toLowerCase(Locale.ROOT)) + "%", '\\\\');
                    };
                }

                private static Path<?> path(Root<?> root, String jpaPath) {
                    Path<?> path = root;
                    for (String part : jpaPath.split("\\\\.")) {
                        path = path.get(part);
                    }
                    return path;
                }

                private static Object convert(Field field, String raw) {
                    try {
                        return switch (field.kind()) {
                            case STRING -> raw;
                            case INTEGER -> Integer.valueOf(raw.trim());
                            case LONG -> Long.valueOf(raw.trim());
                            case DOUBLE -> Double.valueOf(raw.trim());
                            case DECIMAL -> new BigDecimal(raw.trim());
                            case BOOLEAN -> {
                                if (!raw.equals("true") && !raw.equals("false")) {
                                    throw invalid(field.name() + " takes true or false");
                                }
                                yield Boolean.valueOf(raw);
                            }
                            case DATE -> LocalDate.parse(raw.trim());
                            case DATETIME -> LocalDateTime.parse(raw.trim());
                            case UUID -> UUID.fromString(raw.trim());
                            case ENUM -> literal(field, raw.trim());
                        };
                    } catch (RuntimeException exception) {
                        if (exception instanceof GeneratedApiException generated) {
                            throw generated;
                        }
                        throw invalid("'" + raw + "' is not a valid " + field.kind().name().toLowerCase(Locale.ROOT)
                                + " for " + field.name());
                    }
                }

                private static Object literal(Field field, String raw) {
                    List<String> allowed = new ArrayList<>();
                    for (Enum<?> constant : field.enumType().getEnumConstants()) {
                        if (constant.name().equalsIgnoreCase(raw)) {
                            return constant;
                        }
                        allowed.add(constant.name());
                    }
                    throw invalid("'" + raw + "' is not a value of " + field.name() + ". Allowed: " + allowed);
                }

                private static boolean numeric(Kind kind) {
                    return kind == Kind.INTEGER || kind == Kind.LONG || kind == Kind.DOUBLE || kind == Kind.DECIMAL;
                }

                private static String escape(String value) {
                    return value.replace("\\\\", "\\\\\\\\").replace("%", "\\\\%").replace("_", "\\\\_");
                }

                private static GeneratedApiException invalid(String detail) {
                    return GeneratedApiException.invalidQuery(detail);
                }
            }
            """;

    private String apiErrorResponse(TargetNames target) {
        return lines(
                "package " + target.basePackage() + ".api;",
                "",
                "public record ApiErrorResponse(String code, String message, String detail) {",
                "}");
    }

    private String generatedApiException(TargetNames target) {
        String apiPackage = target.basePackage() + ".api";
        return lines(
                "package " + apiPackage + ";",
                "",
                "import org.springframework.http.HttpStatus;",
                "",
                "public final class GeneratedApiException extends RuntimeException {",
                "    private final HttpStatus status;",
                "    private final String code;",
                "    private final String detail;",
                "",
                "    public GeneratedApiException(HttpStatus status, String code, String message, String detail) {",
                "        super(message);",
                "        this.status = status;",
                "        this.code = code;",
                "        this.detail = detail;",
                "    }",
                "",
                "    public HttpStatus status() { return status; }",
                "    public String code() { return code; }",
                "    public String detail() { return detail; }",
                "",
                "    public static GeneratedApiException notFound(String detail) {",
                "        return new GeneratedApiException(HttpStatus.NOT_FOUND, \"NOT_FOUND\",",
                "                \"The requested resource was not found.\", detail);",
                "    }",
                "",
                "    public static GeneratedApiException invalidQuery(String detail) {",
                "        return new GeneratedApiException(HttpStatus.BAD_REQUEST, \"INVALID_QUERY\",",
                "                \"The query uses an unknown field, operator or value.\", detail);",
                "    }",
                "",
                "    public static GeneratedApiException invalidReference(String detail) {",
                "        return new GeneratedApiException(HttpStatus.BAD_REQUEST, \"INVALID_REFERENCE\",",
                "                \"A related resource reference does not exist.\", detail);",
                "    }",
                "}");
    }

    private String generatedApiExceptionHandler(TargetNames target) {
        String apiPackage = target.basePackage() + ".api";
        return lines(
                "package " + apiPackage + ";",
                "",
                "import jakarta.validation.ConstraintViolationException;",
                "import java.util.stream.Collectors;",
                "import org.springframework.dao.DataIntegrityViolationException;",
                "import org.springframework.http.HttpStatus;",
                "import org.springframework.http.ResponseEntity;",
                "import org.springframework.http.converter.HttpMessageNotReadableException;",
                "import org.springframework.web.bind.MethodArgumentNotValidException;",
                "import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;",
                "import org.springframework.web.bind.annotation.ExceptionHandler;",
                "import org.springframework.web.bind.annotation.RestControllerAdvice;",
                "import org.springframework.web.servlet.resource.NoResourceFoundException;",
                "",
                "@RestControllerAdvice",
                "public final class GeneratedApiExceptionHandler {",
                "    @ExceptionHandler(GeneratedApiException.class)",
                "    public ResponseEntity<ApiErrorResponse> handleGenerated(GeneratedApiException exception) {",
                "        return response(exception.status(), exception.code(), exception.getMessage(), exception.detail());",
                "    }",
                "",
                "    @ExceptionHandler(MethodArgumentNotValidException.class)",
                "    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {",
                "        String detail = exception.getBindingResult().getFieldErrors().stream()",
                "                .map(error -> error.getField() + \": \" + String.valueOf(error.getDefaultMessage()))",
                "                .sorted().collect(Collectors.joining(\"; \"));",
                "        return response(HttpStatus.BAD_REQUEST, \"VALIDATION_FAILED\",",
                "                \"Request validation failed.\", detail.isBlank() ? \"Request body is invalid.\" : detail);",
                "    }",
                "",
                "    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,",
                "            ConstraintViolationException.class})",
                "    public ResponseEntity<ApiErrorResponse> handleMalformed(Exception exception) {",
                "        return response(HttpStatus.BAD_REQUEST, \"INVALID_REQUEST\",",
                "                \"Request data is invalid.\", exception.getMessage() == null",
                "                        ? \"Malformed request body or parameter.\" : exception.getMessage());",
                "    }",
                "",
                "    @ExceptionHandler(NoResourceFoundException.class)",
                "    public ResponseEntity<ApiErrorResponse> handleUnknownRoute(NoResourceFoundException exception) {",
                "        return response(HttpStatus.NOT_FOUND, \"NOT_FOUND\", \"The requested route was not found.\",",
                "                \"route=\" + String.valueOf(exception.getResourcePath()));",
                "    }",
                "",
                "    @ExceptionHandler(DataIntegrityViolationException.class)",
                "    public ResponseEntity<ApiErrorResponse> handleConstraint(DataIntegrityViolationException exception) {",
                "        String detail = exception.getMostSpecificCause().getMessage();",
                "        return response(HttpStatus.BAD_REQUEST, \"DATA_CONSTRAINT_VIOLATION\",",
                "                \"The request violates a data constraint.\",",
                "                detail == null ? \"database-constraint\" : detail);",
                "    }",
                "",
                "    @ExceptionHandler(Exception.class)",
                "    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {",
                "        return response(HttpStatus.INTERNAL_SERVER_ERROR, \"INTERNAL_ERROR\",",
                "                \"An unexpected server error occurred.\", \"failureType=\" + exception.getClass().getSimpleName());",
                "    }",
                "",
                "    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message,",
                "            String detail) {",
                "        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message, detail));",
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
                "      <groupId>org.springframework.boot</groupId>",
                "      <artifactId>spring-boot-starter-actuator</artifactId>",
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
                "    locations: classpath:db/migration",
                "management:",
                "  endpoints:",
                "    web:",
                "      exposure:",
                "        include: health");
    }

    private String dockerfile() {
        return lines(
                "FROM " + baseline.runtimeBaseImage(),
                "WORKDIR /app",
                "COPY target/*.jar /app/app.jar",
                "EXPOSE 8080",
                "ENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]");
    }

    /**
     * The destination's own Compose project.
     *
     * The slug names the Compose project, the application service, the database service, and the database
     * volume, so two destinations of the same model are two independent stacks with two independent
     * databases. Host ports stay environment-driven because the destination's port pair belongs to the
     * destination's configuration, and the default pair is the first one of the 1900N/2543N convention.
     *
     * The application is published on every interface and the database only on loopback: a client on the same
     * network needs the first, and nothing outside the machine needs the second.
     */
    private String compose(TargetNames target) {
        String slug = target.slug();
        return lines(
                "name: generated-" + slug,
                "",
                "# One Compose project per destination: its own application service, its own PostgreSQL, and its",
                "# own database volume. No secret is stored in this file.",
                "#",
                "# Port policy: the application is published on every interface, because a client on the same network has",
                "# to reach it. The database is published on the loopback address only -- the application reaches it over",
                "# the Compose network, and nothing outside this machine should be able to open it. Binding it to every",
                "# interface would put the generated database on the network.",
                "",
                "services:",
                "  " + slug + "-db:",
                "    image: " + baseline.postgresImage(),
                "    environment:",
                "      POSTGRES_DB: ${GENERATED_DB_NAME}",
                "      POSTGRES_USER: ${GENERATED_DB_USER}",
                "      POSTGRES_PASSWORD: ${GENERATED_DB_PASSWORD}",
                "    ports:",
                "      - \"127.0.0.1:${GENERATED_POSTGRES_HOST_PORT:-25433}:5432\"",
                "    volumes:",
                "      - " + slug + "-postgres-data:/var/lib/postgresql/data",
                "    healthcheck:",
                "      test: [\"CMD-SHELL\", \"pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}\"]",
                "      interval: 5s",
                "      timeout: 3s",
                "      retries: 12",
                "",
                "  " + slug + ":",
                "    build:",
                "      context: .",
                "      dockerfile: Dockerfile",
                "    environment:",
                "      SPRING_DATASOURCE_URL: jdbc:postgresql://" + slug + "-db:5432/${GENERATED_DB_NAME}",
                "      SPRING_DATASOURCE_USERNAME: ${GENERATED_DB_USER}",
                "      SPRING_DATASOURCE_PASSWORD: ${GENERATED_DB_PASSWORD}",
                "    ports:",
                "      - \"${GENERATED_HTTP_HOST_PORT:-19001}:8080\"",
                "    depends_on:",
                "      " + slug + "-db:",
                "        condition: service_healthy",
                "    healthcheck:",
                "      test: [\"CMD-SHELL\", \"wget -q --spider http://127.0.0.1:8080/actuator/health\"]",
                "      interval: 10s",
                "      timeout: 5s",
                "      retries: 12",
                "      start_period: 30s",
                "",
                "volumes:",
                "  " + slug + "-postgres-data:");
    }

    /** The values a destination must set before it can start. It documents them without carrying a secret. */
    private String envExample(TargetNames target) {
        return lines(
                "# Deployment values for the generated backend `" + target.slug() + "`.",
                "#",
                "# Copy this file to .env in this directory and set every value before running",
                "# `docker compose up -d --build`. Compose reads .env automatically. This example file carries no",
                "# usable secret, and the emitted .gitignore keeps the real .env out of version control.",
                "",
                "# Database created and used by this destination alone.",
                "GENERATED_DB_NAME=generated_db",
                "GENERATED_DB_USER=generated_user",
                "GENERATED_DB_PASSWORD=replace-with-a-local-only-value",
                "",
                "# Host ports for this destination, one pair per destination from the 1900N and 2543N ranges:",
                "# 19001/25433 for the first, 19002/25434 for the second, and so on.",
                "# The application port is published on every interface, so a client on the same network can reach it:",
                "# http://<this-machine>:19001/api/...",
                "# The database port is published on 127.0.0.1 only and is not reachable from the network, on purpose.",
                "GENERATED_HTTP_HOST_PORT=19001",
                "GENERATED_POSTGRES_HOST_PORT=25433");
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
                "## Deploy with Docker Compose",
                "",
                "1. `mvn -o -B package` leaves the runnable jar in `target/`.",
                "2. Copy `.env.example` to `.env` and set every value. The emitted `.gitignore` keeps `.env` out of version control.",
                "3. `docker compose up -d --build` starts this destination and its own PostgreSQL.",
                "4. `curl http://localhost:19001/actuator/health` answers `{\"status\":\"UP\"}` once Flyway applied the migration.",
                "",
                "Each destination is its own Compose project with its own database volume, so two destinations of the same model run at the same time as long as their host port pairs differ. Take the pair from the `1900N` and `2543N` ranges: `19001`/`25433` for the first destination, `19002`/`25434` for the second, and so on.",
                "",
                "## What is reachable from where",
                "",
                "The application port is published on **every interface**, so another device on the same network reaches this backend at `http://<this-machine>:19001`. That is deliberate: it is what lets a client on the same network call this API.",
                "The database port is published on **`127.0.0.1` only**. The application reaches its database over the Compose network, so nothing outside this machine needs the port, and binding it to every interface would put this generated database on the network.",
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
                        + (attribute.source().nullable() ? "" : " NOT NULL")
                        + enumCheck(attribute));
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
                        + (attribute.source().nullable() ? "" : " NOT NULL") + enumCheck(attribute, "value") + "\n);");
            }
        }
        return lines(statements.toArray(String[]::new));
    }

    private static String enumCheck(AttributePlan attribute) {
        return enumCheck(attribute, attribute.columnName());
    }

    private static String enumCheck(AttributePlan attribute, String columnName) {
        if (attribute.enumType() == null) {
            return "";
        }
        return " CHECK (" + quoted(columnName) + " IN ("
                + String.join(", ", attribute.enumType().declaration().literals().stream()
                        .map(literal -> "'" + literal + "'").toList())
                + "))";
    }

    private void appendOperationStubs(StringBuilder source, EntityPlan plan, Map<UUID, EntityPlan> plans,
            Map<UUID, EnumPlan> enumPlans) {
        for (OperationSignature operation : plan.type.operations()) {
            String returnType = operationType(operation.returnType(), plans, enumPlans);
            List<String> parameters = operation.parameters().stream()
                    .map(parameter -> operationType(parameter.type(), plans, enumPlans) + " " + parameter.javaName()).toList();
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

    private String operationType(TypeUse type, Map<UUID, EntityPlan> plans, Map<UUID, EnumPlan> enumPlans) {
        if (!type.resolved()) {
            throw new IllegalArgumentException("Cannot emit an operation with an unresolved type");
        }
        if (type.sourceClassifierId() != null && enumPlans.containsKey(type.sourceClassifierId())) {
            return enumPlans.get(type.sourceClassifierId()).qualifiedName();
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

    private static String enumDefault(FieldDeclaration field, EnumPlan enumType) {
        String value = field.defaultValue();
        if (value == null) {
            return null;
        }
        if (field.collectionIntent() == CollectionIntent.COLLECTION) {
            throw new IllegalArgumentException("Default values for collections are unsupported: " + field.javaName());
        }
        if (!enumType.declaration().literals().contains(value)) {
            throw new IllegalArgumentException("Default value of " + field.javaName() + " is not a literal of "
                    + enumType.declaration().javaName() + ": " + value);
        }
        return enumType.qualifiedName() + "." + value;
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

    private static void appendSetter(StringBuilder source, String type, String fieldName) {
        source.append("\n    public void set").append(capitalize(fieldName)).append('(').append(type).append(' ')
                .append(fieldName).append(") {\n        this.").append(fieldName).append(" = ").append(fieldName)
                .append(";\n    }\n");
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
        if (!path.startsWith("src/") && !Set.of(".env.example", ".gitignore", "Dockerfile", "README.md",
                "docker-compose.yml", "pom.xml").contains(path)) {
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
            EntityPlan referencedType, String javaDefaultValue, String sqlDefaultValue, EnumPlan enumType) {
    }

    private record EnumPlan(EnumDeclaration declaration, String packageName, String qualifiedName) {
    }

    private record OwningRelationshipPlan(RelationshipDescriptor descriptor, EntityPlan referenced) {
    }

    private record InverseRelationshipPlan(RelationshipDescriptor descriptor, EntityPlan owning) {
    }

    private record TargetNames(String slug, String basePackage) {
    }
}
