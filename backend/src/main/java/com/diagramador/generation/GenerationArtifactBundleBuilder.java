package com.diagramador.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.diagramador.domain.GenerationProfile;
import com.diagramador.domain.GenerationRun;
import com.diagramador.domain.GenerationTarget;
import com.diagramador.domain.TemplateVersion;
import com.diagramador.domain.VerificationState;
import com.diagramador.generation.DomainModelIR.EnumDeclaration;
import com.diagramador.generation.DomainModelIR.FieldDeclaration;
import com.diagramador.generation.DomainModelIR.OperationSignature;
import com.diagramador.generation.DomainModelIR.RelationshipDescriptor;
import com.diagramador.generation.DomainModelIR.TypeDeclaration;
import com.diagramador.generation.DomainModelIR.TypeUse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import org.springframework.stereotype.Component;

/** Builds the canonical OpenAPI/provenance artifacts and byte-deterministic ZIP for one frozen generation. */
@Component
public final class GenerationArtifactBundleBuilder {

    public static final String OPENAPI_PATH = "openapi.json";
    public static final String PROVENANCE_PATH = "provenance.json";
    private static final String PROVENANCE_SCHEMA_VERSION = "1.0";
    private static final int ZIP_DOS_TIME = 0;
    private static final int ZIP_DOS_DATE = 33;
    private static final int ZIP_UTF8_FLAG = 0x0800;
    private static final Pattern HOST_PATH = Pattern.compile(
            "(?i)(?:\\b[A-Z]:[\\\\/][^\\s\\\"'<>]*|(?:^|[\\s\\\"'=])/(?:home|Users|tmp|var/tmp|workspace|workspaces|mnt)/[^\\s\\\"'<>]*)");

    private final DomainProjectEmitter emitter;
    private final ObjectMapper objectMapper;

    public GenerationArtifactBundleBuilder(DomainProjectEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public GeneratedArtifactBundle build(GenerationIdentity identity, InputSnapshot snapshot, DomainModelIR ir,
            VerificationState verificationStatus) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(ir, "ir");
        Objects.requireNonNull(verificationStatus, "verificationStatus");
        requireSnapshotMatch(identity, snapshot);
        if (ir.hasBlockingDiagnostics()) {
            throw new IllegalArgumentException("Cannot package a generation with blocking IR diagnostics");
        }
        requireSecretFreeSnapshot(snapshot);

        TreeMap<String, String> files = new TreeMap<>(emitter.emit(identity.targetKey(), ir));
        files.put(OPENAPI_PATH, openApiDocument(ir));
        validateFiles(files);

        List<ArtifactDescriptor> payloadManifest = describe(files);
        String artifactSetHash = sha256(manifestProjection(payloadManifest));
        String provenance = provenanceDocument(identity, ir, payloadManifest, artifactSetHash, verificationStatus);
        requireSafeText(provenance, PROVENANCE_PATH);
        files.put(PROVENANCE_PATH, provenance);

        List<ArtifactDescriptor> completeManifest = describe(files);
        byte[] zipBytes = deterministicZip(files);
        String artifactZipHash = sha256(zipBytes);
        return new GeneratedArtifactBundle(files, payloadManifest, completeManifest, artifactSetHash,
                artifactZipHash, zipBytes, provenance);
    }

