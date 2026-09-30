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
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Row of {@code diagrammer.template_version}. */
@Entity
@Table(name = "template_version", schema = "diagrammer")
public class TemplateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private GenerationProfile profile;

    @Column(name = "label", nullable = false, length = 128)
    private String label;

    @Column(name = "revision", nullable = false)
    private int revision = 1;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, columnDefinition = "char(64)")
    private String contentHash;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected TemplateVersion() {
    }

    public static TemplateVersion create(GenerationProfile profile, String label, int revision, String contentHash) {
        TemplateVersion template = new TemplateVersion();
        template.profile = profile;
        template.label = label;
        template.revision = revision;
        template.contentHash = contentHash;
        return template;
    }

    public UUID getId() {
        return id;
    }

    public GenerationProfile getProfile() {
        return profile;
    }

    public void setProfile(GenerationProfile profile) {
        this.profile = profile;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
