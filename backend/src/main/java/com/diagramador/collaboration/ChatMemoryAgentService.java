package com.diagramador.collaboration;

import com.diagramador.api.ApiException;
import com.diagramador.api.ChatResources.MessageResource;
import com.diagramador.api.DiagramMemoryResource;
import com.diagramador.api.MemoryOperationResources.MemoryWriteRequest;
import com.diagramador.api.MemoryOperationResources.SearchHit;
import com.diagramador.api.MemoryOperationResources.SearchRequest;
import com.diagramador.api.MemoryOperationResources.SearchResponse;
import com.diagramador.api.MemoryOperationResources.SearchSource;
import com.diagramador.assistant.ChatAssistantProvider;
import com.diagramador.assistant.MemoryAgentProtocol;
import com.diagramador.context.ContextBudgetService;
import com.diagramador.context.ContextBudgetService.LiveContext;
import com.diagramador.domain.ChatSession;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Strict, bounded text-protocol dispatcher over the existing conversation provider. */
@Service
public class ChatMemoryAgentService {

    private static final Logger logger = LoggerFactory.getLogger(ChatMemoryAgentService.class);
    private static final int MAX_ACTION_STEPS = 4;
    private static final int MAX_ACTION_RESULTS = 5;
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final int MAX_SOURCE_LABELS = 4;
    private static final int MAX_ANSWER_LENGTH = 2000;
    private static final Pattern MEMORY_SUCCESS_CLAIM = Pattern.compile(
            "(?iu)\\b(?:ya\\s+)?(?:guard[eé]|guard[oó]|registr[eé]|registr[oó]|revis[eé]|revis[oó]|"
                    + "modifiqu[eé]|modific[oó]|actualic[eé]|actualiz[oó]|saved|revised|updated)\\s+"
                    + "(?:(?:la|una|esta|esa|the|this|a)\\s+)?(?:memoria|memorias|recuerdo|recuerdos|memory|memories)\\b|"
                    + "\\b(?:(?:la|una|esta|esa|the|this|a)\\s+)?(?:memoria|recuerdo|memory)\\s+"
                    + "(?:fue|ha sido|was|has been|se(?:\\s+ha)?|está|is)\\s+"
                    + "(?:guardad[oa]s?|registrad[oa]s?|revisad[oa]s?|modificad[oa]s?|"
                    + "actualizad[oa]s?|saved|revised|updated)\\b");
    private static final Pattern DENIED_EXTERNAL_SCOPE = Pattern.compile(
            "(?iu)\\b(?:no|nunca)\\s+(?:"
                    + "busca|buscá|buscar|busque|busques|busquen|consulta|consultá|consultar|consultes|consulten|"
                    + "revisa|revisá|revisar|revises|revisen|mira|mirá|mirar|mires|miren|"
                    + "explora|explorá|explorar|explores|exploren|search|look|consult)\\b.{0,100}\\b"
                    + "(?:otro(?:s|a|as)?|diferente(?:s)?|distinto(?:s|a|as)?|diagramas?|proyectos?|"
                    + "other|different|another|diagrams?|projects?)\\b|"
                    + "\\bno\\s+(?:hace\\s+falta|es\\s+necesario|quiero)\\b.{0,80}\\b"
                    + "(?:buscar|consultar|revisar|search|look)\\b.{0,100}\\b"
                    + "(?:otro(?:s|a|as)?|diferente(?:s)?|distinto(?:s|a|as)?|diagramas?|proyectos?|"
                    + "other|different|another|diagrams?|projects?)\\b");
    private static final Pattern EXPLICIT_EXTERNAL_SCOPE = Pattern.compile(
            "(?iu)\\b(?:(?:otro(?:s|a|as)?|diferente(?:s)?|distinto(?:s|a|as)?)\\s+"
                    + "(?:(?:de\\s+)?(?:los|las)\\s+)?(?:diagramas?|proyectos?)|"
                    + "entre\\s+(?:los\\s+|las\\s+)?(?:diagramas?|proyectos?)|"
                    + "todos?(?:\\s+(?:los|las))?\\s+(?:mis\\s+)?(?:diagramas?|proyectos?)|"
                    + "across\\s+(?:the\\s+)?(?:diagrams?|projects?)|"
                    + "(?:other|different|another)\\s+(?:the\\s+)?(?:diagrams?|projects?))\\b");
    private static final String SAFE_REFUSAL =
            "No pude completar esa operación de memoria. Reformulá la solicitud.";
    private static final String SAFE_PARTIAL = "No pude completar la respuesta adicional.";
    private static final String SAFE_WRITE_FAILURE =
            "No se confirmó ningún cambio de memoria; no puedo afirmar que se haya guardado.";

