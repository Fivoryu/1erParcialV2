package com.diagramador.vcs;

import com.diagramador.api.ApiException;
import com.diagramador.domain.UmlModel;
import com.diagramador.persistence.UmlModelRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Renders recent diagram history as compact prose for the local assistant context. */
@Component
public class VcsRepoContextRenderer {

    private static final int RECENT_COMMITS = 5;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\b");
    private static final Pattern HEX_DIGEST_PATTERN = Pattern.compile("(?i)\\b[0-9a-f]{64}\\b");
    private static final Pattern WEB_PROTOCOL_PATTERN = Pattern.compile("(?i)\\bhttps?://\\S+|\\bhttp\\b");
    private static final Pattern JSON_WORD_PATTERN = Pattern.compile("(?i)\\bjson\\b");

    private final VcsRepoQueryService repoQueryService;
    private final UmlModelRepository modelRepository;

    public VcsRepoContextRenderer(VcsRepoQueryService repoQueryService, UmlModelRepository modelRepository) {
        this.repoQueryService = repoQueryService;
        this.modelRepository = modelRepository;
    }

    public String render(UUID modelId) {
        UmlModel model = modelRepository.findById(modelId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "No se encontró el modelo.", List.of(), null));
        List<VcsResources.Commit> commits = repoQueryService.history(model, null, null, RECENT_COMMITS);
        return renderHistory(commits, OffsetDateTime.now());
    }

    private static String renderHistory(List<VcsResources.Commit> commits, OffsetDateTime now) {
        if (commits.isEmpty()) {
            return "Historial del diagrama: todavía no hay commits.";
        }
        if (commits.size() == 1) {
            return "Historial del diagrama: tiene un solo commit, " + describe(commits.getFirst(), now) + ".";
        }
        StringBuilder block = new StringBuilder("Historial del diagrama:");
        for (VcsResources.Commit commit : commits) {
            block.append("\n- ").append(describe(commit, now));
        }
        return block.toString();
    }

    private static String describe(VcsResources.Commit commit, OffsetDateTime now) {
        String author = clean(commit.authorDisplayName(), "autor desconocido");
        String message = clean(commit.message(), "sin mensaje");
        return author + " — «" + message + "» (" + relativeTime(commit.createdAt(), now) + ")";
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String cleaned = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").strip();
        cleaned = UUID_PATTERN.matcher(cleaned).replaceAll("[identificador omitido]");
        cleaned = HEX_DIGEST_PATTERN.matcher(cleaned).replaceAll("[huella omitida]");
        cleaned = WEB_PROTOCOL_PATTERN.matcher(cleaned).replaceAll("[referencia web omitida]");
        cleaned = JSON_WORD_PATTERN.matcher(cleaned).replaceAll("texto estructurado");
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private static String relativeTime(OffsetDateTime createdAt, OffsetDateTime now) {
        if (createdAt == null) {
            return "fecha desconocida";
        }
        Duration elapsed = Duration.between(createdAt, now);
        if (elapsed.isNegative()) {
            return "ahora";
        }
        long minutes = elapsed.toMinutes();
        if (minutes < 1) {
            return "hace unos segundos";
        }
        if (minutes < 60) {
            return "hace " + minutes + (minutes == 1 ? " minuto" : " minutos");
        }
        long hours = elapsed.toHours();
        if (hours < 24) {
            return "hace " + hours + (hours == 1 ? " hora" : " horas");
        }
        long days = elapsed.toDays();
        if (days < 30) {
            return "hace " + days + (days == 1 ? " día" : " días");
        }
        long months = Math.max(1, days / 30);
        if (months < 12) {
            return "hace " + months + (months == 1 ? " mes" : " meses");
        }
        long years = Math.max(1, months / 12);
        return "hace " + years + (years == 1 ? " año" : " años");
    }
}
