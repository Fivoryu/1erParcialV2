package com.diagramador.persistence;

import com.diagramador.domain.TemplateVersion;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateVersionRepository extends JpaRepository<TemplateVersion, UUID> {
}
