package com.diagramador.xmi;

import com.diagramador.api.ModelResource;
import com.diagramador.assistant.ChatAssistantProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Proposes the closed resolutions (RENAME / SKIP) for the name collisions an XMI import reports. The local AI only
 * sees the issue list (never file bytes) and may only choose among each issue's own options; every choice is
 * validated and anything invalid or missing is completed by a deterministic rule, so the result never depends on
 * the AI being available. The AI never chooses the import mode.
 */
@Component
public class XmiResolutionSuggester {

    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_RULES = "RULES";

    private static final Set<String> COLLISION_CODES =
            Set.of("NAME_COLLISION_IN_FILE", "NAME_COLLISION", "DIAGRAM_NAME_COLLISION");
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_EXPLANATION_LENGTH = 300;
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "xmi-suggest-ai");
        thread.setDaemon(true);
        return thread;
    });

    private static final String SYSTEM_PROMPT = "Resolvés conflictos de nombres al importar un diagrama UML. "
            + "Respondé SOLO con un arreglo JSON estricto y, en una segunda línea, una razón de una oración que "
            + "empiece con 'Razón:'. Cada elemento del arreglo: "
            + "{\"subject\":\"<id>\",\"action\":\"RENAME|SKIP\",\"newName\":\"<nombre nuevo, solo si RENAME>\"}. "
            + "La acción debe ser una de las opciones del problema y el nombre nuevo no puede repetir ninguno de "
            + "los nombres existentes listados.";

    private final ChatAssistantProvider provider;
    private final long timeoutMillis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public XmiResolutionSuggester(ChatAssistantProvider provider,
            @Value("${diagrammer.xmi.suggest-timeout-ms:20000}") long timeoutMillis) {
        this.provider = provider;
        this.timeoutMillis = timeoutMillis;
    }

    /** {@code source} is "AI" only when at least one resolution came from the AI. */
    public record Suggestion(List<XmiResolution> resolutions, String source, String explanation) {

        public Suggestion {
            resolutions = List.copyOf(resolutions);
        }
    }

    /** One collision to resolve; the options are the closed set the AI may choose from. */
    private record Target(String subject, String name, boolean diagram, UUID namespaceId, boolean canRename,
            boolean canSkip) {
    }

    private record AiChoice(String action, String newName) {
    }

    /**
     * @param dryRun applies a resolution list through the same pipeline as the preview and returns the issues
     *               left, so the suggestion can check it leaves no blocker
     */
    public Suggestion suggest(XmiReadingResult parsed, List<XmiImportIssue> issues,
            XmiImportValidator.ExistingModel existing, Function<List<XmiResolution>, List<XmiImportIssue>> dryRun) {
        List<Target> targets = targets(parsed, issues);
        if (targets.isEmpty()) {
            return new Suggestion(List.of(), SOURCE_RULES, "No hay conflictos de nombres que resolver.");
        }
        Map<String, Set<String>> taken = takenNames(parsed, existing);
        String reply = askAi(targets, taken);
        Map<String, AiChoice> choices = parseChoices(reply);
        String reason = parseReason(reply);

        List<XmiResolution> resolutions = new ArrayList<>();
        int fromAi = 0;
        for (Target target : targets) {
            Set<String> names = taken.computeIfAbsent(scope(target), key -> new HashSet<>());
            XmiResolution resolution = validated(target, choices.get(target.subject()), names);
            if (resolution != null) {
                fromAi++;
            } else {
                resolution = byRule(target, names);
            }
            if (resolution.newName() != null) {
                names.add(key(resolution.newName()));
            }
            resolutions.add(resolution);
        }
        String source = fromAi > 0 ? SOURCE_AI : SOURCE_RULES;
        StringBuilder explanation = new StringBuilder();
        if (fromAi > 0 && !reason.isBlank()) {
            explanation.append(reason);
        } else {
            explanation.append(fromAi > 0 ? "La IA propuso las resoluciones y se validaron contra las opciones."
                    : "Se aplicó una regla determinista: los nombres repetidos se renombran con un sufijo numérico.");
        }
        explanation.append(remainingBlockers(resolutions, dryRun));
        return new Suggestion(resolutions, source, explanation.toString());
    }

    // ---- targets and taken names -------------------------------------------------------------------------

    private static List<Target> targets(XmiReadingResult parsed, List<XmiImportIssue> issues) {
        Map<UUID, ModelResource.ClassifierResource> classifiers = new HashMap<>();
        parsed.model().classifiers().forEach(item -> {
            if (item.id() != null) {
                classifiers.put(item.id(), item);
            }
        });
        // One resolution per subject: a classifier can collide in the file and with the stored model at once.
        Map<String, Target> bySubject = new LinkedHashMap<>();
        for (XmiImportIssue issue : issues) {
            if (issue.severity() != XmiImportIssue.Severity.BLOCKER || !COLLISION_CODES.contains(issue.code())
                    || issue.subject() == null) {
                continue;
            }
            boolean diagram = "DIAGRAM_NAME_COLLISION".equals(issue.code());
            boolean rename = issue.options().contains(XmiImportIssue.Option.RENAME);
            boolean skip = issue.options().contains(XmiImportIssue.Option.SKIP);
            UUID namespace = null;
            if (!diagram) {
                try {
                    var classifier = classifiers.get(UUID.fromString(issue.subject()));
                    namespace = classifier == null ? null : classifier.namespaceId();
                } catch (IllegalArgumentException exception) {
                    continue;
                }
            }
            Target previous = bySubject.get(issue.subject());
            if (previous != null) {
                rename &= previous.canRename();
                skip &= previous.canSkip();
            }
            bySubject.put(issue.subject(),
                    new Target(issue.subject(), issue.name(), diagram, namespace, rename, skip));
        }
        return new ArrayList<>(bySubject.values());
    }

    private static String scope(Target target) {
        return target.diagram() ? "views" : "ns:" + target.namespaceId();
    }

    /** Names already in use, per namespace (and one scope for diagrams): the file's own and the stored model's. */
    private static Map<String, Set<String>> takenNames(XmiReadingResult parsed,
            XmiImportValidator.ExistingModel existing) {
        Map<String, Set<String>> taken = new HashMap<>();
        for (ModelResource.ClassifierResource item : parsed.model().classifiers()) {
            if (item.name() != null) {
                taken.computeIfAbsent("ns:" + item.namespaceId(), k -> new HashSet<>()).add(key(item.name()));
            }
        }
        for (var item : existing.classifiers()) {
            if (item.name() != null) {
                taken.computeIfAbsent("ns:" + item.namespaceId(), k -> new HashSet<>()).add(key(item.name()));
            }
        }
        Set<String> views = taken.computeIfAbsent("views", k -> new HashSet<>());
        existing.viewNames().forEach(name -> views.add(key(name)));
        parsed.model().views().forEach(view -> {
            if (view.name() != null) {
                views.add(key(view.name()));
            }
        });
        return taken;
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    // ---- AI ----------------------------------------------------------------------------------------------

    private String askAi(List<Target> targets, Map<String, Set<String>> taken) {
        String prompt = prompt(targets, taken);
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> provider.chat(List.of(
                new ChatAssistantProvider.Turn("system", SYSTEM_PROMPT),
                new ChatAssistantProvider.Turn("user", prompt))), EXECUTOR);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        } catch (Exception exception) {
            // Disabled, failed or too slow: the rules decide, silently.
            future.cancel(true);
            return null;
        }
    }

    private static String prompt(List<Target> targets, Map<String, Set<String>> taken) {
        StringBuilder text = new StringBuilder("Problemas de importación:\n");
        for (Target target : targets) {
            List<String> options = new ArrayList<>();
            if (target.canRename()) {
                options.add("RENAME");
            }
            if (target.canSkip()) {
                options.add("SKIP");
            }
            text.append("- subject=").append(target.subject()).append(" tipo=")
                    .append(target.diagram() ? "diagrama" : "clase").append(" nombre='")
                    .append(target.name()).append("' opciones=").append(options)
                    .append(" nombres_existentes=")
                    .append(taken.getOrDefault(scope(target), Set.of()).stream().sorted().toList()).append('\n');
        }
        return text.toString();
    }

    private Map<String, AiChoice> parseChoices(String reply) {
        Map<String, AiChoice> choices = new HashMap<>();
        if (reply == null) {
            return choices;
        }
        int start = reply.indexOf('[');
        int end = reply.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return choices;
        }
        try {
            JsonNode array = objectMapper.readTree(reply.substring(start, end + 1));
            if (!array.isArray()) {
                return choices;
            }
            for (JsonNode item : array) {
                String subject = item.path("subject").asText(null);
                String action = item.path("action").asText(null);
                if (subject != null && action != null) {
                    choices.putIfAbsent(subject.strip().toLowerCase(Locale.ROOT),
                            new AiChoice(action.strip().toUpperCase(Locale.ROOT), item.path("newName").asText(null)));
                }
            }
        } catch (Exception exception) {
            return new HashMap<>();
        }
        return choices;
    }

    private static String parseReason(String reply) {
        if (reply == null) {
            return "";
        }
        int end = reply.lastIndexOf(']');
        String tail = end >= 0 ? reply.substring(end + 1) : "";
        tail = tail.strip().replaceFirst("(?i)^raz[oó]n\\s*:\\s*", "").strip();
        return tail.length() > MAX_EXPLANATION_LENGTH ? tail.substring(0, MAX_EXPLANATION_LENGTH) : tail;
    }

    // ---- validation and rules ----------------------------------------------------------------------------

    /** The AI choice when it is within the issue's options and its name is free, otherwise null. */
    private static XmiResolution validated(Target target, AiChoice choice, Set<String> names) {
        if (choice == null) {
            return null;
        }
        if ("SKIP".equals(choice.action()) && target.canSkip()) {
            return new XmiResolution(target.subject(), XmiResolution.Action.SKIP, null);
        }
        if ("RENAME".equals(choice.action()) && target.canRename() && choice.newName() != null) {
            String name = choice.newName().strip();
            if (!name.isEmpty() && name.length() <= MAX_NAME_LENGTH && !names.contains(key(name))
                    && !Objects.equals(key(name), key(target.name() == null ? "" : target.name()))) {
                return new XmiResolution(target.subject(), XmiResolution.Action.RENAME, name);
            }
        }
        return null;
    }

    private static XmiResolution byRule(Target target, Set<String> names) {
        if (target.canRename()) {
            String base = target.name() == null || target.name().isBlank() ? "Elemento" : target.name().strip();
            for (int suffix = 2;; suffix++) {
                String candidate = base + " (" + suffix + ")";
                if (!names.contains(key(candidate))) {
                    return new XmiResolution(target.subject(), XmiResolution.Action.RENAME, candidate);
                }
            }
        }
        return new XmiResolution(target.subject(), XmiResolution.Action.SKIP, null);
    }

    private static String remainingBlockers(List<XmiResolution> resolutions,
            Function<List<XmiResolution>, List<XmiImportIssue>> dryRun) {
        try {
            List<XmiImportIssue> blockers = dryRun.apply(resolutions).stream()
                    .filter(issue -> issue.severity() == XmiImportIssue.Severity.BLOCKER).toList();
            if (blockers.isEmpty()) {
                return "";
            }
            StringBuilder text = new StringBuilder(" Aun con estas resoluciones quedan ").append(blockers.size())
                    .append(" problema(s) que bloquean la importación: ");
            text.append(String.join(" ", blockers.stream().limit(3).map(XmiImportIssue::message).toList()));
            return text.toString();
        } catch (RuntimeException exception) {
            return "";
        }
    }
}
