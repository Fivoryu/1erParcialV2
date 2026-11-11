package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.domain.ConnectorPath;
import com.diagramador.domain.ElementGeometry;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlConstraint;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.ConnectorPathRepository;
import com.diagramador.persistence.ElementGeometryRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlNoteLinkRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Deletes a relationship's semantic ends and view-specific connector rows as one cascade. */
@Component
public class RelationshipDeleteHandler implements CommandHandler.DeleteAction {

    private final CommandHandlerSupport support;
    private final UmlRelationshipRepository relationshipRepository;
    private final RelationshipEndRepository endRepository;
    private final ConnectorPathRepository connectorPathRepository;
    private final ElementGeometryRepository geometryRepository;
    private final UmlConstraintRepository constraintRepository;
    private final UmlNoteLinkRepository noteLinkRepository;

    public RelationshipDeleteHandler(CommandHandlerSupport support, UmlRelationshipRepository relationshipRepository,
            RelationshipEndRepository endRepository, ConnectorPathRepository connectorPathRepository,
            ElementGeometryRepository geometryRepository, UmlConstraintRepository constraintRepository,
            UmlNoteLinkRepository noteLinkRepository) {
        this.support = support;
        this.relationshipRepository = relationshipRepository;
        this.endRepository = endRepository;
        this.connectorPathRepository = connectorPathRepository;
        this.geometryRepository = geometryRepository;
        this.constraintRepository = constraintRepository;
        this.noteLinkRepository = noteLinkRepository;
    }

    @Override
    public String type() {
        return "relationship.delete";
    }

    @Override
    public Outcome apply(Context context) {
        support.noPayload(context);
        UmlRelationship relationship = support.relationship(context, "relationship");
        List<RelationshipEnd> ends = endRepository.findByRelationshipId(relationship.getId());
        if (ends.size() != 2) {
            support.precondition("Relationship " + relationship.getId() + " must have exactly two ends before deletion");
        }
        List<ConnectorPath> paths = connectorPathRepository.findByRelationshipId(relationship.getId());
        List<UmlConstraint> constraints = constraintRepository.findByModelId(context.model().getId()).stream()
                .filter(constraint -> constraint.getOwnerType() == TargetType.RELATIONSHIP
                        && relationship.getId().equals(constraint.getOwnerId()))
                .toList();
        List<ElementGeometry> relationshipGeometry = geometryRepository.findByViewModelId(context.model().getId()).stream()
                .filter(geometry -> geometry.getTargetType() == TargetType.RELATIONSHIP
                        && geometry.getTargetId().equals(relationship.getId()))
                .toList();

        // Notes that annotated the relationship lose that link, not the note.
        noteLinkRepository.deleteAll(noteLinkRepository.findByKeyTargetIdIn(List.of(relationship.getId())));
        connectorPathRepository.deleteAll(paths);
        geometryRepository.deleteAll(relationshipGeometry);
        constraintRepository.deleteAll(constraints);
        endRepository.deleteAll(ends);
        relationshipRepository.delete(relationship);

        List<DeletedTarget> deleted = new ArrayList<>();
        deleted.add(new DeletedTarget(TargetType.RELATIONSHIP, relationship.getId()));
        ends.forEach(end -> deleted.add(new DeletedTarget(TargetType.RELATIONSHIP_END, end.getId())));
        constraints.forEach(constraint -> deleted.add(new DeletedTarget(TargetType.CONSTRAINT, constraint.getId())));
        return new Outcome(deleted);
    }
}
