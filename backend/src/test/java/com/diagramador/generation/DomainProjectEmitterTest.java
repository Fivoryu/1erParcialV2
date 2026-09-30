package com.diagramador.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.command.ValidationState;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.generation.DomainModelIR.RelationshipDescriptor;
import java.io.IOException;
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
import org.junit.jupiter.api.Test;

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

    private final DomainModelIRMapper mapper = new DomainModelIRMapper();
    private final DomainProjectEmitter emitter = new DomainProjectEmitter();

    @Test
    void emitsExpectedFourLayerPathsForTwoClassFixture() {
        Map<String, String> files = emitter.emit("course-store", mapper.map(snapshot(false)));

        assertThat(new ArrayList<>(files.keySet())).containsExactly(
                ".gitignore",
                "Dockerfile",
                "README.md",
                "pom.xml",
                "src/main/java/com/generated/course/store/GeneratedApplication.java",
                "src/main/java/com/generated/course/store/api/CustomerController.java",
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
        assertThat(files.get("README.md")).contains("Security limitation", "no generated authentication");
        assertThat(files.get("src/main/java/com/generated/course/store/service/CustomerService.java"))
                .contains("@Transactional(readOnly = true)",
                        "repository.findAll().stream().map(CustomerDto::from).toList()");
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
    }

    @Test
    void emittedProjectBuildsWithTheOfflineMavenCache() throws Exception {
        Map<String, String> files = emitter.emit("course-store", mapper.map(snapshot(false)));
        Path project = Files.createTempDirectory(Path.of("target"), "generated-emitter-build-").toAbsolutePath();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path output = project.resolve(entry.getKey()).normalize();
            assertThat(output.startsWith(project)).isTrue();
            Files.createDirectories(output.getParent());
            Files.write(output, entry.getValue().getBytes(StandardCharsets.UTF_8));
        }

        Path buildLog = project.resolve("nested-maven-build.log");
        List<String> command = isWindows()
                ? List.of("cmd.exe", "/c", "mvn", "-o", "-B", "package")
                : List.of("mvn", "-o", "-B", "package");
        long started = System.nanoTime();
        Process process = new ProcessBuilder(command)
                .directory(project.toFile())
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
        assertThat(output).contains("BUILD SUCCESS");
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

    private static UUID id(long low) {
        return new UUID(0, low);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
