package com.diagramador.generation;

import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.PersistenceStore;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.SeverityKind;
import com.diagramador.storage.PersistenceStoreClassifier;
import com.diagramador.generation.DomainModelIR.CollectionIntent;
import com.diagramador.generation.DomainModelIR.EnumDeclaration;
import com.diagramador.generation.DomainModelIR.FieldDeclaration;
import com.diagramador.generation.DomainModelIR.LifecycleIntent;
import com.diagramador.generation.DomainModelIR.MultiplicityBounds;
import com.diagramador.generation.DomainModelIR.OperationSignature;
import com.diagramador.generation.DomainModelIR.PackageDeclaration;
import com.diagramador.generation.DomainModelIR.ParameterDeclaration;
import com.diagramador.generation.DomainModelIR.RelationshipDescriptor;
import com.diagramador.generation.DomainModelIR.RelationshipEndDescriptor;
import com.diagramador.generation.DomainModelIR.SourceArtifactMapping;
import com.diagramador.generation.DomainModelIR.TypeDeclaration;
import com.diagramador.generation.DomainModelIR.TypeUse;
import com.diagramador.generation.InputSnapshot.AttributeElement;
import com.diagramador.generation.InputSnapshot.ClassifierElement;
import com.diagramador.generation.InputSnapshot.OperationElement;
import com.diagramador.generation.InputSnapshot.ParameterElement;
import com.diagramador.generation.InputSnapshot.RelationshipElement;
import com.diagramador.generation.InputSnapshot.RelationshipEndElement;
import com.diagramador.generation.InputSnapshot.TypeReferenceElement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Pure mapper from an immutable semantic snapshot to a deterministic generation IR. */
@Component
public final class DomainModelIRMapper {

    private static final Comparator<UUID> UUID_ORDER = Comparator.nullsFirst(Comparator.comparing(UUID::toString));
    private static final Comparator<ClassifierElement> CLASSIFIER_ORDER =
            Comparator.comparing(ClassifierElement::id, UUID_ORDER);
    private static final Comparator<AttributeElement> ATTRIBUTE_ORDER = Comparator
            .comparingInt(AttributeElement::ordinal)
            .thenComparing(AttributeElement::id, UUID_ORDER);
    private static final Comparator<OperationElement> OPERATION_ORDER = Comparator
            .comparingInt(OperationElement::ordinal)
            .thenComparing(OperationElement::id, UUID_ORDER);
    private static final Comparator<ParameterElement> PARAMETER_ORDER = Comparator
            .comparingInt(ParameterElement::ordinal)
            .thenComparing(ParameterElement::id, UUID_ORDER);
    private static final Comparator<RelationshipElement> RELATIONSHIP_ORDER =
            Comparator.comparing(RelationshipElement::id, UUID_ORDER);
    private static final Comparator<RelationshipEndElement> END_ORDER =
            Comparator.comparing(RelationshipEndElement::id, UUID_ORDER);
    private static final Comparator<TypeReferenceElement> TYPE_REFERENCE_ORDER =
            Comparator.comparing(TypeReferenceElement::id, UUID_ORDER);

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_");

    public DomainModelIR map(InputSnapshot snapshot) {
        return map(snapshot, VersionedGenerationProfileConfiguration.primitiveTypes(
                VersionedGenerationProfileConfiguration.v1()));
    }

    DomainModelIR map(InputSnapshot snapshot, Map<String, ProvisionalGenerationProfile.TypeMapping> primitiveTypes) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(primitiveTypes, "primitiveTypes");

        List<GenerationDiagnostic> diagnostics = new ArrayList<>();
        Map<UUID, ClassifierElement> classifiersById = index(
                snapshot.classifiers().stream().sorted(CLASSIFIER_ORDER).toList(),
                ClassifierElement::id);
        Map<UUID, TypeReferenceElement> typeReferencesById = index(
                snapshot.typeReferences().stream().sorted(TYPE_REFERENCE_ORDER).toList(),
                TypeReferenceElement::id);
        Map<UUID, TypeName> typeNamesById = new HashMap<>();

        List<ClassifierElement> classifiers = snapshot.classifiers().stream().sorted(CLASSIFIER_ORDER).toList();
        for (ClassifierElement classifier : classifiers) {
            String sourceName = safeName(classifier.name(), "unnamed");
            String javaName = javaIdentifier(sourceName);
            String javaPackageName = packageNameFor(classifier.namespaceId(), classifiersById,
                    classifier.id(), diagnostics);
            typeNamesById.put(classifier.id(), new TypeName(sourceName, javaName, javaPackageName));
        }

        List<PackageDeclaration> packages = buildPackages(classifiers, classifiersById, typeNamesById, diagnostics);
        Map<UUID, PersistenceStoreClassifier.Decision> stores = PersistenceStoreClassifier.classify(snapshot);
        List<TypeDeclaration> types = withStores(buildTypes(snapshot, classifiers, classifiersById,
                typeReferencesById, typeNamesById, primitiveTypes, diagnostics), stores);
        RelationshipMapping relationshipMapping = buildRelationships(snapshot, classifiersById, typeNamesById,
                stores, diagnostics);
        List<EnumDeclaration> enums = buildEnums(snapshot, classifiers, classifiersById, typeNamesById, diagnostics);
        rejectUnmappedConstraints(snapshot, diagnostics);

