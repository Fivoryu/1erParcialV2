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

/** Row of {@code diagrammer.xmi_loss_item}. */
@Entity
@Table(name = "xmi_loss_item", schema = "diagrammer")
public class XmiLossItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "loss_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exchange_id", nullable = false)
    private XmiExchange exchange;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "severity", nullable = false)
    private SeverityKind severity;

    @Column(name = "external_path", length = 512)
    private String externalPath;

    @Column(name = "message", nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected XmiLossItem() {
    }

    public static XmiLossItem create(XmiExchange exchange, SeverityKind severity, String externalPath, String message) {
        XmiLossItem item = new XmiLossItem();
        item.exchange = exchange;
        item.severity = severity;
        item.externalPath = externalPath;
        item.message = message;
        item.createdAt = OffsetDateTime.now();
        return item;
    }

    public UUID getId() {
        return id;
    }

    public XmiExchange getExchange() {
        return exchange;
    }

    public SeverityKind getSeverity() {
        return severity;
    }

    public String getExternalPath() {
        return externalPath;
    }

    public String getMessage() {
        return message;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