    private final ChatAssistantProvider provider;
    private final MemoryOperationsService memoryOperationsService;
    private final ContextBudgetService contextBudgetService;
    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public record AgentAnswer(String body, Set<UUID> consultedProjectIds) {
        public AgentAnswer {
            consultedProjectIds = Set.copyOf(consultedProjectIds);
        }
    }

    private record MemoryAction(String operation, UUID memoryId, String kind, String content, String query,
            boolean includeAccessibleProjects, int limit) {
        String fingerprint() {
            return String.join("\u001f", operation, String.valueOf(memoryId), String.valueOf(kind),
                    String.valueOf(content), String.valueOf(query), String.valueOf(includeAccessibleProjects),
                    String.valueOf(limit));
        }

        boolean writes() {
            return "save".equals(operation) || "revise".equals(operation);
        }
    }

    private record ParsedResponse(String plainText, MemoryAction action, boolean invalidAction) {
        static ParsedResponse plain(String text) {
            return new ParsedResponse(text, null, false);
        }

        static ParsedResponse action(MemoryAction action) {
            return new ParsedResponse(null, action, false);
        }

        static ParsedResponse invalid() {
            return new ParsedResponse(null, null, true);
        }
    }

    private record ToolExecution(String payload, boolean writeSucceeded, boolean writeFailed, String effect) {
    }

    private record SourceLabel(UUID projectId, String projectName, String viewName) {
    }