    public GenerationIdentity identity(GenerationRun run, InputSnapshot snapshot) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(snapshot, "snapshot");
        GenerationTarget target = run.getTarget();
        GenerationProfile profile = run.getProfile();
        TemplateVersion template = run.getTemplate();
        JsonNode profileConfiguration = profile.getConfiguration();
        requireNonSecretConfiguration(profileConfiguration, "profile.configuration");
        if (!Objects.equals(profile.getConfigurationHash(),
                VersionedGenerationProfileConfiguration.hash(profileConfiguration, objectMapper))) {
            throw new IllegalArgumentException("Generation profile configuration hash does not match its content");
        }
        String subsetVersion = profileConfiguration.path("supportedUmlSubset").path("version").asText();
        if (subsetVersion.isBlank()) {
            throw new IllegalArgumentException("Generation profile does not declare supportedUmlSubset.version");
        }
        return new GenerationIdentity(snapshot.modelId(), snapshot.versionId(), snapshot.revision(),
                snapshot.snapshotHash(), subsetVersion, target.getId(), target.getTargetKey(),
                run.getTargetConfigRevision(), profile.getId(), profile.getProfileKey(), profile.getRevision(),
                profile.getConfigurationHash(), template.getId(), template.getRevision(), template.getContentHash());
    }

    private String openApiDocument(DomainModelIR ir) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("openapi", "3.0.3");
        ObjectNode info = root.putObject("info");
        info.put("title", "Generated Domain API");
        info.put("version", "1.0.0");

        TreeMap<String, TypeDeclaration> declarations = new TreeMap<>();
        ir.types().stream().sorted(Comparator.comparing(TypeDeclaration::javaName)
                .thenComparing(TypeDeclaration::javaPackageName).thenComparing(type -> type.sourceId().toString()))
                .forEach(type -> declarations.put(type.javaName(), type));
        TreeMap<String, ObjectNode> paths = new TreeMap<>();
        for (TypeDeclaration type : declarations.values()) {
            String collectionPath = "/api/" + type.tableName();
            ObjectNode collection = paths.computeIfAbsent(collectionPath, ignored -> objectMapper.createObjectNode());
            ObjectNode list = operation("List " + type.javaName(), "200", type.javaName() + "Dto", true);
            addErrorResponse(list, "500", "Unexpected server error");
            collection.set("get", list);
            collection.set("post", createOperation(type.javaName() + "Dto"));
            List<QueryFieldCatalog.QueryField> queryFields = QueryFieldCatalog.fields(type, ir);
            paths.put(collectionPath + "/query", objectMapper.createObjectNode()
                    .set("get", queryOperation(type.javaName() + "Dto")));
            paths.put(collectionPath + "/values", objectMapper.createObjectNode()
                    .set("get", valuesOperation(type.javaName(), queryFields)));
            paths.put(collectionPath + "/aggregate", objectMapper.createObjectNode()
                    .set("get", aggregateOperation(type.javaName(), queryFields)));

            String itemPath = collectionPath + "/{id}";
            ObjectNode item = paths.computeIfAbsent(itemPath, ignored -> objectMapper.createObjectNode());
            addPathIdParameter(item, "get", "Read " + type.javaName(), type.javaName() + "Dto", false);
            addPathIdParameter(item, "put", "Replace " + type.javaName(), type.javaName() + "Dto", true);
            addDeleteOperation(item, type.javaName());
        }
        ObjectNode pathsNode = root.putObject("paths");
        paths.forEach(pathsNode::set);
        ObjectNode capabilities = root.putObject("x-agent-capabilities");
        capabilities.put("version", 1);
        capabilities.put("filterSyntax", "field:operator:value");
        QueryFieldCatalog.OPERATORS.forEach(capabilities.putArray("operators")::add);
        QueryFieldCatalog.AGGREGATES.forEach(capabilities.putArray("aggregates")::add);
        capabilities.put("defaultPageSize", QueryFieldCatalog.DEFAULT_PAGE_SIZE);
        capabilities.put("maxPageSize", QueryFieldCatalog.MAX_PAGE_SIZE);
        capabilities.put("maxValues", QueryFieldCatalog.MAX_VALUES);

        ObjectNode schemas = objectMapper.createObjectNode();
        declarations.values().forEach(type -> schemas.set(type.javaName() + "Dto", dtoSchema(type, ir)));
        schemas.set("ApiErrorResponse", errorSchema());
        root.putObject("components").set("schemas", schemas);

        try {
            return objectMapper.writeValueAsString(root) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("OpenAPI document cannot be serialized", exception);
        }
    }

    private ObjectNode operation(String summary, String status, String schemaName, boolean collection) {
        ObjectNode operation = objectMapper.createObjectNode();
        operation.put("summary", summary);
        ObjectNode response = operation.putObject("responses").putObject(status);
        response.put("description", "Successful response");
        ObjectNode content = response.putObject("content").putObject("application/json");
        if (collection) {
            ObjectNode schema = content.putObject("schema");
            schema.put("type", "array");
            schema.putObject("items").put("$ref", "#/components/schemas/" + schemaName);
        } else {
            content.putObject("schema").put("$ref", "#/components/schemas/" + schemaName);
        }
        return operation;
    }

    private ObjectNode createOperation(String schemaName) {
        ObjectNode operation = objectMapper.createObjectNode();
        operation.put("summary", "Create " + schemaName.substring(0, schemaName.length() - 3));
        ObjectNode body = operation.putObject("requestBody");
        body.put("required", true);
        body.putObject("content").putObject("application/json").putObject("schema")
                .put("$ref", "#/components/schemas/" + schemaName);
        ObjectNode response = operation.putObject("responses").putObject("201");
        response.put("description", "Created");
        response.putObject("content").putObject("application/json").putObject("schema")
                .put("$ref", "#/components/schemas/" + schemaName);
        addErrorResponse(operation, "400", "Invalid reference or request validation failure");
        addErrorResponse(operation, "500", "Unexpected server error");
        return operation;
    }

    private ObjectNode queryOperation(String schemaName) {
        ObjectNode operation = objectMapper.createObjectNode();
        operation.put("summary", "Query " + schemaName.substring(0, schemaName.length() - 3));
        ArrayNode parameters = operation.putArray("parameters");
        parameters.add(filterParameter());
        parameters.add(queryParameter("sort", stringSchema(), false));
        ObjectNode page = objectMapper.createObjectNode().put("type", "integer").put("minimum", 0);
        parameters.add(queryParameter("page", page, false));
        ObjectNode size = objectMapper.createObjectNode().put("type", "integer").put("minimum", 1)
                .put("maximum", QueryFieldCatalog.MAX_PAGE_SIZE);
        parameters.add(queryParameter("size", size, false));
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode items = properties.putObject("items").put("type", "array");
        items.putObject("items").put("$ref", "#/components/schemas/" + schemaName);
        properties.putObject("total").put("type", "integer").put("format", "int64");
        properties.putObject("page").put("type", "integer");
        properties.putObject("size").put("type", "integer");
        okResponse(operation, schema);
        addErrorResponse(operation, "400", "Unknown field, operator or value in the query");
        addErrorResponse(operation, "500", "Unexpected server error");
        return operation;
    }

    private ObjectNode valuesOperation(String typeName, List<QueryFieldCatalog.QueryField> fields) {
        ObjectNode operation = objectMapper.createObjectNode();
        operation.put("summary", "Distinct values of one " + typeName + " field");
        ArrayNode parameters = operation.putArray("parameters");
        parameters.add(queryParameter("field", fieldNameSchema(fields), true));
        parameters.add(filterParameter());
        ObjectNode schema = objectMapper.createObjectNode().put("type", "array");
        schema.putObject("items");
        okResponse(operation, schema);
        addErrorResponse(operation, "400", "Unknown field, operator or value in the query");
        addErrorResponse(operation, "500", "Unexpected server error");
        return operation;
    }

    private ObjectNode aggregateOperation(String typeName, List<QueryFieldCatalog.QueryField> fields) {
        ObjectNode operation = objectMapper.createObjectNode();
        operation.put("summary", "Aggregate " + typeName + " records, optionally grouped");
        ArrayNode parameters = operation.putArray("parameters");
        ObjectNode op = stringSchema();
        QueryFieldCatalog.AGGREGATES.forEach(op.putArray("enum")::add);
        parameters.add(queryParameter("op", op, true));
        parameters.add(queryParameter("field", fieldNameSchema(fields), false));
        parameters.add(queryParameter("groupBy", fieldNameSchema(fields), false));
        parameters.add(filterParameter());
        ObjectNode schema = objectMapper.createObjectNode().put("type", "array");
        ObjectNode bucket = schema.putObject("items").put("type", "object");
        ObjectNode properties = bucket.putObject("properties");
        properties.putObject("group");
        properties.putObject("value").put("type", "number");
        okResponse(operation, schema);
        addErrorResponse(operation, "400", "Unknown field, operator or value in the query");
        addErrorResponse(operation, "500", "Unexpected server error");
        return operation;
    }

    private ObjectNode filterParameter() {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "array");
        schema.set("items", stringSchema());
        ObjectNode parameter = queryParameter("filter", schema, false);
        parameter.put("style", "form");
        parameter.put("explode", true);
        parameter.put("description", "Repeatable field:operator:value; in separates values with |");
        return parameter;
    }

    private ObjectNode queryParameter(String name, ObjectNode schema, boolean required) {
        ObjectNode parameter = objectMapper.createObjectNode();
        parameter.put("name", name);
        parameter.put("in", "query");
        parameter.put("required", required);
        parameter.set("schema", schema);
        return parameter;
    }

    private ObjectNode fieldNameSchema(List<QueryFieldCatalog.QueryField> fields) {
        ObjectNode schema = stringSchema();
        ArrayNode names = schema.putArray("enum");
        fields.forEach(field -> names.add(field.name()));
        return schema;
    }

    private ObjectNode stringSchema() {
        return objectMapper.createObjectNode().put("type", "string");
    }

    private void okResponse(ObjectNode operation, ObjectNode schema) {
        ObjectNode response = operation.putObject("responses").putObject("200");
        response.put("description", "Successful response");
        response.putObject("content").putObject("application/json").set("schema", schema);
    }

    private void addPathIdParameter(ObjectNode path, String method, String summary, String schemaName,
            boolean hasRequestBody) {
        ObjectNode operation = operation(summary, "200", schemaName, false);
        ObjectNode parameter = objectMapper.createObjectNode();
        parameter.put("name", "id");
        parameter.put("in", "path");
        parameter.put("required", true);
        ObjectNode parameterSchema = parameter.putObject("schema");
        parameterSchema.put("type", "string");
        parameterSchema.put("format", "uuid");
        operation.putArray("parameters").add(parameter);
        if (hasRequestBody) {
            ObjectNode body = operation.putObject("requestBody");
            body.put("required", true);
            body.putObject("content").putObject("application/json").putObject("schema")
                    .put("$ref", "#/components/schemas/" + schemaName);
        }
        addErrorResponse(operation, "400", hasRequestBody
                ? "Invalid reference or request validation failure"
                : "Invalid request parameter");
        addErrorResponse(operation, "404", "Resource not found");
        addErrorResponse(operation, "500", "Unexpected server error");
        path.set(method, operation);
    }

    private void addDeleteOperation(ObjectNode path, String typeName) {
        ObjectNode operation = objectMapper.createObjectNode();
        operation.put("summary", "Delete " + typeName);
        ObjectNode parameter = objectMapper.createObjectNode();
        parameter.put("name", "id");
        parameter.put("in", "path");
        parameter.put("required", true);
        ObjectNode schema = parameter.putObject("schema");
        schema.put("type", "string");
        schema.put("format", "uuid");
        operation.putArray("parameters").add(parameter);
        operation.putObject("responses").putObject("204").put("description", "Deleted");
        addErrorResponse(operation, "400", "Invalid request parameter");
        addErrorResponse(operation, "404", "Resource not found");
        addErrorResponse(operation, "500", "Unexpected server error");
        path.set("delete", operation);
    }

    private void addErrorResponse(ObjectNode operation, String status, String description) {
        ObjectNode response = operation.path("responses") instanceof ObjectNode responses
                ? responses
                : operation.putObject("responses");
        ObjectNode error = response.putObject(status);
        error.put("description", description);
        error.putObject("content").putObject("application/json").putObject("schema")
                .put("$ref", "#/components/schemas/ApiErrorResponse");
    }

    private ObjectNode dtoSchema(TypeDeclaration type, DomainModelIR ir) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode id = properties.putObject("id");
        id.put("type", "string");
        id.put("format", "uuid");
        ArrayNode required = schema.putArray("required");
        for (FieldDeclaration field : type.fields()) {
            String name = field.javaName();
            boolean collection = field.collectionIntent() == DomainModelIR.CollectionIntent.COLLECTION;
            EnumDeclaration enumeration = ir.enums().stream()
                    .filter(candidate -> candidate.sourceId().equals(field.type().sourceClassifierId()))
                    .findFirst().orElse(null);
            boolean entityReference = field.type().sourceClassifierId() != null && enumeration == null;
            if (entityReference) {
                name += "Id";
            }
            ObjectNode property = properties.putObject(name);
            ObjectNode valueSchema = entityReference
                    ? uuidSchema()
                    : enumeration != null ? enumSchema(enumeration, collection)
                    : typeSchema(field.type(), collection);
            property.setAll(valueSchema);
            if (field.nullable()) {
                property.put("nullable", true);
            } else {
                required.add(name);
            }
        }
        for (RelationshipDescriptor relationship : ir.relationships()) {
            if (relationship.owningEnd().classifierSourceId().equals(type.sourceId())) {
                String name = relationship.owningFieldName() + "Id";
                ObjectNode property = uuidSchema();
                properties.set(name, property);
                if (!relationship.owningFieldNullable()) {
                    required.add(name);
                } else {
                    property.put("nullable", true);
                }
            }
        }
        ArrayNode displayFields = schema.putArray("x-display-fields");
        displayFields(type).forEach(displayFields::add);
        ArrayNode queryFields = schema.putArray("x-query-fields");
        for (QueryFieldCatalog.QueryField field : QueryFieldCatalog.fields(type, ir)) {
            ObjectNode item = queryFields.addObject();
            item.put("name", field.name());
            item.put("kind", field.kind().name());
            if (field.enumeration() != null) {
                field.enumeration().literals().forEach(item.putArray("enum")::add);
            }
        }
        schema.set("x-uml-operations", operationsExtension(type));
        schema.set("x-uml-relationships", relationshipsExtension(type, ir));
        return schema;
    }

    private static final List<String> LABEL_NAMES = List.of("nombre", "name", "titulo", "title", "descripcion",
            "description");
    private static final List<String> SURNAME_NAMES = List.of("apellido", "apellidos", "lastname", "surname");

    /**
     * Fields a client shows instead of the id: a conventional label name (plus a surname next to a first name),
     * else the first required single-valued String. Empty when the entity has no readable text field.
     */
    static List<String> displayFields(TypeDeclaration type) {
        List<FieldDeclaration> texts = type.fields().stream()
                .filter(field -> field.collectionIntent() != DomainModelIR.CollectionIntent.COLLECTION)
                .filter(field -> field.type().sourceClassifierId() == null)
                .filter(field -> "String".equals(field.type().javaTypeName())
                        || "java.lang.String".equals(field.type().javaTypeName()))
                .toList();
        for (String label : LABEL_NAMES) {
            for (FieldDeclaration field : texts) {
                if (field.javaName().toLowerCase(Locale.ROOT).equals(label)) {
                    if (!label.equals("nombre") && !label.equals("name")) {
                        return List.of(field.javaName());
                    }
                    return texts.stream().map(FieldDeclaration::javaName)
                            .filter(name -> SURNAME_NAMES.contains(name.toLowerCase(Locale.ROOT)))
                            .findFirst().map(surname -> List.of(field.javaName(), surname))
                            .orElse(List.of(field.javaName()));
                }
            }
        }
        return texts.stream().filter(field -> !field.nullable()).findFirst()
                .map(field -> List.of(field.javaName())).orElse(List.of());
    }

    private ObjectNode enumSchema(EnumDeclaration enumeration, boolean collection) {
        ObjectNode literal = objectMapper.createObjectNode();
        literal.put("type", "string");
        ArrayNode values = literal.putArray("enum");
        enumeration.literals().forEach(values::add);
        literal.put("x-enum-name", enumeration.javaName());
        if (!collection) {
            return literal;
        }
        ObjectNode array = objectMapper.createObjectNode();
        array.put("type", "array");
        array.set("items", literal);
        return array;
    }

    private ObjectNode uuidSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "string");
        schema.put("format", "uuid");
        return schema;
    }

    private ObjectNode typeSchema(TypeUse type, boolean collection) {
        ObjectNode value = objectMapper.createObjectNode();
        String javaType = type.javaTypeName();
        if (collection) {
            value.put("type", "array");
            value.set("items", scalarSchema(javaType));
        } else {
            value.setAll(scalarSchema(javaType));
        }
        return value;
    }

    private ObjectNode scalarSchema(String javaType) {
        ObjectNode schema = objectMapper.createObjectNode();
        switch (javaType) {
            case "String", "java.lang.String" -> schema.put("type", "string");
            case "boolean", "Boolean", "java.lang.Boolean" -> schema.put("type", "boolean");
            case "byte", "short", "int", "Integer", "java.lang.Integer" -> schema.put("type", "integer");
            case "long", "Long", "java.lang.Long" -> {
                schema.put("type", "integer");
                schema.put("format", "int64");
            }
            case "float", "Float", "double", "Double", "java.lang.Float", "java.lang.Double",
                    "java.math.BigDecimal" -> {
                schema.put("type", "number");
                schema.put("format", javaType.contains("Float") || javaType.equals("float") ? "float" : "double");
            }
            case "char", "Character", "java.lang.Character" -> schema.put("type", "string");
            case "java.time.LocalDate" -> {
                schema.put("type", "string");
                schema.put("format", "date");
            }
            case "java.time.LocalDateTime" -> {
                schema.put("type", "string");
                schema.put("format", "date-time");
            }
            case "java.util.UUID" -> {
                schema.put("type", "string");
                schema.put("format", "uuid");
            }
            default -> schema.put("type", "string");
        }
        return schema;
    }

    private ArrayNode operationsExtension(TypeDeclaration type) {
        ArrayNode operations = objectMapper.createArrayNode();
        for (OperationSignature operation : type.operations()) {
            ObjectNode item = operations.addObject();
            item.put("name", operation.javaName());
            item.put("visibility", operation.visibility());
            item.put("abstract", operation.isAbstract());
            item.put("static", operation.isStatic());
            item.put("returnType", typeName(operation.returnType()));
            ArrayNode parameters = item.putArray("parameters");
            operation.parameters().forEach(parameter -> {
                ObjectNode value = parameters.addObject();
                value.put("name", parameter.javaName());
                value.put("direction", parameter.direction());
                value.put("type", typeName(parameter.type()));
            });
        }
        return operations;
    }

    private ArrayNode relationshipsExtension(TypeDeclaration type, DomainModelIR ir) {
        ArrayNode relationships = objectMapper.createArrayNode();
        for (RelationshipDescriptor relationship : ir.relationships()) {
            boolean owns = relationship.owningEnd().classifierSourceId().equals(type.sourceId());
            boolean referenced = relationship.referencedEnd().classifierSourceId().equals(type.sourceId());
            if (!owns && !referenced) {
                continue;
            }
            ObjectNode item = relationships.addObject();
            item.put("name", relationship.name() == null ? "" : relationship.name());
            item.put("kind", relationship.kind().name());
            item.put("owning", owns);
            item.put("field", owns ? relationship.owningFieldName()
                    : relationship.inverseCollectionEmitted() ? relationship.inverseCollectionFieldName() : "");
            item.put("otherType", owns ? relationship.referencedEnd().classifierName()
                    : relationship.owningEnd().classifierName());
            item.put("fieldNullable", owns && relationship.owningFieldNullable());
            item.put("collectionEmitted", owns ? false : relationship.inverseCollectionEmitted());
            item.put("owningMultiplicity", relationship.owningEnd().multiplicity().name());
            item.put("referencedMultiplicity", relationship.referencedEnd().multiplicity().name());
            item.put("owningNavigable", relationship.owningEnd().navigable());
            item.put("referencedNavigable", relationship.referencedEnd().navigable());
            item.put("owningAggregation", relationship.owningEnd().aggregationKind());
            item.put("referencedAggregation", relationship.referencedEnd().aggregationKind());
            item.put("lifecycle", relationship.lifecycleIntent().name());
        }
        return relationships;
    }

    private String typeName(TypeUse type) {
        return type.javaTypeName() == null ? "void" : type.javaTypeName();
    }

    private ObjectNode errorSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("code").put("type", "string");
        properties.putObject("message").put("type", "string");
        properties.putObject("detail").put("type", "string");
        schema.putArray("required").add("code").add("message").add("detail");
        return schema;
    }

    private String provenanceDocument(GenerationIdentity identity, DomainModelIR ir,
            List<ArtifactDescriptor> payloadManifest, String artifactSetHash, VerificationState verificationStatus) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", PROVENANCE_SCHEMA_VERSION);

        ObjectNode snapshot = root.putObject("snapshot");
        snapshot.put("modelId", identity.modelId().toString());
        snapshot.put("versionId", identity.versionId().toString());
        snapshot.put("revision", identity.revision());
        snapshot.put("snapshotHash", identity.snapshotHash());
        snapshot.put("subsetVersion", identity.subsetVersion());

        ObjectNode target = root.putObject("target");
        target.put("targetId", identity.targetId().toString());
        target.put("targetKey", identity.targetKey());
        target.put("configurationRevision", identity.targetConfigurationRevision());

        ObjectNode profile = root.putObject("profile");
        profile.put("profileId", identity.profileId().toString());
        profile.put("profileKey", identity.profileKey());
        profile.put("revision", identity.profileRevision());
        profile.put("configurationHash", identity.profileConfigurationHash());

        ObjectNode template = root.putObject("template");
        template.put("templateId", identity.templateId().toString());
        template.put("revision", identity.templateRevision());
        template.put("contentHash", identity.templateContentHash());

        ArrayNode diagnostics = root.putArray("diagnostics");
        ir.diagnostics().forEach(diagnostic -> {
            ObjectNode item = diagnostics.addObject();
            item.put("code", diagnostic.code());
            item.put("severity", diagnostic.severity().name());
            if (diagnostic.elementId() == null) {
                item.putNull("elementId");
            } else {
                item.put("elementId", diagnostic.elementId().toString());
            }
            item.put("feature", diagnostic.feature());
            item.put("message", diagnostic.message());
            item.put("blocking", diagnostic.blocking());
        });

        root.put("artifactSetHash", artifactSetHash);
        root.put("manifestScope", "All emitted artifacts except provenance.json (self-reference excluded)");
        ArrayNode artifacts = root.putArray("artifacts");
        payloadManifest.forEach(artifact -> {
            ObjectNode item = artifacts.addObject();
            item.put("path", artifact.path());
            item.put("kind", artifact.kind());
            item.put("contentHash", artifact.contentHash());
            item.put("sizeBytes", artifact.sizeBytes());
        });
        root.put("verificationStatus", verificationStatus.name());
        try {
            return objectMapper.writeValueAsString(root) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Generation provenance cannot be serialized", exception);
        }
    }

    private List<ArtifactDescriptor> describe(Map<String, String> files) {
        return files.entrySet().stream().map(entry -> {
            byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
            return new ArtifactDescriptor(entry.getKey(), artifactKind(entry.getKey()), sha256(content), content.length);
        }).toList();
    }

    private String manifestProjection(List<ArtifactDescriptor> manifest) {
        return String.join("\n", manifest.stream()
                .map(artifact -> artifact.path() + "|" + artifact.contentHash()).toList());
    }

    private byte[] deterministicZip(Map<String, String> files) {
        if (files.size() > 0xffff) {
            throw new IllegalArgumentException("Artifact ZIP has too many entries");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<ZipCentralEntry> directory = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            byte[] name = file.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);
            if (name.length > 0xffff || content.length > 0xffffffffL || bytes.size() > 0xffffffffL) {
                throw new IllegalArgumentException("Artifact ZIP entry exceeds the non-ZIP64 format limits");
            }
            CRC32 crc = new CRC32();
            crc.update(content);
            long offset = bytes.size();
            writeLeInt(bytes, 0x04034b50L);
            writeLeShort(bytes, 20);
            writeLeShort(bytes, ZIP_UTF8_FLAG);
            writeLeShort(bytes, 0);
            writeLeShort(bytes, ZIP_DOS_TIME);
            writeLeShort(bytes, ZIP_DOS_DATE);
            writeLeInt(bytes, crc.getValue());
            writeLeInt(bytes, content.length);
            writeLeInt(bytes, content.length);
            writeLeShort(bytes, name.length);
            writeLeShort(bytes, 0);
            bytes.writeBytes(name);
            bytes.writeBytes(content);
            directory.add(new ZipCentralEntry(name, crc.getValue(), content.length, offset));
        }

        long directoryOffset = bytes.size();
        for (ZipCentralEntry entry : directory) {
            writeLeInt(bytes, 0x02014b50L);
            writeLeShort(bytes, 20);
            writeLeShort(bytes, 20);
            writeLeShort(bytes, ZIP_UTF8_FLAG);
            writeLeShort(bytes, 0);
            writeLeShort(bytes, ZIP_DOS_TIME);
            writeLeShort(bytes, ZIP_DOS_DATE);
            writeLeInt(bytes, entry.crc());
            writeLeInt(bytes, entry.size());
            writeLeInt(bytes, entry.size());
            writeLeShort(bytes, entry.name().length);
            writeLeShort(bytes, 0);
            writeLeShort(bytes, 0);
            writeLeShort(bytes, 0);
            writeLeShort(bytes, 0);
            writeLeInt(bytes, 0);
            writeLeInt(bytes, entry.offset());
            bytes.writeBytes(entry.name());
        }
        long directorySize = bytes.size() - directoryOffset;
        if (directoryOffset > 0xffffffffL || directorySize > 0xffffffffL) {
            throw new IllegalArgumentException("Artifact ZIP exceeds the non-ZIP64 format limits");
        }
        writeLeInt(bytes, 0x06054b50L);
        writeLeShort(bytes, 0);
        writeLeShort(bytes, 0);
        writeLeShort(bytes, directory.size());
        writeLeShort(bytes, directory.size());
        writeLeInt(bytes, directorySize);
        writeLeInt(bytes, directoryOffset);
        writeLeShort(bytes, 0);
        return bytes.toByteArray();
    }

    private static void writeLeShort(ByteArrayOutputStream output, long value) {
        output.write((int) (value & 0xff));
        output.write((int) ((value >>> 8) & 0xff));
    }

    private static void writeLeInt(ByteArrayOutputStream output, long value) {
        writeLeShort(output, value);
        writeLeShort(output, value >>> 16);
    }

    private record ZipCentralEntry(byte[] name, long crc, long size, long offset) {
    }

    private void validateFiles(Map<String, String> files) {
        files.forEach((path, content) -> {
            if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains("../")
                    || path.equals("..") || !content.endsWith("\n") || content.contains("\r")) {
                throw new IllegalStateException("Generated artifact is not canonical: " + path);
            }
            requireSafeText(content, path);
        });
    }

    private void requireSafeText(String value, String location) {
        if (looksCredentialLikeArtifact(value)) {
            throw new IllegalArgumentException("Credential-shaped value is not allowed in generated artifact " + location);
        }
        if (HOST_PATH.matcher(value).find()) {
            throw new IllegalArgumentException("Host path is not allowed in generated artifact " + location);
        }
    }

    private void requireSecretFreeSnapshot(InputSnapshot snapshot) {
        for (InputSnapshot.AttributeElement attribute : snapshot.attributes()) {
            if (ConnectionConfigRefValidator.looksCredentialLike(attribute.defaultValue())) {
                throw new IllegalArgumentException("Credential-shaped value is not allowed in the frozen input snapshot");
            }
            if (attribute.defaultValue() != null
                    && ConnectionConfigRefValidator.hasSecretFieldName(attribute.name())) {
                throw new IllegalArgumentException("Secret-bearing default is not allowed in the frozen input snapshot");
            }
        }
        requireSafeText(snapshot.canonicalProjection(), "input snapshot");
    }

    private static boolean looksCredentialLikeArtifact(String value) {
        String withoutEnvironmentPlaceholders = value
                .replaceAll("\\$\\{[A-Za-z_][A-Za-z0-9_]*}", "<ENV_PLACEHOLDER>")
                .replaceAll("\\{\\{[A-Za-z_][A-Za-z0-9_]*}}", "<ENV_PLACEHOLDER>");
        return ConnectionConfigRefValidator.looksCredentialLike(withoutEnvironmentPlaceholders);
    }

    private static void requireNonSecretConfiguration(JsonNode value, String path) {
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isObject()) {
            value.fields().forEachRemaining(entry -> {
                String childPath = path + "." + entry.getKey();
                if (ConnectionConfigRefValidator.hasSecretFieldName(entry.getKey())) {
                    throw new IllegalArgumentException("Secret-bearing field is not allowed in " + childPath);
                }
                requireNonSecretConfiguration(entry.getValue(), childPath);
            });
        } else if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                requireNonSecretConfiguration(value.get(index), path + "[" + index + "]");
            }
        } else if (value.isTextual() && ConnectionConfigRefValidator.looksCredentialLike(value.asText())) {
            throw new IllegalArgumentException("Credential-shaped value is not allowed in " + path);
        }
    }

    private static void requireSnapshotMatch(GenerationIdentity identity, InputSnapshot snapshot) {
        if (!identity.modelId().equals(snapshot.modelId()) || !identity.versionId().equals(snapshot.versionId())
                || identity.revision() != snapshot.revision() || !identity.snapshotHash().equals(snapshot.snapshotHash())) {
            throw new IllegalArgumentException("Generation identity does not match the frozen input snapshot");
        }
    }

    private static String artifactKind(String path) {
        if (path.equals(PROVENANCE_PATH)) {
            return "provenance";
        }
        if (path.equals(OPENAPI_PATH)) {
            return "openapi";
        }
        if (path.endsWith("Dto.java")) {
            return "dto";
        }
        if (path.contains("/api/") && path.endsWith("Controller.java")) {
            return "controller";
        }
        if (path.contains("/repository/") && path.endsWith("Repository.java")) {
            return "repository";
        }
        if (path.contains("/service/") && path.endsWith("Service.java")) {
            return "service";
        }
        if (path.contains("/domain/") && path.endsWith(".java")) {
            return "entity";
        }
        if (path.contains("/db/migration/")) {
            return "migration";
        }
        if (path.equals("README.md")) {
            return "documentation";
        }
        if (path.equals(".gitignore") || path.equals("Dockerfile") || path.equals("pom.xml")
                || path.endsWith(".yml")) {
            return "configuration";
        }
        return "supporting";
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(64);
            for (byte digestByte : digest) {
                hex.append(String.format("%02x", digestByte));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record GenerationIdentity(UUID modelId, UUID versionId, long revision, String snapshotHash,
            String subsetVersion, UUID targetId, String targetKey, int targetConfigurationRevision,
            UUID profileId, String profileKey, int profileRevision, String profileConfigurationHash,
            UUID templateId, int templateRevision, String templateContentHash) {

        public GenerationIdentity {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(versionId, "versionId");
            requireHash(snapshotHash, "snapshotHash");
            requireText(subsetVersion, "subsetVersion");
            Objects.requireNonNull(targetId, "targetId");
            requireText(targetKey, "targetKey");
            Objects.requireNonNull(profileId, "profileId");
            requireText(profileKey, "profileKey");
            requireHash(profileConfigurationHash, "profileConfigurationHash");
            Objects.requireNonNull(templateId, "templateId");
            requireHash(templateContentHash, "templateContentHash");
            if (revision <= 0 || targetConfigurationRevision <= 0 || profileRevision <= 0 || templateRevision <= 0) {
                throw new IllegalArgumentException("Generation identity revisions must be positive");
            }
            if (ConnectionConfigRefValidator.looksCredentialLike(targetKey)
                    || ConnectionConfigRefValidator.looksCredentialLike(profileKey)
                    || ConnectionConfigRefValidator.looksCredentialLike(subsetVersion)) {
                throw new IllegalArgumentException("Credential-shaped value is not allowed in generation identity");
            }
        }

        private static void requireHash(String value, String field) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(field + " must be 64 lowercase hexadecimal characters");
            }
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }

    public record ArtifactDescriptor(String path, String kind, String contentHash, long sizeBytes) {
    }

    public static final class GeneratedArtifactBundle {
        private final Map<String, String> files;
        private final List<ArtifactDescriptor> payloadManifest;
        private final List<ArtifactDescriptor> completeManifest;
        private final String artifactSetHash;
        private final String artifactZipHash;
        private final byte[] zipBytes;
        private final String provenanceJson;

        private GeneratedArtifactBundle(Map<String, String> files, List<ArtifactDescriptor> payloadManifest,
                List<ArtifactDescriptor> completeManifest, String artifactSetHash, String artifactZipHash,
                byte[] zipBytes, String provenanceJson) {
            this.files = java.util.Collections.unmodifiableMap(new TreeMap<>(files));
            this.payloadManifest = List.copyOf(payloadManifest);
            this.completeManifest = List.copyOf(completeManifest);
            this.artifactSetHash = artifactSetHash;
            this.artifactZipHash = artifactZipHash;
            this.zipBytes = zipBytes.clone();
            this.provenanceJson = provenanceJson;
        }

        public Map<String, String> files() {
            return files;
        }

        public List<ArtifactDescriptor> payloadManifest() {
            return payloadManifest;
        }

        public List<ArtifactDescriptor> completeManifest() {
            return completeManifest;
        }

        public String artifactSetHash() {
            return artifactSetHash;
        }

        public String artifactZipHash() {
            return artifactZipHash;
        }

        public byte[] zipBytes() {
            return zipBytes.clone();
        }

        public String provenanceJson() {
            return provenanceJson;
        }
    }
}
