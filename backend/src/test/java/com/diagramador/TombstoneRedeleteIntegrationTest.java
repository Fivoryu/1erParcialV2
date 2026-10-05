package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.command.Author;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Precondition;
import com.diagramador.command.Target;
import com.diagramador.domain.CascadeGroup;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.Tombstone;
import com.diagramador.domain.UmlModel;
import com.diagramador.persistence.CascadeGroupRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.TombstoneRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * IA4 acceptance-harness fix: undoing a deletion resurrects the element, and a later re-deletion of
 * that same element used to crash with MODEL_INVALID because uq_tombstone_target made tombstones
 * unique per (target_type, target_id) FOREVER. V13 scopes that uniqueness to the cascade group, so
 * re-deleting a recreated element is legal while a single cascade still cannot double-tombstone.
 */
@SpringBootTest
class TombstoneRedeleteIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final CommandService commandService;
    private final UserAccountRepository userAccountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UmlModelRepository modelRepository;
    private final CascadeGroupRepository cascadeGroupRepository;
    private final TombstoneRepository tombstoneRepository;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    TombstoneRedeleteIntegrationTest(CommandService commandService, UserAccountRepository userAccountRepository,
            ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository,
            UmlModelRepository modelRepository, CascadeGroupRepository cascadeGroupRepository,
            TombstoneRepository tombstoneRepository, TransactionTemplate transactionTemplate) {
        this.commandService = commandService;
        this.userAccountRepository = userAccountRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.modelRepository = modelRepository;
        this.cascadeGroupRepository = cascadeGroupRepository;
        this.tombstoneRepository = tombstoneRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Test
    void deletingARecreatedClassifierWithTheSameIdIsAcceptedInsteadOfModelInvalid() {
        Fixture fixture = fixture("redelete");
        UUID classifierId = UUID.randomUUID();

        assertAccepted(command(fixture, "classifier.create", 0,
                Map.of("id", classifierId, "name", "Cliente", "kind", "CLASS"), List.of(), List.of()));
        assertAccepted(command(fixture, "classifier.delete", 1, Map.of(),
                List.of(new Target("classifier", classifierId)),
                List.of(new Precondition("cascade_count", 1, null))));
        // Recreate with the SAME client-generated id (what the undo path effectively does).
        assertAccepted(command(fixture, "classifier.create", 2,
                Map.of("id", classifierId, "name", "Cliente", "kind", "CLASS"), List.of(), List.of()));

        CommandResult redelete = command(fixture, "classifier.delete", 3, Map.of(),
                List.of(new Target("classifier", classifierId)),
                List.of(new Precondition("cascade_count", 1, null)));

        assertAccepted(redelete);
        List<CascadeGroup> cascades = cascadeGroupRepository.findByModelId(fixture.model().getId());
        assertThat(cascades).hasSize(2);
        List<Tombstone> tombstones = tombstoneRepository.findByModelId(fixture.model().getId());
        // Two tombstones for the SAME target, one per cascade group: legal after V13.
        assertThat(tombstones).hasSize(2);
        assertThat(tombstones).allSatisfy(tombstone -> {
            assertThat(tombstone.getTargetId()).isEqualTo(classifierId);
        });
        assertThat(tombstones.stream().map(tombstone -> tombstone.getCascade().getCascadeId()).distinct())
                .hasSize(2);
    }

    private CommandResult command(Fixture fixture, String type, long revision, Object payload,
            List<Target> targets, List<Precondition> preconditions) {
        return commandService.execute(new OperationEnvelope(UUID.randomUUID(), type, fixture.author(),
                fixture.project().getId(), fixture.model().getId(), revision, targets, preconditions, payload,
                CommandOrigin.MANUAL, null, UUID.randomUUID().toString()));
    }

    private void assertAccepted(CommandResult result) {
        assertThat(result.status()).withFailMessage("diagnostics: %s", result.diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
    }

    private Fixture fixture(String label) {
        return transactionTemplate.execute(status -> {
            com.diagramador.domain.UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            com.diagramador.domain.UserAccount author = userAccountRepository.save(
                    com.diagramador.domain.UserAccount.create("tombstone-" + UUID.randomUUID(), "Tombstone",
                            "tombstone-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "Tombstone " + label, "redelete tests"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "Tombstone Model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            projectMemberRepository.save(ProjectMember.create(project, author, ProjectMemberRole.EDITOR));
            return new Fixture(project, model, new Author(author.getId(), "tombstone"));
        });
    }

    private record Fixture(Project project, UmlModel model, Author author) {
    }
}