    public ChatMemoryAgentService(ChatAssistantProvider provider, MemoryOperationsService memoryOperationsService,
            ContextBudgetService contextBudgetService, ChatService chatService, ObjectMapper objectMapper) {
        this.provider = provider;
        this.memoryOperationsService = memoryOperationsService;
        this.contextBudgetService = contextBudgetService;
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    public AgentAnswer answer(UUID projectId, UUID viewId, LiveContext context,
            List<ChatAssistantProvider.Turn> baseTurns, String userQuestion) {
        List<ChatAssistantProvider.Turn> turns = new ArrayList<>(baseTurns.size() + 1);
        turns.add(new ChatAssistantProvider.Turn("system", MemoryAgentProtocol.INSTRUCTIONS));
        turns.addAll(baseTurns);

        List<String> accountedPrompt = new ArrayList<>();
        Map<String, ToolExecution> completedActions = new HashMap<>();
        List<String> confirmedEffects = new ArrayList<>();
        Set<UUID> consultedProjects = new HashSet<>();
        Map<UUID, SourceLabel> sourceLabels = new LinkedHashMap<>();
        boolean failedWrite = false;
        boolean externalSearchRequested = userQuestion != null
                && EXPLICIT_EXTERNAL_SCOPE.matcher(userQuestion).find()
                && !DENIED_EXTERNAL_SCOPE.matcher(userQuestion).find();

        for (int step = 0; step <= MAX_ACTION_STEPS; step++) {
            try {
                contextBudgetService.requireAdditionalPromptWithinBudget(context.report(), accountedPrompt);
            } catch (ApiException exception) {
                return afterPromptLimit(confirmedEffects, failedWrite, consultedProjects, sourceLabels);
            }

            String raw;
            try {
                raw = provider.chat(List.copyOf(turns));
            } catch (RuntimeException exception) {
                logger.warn("Memory-agent provider call failed for project {} and view {}", projectId, viewId,
                        exception);
                if (!confirmedEffects.isEmpty()) {
                    return partial(confirmedEffects, failedWrite, consultedProjects, sourceLabels);
                }
                throw exception;
            }

            ParsedResponse response = parseResponse(raw);
            if (response.invalidAction()) {
                return refused(confirmedEffects, failedWrite, consultedProjects, sourceLabels);
            }
            if (response.action() == null) {
                if (failedWrite) {
                    return writeFailure(confirmedEffects, consultedProjects, sourceLabels);
                }
                if (confirmedEffects.isEmpty() && response.plainText() != null
                        && MEMORY_SUCCESS_CLAIM.matcher(response.plainText()).find()) {
                    return new AgentAnswer(SAFE_WRITE_FAILURE, consultedProjects);
                }
                return completed(response.plainText(), confirmedEffects, consultedProjects, sourceLabels);
            }
            if (step == MAX_ACTION_STEPS) {
                return partialOrRefusal(confirmedEffects, failedWrite, consultedProjects, sourceLabels);
            }

            MemoryAction action = response.action();
            if ("search".equals(action.operation()) && action.includeAccessibleProjects()
                    && !externalSearchRequested) {
                return refused(confirmedEffects, failedWrite, consultedProjects, sourceLabels);
            }
            ToolExecution execution = completedActions.get(action.fingerprint());
            if (execution == null) {
                execution = execute(projectId, viewId, action, consultedProjects, sourceLabels);
                completedActions.put(action.fingerprint(), execution);
                if (execution.writeSucceeded() && execution.effect() != null) {
                    confirmedEffects.add(execution.effect());
                }
                failedWrite |= execution.writeFailed();
            }

            String actionTurn = raw.strip();
            String resultTurn = "SERVER_MEMORY_RESULT: " + execution.payload();
            List<String> nextPrompt = new ArrayList<>(accountedPrompt);
            nextPrompt.add(actionTurn);
            nextPrompt.add(resultTurn);
            try {
                contextBudgetService.requireAdditionalPromptWithinBudget(context.report(), nextPrompt);
            } catch (ApiException exception) {
                return afterPromptLimit(confirmedEffects, failedWrite, consultedProjects, sourceLabels);
            }
            accountedPrompt.add(actionTurn);
            accountedPrompt.add(resultTurn);
            turns.add(new ChatAssistantProvider.Turn("assistant", actionTurn));
            turns.add(new ChatAssistantProvider.Turn("user", resultTurn));
        }
        return partialOrRefusal(confirmedEffects, failedWrite, consultedProjects, sourceLabels);
    }

    @Transactional
    public boolean publishIfSharedSourcesRemainAccessible(UUID projectId, UUID sessionId, UUID questionId,
            AgentAnswer answer) {
        ChatSession session = chatService.requireAccessibleSession(projectId, sessionId);
        if (!memoryOperationsService.sharedSourcesRemainAccessible(projectId, session.getView().getId(),
                answer.consultedProjectIds())) {
            return false;
        }
        MessageResource ignored = chatService.postAiAnswer(sessionId, questionId, answer.body());
        return ignored != null;
    }

    private ToolExecution execute(UUID projectId, UUID viewId, MemoryAction action,
            Set<UUID> consultedProjects, Map<UUID, SourceLabel> sourceLabels) {
        try {
            Object result;
            switch (action.operation()) {
                case "read" -> result = memoryOperationsService.read(projectId, viewId, action.memoryId());
                case "search" -> {
                    SearchResponse search = memoryOperationsService.searchForSharedChat(projectId, viewId,
                            new SearchRequest(action.query(), action.includeAccessibleProjects(), action.limit()));
                    collectSources(projectId, search.results(), consultedProjects, sourceLabels);
                    result = search;
                }
                case "save" -> result = memoryOperationsService.saveForSharedChat(projectId, viewId,
                        new MemoryWriteRequest(action.kind(), action.content()), consultedProjects);
                case "revise" -> result = memoryOperationsService.reviseForSharedChat(projectId, viewId,
                        action.memoryId(), new MemoryWriteRequest(action.kind(), action.content()), consultedProjects);
                default -> throw new IllegalStateException("Validated memory action changed unexpectedly.");
            }
            boolean write = action.writes() && result instanceof DiagramMemoryResource;
            String effect = write ? confirmedEffect(action.operation(), (DiagramMemoryResource) result) : null;
            return execution(action.operation(), true, result, write, false, effect);
        } catch (RuntimeException exception) {
            logger.info("A bounded memory action was refused: {}", action.operation());
            return execution(action.operation(), false, "The operation was unavailable.", false,
                    action.writes(), null);
        }
    }

    private void collectSources(UUID currentProjectId, List<SearchHit> hits, Set<UUID> consultedProjects,
            Map<UUID, SourceLabel> sourceLabels) {
        for (SearchHit hit : hits) {
            SearchSource source = hit.source();
            if (source == null || source.projectId() == null || currentProjectId.equals(source.projectId())) {
                continue;
            }
            consultedProjects.add(source.projectId());
            sourceLabels.putIfAbsent(source.projectId(),
                    new SourceLabel(source.projectId(), source.projectName(), source.viewName()));
        }
    }

    private ToolExecution execution(String operation, boolean ok, Object result, boolean writeSucceeded,
            boolean writeFailed, String effect) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", operation);
        payload.put("ok", ok);
        payload.put("result", result);
        return new ToolExecution(json(payload), writeSucceeded, writeFailed, effect);
    }

