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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
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
                    + "actualizad[oa]s?|saved|revised|updated)\\b|"
                    + "\\b(?:guard[eé]|guard[oó]|registr[eé]|registr[oó]|anot[eé]|anot[oó]|saved|recorded|stored)"
                    + "(?=\\s|[,:;.]|$).{0,40}?\\b(?:como|as)\\s+(?:una?\\s+|an?\\s+)?"
                    + "(?:hip[oó]tesis|decisi[oó]n|hypothesis|decision)\\b");
    /**
     * Explicit requests are executed by the server instead of waiting for the small local model to emit the
     * action JSON, which it does unreliably: "Guardá como hipótesis que X" saves X; "Revisá la hipótesis que
     * contiene T: cambio" revises the single active memory containing every identifier in T.
     */
    private static final Pattern EXPLICIT_SAVE = Pattern.compile(
            "(?iu)^\\s*(?:por\\s+favor,?\\s+)?(?:guard[aá]|registr[aá]|anot[aá]|save|record)\\s+"
                    + "(?:esto\\s+|this\\s+)?(?:como|as)\\s+(?:una?\\s+|an?\\s+)?"
                    + "(hip[oó]tesis|decisi[oó]n|hypothesis|decision)\\b\\s*[:,]?\\s*(?:que\\s+|that\\s+)?(.+)$",
            Pattern.DOTALL);
    private static final Pattern EXPLICIT_REVISE = Pattern.compile(
            "(?iu)^\\s*(?:por\\s+favor,?\\s+)?(?:revis[aá]|correg[ií]|actualiz[aá]|modific[aá]|revise|update|correct)"
                    + "\\s+(?:la\\s+|el\\s+|una\\s+|the\\s+)?(?:hip[oó]tesis|decisi[oó]n|memoria|hypothesis|decision|memory)"
                    + "\\b([^:]*):\\s*(.+)$",
            Pattern.DOTALL);
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern SEARCH_WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)*");
    private static final int MAX_SERVER_SEARCH_TERMS = 6;
    private static final Set<String> SEARCH_NOISE = Set.of("busca", "buscá", "buscar", "consulta", "consultá",
            "consultar", "revisa", "revisá", "mira", "mirá", "explora", "explorá", "search", "look", "find",
            "otro", "otros", "otra", "otras", "diferente", "diferentes", "distinto", "distintos", "distinta",
            "distintas", "diagrama", "diagramas", "proyecto", "proyectos", "other", "different", "another",
            "diagram", "diagrams", "project", "projects", "todos", "todas", "entre", "across", "para", "como",
            "este", "esta", "estos", "estas", "cual", "cuál", "donde", "dónde", "aparece", "aparecen", "registra",
            "tiene", "decime", "dime", "sobre", "with", "from", "what", "which", "where", "that", "this", "there");
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
    private final DiagramMemoryService diagramMemoryService;
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
            ContextBudgetService contextBudgetService, ChatService chatService,
            DiagramMemoryService diagramMemoryService, ObjectMapper objectMapper) {
        this.provider = provider;
        this.memoryOperationsService = memoryOperationsService;
        this.contextBudgetService = contextBudgetService;
        this.chatService = chatService;
        this.diagramMemoryService = diagramMemoryService;
        this.objectMapper = objectMapper;
    }

    public AgentAnswer answer(UUID projectId, UUID viewId, LiveContext context,
            List<ChatAssistantProvider.Turn> baseTurns, String userQuestion, UUID questionMessageId) {
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
        Deque<MemoryAction> serverActions = new ArrayDeque<>(
                explicitActions(projectId, viewId, userQuestion, questionMessageId, externalSearchRequested));
        boolean serverWriteApplied = false;

        for (int step = 0; step <= MAX_ACTION_STEPS; step++) {
            try {
                contextBudgetService.requireAdditionalPromptWithinBudget(context.report(), accountedPrompt);
            } catch (ApiException exception) {
                return afterPromptLimit(confirmedEffects, failedWrite, consultedProjects, sourceLabels);
            }

            String raw;
            ParsedResponse response;
            boolean plannedByServer = !serverActions.isEmpty();
            if (plannedByServer) {
                MemoryAction planned = serverActions.poll();
                raw = actionJson(planned);
                response = ParsedResponse.action(planned);
            } else {
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
                response = parseResponse(raw);
            }
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
            if (execution == null && !plannedByServer && serverWriteApplied && action.writes()) {
                // The server already applied the explicit write for this request; a reworded model write
                // must not persist a second memory.
                execution = execution(action.operation(), false,
                        "The server already applied this request's memory change.", false, false, null);
            }
            if (execution == null) {
                execution = execute(projectId, viewId, action, consultedProjects, sourceLabels);
                completedActions.put(action.fingerprint(), execution);
                if (execution.writeSucceeded() && execution.effect() != null) {
                    confirmedEffects.add(execution.effect());
                    serverWriteApplied |= plannedByServer;
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

    /** Plans the server-executed action for an explicit save, revise or cross-diagram search request. */
    private List<MemoryAction> explicitActions(UUID projectId, UUID viewId, String question,
            UUID questionMessageId, boolean externalSearchRequested) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Matcher save = EXPLICIT_SAVE.matcher(question);
        if (save.matches()) {
            String content = withoutTerminalPunctuation(firstSentence(save.group(2)));
            if (!content.isEmpty() && content.length() <= MAX_CONTENT_LENGTH) {
                return List.of(new MemoryAction("save", null, kindOf(save.group(1)), content, null, false,
                        MAX_ACTION_RESULTS));
            }
            return List.of();
        }
        Matcher revise = EXPLICIT_REVISE.matcher(question);
        if (revise.matches()) {
            MemoryAction action = explicitRevision(projectId, viewId, questionMessageId, revise.group(1),
                    revise.group(2));
            return action == null ? List.of() : List.of(action);
        }
        if (externalSearchRequested) {
            String query = distinctiveSearchQuery(question);
            if (!query.isEmpty()) {
                return List.of(new MemoryAction("search", null, null, null, query, true, MAX_ACTION_RESULTS));
            }
        }
        return List.of();
    }

    /**
     * Revises only when exactly one active memory of this diagram contains every identifier of the target. Memories
     * extracted from the request itself are not candidates: they repeat its identifiers without being the target.
     */
    private MemoryAction explicitRevision(UUID projectId, UUID viewId, UUID questionMessageId, String target,
            String change) {
        List<String> identifiers = new ArrayList<>();
        Matcher word = SEARCH_WORD.matcher(target);
        while (word.find()) {
            if (word.group().chars().anyMatch(Character::isDigit)) {
                identifiers.add(word.group().toLowerCase(Locale.ROOT));
            }
        }
        String instruction = firstSentence(change);
        if (identifiers.isEmpty() || instruction.isEmpty()) {
            return null;
        }
        List<DiagramMemoryResource> matching = diagramMemoryService.memories(projectId, viewId, "ACTIVE").stream()
                .filter(memory -> questionMessageId == null || !questionMessageId.equals(memory.sourceMessageId()))
                .filter(memory -> identifiers.stream()
                        .allMatch(memory.content().toLowerCase(Locale.ROOT)::contains))
                .toList();
        if (matching.size() != 1) {
            return null;
        }
        DiagramMemoryResource predecessor = matching.get(0);
        String previous = predecessor.content().strip();
        String separator = previous.matches("(?s).*[.!?]$") ? " " : ". ";
        String revised = previous + separator + "Revisión: " + withoutTerminalPunctuation(instruction) + ".";
        if (revised.length() > MAX_CONTENT_LENGTH) {
            return null;
        }
        return new MemoryAction("revise", predecessor.memoryId(), predecessor.kind(), revised, null, false,
                MAX_ACTION_RESULTS);
    }

    private static String distinctiveSearchQuery(String question) {
        Set<String> terms = new java.util.LinkedHashSet<>();
        Matcher word = SEARCH_WORD.matcher(question.toLowerCase(Locale.ROOT));
        while (word.find() && terms.size() < MAX_SERVER_SEARCH_TERMS) {
            String term = word.group();
            boolean identifier = term.chars().anyMatch(Character::isDigit);
            if (identifier || (term.length() >= 4 && !SEARCH_NOISE.contains(term))) {
                terms.add(term);
            }
        }
        String query = String.join(" ", terms);
        return query.length() <= MAX_QUERY_LENGTH ? query : query.substring(0, MAX_QUERY_LENGTH).strip();
    }

    private static String firstSentence(String text) {
        String stripped = text == null ? "" : text.strip();
        return SENTENCE_BREAK.split(stripped, 2)[0].strip();
    }

    private static String withoutTerminalPunctuation(String text) {
        return text.replaceAll("[.!?]+$", "").strip();
    }

    private static String kindOf(String word) {
        String normalized = word.toLowerCase(Locale.ROOT);
        return normalized.startsWith("hip") || normalized.startsWith("hyp") ? "HYPOTHESIS" : "DECISION";
    }

    private String actionJson(MemoryAction action) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("operation", action.operation());
        if (action.memoryId() != null) {
            fields.put("memoryId", action.memoryId().toString());
        }
        if (action.writes()) {
            fields.put("kind", action.kind());
            fields.put("content", action.content());
        }
        if ("search".equals(action.operation())) {
            fields.put("query", action.query());
            fields.put("includeAccessibleProjects", action.includeAccessibleProjects());
            fields.put("limit", action.limit());
        }
        return json(Map.of("memoryAction", fields));
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
                    String kind = node.path("kind").textValue().strip().toUpperCase(Locale.ROOT);
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
