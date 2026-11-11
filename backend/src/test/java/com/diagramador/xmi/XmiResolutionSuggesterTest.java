package com.diagramador.xmi;

import static com.diagramador.xmi.XmiTestReadings.clazz;
import static com.diagramador.xmi.XmiTestReadings.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.assistant.ChatAssistantProvider;
import com.diagramador.command.ErrorCode;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class XmiResolutionSuggesterTest {

    private static final List<XmiImportIssue.Option> RENAME_OR_SKIP =
            List.of(XmiImportIssue.Option.RENAME, XmiImportIssue.Option.SKIP);

    /** Two classes named "Alpha" in the file: the second one (id 2) is the colliding one. */
    private final XmiReadingResult twoAlphas = XmiTestReadings.reading(
            List.of(clazz(1, "Alpha"), clazz(2, "Alpha"), clazz(3, "Gamma")), List.of(), List.of(), List.of());

    private final List<XmiImportIssue> collision = List.of(XmiImportIssue.blocker("NAME_COLLISION_IN_FILE",
            id(2).toString(), "Alpha", "Dos clases llamadas 'Alpha'.", RENAME_OR_SKIP,
            ErrorCode.PRECONDITION_FAILED, "collision"));

    /** A dry run that reports nothing: the resolutions always leave the import clean. */
    private final Function<List<XmiResolution>, List<XmiImportIssue>> cleanDryRun = resolutions -> List.of();

    private XmiResolutionSuggester suggester(String reply) {
        return new XmiResolutionSuggester(new FakeProvider(reply, false), 2000);
    }

    @Test
    void noCollisionIssuesYieldsNoResolutionsFromRulesWithoutAskingTheAi() {
        FakeProvider provider = new FakeProvider("[]", false);
        var suggestion = new XmiResolutionSuggester(provider, 2000).suggest(XmiTestReadings.clean(), List.of(),
                XmiImportValidator.ExistingModel.EMPTY, cleanDryRun);

        assertThat(suggestion.resolutions()).isEmpty();
        assertThat(suggestion.source()).isEqualTo("RULES");
        assertThat(provider.calls).isZero();
    }

    @Test
    void aValidAiChoiceIsUsedAndReportedAsAi() {
        var suggestion = suggester("[{\"subject\":\"" + id(2) + "\",\"action\":\"RENAME\",\"newName\":\"Alpha Dos\"}]\n"
                + "Razón: los dos nombres eran iguales.")
                .suggest(twoAlphas, collision, XmiImportValidator.ExistingModel.EMPTY, cleanDryRun);

        assertThat(suggestion.source()).isEqualTo("AI");
        assertThat(suggestion.resolutions()).containsExactly(
                new XmiResolution(id(2).toString(), XmiResolution.Action.RENAME, "Alpha Dos"));
        assertThat(suggestion.explanation()).contains("los dos nombres eran iguales");
    }

    @Test
    void anActionOutsideTheIssueOptionsFallsBackToTheRule() {
        var suggestion = suggester("[{\"subject\":\"" + id(2) + "\",\"action\":\"KEEP_VIEW\"}]")
                .suggest(twoAlphas, collision, XmiImportValidator.ExistingModel.EMPTY, cleanDryRun);

        assertThat(suggestion.source()).isEqualTo("RULES");
        assertThat(suggestion.resolutions()).containsExactly(
                new XmiResolution(id(2).toString(), XmiResolution.Action.RENAME, "Alpha (2)"));
    }

    @Test
    void aNewNameThatCollidesIsReplacedByTheRule() {
        var suggestion = suggester("[{\"subject\":\"" + id(2) + "\",\"action\":\"RENAME\",\"newName\":\"Gamma\"}]")
                .suggest(twoAlphas, collision, XmiImportValidator.ExistingModel.EMPTY, cleanDryRun);

        assertThat(suggestion.source()).isEqualTo("RULES");
        assertThat(suggestion.resolutions().get(0).newName()).isEqualTo("Alpha (2)");
    }

    @Test
    void aBlankNewNameFallsBackAndTheRuleSkipsNamesAlreadyTaken() {
        XmiReadingResult reading = XmiTestReadings.reading(
                List.of(clazz(1, "Alpha"), clazz(2, "Alpha"), clazz(3, "Alpha (2)")), List.of(), List.of(), List.of());
        var suggestion = suggester("[{\"subject\":\"" + id(2) + "\",\"action\":\"RENAME\",\"newName\":\"  \"}]")
                .suggest(reading, collision, XmiImportValidator.ExistingModel.EMPTY, cleanDryRun);

        assertThat(suggestion.resolutions().get(0).newName()).isEqualTo("Alpha (3)");
    }

    @Test
    void aNewNameThatCollidesWithAnExistingClassifierIsRejected() {
        var existing = new XmiImportValidator.ExistingModel(
                List.of(new XmiImportValidator.ExistingClassifier(id(77), null, "Delta")), Set.of());
        var suggestion = suggester("[{\"subject\":\"" + id(2) + "\",\"action\":\"RENAME\",\"newName\":\"Delta\"}]")
                .suggest(twoAlphas, collision, existing, cleanDryRun);

        assertThat(suggestion.source()).isEqualTo("RULES");
        assertThat(suggestion.resolutions().get(0).newName()).isEqualTo("Alpha (2)");
    }

    @Test
    void aValidSkipChoiceIsKept() {
        var suggestion = suggester("[{\"subject\":\"" + id(2) + "\",\"action\":\"SKIP\"}]")
                .suggest(twoAlphas, collision, XmiImportValidator.ExistingModel.EMPTY, cleanDryRun);

        assertThat(suggestion.source()).isEqualTo("AI");
        assertThat(suggestion.resolutions()).containsExactly(
                new XmiResolution(id(2).toString(), XmiResolution.Action.SKIP, null));
    }

    @Test
    void garbageTextFallsBackToRules() {
        var suggestion = suggester("Claro, con gusto te ayudo [[ no es json").suggest(twoAlphas, collision,
                XmiImportValidator.ExistingModel.EMPTY, cleanDryRun);

        assertThat(suggestion.source()).isEqualTo("RULES");
        assertThat(suggestion.resolutions()).hasSize(1);
        assertThat(suggestion.resolutions().get(0).action()).isEqualTo(XmiResolution.Action.RENAME);
    }

    @Test
    void aProviderThatThrowsFallsBackToRulesSilently() {
        var suggestion = new XmiResolutionSuggester(new FakeProvider(null, true), 2000).suggest(twoAlphas, collision,
                XmiImportValidator.ExistingModel.EMPTY, cleanDryRun);

        assertThat(suggestion.source()).isEqualTo("RULES");
        assertThat(suggestion.resolutions()).hasSize(1);
    }

    @Test
    void aProviderThatTimesOutFallsBackToRules() {
        var slow = new ChatAssistantProvider() {
            @Override
            public String chat(List<Turn> turns) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return "[]";
            }

            @Override
            public String systemPrompt() {
                return "";
            }
        };
        var suggestion = new XmiResolutionSuggester(slow, 100).suggest(twoAlphas, collision,
                XmiImportValidator.ExistingModel.EMPTY, cleanDryRun);

        assertThat(suggestion.source()).isEqualTo("RULES");
    }

    @Test
    void theDiagramNameCollisionIsRenamedAgainstTheStoredDiagramNames() {
        var view = XmiTestReadings.view(100, "Main", true, List.of(), List.of());
        XmiReadingResult reading = XmiTestReadings.reading(List.of(clazz(1, "Alpha")), List.of(), List.of(view),
                List.of());
        var issues = List.of(XmiImportIssue.blocker("DIAGRAM_NAME_COLLISION", id(100).toString(), "Main",
                "Ya existe un diagrama.", List.of(XmiImportIssue.Option.RENAME), ErrorCode.PRECONDITION_FAILED, "x"));
        var existing = new XmiImportValidator.ExistingModel(List.of(), Set.of("Main", "Main (2)"));

        var suggestion = suggester("no es json").suggest(reading, issues, existing, cleanDryRun);

        assertThat(suggestion.resolutions()).containsExactly(
                new XmiResolution(id(100).toString(), XmiResolution.Action.RENAME, "Main (3)"));
    }

    @Test
    void remainingBlockersAfterTheDryRunAreReportedInTheExplanation() {
        var stillBlocked = XmiImportIssue.modelBlocker("R1", id(3).toString(), "Gamma", "Regla R1 incumplida.");
        var suggestion = suggester("no es json").suggest(twoAlphas, collision,
                XmiImportValidator.ExistingModel.EMPTY, resolutions -> List.of(stillBlocked));

        assertThat(suggestion.resolutions()).hasSize(1);
        assertThat(suggestion.explanation()).contains("Regla R1 incumplida.");
    }

    private static final class FakeProvider implements ChatAssistantProvider {
        private final String reply;
        private final boolean fail;
        int calls;

        FakeProvider(String reply, boolean fail) {
            this.reply = reply;
            this.fail = fail;
        }

        @Override
        public String chat(List<Turn> turns) {
            calls++;
            if (fail) {
                throw new IllegalStateException("AI assistant provider is disabled");
            }
            return reply;
        }

        @Override
        public String systemPrompt() {
            return "";
        }
    }
}