    private ParsedResponse parseResponse(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            return ParsedResponse.plain("");
        }

        JsonNode root;
        try (JsonParser parser = objectMapper.getFactory().createParser(text)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            root = objectMapper.readTree(parser);
            if (parser.nextToken() != null) {
                return text.contains("\"memoryAction\"") ? ParsedResponse.invalid() : ParsedResponse.plain(text);
            }
        } catch (IOException exception) {
            return text.contains("\"memoryAction\"") ? ParsedResponse.invalid() : ParsedResponse.plain(text);
        }
        if (root == null || !root.isObject()) {
            return ParsedResponse.invalid();
        }
        if (!root.has("memoryAction")) {
            return ParsedResponse.invalid();
        }
        if (root.size() != 1 || !root.get("memoryAction").isObject()) {
            return ParsedResponse.invalid();
        }
        return parseAction(root.get("memoryAction"));
    }

    private ParsedResponse parseAction(JsonNode node) {
        JsonNode operationNode = node.get("operation");
        if (operationNode == null || !operationNode.isTextual()) {
            return ParsedResponse.invalid();
        }
        String operation = operationNode.textValue();
        Set<String> expected = switch (operation) {
            case "read" -> Set.of("operation", "memoryId");
            case "search" -> Set.of("operation", "query", "includeAccessibleProjects", "limit");
            case "save" -> Set.of("operation", "kind", "content");
            case "revise" -> Set.of("operation", "memoryId", "kind", "content");
            default -> null;
        };
        if (expected == null || !allowedKeys(node, operation)) {
            return ParsedResponse.invalid();
        }

        try {
            return switch (operation) {
                case "read" -> {
                    if (!keysAre(node, expected) || !node.path("memoryId").isTextual()) {
                        yield ParsedResponse.invalid();
                    }
                    UUID memoryId = uuid(node.path("memoryId").textValue());
                    yield memoryId == null ? ParsedResponse.invalid()
                            : ParsedResponse.action(new MemoryAction(operation, memoryId, null, null, null,
                                    false, MAX_ACTION_RESULTS));
                }
                case "search" -> {
                    Set<String> required = Set.of("operation", "query");
                    if (!hasRequiredKeys(node, required) || !node.path("query").isTextual()) {
                        yield ParsedResponse.invalid();
                    }
                    String rawQuery = node.path("query").textValue();
                    String query = rawQuery.strip();
                    if (rawQuery.length() > MAX_QUERY_LENGTH || query.isEmpty()) {
                        yield ParsedResponse.invalid();
                    }
                    boolean includeExternal = false;
                    if (node.has("includeAccessibleProjects")) {
                        if (!node.get("includeAccessibleProjects").isBoolean()) {
                            yield ParsedResponse.invalid();
                        }
                        includeExternal = node.get("includeAccessibleProjects").booleanValue();
                    }
                    int limit = MAX_ACTION_RESULTS;
                    if (node.has("limit")) {
                        if (!node.get("limit").isIntegralNumber() || !node.get("limit").canConvertToInt()) {
                            yield ParsedResponse.invalid();
                        }
                        limit = node.get("limit").intValue();
                    }
                    if (limit < 1 || limit > MAX_ACTION_RESULTS) {
                        yield ParsedResponse.invalid();
                    }
                    yield ParsedResponse.action(new MemoryAction(operation, null, null, null, query,
                            includeExternal, limit));
                }
                case "save", "revise" -> {
                    if (!keysAre(node, expected) || !node.path("kind").isTextual()
                            || !node.path("content").isTextual()
                            || (operation.equals("revise") && !node.path("memoryId").isTextual())) {
                        yield ParsedResponse.invalid();
                    }
                    UUID memoryId = operation.equals("revise") ? uuid(node.path("memoryId").textValue()) : null;
                    String rawContent = node.path("content").textValue();
                    String kind = node.path("kind").textValue().strip().toUpperCase(java.util.Locale.ROOT);
                    String content = rawContent.strip();
                    if (rawContent.length() > MAX_CONTENT_LENGTH
                            || (operation.equals("revise") && memoryId == null)
                            || !Set.of("DECISION", "HYPOTHESIS").contains(kind)
                            || content.isEmpty() || content.length() > MAX_CONTENT_LENGTH) {
                        yield ParsedResponse.invalid();
                    }
                    yield ParsedResponse.action(new MemoryAction(operation, memoryId, kind, content, null,
                            false, MAX_ACTION_RESULTS));
                }
                default -> ParsedResponse.invalid();
            };
        } catch (RuntimeException exception) {
            return ParsedResponse.invalid();
        }
    }

    private boolean allowedKeys(JsonNode node, String operation) {
        Set<String> allowed = switch (operation) {
            case "read" -> Set.of("operation", "memoryId");
            case "search" -> Set.of("operation", "query", "includeAccessibleProjects", "limit");
            case "save" -> Set.of("operation", "kind", "content");
            case "revise" -> Set.of("operation", "memoryId", "kind", "content");
            default -> Set.of();
        };
        var names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                return false;
            }
        }
        return true;
    }

    private boolean keysAre(JsonNode node, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private boolean hasRequiredKeys(JsonNode node, Set<String> required) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        return actual.containsAll(required);
    }

    private UUID uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value) ? parsed : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String confirmedEffect(String operation, DiagramMemoryResource memory) {
        String verb = operation.equals("save") ? "guardada" : "revisada";
        return "Memoria " + verb + " por el servidor (" + memory.memoryId() + ", " + memory.kind() + ").";
    }

    private AgentAnswer completed(String raw, List<String> effects, Set<UUID> sources,
            Map<UUID, SourceLabel> labels) {
        String answer = raw == null ? "" : raw.strip();
        if (answer.isEmpty()) {
            return partialOrRefusal(effects, false, sources, labels);
        }
        return new AgentAnswer(withServerFacts(answer, effects, sources, labels), sources);
    }

    private AgentAnswer refused(List<String> effects, boolean failedWrite, Set<UUID> sources,
            Map<UUID, SourceLabel> labels) {
        if (failedWrite) {
            return writeFailure(effects, sources, labels);
        }
        if (effects.isEmpty()) {
            return new AgentAnswer(SAFE_REFUSAL, sources);
        }
        return partial(effects, false, sources, labels);
    }

    private AgentAnswer afterPromptLimit(List<String> effects, boolean failedWrite, Set<UUID> sources,
            Map<UUID, SourceLabel> labels) {
        if (failedWrite) {
            return writeFailure(effects, sources, labels);
        }
        if (effects.isEmpty()) {
            return new AgentAnswer("El contexto alcanzó el límite disponible; no pude completar la respuesta.",
                    sources);
        }
        return partial(effects, false, sources, labels);
    }

    private AgentAnswer partialOrRefusal(List<String> effects, boolean failedWrite, Set<UUID> sources,
            Map<UUID, SourceLabel> labels) {
        if (failedWrite) {
            return writeFailure(effects, sources, labels);
        }
        if (effects.isEmpty()) {
            return new AgentAnswer(SAFE_REFUSAL, sources);
        }
        return partial(effects, false, sources, labels);
    }

    private AgentAnswer writeFailure(List<String> effects, Set<UUID> sources,
            Map<UUID, SourceLabel> labels) {
        return effects.isEmpty() ? new AgentAnswer(SAFE_WRITE_FAILURE, sources)
                : partial(effects, true, sources, labels);
    }

    private AgentAnswer partial(List<String> effects, boolean failedWrite, Set<UUID> sources,
            Map<UUID, SourceLabel> labels) {
        String body = String.join(" ", effects);
        if (failedWrite) {
            body += " No se confirmaron otros cambios solicitados.";
        }
        body += " " + SAFE_PARTIAL;
        return new AgentAnswer(withServerFacts(body, List.of(), sources, labels), sources);
    }

    private String withServerFacts(String answer, List<String> effects, Set<UUID> sources,
            Map<UUID, SourceLabel> labels) {
        StringBuilder suffix = new StringBuilder();
        if (!effects.isEmpty()) {
            suffix.append("\n\nCambios confirmados: ").append(String.join(" ", effects));
        }
        List<String> externalLabels = labels.values().stream().limit(MAX_SOURCE_LABELS)
                .map(label -> truncate(label.projectName(), 80) + " / "
                        + (label.viewName() == null ? "proyecto" : truncate(label.viewName(), 80)))
                .toList();
        if (!externalLabels.isEmpty()) {
            suffix.append("\n\nFuentes consultadas: ").append(String.join("; ", externalLabels));
        }
        String extra = suffix.toString();
        int answerLength = Math.max(0, MAX_ANSWER_LENGTH - extra.length());
        String boundedAnswer = answer.length() <= answerLength ? answer : answer.substring(0, answerLength).stripTrailing();
        return boundedAnswer + extra;
    }

    private String truncate(String value, int maximum) {
        if (value == null) {
            return "fuente";
        }
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("The memory action result could not be serialized.", exception);
        }
    }
}
