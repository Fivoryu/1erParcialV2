package com.diagramador.generation;

import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.PersistenceStore;
import com.diagramador.domain.RelationshipKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Deterministic, normalized generation input. It contains no live domain entities or persistence references. */
public record DomainModelIR(
        List<PackageDeclaration> packages,
        List<TypeDeclaration> types,
        List<RelationshipDescriptor> relationships,
        List<SourceArtifactMapping> sourceToArtifacts,
        List<GenerationDiagnostic> diagnostics,
        List<EnumDeclaration> enums) {

    private static final Comparator<UUID> UUID_ORDER = Comparator.nullsFirst(Comparator.comparing(UUID::toString));

    public DomainModelIR(List<PackageDeclaration> packages, List<TypeDeclaration> types,
            List<RelationshipDescriptor> relationships, List<SourceArtifactMapping> sourceToArtifacts,
            List<GenerationDiagnostic> diagnostics) {
        this(packages, types, relationships, sourceToArtifacts, diagnostics, List.of());
    }

    public DomainModelIR {
        enums = sorted(enums, Comparator.comparing(EnumDeclaration::javaPackageName)
                .thenComparing(EnumDeclaration::javaName)
                .thenComparing(EnumDeclaration::sourceId, UUID_ORDER));
        packages = sorted(packages, Comparator.comparing(PackageDeclaration::javaPackageName)
                .thenComparing(PackageDeclaration::sourceId, UUID_ORDER));
        types = sorted(types, Comparator.comparing(TypeDeclaration::javaPackageName)
                .thenComparing(TypeDeclaration::javaName)
                .thenComparing(TypeDeclaration::sourceId, UUID_ORDER));
        relationships = sorted(relationships,
                Comparator.comparing(RelationshipDescriptor::sourceId, UUID_ORDER));
        sourceToArtifacts = sorted(sourceToArtifacts,
                Comparator.comparing(SourceArtifactMapping::sourceId, UUID_ORDER));
        diagnostics = sorted(diagnostics, Comparator
                .comparing(GenerationDiagnostic::elementId, UUID_ORDER)
                .thenComparing(GenerationDiagnostic::code)
                .thenComparing(GenerationDiagnostic::feature)
                .thenComparing(GenerationDiagnostic::message));
    }

    public boolean hasBlockingDiagnostics() {
        return diagnostics.stream().anyMatch(GenerationDiagnostic::blocking);
    }

    public enum CollectionIntent {
        SINGLE_VALUE,
        COLLECTION,
        UNRESOLVED
    }

    public enum LifecycleIntent {
        NONE,
        SHARED,
        COMPOSITE,
        OTHER
    }

    public record MultiplicityBounds(Integer lowerBound, Integer upperBound) {
    }

    public record PackageDeclaration(
            UUID sourceId,
            UUID parentPackageSourceId,
            String sourceName,
            String javaName,
            String javaPackageName) {

        public PackageDeclaration {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(sourceName, "sourceName");
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(javaPackageName, "javaPackageName");
        }
    }

    public record TypeDeclaration(
            UUID sourceId,
            UUID packageSourceId,
            ClassifierKind classifierKind,
            String sourceName,
            String javaName,
            String javaPackageName,
            String tableName,
            boolean isAbstract,
            List<FieldDeclaration> fields,
            List<OperationSignature> operations,
            PersistenceStore store,
            String storeReason) {

        /** A type persisted relationally, which is what every type was before the store existed. */
        public TypeDeclaration(UUID sourceId, UUID packageSourceId, ClassifierKind classifierKind, String sourceName,
                String javaName, String javaPackageName, String tableName, boolean isAbstract,
                List<FieldDeclaration> fields, List<OperationSignature> operations) {
            this(sourceId, packageSourceId, classifierKind, sourceName, javaName, javaPackageName, tableName,
                    isAbstract, fields, operations, PersistenceStore.SQL, null);
        }

        public TypeDeclaration {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(classifierKind, "classifierKind");
            Objects.requireNonNull(sourceName, "sourceName");
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(javaPackageName, "javaPackageName");
            Objects.requireNonNull(tableName, "tableName");
            fields = sorted(fields, Comparator.comparingInt(FieldDeclaration::ordinal)
                    .thenComparing(FieldDeclaration::sourceId, UUID_ORDER));
            operations = sorted(operations, Comparator.comparingInt(OperationSignature::ordinal)
                    .thenComparing(OperationSignature::sourceId, UUID_ORDER));
        }
    }

    /** A UML enumeration; attributes typed by it carry its id in {@link TypeUse#sourceClassifierId()}. */
    public record EnumDeclaration(
            UUID sourceId,
            String sourceName,
            String javaName,
            String javaPackageName,
            List<String> literals) {

        public EnumDeclaration {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(sourceName, "sourceName");
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(javaPackageName, "javaPackageName");
            literals = List.copyOf(literals);
        }
    }

    public record FieldDeclaration(
            UUID sourceId,
            String sourceName,
            String javaName,
            TypeUse type,
            String visibility,
            MultiplicityKind multiplicity,
            MultiplicityBounds bounds,
            boolean nullable,
            CollectionIntent collectionIntent,
            String defaultValue,
            int ordinal) {

        public FieldDeclaration {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(sourceName, "sourceName");
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(visibility, "visibility");
            Objects.requireNonNull(collectionIntent, "collectionIntent");
        }
    }

    public record OperationSignature(
            UUID sourceId,
            String sourceName,
            String javaName,
            String visibility,
            boolean isAbstract,
            boolean isStatic,
            TypeUse returnType,
            List<ParameterDeclaration> parameters,
            int ordinal) {

        public OperationSignature {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(sourceName, "sourceName");
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(visibility, "visibility");
            Objects.requireNonNull(returnType, "returnType");
            parameters = sorted(parameters, Comparator.comparingInt(ParameterDeclaration::ordinal)
                    .thenComparing(ParameterDeclaration::sourceId, UUID_ORDER));
        }
    }

    public record ParameterDeclaration(
            UUID sourceId,
            String sourceName,
            String javaName,
            String direction,
            TypeUse type,
            int ordinal) {

        public ParameterDeclaration {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(sourceName, "sourceName");
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(type, "type");
        }
    }

    public record TypeUse(
            UUID sourceTypeReferenceId,
            UUID sourceClassifierId,
            String sourceTypeName,
            String javaTypeName,
            String sqlTypeName,
            boolean resolved) {

        public static TypeUse unresolved(UUID sourceTypeReferenceId) {
            return new TypeUse(sourceTypeReferenceId, null, null, null, null, false);
        }

        public static TypeUse voidType() {
            return new TypeUse(null, null, "void", "void", null, true);
        }
    }

    /** The owning end is always the to-many end; the referenced end is always the to-one end. */
    public record RelationshipDescriptor(
            UUID sourceId,
            RelationshipKind kind,
            String name,
            RelationshipEndDescriptor owningEnd,
            RelationshipEndDescriptor referencedEnd,
            String owningFieldName,
            String inverseCollectionFieldName,
            boolean owningFieldNullable,
            boolean inverseCollectionEmitted,
            LifecycleIntent lifecycleIntent) {

        public RelationshipDescriptor {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(owningEnd, "owningEnd");
            Objects.requireNonNull(referencedEnd, "referencedEnd");
            Objects.requireNonNull(owningFieldName, "owningFieldName");
            Objects.requireNonNull(lifecycleIntent, "lifecycleIntent");
            if (inverseCollectionEmitted != (inverseCollectionFieldName != null)) {
                throw new IllegalArgumentException("inverse collection name must match its emitted state");
            }
        }
    }

    /** Retains each end's raw aggregation_kind as well as its normalized lifecycle meaning. */
    public record RelationshipEndDescriptor(
            UUID sourceId,
            UUID classifierSourceId,
            String classifierName,
            String roleName,
            MultiplicityKind multiplicity,
            MultiplicityBounds bounds,
            boolean navigable,
            String aggregationKind,
            LifecycleIntent lifecycleIntent) {

        public RelationshipEndDescriptor {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(multiplicity, "multiplicity");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(lifecycleIntent, "lifecycleIntent");
        }
    }

    public record SourceArtifactMapping(UUID sourceId, List<String> artifactPaths) {

        public SourceArtifactMapping {
            Objects.requireNonNull(sourceId, "sourceId");
            artifactPaths = artifactPaths.stream().distinct().sorted().toList();
        }
    }

    private static <T> List<T> sorted(List<T> values, Comparator<? super T> comparator) {
        List<T> result = new ArrayList<>(values);
        result.sort(comparator);
        return List.copyOf(result);
    }
}