        List<SourceArtifactMapping> sourceToArtifacts = buildSourceArtifactMappings(snapshot, types,
                relationshipMapping, packages);
        return new DomainModelIR(packages, types, relationshipMapping.relationships(), sourceToArtifacts, diagnostics,
                enums);
    }

    /** Stamps every class with the store the classifier chose, so the emitters never decide it again. */
    private static List<TypeDeclaration> withStores(List<TypeDeclaration> types,
            Map<UUID, PersistenceStoreClassifier.Decision> decisions) {
        List<TypeDeclaration> stamped = new ArrayList<>();
        for (TypeDeclaration type : types) {
            PersistenceStoreClassifier.Decision decision = decisions.get(type.sourceId());
            stamped.add(decision == null ? type : new TypeDeclaration(type.sourceId(), type.packageSourceId(),
                    type.classifierKind(), type.sourceName(), type.javaName(), type.javaPackageName(),
                    type.tableName(), type.isAbstract(), type.fields(), type.operations(), decision.store(),
                    decision.reason()));
        }
        return stamped;
    }

    private List<PackageDeclaration> buildPackages(
            List<ClassifierElement> classifiers,
            Map<UUID, ClassifierElement> classifiersById,
            Map<UUID, TypeName> typeNamesById,
            List<GenerationDiagnostic> diagnostics) {
        List<PackageDeclaration> packages = new ArrayList<>();
        for (ClassifierElement classifier : classifiers) {
            if (classifier.kind() != ClassifierKind.PACKAGE) {
                continue;
            }
            TypeName typeName = typeNamesById.get(classifier.id());
            UUID parentPackageId = null;
            ClassifierElement parent = classifier.namespaceId() == null
                    ? null
                    : classifiersById.get(classifier.namespaceId());
            if (parent != null && parent.kind() == ClassifierKind.PACKAGE) {
                parentPackageId = parent.id();
            }
            String packageSegment = packageSegment(typeName.sourceName());
            String packageName = typeName.javaPackageName().isEmpty()
                    ? packageSegment
                    : typeName.javaPackageName() + "." + packageSegment;
            packages.add(new PackageDeclaration(classifier.id(), parentPackageId,
                    typeName.sourceName(), packageSegment, packageName));
        }
        return packages;
    }

    private List<TypeDeclaration> buildTypes(
            InputSnapshot snapshot,
            List<ClassifierElement> classifiers,
            Map<UUID, ClassifierElement> classifiersById,
            Map<UUID, TypeReferenceElement> typeReferencesById,
            Map<UUID, TypeName> typeNamesById,
            Map<String, ProvisionalGenerationProfile.TypeMapping> primitiveTypes,
            List<GenerationDiagnostic> diagnostics) {
        Map<UUID, List<AttributeElement>> attributesByClassifier = group(snapshot.attributes(),
                AttributeElement::classifierId);
        Map<UUID, List<OperationElement>> operationsByClassifier = group(snapshot.operations(),
                OperationElement::classifierId);
        Map<UUID, List<ParameterElement>> parametersByOperation = group(snapshot.parameters(),
                ParameterElement::operationId);
        Map<UUID, OperationElement> operationsById = index(
                snapshot.operations().stream().sorted(OPERATION_ORDER).toList(), OperationElement::id);

        for (ClassifierElement classifier : classifiers) {
            if (classifier.kind() == ClassifierKind.INTERFACE) {
                addDiagnostic(diagnostics, "UNSUPPORTED_UML_FEATURE", classifier.id(), classifier.kind().name(),
                        "The Increment 2 subset does not map classifier feature '" + classifier.kind().name() + "'.");
            } else if (classifier.kind() != ClassifierKind.CLASS
                    && classifier.kind() != ClassifierKind.ABSTRACT_CLASS
                    && classifier.kind() != ClassifierKind.PACKAGE
                    && classifier.kind() != ClassifierKind.ENUMERATION) {
                addDiagnostic(diagnostics, "UNSUPPORTED_UML_FEATURE", classifier.id(), "classifierKind",
                        "The classifier kind cannot be mapped by the Increment 2 subset.");
            }
        }

        Map<UUID, List<FieldDeclaration>> fieldsByClassifier = new HashMap<>();
        List<AttributeElement> attributes = snapshot.attributes().stream().sorted(ATTRIBUTE_ORDER).toList();
        for (AttributeElement attribute : attributes) {
            if (isEnumerationLiteral(attribute, classifiersById)) {
                continue;
            }
            TypeUse type = resolveType(attribute.typeReferenceId(), attribute.id(), "type",
                    typeReferencesById, classifiersById, typeNamesById, primitiveTypes, diagnostics, false);
            String visibility = normalizedVisibility(attribute.visibility(), attribute.id(), diagnostics);
            MultiplicityBounds bounds = bounds(attribute.multiplicity(), attribute.lowerBound(),
                    attribute.upperBound());
            CollectionIntent collectionIntent = collectionIntent(attribute.multiplicity(), attribute.upperBound());
            Boolean nullable = nullable(attribute.multiplicity());
            if (attribute.multiplicity() == MultiplicityKind.RANGE) {
                addDiagnostic(diagnostics, "UNSUPPORTED_MULTIPLICITY", attribute.id(), "multiplicity",
                        "Attribute '" + safeName(attribute.name(), "unnamed")
                                + "' uses RANGE multiplicity, which the Increment 2 subset cannot map.");
            } else if (attribute.multiplicity() == null) {
                addDiagnostic(diagnostics, "UNSUPPORTED_MULTIPLICITY", attribute.id(), "multiplicity",
                        "Attribute '" + safeName(attribute.name(), "unnamed") + "' has no multiplicity.");
            }
            if (!"ATTRIBUTE".equals(attribute.kind())) {
                addDiagnostic(diagnostics, "UNSUPPORTED_UML_FEATURE", attribute.id(),
                        safeName(attribute.kind(), "attributeKind"),
                        "Attribute kind '" + safeName(attribute.kind(), "unknown") + "' is not mapped.");
            }
            if (!classifiersById.containsKey(attribute.classifierId())) {
                addDiagnostic(diagnostics, "UNRESOLVED_CLASSIFIER", attribute.id(), "classifierId",
                        "Attribute '" + safeName(attribute.name(), "unnamed") + "' references missing classifier "
                                + attribute.classifierId() + ".");
                continue;
            }
            if (!"ATTRIBUTE".equals(attribute.kind())) {
                continue;
            }
            fieldsByClassifier.computeIfAbsent(attribute.classifierId(), ignored -> new ArrayList<>())
                    .add(new FieldDeclaration(attribute.id(), safeName(attribute.name(), "unnamed"),
                            javaIdentifier(attribute.name()), type, visibility, attribute.multiplicity(), bounds,
                            nullable, collectionIntent, attribute.defaultValue(), attribute.ordinal()));
        }

        Map<UUID, List<OperationSignature>> signaturesByClassifier = new HashMap<>();
        List<OperationElement> operations = snapshot.operations().stream().sorted(OPERATION_ORDER).toList();
        for (OperationElement operation : operations) {
            TypeUse returnType = resolveType(operation.returnTypeReferenceId(), operation.id(), "returnType",
                    typeReferencesById, classifiersById, typeNamesById, primitiveTypes, diagnostics, true);
            String visibility = normalizedVisibility(operation.visibility(), operation.id(), diagnostics);
            List<ParameterDeclaration> parameters = new ArrayList<>();
            List<ParameterElement> operationParameters = parametersByOperation.getOrDefault(operation.id(), List.of())
                    .stream().sorted(PARAMETER_ORDER).toList();
            for (ParameterElement parameter : operationParameters) {
                TypeUse parameterType = resolveType(parameter.typeReferenceId(), parameter.id(), "type",
                        typeReferencesById, classifiersById, typeNamesById, primitiveTypes, diagnostics, false);
                parameters.add(new ParameterDeclaration(parameter.id(), safeName(parameter.name(), "unnamed"),
                        javaIdentifier(parameter.name()), safeName(parameter.direction(), "UNKNOWN"), parameterType,
                        parameter.ordinal()));
            }
            if (!classifiersById.containsKey(operation.classifierId())) {
                addDiagnostic(diagnostics, "UNRESOLVED_CLASSIFIER", operation.id(), "classifierId",
                        "Operation '" + safeName(operation.name(), "unnamed") + "' references missing classifier "
                                + operation.classifierId() + ".");
                continue;
            }
            signaturesByClassifier.computeIfAbsent(operation.classifierId(), ignored -> new ArrayList<>())
                    .add(new OperationSignature(operation.id(), safeName(operation.name(), "unnamed"),
                            javaIdentifier(operation.name()), visibility, operation.isAbstract(), operation.isStatic(),
                            returnType, parameters, operation.ordinal()));
        }

        for (ParameterElement parameter : snapshot.parameters().stream().sorted(PARAMETER_ORDER).toList()) {
            if (!operationsById.containsKey(parameter.operationId())) {
                addDiagnostic(diagnostics, "UNRESOLVED_OPERATION", parameter.id(), "operationId",
                        "Parameter '" + safeName(parameter.name(), "unnamed") + "' references missing operation "
                                + parameter.operationId() + ".");
            }
        }

        List<TypeDeclaration> types = new ArrayList<>();
        for (ClassifierElement classifier : classifiers) {
            if (classifier.kind() != ClassifierKind.CLASS && classifier.kind() != ClassifierKind.ABSTRACT_CLASS) {
                continue;
            }
            TypeName typeName = typeNamesById.get(classifier.id());
            types.add(new TypeDeclaration(classifier.id(), classifier.namespaceId(), classifier.kind(),
                    typeName.sourceName(), typeName.javaName(), typeName.javaPackageName(),
                    snakeCase(typeName.sourceName()), classifier.isAbstract(),
                    fieldsByClassifier.getOrDefault(classifier.id(), List.of()),
                    signaturesByClassifier.getOrDefault(classifier.id(), List.of())));
        }
        return types;
    }

    private static boolean isEnumerationLiteral(AttributeElement attribute, Map<UUID, ClassifierElement> classifiersById) {
        ClassifierElement owner = classifiersById.get(attribute.classifierId());
        return owner != null && owner.kind() == ClassifierKind.ENUMERATION && "LITERAL".equals(attribute.kind());
    }

    private List<EnumDeclaration> buildEnums(
            InputSnapshot snapshot,
            List<ClassifierElement> classifiers,
            Map<UUID, ClassifierElement> classifiersById,
            Map<UUID, TypeName> typeNamesById,
            List<GenerationDiagnostic> diagnostics) {
        Map<UUID, List<AttributeElement>> attributesByClassifier = group(
                snapshot.attributes().stream().sorted(ATTRIBUTE_ORDER).toList(), AttributeElement::classifierId);
        List<EnumDeclaration> enums = new ArrayList<>();
        for (ClassifierElement classifier : classifiers) {
            if (classifier.kind() != ClassifierKind.ENUMERATION) {
                continue;
            }
            List<String> literals = new ArrayList<>();
            for (AttributeElement attribute : attributesByClassifier.getOrDefault(classifier.id(), List.of())) {
                if (!isEnumerationLiteral(attribute, classifiersById)) {
                    addDiagnostic(diagnostics, "UNSUPPORTED_UML_FEATURE", attribute.id(), "enumerationAttribute",
                            "Enumeration '" + safeName(classifier.name(), "unnamed")
                                    + "' can only own literals, not attribute '" + safeName(attribute.name(), "unnamed")
                                    + "'.");
                } else if (attribute.name() == null || !attribute.name().matches("[A-Za-z_][A-Za-z0-9_]*")
                        || literals.contains(attribute.name())) {
                    addDiagnostic(diagnostics, "INVALID_ENUMERATION_LITERAL", attribute.id(), "name",
                            "Enumeration literal '" + safeName(attribute.name(), "unnamed")
                                    + "' must be a unique identifier.");
                } else {
                    literals.add(attribute.name());
                }
            }
            if (literals.isEmpty()) {
                addDiagnostic(diagnostics, "EMPTY_ENUMERATION", classifier.id(), "literals",
                        "Enumeration '" + safeName(classifier.name(), "unnamed") + "' needs at least one literal.");
            }
            TypeName typeName = typeNamesById.get(classifier.id());
            enums.add(new EnumDeclaration(classifier.id(), typeName.sourceName(), typeName.javaName(),
                    typeName.javaPackageName(), literals));
        }
        return enums;
    }

    private RelationshipMapping buildRelationships(
            InputSnapshot snapshot,
            Map<UUID, ClassifierElement> classifiersById,
            Map<UUID, TypeName> typeNamesById,
            Map<UUID, PersistenceStoreClassifier.Decision> stores,
            List<GenerationDiagnostic> diagnostics) {
        Map<UUID, List<RelationshipEndElement>> endsByRelationship = new HashMap<>();
        for (RelationshipEndElement end : snapshot.relationshipEnds().stream().sorted(END_ORDER).toList()) {
            endsByRelationship.computeIfAbsent(end.relationshipId(), ignored -> new ArrayList<>()).add(end);
        }

        List<RelationshipDescriptor> relationships = new ArrayList<>();
        Map<UUID, Set<String>> artifactPathsByRelationship = new TreeMap<>(UUID_ORDER);
        Set<UUID> knownRelationshipIds = new HashSet<>();
        for (RelationshipElement relationship : snapshot.relationships().stream().sorted(RELATIONSHIP_ORDER).toList()) {
            knownRelationshipIds.add(relationship.id());
            if (!isSupportedRelationship(relationship.kind())) {
                String feature = relationship.kind() == null ? "relationshipKind" : relationship.kind().name();
                addDiagnostic(diagnostics, "UNSUPPORTED_UML_FEATURE", relationship.id(), feature,
                        "The Increment 2 subset does not map relationship feature '" + feature + "'.");
                continue;
            }

            List<RelationshipEndElement> ends = endsByRelationship.getOrDefault(relationship.id(), List.of())
                    .stream().sorted(END_ORDER).toList();
            if (ends.size() != 2) {
                addDiagnostic(diagnostics, "INVALID_RELATIONSHIP_ENDS", relationship.id(), "relationshipEnds",
                        "Relationship '" + safeName(relationship.name(), relationship.id().toString())
                                + "' must have exactly two ends; found " + ends.size() + ".");
                continue;
            }

            List<RelationshipEndElement> manyEnds = ends.stream().filter(end -> isToMany(end.multiplicity(), end.upperBound())).toList();
            List<RelationshipEndElement> oneEnds = ends.stream().filter(end -> isToOne(end.multiplicity(), end.upperBound())).toList();
            if (manyEnds.size() != 1 || oneEnds.size() != 1) {
                String combination = multiplicityCombination(ends);
                addDiagnostic(diagnostics, "UNSUPPORTED_MULTIPLICITY_COMBINATION", relationship.id(), "multiplicity",
                        "Relationship '" + safeName(relationship.name(), relationship.id().toString())
                                + "' has unsupported multiplicity combination " + combination
                                + "; V1 requires exactly one to-many end and one to-one end.");
                continue;
            }

            RelationshipEndElement manyEnd = manyEnds.get(0);
            RelationshipEndElement oneEnd = oneEnds.get(0);
            if (oneEnd.navigability() != NavigationKind.NAVIGABLE) {
                addDiagnostic(diagnostics, "NON_NAVIGABLE_TO_ONE_END", oneEnd.id(), "navigability",
                        "Relationship '" + safeName(relationship.name(), relationship.id().toString())
                                + "' cannot be mapped because to-one end '" + roleName(oneEnd, classifiersById,
                                        typeNamesById) + "' (end " + oneEnd.id() + ") is not navigable; the foreign key "
                                + "would have no owning field.");
            }

            RelationshipEndDescriptor owningEnd = relationshipEndDescriptor(manyEnd, classifiersById, typeNamesById,
                    diagnostics);
            RelationshipEndDescriptor referencedEnd = relationshipEndDescriptor(oneEnd, classifiersById,
                    typeNamesById, diagnostics);
            TypeName referencedName = typeNamesById.get(oneEnd.classifierId());
            TypeName owningName = typeNamesById.get(manyEnd.classifierId());
            String owningFieldName = fieldName(oneEnd, referencedName);
            boolean inverseCollectionEmitted = manyEnd.navigability() == NavigationKind.NAVIGABLE;
            String inverseCollectionFieldName = inverseCollectionEmitted ? fieldName(manyEnd, owningName) : null;
            LifecycleIntent lifecycleIntent = combineLifecycle(owningEnd.lifecycleIntent(),
                    referencedEnd.lifecycleIntent());

            relationships.add(new RelationshipDescriptor(relationship.id(), relationship.kind(), relationship.name(),
                    owningEnd, referencedEnd, owningFieldName, inverseCollectionFieldName,
                    !isMandatory(oneEnd), inverseCollectionEmitted, lifecycleIntent));

            Set<String> relationshipArtifacts = new TreeSet<>();
            // The reference column lives with the owning (to-many) end, so only a relational owner touches the schema.
            if (isRelational(stores, manyEnd.classifierId())) {
                relationshipArtifacts.add(MIGRATION_PATH);
            }
            addEntityArtifact(relationshipArtifacts, manyEnd.classifierId(), typeNamesById);
            addEntityArtifact(relationshipArtifacts, oneEnd.classifierId(), typeNamesById);
            artifactPathsByRelationship.put(relationship.id(), relationshipArtifacts);
        }

        for (RelationshipEndElement end : snapshot.relationshipEnds()) {
            if (!knownRelationshipIds.contains(end.relationshipId())) {
                addDiagnostic(diagnostics, "UNRESOLVED_RELATIONSHIP", end.id(), "relationshipId",
                        "Relationship end " + end.id() + " references missing relationship "
                                + end.relationshipId() + ".");
            }
        }
        return new RelationshipMapping(List.copyOf(relationships), artifactPathsByRelationship);
    }

    private RelationshipEndDescriptor relationshipEndDescriptor(
            RelationshipEndElement end,
            Map<UUID, ClassifierElement> classifiersById,
            Map<UUID, TypeName> typeNamesById,
            List<GenerationDiagnostic> diagnostics) {
        ClassifierElement classifier = classifiersById.get(end.classifierId());
        TypeName typeName = typeNamesById.get(end.classifierId());
        if (classifier == null) {
            addDiagnostic(diagnostics, "UNRESOLVED_CLASSIFIER", end.id(), "classifierId",
                    "Relationship end '" + roleName(end, classifiersById, typeNamesById)
                            + "' references missing classifier " + end.classifierId() + ".");
        }
        return new RelationshipEndDescriptor(end.id(), end.classifierId(),
                typeName == null ? null : typeName.sourceName(), end.role(), end.multiplicity(),
                bounds(end.multiplicity(), end.lowerBound(), end.upperBound()), end.navigability() == NavigationKind.NAVIGABLE,
                end.aggregationKind(), lifecycleIntent(end.aggregationKind()));
    }

    private TypeUse resolveType(
            UUID typeReferenceId,
            UUID elementId,
            String feature,
            Map<UUID, TypeReferenceElement> typeReferencesById,
            Map<UUID, ClassifierElement> classifiersById,
            Map<UUID, TypeName> typeNamesById,
            Map<String, ProvisionalGenerationProfile.TypeMapping> primitiveTypes,
            List<GenerationDiagnostic> diagnostics,
            boolean allowVoid) {
        if (typeReferenceId == null && allowVoid) {
            return TypeUse.voidType();
        }
        TypeReferenceElement reference = typeReferencesById.get(typeReferenceId);
        if (reference == null) {
            addDiagnostic(diagnostics, "UNRESOLVABLE_TYPE", elementId, feature,
                    "Type reference " + typeReferenceId + " cannot be resolved in the input snapshot.");
            return TypeUse.unresolved(typeReferenceId);
        }

        boolean referencesClassifier = reference.classifierId() != null;
        boolean referencesPrimitive = reference.primitiveName() != null;
        if (referencesClassifier == referencesPrimitive) {
            addDiagnostic(diagnostics, "UNRESOLVABLE_TYPE", elementId, feature,
                    "Type reference " + typeReferenceId
                            + " must resolve to exactly one classifier or primitive name.");
            return TypeUse.unresolved(typeReferenceId);
        }
        if (referencesPrimitive) {
            ProvisionalGenerationProfile.TypeMapping mapping = primitiveTypes.get(reference.primitiveName());
            if (mapping == null) {
                addDiagnostic(diagnostics, "UNRESOLVABLE_TYPE", elementId, feature,
                        "Primitive type '" + reference.primitiveName()
                                + "' has no mapping in profile option 'primitiveTypeMappings."
                                + reference.primitiveName() + "'.");
                return TypeUse.unresolved(typeReferenceId);
            }
            return new TypeUse(typeReferenceId, null, reference.primitiveName(), mapping.javaTypeName(),
                    mapping.sqlTypeName(), true);
        }

        ClassifierElement classifier = classifiersById.get(reference.classifierId());
        TypeName typeName = typeNamesById.get(reference.classifierId());
        if (classifier == null || typeName == null || classifier.kind() == ClassifierKind.PACKAGE) {
            addDiagnostic(diagnostics, "UNRESOLVABLE_TYPE", elementId, feature,
                    "Classifier type reference " + typeReferenceId + " points to an unresolved or non-type classifier "
                            + reference.classifierId() + ".");
            return TypeUse.unresolved(typeReferenceId);
        }
        String javaType = qualifiedType(typeName.javaPackageName(), typeName.javaName());
        // An enumeration is a scalar column (its literal name), not a foreign key.
        String sqlType = classifier.kind() == ClassifierKind.ENUMERATION ? "VARCHAR(64)" : null;
        return new TypeUse(typeReferenceId, classifier.id(), typeName.sourceName(), javaType, sqlType, true);
    }

    private List<SourceArtifactMapping> buildSourceArtifactMappings(
            InputSnapshot snapshot,
            List<TypeDeclaration> types,
            RelationshipMapping relationshipMapping,
            List<PackageDeclaration> packages) {
        Map<UUID, Set<String>> pathsBySourceId = new TreeMap<>(UUID_ORDER);
        Map<UUID, Set<String>> typePathsById = new HashMap<>();
        List<String> projectMigration = types.stream().anyMatch(type -> type.store() == PersistenceStore.SQL)
                ? List.of(MIGRATION_PATH)
                : List.of();

        for (TypeDeclaration type : types) {
            Set<String> paths = artifactPaths(type);
            typePathsById.put(type.sourceId(), paths);
            addPaths(pathsBySourceId, type.sourceId(), paths);
            List<String> typeMigration = type.store() == PersistenceStore.SQL ? List.of(MIGRATION_PATH) : List.of();
            for (FieldDeclaration field : type.fields()) {
                List<String> fieldPaths = new ArrayList<>(List.of(entityArtifactPath(type)));
                fieldPaths.addAll(typeMigration);
                addPaths(pathsBySourceId, field.sourceId(), fieldPaths);
                addTypeUseMapping(pathsBySourceId, field.type(), typeMigration);
            }
            for (OperationSignature operation : type.operations()) {
                String servicePath = serviceArtifactPath(type);
                addPaths(pathsBySourceId, operation.sourceId(), List.of(servicePath));
                addTypeUseMapping(pathsBySourceId, operation.returnType(), projectMigration);
                for (ParameterDeclaration parameter : operation.parameters()) {
                    addPaths(pathsBySourceId, parameter.sourceId(), List.of(servicePath));
                    addTypeUseMapping(pathsBySourceId, parameter.type(), projectMigration);
                }
            }
        }
        relationshipMapping.artifactPathsByRelationship().forEach(
                (sourceId, paths) -> addPaths(pathsBySourceId, sourceId, paths));

        for (PackageDeclaration declaration : packages) {
            Set<String> packagePaths = new TreeSet<>();
            String packageName = declaration.javaPackageName();
            for (TypeDeclaration type : types) {
                if (packageName.isEmpty() || type.javaPackageName().equals(packageName)
                        || type.javaPackageName().startsWith(packageName + ".")) {
                    packagePaths.addAll(typePathsById.get(type.sourceId()));
                }
            }
            addPaths(pathsBySourceId, declaration.sourceId(), packagePaths);
        }

        return pathsBySourceId.entrySet().stream()
                .map(entry -> new SourceArtifactMapping(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }

    private void addTypeUseMapping(Map<UUID, Set<String>> pathsBySourceId, TypeUse type,
            List<String> migrationPaths) {
        if (type.sourceTypeReferenceId() != null && !migrationPaths.isEmpty()) {
            addPaths(pathsBySourceId, type.sourceTypeReferenceId(), migrationPaths);
        }
    }

    private static boolean isRelational(Map<UUID, PersistenceStoreClassifier.Decision> stores, UUID classifierId) {
        PersistenceStoreClassifier.Decision decision = stores.get(classifierId);
        return decision == null || decision.store() == PersistenceStore.SQL;
    }

    private Set<String> artifactPaths(TypeDeclaration type) {
        return new TreeSet<>(List.of(entityArtifactPath(type), repositoryArtifactPath(type),
                serviceArtifactPath(type), controllerArtifactPath(type)));
    }

    private String entityArtifactPath(TypeDeclaration type) {
        return javaSourcePath(type, "domain", type.javaName() + ".java");
    }

    private String repositoryArtifactPath(TypeDeclaration type) {
        return javaSourcePath(type, "repository", type.javaName() + "Repository.java");
    }

    private String serviceArtifactPath(TypeDeclaration type) {
        return javaSourcePath(type, "service", type.javaName() + "Service.java");
    }

    private String controllerArtifactPath(TypeDeclaration type) {
        return javaSourcePath(type, "web", type.javaName() + "Controller.java");
    }

    private String javaSourcePath(TypeDeclaration type, String layer, String fileName) {
        String packagePath = type.javaPackageName().replace('.', '/');
        String prefix = packagePath.isEmpty() ? "" : packagePath + "/";
        return "src/main/java/" + prefix + layer + "/" + fileName;
    }

    private void addEntityArtifact(Set<String> artifactPaths, UUID classifierId, Map<UUID, TypeName> typeNamesById) {
        TypeName typeName = typeNamesById.get(classifierId);
        if (typeName == null) {
            return;
        }
        String packagePath = typeName.javaPackageName().replace('.', '/');
        String prefix = packagePath.isEmpty() ? "" : packagePath + "/";
        artifactPaths.add("src/main/java/" + prefix + "domain/" + typeName.javaName() + ".java");
    }

    private void rejectUnmappedConstraints(InputSnapshot snapshot, List<GenerationDiagnostic> diagnostics) {
        snapshot.constraints().stream()
                .sorted(Comparator.comparing(InputSnapshot.ConstraintElement::id, UUID_ORDER))
                .forEach(constraint -> addDiagnostic(diagnostics, "UNSUPPORTED_UML_FEATURE", constraint.id(),
                        "CONSTRAINT", "Constraint expressions are not mapped by the Increment 2 subset."));
    }

    private String packageNameFor(
            UUID namespaceId,
            Map<UUID, ClassifierElement> classifiersById,
            UUID elementId,
            List<GenerationDiagnostic> diagnostics) {
        List<String> segments = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        UUID current = namespaceId;
        while (current != null) {
            if (!visited.add(current)) {
                addDiagnostic(diagnostics, "INVALID_NAMESPACE_CYCLE", elementId, "namespace",
                        "Namespace cycle detected while normalizing classifier " + elementId + ".");
                break;
            }
            ClassifierElement namespace = classifiersById.get(current);
            if (namespace == null) {
                addDiagnostic(diagnostics, "UNRESOLVED_NAMESPACE", elementId, "namespace",
                        "Namespace " + current + " cannot be resolved in the input snapshot.");
                break;
            }
            if (namespace.kind() != ClassifierKind.PACKAGE) {
                addDiagnostic(diagnostics, "UNSUPPORTED_NAMESPACE", elementId, "namespace",
                        "Namespace " + current + " is not a package.");
                break;
            }
            segments.add(packageSegment(namespace.name()));
            current = namespace.namespaceId();
        }
        java.util.Collections.reverse(segments);
        return String.join(".", segments);
    }

    private String normalizedVisibility(String sourceVisibility, UUID elementId,
            List<GenerationDiagnostic> diagnostics) {
        String visibility = safeName(sourceVisibility, "UNKNOWN").toUpperCase(Locale.ROOT);
        if (!visibility.equals("PUBLIC") && !visibility.equals("PRIVATE")) {
            addDiagnostic(diagnostics, "UNSUPPORTED_UML_FEATURE", elementId, "visibility",
                    "Visibility '" + safeName(sourceVisibility, "UNKNOWN")
                            + "' is outside the Increment 2 subset (PUBLIC and PRIVATE only).");
        }
        return visibility;
    }

    private MultiplicityBounds bounds(MultiplicityKind multiplicity, Integer lowerBound, Integer upperBound) {
        if (multiplicity == MultiplicityKind.RANGE) {
            return new MultiplicityBounds(lowerBound, upperBound);
        }
        if (multiplicity == null) {
            return new MultiplicityBounds(null, null);
        }
        return switch (multiplicity) {
            case EXACTLY_ONE -> new MultiplicityBounds(1, 1);
            case ZERO_OR_ONE -> new MultiplicityBounds(0, 1);
            case ONE_OR_MANY -> new MultiplicityBounds(1, null);
            case ZERO_OR_MANY -> new MultiplicityBounds(0, null);
            case RANGE -> new MultiplicityBounds(null, null);
        };
    }

    private CollectionIntent collectionIntent(MultiplicityKind multiplicity, Integer upperBound) {
        if (isToMany(multiplicity, upperBound)) {
            return CollectionIntent.COLLECTION;
        }
        if (isToOne(multiplicity, upperBound)) {
            return CollectionIntent.SINGLE_VALUE;
        }
        return CollectionIntent.UNRESOLVED;
    }

    private Boolean nullable(MultiplicityKind multiplicity) {
        if (multiplicity == MultiplicityKind.EXACTLY_ONE || multiplicity == MultiplicityKind.ONE_OR_MANY) {
            return false;
        }
        if (multiplicity == MultiplicityKind.ZERO_OR_ONE || multiplicity == MultiplicityKind.ZERO_OR_MANY) {
            return true;
        }
        return null;
    }

    /** A RANGE is to-one when its upper bound is exactly 1 (the bounds validation guarantees upper >= 1). */
    private boolean isToOne(MultiplicityKind multiplicity, Integer upperBound) {
        if (multiplicity == MultiplicityKind.RANGE) {
            return upperBound != null && upperBound <= 1;
        }
        return multiplicity == MultiplicityKind.EXACTLY_ONE || multiplicity == MultiplicityKind.ZERO_OR_ONE;
    }

    /** A RANGE is to-many when its upper bound is unbounded (null) or above 1. */
    private boolean isToMany(MultiplicityKind multiplicity, Integer upperBound) {
        if (multiplicity == MultiplicityKind.RANGE) {
            return upperBound == null || upperBound > 1;
        }
        return multiplicity == MultiplicityKind.ONE_OR_MANY || multiplicity == MultiplicityKind.ZERO_OR_MANY;
    }

    private boolean isMandatory(RelationshipEndElement end) {
        if (end.multiplicity() == MultiplicityKind.RANGE) {
            return end.lowerBound() != null && end.lowerBound() >= 1;
        }
        return end.multiplicity() == MultiplicityKind.EXACTLY_ONE;
    }

    private boolean isSupportedRelationship(RelationshipKind kind) {
        return kind == RelationshipKind.ASSOCIATION || kind == RelationshipKind.AGGREGATION
                || kind == RelationshipKind.COMPOSITION;
    }

    private String multiplicityCombination(List<RelationshipEndElement> ends) {
        return ends.stream().map(end -> end.multiplicity() == null ? "UNKNOWN" : end.multiplicity().name())
                .sorted().reduce((left, right) -> left + " + " + right).orElse("UNKNOWN");
    }

    private String roleName(RelationshipEndElement end, Map<UUID, ClassifierElement> classifiersById,
            Map<UUID, TypeName> typeNamesById) {
        if (end.role() != null && !end.role().isBlank()) {
            return end.role();
        }
        TypeName typeName = typeNamesById.get(end.classifierId());
        return typeName == null ? "unresolved" : typeName.sourceName();
    }

    private String fieldName(RelationshipEndElement end, TypeName target) {
        String sourceName = end.role() == null || end.role().isBlank()
                ? target == null ? "unresolved" : target.javaName()
                : end.role();
        return lowerCamel(javaIdentifier(sourceName));
    }

    private LifecycleIntent lifecycleIntent(String aggregationKind) {
        if (aggregationKind == null) {
            return LifecycleIntent.OTHER;
        }
        return switch (aggregationKind.toUpperCase(Locale.ROOT)) {
            case "NONE" -> LifecycleIntent.NONE;
            case "SHARED" -> LifecycleIntent.SHARED;
            case "COMPOSITE" -> LifecycleIntent.COMPOSITE;
            default -> LifecycleIntent.OTHER;
        };
    }

    private LifecycleIntent combineLifecycle(LifecycleIntent first, LifecycleIntent second) {
        if (first == LifecycleIntent.COMPOSITE || second == LifecycleIntent.COMPOSITE) {
            return LifecycleIntent.COMPOSITE;
        }
        if (first == LifecycleIntent.SHARED || second == LifecycleIntent.SHARED) {
            return LifecycleIntent.SHARED;
        }
        if (first == LifecycleIntent.OTHER || second == LifecycleIntent.OTHER) {
            return LifecycleIntent.OTHER;
        }
        return LifecycleIntent.NONE;
    }

    private String packageSegment(String sourceName) {
        return javaIdentifier(snakeCase(safeName(sourceName, "unnamed"))).toLowerCase(Locale.ROOT);
    }

    private String javaIdentifier(String sourceName) {
        String normalized = Normalizer.normalize(safeName(sourceName, "unnamed"), Normalizer.Form.NFKD);
        StringBuilder result = new StringBuilder();
        normalized.codePoints().filter(codePoint -> Character.getType(codePoint) != Character.NON_SPACING_MARK)
                .forEach(codePoint -> {
                    if (Character.isJavaIdentifierPart(codePoint)) {
                        result.appendCodePoint(codePoint);
                    } else if (result.length() == 0 || result.charAt(result.length() - 1) != '_') {
                        result.append('_');
                    }
                });
        if (result.length() == 0) {
            result.append('_');
        }
        int firstCodePoint = result.codePointAt(0);
        if (!Character.isJavaIdentifierStart(firstCodePoint)) {
            result.insert(0, '_');
        }
        String identifier = result.toString();
        if (JAVA_KEYWORDS.contains(identifier)) {
            return identifier + "_";
        }
        return identifier;
    }

    private String lowerCamel(String identifier) {
        if (identifier.isEmpty()) {
            return "_";
        }
        int first = identifier.codePointAt(0);
        return new StringBuilder().appendCodePoint(Character.toLowerCase(first))
                .append(identifier.substring(Character.charCount(first))).toString();
    }

    private String snakeCase(String sourceName) {
        String normalized = Normalizer.normalize(safeName(sourceName, "unnamed"), Normalizer.Form.NFKD);
        StringBuilder ascii = new StringBuilder();
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.getType(codePoint) == Character.NON_SPACING_MARK) {
                continue;
            }
            if (Character.isUpperCase(codePoint) && ascii.length() > 0
                    && Character.isLowerCase(ascii.codePointBefore(ascii.length()))) {
                ascii.append('_');
            }
            if (Character.isLetterOrDigit(codePoint)) {
                ascii.appendCodePoint(Character.toLowerCase(codePoint));
            } else if (ascii.length() == 0 || ascii.charAt(ascii.length() - 1) != '_') {
                ascii.append('_');
            }
        }
        String value = ascii.toString().replaceAll("^_+|_+$", "");
        return value.isEmpty() ? "unnamed" : value;
    }

    private String qualifiedType(String packageName, String typeName) {
        return packageName == null || packageName.isEmpty() ? typeName : packageName + "." + typeName;
    }

    private String safeName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void addDiagnostic(List<GenerationDiagnostic> diagnostics, String code, UUID elementId,
            String feature, String message) {
        diagnostics.add(new GenerationDiagnostic(code, SeverityKind.ERROR, elementId, feature, message, true));
    }

    private <T> Map<UUID, T> index(List<T> values, java.util.function.Function<T, UUID> id) {
        Map<UUID, T> result = new HashMap<>();
        for (T value : values) {
            result.putIfAbsent(id.apply(value), value);
        }
        return result;
    }

    private <T> Map<UUID, List<T>> group(Collection<T> values, java.util.function.Function<T, UUID> key) {
        Map<UUID, List<T>> result = new HashMap<>();
        for (T value : values) {
            result.computeIfAbsent(key.apply(value), ignored -> new ArrayList<>()).add(value);
        }
        return result;
    }

    private void addPaths(Map<UUID, Set<String>> pathsBySourceId, UUID sourceId, Collection<String> paths) {
        if (sourceId == null) {
            return;
        }
        pathsBySourceId.computeIfAbsent(sourceId, ignored -> new TreeSet<>()).addAll(paths);
    }

    private static final String MIGRATION_PATH = "src/main/resources/db/migration/V1__init.sql";

    private record TypeName(String sourceName, String javaName, String javaPackageName) {
    }

    private record RelationshipMapping(
            List<RelationshipDescriptor> relationships,
            Map<UUID, Set<String>> artifactPathsByRelationship) {
    }
}
