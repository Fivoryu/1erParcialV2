package com.diagramador.xmi;

import static com.diagramador.xmi.XmiTestReadings.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.command.ErrorCode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class XmiImportValidatorTest {

    private static final XmiImportValidator.ExistingModel EMPTY = XmiImportValidator.ExistingModel.EMPTY;

    private final XmiImportValidator validator = new XmiImportValidator();

    @Test
    void aConsistentReadingHasNoIssues() {
        assertThat(validator.validate(clean(), XmiImportMode.AGREGAR, EMPTY)).isEmpty();
    }

    @Test
    void aNameCollisionInsideTheFileIsABlockerWithRenameAndSkipOptions() {
        XmiReadingResult reading = reading(List.of(clazz(1, "Same"), clazz(2, "Same")), List.of(),
                List.of(view(100, "Main", true, List.of(), List.of())), List.of());

        List<XmiImportIssue> issues = validator.validate(reading, XmiImportMode.REEMPLAZAR, EMPTY);

        assertThat(issues).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("NAME_COLLISION_IN_FILE");
            assertThat(issue.severity()).isEqualTo(XmiImportIssue.Severity.BLOCKER);
            assertThat(issue.subject()).isEqualTo(id(2).toString());
            assertThat(issue.name()).isEqualTo("Same");
            assertThat(issue.message()).contains("Same");
            assertThat(issue.options()).containsExactly(XmiImportIssue.Option.RENAME, XmiImportIssue.Option.SKIP);
            assertThat(issue.rejectionCode()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(issue.rejectionMessage())
                    .isEqualTo("Imported classifier name 'Same' collides within its namespace");
        });
    }

    @Test
    void aCollisionWithTheExistingModelBlocksOnlyInAdditionMode() {
        var existing = new XmiImportValidator.ExistingModel(
                List.of(new XmiImportValidator.ExistingClassifier(id(77), null, "Alpha")), Set.of());

        List<XmiImportIssue> added = validator.validate(clean(), XmiImportMode.AGREGAR, existing);
        List<XmiImportIssue> replaced = validator.validate(clean(), XmiImportMode.REEMPLAZAR, existing);

        assertThat(added).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("NAME_COLLISION");
            assertThat(issue.subject()).isEqualTo(id(1).toString());
            assertThat(issue.options()).containsExactly(XmiImportIssue.Option.RENAME, XmiImportIssue.Option.SKIP);
            assertThat(issue.rejectionMessage()).isEqualTo("AGREGAR is blocked: classifier name 'Alpha' "
                    + "collides with existing classifier " + id(77));
        });
        assertThat(replaced).isEmpty();
    }

    @Test
    void anExistingDiagramNameBlocksAdditionWithARenameOption() {
        var existing = new XmiImportValidator.ExistingModel(List.of(), Set.of("Main"));

        List<XmiImportIssue> issues = validator.validate(clean(), XmiImportMode.AGREGAR, existing);

        assertThat(issues).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("DIAGRAM_NAME_COLLISION");
            assertThat(issue.subject()).isEqualTo(id(100).toString());
            assertThat(issue.options()).containsExactly(XmiImportIssue.Option.RENAME);
            assertThat(issue.rejectionMessage()).isEqualTo("AGREGAR is blocked: diagram name 'Main' already exists");
        });
    }

    @Test
    void severalDiagramsAreABlockerWithAKeepViewOption() {
        XmiReadingResult reading = reading(List.of(clazz(1, "Alpha")), List.of(), List.of(
                view(100, "A", true, List.of(), List.of()), view(101, "B", false, List.of(), List.of())), List.of());

        List<XmiImportIssue> issues = validator.validate(reading, XmiImportMode.REEMPLAZAR, EMPTY);

        assertThat(issues).first().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("DIAGRAM_COUNT");
            assertThat(issue.options()).containsExactly(XmiImportIssue.Option.KEEP_VIEW);
            assertThat(issue.rejectionCode()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(issue.rejectionMessage()).isEqualTo("An XMI import must contain exactly one diagram view");
        });
    }

    @Test
    void structuralProblemsKeepTheCodeAndMessageTheHandlerRejectedWithBefore() {
        XmiReadingResult blank = reading(List.of(clazz(1, " ")), List.of(),
                List.of(view(100, "Main", true, List.of(), List.of())), List.of());
        XmiReadingResult missingEnd = reading(List.of(clazz(1, "Alpha")), List.of(association(10, 1, 9)),
                List.of(view(100, "Main", true, List.of(), List.of())), List.of());
        XmiReadingResult badGeometry = reading(List.of(clazz(1, "Alpha")), List.of(),
                List.of(view(100, "Main", true, List.of(geometry(500, 1, 0, 10)), List.of())), List.of());

        assertThat(validator.validate(blank, XmiImportMode.REEMPLAZAR, EMPTY)).first().satisfies(issue -> {
            assertThat(issue.rejectionCode()).isEqualTo(ErrorCode.INVALID_PAYLOAD);
            assertThat(issue.rejectionMessage()).isEqualTo("Every imported classifier requires an id and a name");
        });
        assertThat(validator.validate(missingEnd, XmiImportMode.REEMPLAZAR, EMPTY)).first().satisfies(issue -> {
            assertThat(issue.rejectionCode()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(issue.rejectionMessage()).isEqualTo("Relationship " + id(10) + " references a missing classifier");
        });
        assertThat(validator.validate(badGeometry, XmiImportMode.REEMPLAZAR, EMPTY)).first().satisfies(issue -> {
            assertThat(issue.rejectionCode()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(issue.rejectionMessage()).isEqualTo("The imported diagram contains invalid classifier geometry");
        });
    }
}
