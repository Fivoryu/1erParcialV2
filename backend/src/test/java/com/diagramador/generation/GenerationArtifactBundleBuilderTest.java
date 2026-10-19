package com.diagramador.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.diagramador.command.ValidationState;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.SeverityKind;
import com.diagramador.generation.DomainModelIR.CollectionIntent;
import com.diagramador.generation.DomainModelIR.FieldDeclaration;
import com.diagramador.generation.DomainModelIR.LifecycleIntent;
import com.diagramador.generation.DomainModelIR.MultiplicityBounds;
import com.diagramador.generation.DomainModelIR.OperationSignature;
import com.diagramador.generation.DomainModelIR.ParameterDeclaration;
import com.diagramador.generation.DomainModelIR.RelationshipDescriptor;
import com.diagramador.generation.DomainModelIR.RelationshipEndDescriptor;
import com.diagramador.generation.DomainModelIR.TypeDeclaration;
import com.diagramador.generation.DomainModelIR.TypeUse;
import com.diagramador.generation.GenerationArtifactBundleBuilder.ArtifactDescriptor;
import com.diagramador.generation.GenerationArtifactBundleBuilder.GeneratedArtifactBundle;
import com.diagramador.generation.GenerationArtifactBundleBuilder.GenerationIdentity;
import com.diagramador.domain.VerificationState;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class GenerationArtifactBundleBuilderTest {

    private static final UUID CUSTOMER_ID = id(1);
    private static final UUID ORDER_ID = id(2);
    private static final UUID SNAPSHOT_MODEL_ID = id(100);
    private static final UUID SNAPSHOT_VERSION_ID = id(101);
    private static final UUID TARGET_ID = id(201);
    private static final UUID PROFILE_ID = id(202);
    private static final UUID TEMPLATE_ID = id(203);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HOST_PATH = Pattern.compile(
            "(?i)(?:\\b[A-Z]:[\\\\/][^\\s\\\"'<>]*|(?:^|[\\s\\\"'=])/(?:home|Users|tmp|var/tmp|workspace|workspaces|mnt)/[^\\s\\\"'<>]*)");
    private final GenerationArtifactBundleBuilder builder = new GenerationArtifactBundleBuilder(
            new DomainProjectEmitter(), JSON);

    @Test
    void repeatBuildsHaveTheSameHashesAndByteIdenticalZipWithCanonicalMetadata() throws Exception {
        InputSnapshot snapshot = snapshot("Guest");
        DomainModelIR ir = ir("displayName", "Guest", false);
        GenerationIdentity identity = identity("course-store", snapshot, 1);

        GeneratedArtifactBundle first = builder.build(identity, snapshot, ir, VerificationState.NOT_RUN);
        GeneratedArtifactBundle second = builder.build(identity, snapshot, ir, VerificationState.NOT_RUN);

        assertThat(first.artifactSetHash()).isEqualTo(second.artifactSetHash());
        assertThat(first.artifactZipHash()).isEqualTo(second.artifactZipHash());
        assertThat(first.zipBytes()).containsExactly(second.zipBytes());
        assertThat(first.zipBytes()).hasSize(second.zipBytes().length);
        assertThat(first.artifactZipHash()).isEqualTo(sha256(first.zipBytes()));
        String manifestProjection = String.join("\n", first.payloadManifest().stream()
                .map(artifact -> artifact.path() + "|" + artifact.contentHash()).toList());
        assertThat(first.artifactSetHash()).isEqualTo(sha256(manifestProjection.getBytes(StandardCharsets.UTF_8)));
        for (ArtifactDescriptor artifact : first.completeManifest()) {
            byte[] content = first.files().get(artifact.path()).getBytes(StandardCharsets.UTF_8);
            assertThat(artifact.contentHash()).isEqualTo(sha256(content));
            assertThat(artifact.sizeBytes()).isEqualTo(content.length);
        }
        List<String> entryNames = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(first.zipBytes()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryNames.add(entry.getName());
                assertThat(entry.getTimeLocal()).isEqualTo(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
                assertThat(entry.getMethod()).isEqualTo(ZipEntry.STORED);
                assertThat(entry.getExtra()).isNullOrEmpty();
                assertThat(entry.getComment()).isNull();
            }
        }
        assertThat(entryNames).containsExactlyElementsOf(first.files().keySet());
        System.out.printf("T7_REPEAT artifactSetHash1=%s artifactSetHash2=%s artifactZipHash1=%s "
                        + "artifactZipHash2=%s byteIdentical=%s zipSize1=%d zipSize2=%d%n",
                first.artifactSetHash(), second.artifactSetHash(), first.artifactZipHash(), second.artifactZipHash(),
                java.util.Arrays.equals(first.zipBytes(), second.zipBytes()), first.zipBytes().length,
                second.zipBytes().length);
        System.out.println("T7_PATHS " + String.join(", ", first.files().keySet()));
    }

    @Test
    void semanticChangeChangesSetHashAndIdentifiesChangedManifestPaths() {
        InputSnapshot originalSnapshot = snapshot("Guest");
        InputSnapshot changedSnapshot = snapshot("Visitor");
        DomainModelIR originalIr = ir("displayName", "Guest", false);
        DomainModelIR changedIr = ir("displayName", "Visitor", false);
        assertThat(originalSnapshot.attributes().getFirst().defaultValue())
                .isNotEqualTo(changedSnapshot.attributes().getFirst().defaultValue());
        assertThat(originalSnapshot.snapshotHash()).isNotEqualTo(changedSnapshot.snapshotHash());
        assertThat(originalIr.types().getFirst().fields().getFirst().defaultValue())
                .isNotEqualTo(changedIr.types().getFirst().fields().getFirst().defaultValue());

        GeneratedArtifactBundle original = builder.build(identity("course-store", originalSnapshot, 1),
                originalSnapshot, originalIr, VerificationState.NOT_RUN);
        GeneratedArtifactBundle changed = builder.build(identity("course-store", changedSnapshot, 1),
                changedSnapshot, changedIr, VerificationState.NOT_RUN);

        assertThat(original.artifactSetHash()).isNotEqualTo(changed.artifactSetHash());
        Map<String, String> originalHashes = hashesByPath(original.payloadManifest());
        Map<String, String> changedHashes = hashesByPath(changed.payloadManifest());
        List<String> changedPaths = originalHashes.keySet().stream()
                .filter(path -> !originalHashes.get(path).equals(changedHashes.get(path))).toList();
        assertThat(changedPaths).contains(
                "src/main/java/com/generated/course/store/domain/Customer.java",
                "src/main/resources/db/migration/V1__init.sql");
        assertThat(changed.payloadManifest()).extracting(ArtifactDescriptor::path)
                .contains("src/main/java/com/generated/course/store/domain/Customer.java");
    }

    @Test
    void openApiDocumentsAllFiveGeneratedCrudRoutesAndUniformErrorSchemas() throws Exception {
        InputSnapshot snapshot = snapshot("Guest");
        GeneratedArtifactBundle bundle = builder.build(identity("course-store", snapshot, 1), snapshot,
                ir("displayName", "Guest", false), VerificationState.NOT_RUN);
        String document = bundle.files().get(GenerationArtifactBundleBuilder.OPENAPI_PATH);
        JsonNode openApi = JSON.readTree(document);

        assertThat(openApi.path("openapi").asText()).isEqualTo("3.0.3");
        assertThat(fieldNames(openApi.path("paths")))
                .containsExactly("/api/customer", "/api/customer/{id}", "/api/order", "/api/order/{id}");
        assertCrudOperations(openApi, "customer");
        assertCrudOperations(openApi, "order");
        assertThat(openApi.at("/paths/~1api~1customer/post/responses/201/content/application~1json/schema/$ref")
                .asText()).isEqualTo("#/components/schemas/CustomerDto");
        assertThat(openApi.at("/paths/~1api~1order/post/responses/400/content/application~1json/schema/$ref")
                .asText()).isEqualTo("#/components/schemas/ApiErrorResponse");
        assertThat(openApi.at("/paths/~1api~1order~1{id}/get/responses/404/content/application~1json/schema/$ref")
                .asText()).isEqualTo("#/components/schemas/ApiErrorResponse");
        assertThat(fieldNames(openApi.at("/components/schemas/ApiErrorResponse/properties")))
                .containsExactly("code", "message", "detail");
        assertThat(openApi.at("/components/schemas/OrderDto/properties/customerId/format").asText())
                .isEqualTo("uuid");
        assertThat(openApi.at("/components/schemas/CustomerDto/x-uml-operations/0/name").asText())
                .isEqualTo("validate");
        assertThat(openApi.at("/components/schemas/CustomerDto/x-uml-operations/0/parameters/0/name").asText())
                .isEqualTo("query");
        assertThat(openApi.at("/components/schemas/OrderDto/x-uml-relationships/0/name").asText())
                .isEqualTo("customer-orders");
        assertThat(ConnectionConfigRefValidator.looksCredentialLike(document)).isFalse();
        assertThat(HOST_PATH.matcher(document).find()).isFalse();
        assertThat(bundle.files().keySet()).contains("openapi.json", "provenance.json");
    }

    @Test
    void openApiPublishesEnumerationLiteralsInsteadOfAForeignKey() throws Exception {
        InputSnapshot snapshot = snapshot("Guest");
        DomainModelIR base = ir("displayName", "Guest", false);
        UUID statusId = id(701);
        TypeUse statusType = new TypeUse(id(702), statusId, "OrderStatus", "OrderStatus", "VARCHAR(64)", true);
        FieldDeclaration status = new FieldDeclaration(id(703), "status", "status", statusType, "PRIVATE",
                MultiplicityKind.ZERO_OR_ONE, new MultiplicityBounds(0, 1), true, CollectionIntent.SINGLE_VALUE,
                null, 1);
        List<TypeDeclaration> types = base.types().stream().map(type -> !type.sourceId().equals(ORDER_ID) ? type
                : new TypeDeclaration(type.sourceId(), null, type.classifierKind(), type.sourceName(),
                        type.javaName(), type.javaPackageName(), type.tableName(), false,
                        java.util.stream.Stream.concat(type.fields().stream(), java.util.stream.Stream.of(status))
                                .toList(), type.operations())).toList();
        DomainModelIR ir = new DomainModelIR(base.packages(), types, base.relationships(), List.of(),
                base.diagnostics(), List.of(new DomainModelIR.EnumDeclaration(statusId, "OrderStatus",
                        "OrderStatus", "", List.of("OPEN", "CLOSED"))));

        GeneratedArtifactBundle bundle = builder.build(identity("course-store", snapshot, 1), snapshot, ir,
                VerificationState.NOT_RUN);
        JsonNode openApi = JSON.readTree(bundle.files().get(GenerationArtifactBundleBuilder.OPENAPI_PATH));

        JsonNode property = openApi.at("/components/schemas/OrderDto/properties/status");
        assertThat(property.path("type").asText()).isEqualTo("string");
        assertThat(property.path("enum").toString()).isEqualTo("[\"OPEN\",\"CLOSED\"]");
        assertThat(property.path("x-enum-name").asText()).isEqualTo("OrderStatus");
        assertThat(openApi.at("/components/schemas/OrderDto/properties/statusId").isMissingNode()).isTrue();
    }

    @Test
    void openApiNamesTheHumanReadableDisplayFieldsOfEachEntity() throws Exception {
        TypeUse text = new TypeUse(null, null, "String", "String", "VARCHAR(255)", true);
        TypeUse number = new TypeUse(null, null, "Integer", "Integer", "INTEGER", true);
        java.util.function.BiFunction<String, Boolean, FieldDeclaration> field = (name, nullable) ->
                new FieldDeclaration(UUID.nameUUIDFromBytes(name.getBytes()), name, name,
                        name.equals("edad") ? number : text, "PRIVATE",
                        nullable ? MultiplicityKind.ZERO_OR_ONE : MultiplicityKind.EXACTLY_ONE,
                        new MultiplicityBounds(nullable ? 0 : 1, 1), nullable, CollectionIntent.SINGLE_VALUE, null, 0);
        TypeDeclaration alumno = new TypeDeclaration(id(801), null, ClassifierKind.CLASS, "Alumno", "Alumno", "",
                "alumno", false, List.of(field.apply("edad", false), field.apply("apellido", false),
                        field.apply("nombre", false)), List.of());
        TypeDeclaration libro = new TypeDeclaration(id(802), null, ClassifierKind.CLASS, "Libro", "Libro", "",
                "libro", false, List.of(field.apply("isbn", false), field.apply("titulo", true)), List.of());
        TypeDeclaration pago = new TypeDeclaration(id(803), null, ClassifierKind.CLASS, "Pago", "Pago", "",
                "pago", false, List.of(field.apply("nota", true), field.apply("referencia", false)), List.of());
        TypeDeclaration medida = new TypeDeclaration(id(804), null, ClassifierKind.CLASS, "Medida", "Medida", "",
                "medida", false, List.of(field.apply("edad", false)), List.of());
        InputSnapshot snapshot = snapshot("Guest");

        GeneratedArtifactBundle bundle = builder.build(identity("course-store", snapshot, 1), snapshot,
                new DomainModelIR(List.of(), List.of(alumno, libro, pago, medida), List.of(), List.of(), List.of()),
                VerificationState.NOT_RUN);
        JsonNode schemas = JSON.readTree(bundle.files().get(GenerationArtifactBundleBuilder.OPENAPI_PATH))
                .at("/components/schemas");

        assertThat(schemas.at("/AlumnoDto/x-display-fields").toString()).isEqualTo("[\"nombre\",\"apellido\"]");
        assertThat(schemas.at("/LibroDto/x-display-fields").toString()).isEqualTo("[\"titulo\"]");
        assertThat(schemas.at("/PagoDto/x-display-fields").toString()).isEqualTo("[\"referencia\"]");
        assertThat(schemas.at("/MedidaDto/x-display-fields").toString()).isEqualTo("[]");
    }

    @Test
    void provenanceRecordsFrozenIdentitiesDiagnosticsManifestAndVerificationStatus() throws Exception {
        InputSnapshot snapshot = snapshot("Guest");
        GeneratedArtifactBundle bundle = builder.build(identity("course-store", snapshot, 1), snapshot,
                ir("displayName", "Guest", false), VerificationState.NOT_RUN);
        JsonNode provenance = JSON.readTree(bundle.provenanceJson());

        assertThat(provenance.path("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(fieldNames(provenance.path("snapshot")))
                .containsExactly("modelId", "versionId", "revision", "snapshotHash", "subsetVersion");
        assertThat(fieldNames(provenance.path("target")))
                .containsExactly("targetId", "targetKey", "configurationRevision");
        assertThat(fieldNames(provenance.path("profile")))
                .containsExactly("profileId", "profileKey", "revision", "configurationHash");
        assertThat(fieldNames(provenance.path("template")))
                .containsExactly("templateId", "revision", "contentHash");
        assertThat(provenance.path("snapshot").path("modelId").asText()).isEqualTo(SNAPSHOT_MODEL_ID.toString());
        assertThat(provenance.path("snapshot").path("versionId").asText()).isEqualTo(SNAPSHOT_VERSION_ID.toString());
        assertThat(provenance.path("snapshot").path("revision").asLong()).isEqualTo(1);
        assertThat(provenance.path("snapshot").path("snapshotHash").asText()).isEqualTo(snapshot.snapshotHash());
        assertThat(provenance.path("snapshot").path("subsetVersion").asText()).isEqualTo("increment-2-subset-v1");
        assertThat(provenance.path("target").path("targetId").asText()).isEqualTo(TARGET_ID.toString());
        assertThat(provenance.path("target").path("targetKey").asText()).isEqualTo("course-store");
        assertThat(provenance.path("target").path("configurationRevision").asInt()).isEqualTo(1);
        assertThat(provenance.path("profile").path("profileId").asText()).isEqualTo(PROFILE_ID.toString());
        assertThat(provenance.path("profile").path("profileKey").asText()).isEqualTo("spring-boot-postgresql");
        assertThat(provenance.path("profile").path("revision").asInt()).isEqualTo(1);
        assertThat(provenance.path("profile").path("configurationHash").asText()).isEqualTo("a".repeat(64));
        assertThat(provenance.path("template").path("templateId").asText()).isEqualTo(TEMPLATE_ID.toString());
        assertThat(provenance.path("template").path("revision").asInt()).isEqualTo(1);
        assertThat(provenance.path("template").path("contentHash").asText()).isEqualTo("b".repeat(64));
        assertThat(provenance.path("diagnostics").isArray()).isTrue();
        assertThat(provenance.path("diagnostics")).isEmpty();
        assertThat(provenance.path("artifactSetHash").asText()).isEqualTo(bundle.artifactSetHash());
        assertThat(provenance.path("manifestScope").asText()).contains("provenance.json");
        assertThat(provenance.path("verificationStatus").asText()).isEqualTo("NOT_RUN");
        List<String> provenancePaths = new ArrayList<>();
        provenance.path("artifacts").forEach(artifact -> provenancePaths.add(artifact.path("path").asText()));
        assertThat(provenancePaths).containsExactlyElementsOf(bundle.payloadManifest().stream()
                .map(ArtifactDescriptor::path).toList());
        assertThat(provenancePaths).doesNotContain("provenance.json");
        assertThat(bundle.completeManifest()).extracting(ArtifactDescriptor::path).contains("provenance.json");
    }

    @Test
    void credentialShapedFrozenValueIsRejectedBeforeItCanBeHashedOrPackaged() {
        InputSnapshot snapshot = snapshot("Bearer t7-fixture-secret");
        DomainModelIR ir = ir("displayName", "Bearer t7-fixture-secret", false);

        assertThatThrownBy(() -> builder.build(identity("course-store", snapshot, 1), snapshot, ir,
                VerificationState.NOT_RUN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Credential-shaped value");
    }

    @Test
    void credentialShapedDiagnosticIsRejectedBeforeProvenanceAndZipAreEmitted() {
        InputSnapshot snapshot = snapshot("Guest");
        DomainModelIR unsafeIr = ir("displayName", "Guest", true);

        assertThatThrownBy(() -> builder.build(identity("course-store", snapshot, 1), snapshot, unsafeIr,
                VerificationState.NOT_RUN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Credential-shaped value");
    }

    private static void assertCrudOperations(JsonNode openApi, String tableName) {
        JsonNode paths = openApi.path("paths");
        assertThat(fieldNames(paths.path("/api/" + tableName))).containsExactly("get", "post");
        assertThat(fieldNames(paths.path("/api/" + tableName + "/{id}")))
                .containsExactly("get", "put", "delete");
        JsonNode collection = paths.path("/api/" + tableName);
        JsonNode item = paths.path("/api/" + tableName + "/{id}");
        String schemaName = Character.toUpperCase(tableName.charAt(0)) + tableName.substring(1) + "Dto";
        String schemaRef = "#/components/schemas/" + schemaName;
        assertThat(collection.path("get").path("responses").path("200").path("content")
                .path("application/json").path("schema").path("items").path("$ref").asText()).isEqualTo(schemaRef);
        assertThat(collection.path("post").path("requestBody").path("content").path("application/json")
                .path("schema").path("$ref").asText()).isEqualTo(schemaRef);
        assertThat(collection.path("post").path("responses").path("201").path("description").asText())
                .isEqualTo("Created");
        assertThat(collection.path("post").path("responses").path("201").path("content").path("application/json")
                .path("schema").path("$ref").asText()).isEqualTo(schemaRef);
        assertThat(item.path("get").path("responses").path("200").path("content").path("application/json")
                .path("schema").path("$ref").asText()).isEqualTo(schemaRef);
        assertThat(item.path("get").path("responses").path("404").isObject()).isTrue();
        assertThat(item.path("put").path("requestBody").path("content").path("application/json")
                .path("schema").path("$ref").asText()).isEqualTo(schemaRef);
        assertThat(item.path("put").path("responses").path("200").path("content").path("application/json")
                .path("schema").path("$ref").asText()).isEqualTo(schemaRef);
        assertThat(item.path("put").path("responses").path("400").isObject()).isTrue();
        assertThat(item.path("delete").path("responses").path("204").isObject()).isTrue();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Map<String, String> hashesByPath(List<ArtifactDescriptor> artifacts) {
        return artifacts.stream().collect(java.util.stream.Collectors.toMap(
                ArtifactDescriptor::path, ArtifactDescriptor::contentHash));
    }

    private static GenerationIdentity identity(String targetKey, InputSnapshot snapshot, int targetRevision) {
        return new GenerationIdentity(snapshot.modelId(), snapshot.versionId(), snapshot.revision(),
                snapshot.snapshotHash(), "increment-2-subset-v1", TARGET_ID, targetKey, targetRevision, PROFILE_ID,
                "spring-boot-postgresql", 1, "a".repeat(64), TEMPLATE_ID, 1, "b".repeat(64));
    }

    private static InputSnapshot snapshot(String defaultValue) {
        String projection = "attributes|" + CUSTOMER_ID + "|displayName|defaultValue=" + defaultValue;
        return new InputSnapshot("artifact-fixture", SNAPSHOT_MODEL_ID, SNAPSHOT_VERSION_ID, 1,
                "c".repeat(64), ValidationState.VALID, InputSnapshotFactory.sha256(projection), List.of(),
                List.of(new InputSnapshot.AttributeElement(id(301), CUSTOMER_ID, null, "displayName", "ATTRIBUTE",
                        "PRIVATE", MultiplicityKind.EXACTLY_ONE, defaultValue, 0)), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), projection);
    }

    private static DomainModelIR ir(String fieldName, String defaultValue, boolean withCredentialDiagnostic) {
        TypeUse stringType = new TypeUse(null, null, "String", "String", "VARCHAR(255)", true);
        TypeUse booleanType = new TypeUse(null, null, "boolean", "boolean", null, true);
        FieldDeclaration customerName = new FieldDeclaration(id(301), fieldName, fieldName, stringType,
                "PRIVATE", MultiplicityKind.EXACTLY_ONE, new MultiplicityBounds(1, 1), false,
                CollectionIntent.SINGLE_VALUE, defaultValue, 0);
        FieldDeclaration orderNumber = new FieldDeclaration(id(302), "orderNumber", "orderNumber", stringType,
                "PRIVATE", MultiplicityKind.EXACTLY_ONE, new MultiplicityBounds(1, 1), false,
                CollectionIntent.SINGLE_VALUE, null, 0);
        OperationSignature validation = new OperationSignature(id(401), "validate", "validate", "PUBLIC", false,
                false, booleanType, List.of(new ParameterDeclaration(id(402), "query", "query", "IN", stringType, 0)), 0);
        TypeDeclaration customer = new TypeDeclaration(CUSTOMER_ID, null, ClassifierKind.CLASS, "Customer", "Customer",
                "", "customer", false, List.of(customerName), List.of(validation));
        TypeDeclaration order = new TypeDeclaration(ORDER_ID, null, ClassifierKind.CLASS, "Order", "Order", "",
                "order", false, List.of(orderNumber), List.of());
        RelationshipEndDescriptor many = new RelationshipEndDescriptor(id(501), ORDER_ID, "Order", "orders",
                MultiplicityKind.ONE_OR_MANY, new MultiplicityBounds(1, null), true, "NONE", LifecycleIntent.NONE);
        RelationshipEndDescriptor one = new RelationshipEndDescriptor(id(502), CUSTOMER_ID, "Customer", "customer",
                MultiplicityKind.EXACTLY_ONE, new MultiplicityBounds(1, 1), true, "NONE", LifecycleIntent.NONE);
        RelationshipDescriptor relationship = new RelationshipDescriptor(id(503), RelationshipKind.ASSOCIATION,
                "customer-orders", many, one, "customer", "orders", false, true, LifecycleIntent.NONE);
        List<GenerationDiagnostic> diagnostics = withCredentialDiagnostic
                ? List.of(new GenerationDiagnostic("WARNING", SeverityKind.WARNING, id(601), "credential",
                        "Bearer t7-fixture-secret", false))
                : List.of();
        return new DomainModelIR(List.of(), List.of(customer, order), List.of(relationship), List.of(), diagnostics);
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(64);
            for (byte digestByte : digest) {
                hex.append(String.format("%02x", digestByte));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
