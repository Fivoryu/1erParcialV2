package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.api.ModelResource.AttributeResource;
import com.diagramador.api.ModelResource.RelationshipEndResource;
import com.diagramador.command.Author;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.ErrorCode;
import com.diagramador.command.ModelStateHasher;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Target;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.UmlModel;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UserAccountRepository;
import com.diagramador.vcs.ModelSnapshotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/** T5: attribute isStatic / isDerived and relationship-end isOrdered / isUnique (S7a). */
@SpringBootTest
class AttributeFlagsAndEndPropertiesTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Map<String, Object> STRING_TYPE = Map.of("primitiveName", "String");

    private final CommandService commandService;
    private final UserAccountRepository userAccountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UmlModelRepository modelRepository;
    private final UmlAttributeRepository attributeRepository;
    private final RelationshipEndRepository endRepository;
    private final ModelStateHasher hasher;
    private final ModelSnapshotService snapshotService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    AttributeFlagsAndEndPropertiesTest(CommandService commandService, UserAccountRepository userAccountRepository,
            ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository,
            UmlModelRepository modelRepository, UmlAttributeRepository attributeRepository,
            RelationshipEndRepository endRepository, ModelStateHasher hasher, ModelSnapshotService snapshotService,
            ObjectMapper objectMapper, TransactionTemplate transactionTemplate) {
        this.commandService = commandService;
        this.userAccountRepository = userAccountRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.modelRepository = modelRepository;
        this.attributeRepository = attributeRepository;
        this.endRepository = endRepository;
        this.hasher = hasher;
        this.snapshotService = snapshotService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Test
    void anAttributeIsAddedWithTheFlagsItWasSentAndFalseByDefault() {
        Fixture fixture = fixture();
        UUID classifierId = createClass(fixture);
        UUID plain = UUID.randomUUID();
        UUID marked = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add",
                Map.of("id", plain, "name", "plain", "type", STRING_TYPE), target("classifier", classifierId)));
        assertAccepted(command(fixture, "attribute.add",
                Map.of("id", marked, "name", "marked", "type", STRING_TYPE, "isStatic", true, "isDerived", true),
                target("classifier", classifierId)));

        assertThat(attributeRepository.findById(plain).orElseThrow()).satisfies(attribute -> {
            assertThat(attribute.isStatic()).isFalse();
            assertThat(attribute.isDerived()).isFalse();
        });
        assertThat(attributeRepository.findById(marked).orElseThrow()).satisfies(attribute -> {
            assertThat(attribute.isStatic()).isTrue();
            assertThat(attribute.isDerived()).isTrue();
        });
        assertThat(resource(fixture, marked)).satisfies(attribute -> {
            assertThat(attribute.isStatic()).isTrue();
            assertThat(attribute.isDerived()).isTrue();
        });
        assertThat(resource(fixture, plain)).satisfies(attribute -> {
            assertThat(attribute.isStatic()).isFalse();
            assertThat(attribute.isDerived()).isFalse();
        });
    }

    @Test
    void anAttributeUpdateChangesOnlyTheFlagsSentAndAnOmittedFlagIsUnchanged() {
        Fixture fixture = fixture();
        UUID classifierId = createClass(fixture);
        UUID attributeId = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add", Map.of("id", attributeId, "name", "a", "type", STRING_TYPE,
                "isStatic", true), target("classifier", classifierId)));

        assertAccepted(command(fixture, "attribute.update", Map.of("isDerived", true),
                target("attribute", attributeId)));
        assertThat(attributeRepository.findById(attributeId).orElseThrow()).satisfies(attribute -> {
            assertThat(attribute.isStatic()).as("omitted: unchanged").isTrue();
            assertThat(attribute.isDerived()).isTrue();
        });

        assertAccepted(command(fixture, "attribute.update", Map.of("name", "renamed"),
                target("attribute", attributeId)));
        assertThat(attributeRepository.findById(attributeId).orElseThrow()).satisfies(attribute -> {
            assertThat(attribute.getName()).isEqualTo("renamed");
            assertThat(attribute.isStatic()).isTrue();
            assertThat(attribute.isDerived()).isTrue();
        });

        assertAccepted(command(fixture, "attribute.update", Map.of("isStatic", false, "isDerived", false),
                target("attribute", attributeId)));
        assertThat(attributeRepository.findById(attributeId).orElseThrow()).satisfies(attribute -> {
            assertThat(attribute.isStatic()).isFalse();
            assertThat(attribute.isDerived()).isFalse();
        });
    }

    @Test
    void aNonBooleanAttributeFlagIsRejectedAndLeavesTheModelUntouched() {
        Fixture fixture = fixture();
        UUID classifierId = createClass(fixture);
        UUID attributeId = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add", Map.of("id", attributeId, "name", "a", "type", STRING_TYPE),
                target("classifier", classifierId)));
        String before = hasher.hash(modelOf(fixture));

        for (String flag : List.of("isStatic", "isDerived")) {
            for (Object bad : new Object[] {"true", 1, "yes"}) {
                assertRejected(command(fixture, "attribute.add", Map.of("id", UUID.randomUUID(), "name", "b",
                        "type", STRING_TYPE, flag, bad), target("classifier", classifierId)),
                        ErrorCode.INVALID_PAYLOAD, flag);
                assertRejected(command(fixture, "attribute.update", Map.of(flag, bad),
                        target("attribute", attributeId)), ErrorCode.INVALID_PAYLOAD, flag);
            }
            assertRejected(command(fixture, "attribute.update", java.util.Collections.singletonMap(flag, null),
                    target("attribute", attributeId)), ErrorCode.INVALID_PAYLOAD, flag);
        }

        assertThat(hasher.hash(modelOf(fixture))).isEqualTo(before);
        assertThat(attributeRepository.findById(attributeId).orElseThrow()).satisfies(attribute -> {
            assertThat(attribute.isStatic()).isFalse();
            assertThat(attribute.isDerived()).isFalse();
        });
    }

    @Test
    void aRelationshipIsCreatedWithTheEndPropertiesItWasSentAndFalseByDefault() {
        Fixture fixture = fixture();
        UUID source = createClass(fixture);
        UUID target = createClass(fixture);
        UUID plain = UUID.randomUUID();
        UUID marked = UUID.randomUUID();
        assertAccepted(command(fixture, "relationship.create", Map.of("id", plain, "kind", "ASSOCIATION",
                "source", Map.of("classifierId", source), "target", Map.of("classifierId", target)),
                List.of(new Target("classifier", source), new Target("classifier", target))));
        assertAccepted(command(fixture, "relationship.create", Map.of("id", marked, "kind", "ASSOCIATION",
                "source", Map.of("classifierId", source, "isOrdered", true),
                "target", Map.of("classifierId", target, "isUnique", true, "isOrdered", false)),
                List.of(new Target("classifier", source), new Target("classifier", target))));

        for (RelationshipEnd end : endRepository.findByRelationshipId(plain)) {
            assertThat(end.isOrdered()).isFalse();
            assertThat(end.isUnique()).isFalse();
        }
        RelationshipEnd first = endOf(marked, 1);
        RelationshipEnd second = endOf(marked, 2);
        assertThat(first.isOrdered()).isTrue();
        assertThat(first.isUnique()).isFalse();
        assertThat(second.isOrdered()).isFalse();
        assertThat(second.isUnique()).isTrue();

        List<RelationshipEndResource> ends = snapshotService.capture(modelOf(fixture)).relationships().stream()
                .filter(relationship -> relationship.id().equals(marked)).findFirst().orElseThrow().ends();
        assertThat(ends).extracting(RelationshipEndResource::isOrdered).containsExactly(true, false);
        assertThat(ends).extracting(RelationshipEndResource::isUnique).containsExactly(false, true);
    }

    @Test
    void aRelationshipUpdateChangesOnlyTheEndPropertiesSentAndKeepsTheRest() {
        Fixture fixture = fixture();
        UUID source = createClass(fixture);
        UUID target = createClass(fixture);
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(command(fixture, "relationship.create", Map.of("id", relationshipId, "kind", "ASSOCIATION",
                "source", Map.of("classifierId", source, "isUnique", true, "role", "owner"),
                "target", Map.of("classifierId", target)),
                List.of(new Target("classifier", source), new Target("classifier", target))));

        assertAccepted(command(fixture, "relationship.update", Map.of("target", Map.of("isOrdered", true)),
                target("relationship", relationshipId)));
        assertThat(endOf(relationshipId, 1)).satisfies(end -> {
            assertThat(end.isUnique()).as("source untouched").isTrue();
            assertThat(end.isOrdered()).isFalse();
            assertThat(end.getRole()).isEqualTo("owner");
        });
        assertThat(endOf(relationshipId, 2)).satisfies(end -> {
            assertThat(end.isOrdered()).isTrue();
            assertThat(end.isUnique()).isFalse();
        });

        assertAccepted(command(fixture, "relationship.update", Map.of("source", Map.of("role", "renamed")),
                target("relationship", relationshipId)));
        assertThat(endOf(relationshipId, 1)).satisfies(end -> {
            assertThat(end.getRole()).isEqualTo("renamed");
            assertThat(end.isUnique()).as("omitted: unchanged").isTrue();
        });

        assertAccepted(command(fixture, "relationship.update", Map.of("source", Map.of("isUnique", false),
                "target", Map.of("isOrdered", false, "isUnique", true)), target("relationship", relationshipId)));
        assertThat(endOf(relationshipId, 1).isUnique()).isFalse();
        assertThat(endOf(relationshipId, 2)).satisfies(end -> {
            assertThat(end.isOrdered()).isFalse();
            assertThat(end.isUnique()).isTrue();
        });
    }

    @Test
    void aNonBooleanEndPropertyIsRejectedAndLeavesTheModelUntouched() {
        Fixture fixture = fixture();
        UUID source = createClass(fixture);
        UUID target = createClass(fixture);
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(command(fixture, "relationship.create", Map.of("id", relationshipId, "kind", "ASSOCIATION",
                "source", Map.of("classifierId", source), "target", Map.of("classifierId", target)),
                List.of(new Target("classifier", source), new Target("classifier", target))));
        String before = hasher.hash(modelOf(fixture));

        for (String property : List.of("isOrdered", "isUnique")) {
            assertRejected(command(fixture, "relationship.create", Map.of("id", UUID.randomUUID(),
                    "kind", "ASSOCIATION", "source", Map.of("classifierId", source, property, "true"),
                    "target", Map.of("classifierId", target)),
                    List.of(new Target("classifier", source), new Target("classifier", target))),
                    ErrorCode.INVALID_PAYLOAD, property);
            assertRejected(command(fixture, "relationship.update", Map.of("target", Map.of(property, 1)),
                    target("relationship", relationshipId)), ErrorCode.INVALID_PAYLOAD, property);
            assertRejected(command(fixture, "relationship.update",
                    Map.of("source", java.util.Collections.singletonMap(property, null)),
                    target("relationship", relationshipId)), ErrorCode.INVALID_PAYLOAD, property);
        }

        assertThat(hasher.hash(modelOf(fixture))).isEqualTo(before);
        for (RelationshipEnd end : endRepository.findByRelationshipId(relationshipId)) {
            assertThat(end.isOrdered()).isFalse();
            assertThat(end.isUnique()).isFalse();
        }
    }

    @Test
    void theEndPropertiesDoNotChangeWhatTheEndAlreadyDid() {
        Fixture fixture = fixture();
        UUID source = createClass(fixture);
        UUID target = createClass(fixture);
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(command(fixture, "relationship.create", Map.of("id", relationshipId, "kind", "ASSOCIATION",
                "source", Map.of("classifierId", source, "role", "a", "multiplicity", "ZERO_OR_MANY"),
                "target", Map.of("classifierId", target, "navigability", "NAVIGABLE")),
                List.of(new Target("classifier", source), new Target("classifier", target))));
        assertAccepted(command(fixture, "relationship.update", Map.of("source", Map.of("role", "b")),
                target("relationship", relationshipId)));
        assertRejected(command(fixture, "relationship.update", Map.of("source", Map.of("color", "red")),
                target("relationship", relationshipId)), ErrorCode.INVALID_PAYLOAD, "color");

        assertThat(endOf(relationshipId, 1)).satisfies(end -> {
            assertThat(end.getRole()).isEqualTo("b");
            assertThat(end.getMultiplicity().name()).isEqualTo("ZERO_OR_MANY");
            assertThat(end.isOrdered()).isFalse();
            assertThat(end.isUnique()).isFalse();
        });
        assertThat(endOf(relationshipId, 2).getNavigability().name()).isEqualTo("NAVIGABLE");
    }

    @Test
    void theStateHashChangesOnlyWhileAFlagIsTrueSoOldHashesStay() {
        Fixture fixture = fixture();
        UUID source = createClass(fixture);
        UUID target = createClass(fixture);
        UUID attributeId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add", Map.of("id", attributeId, "name", "a", "type", STRING_TYPE),
                target("classifier", source)));
        assertAccepted(command(fixture, "relationship.create", Map.of("id", relationshipId, "kind", "ASSOCIATION",
                "source", Map.of("classifierId", source), "target", Map.of("classifierId", target)),
                List.of(new Target("classifier", source), new Target("classifier", target))));
        String unmarked = hasher.hash(modelOf(fixture));

        for (OperationFlag flag : List.of(
                new OperationFlag("attribute.update", "attribute", attributeId, null, "isStatic"),
                new OperationFlag("attribute.update", "attribute", attributeId, null, "isDerived"),
                new OperationFlag("relationship.update", "relationship", relationshipId, "source", "isOrdered"),
                new OperationFlag("relationship.update", "relationship", relationshipId, "target", "isUnique"))) {
            assertAccepted(command(fixture, flag.type(), flag.payload(true), target(flag.scope(), flag.id())));
            String marked = hasher.hash(modelOf(fixture));
            assertThat(marked).as(flag.field() + " true").isNotEqualTo(unmarked);
            assertAccepted(command(fixture, flag.type(), flag.payload(false), target(flag.scope(), flag.id())));
            assertThat(hasher.hash(modelOf(fixture))).as(flag.field() + " back to false").isEqualTo(unmarked);
        }
    }

    @Test
    void theResourcesSerializeTheFlagsAndOldJsonWithoutThemReadsFalse() throws Exception {
        UUID id = UUID.randomUUID();
        AttributeResource attribute = new AttributeResource(id, "a", "ATTRIBUTE", "PUBLIC", null, null, null, "String",
                0, null, null, null, true, false);
        JsonNode attributeJson = objectMapper.valueToTree(attribute);
        assertThat(attributeJson.get("isStatic").asBoolean()).isTrue();
        assertThat(attributeJson.get("isDerived").asBoolean()).isFalse();

        ((com.fasterxml.jackson.databind.node.ObjectNode) attributeJson).remove(List.of("isStatic", "isDerived"));
        AttributeResource oldAttribute = objectMapper.treeToValue(attributeJson, AttributeResource.class);
        assertThat(oldAttribute.isStatic()).isFalse();
        assertThat(oldAttribute.isDerived()).isFalse();
        assertThat(oldAttribute.name()).isEqualTo("a");

        RelationshipEndResource end = new RelationshipEndResource(id, id, "r", null, null, "none", (short) 1, null,
                null, false, true);
        JsonNode endJson = objectMapper.valueToTree(end);
        assertThat(endJson.get("isOrdered").asBoolean()).isFalse();
        assertThat(endJson.get("isUnique").asBoolean()).isTrue();

        ((com.fasterxml.jackson.databind.node.ObjectNode) endJson).remove(List.of("isOrdered", "isUnique"));
        RelationshipEndResource oldEnd = objectMapper.treeToValue(endJson, RelationshipEndResource.class);
        assertThat(oldEnd.isOrdered()).isFalse();
        assertThat(oldEnd.isUnique()).isFalse();
        assertThat(oldEnd.role()).isEqualTo("r");
    }

    private record OperationFlag(String type, String scope, UUID id, String endField, String field) {
        Map<String, Object> payload(boolean value) {
            return endField == null ? Map.of(field, value) : Map.of(endField, Map.of(field, value));
        }
    }

    private AttributeResource resource(Fixture fixture, UUID attributeId) {
        return snapshotService.capture(modelOf(fixture)).classifiers().stream()
                .flatMap(classifier -> classifier.attributes().stream())
                .filter(attribute -> attribute.id().equals(attributeId)).findFirst().orElseThrow();
    }

    private RelationshipEnd endOf(UUID relationshipId, int ordinal) {
        return endRepository.findByRelationshipId(relationshipId).stream()
                .filter(end -> end.getOrdinal() == ordinal).findFirst().orElseThrow();
    }

    private UUID createClass(Fixture fixture) {
        UUID id = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create",
                Map.of("id", id, "name", "C" + id.toString().substring(0, 8), "kind", "CLASS"), List.of()));
        return id;
    }

    private UmlModel modelOf(Fixture fixture) {
        return modelRepository.findById(fixture.model().getId()).orElseThrow();
    }

    /** Executes at the model's current revision, so a rejection never leaves the next command stale. */
    private CommandResult command(Fixture fixture, String type, Object payload, List<Target> targets) {
        return commandService.execute(new OperationEnvelope(UUID.randomUUID(), type, fixture.author(),
                fixture.project().getId(), fixture.model().getId(), modelOf(fixture).getCurrentRevision(), targets,
                List.of(), payload, CommandOrigin.MANUAL, null, UUID.randomUUID().toString()));
    }

    private void assertAccepted(CommandResult result) {
        assertThat(result.status()).withFailMessage("diagnostics: %s", result.diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
    }

    private void assertRejected(CommandResult result, ErrorCode code, String... fragments) {
        assertThat(result.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(code);
            assertThat(diagnostic.message()).contains(fragments);
        });
    }

    private List<Target> target(String scope, UUID id) {
        return List.of(new Target(scope, id));
    }

    private Fixture fixture() {
        return transactionTemplate.execute(status -> {
            com.diagramador.domain.UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            com.diagramador.domain.UserAccount author = userAccountRepository.save(
                    com.diagramador.domain.UserAccount.create("fl-" + UUID.randomUUID(), "Fl",
                            "fl-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "Flags", "commands"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "Flags Model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            projectMemberRepository.save(ProjectMember.create(project, author, ProjectMemberRole.EDITOR));
            return new Fixture(project, model, new Author(author.getId(), "fl"));
        });
    }

    private record Fixture(Project project, UmlModel model, Author author) {
    }
}
