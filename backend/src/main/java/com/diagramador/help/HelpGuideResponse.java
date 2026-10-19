package com.diagramador.help;

import java.util.List;

public record HelpGuideResponse(int schemaVersion, String answer, List<String> suggestedSlugs) {
}
