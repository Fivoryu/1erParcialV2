package com.diagramador.xmi;

import com.diagramador.command.Author;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.Diagnostic;
import com.diagramador.command.ErrorCode;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Precondition;
import com.diagramador.command.handler.XmiImportHandler;
import com.diagramador.domain.ExchangeDirection;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.XmiExchange;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.XmiExchangeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Verifies the uploaded bytes and submits the parsed model through the atomic command path. */
@Service
public class XmiImportService {

    public static final String COMMAND_TYPE = "xmi.import";
    public static final String PROFILE = "Enterprise Architect 15.0.1514 / XMI 2.1";

    private final XmiReader reader = new XmiReader();
    private final XmiResolutionApplier resolutionApplier = new XmiResolutionApplier();
    private final XmiRepair repair = new XmiRepair();
    private final XmiImportValidator validator = new XmiImportValidator();
    private final XmiSemanticGate semanticGate = new XmiSemanticGate();
    private final UmlModelRepository modelRepository;
    private final CommandService commandService;
    private final XmiImportHandler importHandler;
    private final XmiExchangeRepository exchangeRepository;

    public XmiImportService(UmlModelRepository modelRepository, CommandService commandService,
            XmiImportHandler importHandler, XmiExchangeRepository exchangeRepository) {
        this.modelRepository = modelRepository;
        this.commandService = commandService;
        this.importHandler = importHandler;
        this.exchangeRepository = exchangeRepository;
    }

    /** Imports without resolutions: deterministic repairs still apply, while choices stay blockers. */
    public XmiImportResult importBytes(UUID projectId, Author author, byte[] rawBytes, String suppliedChecksum,
            XmiImportMode mode, String idempotencyKey) {
        return importBytes(projectId, author, rawBytes, suppliedChecksum, mode, idempotencyKey, List.of());
    }

    /**
     * Imports raw uploaded bytes. The checksum comparison happens before parsing, repository access, or command
     * submission so a text-decoding round trip cannot alter the bytes being authenticated. After parsing, the
     * resolutions are applied and the deterministic repairs run; the import is only submitted when the resulting
     * model passes the semantic rules, and the handler re-runs the same exchange checks inside its transaction.
     */
    public XmiImportResult importBytes(UUID projectId, Author author, byte[] rawBytes, String suppliedChecksum,
            XmiImportMode mode, String idempotencyKey, List<XmiResolution> resolutions) {
        String checksum = verifyChecksum(rawBytes, suppliedChecksum);
        XmiReadingResult parsed = withApplicationLosses(reader.read(rawBytes));
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(mode, "mode");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("An idempotency key is required for an XMI import.");
        }

        UmlModel model = modelRepository.findByProjectId(projectId)
                .orElseThrow(() -> new IllegalArgumentException("The project has no model to import into."));
        Assessment assessment = assess(model, parsed, mode, resolutions);
        XmiReadingResult reading = assessment.reading();
        UUID operationId = UUID.randomUUID();
        if (assessment.rejectedBySemanticRules()) {
            List<Diagnostic> diagnostics = assessment.semanticBlockers().stream()
                    .map(issue -> new Diagnostic(ErrorCode.PRECONDITION_FAILED,
                            "La importación no quedaría limpia: " + issue.message()))
                    .toList();
            return new XmiImportResult(new CommandResult(operationId, CommandStatus.REJECTED, null, diagnostics, null),
                    null, reading.losses(), reading.disagreements());
        }
        reading = importHandler.scopeConflictingIdentities(model, reading);
        List<Precondition> preconditions = mode == XmiImportMode.REEMPLAZAR
                ? List.of(new Precondition("cascade_count",
                        importHandler.countDeletedTargets(model, reading), null))
                : List.of();
        Map<String, Object> payload = Map.of(
                "mode", mode.name(),
                "profile", PROFILE,
                "checksum", checksum,
                "readingResult", reading);
        OperationEnvelope envelope = new OperationEnvelope(operationId, COMMAND_TYPE, author, projectId,
                model.getId(), model.getCurrentRevision(), List.of(), preconditions, payload, CommandOrigin.MANUAL,
                null, idempotencyKey);
        CommandResult commandResult = commandService.execute(envelope);

