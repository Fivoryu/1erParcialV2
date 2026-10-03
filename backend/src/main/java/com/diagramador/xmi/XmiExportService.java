package com.diagramador.xmi;

import com.diagramador.api.ApiException;
import com.diagramador.api.ModelResource;
import com.diagramador.api.ProjectApiService;
import com.diagramador.collaboration.EditingLeaseService;
import com.diagramador.command.ValidationState;
import com.diagramador.domain.ExchangeDirection;
import com.diagramador.domain.ExchangeStatus;
import com.diagramador.domain.ModelVersion;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.XmiExchange;
import com.diagramador.domain.XmiLossItem;
import com.diagramador.persistence.ModelVersionRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.XmiExchangeRepository;
import com.diagramador.persistence.XmiLossItemRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exports the current project snapshot assembled by the project API service. */
@Service
public class XmiExportService {

    public static final String PROFILE = XmiImportService.PROFILE;

    private final ProjectApiService projectApiService;
    private final EditingLeaseService editingLeaseService;
    private final ModelVersionRepository versionRepository;
    private final UmlModelRepository modelRepository;
    private final XmiExchangeRepository exchangeRepository;
    private final XmiLossItemRepository lossItemRepository;
    private final XmiSemanticEmitter emitter = new XmiSemanticEmitter();

    public XmiExportService(ProjectApiService projectApiService, EditingLeaseService editingLeaseService,
            ModelVersionRepository versionRepository, UmlModelRepository modelRepository,
            XmiExchangeRepository exchangeRepository, XmiLossItemRepository lossItemRepository) {
        this.projectApiService = projectApiService;
        this.editingLeaseService = editingLeaseService;
        this.versionRepository = versionRepository;
        this.modelRepository = modelRepository;
        this.exchangeRepository = exchangeRepository;
        this.lossItemRepository = lossItemRepository;
    }

    @Transactional
    public XmiExportResult exportProject(UUID projectId) {
        ModelResource snapshot = projectApiService.getModel(projectId);
        // The declared export guard closes here, against the very model id this export reads.
        editingLeaseService.requireExportAllowed(snapshot.id());
        ModelVersion version = versionRepository.findById(snapshot.versionId())
                .orElseThrow(() -> invalidModel("The current model version was not found."));
        if (version.getValidationState() != ValidationState.VALID) {
            throw invalidModel("The current model version is not valid for export.");
        }
        requireNoNameCollisions(snapshot);
        if (snapshot.views().stream().noneMatch(ModelResource.ViewResource::isDefault)) {
            throw invalidModel("The model has no default diagram view to export.");
        }

        XmiEmissionResult emission = emitter.emit(snapshot);
        byte[] document = emission.document().getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(document);
        UmlModel model = modelRepository.findById(snapshot.id())
                .orElseThrow(() -> invalidModel("The current model was not found."));
        XmiExchange exchange = XmiExchange.create(model, ExchangeDirection.EXPORT, PROFILE, snapshot.revision(),
                checksum, null);
        if (!emission.losses().isEmpty()) {
            exchange.setStatus(ExchangeStatus.PARTIAL);
        }
        XmiExchange savedExchange = exchangeRepository.saveAndFlush(exchange);
        List<XmiLossItem> items = emission.losses().stream()
                .map(loss -> savedExchange.addLossItem(SeverityKind.WARNING, null,
                        loss.name() + ": " + loss.count() + " item(s) could not be represented."))
                .toList();
        if (!items.isEmpty()) {
            lossItemRepository.saveAllAndFlush(items);
        }
        return new XmiExportResult(document, checksum, savedExchange.getId(), emission.losses());
    }

    private static void requireNoNameCollisions(ModelResource snapshot) {
        Set<NameKey> names = new HashSet<>();
        for (ModelResource.ClassifierResource classifier : snapshot.classifiers()) {
            if (!names.add(new NameKey(classifier.namespaceId(), classifier.name()))) {
                throw new ApiException(HttpStatus.CONFLICT, "NAME_COLLISION_BLOCKS",
                        "Classifier name '" + classifier.name() + "' collides within its namespace and blocks export.",
                        List.of(), null);
            }
        }
    }

    private static ApiException invalidModel(String message) {
        return new ApiException(HttpStatus.CONFLICT, "MODEL_INVALID", message, List.of(), null);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private record NameKey(UUID namespaceId, String name) {
    }

    public record XmiExportResult(byte[] document, String checksum, UUID exchangeId,
            List<XmiEmissionResult.Loss> losses) {

        public XmiExportResult {
            document = Objects.requireNonNull(document, "document").clone();
            Objects.requireNonNull(checksum, "checksum");
            Objects.requireNonNull(exchangeId, "exchangeId");
            losses = List.copyOf(losses);
        }

        @Override
        public byte[] document() {
            return document.clone();
        }
    }
}
