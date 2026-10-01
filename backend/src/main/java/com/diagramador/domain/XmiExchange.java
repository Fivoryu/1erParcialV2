package com.diagramador.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Row of {@code diagrammer.xmi_exchange}. */
@Entity
@Table(name = "xmi_exchange", schema = "diagrammer")
public class XmiExchange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "exchange_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "direction", nullable = false)
    private ExchangeDirection direction;

    @Column(name = "profile", nullable = false, length = 255)
    private String profile;

    @Column(name = "model_revision")
    private Long modelRevision;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checksum", columnDefinition = "char(64)")
    private String checksum;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private ExchangeStatus status = ExchangeStatus.SUCCESS;

    @Column(name = "artifact_ref", length = 512)
    private String artifactRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "exchange", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<XmiLossItem> lossItems = new ArrayList<>();

    protected XmiExchange() {
    }

    public static XmiExchange create(UmlModel model, ExchangeDirection direction, String profile,
            Long modelRevision, String checksum, String artifactRef) {
        XmiExchange exchange = new XmiExchange();
        exchange.model = model;
        exchange.direction = direction;
        exchange.profile = profile;
        exchange.modelRevision = modelRevision;
        exchange.checksum = checksum;
        exchange.status = ExchangeStatus.SUCCESS;
        exchange.artifactRef = artifactRef;
        exchange.createdAt = OffsetDateTime.now();
        return exchange;
    }

    public XmiLossItem addLossItem(SeverityKind severity, String externalPath, String message) {
        XmiLossItem lossItem = XmiLossItem.create(this, severity, externalPath, message);
        lossItems.add(lossItem);
        return lossItem;
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public ExchangeDirection getDirection() {
        return direction;
    }

    public String getProfile() {
        return profile;
    }

    public Long getModelRevision() {
        return modelRevision;
    }

    public String getChecksum() {
        return checksum;
    }

    public ExchangeStatus getStatus() {
        return status;
    }

    public void setStatus(ExchangeStatus status) {
        this.status = status;
    }

    public String getArtifactRef() {
        return artifactRef;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public List<XmiLossItem> getLossItems() {
        return List.copyOf(lossItems);
    }
}
