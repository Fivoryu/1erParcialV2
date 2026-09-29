package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Row of {@code diagrammer.generation_run}. */
@Entity
@Table(name = "generation_run", schema = "diagrammer")
public class GenerationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private ModelVersion version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id", nullable = false)
    private GenerationTarget target;

    @Column(name = "target_config_revision", nullable = false)
    private int targetConfigRevision;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private GenerationProfile profile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private TemplateVersion template;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "input_snapshot_hash", nullable = false, columnDefinition = "char(64)")
    private String inputSnapshotHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "artifact_set_hash", columnDefinition = "char(64)")
    private String artifactSetHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "artifact_zip_hash", columnDefinition = "char(64)")
    private String artifactZipHash;

    @Column(name = "artifact_zip_uri", length = 1024)
    private String artifactZipUri;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "state", nullable = false)
    private GenerationState state = GenerationState.PENDING;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    protected GenerationRun() {
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public void setModel(UmlModel model) {
        this.model = model;
    }

    public ModelVersion getVersion() {
        return version;
    }

    public void setVersion(ModelVersion version) {
        this.version = version;
    }

    public GenerationTarget getTarget() {
        return target;
    }

    public void setTarget(GenerationTarget target) {
        this.target = target;
    }

    public int getTargetConfigRevision() {
        return targetConfigRevision;
    }

    public void setTargetConfigRevision(int targetConfigRevision) {
        this.targetConfigRevision = targetConfigRevision;
    }

    public GenerationProfile getProfile() {
        return profile;
    }

    public void setProfile(GenerationProfile profile) {
        this.profile = profile;
    }

    public TemplateVersion getTemplate() {
        return template;
    }

    public void setTemplate(TemplateVersion template) {
        this.template = template;
    }

    public String getInputSnapshotHash() {
        return inputSnapshotHash;
    }

    public void setInputSnapshotHash(String inputSnapshotHash) {
        this.inputSnapshotHash = inputSnapshotHash;
    }

    public String getArtifactSetHash() {
        return artifactSetHash;
    }

    public void setArtifactSetHash(String artifactSetHash) {
        this.artifactSetHash = artifactSetHash;
    }

    public String getArtifactZipHash() {
        return artifactZipHash;
    }

    public void setArtifactZipHash(String artifactZipHash) {
        this.artifactZipHash = artifactZipHash;
    }

    public String getArtifactZipUri() {
        return artifactZipUri;
    }

    public void setArtifactZipUri(String artifactZipUri) {
        this.artifactZipUri = artifactZipUri;
    }

    public GenerationState getState() {
        return state;
    }

    public void setState(GenerationState state) {
        this.state = state;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
