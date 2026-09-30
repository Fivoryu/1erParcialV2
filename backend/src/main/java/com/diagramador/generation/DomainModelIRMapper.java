package com.diagramador.generation;

import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.SeverityKind;
import com.diagramador.generation.DomainModelIR.CollectionIntent;
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

/** Pure mapper from an immutable semantic snapshot to a deterministic generation IR. */
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
        Objects.requireNonNull(snapshot, "snapshot");

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
        List<TypeDeclaration> types = buildTypes(snapshot, classifiers, classifiersById, typeReferencesById,
                typeNamesById, diagnostics);
        RelationshipMapping relationshipMapping = buildRelationships(snapshot, classifiersById, typeNamesById,
                diagnostics);
        rejectUnmappedConstraints(snapshot, diagnostics);

        List<SourceArtifactMapping> sourceToArtifacts = buildSourceArtifactMappings(snapshot, types,
                relationshipMapping, packages);
        return new DomainModelIR(packages, types, relationshipMapping.relationships(), sourceToArtifacts, diagnostics);
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
            if (classifier.kind() == ClassifierKind.INTERFACE || classifier.kind() == ClassifierKind.ENUMERATION) {
                addDiagnostic(diagnostics, "UNSUPPORTED_UML_FEATURE", classifier.id(), classifier.kind().name(),
                        "The Increment 2 subset does not map classifier feature '" + classifier.kind().name() + "'.");
            } else if (classifier.kind() != ClassifierKind.CLASS
                    && classifier.kind() != ClassifierKind.ABSTRACT_CLASS
                    && classifier.kind() != ClassifierKind.PACKAGE) {
                addDiagnostic(diagnostics, "UNSUPPORTED_UML_FEATURE", classifier.id(), "classifierKind",
                        "The classifier kind cannot be mapped by the Increment 2 subset.");
            }
        }

        Map<UUID, List<FieldDeclaration>> fieldsByClassifier = new HashMap<>();
        List<AttributeElement> attributes = snapshot.attributes().stream().sorted(ATTRIBUTE_ORDER).toList();
        for (AttributeElement attribute : attributes) {
            TypeUse type = resolveType(attribute.typeReferenceId(), attribute.id(), "type",
                    typeReferencesById, classifiersById, typeNamesById, diagnostics, false);
            String visibility = normalizedVisibility(attribute.visibility(), attribute.id(), diagnostics);
            MultiplicityBounds bounds = bounds(attribute.multiplicity());
            CollectionIntent collectionIntent = collectionIntent(attribute.multiplicity());
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
                    typeReferencesById, classifiersById, typeNamesById, diagnostics, true);
            String visibility = normalizedVisibility(operation.visibility(), operation.id(), diagnostics);
            List<ParameterDeclaration> parameters = new ArrayList<>();
            List<ParameterElement> operationParameters = parametersByOperation.getOrDefault(operation.id(), List.of())
                    .stream().sorted(PARAMETER_ORDER).toList();
            for (ParameterElement parameter : operationParameters) {
                TypeUse parameterType = resolveType(parameter.typeReferenceId(), parameter.id(), "type",
                        typeReferencesById, classifiersById, typeNamesById, diagnostics, false);
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

    private RelationshipMapping buildRelationships(
            InputSnapshot snapshot,
            Map<UUID, ClassifierElement> classifiersById,
            Map<UUID, TypeName> typeNamesById,
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

            List<RelationshipEndElement> manyEnds = ends.stream().filter(end -> isToMany(end.multiplicity())).toList();
            List<RelationshipEndElement> oneEnds = ends.stream().filter(end -> isToOne(end.multiplicity())).toList();
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
                    oneEnd.multiplicity() != MultiplicityKind.EXACTLY_ONE, inverseCollectionEmitted, lifecycleIntent));

            Set<String> relationshipArtifacts = new TreeSet<>();
            relationshipArtifacts.add("src/main/resources/db/migration/V1__init.sql");
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
                bounds(end.multiplicity()), end.navigability() == NavigationKind.NAVIGABLE,
                end.aggregationKind(), lifecycleIntent(end.aggregationKind()));
    }

    private TypeUse resolveType(
            UUID typeReferenceId,
            UUID elementId,
            String feature,
            Map<UUID, TypeReferenceElement> typeReferencesById,
            Map<UUID, ClassifierElement> classifiersById,
            Map<UUID, TypeName> typeNamesById,
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
            ProvisionalGenerationProfile.TypeMapping mapping =
                    ProvisionalGenerationProfile.primitiveType(reference.primitiveName());
            if (mapping == null) {
                addDiagnostic(diagnostics, "UNRESOLVABLE_TYPE", elementId, feature,
                        "Primitive type '" + reference.primitiveName() + "' has no mapping in the provisional V1 profile.");
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
        return new TypeUse(typeReferenceId, classifier.id(), typeName.sourceName(), javaType, null, true);
    }

    private List<SourceArtifactMapping> buildSourceArtifactMappings(
            InputSnapshot snapshot,
            List<TypeDeclaration> types,
            RelationshipMapping relationshipMapping,
            List<PackageDeclaration> packages) {
        Map<UUID, Set<String>> pathsBySourceId = new TreeMap<>(UUID_ORDER);
        Map<UUID, Set<String>> typePathsById = new HashMap<>();

        for (TypeDeclaration type : types) {
            Set<String> paths = artifactPaths(type);
            typePathsById.put(type.sourceId(), paths);
            addPaths(pathsBySourceId, type.sourceId(), paths);
            for (FieldDeclaration field : type.fields()) {
                addPaths(pathsBySourceId, field.sourceId(), List.of(
                        entityArtifactPath(type), "src/main/resources/db/migration/V1__init.sql"));
                addTypeUseMapping(pathsBySourceId, field.type());
            }
            for (OperationSignature operation : type.operations()) {
                String servicePath = serviceArtifactPath(type);
                addPaths(pathsBySourceId, operation.sourceId(), List.of(servicePath));
                addTypeUseMapping(pathsBySourceId, operation.returnType());
                for (ParameterDeclaration parameter : operation.parameters()) {
                    addPaths(pathsBySourceId, parameter.sourceId(), List.of(servicePath));
                    addTypeUseMapping(pathsBySourceId, parameter.type());
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

    private void addTypeUseMapping(Map<UUID, Set<String>> pathsBySourceId, TypeUse type) {
        if (type.sourceTypeReferenceId() != null) {
            addPaths(pathsBySourceId, type.sourceTypeReferenceId(),
                    List.of("src/main/resources/db/migration/V1__init.sql"));
        }
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

    private MultiplicityBounds bounds(MultiplicityKind multiplicity) {
        if (multiplicity == null || multiplicity == MultiplicityKind.RANGE) {
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

    private CollectionIntent collectionIntent(MultiplicityKind multiplicity) {
        if (isToMany(multiplicity)) {
            return CollectionIntent.COLLECTION;
        }
        if (isToOne(multiplicity)) {
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

    private boolean isToOne(MultiplicityKind multiplicity) {
        return multiplicity == MultiplicityKind.EXACTLY_ONE || multiplicity == MultiplicityKind.ZERO_OR_ONE;
    }

    private boolean isToMany(MultiplicityKind multiplicity) {
        return multiplicity == MultiplicityKind.ONE_OR_MANY || multiplicity == MultiplicityKind.ZERO_OR_MANY;
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

    private record TypeName(String sourceName, String javaName, String javaPackageName) {
    }

    private record RelationshipMapping(
            List<RelationshipDescriptor> relationships,
            Map<UUID, Set<String>> artifactPathsByRelationship) {
    }
}
