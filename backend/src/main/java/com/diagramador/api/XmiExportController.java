package com.diagramador.api;

import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.xmi.XmiEmissionResult;
import com.diagramador.xmi.XmiExportService;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/projects/{projectId}/xmi", produces = MediaType.APPLICATION_JSON_VALUE)
public class XmiExportController {

    private final ProjectRepository projectRepository;
    private final ProjectAuthorizationService authorizationService;
    private final XmiExportService exportService;

    public XmiExportController(ProjectRepository projectRepository,
            ProjectAuthorizationService authorizationService, XmiExportService exportService) {
        this.projectRepository = projectRepository;
        this.authorizationService = authorizationService;
        this.exportService = exportService;
    }

    @PostMapping("/export")
    public XmiExportResource exportXmi(@PathVariable("projectId") UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Project was not found.",
                        List.of(), null));
        authorizationService.requireEditor(projectId);
        // TODO(consolidation): wire the declared export editing guard when its sibling increment is present.
        XmiExportService.XmiExportResult result = exportService.exportProject(projectId);
        return new XmiExportResource(Base64.getEncoder().encodeToString(result.document()), result.checksum(),
                result.exchangeId(), result.losses());
    }

    public record XmiExportResource(String fileBase64, String checksum, UUID exchangeId,
            List<XmiEmissionResult.Loss> losses) {

        public XmiExportResource {
            losses = List.copyOf(losses);
        }
    }
}
