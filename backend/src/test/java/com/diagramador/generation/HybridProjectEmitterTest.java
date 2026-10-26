package com.diagramador.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.command.ValidationState;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.PersistenceStore;
import com.diagramador.domain.RelationshipKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Structural checks of a model whose classes are persisted in more than one store. */
class HybridProjectEmitterTest {

    private static final UUID CUSTOMER = id(1);
    private static final UUID ORDER = id(2);
    private static final UUID NOTE = id(3);
    private static final UUID LINE = id(4);
    private static final UUID STRING_REFERENCE = id(20);

    private final DomainModelIRMapper mapper = new DomainModelIRMapper();
    private final DomainProjectEmitter emitter = new DomainProjectEmitter();

    @Test
    void relationalAndDocumentClassesAreEmittedTogetherAndReferenceEachOtherById() {
        // Customer is forced to a document; Order has no signal; Note has a list; OrderLine is a composition part.
        Map<String, String> files = emitter.emit("shop", mapper.map(snapshot(PersistenceStore.DOCUMENT, null)));

        String migration = files.get("src/main/resources/db/migration/V1__init.sql");
        assertThat(migration).contains("CREATE TABLE \"order\"").doesNotContain("CREATE TABLE \"customer\"")
                .doesNotContain("CREATE TABLE \"note\"").doesNotContain("CREATE TABLE \"order_line\"")
                .doesNotContain("FOREIGN KEY");
        assertThat(migration).contains("\"customer_id\" UUID");

        String customer = files.get("src/main/java/com/generated/shop/domain/Customer.java");
        assertThat(customer).contains("@Document(collection = \"customer\")", "private UUID id = UUID.randomUUID();")
                .doesNotContain("jakarta.persistence");
        assertThat(files.get("src/main/java/com/generated/shop/repository/CustomerRepository.java"))
                .contains("extends MongoRepository<Customer, UUID>");

        String order = files.get("src/main/java/com/generated/shop/domain/Order.java");
        assertThat(order).contains("@Entity", "private UUID customerId;").doesNotContain("@ManyToOne")
                .doesNotContain("private List<");
        assertThat(files.get("src/main/java/com/generated/shop/repository/OrderRepository.java"))
                .contains("extends JpaRepository<Order, UUID>");

        assertThat(files.get("src/main/java/com/generated/shop/domain/Note.java"))
                .contains("@Document(collection = \"note\")", "private List<String> tags = new ArrayList<>();");
        String line = files.get("src/main/java/com/generated/shop/domain/OrderLine.java");
        assertThat(line).contains("@Document(collection = \"order_line\")", "private UUID orderId;");
    }

    @Test
    void documentClassesExposeCrudOnlyAndRelationalOnesKeepTheQueryEndpoints() {
        Map<String, String> files = emitter.emit("shop", mapper.map(snapshot(PersistenceStore.DOCUMENT, null)));

        String documentController = files.get("src/main/java/com/generated/shop/api/CustomerController.java");
        assertThat(documentController).contains("@PostMapping", "@DeleteMapping", "@PutMapping")
                .doesNotContain("/query").doesNotContain("/aggregate").doesNotContain("QuerySupport")
                .doesNotContain("MultiValueMap");
        String documentService = files.get("src/main/java/com/generated/shop/service/CustomerService.java");
        assertThat(documentService).doesNotContain("@Transactional").doesNotContain("EntityManager")
                .contains("repository.save(entity)");

        assertThat(files.get("src/main/java/com/generated/shop/api/OrderController.java"))
                .contains("/query", "/aggregate");
        assertThat(files).containsKey("src/main/java/com/generated/shop/api/QuerySupport.java");
    }

