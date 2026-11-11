package com.diagramador.assistant;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ProjectApiService;
import com.diagramador.identity.ProjectAuthorizationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssistantPlanService {

    private static final Pattern FENCED_JSON = Pattern.compile("```(?:json)?\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);
    private static final TypeReference<Map<String, Object>> OBJECT_TYPE = new TypeReference<>() {
    };

    private final ProjectApiService projectApiService;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final AiAssistantProvider provider;
    private final AssistantPlanValidator validator;
    private final DiagramContextRenderer contextRenderer;
    private final ObjectMapper objectMapper;

    public AssistantPlanService(ProjectApiService projectApiService,
            ProjectAuthorizationService projectAuthorizationService,
            AiAssistantProvider provider,
            AssistantPlanValidator validator,
            DiagramContextRenderer contextRenderer,
            ObjectMapper objectMapper) {
        this.projectApiService = projectApiService;
        this.projectAuthorizationService = projectAuthorizationService;
        this.provider = provider;
        this.validator = validator;
        this.contextRenderer = contextRenderer;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AssistantPlanResponse plan(java.util.UUID projectId, AssistantPlanRequest request) {
        return plan(projectId, request, null, null);
    }

    /** Same plan, optionally from a photo of a class diagram (base64 and MIME type). */
    @Transactional(readOnly = true)
    public AssistantPlanResponse plan(java.util.UUID projectId, AssistantPlanRequest request,
            String imageBase64, String imageMime) {
        // getModel preserves the existing 404/member authorization and snapshot-loading path.
        ModelResource model = projectApiService.getModel(projectId);
        projectAuthorizationService.requireEditor(projectId);
        if (request.baseRevision() != model.revision()) {
            return rejected("REVIEW_REQUIRED",
                    "El diagrama cambió. Revisá el modelo antes de generar un nuevo plan.");
        }

        String contextJson = contextRenderer.renderDiagramOnly(model);
        String rawDraft;
        try {
            rawDraft = imageBase64 == null
                    ? provider.draft(contextJson, request.utterance())
                    : provider.draft(contextJson, request.utterance(), imageBase64, imageMime);
        } catch (IllegalStateException exception) {
            return rejected("PROVIDER_DISABLED", "El asistente de IA no está habilitado.");
        }

        Map<String, Object> parsed = extractJson(rawDraft);
        if (parsed == null || !(parsed.get("intent") instanceof String intent)
                || !(parsed.get("operations") instanceof List<?> rawOperations)) {
            return rejected("UNPARSEABLE_DRAFT", "El asistente no devolvió un plan JSON válido.");
        }

        List<Map<String, Object>> operations = new ArrayList<>();
        for (Object operation : rawOperations) {
            if (operation instanceof Map<?, ?> rawMap) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                rawMap.forEach((key, value) -> {
                    if (key instanceof String stringKey) {
                        normalized.put(stringKey, value);
                    }
                });
                operations.add(normalized);
            }
        }
        String question = parsed.get("question") instanceof String value ? value : null;
        String explanation = parsed.get("explanation") instanceof String value ? value : null;
        if (!isIntent(intent)) {
            return rejected("UNPARSEABLE_DRAFT", "El asistente no devolvió una intención válida.");
        }
        return validator.validate(intent, operations, model, question, explanation);
    }

    private Map<String, Object> extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        Matcher fenced = FENCED_JSON.matcher(raw);
        if (fenced.find()) {
            candidates.add(fenced.group(1));
        }
        int start = raw.indexOf('{');
        if (start >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int index = start; index < raw.length(); index++) {
                char character = raw.charAt(index);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (character == '\\') {
                        escaped = true;
                    } else if (character == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (character == '"') {
                    inString = true;
                } else if (character == '{') {
                    depth++;
                } else if (character == '}') {
                    depth--;
                    if (depth == 0) {
                        candidates.add(raw.substring(start, index + 1));
                        break;
                    }
                }
            }
        }
        for (String candidate : candidates) {
            try {
                Map<String, Object> object = objectMapper.readValue(candidate, OBJECT_TYPE);
                if (object != null) {
                    return object;
                }
            } catch (JsonProcessingException ignored) {
                // Try the balanced-object candidate after a possibly incomplete fenced match.
            }
        }
        return null;
    }

    private static boolean isIntent(String intent) {
        return switch (intent.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "PLAN", "EXPLAIN", "CLARIFY", "REJECT" -> true;
            default -> false;
        };
    }

    private static AssistantPlanResponse rejected(String code, String message) {
        return new AssistantPlanResponse("REJECT", List.of(), null, null,
                List.of(new AssistantPlanResponse.Diagnostic(code, message)));
    }
}
