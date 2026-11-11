package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.api.ModelResource.NoteLinkResource;
import com.diagramador.api.ModelResource.NoteResource;
import com.diagramador.api.NoteResourceAssembler;
import com.diagramador.command.Author;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.ErrorCode;
import com.diagramador.command.ModelStateHasher;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Precondition;
import com.diagramador.command.Target;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlModel;
import com.diagramador.persistence.ElementGeometryRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.TombstoneRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlNoteLinkRepository;
import com.diagramador.persistence.UmlNoteRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/** T4: UML notes (S6a): note commands, links to classifiers and relationships, canvas placement and deletion. */
@SpringBootTest
class NoteCommandHandlersTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final CommandService commandService;
    private final UserAccountRepository userAccountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UmlModelRepository modelRepository;
    private final UmlNoteRepository noteRepository;
    private final UmlNoteLinkRepository linkRepository;
    private final ElementGeometryRepository geometryRepository;
    private final TombstoneRepository tombstoneRepository;
    private final NoteResourceAssembler noteResources;
    private final ModelStateHasher hasher;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    NoteCommandHandlersTest(CommandService commandService, UserAccountRepository userAccountRepository,
            ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository,
            UmlModelRepository modelRepository, UmlNoteRepository noteRepository,
            UmlNoteLinkRepository linkRepository, ElementGeometryRepository geometryRepository,
            TombstoneRepository tombstoneRepository, NoteResourceAssembler noteResources, ModelStateHasher hasher,
            TransactionTemplate transactionTemplate) {
        this.commandService = commandService;
        this.userAccountRepository = userAccountRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.modelRepository = modelRepository;
        this.noteRepository = noteRepository;
        this.linkRepository = linkRepository;
        this.geometryRepository = geometryRepository;
        this.tombstoneRepository = tombstoneRepository;
        this.noteResources = noteResources;
        this.hasher = hasher;
        this.transactionTemplate = transactionTemplate;
    }

    @Test
    void aNoteIsCreatedWithItsAnnotatedClassifierAndRelationshipAndReadBack() {
        Fixture fixture = fixture();
        UUID first = createClass(fixture);
        UUID second = createClass(fixture);
        UUID relationship = createRelationship(fixture, first, second);
        UUID noteId = UUID.randomUUID();

        assertAccepted(command(fixture, "note.create",
                Map.of("id", noteId, "body", "Remember the audit trail\nsecond line",
                        "annotatedElementIds", List.of(first.toString(), relationship.toString())),
                List.of()));

        assertThat(noteRepository.findById(noteId).orElseThrow().getBody())
                .isEqualTo("Remember the audit trail\nsecond line");
        assertThat(noteResources.forModel(fixture.model().getId())).singleElement().satisfies(note -> {
            assertThat(note.id()).isEqualTo(noteId);
            assertThat(note.body()).isEqualTo("Remember the audit trail\nsecond line");
            assertThat(note.annotatedElements()).containsExactlyInAnyOrder(
                    new NoteLinkResource(TargetType.CLASSIFIER, first),
                    new NoteLinkResource(TargetType.RELATIONSHIP, relationship));
        });
    }

    @Test
    void aNoteWithoutAnnotatedElementsOrAnIdIsAccepted() {
        Fixture fixture = fixture();

        assertAccepted(command(fixture, "note.create", Map.of("body", "Free floating"), List.of()));

        assertThat(noteResources.forModel(fixture.model().getId())).singleElement().satisfies(note -> {
            assertThat(note.body()).isEqualTo("Free floating");
            assertThat(note.annotatedElements()).isEmpty();
        });
    }

    @Test
    void noteCreateRejectionsAreInvalidPayloadAndLeaveTheModelHashUntouched() {
        Fixture fixture = fixture();
        UUID classifier = createClass(fixture);
        String before = hasher.hash(modelOf(fixture));

        assertRejected(command(fixture, "note.create", Map.of("id", UUID.randomUUID()), List.of()),
                ErrorCode.INVALID_PAYLOAD, "body");
        assertRejected(command(fixture, "note.create", Map.of("id", UUID.randomUUID(), "body", "   "), List.of()),
                ErrorCode.INVALID_PAYLOAD, "body");
        assertRejected(command(fixture, "note.create", Map.of("id", UUID.randomUUID(), "body", "x".repeat(2001)),
                List.of()), ErrorCode.INVALID_PAYLOAD, "2000");
        assertRejected(command(fixture, "note.create",
                Map.of("id", UUID.randomUUID(), "body", "b", "annotatedElementIds", "not-a-list"), List.of()),
                ErrorCode.INVALID_PAYLOAD, "annotatedElementIds");
        assertRejected(command(fixture, "note.create",
                Map.of("id", UUID.randomUUID(), "body", "b", "annotatedElementIds", List.of("nope")), List.of()),
                ErrorCode.INVALID_PAYLOAD, "UUID");
        assertRejected(command(fixture, "note.create",
                Map.of("id", UUID.randomUUID(), "body", "b",
                        "annotatedElementIds", List.of(classifier.toString(), classifier.toString())), List.of()),
                ErrorCode.INVALID_PAYLOAD, "twice");
        assertRejected(command(fixture, "note.create",
                Map.of("id", UUID.randomUUID(), "body", "b", "color", "red"), List.of()),
                ErrorCode.INVALID_PAYLOAD, "color");

        assertThat(noteRepository.findByModelId(fixture.model().getId())).isEmpty();
        assertThat(hasher.hash(modelOf(fixture))).isEqualTo(before);
    }

    @Test
    void anAnnotatedElementThatIsNotInTheModelFailsThePrecondition() {
        Fixture fixture = fixture();
        Fixture other = fixture();
        UUID foreign = createClass(other);
        UUID missing = UUID.randomUUID();
        String before = hasher.hash(modelOf(fixture));

        assertRejected(command(fixture, "note.create",
                Map.of("id", UUID.randomUUID(), "body", "b", "annotatedElementIds", List.of(missing.toString())),
                List.of()), ErrorCode.PRECONDITION_FAILED, missing.toString());
        assertRejected(command(fixture, "note.create",
                Map.of("id", UUID.randomUUID(), "body", "b", "annotatedElementIds", List.of(foreign.toString())),
                List.of()), ErrorCode.PRECONDITION_FAILED, foreign.toString());

        assertThat(noteRepository.findByModelId(fixture.model().getId())).isEmpty();
        assertThat(hasher.hash(modelOf(fixture))).isEqualTo(before);
    }

    @Test
    void aDuplicateNoteIdIsRejected() {
        Fixture fixture = fixture();
        UUID noteId = createNote(fixture, "first");

        assertRejected(command(fixture, "note.create", Map.of("id", noteId, "body", "second"), List.of()),
                ErrorCode.PRECONDITION_FAILED, noteId.toString());
        assertThat(noteRepository.findById(noteId).orElseThrow().getBody()).isEqualTo("first");
    }

    @Test
    void noteUpdateReplacesTheBodyAndRejectsAnInvalidOne() {
        Fixture fixture = fixture();
        UUID noteId = createNote(fixture, "before");

        assertAccepted(command(fixture, "note.update", Map.of("body", "after"), target("note", noteId)));
        assertThat(noteRepository.findById(noteId).orElseThrow().getBody()).isEqualTo("after");
        String hash = hasher.hash(modelOf(fixture));

        assertRejected(command(fixture, "note.update", Map.of("body", ""), target("note", noteId)),
                ErrorCode.INVALID_PAYLOAD, "body");
        assertRejected(command(fixture, "note.update", Map.of(), target("note", noteId)),
                ErrorCode.INVALID_PAYLOAD, "body");
        assertRejected(command(fixture, "note.update", Map.of("body", "x".repeat(2001)), target("note", noteId)),
                ErrorCode.INVALID_PAYLOAD, "2000");
        assertRejected(command(fixture, "note.update", Map.of("body", "b", "extra", 1), target("note", noteId)),
                ErrorCode.INVALID_PAYLOAD, "extra");
        assertRejected(command(fixture, "note.update", Map.of("body", "b"), target("note", UUID.randomUUID())),
                ErrorCode.PRECONDITION_FAILED, "Note");
        assertThat(noteRepository.findById(noteId).orElseThrow().getBody()).isEqualTo("after");
        assertThat(hasher.hash(modelOf(fixture))).isEqualTo(hash);
    }

    @Test
    void linkAndUnlinkAddAndRemoveAnnotatedElementsWithoutDuplicates() {
        Fixture fixture = fixture();
        UUID first = createClass(fixture);
        UUID second = createClass(fixture);
        UUID relationship = createRelationship(fixture, first, second);
        UUID noteId = createNote(fixture, "n");

        assertAccepted(command(fixture, "note.link", Map.of("targetType", "classifier", "targetId", first),
                target("note", noteId)));
        assertAccepted(command(fixture, "note.link", Map.of("targetType", "RELATIONSHIP", "targetId", relationship),
                target("note", noteId)));
        assertThat(links(fixture, noteId)).containsExactlyInAnyOrder(
                new NoteLinkResource(TargetType.CLASSIFIER, first),
                new NoteLinkResource(TargetType.RELATIONSHIP, relationship));
        String linked = hasher.hash(modelOf(fixture));

        assertRejected(command(fixture, "note.link", Map.of("targetType", "CLASSIFIER", "targetId", first),
                target("note", noteId)), ErrorCode.PRECONDITION_FAILED, "already annotates");
        assertRejected(command(fixture, "note.link", Map.of("targetType", "RELATIONSHIP", "targetId", first),
                target("note", noteId)), ErrorCode.PRECONDITION_FAILED, "not a RELATIONSHIP");
        assertRejected(command(fixture, "note.link", Map.of("targetType", "ATTRIBUTE", "targetId", first),
                target("note", noteId)), ErrorCode.INVALID_PAYLOAD, "ATTRIBUTE");
        assertRejected(command(fixture, "note.link", Map.of("targetType", "CLASSIFIER"), target("note", noteId)),
                ErrorCode.INVALID_PAYLOAD, "targetId");
        assertRejected(command(fixture, "note.link",
                Map.of("targetType", "CLASSIFIER", "targetId", UUID.randomUUID()), target("note", noteId)),
                ErrorCode.PRECONDITION_FAILED, "neither a classifier nor a relationship");
        assertThat(hasher.hash(modelOf(fixture))).isEqualTo(linked);

        assertAccepted(command(fixture, "note.unlink", Map.of("targetType", "CLASSIFIER", "targetId", first),
                target("note", noteId)));
        assertThat(links(fixture, noteId)).containsExactly(new NoteLinkResource(TargetType.RELATIONSHIP, relationship));
        assertRejected(command(fixture, "note.unlink", Map.of("targetType", "CLASSIFIER", "targetId", first),
                target("note", noteId)), ErrorCode.PRECONDITION_FAILED, "does not annotate");
        assertRejected(command(fixture, "note.unlink", Map.of("targetType", "CLASSIFIER", "targetId", first),
                target("classifier", noteId)), ErrorCode.INVALID_PAYLOAD, "scope");
    }

    @Test
    void noteDeleteRemovesItsLinksAndPlacementsAndLeavesATombstone() {
        Fixture fixture = fixture();
        UUID classifier = createClass(fixture);
        UUID noteId = UUID.randomUUID();
        assertAccepted(command(fixture, "note.create",
                Map.of("id", noteId, "body", "n", "annotatedElementIds", List.of(classifier.toString())), List.of()));
        UUID viewId = defaultViewId(fixture);
        assertAccepted(command(fixture, "geometry.set",
                Map.of("targetType", "NOTE", "viewId", viewId, "targetId", noteId, "x", 5, "y", 6, "w", 120, "h", 60),
                List.of()));
        assertThat(geometryRepository.findByViewId(viewId)).anySatisfy(geometry -> {
            assertThat(geometry.getTargetType()).isEqualTo(TargetType.NOTE);
            assertThat(geometry.getTargetId()).isEqualTo(noteId);
        });

        assertAccepted(command(fixture, "note.delete", Map.of(), target("note", noteId)));

        assertThat(noteRepository.findById(noteId)).isEmpty();
        assertThat(linkRepository.findByKeyNoteId(noteId)).isEmpty();
        assertThat(geometryRepository.findByViewId(viewId)).noneMatch(geometry -> geometry.getTargetId().equals(noteId));
        assertThat(tombstoneRepository.findByModelId(fixture.model().getId())).anySatisfy(tombstone -> {
            assertThat(tombstone.getTargetType()).isEqualTo(TargetType.NOTE);
            assertThat(tombstone.getTargetId()).isEqualTo(noteId);
        });
        assertRejected(command(fixture, "note.delete", Map.of(), target("note", noteId)),
                ErrorCode.PRECONDITION_FAILED, "Note");
    }

    @Test
    void deletingAClassifierOrARelationshipRemovesItsNoteLinksButKeepsTheNote() {
        Fixture fixture = fixture();
        UUID first = createClass(fixture);
        UUID second = createClass(fixture);
        UUID third = createClass(fixture);
        UUID relationship = createRelationship(fixture, first, second);
        UUID viaRelationship = createRelationship(fixture, second, third);
        UUID noteId = UUID.randomUUID();
        assertAccepted(command(fixture, "note.create",
                Map.of("id", noteId, "body", "n", "annotatedElementIds",
                        List.of(first.toString(), second.toString(), third.toString(), relationship.toString(),
                                viaRelationship.toString())),
                List.of()));

        assertAccepted(command(fixture, "relationship.delete", Map.of(), target("relationship", viaRelationship),
                List.of(new Precondition("cascade_count", 3, null))));
        assertThat(links(fixture, noteId)).containsExactlyInAnyOrder(
                new NoteLinkResource(TargetType.CLASSIFIER, first),
                new NoteLinkResource(TargetType.CLASSIFIER, second),
                new NoteLinkResource(TargetType.CLASSIFIER, third),
                new NoteLinkResource(TargetType.RELATIONSHIP, relationship));

        // Deleting the first classifier cascades to the relationship it ends: both links go, the others stay.
        assertAccepted(command(fixture, "classifier.delete", Map.of(), target("classifier", first),
                List.of(new Precondition("cascade_count", 4, null))));
        assertThat(noteRepository.findById(noteId)).isPresent();
        assertThat(links(fixture, noteId)).containsExactlyInAnyOrder(
                new NoteLinkResource(TargetType.CLASSIFIER, second),
                new NoteLinkResource(TargetType.CLASSIFIER, third));
    }

    @Test
    void aNoteGetsCanvasGeometryThroughGeometrySetAndRemove() {
        Fixture fixture = fixture();
        UUID noteId = createNote(fixture, "n");
        UUID viewId = defaultViewId(fixture);
        Map<String, Object> place = Map.of("targetType", "NOTE", "viewId", viewId, "targetId", noteId, "x", 10,
                "y", 20, "w", 140, "h", 70, "zOrder", 3);

        assertAccepted(command(fixture, "geometry.set", place, List.of()));
        assertThat(geometryRepository.findByViewId(viewId)).filteredOn(geometry -> geometry.getTargetId().equals(noteId))
                .singleElement().satisfies(geometry -> {
                    assertThat(geometry.getTargetType()).isEqualTo(TargetType.NOTE);
                    assertThat(geometry.getX()).isEqualTo(10);
                    assertThat(geometry.getY()).isEqualTo(20);
                    assertThat(geometry.getW()).isEqualTo(140);
                    assertThat(geometry.getH()).isEqualTo(70);
                    assertThat(geometry.getZOrder()).isEqualTo(3);
                });

        assertAccepted(command(fixture, "geometry.set", Map.of("targetType", "note", "viewId", viewId,
                "targetId", noteId, "x", 11, "y", 21, "w", 141, "h", 71), List.of()));
        assertThat(geometryRepository.findByViewId(viewId)).filteredOn(geometry -> geometry.getTargetId().equals(noteId))
                .singleElement().satisfies(geometry -> {
                    assertThat(geometry.getX()).as("an upsert, not a second row").isEqualTo(11);
                    assertThat(geometry.getZOrder()).as("zOrder kept when omitted").isEqualTo(3);
                });

        String placed = hasher.hash(modelOf(fixture));
        assertRejected(command(fixture, "geometry.set", Map.of("targetType", "NOTE", "viewId", viewId,
                "targetId", noteId, "x", 1, "y", 1, "w", 0, "h", 5), List.of()), ErrorCode.INVALID_PAYLOAD, "w and h");
        assertRejected(command(fixture, "geometry.set", Map.of("targetType", "NOTE", "viewId", viewId,
                "targetId", noteId, "x", 1, "y", 1, "w", 5, "h", 5, "waypoints", List.of()), List.of()),
                ErrorCode.INVALID_PAYLOAD, "waypoints");
        assertRejected(command(fixture, "geometry.set", Map.of("targetType", "NOTE", "viewId", viewId,
                "targetId", UUID.randomUUID(), "x", 1, "y", 1, "w", 5, "h", 5), List.of()),
                ErrorCode.PRECONDITION_FAILED, "Note");
        assertRejected(command(fixture, "geometry.set", Map.of("targetType", "ATTRIBUTE", "viewId", viewId,
                "targetId", noteId, "x", 1, "y", 1, "w", 5, "h", 5), List.of()), ErrorCode.INVALID_PAYLOAD, "NOTE");
        assertThat(hasher.hash(modelOf(fixture))).isEqualTo(placed);

        assertAccepted(command(fixture, "geometry.remove",
                Map.of("targetType", "NOTE", "viewId", viewId, "targetId", noteId), List.of()));
        assertThat(geometryRepository.findByViewId(viewId)).noneMatch(geometry -> geometry.getTargetId().equals(noteId));
        assertThat(noteRepository.findById(noteId)).as("removing the placement keeps the note").isPresent();
    }

    @Test
    void theStateHashIsUnchangedWithoutNotesAndChangesWithTheirContent() {
        Fixture fixture = fixture();
        UUID classifier = createClass(fixture);
        String withoutNotes = hasher.hash(modelOf(fixture));
        UUID noteId = createNote(fixture, "one");
        String withNote = hasher.hash(modelOf(fixture));
        assertThat(withNote).isNotEqualTo(withoutNotes);

        assertAccepted(command(fixture, "note.link", Map.of("targetType", "CLASSIFIER", "targetId", classifier),
                target("note", noteId)));
        String linked = hasher.hash(modelOf(fixture));
        assertThat(linked).isNotEqualTo(withNote);
        assertAccepted(command(fixture, "note.update", Map.of("body", "one\ntwo"), target("note", noteId)));
        assertThat(hasher.hash(modelOf(fixture))).isNotEqualTo(linked);

        assertAccepted(command(fixture, "note.delete", Map.of(), target("note", noteId)));
        assertThat(hasher.hash(modelOf(fixture))).as("old hashes stay valid once no note is left")
                .isEqualTo(withoutNotes);
    }

    private List<NoteLinkResource> links(Fixture fixture, UUID noteId) {
        return noteResources.forModel(fixture.model().getId()).stream()
                .filter(note -> note.id().equals(noteId)).map(NoteResource::annotatedElements)
                .findFirst().orElseThrow();
    }

    private UUID createNote(Fixture fixture, String body) {
        UUID id = UUID.randomUUID();
        assertAccepted(command(fixture, "note.create", Map.of("id", id, "body", body), List.of()));
        return id;
    }

    private UUID createClass(Fixture fixture) {
        UUID id = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create",
                Map.of("id", id, "name", "C" + id.toString().substring(0, 8), "kind", "CLASS"), List.of()));
        return id;
    }

    private UUID createRelationship(Fixture fixture, UUID source, UUID target) {
        UUID id = UUID.randomUUID();
        assertAccepted(command(fixture, "relationship.create",
                Map.of("id", id, "kind", "ASSOCIATION", "source", Map.of("classifierId", source),
                        "target", Map.of("classifierId", target)),
                List.of(new Target("classifier", source), new Target("classifier", target))));
        return id;
    }

    private UUID defaultViewId(Fixture fixture) {
        UUID viewId = UUID.randomUUID();
        assertAccepted(command(fixture, "view.create", Map.of("id", viewId, "name", "Main"), List.of()));
        return viewId;
    }

    private UmlModel modelOf(Fixture fixture) {
        return modelRepository.findById(fixture.model().getId()).orElseThrow();
    }

    /** Executes at the model's current revision, so a rejection never leaves the next command stale. */
    private CommandResult command(Fixture fixture, String type, Object payload, List<Target> targets) {
        return command(fixture, type, payload, targets, List.of());
    }

    private CommandResult command(Fixture fixture, String type, Object payload, List<Target> targets,
            List<Precondition> preconditions) {
        long revision = modelRepository.findById(fixture.model().getId()).orElseThrow().getCurrentRevision();
        return commandService.execute(new OperationEnvelope(UUID.randomUUID(), type, fixture.author(),
                fixture.project().getId(), fixture.model().getId(), revision, targets, preconditions, payload,
                CommandOrigin.MANUAL, null, UUID.randomUUID().toString()));
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
                    com.diagramador.domain.UserAccount.create("nt-" + UUID.randomUUID(), "Nt",
                            "nt-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "Notes", "commands"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "Notes Model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            projectMemberRepository.save(ProjectMember.create(project, author, ProjectMemberRole.EDITOR));
            return new Fixture(project, model, new Author(author.getId(), "nt"));
        });
    }

    private record Fixture(Project project, UmlModel model, Author author) {
    }
}
