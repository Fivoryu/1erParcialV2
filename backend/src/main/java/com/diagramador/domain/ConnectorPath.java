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

/**
 * Row of {@code diagrammer.connector_path}.
 */
@Entity
@Table(name = "connector_path", schema = "diagrammer")
public class ConnectorPath {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "path_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "view_id", nullable = false)
    private DiagramView view;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "relationship_id", nullable = false)
    private UmlRelationship relationship;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "x", nullable = false)
    private int x;

    @Column(name = "y", nullable = false)
    private int y;

    protected ConnectorPath() {
    }

    public static ConnectorPath create(DiagramView view, UmlRelationship relationship, int ordinal, int x, int y) {
        ConnectorPath path = new ConnectorPath();
        path.view = view;
        path.relationship = relationship;
        path.ordinal = ordinal;
        path.x = x;
        path.y = y;
        return path;
    }

    public UUID getId() {
        return id;
    }

    public DiagramView getView() {
        return view;
    }

    public UmlRelationship getRelationship() {
        return relationship;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(int ordinal) {
        this.ordinal = ordinal;
    }

    public int getX() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
    }

    public int getY() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
    }
}
