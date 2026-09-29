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
 * Row of {@code diagrammer.operation_parameter}.
 */
@Entity
@Table(name = "operation_parameter", schema = "diagrammer")
public class OperationParameter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "parameter_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operation_id", nullable = false)
    private UmlOperation operation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "type_reference_id")
    private TypeReference typeReference;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "direction", nullable = false, length = 16)
    private String direction;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    protected OperationParameter() {
    }

    public static OperationParameter create(UmlOperation operation, TypeReference typeReference, String name,
            String direction, int ordinal) {
        OperationParameter parameter = new OperationParameter();
        parameter.operation = operation;
        parameter.typeReference = typeReference;
        parameter.name = name;
        parameter.direction = direction;
        parameter.ordinal = ordinal;
        return parameter;
    }

    public UUID getId() {
        return id;
    }

    public UmlOperation getOperation() {
        return operation;
    }

    public TypeReference getTypeReference() {
        return typeReference;
    }

    public void setTypeReference(TypeReference typeReference) {
        this.typeReference = typeReference;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(int ordinal) {
        this.ordinal = ordinal;
    }
}
