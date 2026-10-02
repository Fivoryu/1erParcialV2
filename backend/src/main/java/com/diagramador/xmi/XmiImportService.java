package com.diagramador.xmi;

import com.diagramador.command.Author;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.handler.XmiImportHandler;
import com.diagramador.command.Precondition;
import com.diagramador.domain.ExchangeDirection;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.XmiExchange;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.XmiExchangeRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
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

    /**
     * Imports raw uploaded bytes. The checksum comparison happens before parsing, repository access, or command
     * submission so a text-decoding round trip cannot alter the bytes being authenticated.
     */
    public XmiImportResult importBytes(UUID projectId, Author author, byte[] rawBytes, String suppliedChecksum,
            XmiImportMode mode, String idempotencyKey) {
        String checksum = verifyChecksum(rawBytes, suppliedChecksum);
        XmiReadingResult reading = withApplicationLosses(reader.read(rawBytes));
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(mode, "mode");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("An idempotency key is required for an XMI import.");
        }

        UmlModel model = modelRepository.findByProjectId(projectId)
                .orElseThrow(() -> new IllegalArgumentException("The project has no model to import into."));
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
        UUID operationId = UUID.randomUUID();
        OperationEnvelope envelope = new OperationEnvelope(operationId, COMMAND_TYPE, author, projectId,
                model.getId(), model.getCurrentRevision(), List.of(), preconditions, payload, CommandOrigin.MANUAL,
                null, idempotencyKey);
        CommandResult commandResult = commandService.execute(envelope);

        UUID exchangeId = null;
        if (commandResult.status() == com.diagramador.command.CommandStatus.ACCEPTED) {
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