    @Test
    void theStackIncludesEachStoreOnlyWhenAClassNeedsIt() {
        Map<String, String> hybrid = emitter.emit("shop", mapper.map(snapshot(PersistenceStore.DOCUMENT, null)));
        assertThat(hybrid.get("pom.xml")).contains("spring-boot-starter-data-jpa", "flyway-core",
                "spring-boot-starter-data-mongodb");
        assertThat(hybrid.get("src/main/resources/application.yml")).contains("datasource:", "ddl-auto: validate",
                "uri: ${SPRING_DATA_MONGODB_URI}", "uuid-representation: standard");
        assertThat(hybrid.get("docker-compose.yml")).contains("shop-db:", "shop-mongo:", "shop-postgres-data:",
                "shop-mongo-data:", "SPRING_DATA_MONGODB_URI", "SPRING_DATASOURCE_URL");
        assertThat(hybrid.get(".env.example")).contains("GENERATED_DB_NAME", "GENERATED_MONGO_DB");

        Map<String, String> sqlOnly = emitter.emit("shop", mapper.map(snapshot(PersistenceStore.SQL, PersistenceStore.SQL)));
        assertThat(sqlOnly.get("pom.xml")).doesNotContain("mongodb");
        assertThat(sqlOnly.get("src/main/resources/application.yml")).doesNotContain("mongodb");
        assertThat(sqlOnly.get("docker-compose.yml")).doesNotContain("mongo");
        assertThat(sqlOnly.get(".env.example")).doesNotContain("MONGO");
        assertThat(sqlOnly.get("README.md")).doesNotContain("Persistence stores");
    }

    @Test
    void aModelOfOnlyDocumentsHasNoRelationalArtifacts() {
        Map<String, String> files = emitter.emit("shop", mapper.map(snapshot(PersistenceStore.DOCUMENT,
                PersistenceStore.DOCUMENT)));

        assertThat(files).doesNotContainKey("src/main/resources/db/migration/V1__init.sql")
                .doesNotContainKey("src/main/java/com/generated/shop/api/QuerySupport.java");
        assertThat(files.get("pom.xml")).doesNotContain("data-jpa", "flyway", "postgresql")
                .contains("spring-boot-starter-data-mongodb");
        assertThat(files.get("src/main/resources/application.yml")).doesNotContain("datasource", "flyway", "jpa");
        assertThat(files.get("docker-compose.yml")).doesNotContain("postgres", "-db:").contains("shop-mongo:");
        assertThat(files.get("README.md")).doesNotContain("Flyway applies").contains("MongoDB");
    }

    @Test
    void theReadmeExplainsEachClassStoreAndTheLimitsOfReferencesByIdAndRepeatsByteForByte() {
        Map<String, String> files = emitter.emit("shop", mapper.map(snapshot(PersistenceStore.DOCUMENT, null)));

        assertThat(files.get("README.md")).contains("## Persistence stores",
                "- `Customer`: document (MongoDB). Set manually.",
                "- `Order`: relational (PostgreSQL). No document signal, so it stays relational.",
                "- `Note`: document (MongoDB). Has list attributes, which a document stores natively.",
                "- `OrderLine`: document (MongoDB). Part of a composition owned by Order.",
                "There is no foreign key and no join");
        assertThat(emitter.emit("shop", mapper.map(snapshot(PersistenceStore.DOCUMENT, null)))).isEqualTo(files);
    }

    @Test
    void theIrCarriesTheDecidedStoreAndItsReasonOnEveryType() {
        DomainModelIR ir = mapper.map(snapshot(PersistenceStore.DOCUMENT, null));

        Map<String, DomainModelIR.TypeDeclaration> byName = new java.util.HashMap<>();
        ir.types().forEach(type -> byName.put(type.javaName(), type));
        assertThat(byName.get("Customer").store()).isEqualTo(PersistenceStore.DOCUMENT);
        assertThat(byName.get("Customer").storeReason()).isEqualTo("Set manually.");
        assertThat(byName.get("Order").store()).isEqualTo(PersistenceStore.SQL);
        assertThat(byName.get("Note").store()).isEqualTo(PersistenceStore.DOCUMENT);
        assertThat(byName.get("OrderLine").storeReason()).isEqualTo("Part of a composition owned by Order.");
    }

