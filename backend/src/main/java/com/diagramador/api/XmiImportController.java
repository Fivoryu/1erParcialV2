package com.diagramador.api;

import com.diagramador.command.Author;
import com.diagramador.command.CommandResult;
import com.diagramador.domain.Project;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.xmi.XmiImportIssue;
import com.diagramador.xmi.XmiImportMode;
import com.diagramador.xmi.XmiRepair;
import com.diagramador.xmi.XmiResolution;
import com.diagramador.xmi.XmiImportService;
import com.diagramador.xmi.XmiReadingResult;
import com.diagramador.xmi.XmiReader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/projects/{projectId}/xmi", produces = MediaType.APPLICATION_JSON_VALUE)
public class XmiImportController {

    private final ProjectRepository projectRepository;
    private final ProjectAuthorizationService authorizationService;
    private final CurrentUserContext currentUserContext;
    private final XmiImportService importService;
    private final XmiReader reader = new XmiReader();

    public XmiImportController(ProjectRepository projectRepository,
            ProjectAuthorizationService authorizationService, CurrentUserContext currentUserContext,
            XmiImportService importService) {
        this.projectRepository = projectRepository;
        this.authorizationService = authorizationService;
        this.currentUserContext = currentUserContext;
        this.importService = importService;
    }

    @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public XmiPreviewResource preview(@PathVariable("projectId") UUID projectId,
            @Valid @RequestBody XmiFileRequest request) {
        requireProject(projectId);
        authorizationService.requireMember(projectId);
        byte[] rawBytes = decode(request.fileBase64());
        try {
            return preview(importService.preview(projectId, rawBytes, request.mode(), request.resolutions()),
                    rawBytes);
        } catch (IllegalArgumentException exception) {
            throw invalidPayload(exception.getMessage());
        }
    }

    @PostMapping(path = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public XmiImportResource importXmi(@PathVariable("projectId") UUID projectId,
            @Valid @RequestBody XmiImportRequest request) {
        requireProject(projectId);
        if (request.mode() == XmiImportMode.REEMPLAZAR) {
            // REEMPLAZAR deletes the existing model's views, which cascades view ->
            // chat_session -> message (PD-02/L33): destroying chat history is
            // OWNER-exclusive (PD-01, L36), so editors must not trigger it.
            authorizationService.requireOwner(projectId);
        } else {
            authorizationService.requireEditor(projectId);
        }
        var user = currentUserContext.require();
        byte[] rawBytes = decode(request.fileBase64());
        try {
            XmiImportService.XmiImportResult result = importService.importBytes(projectId,
                    new Author(user.userId(), user.username()), rawBytes, request.checksum(), request.mode(),
                    request.idempotencyKey(), request.resolutions());
            return new XmiImportResource(result.command().status().name(), result.command(), result.exchangeId(),
                    result.losses(), result.disagreements());
        } catch (IllegalArgumentException exception) {
            throw invalidPayload(exception.getMessage());
        }
    }

    private Project requireProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No se encontró el proyecto.",
                        List.of(), null));
    }

    private static byte[] decode(String fileBase64) {
        try {
            // Keep the uploaded payload as bytes all the way to the XMI reader and checksum calculation.
            return Base64.getDecoder().decode(fileBase64);
        } catch (IllegalArgumentException exception) {
            throw invalidPayload("The uploaded file must be valid base64.");
        }
    }

    /**
     * The dry run: counts of the file as parsed (unchanged), the losses the import would record (parse losses plus
     * the named repairs), and the new fields: issues still standing, the repairs applied, the resulting counts and
     * whether the import would end clean.
     */
    private static XmiPreviewResource preview(XmiImportService.XmiPreview preview, byte[] rawBytes) {
        XmiReadingResult parsed = preview.parsed();
        XmiImportService.Assessment assessment = preview.assessment();
        return new XmiPreviewResource(counts(parsed), assessment.reading().losses(), parsed.disagreements(),
                checksum(rawBytes), assessment.issues(), assessment.repairs(), counts(assessment.reading()),
                assessment.clean());
    }

    private static Counts counts(XmiReadingResult reading) {
        int figures = reading.diagrams().stream().mapToInt(diagram -> diagram.figures().size()).sum();
        int connectors = reading.diagrams().stream().mapToInt(diagram -> diagram.connectors().size()).sum();
        return new Counts(reading.model().classifiers().size(), reading.model().relationships().size(),
                reading.diagrams().size(), figures, connectors);
    }

    private static String checksum(byte[] rawBytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static ApiException invalidPayload(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD",
                message == null || message.isBlank() ? "The XMI request is invalid." : message, List.of(), null);
    }

    /** {@code mode} and {@code resolutions} are optional: the dry run narrows its checks to them when present. */
    public record XmiFileRequest(@NotBlank String fileBase64, XmiImportMode mode, List<XmiResolution> resolutions) {

        public XmiFileRequest {
            resolutions = resolutions == null ? List.of() : List.copyOf(resolutions);
        }
    }

    public record XmiImportRequest(
            @NotBlank String fileBase64,
            @NotBlank @Pattern(regexp = "(?i)[0-9a-f]{64}") String checksum,
            @NotNull XmiImportMode mode,
            @NotBlank @Size(max = 256) String idempotencyKey,
            List<XmiResolution> resolutions) {

        public XmiImportRequest {
            resolutions = resolutions == null ? List.of() : List.copyOf(resolutions);
        }
    }

    public record Counts(int classifiers, int relationships, int views, int figurePlacements,
            int connectorPlacements) {
    }

    public record XmiPreviewResource(Counts counts, List<XmiReadingResult.Loss> losses,
            List<XmiReadingResult.Disagreement> disagreements, String checksum, List<XmiImportIssue> issues,
            List<XmiRepair.Repair> repairs, Counts resultingCounts, boolean clean) {

        public XmiPreviewResource {
            losses = List.copyOf(losses);
            disagreements = List.copyOf(disagreements);
            issues = List.copyOf(issues);
            repairs = List.copyOf(repairs);
        }
    }

    public record XmiImportResource(String outcome, CommandResult command, UUID exchangeId,
            List<XmiReadingResult.Loss> losses, List<XmiReadingResult.Disagreement> disagreements) {

        public XmiImportResource {
            losses = List.copyOf(losses);
            disagreements = List.copyOf(disagreements);
        }
    }
}
