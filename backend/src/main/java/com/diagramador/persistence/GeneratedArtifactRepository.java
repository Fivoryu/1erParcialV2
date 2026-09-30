package com.diagramador.persistence;

import com.diagramador.domain.GeneratedArtifact;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GeneratedArtifactRepository extends JpaRepository<GeneratedArtifact, UUID> {

    List<GeneratedArtifact> findByRunIdOrderByPathAsc(UUID runId);

    @Modifying
    @Query(value = "INSERT INTO diagrammer.generated_artifact "
            + "(run_id, path, kind, content_hash, size_bytes) "
            + "VALUES (:runId, :path, :kind, :contentHash, :sizeBytes) "
            + "ON CONFLICT (run_id, path) DO UPDATE SET kind = EXCLUDED.kind, "
            + "content_hash = EXCLUDED.content_hash, size_bytes = EXCLUDED.size_bytes, uri = NULL",
            nativeQuery = true)
    int upsertForRun(@Param("runId") UUID runId, @Param("path") String path, @Param("kind") String kind,
            @Param("contentHash") String contentHash, @Param("sizeBytes") long sizeBytes);
}
