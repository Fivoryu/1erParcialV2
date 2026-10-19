package com.diagramador.generation;

import com.diagramador.generation.DomainModelIR.CollectionIntent;
import com.diagramador.generation.DomainModelIR.EnumDeclaration;
import com.diagramador.generation.DomainModelIR.FieldDeclaration;
import com.diagramador.generation.DomainModelIR.RelationshipDescriptor;
import com.diagramador.generation.DomainModelIR.TypeDeclaration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single allowlist of query fields of a generated entity. The emitter compiles it into the generated
 * backend and the OpenAPI builder publishes it, so the server and the contract a client reads cannot drift.
 * A field is a single-valued scalar or enum of the entity ({@code turno}), the id of a to-one reference
 * ({@code cursoId}), or a scalar of that referenced entity one level deep ({@code curso.turno}).
 */
public final class QueryFieldCatalog {

    public enum Kind {
        STRING, INTEGER, LONG, DOUBLE, DECIMAL, BOOLEAN, DATE, DATETIME, UUID, ENUM
    }

    /**
     * {@code enumeration} is set only for {@link Kind#ENUM}; {@code references} is the table name of the entity a
     * reference id points to, so a client can resolve its display fields.
     */
    public record QueryField(String name, String jpaPath, Kind kind, EnumDeclaration enumeration, String references) {
    }

    public static final List<String> OPERATORS = List.of("eq", "ne", "gt", "gte", "lt", "lte", "contains", "in",
            "is_null");
    public static final List<String> AGGREGATES = List.of("count", "sum", "avg", "min", "max");
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_VALUES = 200;

    private QueryFieldCatalog() {
    }

    public static List<QueryField> fields(TypeDeclaration type, DomainModelIR ir) {
        Map<UUID, TypeDeclaration> types = ir.types().stream()
                .collect(Collectors.toMap(TypeDeclaration::sourceId, Function.identity(), (left, right) -> left));
        Map<UUID, EnumDeclaration> enums = ir.enums().stream()
                .collect(Collectors.toMap(EnumDeclaration::sourceId, Function.identity(), (left, right) -> left));
        List<QueryField> fields = new ArrayList<>();
        fields.add(new QueryField("id", "id", Kind.UUID, null, null));
        fields.addAll(scalars(type, enums, ""));
        for (FieldDeclaration field : type.fields()) {
            UUID referenced = field.type().sourceClassifierId();
            if (referenced != null && !enums.containsKey(referenced) && types.containsKey(referenced)
                    && field.collectionIntent() != CollectionIntent.COLLECTION) {
                addReference(fields, field.javaName(), types.get(referenced), enums);
            }
        }
        for (RelationshipDescriptor relationship : ir.relationships()) {
            if (relationship.owningEnd().classifierSourceId().equals(type.sourceId())
                    && types.containsKey(relationship.referencedEnd().classifierSourceId())) {
                addReference(fields, relationship.owningFieldName(),
                        types.get(relationship.referencedEnd().classifierSourceId()), enums);
            }
        }
        return List.copyOf(fields);
    }

    private static void addReference(List<QueryField> fields, String fieldName, TypeDeclaration referenced,
            Map<UUID, EnumDeclaration> enums) {
        fields.add(new QueryField(fieldName + "Id", fieldName + ".id", Kind.UUID, null, referenced.tableName()));
        fields.addAll(scalars(referenced, enums, fieldName + "."));
    }

    private static List<QueryField> scalars(TypeDeclaration type, Map<UUID, EnumDeclaration> enums, String prefix) {
        List<QueryField> fields = new ArrayList<>();
        for (FieldDeclaration field : type.fields()) {
            if (field.collectionIntent() == CollectionIntent.COLLECTION) {
                continue;
            }
            UUID classifier = field.type().sourceClassifierId();
            if (classifier != null && enums.containsKey(classifier)) {
                fields.add(new QueryField(prefix + field.javaName(), prefix + field.javaName(), Kind.ENUM,
                        enums.get(classifier), null));
            } else if (classifier == null) {
                kind(field.type().javaTypeName()).ifPresent(kind -> fields.add(
                        new QueryField(prefix + field.javaName(), prefix + field.javaName(), kind, null, null)));
            }
        }
        return fields;
    }

    private static Optional<Kind> kind(String javaType) {
        if (javaType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(switch (javaType) {
            case "String", "java.lang.String" -> Kind.STRING;
            case "Integer", "java.lang.Integer", "int" -> Kind.INTEGER;
            case "Long", "java.lang.Long", "long" -> Kind.LONG;
            case "Double", "java.lang.Double", "double" -> Kind.DOUBLE;
            case "java.math.BigDecimal" -> Kind.DECIMAL;
            case "Boolean", "java.lang.Boolean", "boolean" -> Kind.BOOLEAN;
            case "java.time.LocalDate" -> Kind.DATE;
            case "java.time.LocalDateTime" -> Kind.DATETIME;
            case "java.util.UUID" -> Kind.UUID;
            default -> null;
        });
    }
}
