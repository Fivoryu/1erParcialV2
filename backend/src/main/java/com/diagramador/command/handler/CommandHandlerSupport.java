package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.diagramador.command.CommandHandler;
import com.diagramador.command.CommandValidationException;
import com.diagramador.command.ErrorCode;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlConstraint;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlOperation;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.TypeReferenceRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlOperationRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Shared payload, target, model-scope, type, and ordinal validation for T3a handlers. */
@Component
public class CommandHandlerSupport {

    private final ObjectMapper objectMapper;
    private final ClassifierRepository classifierRepository;
    private final UmlAttributeRepository attributeRepository;
    private final UmlOperationRepository operationRepository;
    private final OperationParameterRepository parameterRepository;
    private final TypeReferenceRepository typeReferenceRepository;
    private final UmlConstraintRepository constraintRepository;

    public CommandHandlerSupport(ObjectMapper objectMapper, ClassifierRepository classifierRepository,
            UmlAttributeRepository attributeRepository, UmlOperationRepository operationRepository,
            OperationParameterRepository parameterRepository, TypeReferenceRepository typeReferenceRepository,
            UmlConstraintRepository constraintRepository) {
        this.objectMapper = objectMapper;
        this.classifierRepository = classifierRepository;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.parameterRepository = parameterRepository;
        this.typeReferenceRepository = typeReferenceRepository;
        this.constraintRepository = constraintRepository;
    }

