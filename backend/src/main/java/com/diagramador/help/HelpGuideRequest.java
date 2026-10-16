package com.diagramador.help;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record HelpGuideRequest(
        @NotBlank String question,
        @NotEmpty List<@Valid Topic> topics,
        @NotNull @Valid EditorState editorState) {

    public record Topic(
            @NotBlank String slug,
            @NotBlank String title,
            @NotBlank String summary) {
    }

    public record EditorState(
            String openSection,
            @NotEmpty List<@NotBlank String> sections) {
    }
}
