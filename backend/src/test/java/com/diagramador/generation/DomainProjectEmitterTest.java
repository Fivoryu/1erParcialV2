package com.diagramador.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.command.ValidationState;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.generation.DomainModelIR.RelationshipDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class DomainProjectEmitterTest {

    private static final UUID CUSTOMER_ID = id(1);
    private static final UUID ORDER_ID = id(2);
    private static final UUID RELATIONSHIP_ID = id(10);
    private static final UUID MANY_END_ID = id(11);
    private static final UUID ONE_END_ID = id(12);
    private static final UUID STRING_REFERENCE_ID = id(20);
    private static final Pattern HOST_PATH = Pattern.compile(
            "(?i)(?:\\b[A-Z]:[\\\\/][^\\s\"'<>]*|(?:^|[\\s\"'=])/(?:home|Users|tmp|var/tmp|workspace|workspaces|mnt)/[^\\s\"'<>]*)");
    private static final Pattern UUID_LITERAL = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "\\b[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.+-]+(?:Z|[+-][0-9:]+)?\\b");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final DomainModelIRMapper MAPPER = new DomainModelIRMapper();
    private static final DomainProjectEmitter EMITTER = new DomainProjectEmitter();
    private static Path generatedProject;
    private static Path generatedAppLog;
    private static PostgreSQLContainer<?> postgres;
    private static Process generatedApp;
    private static int generatedPort;

    private final DomainModelIRMapper mapper = MAPPER;
    private final DomainProjectEmitter emitter = EMITTER;

    @BeforeAll
    static void buildAndStartGeneratedCrudApplication() throws Exception {
        Map<String, String> files = EMITTER.emit("course-store", MAPPER.map(snapshot(false)));
        generatedProject = Files.createTempDirectory(Path.of("target"), "generated-emitter-crud-").toAbsolutePath();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path output = generatedProject.resolve(entry.getKey()).normalize();
            assertThat(output.startsWith(generatedProject)).isTrue();
            Files.createDirectories(output.getParent());
            Files.write(output, entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        buildGeneratedProject();

        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        generatedPort = availablePort();
        generatedAppLog = generatedProject.resolve("generated-app.log");
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        ProcessBuilder appBuilder = new ProcessBuilder(java.toString(), "-jar",
                generatedProject.resolve("target/generated-course-store-0.1.0.jar").toString())
                .directory(generatedProject.toFile())
                .redirectErrorStream(true)
                .redirectOutput(generatedAppLog.toFile());
        appBuilder.environment().put("SERVER_PORT", Integer.toString(generatedPort));
        appBuilder.environment().put("SPRING_DATASOURCE_URL", postgres.getJdbcUrl());
        appBuilder.environment().put("SPRING_DATASOURCE_USERNAME", postgres.getUsername());
        appBuilder.environment().put("SPRING_DATASOURCE_PASSWORD", postgres.getPassword());
        generatedApp = appBuilder.start();
        awaitGeneratedApplication();
    }

    @Test
    void emitsExpectedFourLayerPathsForTwoClassFixture() {
        Map<String, String> files = emitter.emit("course-store", mapper.map(snapshot(false)));

        assertThat(new ArrayList<>(files.keySet())).containsExactly(
                ".env.example",
                ".gitignore",
                "Dockerfile",
                "README.md",
                "docker-compose.yml",
                "pom.xml",
                "src/main/java/com/generated/course/store/GeneratedApplication.java",
                "src/main/java/com/generated/course/store/api/ApiErrorResponse.java",
                "src/main/java/com/generated/course/store/api/CustomerController.java",
                "src/main/java/com/generated/course/store/api/GeneratedApiException.java",
                "src/main/java/com/generated/course/store/api/GeneratedApiExceptionHandler.java",
                "src/main/java/com/generated/course/store/api/OrderController.java",
                "src/main/java/com/generated/course/store/api/dto/CustomerDto.java",
                "src/main/java/com/generated/course/store/api/dto/OrderDto.java",
                "src/main/java/com/generated/course/store/domain/Customer.java",
                "src/main/java/com/generated/course/store/domain/Order.java",
                "src/main/java/com/generated/course/store/repository/CustomerRepository.java",
                "src/main/java/com/generated/course/store/repository/OrderRepository.java",
                "src/main/java/com/generated/course/store/service/CustomerService.java",
                "src/main/java/com/generated/course/store/service/OrderService.java",
                "src/main/resources/application.yml",
                "src/main/resources/db/migration/V1__init.sql");
        assertThat(files.get("pom.xml")).contains("<artifactId>generated-course-store</artifactId>");
        assertThat(files.get("pom.xml")).contains("<version>3.4.5</version>");
        assertThat(files.get("Dockerfile")).startsWith("FROM eclipse-temurin:21-jre-alpine\n");
        assertThat(files.get("src/main/resources/application.yml"))
                .contains("${SPRING_DATASOURCE_URL}", "${SPRING_DATASOURCE_USERNAME}",
                        "${SPRING_DATASOURCE_PASSWORD}");
        assertThat(files.get("pom.xml")).doesNotContain("<artifactId>spring-boot-starter-security</artifactId>");
        assertThat(files.get("pom.xml")).contains("spring-boot-starter-security is omitted");
        // The generated backend must answer a health endpoint: the increment's definition of done and
        // CU-20 both require "health UP" from the started artefact, so the emitter has to ship it.
        assertThat(files.get("pom.xml")).contains("<artifactId>spring-boot-starter-actuator</artifactId>");
        assertThat(files.get("src/main/resources/application.yml")).contains("include: health");
        assertThat(files.get("README.md")).contains("Security limitation", "no generated authentication");
        assertThat(files.get("README.md")).contains("## Deploy with Docker Compose", "docker compose up -d --build");
        assertThat(files.get("src/main/java/com/generated/course/store/service/CustomerService.java"))
                .contains("@Transactional(readOnly = true)",
                        "repository.findAll().stream().map(CustomerDto::from).toList()",
                        "public CustomerDto create(CustomerDto request)", "public boolean delete(UUID id)");
        assertThat(files.get("src/main/java/com/generated/course/store/api/OrderController.java"))
                .contains("@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping");
        assertThat(files.get("src/main/java/com/generated/course/store/domain/Customer.java"))
                .contains("private String displayName =", "Guest");
        assertThat(files.get("src/main/resources/db/migration/V1__init.sql"))
                .contains("display_name", "VARCHAR(255)", "DEFAULT 'Guest' NOT NULL");
    }

    @Test
    void repeatedEmissionIsByteIdenticalAndDiskArtifactsHavePortableLfText() throws IOException {
        DomainModelIR ir = mapper.map(snapshot(false));
        Map<String, String> first = emitter.emit("course-store", ir);
        Map<String, String> second = emitter.emit("course-store", ir);
        assertThat(new ArrayList<>(second.keySet())).containsExactlyElementsOf(new ArrayList<>(first.keySet()));
        for (String path : first.keySet()) {
            assertThat(second.get(path).getBytes(StandardCharsets.UTF_8))
                    .as("repeat emission bytes for " + path)
                    .containsExactly(first.get(path).getBytes(StandardCharsets.UTF_8));
            assertThat(path).doesNotContain("\\").doesNotStartWith("/").doesNotContain("../");
            assertThat(first.get(path)).endsWith("\n").doesNotContain("\r");
            assertThat(UUID_LITERAL.matcher(first.get(path)).find()).as("no generated UUID in " + path).isFalse();
            assertThat(TIMESTAMP.matcher(first.get(path)).find()).as("no timestamp in " + path).isFalse();
        }

        Path project = Files.createTempDirectory(Path.of("target"), "generated-emitter-bytes-").toAbsolutePath();
        for (Map.Entry<String, String> entry : first.entrySet()) {
            Path output = project.resolve(entry.getKey()).normalize();
            assertThat(output.startsWith(project)).isTrue();
            Files.createDirectories(output.getParent());
            Files.write(output, entry.getValue().getBytes(StandardCharsets.UTF_8));
            byte[] diskBytes = Files.readAllBytes(output);
            assertThat(HOST_PATH.matcher(new String(diskBytes, StandardCharsets.UTF_8)).find())
                    .as("no host path or drive letter in " + entry.getKey())
                    .isFalse();
        }

        Path positiveControl = project.resolve("host-path-positive-control.txt");
        Files.writeString(positiveControl, "C:\\Users\\fixture\\generated-project", StandardCharsets.UTF_8);
        byte[] controlBytes = Files.readAllBytes(positiveControl);
        var controlMatcher = HOST_PATH.matcher(new String(controlBytes, StandardCharsets.UTF_8));
        assertThat(controlMatcher.find()).as("the host-path detector must recognize a real Windows drive path").isTrue();
        System.out.println("HOST_PATH_POSITIVE_CONTROL detected: " + controlMatcher.group());
    }

    @Test
    void emittedOwnershipFollowsTheIrForBothNormalizedAndInvertedEndIds() {
        DomainModelIR normal = mapper.map(snapshot(false));
        DomainModelIR inverted = mapper.map(snapshot(true));
        for (DomainModelIR ir : List.of(normal, inverted)) {
            assertThat(ir.hasBlockingDiagnostics()).isFalse();
            RelationshipDescriptor relationship = ir.relationships().getFirst();
            assertThat(relationship.owningEnd().classifierSourceId()).isEqualTo(ORDER_ID);
            assertThat(relationship.referencedEnd().classifierSourceId()).isEqualTo(CUSTOMER_ID);
            Map<String, String> files = emitter.emit("course-store", ir);
            String orderEntity = files.get("src/main/java/com/generated/course/store/domain/Order.java");
            String customerEntity = files.get("src/main/java/com/generated/course/store/domain/Customer.java");
            assertThat(orderEntity).contains("@ManyToOne", "customer_id",
                    "private com.generated.course.store.domain.Customer customer;");
            assertThat(customerEntity).contains("@OneToMany",
                    "private List<com.generated.course.store.domain.Order> orders = new ArrayList<>();");
            assertThat(files.get("src/main/resources/db/migration/V1__init.sql"))
                    .contains("FOREIGN KEY (\"customer_id\") REFERENCES \"customer\" (\"id\")");
        }
        assertThat(emitter.emit("course-store", normal)).isEqualTo(emitter.emit("course-store", inverted));
    }

    @Test
    void emittedPomUsesTheInjectedBaselineValue() {
        GenerationBaseline testBaseline = new GenerationBaseline(
                "21", "9.8.7", "postgres:16-alpine", "eclipse-temurin:21-jre-alpine");
        Map<String, String> files = new DomainProjectEmitter(testBaseline)
                .emit("course-store", mapper.map(snapshot(false)));

        assertThat(files.get("pom.xml")).contains("<version>9.8.7</version>");
        assertThat(files.get("pom.xml")).doesNotContain("<version>3.4.5</version>");
        assertThat(GenerationBaseline.load().springBootVersion()).isEqualTo("3.4.5");
        // The Compose file reads the same baseline, so a baseline change reaches the destination too.
        assertThat(files.get("docker-compose.yml")).contains("image: postgres:16-alpine");
    }

    @Test
    void emittedComposeGivesEachDestinationItsOwnStackWithoutSecrets() {
        DomainModelIR ir = mapper.map(snapshot(false));
        Map<String, String> reporting = emitter.emit("reporting-service", ir);
        Map<String, String> billing = emitter.emit("billing-service", ir);
        String reportingCompose = reporting.get("docker-compose.yml");
        String billingCompose = billing.get("docker-compose.yml");

        assertThat(reportingCompose).contains(
                "name: generated-reporting-service",
                "  reporting-service-db:",
                "  reporting-service:",
                "      - reporting-service-postgres-data:/var/lib/postgresql/data",
                "  reporting-service-postgres-data:");
        assertThat(billingCompose).contains(
                "name: generated-billing-service",
                "  billing-service-postgres-data:");
        // Two destinations must not share a Compose project, a service name, or a database volume.
        assertThat(billingCompose).doesNotContain("reporting-service");
        assertThat(reportingCompose).doesNotContain("billing-service");

        // The database is the classpath baseline, and the application reads its datasource from the environment.
        assertThat(reportingCompose).contains("image: postgres:16-alpine");
        assertThat(reportingCompose).contains(
                "      SPRING_DATASOURCE_URL: jdbc:postgresql://reporting-service-db:5432/${GENERATED_DB_NAME}",
                "      SPRING_DATASOURCE_USERNAME: ${GENERATED_DB_USER}",
                "      SPRING_DATASOURCE_PASSWORD: ${GENERATED_DB_PASSWORD}",
                "        condition: service_healthy",
                "/actuator/health");

        // No literal credential, no uri user-info, and no host path in any deployment artifact.
        assertThat(reportingCompose).doesNotContain("replace-with-a-local-only-value");
        for (String path : List.of("docker-compose.yml", ".env.example", "README.md", ".gitignore")) {
            String content = reporting.get(path);
            assertThat(ConnectionConfigRefValidator.looksCredentialLike(content))
                    .as("credential-shaped value in " + path)
                    .isFalse();
            assertThat(HOST_PATH.matcher(content).find()).as("host path in " + path).isFalse();
        }

        assertThat(reporting.get(".env.example")).contains(
                "GENERATED_DB_NAME=", "GENERATED_DB_PASSWORD=replace-with-a-local-only-value",
                "GENERATED_HTTP_HOST_PORT=19001", "GENERATED_POSTGRES_HOST_PORT=25433");
        assertThat(reporting.get(".gitignore")).contains(".env");
    }

    /**
     * The Compose file must be valid YAML before any container is involved.
     *
     * <p>A healthcheck written with unescaped inner double quotes parsed as neither YAML nor Compose:
     * `docker compose config` refused it with "did not find expected ',' or ']'". Serializing the file
     * through a real parser is the guard that catches that class of defect in the suite instead of at a
     * deployment.
     */
    @Test
    @SuppressWarnings("unchecked")
    void emittedComposeParsesAsYamlAndItsHealthchecksRunAsWritten() {
        Map<String, String> files = emitter.emit("reporting-service", mapper.map(snapshot(false)));
        String compose = files.get("docker-compose.yml");

        Map<String, Object> parsed = new Yaml().load(compose);
        assertThat(parsed).containsEntry("name", "generated-reporting-service");
        Map<String, Object> services = (Map<String, Object>) parsed.get("services");
        assertThat(services).containsOnlyKeys("reporting-service-db", "reporting-service");

        Map<String, Object> database = (Map<String, Object>) services.get("reporting-service-db");
        Map<String, Object> databaseHealthcheck = (Map<String, Object>) database.get("healthcheck");
        assertThat(databaseHealthcheck.get("test")).isEqualTo(List.of("CMD-SHELL",
                "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"));

        Map<String, Object> application = (Map<String, Object>) services.get("reporting-service");
        Map<String, Object> applicationHealthcheck = (Map<String, Object>) application.get("healthcheck");
        assertThat(applicationHealthcheck.get("test")).isEqualTo(List.of("CMD-SHELL",
                "wget -q --spider http://127.0.0.1:8080/actuator/health"));
        assertThat((Map<String, Object>) application.get("environment")).containsKey("SPRING_DATASOURCE_URL");
        assertThat((Map<String, Object>) parsed.get("volumes")).containsOnlyKeys("reporting-service-postgres-data");
    }

    @Test
    void generatedCrudRoutesPersistAndReturnTheResolvedParentReference() throws Exception {
        HttpResponse<String> customerList = request("GET", "/api/customer", null);
        HttpResponse<String> orderList = request("GET", "/api/order", null);
        assertThat(customerList.statusCode()).isEqualTo(200);
        assertThat(orderList.statusCode()).isEqualTo(200);

        String suffix = UUID.randomUUID().toString();
        HttpResponse<String> createdCustomer = request("POST", "/api/customer",
                JSON.writeValueAsString(Map.of("displayName", "Parent " + suffix)));
        assertThat(createdCustomer.statusCode()).isEqualTo(201);
        String parentId = JSON.readTree(createdCustomer.body()).get("id").asText();

        HttpResponse<String> createdOrder = request("POST", "/api/order",
                JSON.writeValueAsString(Map.of("orderNumber", "Order " + suffix, "customerId", parentId)));
        assertThat(createdOrder.statusCode()).isEqualTo(201);
        JsonNode createdBody = JSON.readTree(createdOrder.body());
        String orderId = createdBody.get("id").asText();
        assertThat(createdBody.get("customerId").asText()).isEqualTo(parentId);

        HttpResponse<String> readOrder = request("GET", "/api/order/" + orderId, null);
        assertThat(readOrder.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(readOrder.body()).get("customerId").asText()).isEqualTo(parentId);

        HttpResponse<String> updatedOrder = request("PUT", "/api/order/" + orderId,
                JSON.writeValueAsString(Map.of("orderNumber", "Updated " + suffix, "customerId", parentId)));
        assertThat(updatedOrder.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(updatedOrder.body()).get("customerId").asText()).isEqualTo(parentId);

        HttpResponse<String> deletedOrder = request("DELETE", "/api/order/" + orderId, null);
        assertThat(deletedOrder.statusCode()).isEqualTo(204);
        UUID missingId = UUID.randomUUID();
        HttpResponse<String> updateUnknown = request("PUT", "/api/order/" + missingId,
                JSON.writeValueAsString(Map.of("orderNumber", "missing", "customerId", parentId)));
        HttpResponse<String> deleteUnknown = request("DELETE", "/api/order/" + missingId, null);
        assertThat(updateUnknown.statusCode()).isEqualTo(404);
        assertThat(deleteUnknown.statusCode()).isEqualTo(404);
        assertErrorShape(JSON.readTree(updateUnknown.body()), "NOT_FOUND");
        assertErrorShape(JSON.readTree(deleteUnknown.body()), "NOT_FOUND");
        System.out.printf("CRUD_HTTP_STATUS listCustomer=%d listOrder=%d postCustomer=%d postOrder=%d get=%d put=%d delete=%d putUnknown=%d deleteUnknown=%d%n",
                customerList.statusCode(), orderList.statusCode(), createdCustomer.statusCode(),
                createdOrder.statusCode(), readOrder.statusCode(), updatedOrder.statusCode(),
                deletedOrder.statusCode(), updateUnknown.statusCode(), deleteUnknown.statusCode());
    }

    @Test
    void generatedApplicationAnswersHealthWithUp() throws Exception {
        HttpResponse<String> health = request("GET", "/actuator/health", null);

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(health.body()).get("status").asText()).isEqualTo("UP");
    }

    @Test
    void missingRelatedIdReturns400ThroughTheReferenceResolutionPath() throws Exception {
        UUID missingParentId = UUID.randomUUID();
        HttpResponse<String> response = request("POST", "/api/order",
                JSON.writeValueAsString(Map.of("orderNumber", "valid-order", "customerId", missingParentId.toString())));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.statusCode()).isLessThan(500);
        JsonNode error = JSON.readTree(response.body());
        assertErrorShape(error, "INVALID_REFERENCE");
        assertThat(error.get("detail").asText()).contains("customerId=" + missingParentId);
        System.out.printf("MISSING_REFERENCE_HTTP_STATUS status=%d code=%s detail=%s%n",
                response.statusCode(), error.get("code").asText(), error.get("detail").asText());
    }

    @Test
    void unknownGetIdReturns404WithTheUniformErrorBody() throws Exception {
        UUID unknownId = UUID.randomUUID();
        HttpResponse<String> response = request("GET", "/api/order/" + unknownId, null);

        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode error = JSON.readTree(response.body());
        assertErrorShape(error, "NOT_FOUND");
        assertThat(error.get("detail").asText()).contains("Order id=" + unknownId);
        System.out.printf("UNKNOWN_GET_HTTP_STATUS status=%d code=%s detail=%s%n",
                response.statusCode(), error.get("code").asText(), error.get("detail").asText());
    }

    @Test
    void invalidRequestReturns400WithTheUniformErrorBody() throws Exception {
        HttpResponse<String> response = request("POST", "/api/customer", "{}");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = JSON.readTree(response.body());
        assertErrorShape(error, "VALIDATION_FAILED");
        assertThat(error.get("detail").asText()).contains("displayName");
        System.out.printf("VALIDATION_HTTP_STATUS status=%d code=%s detail=%s%n",
                response.statusCode(), error.get("code").asText(), error.get("detail").asText());
    }

    private static InputSnapshot snapshot(boolean invertEndIds) {
        String projection = "two-class-emitter-fixture-v1";
        UUID modelId = id(100);
        UUID versionId = id(101);
        UUID manyEndId = invertEndIds ? ONE_END_ID : MANY_END_ID;
        UUID oneEndId = invertEndIds ? MANY_END_ID : ONE_END_ID;
        short manyOrdinal = (short) (invertEndIds ? 1 : 0);
        short oneOrdinal = (short) (invertEndIds ? 0 : 1);
        return new InputSnapshot(
                "emitter-fixture",
                modelId,
                versionId,
                1,
                "a".repeat(64),
                ValidationState.VALID,
                InputSnapshotFactory.sha256(projection),
                List.of(
                        new InputSnapshot.ClassifierElement(CUSTOMER_ID, null, ClassifierKind.CLASS, "Customer", false),
                        new InputSnapshot.ClassifierElement(ORDER_ID, null, ClassifierKind.CLASS, "Order", false)),
                List.of(
                        new InputSnapshot.AttributeElement(id(30), CUSTOMER_ID, STRING_REFERENCE_ID, "displayName",
                                "ATTRIBUTE", "PRIVATE", MultiplicityKind.EXACTLY_ONE, "Guest", 0),
                        new InputSnapshot.AttributeElement(id(31), ORDER_ID, STRING_REFERENCE_ID, "orderNumber",
                                "ATTRIBUTE", "PRIVATE", MultiplicityKind.EXACTLY_ONE, null, 0)),
                List.of(),
                List.of(),
                List.of(new InputSnapshot.TypeReferenceElement(STRING_REFERENCE_ID, null, "String")),
                List.of(new InputSnapshot.RelationshipElement(RELATIONSHIP_ID, RelationshipKind.ASSOCIATION,
                        "CustomerOrders")),
                List.of(
                        new InputSnapshot.RelationshipEndElement(manyEndId, RELATIONSHIP_ID, ORDER_ID, manyOrdinal,
                                "orders", MultiplicityKind.ONE_OR_MANY, NavigationKind.NAVIGABLE, "NONE"),
                        new InputSnapshot.RelationshipEndElement(oneEndId, RELATIONSHIP_ID, CUSTOMER_ID, oneOrdinal,
                                "customer", MultiplicityKind.ZERO_OR_ONE, NavigationKind.NAVIGABLE, "NONE")),
                List.of(),
                projection);
    }

    private static void buildGeneratedProject() throws Exception {
        Path buildLog = generatedProject.resolve("nested-maven-build.log");
        List<String> command = isWindows()
                ? List.of("cmd.exe", "/c", "mvn", "-o", "-B", "package")
                : List.of("mvn", "-o", "-B", "package");
        long started = System.nanoTime();
        Process process = new ProcessBuilder(command)
                .directory(generatedProject.toFile())
                .redirectErrorStream(true)
                .redirectOutput(buildLog.toFile())
                .start();
        boolean completed = process.waitFor(5, TimeUnit.MINUTES);
        long durationMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        if (!completed) {
            process.destroyForcibly();
        }
        String output = Files.exists(buildLog) ? Files.readString(buildLog, StandardCharsets.UTF_8) : "";
        int exitCode = completed ? process.exitValue() : -1;
        System.out.printf("NESTED_OFFLINE_BUILD command=%s exitCode=%d durationMs=%d%n%s%n",
                String.join(" ", command), exitCode, durationMillis, output);
        assertThat(completed).as("nested mvn -o -B package completed within five minutes\n" + output).isTrue();
        assertThat(exitCode).as("nested mvn -o -B package output\n" + output).isZero();
        assertThat(output).contains("Compiling 14 source files", "BUILD SUCCESS");
    }

    private static void awaitGeneratedApplication() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        String lastFailure = "application did not answer HTTP";
        while (System.nanoTime() < deadline) {
            if (generatedApp == null || !generatedApp.isAlive()) {
                throw new AssertionError("Generated application exited during startup. " + generatedAppOutput());
            }
            try {
                HttpResponse<String> response = request("GET", "/api/customer", null);
                if (response.statusCode() == 200) {
                    return;
                }
                lastFailure = "GET /api/customer returned " + response.statusCode() + ": " + response.body();
            } catch (IOException exception) {
                lastFailure = exception.getMessage();
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Generated application did not become ready: " + lastFailure + "\n"
                + generatedAppOutput());
    }

    @AfterAll
    static void stopGeneratedCrudApplication() throws Exception {
        if (generatedApp != null && generatedApp.isAlive()) {
            generatedApp.destroy();
            if (!generatedApp.waitFor(10, TimeUnit.SECONDS)) {
                generatedApp.destroyForcibly();
            }
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static HttpResponse<String> request(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + generatedPort + path))
                .timeout(Duration.ofSeconds(5));
        if (body == null) {
            if (method.equals("GET")) {
                builder.GET();
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertErrorShape(JsonNode error, String expectedCode) {
        assertThat(error.get("code")).isNotNull();
        assertThat(error.get("message")).isNotNull();
        assertThat(error.get("detail")).isNotNull();
        assertThat(error.size()).isEqualTo(3);
        assertThat(error.get("code").asText()).isEqualTo(expectedCode);
        assertThat(error.get("message").asText()).isNotBlank();
        assertThat(error.get("detail").asText()).isNotBlank();
    }

    private static int availablePort() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            return server.getLocalPort();
        }
    }

    private static String generatedAppOutput() {
        try {
            return generatedAppLog == null ? "no application log" : Files.readString(generatedAppLog);
        } catch (IOException exception) {
            return "unable to read application log: " + exception.getMessage();
        }
    }

    private static UUID id(long low) {
        return new UUID(0, low);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