    @Test
    void onlyRelationalSourcesTraceToTheMigrationAndAModelOfDocumentsTracesToNone() {
        UUID orderNumberField = id(31);
        UUID customerNameField = id(30);
        DomainModelIR hybrid = mapper.map(snapshot(PersistenceStore.DOCUMENT, null));
        assertThat(pathsOf(hybrid, orderNumberField)).contains("src/main/resources/db/migration/V1__init.sql");
        assertThat(pathsOf(hybrid, customerNameField)).isNotEmpty()
                .doesNotContain("src/main/resources/db/migration/V1__init.sql");

        DomainModelIR documents = mapper.map(snapshot(PersistenceStore.DOCUMENT, PersistenceStore.DOCUMENT));
        assertThat(documents.sourceToArtifacts()).allSatisfy(mapping -> assertThat(mapping.artifactPaths())
                .doesNotContain("src/main/resources/db/migration/V1__init.sql"));
    }

    private static List<String> pathsOf(DomainModelIR ir, UUID sourceId) {
        return ir.sourceToArtifacts().stream().filter(mapping -> mapping.sourceId().equals(sourceId))
                .flatMap(mapping -> mapping.artifactPaths().stream()).toList();
    }

    static InputSnapshot snapshot(PersistenceStore customerStore, PersistenceStore orderStore) {
        String projection = "hybrid-emitter-fixture-v1";
        return new InputSnapshot(
                "hybrid-fixture",
                id(100),
                id(101),
                1,
                "a".repeat(64),
                ValidationState.VALID,
                InputSnapshotFactory.sha256(projection),
                List.of(
                        new InputSnapshot.ClassifierElement(CUSTOMER, null, ClassifierKind.CLASS, "Customer", false,
                                customerStore),
                        new InputSnapshot.ClassifierElement(ORDER, null, ClassifierKind.CLASS, "Order", false,
                                orderStore),
                        new InputSnapshot.ClassifierElement(NOTE, null, ClassifierKind.CLASS, "Note", false,
                                orderStore),
                        new InputSnapshot.ClassifierElement(LINE, null, ClassifierKind.CLASS, "OrderLine", false,
                                orderStore)),
                List.of(
                        attribute(30, CUSTOMER, "displayName", MultiplicityKind.EXACTLY_ONE),
                        attribute(31, ORDER, "orderNumber", MultiplicityKind.EXACTLY_ONE),
                        attribute(32, NOTE, "tags", MultiplicityKind.ZERO_OR_MANY),
                        attribute(33, LINE, "sku", MultiplicityKind.EXACTLY_ONE)),
                List.of(),
                List.of(),
                List.of(new InputSnapshot.TypeReferenceElement(STRING_REFERENCE, null, "String")),
                List.of(
                        new InputSnapshot.RelationshipElement(id(10), RelationshipKind.ASSOCIATION, "CustomerOrders"),
                        new InputSnapshot.RelationshipElement(id(13), RelationshipKind.COMPOSITION, "OrderLines")),
                List.of(
                        end(11, 10, ORDER, 0, "orders", MultiplicityKind.ONE_OR_MANY, "NONE"),
                        end(12, 10, CUSTOMER, 1, "customer", MultiplicityKind.ZERO_OR_ONE, "NONE"),
                        end(14, 13, ORDER, 0, "order", MultiplicityKind.EXACTLY_ONE, "COMPOSITE"),
                        end(15, 13, LINE, 1, "lines", MultiplicityKind.ONE_OR_MANY, "NONE")),
                List.of(),
                projection);
    }

    private static InputSnapshot.AttributeElement attribute(int number, UUID owner, String name,
            MultiplicityKind multiplicity) {
        return new InputSnapshot.AttributeElement(id(number), owner, STRING_REFERENCE, name, "ATTRIBUTE", "PRIVATE",
                multiplicity, null, 0);
    }

    private static InputSnapshot.RelationshipEndElement end(int number, int relationship, UUID classifier, int ordinal,
            String role, MultiplicityKind multiplicity, String aggregationKind) {
        return new InputSnapshot.RelationshipEndElement(id(number), id(relationship), classifier, (short) ordinal, role,
                multiplicity, NavigationKind.NAVIGABLE, aggregationKind);
    }

    private static UUID id(long low) {
        return new UUID(0L, low);
    }
}