        UUID exchangeId = null;
        if (commandResult.status() == CommandStatus.ACCEPTED) {
            long revision = commandResult.resultRevision();
            exchangeId = exchangeRepository.findAll().stream()
                    .filter(exchange -> exchange.getModel().getId().equals(model.getId()))
                    .filter(exchange -> exchange.getDirection() == ExchangeDirection.IMPORT)
                    .filter(exchange -> Objects.equals(exchange.getChecksum(), checksum))
                    .filter(exchange -> Objects.equals(exchange.getModelRevision(), revision))
                    .map(XmiExchange::getId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("The accepted XMI import did not record its exchange."));
        }
        return new XmiImportResult(commandResult, exchangeId, reading.losses(), reading.disagreements());
    }

    /**
     * Dry run of an import: parses, applies the resolutions and repairs, and reports every issue and repair without
     * writing anything. {@code mode} may be null, in which case only the checks that do not depend on the target
     * model run.
     */
    public XmiPreview preview(UUID projectId, byte[] rawBytes, XmiImportMode mode, List<XmiResolution> resolutions) {
        XmiReadingResult parsed = withApplicationLosses(reader.read(rawBytes));
        UmlModel model = modelRepository.findByProjectId(projectId).orElse(null);
        return new XmiPreview(parsed, assess(model, parsed, mode, resolutions));
    }

    /** What AGREGAR would collide with in the stored model; empty for any other mode. */
    public XmiImportValidator.ExistingModel existingModel(UUID projectId, XmiImportMode mode) {
        UmlModel model = modelRepository.findByProjectId(projectId).orElse(null);
        return mode == XmiImportMode.AGREGAR && model != null ? importHandler.existingModel(model)
                : XmiImportValidator.ExistingModel.EMPTY;
    }

    private Assessment assess(UmlModel model, XmiReadingResult parsed, XmiImportMode mode,
            List<XmiResolution> resolutions) {
        XmiRepair.Result resolved = resolutionApplier.apply(parsed, resolutions);
        XmiRepair.Result repaired = repair.repair(resolved.reading());
        List<XmiRepair.Repair> repairs = new ArrayList<>(resolved.repairs());
        repairs.addAll(repaired.repairs());
        XmiReadingResult reading = repaired.reading();
        XmiImportValidator.ExistingModel existing = mode == XmiImportMode.AGREGAR && model != null
                ? importHandler.existingModel(model) : XmiImportValidator.ExistingModel.EMPTY;
        return new Assessment(reading, repairs, validator.validate(reading, mode, existing), semanticGate.issues(reading));
    }

    private static String verifyChecksum(byte[] rawBytes, String suppliedChecksum) {
        if (rawBytes == null || suppliedChecksum == null || !suppliedChecksum.matches("(?i)[0-9a-f]{64}")) {
            throw new ChecksumMismatchException("A SHA-256 checksum and the raw uploaded bytes are required.");
        }
        String actual = sha256(rawBytes);
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                suppliedChecksum.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            throw new ChecksumMismatchException("The supplied checksum does not match the uploaded bytes.");
        }
        return actual;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static XmiReadingResult withApplicationLosses(XmiReadingResult reading) {
        if (reading.documentation().isEmpty()) {
            return reading;
        }
        List<XmiReadingResult.Loss> losses = new ArrayList<>(reading.losses());
        losses.add(new XmiReadingResult.Loss("classifier_documentation_not_persisted", reading.documentation().size()));
        return new XmiReadingResult(reading.model(), reading.diagrams(), reading.documentation(),
                reading.disagreements(), losses);
    }

    /** The reading after resolutions and repairs, what was repaired, and every issue still standing. */
    public record Assessment(XmiReadingResult reading, List<XmiRepair.Repair> repairs,
            List<XmiImportIssue> structuralIssues, List<XmiImportIssue> semanticIssues) {

        public Assessment {
            repairs = List.copyOf(repairs);
            structuralIssues = List.copyOf(structuralIssues);
            semanticIssues = List.copyOf(semanticIssues);
        }

        public List<XmiImportIssue> issues() {
            List<XmiImportIssue> all = new ArrayList<>(structuralIssues);
            all.addAll(semanticIssues);
            return all;
        }

        List<XmiImportIssue> semanticBlockers() {
            return semanticIssues.stream().filter(issue -> issue.severity() == XmiImportIssue.Severity.BLOCKER)
                    .toList();
        }

        /**
         * Exchange blockers are left to the handler, which rejects with the code and text it always used; only a
         * file that is otherwise importable but would leave semantic errors behind is refused up front.
         */
        boolean rejectedBySemanticRules() {
            return structuralIssues.stream().noneMatch(issue -> issue.severity() == XmiImportIssue.Severity.BLOCKER)
                    && !semanticBlockers().isEmpty();
        }

        /** True when nothing blocks the import: no exchange blocker and no semantic error. */
        public boolean clean() {
            return issues().stream().noneMatch(issue -> issue.severity() == XmiImportIssue.Severity.BLOCKER);
        }
    }

    /** The parsed file next to what an import of it would do. */
    public record XmiPreview(XmiReadingResult parsed, Assessment assessment) {
    }

    /** Losses are returned with the command outcome, not merely recorded in the database. */
    public record XmiImportResult(CommandResult command, UUID exchangeId,
            List<XmiReadingResult.Loss> losses, List<XmiReadingResult.Disagreement> disagreements) {

        public XmiImportResult {
            losses = List.copyOf(losses);
            disagreements = List.copyOf(disagreements);
        }
    }

    public static final class ChecksumMismatchException extends IllegalArgumentException {

        public ChecksumMismatchException(String message) {
            super(message);
        }
    }
}
