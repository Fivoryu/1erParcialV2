package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Row of {@code diagrammer.generated_artifact}. */
@Entity
@Table(name = "generated_artifact", schema = "diagrammer")
public class GeneratedArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "artifact_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private GenerationRun run;

    @Column(name = "path", nullable = false, length = 512)
    private String path;

    @Column(name = "kind", nullable = false, length = 64)
    private String kind;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, columnDefinition = "char(64)")
    private String contentHash;

    @Column(name = "uri", length = 1024)
    private String uri;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    protected GeneratedArtifact() {
    }

    public UUID getId() {
        return id;
    }

    public GenerationRun getRun() {
        return run;
    }

    public void setRun(GenerationRun run) {
        this.run = run;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }
}