    public JsonNode payload(CommandHandler.Context context, Set<String> allowed) {
        JsonNode payload = objectMapper.valueToTree(context.envelope().payload());
        if (payload == null || !payload.isObject()) {
            invalid("Payload must be a JSON object");
        }
        Set<String> unknown = new HashSet<>();
        payload.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            invalid("Payload contains unsupported field(s): " + unknown);
        }
        return payload;
    }

    public void noPayload(CommandHandler.Context context) {
        if (context.envelope().payload() == null) {
            return;
        }
        JsonNode payload = objectMapper.valueToTree(context.envelope().payload());
        if (payload == null || !payload.isObject() || !payload.isEmpty()) {
            invalid("This command does not accept a payload");
        }
    }

    public void requireFields(JsonNode payload, String... fields) {
        for (String field : fields) {
            if (!payload.hasNonNull(field)) {
                invalid("Payload field '" + field + "' is required");
            }
        }
    }

    public UUID uuid(JsonNode payload, String field) {
        String value = text(payload, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            invalid("Payload field '" + field + "' must be a UUID");
            return null;
        }
    }

    public UUID optionalUuid(JsonNode payload, String field) {
        if (!payload.has(field) || payload.get(field).isNull()) {
            return null;
        }
        return uuid(payload, field);
    }

    public String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            invalid("Payload field '" + field + "' must be a non-blank string");
        }
        return value.asText();
    }

    public String optionalText(JsonNode payload, String field) {
        if (!payload.has(field) || payload.get(field).isNull()) {
            return null;
        }
        JsonNode value = payload.get(field);
        if (!value.isTextual()) {
            invalid("Payload field '" + field + "' must be a string or null");
        }
        return value.asText();
    }

    public boolean bool(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isBoolean()) {
            invalid("Payload field '" + field + "' must be boolean");
        }
        return value.asBoolean();
    }

    public int ordinal(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.canConvertToInt() || value.asInt() < 0) {
            invalid("Payload field '" + field + "' must be a non-negative integer");
        }
        return value.asInt();
    }

    public int ordinalOrNext(JsonNode payload, String field, List<Integer> used) {
        if (payload.has(field) && !payload.get(field).isNull()) {
            int requested = ordinal(payload, field);
            if (used.contains(requested)) {
                precondition("ordinal " + requested + " is already taken");
            }
            return requested;
        }
        return used.stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
    }

    public String visibility(JsonNode payload, String field, String defaultValue) {
        String value = payload.has(field) && !payload.get(field).isNull()
                ? text(payload, field).toUpperCase(Locale.ROOT)
                : defaultValue;
        if (value.equals("PROTECTED")) {
            precondition("Visibility PROTECTED is unsupported: BL-VIS-03 and GAP-06");
        }
        if (value.equals("PACKAGE")) {
            precondition("Visibility PACKAGE is unsupported: BL-VIS-04 and GAP-06");
        }
        if (!value.equals("PUBLIC") && !value.equals("PRIVATE")) {
            invalid("Unsupported visibility '" + value + "'");
        }
        return value;
    }

    public MultiplicityKind multiplicity(JsonNode payload, String field, MultiplicityKind defaultValue) {
        String value = payload.has(field) && !payload.get(field).isNull()
                ? text(payload, field).toUpperCase(Locale.ROOT)
                : defaultValue.name();
        if (value.equals("RANGE")) {
            precondition("Multiplicity RANGE is unsupported: BL-REL-07 and GAP-05");
        }
        try {
            return MultiplicityKind.valueOf(value);
        } catch (IllegalArgumentException exception) {
            invalid("Unsupported multiplicity '" + value + "'");
            return null;
        }
    }

    public ClassifierKind classifierKind(JsonNode payload) {
        String value = text(payload, "kind").toUpperCase(Locale.ROOT);
        if (value.equals("ENUMERATION")) {
            precondition("Classifier kind ENUMERATION is unsupported: BL-CLS-04 and GAP-02");
        }
        try {
            return ClassifierKind.valueOf(value);
        } catch (IllegalArgumentException exception) {
            invalid("Unsupported classifier kind '" + value + "'");
            return null;
        }
    }

    public TargetType ownerType(JsonNode payload) {
        String value = text(payload, "ownerType").toUpperCase(Locale.ROOT);
        if (!value.equals("CLASSIFIER")) {
            precondition("Constraint owner type '" + value + "' is unsupported in this increment");
        }
        return TargetType.CLASSIFIER;
    }

    public UUID targetId(CommandHandler.Context context, String expectedScope) {
        if (context.envelope().targets().size() != 1 || context.envelope().targets().get(0) == null
                || context.envelope().targets().get(0).id() == null) {
            invalid("Exactly one target with an id is required for " + context.envelope().type());
        }
        String actualScope = context.envelope().targets().get(0).scope();
        if (actualScope == null || !actualScope.equalsIgnoreCase(expectedScope)) {
            invalid("Target scope must be " + expectedScope);
        }
        return context.envelope().targets().get(0).id();
    }

    public Classifier classifier(CommandHandler.Context context, String scope) {
        UUID id = targetId(context, scope);
        Classifier classifier = classifierRepository.findById(id).orElse(null);
        if (classifier == null || !classifier.getModel().getId().equals(context.model().getId())) {
            precondition("Target classifier " + id + " does not belong to model " + context.model().getId());
        }
        return classifier;
    }

    public UmlAttribute attribute(CommandHandler.Context context) {
        UUID id = targetId(context, "attribute");
        UmlAttribute attribute = attributeRepository.findById(id).orElse(null);
        if (attribute == null || !attribute.getClassifier().getModel().getId().equals(context.model().getId())) {
            precondition("Target attribute " + id + " does not belong to model " + context.model().getId());
        }
        return attribute;
    }

    public UmlOperation operation(CommandHandler.Context context) {
        UUID id = targetId(context, "operation");
        UmlOperation operation = operationRepository.findById(id).orElse(null);
        if (operation == null || !operation.getClassifier().getModel().getId().equals(context.model().getId())) {
            precondition("Target operation " + id + " does not belong to model " + context.model().getId());
        }
        return operation;
    }

    public OperationParameter parameter(CommandHandler.Context context) {
        UUID id = targetId(context, "parameter");
        OperationParameter parameter = parameterRepository.findById(id).orElse(null);
        if (parameter == null || !parameter.getOperation().getClassifier().getModel().getId().equals(context.model().getId())) {
            precondition("Target parameter " + id + " does not belong to model " + context.model().getId());
        }
        return parameter;
    }

    public UmlConstraint constraint(CommandHandler.Context context) {
        UUID id = targetId(context, "constraint");
        UmlConstraint constraint = constraintRepository.findById(id).orElse(null);
        if (constraint == null || !constraint.getModel().getId().equals(context.model().getId())) {
            precondition("Target constraint " + id + " does not belong to model " + context.model().getId());
        }
        return constraint;
    }

    public Classifier namespace(UmlModel model, UUID namespaceId) {
        if (namespaceId == null) {
            return null;
        }
        Classifier namespace = classifierRepository.findById(namespaceId).orElse(null);
        if (namespace == null || !namespace.getModel().getId().equals(model.getId())) {
            precondition("Namespace " + namespaceId + " does not belong to model " + model.getId());
        }
        return namespace;
    }

    public void ensureClassifierNameAvailable(UmlModel model, Classifier namespace, String name, UUID excludedId) {
        Optional<Classifier> collision = classifierRepository.findByModelId(model.getId()).stream()
                .filter(candidate -> !Objects.equals(candidate.getId(), excludedId))
                .filter(candidate -> Objects.equals(id(candidate.getNamespace()), id(namespace)))
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst();
        if (collision.isPresent()) {
            Classifier existing = collision.get();
            precondition("Classifier name '" + name + "' collides with existing classifier '"
                    + existing.getName() + "' (id " + existing.getId() + ")");
        }
    }

    public TypeReference typeReference(CommandHandler.Context context, JsonNode type) {
        if (type == null || !type.isObject()) {
            invalid("Type must be an object with exactly one of primitiveName or classifierId");
        }
        boolean primitive = type.hasNonNull("primitiveName");
        boolean classifier = type.hasNonNull("classifierId");
        if (primitive == classifier || type.size() != 1) {
            invalid("Type must contain exactly one of primitiveName or classifierId");
        }
        if (primitive) {
            String primitiveName = type.get("primitiveName").isTextual() ? type.get("primitiveName").asText() : "";
            if (primitiveName.isBlank()) {
                invalid("Type primitiveName must be a non-blank string");
            }
            return typeReferenceRepository.findFirstByModelIdAndPrimitiveName(context.model().getId(), primitiveName)
                    .orElseGet(() -> typeReferenceRepository.save(TypeReference.forPrimitive(context.model(), primitiveName)));
        }
        UUID classifierId;
        try {
            classifierId = UUID.fromString(type.get("classifierId").asText());
        } catch (IllegalArgumentException exception) {
            invalid("Type classifierId must be a UUID");
            return null;
        }
        Classifier referenced = classifierRepository.findById(classifierId).orElse(null);
        if (referenced == null || !referenced.getModel().getId().equals(context.model().getId())) {
            precondition("Type classifierId " + classifierId + " does not resolve in model "
                    + context.model().getId());
        }
        return typeReferenceRepository.findFirstByModelIdAndClassifierId(context.model().getId(), classifierId)
                .orElseGet(() -> typeReferenceRepository.save(TypeReference.forClassifier(context.model(), referenced)));
    }

    public void duplicateId(boolean exists, UUID id, String element) {
        if (exists) {
            precondition("The " + element + " id " + id + " already exists");
        }
    }

    public void insertClassifier(CommandHandler.Context context, UUID id, Classifier namespace, ClassifierKind kind,
            String name, boolean isAbstract) {
        context.entityManager().createNativeQuery("""
                INSERT INTO diagrammer.classifier
                    (classifier_id, model_id, namespace_id, kind, name, is_abstract, created_at)
                VALUES (CAST(:id AS uuid), CAST(:modelId AS uuid), CAST(:namespaceId AS uuid),
                    CAST(:kind AS diagrammer.classifier_kind), :name, :isAbstract, now())
                """)
                .setParameter("id", id)
                .setParameter("modelId", context.model().getId())
                .setParameter("namespaceId", id(namespace))
                .setParameter("kind", kind.name())
                .setParameter("name", name)
                .setParameter("isAbstract", isAbstract)
                .executeUpdate();
    }

    public void insertAttribute(CommandHandler.Context context, UUID id, Classifier classifier,
            TypeReference type, String name, String visibility, MultiplicityKind multiplicity, String defaultValue,
            int ordinal) {
        context.entityManager().createNativeQuery("""
                INSERT INTO diagrammer.uml_attribute
                    (attribute_id, classifier_id, type_reference_id, name, kind, visibility, multiplicity,
                     default_value, ordinal, created_at)
                VALUES (CAST(:id AS uuid), CAST(:classifierId AS uuid), CAST(:typeReferenceId AS uuid), :name,
                    'ATTRIBUTE', :visibility, CAST(:multiplicity AS diagrammer.multiplicity_kind),
                    :defaultValue, :ordinal, now())
                """)
                .setParameter("id", id)
                .setParameter("classifierId", classifier.getId())
                .setParameter("typeReferenceId", id(type))
                .setParameter("name", name)
                .setParameter("visibility", visibility)
                .setParameter("multiplicity", multiplicity.name())
                .setParameter("defaultValue", defaultValue)
                .setParameter("ordinal", ordinal)
                .executeUpdate();
    }

    public void insertOperation(CommandHandler.Context context, UUID id, Classifier classifier, String name,
            TypeReference returnType, String visibility, boolean isAbstract, boolean isStatic, int ordinal) {
        context.entityManager().createNativeQuery("""
                INSERT INTO diagrammer.uml_operation
                    (operation_id, classifier_id, name, return_type_reference_id, visibility, is_abstract,
                     is_static, ordinal, created_at)
                VALUES (CAST(:id AS uuid), CAST(:classifierId AS uuid), :name, CAST(:returnTypeId AS uuid),
                    :visibility, :isAbstract, :isStatic, :ordinal, now())
                """)
                .setParameter("id", id)
                .setParameter("classifierId", classifier.getId())
                .setParameter("name", name)
                .setParameter("returnTypeId", id(returnType))
                .setParameter("visibility", visibility)
                .setParameter("isAbstract", isAbstract)
                .setParameter("isStatic", isStatic)
                .setParameter("ordinal", ordinal)
                .executeUpdate();
    }

    public void insertParameter(CommandHandler.Context context, UUID id, UmlOperation operation,
            TypeReference type, String name, String direction, int ordinal) {
        context.entityManager().createNativeQuery("""
                INSERT INTO diagrammer.operation_parameter
                    (parameter_id, operation_id, type_reference_id, name, direction, ordinal)
                VALUES (CAST(:id AS uuid), CAST(:operationId AS uuid), CAST(:typeReferenceId AS uuid),
                    :name, :direction, :ordinal)
                """)
                .setParameter("id", id)
                .setParameter("operationId", operation.getId())
                .setParameter("typeReferenceId", id(type))
                .setParameter("name", name)
                .setParameter("direction", direction)
                .setParameter("ordinal", ordinal)
                .executeUpdate();
    }

    public void insertConstraint(CommandHandler.Context context, UUID id, TargetType ownerType, UUID ownerId,
            String expression, String severity) {
        context.entityManager().createNativeQuery("""
                INSERT INTO diagrammer.uml_constraint
                    (constraint_id, model_id, owner_type, owner_id, expression, severity, created_at)
                VALUES (CAST(:id AS uuid), CAST(:modelId AS uuid), CAST(:ownerType AS diagrammer.target_type),
                    CAST(:ownerId AS uuid), :expression, CAST(:severity AS diagrammer.severity_kind), now())
                """)
                .setParameter("id", id)
                .setParameter("modelId", context.model().getId())
                .setParameter("ownerType", ownerType.name())
                .setParameter("ownerId", ownerId)
                .setParameter("expression", expression)
                .setParameter("severity", severity)
                .executeUpdate();
    }

    public static void invalid(String message) {
        throw new CommandValidationException(ErrorCode.INVALID_PAYLOAD, message);
    }

    public static void precondition(String message) {
        throw new CommandValidationException(ErrorCode.PRECONDITION_FAILED, message);
    }

    private static UUID id(Classifier classifier) {
        return classifier == null ? null : classifier.getId();
    }

    private static UUID id(TypeReference reference) {
        return reference == null ? null : reference.getId();
    }
}
